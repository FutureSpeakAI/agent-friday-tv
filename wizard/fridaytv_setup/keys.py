"""Your own API keys, from this PC to the TV, without printing, logging or saving them.

How they travel: you type each key at a hidden prompt; the wizard holds them in memory,
checks the Anthropic key against Anthropic's API (only "works" or "doesn't" is shown), and
streams one JSON object over adb's stdin into the TV app's key importer, which seals it with
the Android Keystore at once. Nothing is written to the PC's or the TV's storage in the clear,
and nothing appears on a command line. Your keys go only to your TV and to the provider
that issued them.
"""
from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request

from . import providers as prov
from . import ui
from .adb import Adb

PKG = "ai.futurespeak.friday.tv"
IMPORT_URI = f"content://{PKG}.keys/import"
STATUS_URI = f"content://{PKG}.keys/status"

#: (name on the TV, what it's for, where to get one, required)
PROVIDERS = [
    ("anthropic", "Claude, Friday's answers (required)", "https://console.anthropic.com/settings/keys", True),
    ("elevenlabs", "Friday's spoken voice (optional; without it the TV's own voice is used)", "https://elevenlabs.io/app/settings/api-keys", False),
    ("openrouter", "Other AI models as a fallback (optional)", "https://openrouter.ai/settings/keys", False),
    ("gemini", "Gemini Live voice conversations (optional)", "https://aistudio.google.com/apikey", False),
    ("firecrawl", "Reading web pages for answers (optional, paid per use)", "https://www.firecrawl.dev/app/api-keys", False),
    ("kie", "Pictures in answers and Studio (optional, paid per use)", "https://kie.ai/api-key", False),
]


def check_anthropic(key: str) -> tuple[bool, list[str]]:
    """True when the key is accepted; returns model ids (names only, never the key)."""
    req = urllib.request.Request("https://api.anthropic.com/v1/models?limit=100",
                                 headers={"x-api-key": key, "anthropic-version": "2023-06-01"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            data = json.loads(r.read().decode("utf-8"))
        return True, [m.get("id", "") for m in data.get("data", [])]
    except urllib.error.HTTPError as e:
        return (e.code not in (401, 403)), []
    except Exception:
        return True, []   # offline or blocked: can't tell; the TV will say if it's refused


def geocode(city: str) -> list[dict]:
    """Open-Meteo's free geocoder (no key, no account). Sends only the place name you typed."""
    q = urllib.parse.urlencode({"name": city, "count": 5, "language": "en", "format": "json"})
    with urllib.request.urlopen(f"https://geocoding-api.open-meteo.com/v1/search?{q}", timeout=15) as r:
        return json.loads(r.read().decode("utf-8")).get("results", []) or []


def ask_place() -> dict | None:
    """The TV's place, for the weather only. Optional; nothing is fetched without one."""
    ui.say("Friday can show your local weather. That needs a place (a town or city), used only")
    ui.say("to ask Open-Meteo for the forecast. Skip it and no weather is ever fetched.")
    city = ui.ask("Town or city for the weather (Enter to skip)", "")
    if not city:
        return None
    if not ui.yes(f"Look up '{city}' with Open-Meteo's free geocoder?", default=True):
        raw = ui.ask("Then type latitude,longitude (for example 51.5,-0.12), or Enter to skip", "")
        try:
            lat, lon = (float(x) for x in raw.split(","))
            return {"name": city, "lat": lat, "lon": lon, "units": "F" if ui.yes("Fahrenheit?", False) else "C"}
        except ValueError:
            return None
    try:
        found = geocode(city)
    except Exception:
        ui.warn("The geocoder didn't answer; skipping the weather. You can run 'keys' again later.")
        return None
    if not found:
        ui.warn("No place by that name. Skipping the weather.")
        return None
    labels = [", ".join(x for x in (r.get("name"), r.get("admin1"), r.get("country")) if x) for r in found]
    r = found[ui.choose("Which one?", labels)]
    f_default = r.get("country_code") in ("US", "LR", "MM")
    units = "F" if ui.yes("Show temperatures in Fahrenheit?", default=f_default) else "C"
    return {"name": r.get("name", city), "lat": round(float(r["latitude"]), 4), "lon": round(float(r["longitude"]), 4), "units": units}


def status(a: Adb) -> dict[str, bool]:
    out = a.shell(f"content query --uri {STATUS_URI}")
    present = {}
    for name, *_ in PROVIDERS:
        present[name] = f"{name}, present=yes" in out
    return present


def provision(a: Adb, include_place: bool = True, providers_file: str | None = None) -> bool:
    if not a.has_package(PKG):
        ui.warn("Agent Friday isn't installed on this TV yet. Install it first.")
        return False
    ui.say("Your keys go only to your TV (sealed by its Android Keystore) and to the provider")
    ui.say("that issued them. Nothing is shown, logged or saved on this PC.")
    chosen: dict = {}
    if providers_file:
        try:
            chosen = prov.load(providers_file)
        except (prov.NotAllowed, ValueError, OSError) as e:
            ui.warn(f"Not using {providers_file}: {e}")
            return False
    payload: dict = {}
    models: list[str] = []
    for name, what, where, required in PROVIDERS:
        ui.say("")
        ui.say(f"{name.title()} key: {what}")
        ui.say(f"  Get one at {where}")
        v = ui.secret(f"  Paste your {name.title()} key")
        if not v:
            if required:
                ui.warn("Friday needs a Claude key to answer. You can come back to this step any time.")
                return False
            continue
        if name == "anthropic":
            ok, models = check_anthropic(v)
            if not ok:
                ui.warn("Anthropic refused that key. Nothing was sent to the TV.")
                return False
            ui.ok("Anthropic key works.")
            opus = [m for m in models if m.startswith("claude-opus-")]
            if opus and "claude-opus-5-5" not in models:
                payload["model"] = opus[0]
            models = list(models)
        payload[name] = v
    if not providers_file:
        ui.say("")
        chosen = prov.choose(models, payload.get("elevenlabs"))
    if chosen:
        payload["providers"] = chosen
        if chosen.get("text", {}).get("model"):
            payload["model"] = chosen["text"]["model"]
    ui.say("")
    ui.say("With these keys, on the TV:")
    for line in prov.describe(prov.plan(chosen, {k for k in payload if k in {p[0] for p in PROVIDERS}})):
        ui.say("  " + line)
    if include_place:
        ui.say("")
        place = ask_place()
        if place:
            payload["place"] = place
    data = json.dumps(payload).encode("utf-8")
    payload.clear()
    sent = False
    for _ in range(3):
        a.write_stdin_to_content(IMPORT_URI, data)
        st = status(a)
        if st.get("anthropic"):
            sent = True
            break
    data = b""
    if sent:
        ui.ok("Keys are on the TV: " + ", ".join(k for k, v in status(a).items() if v))
    else:
        ui.warn("The TV didn't confirm the keys. Open Agent Friday on the TV once, then try this step again.")
    return sent


def remove(a: Adb) -> None:
    a.shell(f"content delete --uri content://{PKG}.keys/keys")
