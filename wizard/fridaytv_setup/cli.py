"""Agent Friday TV setup: find your Android TV, pair, install, add your keys, harden (optional), verify.

  python -m fridaytv_setup                 the whole guided setup
  python -m fridaytv_setup discover        list TVs on the network (add --json for machines)
  python -m fridaytv_setup install --device 192.168.0.20:41234 --with news
  python -m fridaytv_setup keys --device …
  python -m fridaytv_setup speech --device …      Friday's own offline speech recognizer (optional)
  python -m fridaytv_setup harden --device …
  python -m fridaytv_setup verify --device …
  python -m fridaytv_setup undo --device …        undo hardening only
  python -m fridaytv_setup uninstall --device …   undo and remove everything
"""
from __future__ import annotations

import argparse
import json
import sys

from . import __version__, apks, harden, keys, mdns, speech, steps, ui
from .adb import Adb, ensure_adb


def _device(a: Adb, given: str | None) -> str | None:
    if given:
        host, _, port = given.partition(":")
        if a.connect(host, int(port or 5555)):
            return host
        raise SystemExit(f"Couldn't connect to {given}. Is wireless debugging still on? (It switches off when the TV restarts.)")
    devs = a.devices()
    if len(devs) == 1:
        a.serial = devs[0]
        return devs[0].split(":")[0] if ":" in devs[0] else None
    raise SystemExit("Say which TV with --device <ip:port> (see 'discover'), or run the full setup without arguments.")


def wizard(args) -> None:
    total = 6
    ui.say(f"Agent Friday TV setup {__version__}")
    ui.say("About 10 minutes. You'll need the TV remote and your Claude API key.")
    ui.say("Nothing here sends telemetry; your keys go only to your TV and the provider that issued them.")

    ui.step(1, total, "Find your TV")
    a = Adb(ensure_adb())
    tv = steps.choose_tv(steps.find_tvs())
    if not tv:
        raise SystemExit("No TV chosen.")
    ui.ok(f"Using {tv.label()}")

    ui.step(2, total, "Let this PC install apps on the TV")
    if not steps.connect_existing(a, tv):
        steps.guide_developer_options()
        ui.ask("Press Enter when the TV says you are a developer", "")
        steps.guide_wireless_debugging()
        ui.ask("Press Enter when the pairing code is on the TV", "")
        if not steps.pair_and_connect(a, tv):
            raise SystemExit("Couldn't pair. See Troubleshooting in the README; nothing on the TV was changed.")
    else:
        ui.ok("Already paired; connected.")

    ui.step(3, total, "Install Agent Friday")
    chosen = ["friday-tv", "helper"]
    for c in apks.COMPONENTS:
        if c.optional:
            if not c.ready:
                ui.say(f"  {c.title}: {c.blurb}")
            elif ui.yes(f"Also install {c.title}? {c.blurb}", default=True):
                chosen.append(c.key)
    steps.install(a, chosen)
    ui.say("Optional third-party apps (not ours, not bundled; install them from their official pages if you like):")
    for name, what, url in apks.THIRD_PARTY_SUGGESTIONS:
        ui.say(f"  - {name}: {what}. {url}")

    ui.step(4, total, "Your API keys")
    keys.provision(a)
    if ui.yes("Optional: put Friday's own speech recognizer on the TV (offline, about 120 MB download)?", default=False):
        speech.push(a)

    ui.step(5, total, "Privacy hardening (optional)")
    if ui.yes("Look at the optional privacy checklist?", default=False):
        steps.hardening(a, tv.ip)

    ui.step(6, total, "Check everything")
    steps.verify(a, tv.ip)
    ui.say("")
    ui.say("Done. On the TV: Apps, Agent Friday. Hold OK on Talk and ask a question.")
    ui.say("To undo everything later: python -m fridaytv_setup uninstall --device " + (a.serial or "<ip:port>"))


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="fridaytv_setup", description="Agent Friday TV setup")
    p.add_argument("command", nargs="?", default="wizard",
                   choices=["wizard", "discover", "pair", "install", "keys", "speech", "harden", "verify", "undo", "uninstall", "profiles"])
    p.add_argument("--device", help="the TV as ip:port (from 'discover' or the Wireless debugging screen)")
    p.add_argument("--with", dest="extras", action="append", default=[], help="add-on to install: news")
    p.add_argument("--apk-dir", help="install APKs from this folder")
    p.add_argument("--providers", help="keys: which service and model does each job (a JSON file with no keys; see providers.example.json)")
    p.add_argument("--json", action="store_true", help="machine-readable output (discover, verify, profiles)")
    p.add_argument("--dry-run", action="store_true", help="harden: show what would change, change nothing")
    args = p.parse_args(argv)
    if args.json:
        ui.QUIET = True

    if args.command == "wizard":
        wizard(args)
        return 0
    if args.command == "discover":
        found = [d for d in mdns.discover() if d.is_tv]
        if args.json:
            print(json.dumps([{"ip": d.ip, "name": d.name, "model": d.model, "pairing_port": d.pairing_port,
                               "connect_port": d.connect_port, "cast": d.cast, "tv_remote": d.tv_remote} for d in found]))
        else:
            steps.find_tvs()
        return 0
    if args.command == "profiles":
        out = {n: {"name": pr["name"], "items": [{"id": i.id, "title": i.title, "lose": i.lose, "undo": i.undo_line()} for i in harden.items(pr)]}
               for n, pr in harden.profiles().items()}
        print(json.dumps(out, indent=None if args.json else 1, ensure_ascii=False))
        return 0

    a = Adb(ensure_adb(interactive=not args.json))
    if args.command == "pair":
        tv = steps.choose_tv(steps.find_tvs())
        if tv:
            steps.guide_wireless_debugging()
            return 0 if steps.pair_and_connect(a, tv) else 1
        return 1
    ip = _device(a, args.device)
    if args.command == "install":
        steps.install(a, ["friday-tv", "helper"] + [x for x in args.extras if x in ("news", "wonder-owl")], args.apk_dir)
    elif args.command == "keys":
        return 0 if keys.provision(a, providers_file=args.providers) else 1
    elif args.command == "speech":
        return 0 if speech.push(a) else 1
    elif args.command == "harden":
        if args.dry_run:
            for name in harden.suggest_profiles(a.prop("ro.product.manufacturer")):
                harden.apply(a, harden.items(harden.profiles()[name]), dry_run=True)
        else:
            steps.hardening(a, ip)
    elif args.command == "verify":
        r = steps.verify(a, ip)
        if args.json:
            print(json.dumps(r))
    elif args.command == "undo":
        ui.ok(f"Undid {harden.undo(a)} change(s).")
    elif args.command == "uninstall":
        steps.uninstall_everything(a)
    return 0


if __name__ == "__main__":
    sys.exit(main())
