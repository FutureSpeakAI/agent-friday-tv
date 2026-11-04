"""A thin, careful wrapper around Google's adb (Android Debug Bridge).

Rules kept here:
  - adb comes from Google's platform-tools only (PATH, the Android SDK, or a copy the
    wizard downloads from dl.google.com with your yes).
  - Secrets are never put on a command line or in a log; they go to adb on stdin.
"""
from __future__ import annotations

import io
import os
import platform
import shutil
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

from . import ui

TOOLS_URL = {
    "Windows": "https://dl.google.com/android/repository/platform-tools-latest-windows.zip",
    "Darwin": "https://dl.google.com/android/repository/platform-tools-latest-darwin.zip",
    "Linux": "https://dl.google.com/android/repository/platform-tools-latest-linux.zip",
}


def home() -> Path:
    """Where the wizard keeps its own files (never inside the repository)."""
    base = os.environ.get("LOCALAPPDATA") or os.environ.get("XDG_DATA_HOME") or str(Path.home() / ".local" / "share")
    p = Path(base) / "agent-friday-tv"
    p.mkdir(parents=True, exist_ok=True)
    return p


def _exe(name: str) -> str:
    return name + (".exe" if platform.system() == "Windows" else "")


def find_adb() -> str | None:
    found = shutil.which("adb")
    if found:
        return found
    candidates = []
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(env):
            candidates.append(Path(os.environ[env]) / "platform-tools")
    if os.environ.get("LOCALAPPDATA"):
        candidates.append(Path(os.environ["LOCALAPPDATA"]) / "Android" / "Sdk" / "platform-tools")
    candidates.append(Path.home() / "Library" / "Android" / "sdk" / "platform-tools")
    candidates.append(Path.home() / "Android" / "Sdk" / "platform-tools")
    candidates.append(home() / "platform-tools")
    for c in candidates:
        p = c / _exe("adb")
        if p.is_file():
            return str(p)
    return None


def ensure_adb(interactive: bool = True) -> str:
    """Returns adb's path, offering to download Google's platform-tools when it is missing."""
    found = find_adb()
    if found:
        return found
    url = TOOLS_URL.get(platform.system())
    if not url:
        raise SystemExit("adb was not found. Install Android platform-tools from https://developer.android.com/tools/releases/platform-tools")
    ui.say("adb (Google's Android Debug Bridge) isn't on this computer yet.")
    ui.say(f"I can download Google's platform-tools (about 7 MB) from:\n  {url}\ninto {home()}")
    if not interactive or not ui.yes("Download it now?", default=False):
        raise SystemExit("adb is needed. Install it, or run me again and answer yes. "
                         "Windows: winget install Google.PlatformTools")
    with urllib.request.urlopen(url, timeout=60) as r:
        data = r.read()
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        for m in z.namelist():
            if m.startswith("/") or ".." in Path(m).parts:
                raise SystemExit("Unexpected file in the platform-tools download; stopping.")
        z.extractall(home())
    p = home() / "platform-tools" / _exe("adb")
    if platform.system() != "Windows":
        p.chmod(0o755)
    ui.ok(f"adb is ready: {p}")
    return str(p)


class Adb:
    def __init__(self, path: str, serial: str | None = None):
        self.path = path
        self.serial = serial

    def _base(self) -> list[str]:
        return [self.path] + (["-s", self.serial] if self.serial else [])

    def run(self, *args: str, timeout: int = 60, check: bool = False, stdin: bytes | None = None, device: bool = True) -> subprocess.CompletedProcess:
        cmd = (self._base() if device else [self.path]) + list(args)
        p = subprocess.run(cmd, input=stdin, capture_output=True, timeout=timeout)
        if check and p.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args[:2])} failed: {p.stderr.decode(errors='replace').strip()[:300]}")
        return p

    def out(self, *args: str, timeout: int = 60) -> str:
        p = self.run(*args, timeout=timeout)
        return (p.stdout + p.stderr).decode("utf-8", "replace").strip()

    def shell(self, command: str, timeout: int = 60) -> str:
        return self.out("shell", command, timeout=timeout)

    # ---- connection -----------------------------------------------------------------------------

    def pair(self, host: str, port: int, code: str) -> bool:
        """Wireless-debugging pairing. The six-digit code is short-lived and single-use."""
        code = "".join(c for c in code if c.isdigit())
        if len(code) != 6:
            raise ValueError("The pairing code is the six digits the TV shows.")
        # adb pair takes the code as an argument or on stdin; stdin keeps it out of process lists.
        p = self.run("pair", f"{host}:{port}", stdin=(code + "\n").encode(), timeout=30, device=False)
        text = (p.stdout + p.stderr).decode("utf-8", "replace")
        return "Successfully paired" in text

    def connect(self, host: str, port: int) -> bool:
        p = self.run("connect", f"{host}:{port}", timeout=20, device=False)
        text = (p.stdout + p.stderr).decode("utf-8", "replace")
        ok = "connected to" in text and "failed" not in text.lower()
        if ok:
            self.serial = f"{host}:{port}"
        return ok

    def devices(self) -> list[str]:
        p = self.run("devices", device=False)
        lines = p.stdout.decode("utf-8", "replace").splitlines()[1:]
        return [l.split()[0] for l in lines if l.strip().endswith("device")]

    def mdns_services(self) -> str:
        p = self.run("mdns", "services", device=False, timeout=15)
        return p.stdout.decode("utf-8", "replace")

    # ---- device facts -----------------------------------------------------------------------------

    def prop(self, name: str) -> str:
        return self.shell(f"getprop {name}")

    def has_package(self, pkg: str) -> bool:
        return f"package:{pkg}" in self.shell(f"pm list packages {pkg}").split()

    def version_of(self, pkg: str) -> str:
        for line in self.shell(f"dumpsys package {pkg}").splitlines():
            line = line.strip()
            if line.startswith("versionName="):
                return line.split("=", 1)[1]
        return ""

    def install(self, apk: Path) -> tuple[bool, str]:
        p = self.run("install", "-r", str(apk), timeout=300)
        text = (p.stdout + p.stderr).decode("utf-8", "replace").strip()
        return ("Success" in text), text.splitlines()[-1] if text else ""

    def uninstall(self, pkg: str) -> bool:
        return "Success" in self.out("uninstall", pkg, timeout=120)

    def write_stdin_to_content(self, uri: str, payload: bytes) -> bool:
        """Streams bytes into a content provider without them touching disk or a command line."""
        p = self.run("exec-in", "content", "write", "--uri", uri, stdin=payload, timeout=60)
        return p.returncode == 0
