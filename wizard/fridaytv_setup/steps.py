"""The wizard's steps. Each one can run on its own, and each says what it is about to do first."""
from __future__ import annotations

import re
import time

from . import apks, harden, keys, mdns, ui
from .adb import Adb

HELPER_SERVICE = "org.tvprivacy.adbreak/org.tvprivacy.adbreak.AdGuardService"
HELPER_PREFIX = "org.tvprivacy.adbreak/"   # Android may store the short form (".AdGuardService")
CORE = "ai.futurespeak.friday.tv"
KEYS_URI = f"content://{CORE}.keys"


# ---- 1. find the TV -----------------------------------------------------------------------------

def find_tvs(timeout: float = 4.0) -> list[mdns.Device]:
    ui.say("Looking for Android TV and Google TV devices on your network…")
    found = [d for d in mdns.discover(timeout) if d.is_tv]
    if not found:
        ui.warn("No TV answered. Is it on, and on the same Wi-Fi or network as this PC?")
        ui.say("  Some routers block discovery between Wi-Fi and wired devices; you can type the TV's address instead.")
    for d in found:
        extras = []
        if d.pairing_port:
            extras.append("showing a pairing code")
        elif d.connect_port:
            extras.append("wireless debugging on")
        ui.say(f"  - {d.label()}" + (f"  [{', '.join(extras)}]" if extras else ""))
    return found


def choose_tv(found: list[mdns.Device]) -> mdns.Device | None:
    if not found:
        ip = ui.ask("The TV's IP address (Settings, Network & Internet, your network), or Enter to stop", "")
        return mdns.Device(ip=ip) if re.match(r"^\d{1,3}(\.\d{1,3}){3}$", ip) else None
    if len(found) == 1:
        return found[0]
    return found[ui.choose("Which TV?", [d.label() for d in found])]


# ---- 2. developer options and wireless debugging, with the remote ---------------------------------

def guide_developer_options() -> None:
    ui.say("On the TV, with the remote (this is a one-time switch; nothing is erased):")
    ui.remote("Press Home", "Go to the gear (Settings) at the top right, press OK", "System  >  About",
              "Scroll to 'Android TV OS build' (older TVs: 'Build')", "Press OK on it 7 times, until it says 'You are now a developer'",
              "Press Back once")


def guide_wireless_debugging() -> None:
    ui.say("Now turn on wireless debugging and ask for a pairing code:")
    ui.remote("Settings  >  System  >  Developer options", "Wireless debugging  >  On (choose 'Allow' if asked)",
              "Press OK on 'Wireless debugging' to open it", "Choose 'Pair device with pairing code'",
              "Leave that screen open: it shows a six-digit code and an address like 192.168.x.x:4xxxx")
    ui.say("  Older Android TV (Android 10 or earlier) has no wireless debugging: turn on 'USB debugging' or 'Network debugging'")
    ui.say("  in Developer options instead; then this wizard connects on port 5555 and you approve the PC on the TV.")


def pair_and_connect(a: Adb, tv: mdns.Device) -> bool:
    for attempt in range(3):
        fresh = {d.ip: d for d in mdns.discover(3.0)}
        d = fresh.get(tv.ip, tv)
        port = d.pairing_port
        if not port:
            raw = ui.ask("The port after the colon on the pairing screen (for example 41235)", "")
            port = int(raw) if raw.isdigit() else 0
        if not port:
            break
        code = ui.ask("The six-digit pairing code on the TV", "")
        try:
            if a.pair(tv.ip, port, code):
                ui.ok("Paired. This PC is now trusted by the TV (you can revoke it in Wireless debugging).")
                break
        except ValueError as e:
            ui.warn(str(e))
            continue
        ui.warn("Pairing didn't work. The code changes each time the screen opens; check it and try again.")
    else:
        return False
    for _ in range(6):
        d = {x.ip: x for x in mdns.discover(3.0)}.get(tv.ip, tv)
        for port in [p for p in (d.connect_port, 5555) if p]:
            if a.connect(tv.ip, port):
                ui.ok(f"Connected to {tv.ip}:{port}.")
                return True
        time.sleep(2)
    raw = ui.ask("The port shown on the Wireless debugging screen under 'IP address & Port'", "")
    return raw.isdigit() and a.connect(tv.ip, int(raw))


def connect_existing(a: Adb, tv: mdns.Device) -> bool:
    """Already paired before: connect without a new code."""
    for port in [p for p in (tv.connect_port, 5555) if p]:
        if a.connect(tv.ip, port):
            return True
    return False


# ---- 3. install --------------------------------------------------------------------------------

def install(a: Adb, chosen: list[str], apk_dir: str | None = None) -> list[str]:
    done = []
    for key in chosen:
        c = apks.by_key(key)
        apk = apks.locate(c, apk_dir)
        if not apk:
            ui.warn(f"{c.title}: no APK available (see 'Build from source' in the README).")
            continue
        ok, msg = a.install(apk)
        if ok:
            ui.ok(f"Installed {c.title}.")
            done.append(key)
        else:
            ui.warn(f"{c.title} didn't install: {msg}")
            if "UPDATE_INCOMPATIBLE" in msg:
                ui.say("  A copy signed with a different key is already on the TV. Uninstall it first (this deletes its data).")
    if "helper" in done:
        enable_helper(a)
    for key in done:
        c = apks.by_key(key)
        if c.optional:
            r = a.shell(f"content call --uri {KEYS_URI} --method addon --arg enable:{c.package}")
            if "enabled=true" in r:
                ui.ok(f"{c.title} is enabled in Friday (it appears on Friday's rail).")
            else:
                ui.warn(f"Friday couldn't enable {c.title} yet. Open Agent Friday once, then run 'verify'.")
    maker = a.prop("ro.product.manufacturer").lower()
    if "friday-tv" in done:
        if "tcl" in maker:   # vendor quirk: TCL blocks background starts unless allowed
            a.shell(f"appops set {CORE} AUTO_START allow")
            a.shell("appops set org.tvprivacy.adbreak AUTO_START allow")
    return done


def enable_helper(a: Adb) -> None:
    """The helper is an accessibility service; it only works once switched on. Asks first."""
    current = a.shell("settings get secure enabled_accessibility_services").strip()
    if HELPER_PREFIX in current:
        return
    ui.say("Ad Break Guard works as an accessibility service: it reads on-screen ad labels (like 'Ad 1 of 2')")
    ui.say("in the app in front, mutes and covers the ad, and keeps the Cast receiver awake. It has no internet access.")
    if not ui.yes("Switch it on now? (Settings, Accessibility can switch it off any time)", default=True):
        return
    parts = [p for p in current.split(":") if p and p != "null"]
    parts.append(HELPER_SERVICE)
    a.shell("settings put secure enabled_accessibility_services " + ":".join(parts))
    a.shell("settings put secure accessibility_enabled 1")
    ui.ok("Ad Break Guard is on.")


# ---- 5. hardening ------------------------------------------------------------------------------

def hardening(a: Adb, tv_ip: str | None) -> None:
    ui.say("Optional privacy hardening. Every item is off until you say yes, and each has a one-line undo.")
    ui.say("These never touch Google's launcher, the Cast receiver, Google Play services, updates, HDMI or the remote.")
    ui.say("")
    ui.say("First, menu switches only you can press:")
    for title, path in harden.manual_checklist():
        ui.say(f"  • {title}:  " + "  >  ".join(path))
    maker = a.prop("ro.product.manufacturer")
    names = harden.suggest_profiles(maker)
    all_profiles = harden.profiles()
    chosen = []
    for name in names:
        p = all_profiles[name]
        ui.say("")
        ui.say(f"{p['name']}: {p['description']}")
        if name != "generic" and not ui.yes(f"Show the {p['name']} checklist?", default=False):
            continue
        for it in harden.items(p):
            ui.say("")
            ui.say(f"  {it.title}")
            ui.say(f"    What it is:    {it.why}")
            ui.say(f"    What you lose: {it.lose}")
            if it.caution:
                ui.say(f"    Caution:       {it.caution}")
            ui.say(f"    Undo:          {it.undo_line()}")
            if ui.yes("    Do this?", default=False):
                chosen.append(it)
    if not chosen:
        ui.say("No hardening changes made.")
        return
    harden.apply(a, chosen, tv_ip)


# ---- 6. verify, undo -----------------------------------------------------------------------------

def verify(a: Adb, tv_ip: str | None = None) -> dict:
    report = {}
    for c in apks.COMPONENTS:
        if a.has_package(c.package):
            report[c.key] = a.version_of(c.package) or "installed"
    report["keys"] = {k: v for k, v in keys.status(a).items() if v} if a.has_package(CORE) else {}
    report["providers"] = a.shell(f"content call --uri {KEYS_URI} --method providers") if a.has_package(CORE) else ""
    report["helper_on"] = HELPER_PREFIX in a.shell("settings get secure enabled_accessibility_services")
    report["addons"] = a.shell(f"content call --uri {KEYS_URI} --method addon --arg list") if a.has_package(CORE) else ""
    if tv_ip:
        report["cast_discoverable"] = harden.cast_visible(tv_ip)
    report["hardening_changes"] = len(harden._load(a.serial))
    ui.say("")
    ui.say("What's on the TV now:")
    for c in apks.COMPONENTS:
        ui.say(f"  {c.title:32} {report.get(c.key, 'not installed')}")
    ui.say(f"  {'Keys present':32} {', '.join(report['keys']) or 'none'}")
    plan = report["providers"].split("plan=", 1)[-1].rstrip("}]") if "plan=" in report["providers"] else "unknown"
    ui.say(f"  {'Services in use':32} {plan}")
    ui.say(f"  {'Ad Break Guard switched on':32} {'yes' if report['helper_on'] else 'no'}")
    if tv_ip:
        ui.say(f"  {'Cast still discoverable':32} {'yes' if report['cast_discoverable'] else 'NO: run undo, see docs/NEVER-BREAK.md'}")
    ui.say(f"  {'Hardening changes (undoable)':32} {report['hardening_changes']}")
    return report


def uninstall_everything(a: Adb) -> None:
    ui.say("This undoes every hardening change, removes your keys from the TV, switches the helper off,")
    ui.say("and uninstalls Agent Friday, its helper and add-ons (their data, history and channels go with them).")
    if not ui.yes("Undo and remove everything?", default=False):
        return
    n = harden.undo(a)
    ui.ok(f"Undid {n} hardening change(s).")
    if a.has_package(CORE):
        keys.remove(a)
        ui.ok("Removed the keys from the TV.")
    current = a.shell("settings get secure enabled_accessibility_services").strip()
    if HELPER_PREFIX in current:
        rest = ":".join(p for p in current.split(":") if p and p != "null" and not p.startswith(HELPER_PREFIX))
        a.shell("settings put secure enabled_accessibility_services " + rest if rest else "settings delete secure enabled_accessibility_services")
    for c in reversed(apks.COMPONENTS):
        if a.has_package(c.package):
            ui.ok(f"Uninstalled {c.title}." if a.uninstall(c.package) else f"Couldn't uninstall {c.title}; remove it in Settings, Apps.")
    ui.say("")
    ui.say("Last, on the TV, switch debugging off again:")
    ui.remote("Settings  >  System  >  Developer options", "Wireless debugging  >  Off",
              "Optional: 'Developer options' toggle at the top  >  Off")
    if a.serial:
        a.run("disconnect", a.serial, device=False)
