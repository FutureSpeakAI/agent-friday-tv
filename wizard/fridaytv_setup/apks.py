"""Where the APKs come from: a folder you give, a signed GitHub release, or a build on this PC.

Releases are checked against the SHA256SUMS file published with them before anything is
installed. Third-party apps (SmartTube, Projectivy Launcher and others) are never bundled or
downloaded here; the wizard only links to their official pages.
"""
from __future__ import annotations

import hashlib
import json
import os
import platform
import shutil
import subprocess
import urllib.request
from dataclasses import dataclass
from pathlib import Path

from . import ui
from .adb import home

REPO_ROOT = Path(__file__).resolve().parents[2]   # this checkout of agent-friday-tv (when run from source)


@dataclass(frozen=True)
class Component:
    key: str
    title: str
    package: str
    repo: str            # GitHub owner/name
    asset: str           # release asset file name
    gradle_task: str
    built_apk: str       # path under the repo's android/ folder
    optional: bool
    ready: bool = True   # False: announced, not published yet
    blurb: str = ""


COMPONENTS = [
    Component("friday-tv", "Agent Friday (the TV app)", "ai.futurespeak.friday.tv", "FutureSpeakAI/agent-friday-tv",
              "agent-friday-tv.apk", ":tv:assembleRelease", "tv/build/outputs/apk/release/tv-release.apk", False,
              blurb="Friday on your TV: ask by voice from the remote; answers, cards and channels."),
    Component("helper", "Ad Break Guard (the helper)", "org.tvprivacy.adbreak", "FutureSpeakAI/agent-friday-tv",
              "adbreak-helper.apk", ":helper:assembleRelease", "helper/build/outputs/apk/release/helper-release.apk", False,
              blurb="Mutes and covers labelled ad breaks, keeps the grown-up PIN and kids' bedtime, and keeps Cast awake."),
    Component("news", "Friday News (add-on)", "ai.futurespeak.friday.tv.news", "FutureSpeakAI/friday-tv-news",
              "friday-tv-news.apk", ":news:assembleRelease", "news/build/outputs/apk/release/news-release.apk", True,
              blurb="Headlines from public outlets, read aloud, with a kids filter and your local weather."),
    Component("wonder-owl", "Wonder Owl (add-on)", "ai.futurespeak.friday.tv.owl", "FutureSpeakAI/wonder-owl",
              "wonder-owl.apk", ":owl:assembleRelease", "owl/build/outputs/apk/release/owl-release.apk", True, ready=False,
              blurb="A kid-safe explorer with story mode. Coming soon."),
]

THIRD_PARTY_SUGGESTIONS = [
    ("SmartTube", "an ad-free YouTube client for Android TV", "https://github.com/yuliskov/SmartTube"),
    ("Projectivy Launcher", "a home screen without sponsored rows", "https://play.google.com/store/apps/details?id=com.spocky.projengmenu"),
]


def by_key(key: str) -> Component:
    for c in COMPONENTS:
        if c.key == key:
            return c
    raise KeyError(key)


def _sha256(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def from_release(c: Component, tag: str = "latest") -> Path | None:
    """Downloads the component's APK from its GitHub release, verified against SHA256SUMS."""
    api = f"https://api.github.com/repos/{c.repo}/releases/" + ("latest" if tag == "latest" else f"tags/{tag}")
    try:
        with urllib.request.urlopen(urllib.request.Request(api, headers={"Accept": "application/vnd.github+json"}), timeout=20) as r:
            rel = json.loads(r.read().decode("utf-8"))
    except Exception:
        return None
    assets = {a["name"]: a["browser_download_url"] for a in rel.get("assets", [])}
    if c.asset not in assets or "SHA256SUMS" not in assets:
        return None
    with urllib.request.urlopen(assets["SHA256SUMS"], timeout=20) as r:
        sums = dict(reversed(l.split()) for l in r.read().decode("utf-8").splitlines() if len(l.split()) == 2)
    want = sums.get(c.asset) or sums.get("*" + c.asset)
    if not want:
        return None
    dest = home() / "apks" / rel.get("tag_name", "release") / c.asset
    dest.parent.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(assets[c.asset], timeout=120) as r, open(dest, "wb") as f:
        shutil.copyfileobj(r, f)
    if _sha256(dest) != want.lower():
        dest.unlink(missing_ok=True)
        ui.warn(f"{c.title}: the download didn't match its published checksum; not installing it.")
        return None
    return dest


def source_dir(c: Component) -> Path | None:
    """The android/ folder that builds this component: this checkout, or a clone in the wizard's folder."""
    if c.repo.endswith("/agent-friday-tv") and (REPO_ROOT / "android" / "settings.gradle").exists():
        return REPO_ROOT / "android"
    clone = home() / "src" / c.repo.split("/")[1]
    if (clone / "android" / "settings.gradle").exists():
        return clone / "android"
    return None


def clone(c: Component) -> Path | None:
    git = shutil.which("git")
    if not git:
        ui.warn("git isn't installed, so I can't fetch the source. Install Git, or download the release APK yourself.")
        return None
    dest = home() / "src" / c.repo.split("/")[1]
    dest.parent.mkdir(parents=True, exist_ok=True)
    if not ui.yes(f"Download the source of {c.title} from https://github.com/{c.repo} into {dest}?", default=True):
        return None
    subprocess.run([git, "clone", "--depth", "1", f"https://github.com/{c.repo}.git", str(dest)], check=False)
    return dest / "android" if (dest / "android" / "settings.gradle").exists() else None


def build(c: Component) -> Path | None:
    """Builds a signed release APK on this PC (needs JDK 17 and the Android SDK; see the README)."""
    src = source_dir(c) or clone(c)
    if not src:
        return None
    if not os.environ.get("JAVA_HOME") and not shutil.which("java"):
        ui.warn("Building needs Java 17 (JDK). Windows: winget install Microsoft.OpenJDK.17")
        return None
    gradlew = src / ("gradlew.bat" if platform.system() == "Windows" else "gradlew")
    ui.say(f"Building {c.title} (the first build downloads Gradle and the Android build tools; it can take several minutes)…")
    p = subprocess.run([str(gradlew), "--no-daemon", "-q", c.gradle_task], cwd=src)
    apk = src / c.built_apk
    if p.returncode != 0 or not apk.exists():
        ui.warn(f"The build of {c.title} failed. The README's Troubleshooting section lists the usual causes.")
        return None
    return apk


def locate(c: Component, apk_dir: str | None = None, allow_download: bool = True, allow_build: bool = True) -> Path | None:
    if not c.ready:
        ui.say(f"  {c.title}: not published yet.")
        return None
    if apk_dir:
        for name in (c.asset, Path(c.built_apk).name):
            p = Path(apk_dir) / name
            if p.exists():
                return p
    src = source_dir(c)
    if src and (src / c.built_apk).exists():
        return src / c.built_apk
    if allow_download:
        p = from_release(c)
        if p:
            return p
    if allow_build:
        return build(c)
    return None
