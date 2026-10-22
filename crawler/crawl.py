"""Bounded, robots-aware public-article crawler with conditional requests and provenance."""
import argparse
import hashlib
import json
import time
from collections import deque
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urldefrag, urljoin, urlsplit, urlunsplit
from urllib.robotparser import RobotFileParser

import requests
from bs4 import BeautifulSoup, UnicodeDammit
from storage import atomic_text

ROOT = Path(__file__).resolve().parent.parent
RAW_DIR = ROOT / "data" / "raw"
SEEDS = Path(__file__).with_name("seeds.txt")
UA = "OHCP-CropAgent-Crawler/1.1 (+public agricultural research)"
MAX_BYTES = 2 * 1024 * 1024


def canonical_url(url: str) -> str:
    p = urlsplit(urldefrag(url.strip())[0])
    if p.scheme not in ("http", "https") or not p.hostname or p.username or p.password:
        raise ValueError("Only public HTTP(S) URLs without credentials are supported")
    if p.port not in (None, 80, 443):
        raise ValueError("Only standard HTTP(S) ports are supported")
    host = p.hostname.lower()
    return urlunsplit((p.scheme.lower(), host, p.path or "/", p.query, ""))


def url_key(url: str) -> str:
    # Preserve the existing raw-file keys for incremental upgrades.
    return hashlib.md5(url.encode("utf-8")).hexdigest()[:16]


def same_domain_links(html: str, base: str, limit: int = 10) -> list[str]:
    host = urlsplit(base).hostname
    out, seen = [], set()
    for anchor in BeautifulSoup(html, "lxml").find_all("a", href=True):
        try:
            url = canonical_url(urljoin(base, anchor["href"]))
        except (ValueError, TypeError):
            continue
        if urlsplit(url).hostname != host or url in seen or url == base:
            continue
        if urlsplit(url).path.lower().endswith((".jpg", ".png", ".pdf", ".zip", ".mp4", ".docx")):
            continue
        seen.add(url)
        out.append(url)
        if len(out) >= limit:
            break
    return out


class Crawler:
    def __init__(self, output: Path = RAW_DIR, session=None, sleep=time.sleep, clock=time.monotonic):
        self.output = Path(output)
        self.session = session or requests.Session()
        self.sleep, self.clock = sleep, clock
        self.last_request = {}
        self.robots = {}
        self.delays = {}

    def _pace(self, host: str):
        delay = max(1.2, self.delays.get(host, 0))
        elapsed = self.clock() - self.last_request.get(host, float("-inf"))
        if elapsed < delay:
            self.sleep(delay - elapsed)
        self.last_request[host] = self.clock()

    def _request(self, url: str, headers=None):
        host = urlsplit(url).hostname
        for redirect in range(6):
            for attempt in range(3):
                self._pace(host)
                try:
                    with self.session.get(url, headers={"User-Agent": UA, **(headers or {})},
                                          timeout=(5, 15), stream=True, allow_redirects=False) as response:
                        status, response_headers = response.status_code, requests.structures.CaseInsensitiveDict(response.headers)
                        chunks, size = [], 0
                        for chunk in response.iter_content(65536):
                            size += len(chunk)
                            if size > MAX_BYTES:
                                raise ValueError("Response exceeds 2 MiB")
                            chunks.append(chunk)
                        body = b"".join(chunks)
                    if status == 429 or status >= 500:
                        if attempt == 2:
                            raise ValueError(f"HTTP {status} after retries")
                        retry_after = response_headers.get("Retry-After", "")
                        delay = min(10, float(retry_after)) if retry_after.isdigit() else 2 ** attempt
                        self.sleep(delay)
                        continue
                    break
                except requests.RequestException:
                    if attempt == 2:
                        raise
                    self.sleep(2 ** attempt)
            if status in (301, 302, 303, 307, 308):
                target = canonical_url(urljoin(url, response_headers.get("Location", "")))
                if urlsplit(target).hostname != host:
                    raise ValueError("Cross-host redirect refused")
                # A redirect to another article must also respect its robots policy.
                if not urlsplit(url).path.endswith("/robots.txt") and not self.allowed(target):
                    raise ValueError("Redirect target disallowed by robots.txt")
                url = target
                headers = None
                continue
            return url, status, response_headers, body
        raise ValueError("Too many redirects")

    def allowed(self, url: str) -> bool:
        p = urlsplit(url)
        origin = f"{p.scheme}://{p.netloc}"
        if origin not in self.robots:
            robot = RobotFileParser(origin + "/robots.txt")
            _, status, _, body = self._request(origin + "/robots.txt")
            if status == 404:
                robot.parse([])
            elif status == 200:
                robot.parse(body.decode("utf-8", "replace").splitlines())
            else:
                robot.parse(["User-agent: *", "Disallow: /"])
            self.robots[origin] = robot
            self.delays[p.hostname] = max(1.2, robot.crawl_delay(UA) or robot.crawl_delay("*") or 0)
        return self.robots[origin].can_fetch(UA, url)

    def fetch(self, raw_url: str):
        url = canonical_url(raw_url)
        if not self.allowed(url):
            return {"url": url, "status": "robots_blocked"}, None
        key = url_key(url)
        meta_path, html_path = self.output / f"{key}.meta.json", self.output / f"{key}.html"
        previous = {}
        if meta_path.exists() and html_path.exists():
            try:
                previous = json.loads(meta_path.read_text(encoding="utf-8"))
            except (ValueError, OSError):
                pass
        headers = {"Accept": "text/html,application/xhtml+xml"}
        if previous.get("etag"):
            headers["If-None-Match"] = previous["etag"]
        if previous.get("lastModified"):
            headers["If-Modified-Since"] = previous["lastModified"]
        final_url, status, response_headers, body = self._request(url, headers)
        checked_at = datetime.now(timezone.utc).isoformat()
        if status == 304 and previous:
            previous["checkedAt"] = checked_at
            atomic_text(meta_path, json.dumps(previous, ensure_ascii=False, indent=2))
            return {"url": url, "finalUrl": previous.get("url", url), "status": "not_modified"}, html_path.read_text(encoding="utf-8")
        if status != 200:
            raise ValueError(f"HTTP {status}")
        content_type = response_headers.get("Content-Type", "").lower()
        if "html" not in content_type:
            raise ValueError("Response is not HTML")
        html = UnicodeDammit(body, is_html=True).unicode_markup
        if not html:
            raise ValueError("Empty or undecodable HTML")
        digest = hashlib.sha256(html.encode("utf-8")).hexdigest()
        changed = digest != previous.get("contentHash")
        if changed:
            atomic_text(html_path, html)
        metadata = {"url": final_url, "requestedUrl": url, "crawledAt": checked_at if changed else previous.get("crawledAt", checked_at),
                    "checkedAt": checked_at, "bytes": len(body), "contentHash": digest,
                    "etag": response_headers.get("ETag"), "lastModified": response_headers.get("Last-Modified")}
        atomic_text(meta_path, json.dumps(metadata, ensure_ascii=False, indent=2))
        return {"url": url, "finalUrl": final_url, "status": "fetched" if changed else "unchanged", "contentHash": digest}, html

    def run(self, urls: list[str], depth: int = 0, max_pages: int = 20):
        if not 0 <= depth <= 2 or not 1 <= max_pages <= 200:
            raise ValueError("depth must be 0–2 and max_pages must be 1–200")
        queue = deque((canonical_url(url), 0) for url in urls)
        visited, report = set(), []
        while queue and len(visited) < max_pages:
            url, level = queue.popleft()
            if url in visited:
                continue
            visited.add(url)
            try:
                result, html = self.fetch(url)
                if html and level < depth:
                    queue.extend((child, level + 1) for child in same_domain_links(html, result.get("finalUrl", url)))
            except (requests.RequestException, ValueError, OSError) as error:
                result = {"url": url, "status": "failed", "error": type(error).__name__,
                          "detail": str(error) if isinstance(error, ValueError) else "Network or local storage request failed"}
            report.append(result)
            print(f"[{result['status']}] {url}")
        atomic_text(self.output / "run_report.json", json.dumps(report, ensure_ascii=False, indent=2))
        return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", action="append", help="Public article URL; repeatable")
    parser.add_argument("--depth", type=int, default=0, choices=range(3))
    parser.add_argument("--max-pages", type=int, default=20)
    args = parser.parse_args()
    urls = args.url or [line.strip() for line in SEEDS.read_text(encoding="utf-8").splitlines()
                        if line.strip() and not line.lstrip().startswith("#")]
    report = Crawler().run(urls, args.depth, args.max_pages)
    print(f"Completed {len(report)} pages; details: {RAW_DIR / 'run_report.json'}")
    if not any(item["status"] in ("fetched", "unchanged", "not_modified") for item in report):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
