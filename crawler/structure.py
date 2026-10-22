"""Extract source-grounded nutrient candidates, then atomically merge only reviewed complete entries."""
import argparse
import hashlib
import json
import re
from datetime import datetime
from pathlib import Path
from urllib.parse import urlsplit
from storage import atomic_jsonl

ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = Path(__file__).resolve().parent / "out"
CAND = OUT_DIR / "candidates.jsonl"
KB = ROOT / "data" / "kb" / "deficiency_kb.jsonl"
ELEMENTS = {"氮": "N", "磷": "P", "钾": "K", "镁": "Mg", "铁": "Fe", "锌": "Zn",
            "钙": "Ca", "硼": "B", "硫": "S", "锰": "Mn", "铜": "Cu", "钼": "Mo"}
CROPS = ["水稻", "小麦", "玉米", "大豆", "棉花", "油菜", "番茄", "黄瓜", "白菜",
         "马铃薯", "苹果", "柑橘", "葡萄", "茶叶", "花生", "甘蔗"]
SYMPTOM_WORDS = ["黄化", "发黄", "褪绿", "失绿", "紫红", "发紫", "焦枯", "焦边", "褐斑",
                 "坏死", "矮小", "瘦弱", "卷曲", "畸形", "小叶", "丛生", "节间", "脐腐", "裂果", "白化", "分蘖"]
ADVICE_WORDS = ["施用", "追施", "基施", "喷施", "用量", "公斤", "千克", "有机肥", "尿素", "硫酸"]
MARKER = re.compile(r"缺([氮磷钾镁铁锌钙硼硫锰铜钼])|([氮磷钾镁铁锌钙硼硫锰铜钼])(?:素)?缺乏")


def elements_in(text):
    return {ELEMENTS[left or right] for left, right in MARKER.findall(text)}


def extract_candidates(document: dict) -> list[dict]:
    """Keep section context, but never assign a comparison sentence to a single nutrient."""
    buckets = {}
    title = document.get("title", "")
    title_elements = elements_in(title)
    current = next(iter(title_elements)) if len(title_elements) == 1 else None
    heading = title
    for sentence in re.split(r"[。；！？\n]", document["text"]):
        sentence = sentence.strip()
        if not sentence:
            continue
        mentioned = elements_in(sentence)
        if len(mentioned) > 1:
            # A list/comparison resets context rather than leaking the preceding element.
            current = None
            continue
        if mentioned:
            current = next(iter(mentioned))
            if len(sentence) < 30:
                heading = sentence
        if not current:
            continue
        bucket = buckets.setdefault(current, {"symptoms": [], "adviceHints": [], "crops": set()})
        if any(word in sentence for word in SYMPTOM_WORDS):
            bucket["symptoms"].append(sentence)
            bucket["crops"].update(crop for crop in CROPS if crop in title + heading + sentence)
        if any(word in sentence for word in ADVICE_WORDS):
            bucket["adviceHints"].append(sentence)
    candidates = []
    for element, bucket in sorted(buckets.items()):
        symptoms = list(dict.fromkeys(bucket["symptoms"]))
        if not symptoms:
            continue
        # Stable source+element IDs preserve a review when the source content is unchanged.
        identity = document["url"] + "|" + element
        source_text = "；".join(symptoms)
        digest = document.get("contentHash") or hashlib.sha256(document["text"].encode("utf-8")).hexdigest()
        candidates.append({
            "id": "crawl_" + hashlib.sha256(identity.encode("utf-8")).hexdigest()[:20],
            "element": element, "elementName": next(cn for cn, code in ELEMENTS.items() if code == element),
            "crops": sorted(bucket["crops"]) or ["通用"], "symptoms": symptoms[:12],
            "adviceHints": list(dict.fromkeys(bucket["adviceHints"]))[:12],
            "sourceText": source_text, "sourceUrl": document["url"], "sourceTitle": title,
            "publisher": title[:120], "crawledAt": document.get("crawledAt"), "contentHash": digest,
            "needsReview": True,
        })
    return candidates


def validate_review(entry):
    if entry.get("needsReview") is not False:
        raise ValueError("Entry has not been explicitly reviewed")
    for field in ("id", "elementName", "intro", "reviewedBy", "reviewedAt", "contentHash"):
        if not isinstance(entry.get(field), str) or not entry[field].strip():
            raise ValueError(f"Missing reviewed field: {field}")
    datetime.fromisoformat(entry["reviewedAt"].replace("Z", "+00:00"))
    if entry.get("element") not in ELEMENTS.values():
        raise ValueError("Unknown nutrient element")
    url = urlsplit(entry.get("sourceUrl", ""))
    if url.scheme not in ("http", "https") or not url.hostname or url.username or url.password or url.path in ("", "/"):
        raise ValueError("Review requires the exact public article URL, not a homepage")
    for field in ("crops", "agronomy", "safety"):
        if not isinstance(entry.get(field), list) or not entry[field] or not all(isinstance(v, str) and v.strip() for v in entry[field]):
            raise ValueError(f"Missing reviewed list: {field}")
    for field in ("colorPattern", "morphology"):
        if not isinstance(entry.get(field, []), list):
            raise ValueError(f"Invalid symptom list: {field}")
    symptoms = entry.get("colorPattern", []) + entry.get("morphology", [])
    if not symptoms or not all(isinstance(v, str) and v.strip() for v in symptoms):
        raise ValueError("Review must map symptoms to colorPattern/morphology")
    for field, name in (("organicPlan", "measure"), ("chemicalPlan", "fertilizer")):
        plans = entry.get(field)
        if not isinstance(plans, list):
            raise ValueError(f"Missing reviewed plan: {field}")
        for item in plans:
            if not isinstance(item, dict) or not all(isinstance(item.get(key), str) and item[key].strip() for key in (name, "dosage", "method", "timing")):
                raise ValueError(f"Incomplete reviewed dosage or application context: {field}")


def merge_reviewed(candidates, path=KB):
    path = Path(path)
    existing = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()] if path.exists() else []
    records = {entry["id"]: entry for entry in existing}
    if len(records) != len(existing):
        raise ValueError("Existing knowledge IDs must be unique")
    incoming, changed = {}, 0
    for candidate in candidates:
        if candidate.get("needsReview") is not False:
            continue
        identity = candidate.get("id")
        if identity in incoming:
            if incoming[identity] != candidate:
                raise ValueError("Conflicting reviewed candidates with the same ID")
            continue
        incoming[identity] = candidate
        if records.get(identity) == candidate:
            continue
        validate_review(candidate)
        records[identity] = candidate
        changed += 1
    if changed:
        atomic_jsonl(path, list(records.values()))
    return changed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--merge", action="store_true")
    args = parser.parse_args()
    if args.merge:
        if not CAND.exists():
            raise SystemExit("Run structure.py and review candidates first")
        candidates = [json.loads(line) for line in CAND.read_text(encoding="utf-8").splitlines() if line.strip()]
        print(f"Merged {merge_reviewed(candidates)} reviewed entries")
        return
    clean = OUT_DIR / "clean.jsonl"
    if not clean.exists():
        raise SystemExit("Run clean.py first")
    previous = {entry["id"]: entry for entry in (json.loads(line) for line in CAND.read_text(encoding="utf-8").splitlines() if line.strip())} if CAND.exists() else {}
    candidates = []
    for line in clean.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        for candidate in extract_candidates(json.loads(line)):
            old = previous.get(candidate["id"])
            if old and old.get("contentHash") == candidate["contentHash"]:
                candidate = old
            candidates.append(candidate)
    atomic_jsonl(CAND, candidates)
    print(f"Extracted {len(candidates)} source-grounded candidates; review required before --merge")


if __name__ == "__main__":
    main()
