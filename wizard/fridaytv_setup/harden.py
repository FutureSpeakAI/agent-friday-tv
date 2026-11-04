"""Optional privacy hardening: an opt-in checklist, every item with a one-line undo.

Nothing here uninstalls, roots or flashes anything. An item either switches a package off for
the TV's user ("pm disable-user --user 0", undone by "pm enable") or sets one settings value
(undone by putting the old value back). Every change is written to a local journal first, so
"undo everything" works even after a crash.

The NEVER-BREAK rules (docs/NEVER-BREAK.md) are enforced here, in code, before any command
runs; a profile cannot override them.
"""
from __future__ import annotations

import json
import re
import time
from dataclasses import dataclass
from importlib import resources
from pathlib import Path

from . import adb as adbmod
from . import mdns, ui

# ---- NEVER-BREAK ---------------------------------------------------------------------------------

#: Never disabled, force-stopped or hidden, whatever a profile says.
NEVER_EXACT = frozenset({
    "android", "com.android.shell", "com.android.systemui", "com.android.tv.settings", "com.android.vending",
    "com.google.android.gms", "com.google.android.gsf", "com.android.location.fused",
    "com.google.android.apps.mediashell",            # the Google Cast receiver
    "com.google.android.apps.tv.launcherx",          # disabling it can erase saved Wi-Fi and break Cast
    "com.google.android.tungsten.setupwraith",
    "com.android.tv.mdnsoffloadmanager",             # answers Cast discovery while the TV sleeps
    "com.google.android.tv.remote.service",
    "com.google.android.packageinstaller", "com.android.packageinstaller",
    "com.google.android.permissioncontroller", "com.android.providers.settings",
    "ai.futurespeak.friday.tv", "org.tvprivacy.adbreak",
})
#: Anything whose name suggests casting, updates, HDMI/CEC, inputs, the remote, the launcher or core UI.
NEVER_PATTERN = re.compile(
    r"(googlecast|mediashell|(^|[._])ota([._]|$)|update|upgrade|cec|hdmi|tvinput|autopair|remote|bluetooth|systemui|"
    r"settings|packageinstaller|permissioncontroller|inputmethod|keyboard|launcher|setupwraith|framework|webview|tvprovider|"
    r"\.gms$|\.gsf$|vending)")
#: Settings namespaces and keys an item may change. Anything else is refused.
SETTING_KEY = re.compile(r"^[a-z0-9_.]{2,64}$")
PKG = re.compile(r"^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+$")


class NeverBreak(ValueError):
    pass


def check_package(pkg: str) -> None:
    if not PKG.match(pkg):
        raise NeverBreak(f"not a package name: {pkg!r}")
    if pkg in NEVER_EXACT or NEVER_PATTERN.search(pkg.lower()):
        raise NeverBreak(f"{pkg} is on the never-break list (it keeps Wi-Fi, Cast, the remote, updates or Friday working)")


@dataclass
class Item:
    id: str
    title: str
    why: str
    lose: str
    kind: str            # "package" | "setting"
    target: str          # package name, or "namespace/key"
    value: str = ""      # for settings
    caution: str = ""

    def validate(self) -> "Item":
        if not re.match(r"^[a-z0-9-]{2,48}$", self.id):
            raise NeverBreak(f"bad item id {self.id!r}")
        if self.kind == "package":
            check_package(self.target)
        elif self.kind == "setting":
            ns, _, key = self.target.partition("/")
            if ns not in ("global", "secure", "system") or not SETTING_KEY.match(key):
                raise NeverBreak(f"bad setting {self.target!r}")
            if ns == "secure" and key in ("enabled_accessibility_services", "default_input_method", "location_mode"):
                raise NeverBreak(f"{key} is never changed by hardening")
            if not re.match(r"^[A-Za-z0-9_.:-]{1,64}$", self.value):
                raise NeverBreak(f"bad value for {self.target}")
        else:
            raise NeverBreak(f"unknown kind {self.kind!r}")
        return self

    def apply_cmd(self) -> str:
        if self.kind == "package":
            return f"pm disable-user --user 0 {self.target}"
        ns, _, key = self.target.partition("/")
        return f"settings put {ns} {key} {self.value}"

    def undo_line(self, before: str | None = None) -> str:
        if self.kind == "package":
            return f"adb shell pm enable --user 0 {self.target}"
        ns, _, key = self.target.partition("/")
        if before in (None, "", "null"):
            return f"adb shell settings delete {ns} {key}"
        return f"adb shell settings put {ns} {key} {before}"


def profiles() -> dict[str, dict]:
    """Every bundled profile: generic (any Android TV / Google TV) plus optional vendor ones."""
    out = {}
    for f in resources.files("fridaytv_setup.profiles").iterdir():
        if f.name.endswith(".json"):
            out[f.name[:-5]] = json.loads(f.read_text(encoding="utf-8"))
    return out


def items(profile: dict) -> list[Item]:
    return [Item(**{k: v for k, v in i.items() if k in Item.__dataclass_fields__}).validate() for i in profile.get("items", [])]


def suggest_profiles(manufacturer: str) -> list[str]:
    m = (manufacturer or "").lower()
    out = ["generic"]
    for name, p in profiles().items():
        if name != "generic" and any(v in m for v in p.get("manufacturers", [])):
            out.append(name)
    return out


# ---- journal -------------------------------------------------------------------------------------

def journal_path(serial: str) -> Path:
    safe = re.sub(r"[^A-Za-z0-9]", "_", serial or "tv")[:40]
    return adbmod.home() / f"hardening-{safe}.json"


def _load(serial: str) -> list[dict]:
    p = journal_path(serial)
    return json.loads(p.read_text(encoding="utf-8")) if p.exists() else []


def _save(serial: str, rows: list[dict]) -> None:
    journal_path(serial).write_text(json.dumps(rows, indent=1), encoding="utf-8")


# ---- apply and undo ------------------------------------------------------------------------------

def cast_visible(ip: str) -> bool | None:
    """True/False when we can tell whether the TV still answers Cast discovery; None when it never did."""
    for d in mdns.discover(timeout=4.0):
        if d.ip == ip:
            return d.cast
    return False


def apply(a: adbmod.Adb, chosen: list[Item], tv_ip: str | None = None, dry_run: bool = False) -> list[dict]:
    """Applies the chosen items, journaling each one first; rolls the batch back if Cast disappears."""
    for it in chosen:
        it.validate()
    cast_before = cast_visible(tv_ip) if tv_ip else None
    rows = _load(a.serial)
    done = []
    for it in chosen:
        if it.kind == "package":
            if not a.has_package(it.target):
                ui.say(f"  - {it.title}: not on this TV, skipped")
                continue
            before = None
        else:
            ns, _, key = it.target.partition("/")
            before = a.shell(f"settings get {ns} {key}")
        row = {"id": it.id, "kind": it.kind, "target": it.target, "before": before,
               "undo": it.undo_line(before), "at": int(time.time())}
        if dry_run:
            ui.say(f"  would run: adb shell {it.apply_cmd()}   (undo: {row['undo']})")
            continue
        rows.append(row)
        _save(a.serial, rows)                      # journal first, then change
        out = a.shell(it.apply_cmd())
        if it.kind == "package" and "disabled" not in out.lower():
            ui.warn(f"{it.title}: Android refused ({out[:120]}). Nothing changed.")
            rows.remove(row)
            _save(a.serial, rows)
            continue
        done.append(row)
        ui.ok(f"{it.title}   undo: {row['undo']}")
    if done and cast_before:
        time.sleep(3)
        if not cast_visible(tv_ip):
            ui.warn("The TV stopped answering Cast discovery after these changes. Putting them back now.")
            undo(a, only=[r["id"] for r in done])
    return done


def undo(a: adbmod.Adb, only: list[str] | None = None) -> int:
    """Reverses journaled changes, newest first. Returns how many were undone."""
    rows = _load(a.serial)
    keep, n = [], 0
    for row in reversed(rows):
        if only is not None and row["id"] not in only:
            keep.insert(0, row)
            continue
        if row["kind"] == "package":
            a.shell(f"pm enable --user 0 {row['target']}")
        else:
            ns, _, key = row["target"].partition("/")
            if row["before"] in (None, "", "null"):
                a.shell(f"settings delete {ns} {key}")
            else:
                a.shell(f"settings put {ns} {key} {row['before']}")
        n += 1
        ui.ok(f"undone: {row['target']}")
    _save(a.serial, keep)
    return n


def manual_checklist() -> list[tuple[str, list[str]]]:
    """Menu switches only the person can press (no command can do these reliably on every TV)."""
    return [
        ("Delete or reset the advertising ID", ["Home", "Settings (gear, top right)", "Privacy", "Ads", "Delete advertising ID (or Reset)"]),
        ("Turn off usage & diagnostics", ["Settings", "Privacy", "Usage & diagnostics", "Off"]),
        ("Optional: Apps-only mode (hides recommendation rows)", ["Settings", "Accounts & sign-in", "your Google account", "Apps only mode", "On"]),
        ("Decline any 'viewing information' or ACR consent", ["Settings", "Privacy (or your TV brand's own menu)", "Viewing information / Smart interactivity", "Off"]),
    ]
