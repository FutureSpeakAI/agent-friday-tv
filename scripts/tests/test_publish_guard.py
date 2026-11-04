"""The publish guard must fail on household content. python -m unittest discover -s scripts/tests"""
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

GUARD = Path(__file__).resolve().parents[1] / "publish_guard.py"


def run_in(files: dict, manifest: list | None = None) -> tuple[int, str]:
    with tempfile.TemporaryDirectory() as d:
        root = Path(d)
        subprocess.run(["git", "init", "-q"], cwd=root, check=True)
        for name, data in files.items():
            p = root / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(data if isinstance(data, bytes) else data.encode("utf-8"))
        if manifest is not None:
            (root / "ASSET-SOURCES.json").write_text(json.dumps({"assets": manifest}), encoding="utf-8")
        subprocess.run(["git", "add", "-A"], cwd=root, check=True)
        r = subprocess.run([sys.executable, str(GUARD)], cwd=root, capture_output=True, text=True)
        return r.returncode, r.stdout


class PublishGuardTest(unittest.TestCase):
    def test_a_clean_tree_passes(self):
        png = b"\x89PNG fake"
        code, out = run_in({"README.md": "hi", "app/icon.png": png},
                           [{"path": "app/icon.png", "sha256": hashlib.sha256(png).hexdigest(), "origin": "drawn", "license": "MIT"}])
        self.assertEqual(code, 0, out)

    def test_an_unlisted_or_changed_picture_fails(self):
        self.assertEqual(run_in({"app/pic.png": b"x"}, [])[0], 1)
        code, out = run_in({"app/pic.png": b"new"}, [{"path": "app/pic.png", "sha256": "0" * 64, "origin": "o", "license": "MIT"}])
        self.assertEqual(code, 1)
        self.assertIn("changed since it was reviewed", out)

    def test_generated_household_content_fails(self):
        for files in ({"owl/src/main/assets/stories/ember.json": "{}"},
                      {"owl/src/main/assets/house-voices.json": "{}"},
                      {"owl/src/main/assets/lesson.json": '{"generated_voice_id": "x"}'},
                      {"owl/src/main/assets/night.json": '{"wondering_answer": "x"}'},
                      {"owl/review/last-mix.txt": "x"}):
            code, out = run_in(files, [])
            self.assertEqual(code, 1, (files, out))

    def test_only_premade_voices_may_appear(self):
        self.assertEqual(run_in({"Voices.java": 'String v = "21m00Tcm4TlvDq8ikWAM";'}, [])[0], 0)
        code, out = run_in({"Voices.java": 'String v = "Zq9xL2mPa7Rt4KdW8sYb";'}, [])
        self.assertEqual(code, 1)
        self.assertIn("not an ElevenLabs premade voice", out)


if __name__ == "__main__":
    unittest.main()
