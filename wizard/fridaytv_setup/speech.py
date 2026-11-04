"""Optional: Friday's own speech recognition on the TV (no Google speech service, nothing sent online).

The model is sherpa-onnx's streaming Zipformer English 20M (int8, Apache-2.0), downloaded from
the k2-fsa project's own GitHub release with your yes, then copied into Agent Friday's private
storage over adb with a manifest of sizes and SHA-256 that the app checks before trusting it.
Without it, Friday uses the TV's built-in speech recognizer.
"""
from __future__ import annotations

import hashlib
import json
import tarfile
import tempfile
import urllib.request
from pathlib import Path

from . import ui
from .adb import Adb, home

MODEL = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"
URL = f"https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/{MODEL}.tar.bz2"
URI = "content://ai.futurespeak.friday.tv.keys/stt"
FILES = {   # name on the TV: name in the archive
    "encoder.int8.onnx": "encoder-epoch-99-avg-1.int8.onnx",
    "decoder.int8.onnx": "decoder-epoch-99-avg-1.int8.onnx",
    "joiner.int8.onnx": "joiner-epoch-99-avg-1.int8.onnx",
    "tokens.txt": "tokens.txt",
}


def model_dir() -> Path:
    return home() / "models" / MODEL


def fetch() -> Path | None:
    d = model_dir()
    if all((d / f).exists() for f in FILES.values()):
        return d
    ui.say(f"The speech model (about 120 MB to download, 44 MB on the TV) comes from the sherpa-onnx project:\n  {URL}")
    if not ui.yes("Download it now?", default=False):
        return None
    d.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as t:
        arc = Path(t) / "model.tar.bz2"
        with urllib.request.urlopen(URL, timeout=300) as r, open(arc, "wb") as f:
            while chunk := r.read(1 << 20):
                f.write(chunk)
        with tarfile.open(arc, "r:bz2") as z:
            wanted = {f"{MODEL}/{n}" for n in FILES.values()}
            for m in z.getmembers():
                if m.name in wanted and m.isfile():
                    m.name = Path(m.name).name   # flat, no paths from the archive
                    z.extract(m, d)
    return d if all((d / f).exists() for f in FILES.values()) else None


def push(a: Adb) -> bool:
    d = fetch()
    if not d:
        ui.say("Skipped. Friday will use the TV's own speech recognizer.")
        return False
    manifest = {"model": f"{MODEL} (int8)"}
    for tv_name, src in FILES.items():
        p = d / src
        manifest[tv_name] = {"size": p.stat().st_size, "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
    for tv_name, src in FILES.items():
        # adb push to a temporary file, then a device-side redirect (large stdin over exec-in is unreliable on Windows).
        a.run("push", str(d / src), "/data/local/tmp/friday-stt.part", timeout=600)
        a.shell(f"content write --uri {URI}/{tv_name} < /data/local/tmp/friday-stt.part; rm /data/local/tmp/friday-stt.part", timeout=600)
        ui.ok(f"{tv_name} copied")
    with tempfile.TemporaryDirectory() as t:
        m = Path(t) / "manifest.json"
        m.write_text(json.dumps(manifest), encoding="utf-8")
        a.run("push", str(m), "/data/local/tmp/friday-stt-manifest.json")
    a.shell(f"content write --uri {URI}/manifest.json < /data/local/tmp/friday-stt-manifest.json; rm /data/local/tmp/friday-stt-manifest.json")
    out = a.shell("content call --uri content://ai.futurespeak.friday.tv.keys --method sttmodel")
    ui.say("  " + out.strip()[:200])
    return "READY" in out.upper() or "ok" in out.lower()
