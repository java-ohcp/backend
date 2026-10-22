"""
清洗器：把 data/raw 下的原始 HTML 变成干净正文，并按内容指纹去重。

处理链：去脚本样式 -> 去导航/页脚/侧栏 -> 提取正文容器 -> 分句归一 -> SimHash 去重
产出：crawler/out/clean.jsonl，每行 {url, title, text, sentences, simhash, crawledAt}
"""
import hashlib
import json
import re
from pathlib import Path

from bs4 import BeautifulSoup
from storage import atomic_jsonl

ROOT = Path(__file__).resolve().parent.parent
RAW_DIR = ROOT / "data" / "raw"
OUT = Path(__file__).resolve().parent / "out" / "clean.jsonl"

DROP_TAGS = ["script", "style", "nav", "footer", "header", "form", "iframe", "noscript", "aside"]
NOISE = re.compile(
    r"(版权所有|京ICP备|网站地图|联系我们|主办单位|技术支持|无障碍|打印本页|分享到|"
    r"上一篇|下一篇|返回顶部|登录|注册|扫码|微信公众号)"
)


def norm(text: str) -> str:
    text = text.replace("\u3000", " ").replace("\xa0", " ")
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n{2,}", "\n", text)
    return text.strip()


def extract(html: str) -> tuple[str, str]:
    soup = BeautifulSoup(html, "lxml")
    for t in soup(DROP_TAGS):
        t.decompose()

    title = " ".join((soup.title.get_text(strip=True) if soup.title else "").split())

    # 优先取语义化正文容器，找不到就退化为全 body
    node = None
    for sel in ["article", "div.content", "div.article", "div.TRS_Editor", "div#content", "main"]:
        node = soup.select_one(sel)
        if node:
            break
    if node is None:
        node = soup.body or soup

    lines = []
    for p in node.find_all(["p", "li", "h2", "h3", "h4"]):
        s = norm(p.get_text(" ", strip=True))
        if len(s) < (2 if p.name.startswith("h") else 8) or NOISE.search(s):
            continue
        lines.append(s)
    if not lines:
        lines = [norm(line) for line in node.get_text("\n", strip=True).splitlines()
                 if len(norm(line)) >= 8 and not NOISE.search(line)]
    return title, norm("\n".join(dict.fromkeys(lines)))


def simhash(text: str) -> int:
    """64 位 SimHash：字符 2-gram 加权，用于近似去重。"""
    v = [0] * 64
    grams = [text[i:i + 2] for i in range(len(text) - 1)]
    for g in grams:
        h = int(hashlib.md5(g.encode("utf-8")).hexdigest()[:16], 16)
        for i in range(64):
            v[i] += 1 if (h >> i) & 1 else -1
    out = 0
    for i in range(64):
        if v[i] > 0:
            out |= (1 << i)
    return out


def hamming(a: int, b: int) -> int:
    return bin(a ^ b).count("1")


def main() -> None:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    seen: list[tuple[int, str]] = []
    kept = dup = 0

    records = []
    for meta_path in sorted(RAW_DIR.glob("*.meta.json")):
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        html_path = meta_path.with_name(meta_path.name.replace(".meta.json", ".html"))
        if not html_path.exists():
            continue

        title, text = extract(html_path.read_text(encoding="utf-8"))
        if len(text) < 200:
            continue

        sh = simhash(text)
        if any(hamming(sh, old) <= 3 for old, _ in seen):
            dup += 1
            continue
        seen.append((sh, meta["url"]))

        sentences = [s for s in re.split(r"[。；！？\n]", text) if len(s) >= 10]
        records.append({
            "url": meta["url"], "title": title, "text": text, "sentences": sentences,
            "simhash": str(sh), "crawledAt": meta.get("crawledAt"), "contentHash": meta.get("contentHash"),
        })
        kept += 1
        print(f"[clean] {title[:30] or meta['url']}  {len(text)} 字")

    atomic_jsonl(OUT, records)
    print(f"\n保留 {kept} 篇，去重丢弃 {dup} 篇 -> {OUT}")


if __name__ == "__main__":
    main()
