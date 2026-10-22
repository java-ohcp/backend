"""Run crawl -> clean -> extract; never auto-approve candidates or invoke paid models."""
import argparse
import subprocess
import sys
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", action="append")
    parser.add_argument("--depth", type=int, default=0, choices=range(3))
    parser.add_argument("--max-pages", type=int, default=20)
    parser.add_argument("--offline", action="store_true", help="Reprocess previously saved HTML only")
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    if not args.offline:
        command = [sys.executable, str(here / "crawl.py"), "--depth", str(args.depth), "--max-pages", str(args.max_pages)]
        for url in args.url or []:
            command.extend(["--url", url])
        subprocess.run(command, check=True)
    for script in ("clean.py", "structure.py"):
        subprocess.run([sys.executable, str(here / script)], check=True)
    print("Candidates ready in crawler/out/candidates.jsonl; human review is required before --merge.")


if __name__ == "__main__":
    main()
