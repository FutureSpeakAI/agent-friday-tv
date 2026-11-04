"""Which AI service and model does each job: the owner's choice, kept in a small file with no keys.

  python -m fridaytv_setup keys --providers my-providers.json

See providers.example.json and docs/PROVIDERS.md. Anything left out takes the default. The file
must never hold a key; the wizard refuses one that looks like it does.
"""
from __future__ import annotations

import json
import re
import urllib.request
from pathlib import Path

from . import ui

JOBS = {
    "text": {"provider": ("anthropic", "openrouter"), "model": "claude-opus-5-5", "fast_model": "claude-sonnet-5-5"},
    "voice": {"provider": ("elevenlabs", "tv"), "voice_id": "", "model": "eleven_flash_v2_5", "design_model": "eleven_multilingual_ttv_v2"},
    "sound": {"provider": ("elevenlabs", "none")},
    "music": {"provider": ("elevenlabs", "none")},
    "images": {"provider": ("kie", "none"), "model": "nano-banana-2"},
    "video": {"provider": ("kie", "none"), "model": ""},
}
MODEL = re.compile(r"^[A-Za-z0-9_.:/-]{0,96}$")
KEY_VALUE = re.compile(r"(sk-[A-Za-z0-9_-]{8,}|^[A-Za-z0-9_-]{32,}$)")
KEY_NAME = re.compile(r"(key|secret|token|password)", re.I)

FALLBACK_WORDS = {
    "text": {"none": "Friday can't answer until a Claude (or OpenRouter) key is added."},
    "voice": {"tv": "Friday speaks with the TV's own voice."},
    "sound": {"none": "No sound effects."},
    "music": {"none": "No generated music."},
    "images": {"stills": "No generated pictures: add-ons show their own still pictures."},
    "video": {"stills": "No video clips: pictures drift slowly instead."},
}


class NotAllowed(ValueError):
    pass


def clean(raw: dict) -> dict:
    """Keeps only known jobs and fields with valid values; refuses anything that looks like a key."""
    if not isinstance(raw, dict):
        raise NotAllowed("the providers file must be a JSON object")
    _no_keys(raw)
    out = {}
    for job, fields in JOBS.items():
        given = raw.get(job) or {}
        if not isinstance(given, dict):
            continue
        o = {}
        for f, allowed in fields.items():
            if f not in given:
                continue
            v = str(given[f]).strip()
            if f == "provider":
                if v in allowed:
                    o[f] = v
                continue
            if f == "voice_id":
                if re.fullmatch(r"[A-Za-z0-9_-]{1,40}", v):
                    o[f] = v
                continue
            if MODEL.match(v):
                o[f] = v
        if o:
            out[job] = o
    return out


def _no_keys(node, where: str = "") -> None:
    """Refuses a file that holds anything shaped like a key, under any name, at any depth."""
    if isinstance(node, dict):
        for k, v in node.items():
            if KEY_NAME.search(str(k)):
                raise NotAllowed(f"'{where}{k}' looks like a key field. Keys never go in this file; the wizard asks for them.")
            _no_keys(v, f"{where}{k}.")
    elif isinstance(node, list):
        for i, v in enumerate(node):
            _no_keys(v, f"{where}{i}.")
    elif isinstance(node, str) and KEY_VALUE.search(node.strip()):
        raise NotAllowed(f"'{where.rstrip('.')}' looks like a key. Keys never go in this file; the wizard asks for them.")


def load(path: str) -> dict:
    return clean(json.loads(Path(path).read_text(encoding="utf-8")))


def plan(chosen: dict, keys_present: set[str]) -> dict:
    """The same decision the TV makes (Providers.plan), so the wizard can say it in words first."""
    g = lambda job, f: (chosen.get(job) or {}).get(f, JOBS[job][f] if not isinstance(JOBS[job][f], tuple) else JOBS[job][f][0])
    text = "openrouter" if g("text", "provider") == "openrouter" and "openrouter" in keys_present else \
        "anthropic" if "anthropic" in keys_present else "openrouter" if "openrouter" in keys_present else "none"
    el, kie = "elevenlabs" in keys_present, "kie" in keys_present
    return {
        "text": text,
        "voice": "elevenlabs" if g("voice", "provider") == "elevenlabs" and el else "tv",
        "sound": "elevenlabs" if g("sound", "provider") == "elevenlabs" and el else "none",
        "music": "elevenlabs" if g("music", "provider") == "elevenlabs" and el else "none",
        "images": "kie" if g("images", "provider") == "kie" and kie else "stills",
        "video": "kie" if g("video", "provider") == "kie" and kie and g("video", "model") else "stills",
    }


def describe(p: dict) -> list[str]:
    lines = []
    for job, used in p.items():
        words = FALLBACK_WORDS.get(job, {}).get(used)
        lines.append(f"{job:7} {used:11} {words or ''}".rstrip())
    return lines


def eleven_voices(key: str) -> list[tuple[str, str]]:
    """The voices on this ElevenLabs account (names and ids only)."""
    req = urllib.request.Request("https://api.elevenlabs.io/v1/voices", headers={"xi-api-key": key})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            data = json.loads(r.read().decode("utf-8"))
        return [(v.get("name", "?"), v.get("voice_id", "")) for v in data.get("voices", []) if v.get("voice_id")]
    except Exception:
        return []


def choose(models: list[str], eleven_key: str | None) -> dict:
    """Optional, interactive: pick models and Friday's voice. Enter keeps every default."""
    if not ui.yes("Choose models and Friday's voice yourself? (No keeps the recommended defaults)", default=False):
        return {}
    out: dict = {}
    claude = [m for m in models if m.startswith("claude-")]
    if claude:
        ui.say("Claude models on your account:")
        i = ui.choose("Friday's main model", claude)
        out.setdefault("text", {})["model"] = claude[i]
    if eleven_key:
        voices = eleven_voices(eleven_key)
        if voices:
            ui.say("Voices on your ElevenLabs account:")
            labels = ["The TV's own voice (no ElevenLabs for speaking)"] + [n for n, _ in voices]
            i = ui.choose("Friday's voice", labels)
            if i == 0:
                out["voice"] = {"provider": "tv"}
            else:
                out["voice"] = {"provider": "elevenlabs", "voice_id": voices[i - 1][1]}
    img = ui.ask("kie.ai image model (Enter for the default, 'none' for no generated pictures)", JOBS["images"]["model"])
    out["images"] = {"provider": "none"} if img == "none" else {"provider": "kie", "model": img}
    vid = ui.ask("kie.ai video model for short clips (Enter for none)", "")
    if vid:
        out["video"] = {"provider": "kie", "model": vid}
    return clean(out)
