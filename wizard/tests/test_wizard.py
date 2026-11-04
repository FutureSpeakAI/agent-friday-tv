"""Tests for the setup wizard. Standard library only: python -m unittest discover -s wizard/tests"""
import io
import json
import os
import struct
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from fridaytv_setup import harden, keys, mdns  # noqa: E402


def name(n: str) -> bytes:
    return mdns._encode_name(n)


def rr(owner: str, rtype: int, rdata: bytes) -> bytes:
    return name(owner) + struct.pack("!HHIH", rtype, 1, 120, len(rdata)) + rdata


def cast_answer() -> bytes:
    inst = "Den-TV-abc._googlecast._tcp.local"
    txt = b"".join(bytes([len(x)]) + x for x in (b"fn=Den TV", b"md=Google TV"))
    recs = [
        rr("_googlecast._tcp.local", mdns.T_PTR, name(inst)),
        rr(inst, mdns.T_SRV, struct.pack("!HHH", 0, 0, 8009) + name("abc.local")),
        rr(inst, mdns.T_TXT, txt),
        rr("abc.local", mdns.T_A, bytes([192, 0, 2, 7])),
        rr("_adb-tls-connect._tcp.local", mdns.T_PTR, name("adb-xyz._adb-tls-connect._tcp.local")),
        rr("adb-xyz._adb-tls-connect._tcp.local", mdns.T_SRV, struct.pack("!HHH", 0, 0, 40123) + name("abc.local")),
    ]
    return struct.pack("!HHHHHH", 0, 0x8400, 0, len(recs), 0, 0) + b"".join(recs)


class MdnsTest(unittest.TestCase):
    def test_query_asks_every_service(self):
        q = mdns.build_query()
        self.assertEqual(struct.unpack("!H", q[4:6])[0], len(mdns.SERVICES))
        for s in mdns.SERVICES:
            self.assertIn(name(s), q)

    def test_a_cast_tv_with_wireless_debugging_is_found(self):
        devs = mdns.collect([("192.0.2.7", mdns.parse_packet(cast_answer()))])
        self.assertEqual(len(devs), 1)
        d = devs[0]
        self.assertEqual((d.ip, d.name, d.model, d.connect_port, d.cast), ("192.0.2.7", "Den TV", "Google TV", 40123, True))
        self.assertTrue(d.is_tv)

    def test_garbage_and_pointer_loops_are_ignored(self):
        self.assertEqual(mdns.parse_packet(b"\x00" * 5), [])
        loop = struct.pack("!HHHHHH", 0, 0x8400, 0, 1, 0, 0) + b"\xc0\x0c" + struct.pack("!HHIH", 12, 1, 1, 2) + b"\xc0\x0c"
        self.assertEqual(mdns.parse_packet(loop), [])


class NeverBreakTest(unittest.TestCase):
    def test_the_never_break_packages_are_refused(self):
        for pkg in ("com.google.android.apps.tv.launcherx", "com.google.android.apps.mediashell", "com.google.android.gms",
                    "com.android.tv.mdnsoffloadmanager", "vendor.mediatek.tv.googlecast", "com.android.vending",
                    "com.example.ota.agent", "com.tcl.tvinput", "ai.futurespeak.friday.tv", "org.tvprivacy.adbreak"):
            with self.assertRaises(harden.NeverBreak, msg=pkg):
                harden.check_package(pkg)

    def test_every_bundled_profile_item_is_allowed_and_undoable(self):
        ps = harden.profiles()
        self.assertIn("generic", ps)
        for pname, p in ps.items():
            for it in harden.items(p):
                self.assertTrue(it.undo_line().startswith("adb shell "), it.id)
                self.assertTrue(it.why and it.lose, it.id)

    def test_a_profile_cannot_smuggle_a_command(self):
        bad = harden.Item("x-bad", "t", "w", "l", "setting", "global/foo", value="1; reboot")
        with self.assertRaises(harden.NeverBreak):
            bad.validate()
        with self.assertRaises(harden.NeverBreak):
            harden.Item("x-acc", "t", "w", "l", "setting", "secure/enabled_accessibility_services", value="0").validate()

    def test_vendor_profiles_only_for_their_vendor(self):
        self.assertEqual(harden.suggest_profiles("Google"), ["generic"])
        self.assertIn("tcl", harden.suggest_profiles("TCL"))

    def test_journal_comes_before_the_change_and_undo_reverses_it(self):
        with tempfile.TemporaryDirectory() as d, mock.patch("fridaytv_setup.adb.home", return_value=Path(d)):
            calls = []

            class FakeAdb:
                serial = "192.0.2.7:40123"
                def has_package(self, p): return True
                def shell(self, c):
                    calls.append((c, harden.journal_path(self.serial).exists()))
                    return "Package com.tcl.ttvs new state: disabled-user" if c.startswith("pm disable") else ""

            it = harden.Item("tcl-ttvs", "t", "w", "l", "package", "com.tcl.ttvs").validate()
            with redirect_stdout(io.StringIO()):
                harden.apply(FakeAdb(), [it])
                self.assertEqual(calls[0], ("pm disable-user --user 0 com.tcl.ttvs", True))
                self.assertEqual(harden.undo(FakeAdb()), 1)
            self.assertEqual(calls[-1][0], "pm enable --user 0 com.tcl.ttvs")


class KeysTest(unittest.TestCase):
    def test_keys_never_reach_output_or_a_command_line(self):
        secret = "sk-ant-TEST-not-a-real-key-0000"
        sent = {}

        class FakeAdb:
            serial = "x"
            def has_package(self, p): return True
            def write_stdin_to_content(self, uri, payload):
                sent["uri"], sent["payload"] = uri, payload
                return True
            def shell(self, c):
                self.last = c
                assert secret not in c
                return "Row: 0 name=anthropic, present=yes"

        out = io.StringIO()
        answers = iter([secret, "", "", "", "", ""])
        with mock.patch("fridaytv_setup.ui.secret", lambda q: next(answers)), \
             mock.patch("fridaytv_setup.keys.check_anthropic", return_value=(True, ["claude-opus-5-5"])), \
             mock.patch("fridaytv_setup.keys.ask_place", return_value=None),              mock.patch("fridaytv_setup.providers.choose", return_value={}), redirect_stdout(out):
            self.assertTrue(keys.provision(FakeAdb()))
        self.assertNotIn(secret, out.getvalue())
        self.assertEqual(sent["uri"], keys.IMPORT_URI)
        self.assertEqual(json.loads(sent["payload"])["anthropic"], secret)

    def test_a_refused_key_sends_nothing(self):
        class FakeAdb:
            def has_package(self, p): return True
            def write_stdin_to_content(self, uri, payload): raise AssertionError("sent")
        answers = iter(["bad"])
        with mock.patch("fridaytv_setup.ui.secret", lambda q: next(answers)), \
             mock.patch("fridaytv_setup.keys.check_anthropic", return_value=(False, [])), redirect_stdout(io.StringIO()):
            self.assertFalse(keys.provision(FakeAdb()))


class ProvidersTest(unittest.TestCase):
    def test_the_example_file_is_valid_and_has_no_keys(self):
        from fridaytv_setup import providers
        p = providers.load(str(Path(__file__).resolve().parents[2] / "providers.example.json"))
        self.assertEqual(p["text"]["provider"], "anthropic")

    def test_a_key_in_the_file_is_refused_wherever_it_hides(self):
        from fridaytv_setup import providers
        for bad in ({"text": {"api_key": "x"}}, {"voice": {"model": "sk-ant-0123456789abc"}}, {"extra": ["A" * 40]}):
            with self.assertRaises(providers.NotAllowed):
                providers.clean(bad)

    def test_the_plan_matches_the_tv_fallbacks(self):
        from fridaytv_setup import providers
        self.assertEqual(providers.plan({}, set()),
                         {"text": "none", "voice": "tv", "sound": "none", "music": "none", "images": "stills", "video": "stills"})
        self.assertEqual(providers.plan({"voice": {"provider": "tv"}}, {"anthropic", "elevenlabs", "kie"})["voice"], "tv")
        self.assertEqual(providers.plan({}, {"anthropic", "elevenlabs", "kie"})["images"], "kie")


class McpTest(unittest.TestCase):
    def test_initialize_and_list_tools(self):
        from fridaytv_setup import mcp
        init = mcp.handle({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}})
        self.assertEqual(init["result"]["serverInfo"]["name"], "friday-tv")
        self.assertIsNone(mcp.handle({"jsonrpc": "2.0", "method": "notifications/initialized"}))
        names = {t["name"] for t in mcp.handle({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})["result"]["tools"]}
        self.assertIn("tv_provision_keys", names)
        for t in mcp.TOOLS:   # keys never travel as a tool argument
            self.assertNotRegex(json.dumps(t["inputSchema"]).lower(), r"api_?key|secret|token")

    def test_uninstall_needs_the_word(self):
        from fridaytv_setup import mcp
        r = mcp.handle({"jsonrpc": "2.0", "id": 3, "method": "tools/call",
                        "params": {"name": "tv_uninstall_everything", "arguments": {"device": "192.0.2.7:5555", "confirm": "yes"}}})
        self.assertIn("confirm must be REMOVE", r["result"]["content"][0]["text"])


class NoLauncherDisableTest(unittest.TestCase):
    def test_no_script_disables_googles_launcher(self):
        root = Path(__file__).resolve().parents[2]
        for p in list(root.glob("wizard/fridaytv_setup/**/*.py")) + list(root.glob("**/*.ps1")):
            t = p.read_text(encoding="utf-8", errors="replace")
            self.assertNotRegex(t, r"pm\s+(disable|uninstall|hide)[^\n]*launcherx", str(p))


if __name__ == "__main__":
    unittest.main()
