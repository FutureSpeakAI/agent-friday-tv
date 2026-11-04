"""Scans the repository (and optionally its whole git history) for strings that must never be published.

The strings themselves are private, so they are NOT in this repository: put one per line in a
file outside the repo and pass it with --patterns. Lines are case-insensitive literals; a line
starting with re: is a regular expression. Matches are reported by file and line, never by value.

  python scripts/privacy_scan.py --patterns ../private-patterns.txt [--history]
Exit code 1 when anything matches.
"""
import argparse
import re
import subprocess
import sys
from pathlib import Path

SKIP_DIRS = {".git", "build", ".gradle", "__pycache__"}
# Generic shapes that should never appear in a public tree, whatever the private list says.
GENERIC = [
    ("home-network IPv4 address", re.compile(r"\b192\.168\.\d{1,3}\.(?!(?:1|2|10|20)\b)\d{1,3}\b")),
    ("MAC address", re.compile(r"\b(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\b")),
    ("Anthropic key", re.compile(r"sk-ant-(?!TEST-not-a-real-key)[A-Za-z0-9_-]{20,}")),
    ("Windows user path", re.compile(r"C:[\\/]+Users[\\/]+(?!Public|YourName|<)[A-Za-z0-9._-]+", re.I)),
]
GENERIC_NAMES = {n for n, _ in GENERIC}


def load(path):
    out = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        out.append((line, re.compile(line[3:], re.I) if line.startswith("re:") else re.compile(re.escape(line), re.I)))
    return out


def scan_text(label, text, pats, hits):
    for name, rx in pats:
        for m in rx.finditer(text):
            line = text.count("\n", 0, m.start()) + 1
            hits.append(f"{label}:{line}: matches {name if name in GENERIC_NAMES else '<private pattern>'}")


def decode(b: bytes) -> str:
    try:
        return b.decode("utf-8")
    except UnicodeDecodeError:
        return b.decode("latin-1")   # binaries too: names can hide in resources


def tracked_files(root: Path):
    """git ls-files when this is a repository (what would be published); every file otherwise."""
    p = subprocess.run(["git", "-C", str(root), "ls-files", "-z"], capture_output=True)
    if p.returncode == 0:
        return [root / f for f in p.stdout.decode("utf-8").split("\0") if f]
    return [f for f in root.rglob("*") if f.is_file() and not any(x in SKIP_DIRS for x in f.relative_to(root).parts)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--patterns", required=True)
    ap.add_argument("--root", default=".")
    ap.add_argument("--history", action="store_true", help="also scan every blob and commit message in the git history")
    a = ap.parse_args()
    pats = load(a.patterns) + GENERIC
    hits = []
    root = Path(a.root)
    for p in tracked_files(root):
        if p.name == "privacy_scan.py" or not p.is_file():
            continue   # this file holds the generic patterns themselves
        scan_text(str(p.relative_to(root)), decode(p.read_bytes()), pats, hits)
    if a.history:
        objs = subprocess.run(["git", "-C", str(root), "rev-list", "--all", "--objects"], capture_output=True, text=True).stdout
        seen = set()
        for line in objs.splitlines():
            sha, _, path = line.partition(" ")
            if not path or sha in seen or path.endswith("privacy_scan.py"):
                continue
            seen.add(sha)
            blob = subprocess.run(["git", "-C", str(root), "cat-file", "-p", sha], capture_output=True).stdout
            scan_text("history:" + path, decode(blob), pats, hits)
        meta = subprocess.run(["git", "-C", str(root), "log", "--all", "--format=%an <%ae> %cn <%ce>%n%B"], capture_output=True, text=True).stdout
        scan_text("history:commit-metadata", meta, pats, hits)
    for h in hits:
        print(h)
    print(f"{len(hits)} match(es)")
    return 1 if hits else 0


if __name__ == "__main__":
    sys.exit(main())
