"""
向量化器：把知识条目切成"检索单元"，调用 Embedding 接口生成向量，落盘供后端启动时加载。

关键设计：向量只在这里算一次（构建期），后端运行期不再调用 Embedding 接口。
好处是现场即使断网，语义检索照样工作；只有用户新输入需要实时向量化时才降级到 BM25。

用法：
  set EMBEDDING_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
  set EMBEDDING_API_KEY=sk-xxx
  set EMBEDDING_MODEL=text-embedding-v3
  python embed.py
  python embed.py --dim 768      # 想省内存可降到 768 或 512
"""
import argparse
import hashlib
import json
import math
import os
import urllib.error
import urllib.request
from pathlib import Path
from storage import atomic_jsonl

ROOT = Path(__file__).resolve().parent.parent
KB = ROOT / "data" / "kb" / "deficiency_kb.jsonl"
OUT = ROOT / "data" / "vectors" / "vectors.jsonl"


def chunk_text(e: dict) -> str:
    """把一条知识拼成检索单元。前缀带上元素、作物、叶龄，显著提升短查询召回率。"""
    head = f"{e['elementName']} 缺素症 {e.get('leafAge', '')} {'、'.join(e.get('crops', []))}"
    sym = "；".join(e.get("colorPattern", []) + e.get("morphology", []) + e.get("symptoms", []))
    plan = "；".join(
        [x.get("measure", "") + x.get("dosage", "") for x in e.get("organicPlan", [])]
        + [x.get("fertilizer", "") + x.get("dosage", "") for x in e.get("chemicalPlan", [])]
    )
    return f"{head} 症状：{sym}。简介：{e.get('intro', '')} 施肥：{plan}"


def embed_batch(texts: list[str], base_url: str, key: str, model: str, dim: int) -> list[list[float]]:
    """只用标准库发请求，这个脚本不依赖 requests，换台机器也能直接跑。"""
    url = base_url.rstrip("/") + "/embeddings"
    payload = {"model": model, "input": texts, "encoding_format": "float"}
    if dim:
        payload["dimensions"] = dim
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=body, method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", f"Bearer {key}")
    try:
        with urllib.request.urlopen(req, timeout=40) as resp:
            data = json.loads(resp.read().decode("utf-8"))["data"]
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"Embedding provider returned HTTP {e.code}") from None
    if not isinstance(data, list) or sorted(d.get("index", -1) for d in data) != list(range(len(texts))):
        raise ValueError("Embedding response must contain one unique index per input")
    data.sort(key=lambda x: x["index"])
    return [d["embedding"] for d in data]


def build_vectors(entries, embed_fn, output=OUT, model="", dim=1024, batch_size=8):
    """Reuse only matching model/text vectors; replace the published index after every batch succeeds."""
    if batch_size < 1 or dim < 0:
        raise ValueError("Invalid embedding batch size or dimension")
    output = Path(output)
    cache = {}
    if output.exists():
        for line in output.read_text(encoding="utf-8").splitlines():
            if line.strip():
                item = json.loads(line)
                cache[item.get("chunkId")] = item
    rows, missing = [], []
    expected_dim = dim or None

    def validate(vector):
        nonlocal expected_dim
        if not isinstance(vector, list) or not vector or any(isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v) for v in vector):
            raise ValueError("Embedding vector must contain finite numbers")
        if not any(v != 0 for v in vector):
            raise ValueError("Embedding vector cannot be all zero")
        expected_dim = expected_dim or len(vector)
        if len(vector) != expected_dim:
            raise ValueError("Inconsistent embedding dimensions")

    seen = set()
    for entry in entries:
        if entry.get("needsReview") is True:
            continue
        identity = entry["id"]
        if identity in seen:
            raise ValueError("Duplicate knowledge ID")
        seen.add(identity)
        text = chunk_text(entry)
        digest = hashlib.sha256(text.encode("utf-8")).hexdigest()
        row = {"chunkId": identity, "element": entry["element"], "text": text,
               "meta": {"sourceUrl": entry.get("sourceUrl"), "publisher": entry.get("publisher")},
               "model": model, "textHash": digest}
        old = cache.get(identity, {})
        if old.get("model") == model and old.get("textHash") == digest:
            try:
                validate(old.get("vector"))
                row["vector"] = old["vector"]
            except ValueError:
                missing.append(row)
        else:
            missing.append(row)
        rows.append(row)
    for offset in range(0, len(missing), batch_size):
        batch = missing[offset:offset + batch_size]
        vectors = embed_fn([row["text"] for row in batch])
        if not isinstance(vectors, list) or len(vectors) != len(batch):
            raise ValueError("Embedding response cardinality mismatch")
        for row, vector in zip(batch, vectors):
            validate(vector)
            row["vector"] = vector
    atomic_jsonl(output, rows)
    return {"total": len(rows), "embedded": len(missing), "reused": len(rows) - len(missing)}


def load_dotenv() -> None:
    """读取 crawler/.env（不入库），省得每次开新终端都要重设环境变量。"""
    p = Path(__file__).resolve().parent / ".env"
    if not p.exists():
        return
    for line in p.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        os.environ.setdefault(k.strip(), v.strip().strip('"').strip("'"))


def main() -> None:
    load_dotenv()
    ap = argparse.ArgumentParser()
    ap.add_argument("--dim", type=int, default=1024, help="向量维度，1024/768/512")
    ap.add_argument("--batch", type=int, default=8)
    ap.add_argument("--base-url", default=None, help="覆盖 EMBEDDING_BASE_URL")
    ap.add_argument("--api-key", default=None, help="覆盖 EMBEDDING_API_KEY")
    ap.add_argument("--model", default=None, help="覆盖 EMBEDDING_MODEL")
    ap.add_argument("--probe", action="store_true", help="只测连通性，不写文件")
    args = ap.parse_args()

    base_url = args.base_url or os.getenv("EMBEDDING_BASE_URL")
    key = args.api_key or os.getenv("EMBEDDING_API_KEY")
    model = args.model or os.getenv("EMBEDDING_MODEL", "text-embedding-v3")
    if not base_url or not key:
        raise SystemExit(
            "缺少配置。三种给法任选其一：\n"
            "  1) 环境变量 EMBEDDING_BASE_URL / EMBEDDING_API_KEY\n"
            "  2) 命令行 --base-url ... --api-key ...\n"
            "  3) 写进 crawler/.env（已在 .gitignore 里）\n"
            "常用地址：\n"
            "  通义  https://dashscope.aliyuncs.com/compatible-mode/v1   text-embedding-v3\n"
            "  智谱  https://open.bigmodel.cn/api/paas/v4               embedding-3\n"
            "  硅基  https://api.siliconflow.cn/v1                      BAAI/bge-m3"
        )

    if args.probe:
        try:
            v = embed_batch(["连通性测试"], base_url, key, model, args.dim)[0]
            print(f"连通性 OK：{model} 返回 {len(v)} 维向量")
        except Exception as ex:
            raise SystemExit(f"连通性失败：{ex}")
        return

    entries = [json.loads(l) for l in KB.read_text(encoding="utf-8").splitlines() if l.strip()]
    print(f"知识条目 {len(entries)} 条，模型 {model}，维度 {args.dim}")

    stats = build_vectors(entries, lambda texts: embed_batch(texts, base_url, key, model, args.dim),
                          OUT, model, args.dim, args.batch)
    print(f"\n完成：{stats} -> {OUT}")
    print("后端启动时会自动加载该文件构建内存索引。")


if __name__ == "__main__":
    main()
