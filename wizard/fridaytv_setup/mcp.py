"""The Friday desktop extension: the setup wizard as an MCP server (stdio, standard library only).

Friday desktop lists these as mcp_friday-tv_<tool>. Reads are plain reads; everything that
changes the TV is an action, so Friday shows its approval card before it runs. API keys never
pass through Friday's chat or model: tv_provision_keys opens a separate window on this PC
with hidden prompts, and the keys go from there straight to the TV.

Run: python -m fridaytv_setup.mcp   (from the wizard folder; see extensions/friday-desktop/README.md)
"""
from __future__ import annotations

import io
import json
import platform
import shlex
import subprocess
import sys
from contextlib import redirect_stdout
from pathlib import Path

from . import __version__, apks, harden, mdns, steps, ui
from .adb import Adb, ensure_adb

PROTOCOL = "2025-06-18"

TOOLS = [
    {"name": "tv_discover", "description": "Find Android TV / Google TV devices on the local network (mDNS). Read-only.",
     "inputSchema": {"type": "object", "properties": {}}},
    {"name": "tv_setup_guide", "description": "The remote-control presses for a setup step: developer-options, wireless-debugging, or switch-off. Read-only.",
     "inputSchema": {"type": "object", "properties": {"step": {"type": "string", "enum": ["developer-options", "wireless-debugging", "switch-off"]}}, "required": ["step"]}},
    {"name": "tv_pair", "description": "Pair this PC with the TV using the six-digit code its Wireless debugging screen shows, then connect.",
     "inputSchema": {"type": "object", "properties": {"ip": {"type": "string"}, "pairing_port": {"type": "integer"}, "code": {"type": "string"}}, "required": ["ip", "code"]}},
    {"name": "tv_connect", "description": "Connect to a TV that was paired before (no code needed).",
     "inputSchema": {"type": "object", "properties": {"ip": {"type": "string"}, "port": {"type": "integer"}}, "required": ["ip"]}},
    {"name": "tv_install", "description": "Install Agent Friday and its helper on the connected TV, plus optional add-ons (news). Uses release or already-built APKs.",
     "inputSchema": {"type": "object", "properties": {"device": {"type": "string"}, "addons": {"type": "array", "items": {"type": "string", "enum": ["news"]}},
                     "switch_on_helper": {"type": "boolean", "description": "Switch on Ad Break Guard's accessibility service (the person agreed)."}}, "required": ["device", "switch_on_helper"]}},
    {"name": "tv_provision_keys", "description": "Open a window on this PC where the person types their own API keys (hidden). The keys go straight to the TV; they never pass through Friday.",
     "inputSchema": {"type": "object", "properties": {"device": {"type": "string"}}, "required": ["device"]}},
    {"name": "tv_hardening_checklist", "description": "The optional privacy checklist for this TV: each item, what it is, what you lose, and its one-line undo. Read-only.",
     "inputSchema": {"type": "object", "properties": {"device": {"type": "string"}}}},
    {"name": "tv_harden", "description": "Apply chosen checklist items (by id). Never touches Google's launcher, Cast, Play services, updates, HDMI or the remote; rolls back if Cast disappears.",
     "inputSchema": {"type": "object", "properties": {"device": {"type": "string"}, "ids": {"type": "array", "items": {"type": "string"}}}, "required": ["device", "ids"]}},
    {"name": "tv_verify", "description": "What's installed, which keys are present (never their values), the helper, add-ons, Cast discoverability. Read-only.",
     "inputSchema": {"type": "object", "properties": {"device": {"type": "string"}}, "required": ["device"]}},
    {"name": "tv_undo_hardening", "description": "Undo every hardening change on this TV, newest first.",
     "inputSchema": {"type": "object", "properties": {"device": {"type": "string"}}, "required": ["device"]}},
    {"name": "tv_uninstall_everything", "description": "Undo hardening, remove the keys, switch the helper off and uninstall Agent Friday and its add-ons. confirm must be REMOVE.",
     "inputSchema": {"type": "object", "properties": {"device": {"type": "string"}, "confirm": {"type": "string"}}, "required": ["device", "confirm"]}},
]


def _adb(device: str | None) -> tuple[Adb, str | None]:
    a = Adb(ensure_adb(interactive=False))
    if device:
        host, _, port = device.partition(":")
        if not a.connect(host, int(port or 5555)):
            raise RuntimeError(f"Couldn't connect to {device}. Wireless debugging switches off when the TV restarts; turn it on again.")
        return a, host
    return a, None


def _open_key_window(device: str) -> str:
    wizard_dir = Path(__file__).resolve().parents[1]
    args = [sys.executable, "-m", "fridaytv_setup", "keys", "--device", device]
    if platform.system() == "Windows":
        subprocess.Popen(args, cwd=wizard_dir, creationflags=subprocess.CREATE_NEW_CONSOLE)
    elif platform.system() == "Darwin":
        cmd = f"cd {shlex.quote(str(wizard_dir))} && " + " ".join(shlex.quote(x) for x in args)
        subprocess.Popen(["osascript", "-e", f'tell application "Terminal" to do script {json.dumps(cmd)}'])
    else:
        subprocess.Popen(["x-terminal-emulator", "-e"] + args, cwd=wizard_dir)
    return "A window titled with the setup prompts is open on this PC. Type your keys there; they go straight to the TV."


def call(name: str, a: dict) -> str:
    buf = io.StringIO()
    with redirect_stdout(buf):
        if name == "tv_discover":
            found = [d for d in mdns.discover() if d.is_tv]
            return json.dumps([{"ip": d.ip, "name": d.name, "model": d.model, "pairing_port": d.pairing_port,
                                "connect_port": d.connect_port, "cast": d.cast} for d in found])
        if name == "tv_setup_guide":
            {"developer-options": steps.guide_developer_options, "wireless-debugging": steps.guide_wireless_debugging,
             "switch-off": lambda: ui.remote("Settings  >  System  >  Developer options", "Wireless debugging  >  Off")}[a["step"]]()
        elif name == "tv_pair":
            adb = Adb(ensure_adb(interactive=False))
            port = int(a.get("pairing_port") or 0) or next((d.pairing_port for d in mdns.discover(3) if d.ip == a["ip"]), 0)
            if not port:
                return "I can't see the pairing port. Ask for the number after the colon on the TV's pairing screen."
            if not adb.pair(a["ip"], port, a["code"]):
                return "Pairing failed. The code changes each time the pairing screen opens; ask for the current one."
            ok = steps.connect_existing(adb, next((d for d in mdns.discover(3) if d.ip == a["ip"]), mdns.Device(ip=a["ip"])))
            print(f"Paired. Connected: {'yes, device ' + adb.serial if ok else 'not yet; use tv_connect with the port on the Wireless debugging screen'}")
        elif name == "tv_connect":
            adb = Adb(ensure_adb(interactive=False))
            ok = adb.connect(a["ip"], int(a.get("port") or 5555)) if a.get("port") else \
                steps.connect_existing(adb, next((d for d in mdns.discover(3) if d.ip == a["ip"]), mdns.Device(ip=a["ip"])))
            print(f"Connected as {adb.serial}." if ok else "Not connected. Is Wireless debugging on? Give the port from its screen.")
        elif name == "tv_install":
            adb, _ = _adb(a["device"])
            if not a.get("switch_on_helper"):
                ui.say("The helper will be installed but stays off; Settings, Accessibility switches it on.")
            with _answer(a.get("switch_on_helper", False)):
                done = steps.install(adb, ["friday-tv", "helper"] + list(a.get("addons", [])))
            if not done:
                print("No APKs were available. Build them once in a terminal: python -m fridaytv_setup install --device " + a["device"])
        elif name == "tv_provision_keys":
            return _open_key_window(a["device"])
        elif name == "tv_hardening_checklist":
            maker = ""
            if a.get("device"):
                adb, _ = _adb(a["device"])
                maker = adb.prop("ro.product.manufacturer")
            out = {"menu_switches": [{"title": t, "presses": p} for t, p in harden.manual_checklist()], "profiles": {}}
            for n in harden.suggest_profiles(maker):
                p = harden.profiles()[n]
                out["profiles"][n] = {"name": p["name"], "items": [{"id": i.id, "title": i.title, "what_it_is": i.why, "what_you_lose": i.lose,
                                                                    "caution": i.caution, "undo": i.undo_line()} for i in harden.items(p)]}
            return json.dumps(out, ensure_ascii=False)
        elif name == "tv_harden":
            adb, ip = _adb(a["device"])
            wanted = set(a.get("ids", []))
            chosen = [i for p in harden.profiles().values() for i in harden.items(p) if i.id in wanted]
            harden.apply(adb, chosen, ip)
        elif name == "tv_verify":
            adb, ip = _adb(a["device"])
            return json.dumps(steps.verify(adb, ip))
        elif name == "tv_undo_hardening":
            adb, _ = _adb(a["device"])
            print(f"Undid {harden.undo(adb)} change(s).")
        elif name == "tv_uninstall_everything":
            if a.get("confirm") != "REMOVE":
                return "Not done: confirm must be REMOVE."
            adb, _ = _adb(a["device"])
            with _answer(True):
                steps.uninstall_everything(adb)
        else:
            raise KeyError(name)
    return buf.getvalue().strip() or "Done."


class _answer:
    """Inside a tool call the person has already answered on Friday's card: yes() returns that answer."""
    def __init__(self, value: bool):
        self.value = value
    def __enter__(self):
        self.saved = ui.yes
        ui.yes = lambda q, default=False: self.value
    def __exit__(self, *exc):
        ui.yes = self.saved


def handle(msg: dict) -> dict | None:
    mid, method, params = msg.get("id"), msg.get("method"), msg.get("params") or {}
    if mid is None:
        return None   # a notification
    if method == "initialize":
        res = {"protocolVersion": PROTOCOL, "capabilities": {"tools": {}},
               "serverInfo": {"name": "friday-tv", "version": __version__}}
    elif method == "ping":
        res = {}
    elif method == "tools/list":
        res = {"tools": TOOLS}
    elif method == "tools/call":
        try:
            text = call(params.get("name", ""), params.get("arguments") or {})
            res = {"content": [{"type": "text", "text": text}], "isError": False}
        except KeyError as e:
            return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32602, "message": f"unknown tool or missing argument: {e}"}}
        except Exception as e:   # reported to the model, never with secrets (tools never receive any)
            res = {"content": [{"type": "text", "text": f"{type(e).__name__}: {e}"}], "isError": True}
    else:
        return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": f"no method {method}"}}
    return {"jsonrpc": "2.0", "id": mid, "result": res}


def main() -> int:
    out = sys.stdout
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except json.JSONDecodeError:
            continue
        reply = handle(msg)
        if reply is not None:
            out.write(json.dumps(reply) + "\n")
            out.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
