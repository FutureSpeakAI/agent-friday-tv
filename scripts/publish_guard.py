"""Publish guard: nothing generated in a household, and nothing unreviewed, goes into the public tree.

Fails (exit 1) when:
  1. A media or binary file (pictures, audio, video, fonts, native libraries, models) is tracked but not
     listed in ASSET-SOURCES.json with its SHA-256, origin and license, or its bytes changed since review.
  2. Anything looks like generated household content: stories, scripts, scores, cast or voice files,
     notebooks, reports, recordings or reviews (by path), or a JSON file that carries generated voice ids,
     a child's answers or a "today" note.
  3. An ElevenLabs-style voice id appears in code or data that is not one of ElevenLabs' public premade
     voices. Voices designed for a household live on that TV, never in the repository.

  python scripts/publish_guard.py            (run from the repository root; CI runs it on every push)
"""
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

MEDIA = re.compile(r"\.(png|jpe?g|webp|gif|bmp|svg|mp3|wav|ogg|oga|m4a|aac|flac|pcm|mp4|webm|mkv|mov|ttf|otf|woff2?|so|onnx|tflite|bin|p12|jar)$", re.I)
GENERATED_PATH = re.compile(
    r"(^|/)(stories|story-books?|books|scripts/generated|scores?|cast|house-voices|voices|notebook|today-notes?|"
    r"reports?|recordings?|review|screenshots|last-mix|mix-tap)(/|\.|$)", re.I)
GENERATED_JSON_KEYS = re.compile(r'"(generated_voice_id|house_voice|child_answer|answers|today_note|notebook|wondering_answer)"\s*:')
VOICE_ID = re.compile(r"[\"']([A-Za-z0-9]{20})[\"']")   # quoted 20-character tokens; checked for mixed case and digits below
# ElevenLabs' public premade voices (the same ids on every account).
PREMADE = {
    "21m00Tcm4TlvDq8ikWAM",  # Rachel
    "EXAVITQu4vr4xnSDxMaL",  # Sarah
    "hpp4J3VqNfWAUOO0d1Us",  # Bella
    "Xb7hH8MSUJpSbSDYk0k2",  # Alice
    "pFZP5JQG7iQjIQuC4Bku",  # Lily
    "XrExE9yKIg1WjnnlVkGX",  # Matilda
    "cgSgspJ2msm6clMCkdW9",  # Jessica
    "JBFqnCBsd6RMkjVDRZzb",  # George
}
NOT_VOICES = {"PBKDF2WithHmacSHA256"}
TEXT = re.compile(r"\.(java|kt|js|mjs|ts|json|py|xml|html|css|md|txt|gradle|toml|yml|yaml|ps1|sh|csv)$", re.I)
CODE = re.compile(r"\.(java|kt|py|js|mjs|ts|gradle)$", re.I)   # code may be named for what it makes; data may not
SKIP_TEXT = re.compile(r"(three-r128.*\.js$|publish_guard\.py$|^scripts/tests/)")
ALLOWED_GENERATED = re.compile(r"^(\.github/|scripts/publish_guard\.py$)")


def tracked(root: Path) -> list[str]:
    out = subprocess.run(["git", "-C", str(root), "ls-files", "-z"], capture_output=True)
    if out.returncode != 0:
        sys.exit("Run inside a git repository.")
    return [f for f in out.stdout.decode("utf-8").split("\0") if f]


def main() -> int:
    root = Path(".")
    files = tracked(root)
    problems = []
    manifest_path = root / "ASSET-SOURCES.json"
    manifest = {}
    if manifest_path.exists():
        for a in json.loads(manifest_path.read_text(encoding="utf-8"))["assets"]:
            manifest[a["path"]] = a
    for f in files:
        p = root / f
        if MEDIA.search(f):
            a = manifest.get(f)
            if not a:
                problems.append(f"{f}: not listed in ASSET-SOURCES.json (add its origin and license after review)")
            else:
                for field in ("sha256", "origin", "license"):
                    if not a.get(field):
                        problems.append(f"{f}: ASSET-SOURCES.json entry has no {field}")
                if a.get("sha256") and hashlib.sha256(p.read_bytes()).hexdigest() != a["sha256"]:
                    problems.append(f"{f}: changed since it was reviewed (sha256 differs)")
                if a.get("generated_for_household"):
                    problems.append(f"{f}: marked as generated for a household")
        if GENERATED_PATH.search(f) and not ALLOWED_GENERATED.search(f) and "/test/" not in f and not CODE.search(f):
            problems.append(f"{f}: path looks like generated household content (stories, voices, reports, recordings)")
        if TEXT.search(f) and not SKIP_TEXT.search(f):
            text = p.read_text(encoding="utf-8", errors="replace")
            if f.endswith(".json") and GENERATED_JSON_KEYS.search(text):
                problems.append(f"{f}: carries generated voices or a child's answers")
            for m in VOICE_ID.finditer(text):
                v = m.group(1)
                if not (re.search(r"\d", v) and re.search(r"[A-Z]", v) and re.search(r"[a-z]", v)):
                    continue
                if v not in PREMADE and v not in NOT_VOICES:
                    line = text.count("\n", 0, m.start()) + 1
                    problems.append(f"{f}:{line}: a voice-id-shaped string that is not an ElevenLabs premade voice")
    for path in manifest:
        if path not in files:
            problems.append(f"ASSET-SOURCES.json lists {path}, which isn't in the repository")
    for x in problems:
        print(x)
    print(f"publish guard: {len(problems)} problem(s) in {len(files)} tracked files")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
