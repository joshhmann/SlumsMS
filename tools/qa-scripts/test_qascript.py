"""Tests for the client-script validator (ticket 170). Run: python3 -m unittest discover -s tools/qa-scripts -p 'test_*.py'"""
import json
import re
import sys
import unittest
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))
import qascript  # noqa: E402

REPO = qascript.REPO

GOOD = """
id: sample
title: A sample script
covers: [b140]
server: test
needs: [gm]
clients:
  me:
    account: {vault: aq-gm-1}
    world: Bera
    channel: 3
steps:
  - {do: login}
  - {id: worlds, do: wait, signal: {type: client_signal, name: worldselect.ready}, timeout_ms: 30000}
  - {id: pick-world, do: select_world, world: Bera}
  - {id: channels, do: wait, signal: {type: client_signal, name: channels.ready}, retry: {step: pick-world, every_ms: 4000, times: 4}, timeout_ms: 30000}
  - {do: select_channel, channel: 3}
  - {id: chars, do: wait, signal: {type: client_signal, name: charselect.ready}, timeout_ms: 30000}
  - {do: select_character}
  - {id: in-game, do: wait, signal: {type: client_signal, name: field.ready}, timeout_ms: 60000}
  - {id: hurt, do: gm, line: "!hurt ${me.name} 40"}
  - {id: heal, do: wait, armed_at: hurt, signal: {type: server_line, topic: COMBAT, regex: "^PARTY HEAL (?P<who>\\\\S+) -> ${me.name} ", char: me}, bind: {healer: who}, timeout_ms: 30000}
  - {do: gm, line: "!bot remove ${healer}"}
captures:
  - {id: burst-1, burst: {fps: 10, ms: 2000, at: {signal: heal}}}
expect:
  reviewer:
    - {see: "the heal column over the healer", in: [burst-1]}
  checks:
    - {check: server_line, topic: COMBAT, regex: "PARTY HEAL", char: me, min: 1}
"""


def check(text, stem="sample"):
    return qascript.validate_text(text, stem)


class ValidatorTest(unittest.TestCase):

    def assertRefused(self, text, needle):
        errors, _ = check(text)
        self.assertTrue(any(needle in e for e in errors), "expected an error with %r, got %r" % (needle, errors))

    def test_a_good_script_passes(self):
        errors, warnings = check(GOOD)
        self.assertEqual([], errors)

    def test_every_script_in_the_repo_validates(self):
        files = sorted((REPO / "qa-scripts").glob("*.yaml"))
        self.assertGreaterEqual(len(files), 4)
        for f in files:
            errors, _ = qascript.validate_file(f)
            self.assertEqual([], errors, str(f))

    def test_a_timer_needs_a_real_reason(self):
        self.assertRefused(GOOD.replace("  - {do: select_character}\n",
                                        "  - {do: select_character}\n  - {do: sleep, ms: 2000}\n"), "missing reason")
        self.assertRefused(GOOD.replace("  - {do: select_character}\n",
                                        "  - {do: select_character}\n  - {do: sleep, ms: 2000, reason: wait}\n"),
                           "shorter than 15")
        self.assertRefused(GOOD.replace("  - {do: select_character}\n",
                                        "  - {do: select_character}\n  - {do: sleep, ms: 2000, reason: \"               \"}\n"),
                           "real reason")
        many = "".join("  - {do: sleep, ms: 9000, reason: \"no signal exists for this yet, see note\"}\n" for _ in range(4))
        self.assertRefused(GOOD.replace("  - {do: select_character}\n", "  - {do: select_character}\n" + many),
                           "sleeps add up")

    def test_every_wait_has_a_timeout(self):
        self.assertRefused(GOOD.replace(", timeout_ms: 60000}", "}"), "missing timeout_ms")
        self.assertRefused(GOOD.replace("timeout_ms: 60000", "timeout_ms: 900000"), "above")

    def test_unknown_anchors_and_windows_are_refused(self):
        self.assertRefused(GOOD.replace("  - {do: select_character}\n",
                                        "  - {do: select_character}\n  - {do: click, anchor: login.nowhere}\n"),
                           "unknown anchor")
        self.assertRefused(GOOD.replace("  - {do: select_character}\n",
                                        "  - {do: select_character}\n  - {id: w, do: wait, signal: {type: client_signal, name: window.open, window: nope}, timeout_ms: 5000}\n")
                           .replace("needs: [gm]", "needs: [gm, proposed_signals]"), "unknown window")

    def test_proposed_anchors_signals_and_steps_must_be_declared(self):
        with_anchor = GOOD.replace("  - {do: select_character}\n",
                                   "  - {do: select_character}\n  - {do: click, anchor: party_invite.accept}\n")
        self.assertRefused(with_anchor, "proposed_anchors")
        errors, warnings = check(with_anchor.replace("needs: [gm]", "needs: [gm, proposed_anchors]"))
        self.assertEqual([], errors)
        self.assertTrue(any("party_invite.accept" in w for w in warnings))
        self.assertRefused(GOOD.replace("  - {do: select_character}\n",
                                        "  - {do: select_character}\n  - {id: c, do: wait, signal: {type: client_signal, name: chat.received, regex: x}, timeout_ms: 5000}\n"),
                           "proposed_signals")
        self.assertRefused(GOOD.replace("  - {do: select_character}\n",
                                        "  - {do: select_character}\n  - {do: click_npc, npc: 9020001}\n"),
                           "proposed_steps")

    def test_no_credentials_in_the_file(self):
        self.assertRefused(GOOD.replace("notes", "x") + "\n# password: hunter2\n", "credential")
        self.assertRefused(GOOD.replace("account: {vault: aq-gm-1}", "account: {vault: aq-gm-1, password: x}"),
                           "credential")
        self.assertRefused(GOOD.replace("account: {vault: aq-gm-1}", "account: {name: aqclient}"), "missing vault")

    def test_a_channel_click_waits_for_channels_ready(self):
        no_wait = GOOD.replace(
            "  - {id: channels, do: wait, signal: {type: client_signal, name: channels.ready}, retry: {step: pick-world, every_ms: 4000, times: 4}, timeout_ms: 30000}\n",
            "")
        self.assertRefused(no_wait, "channels.ready")

    def test_retry_is_for_a_client_signal_and_an_earlier_step(self):
        self.assertRefused(GOOD.replace("retry: {step: pick-world,", "retry: {step: chars,"), "not an earlier step")
        self.assertRefused(GOOD.replace("every_ms: 4000, times: 4", "every_ms: 10000, times: 4"), "fit inside")
        self.assertRefused(GOOD.replace("bind: {healer: who},", "retry: {step: hurt, every_ms: 1000, times: 1}, bind: {healer: who},"),
                           "client signals only")

    def test_placeholders_and_binds_resolve(self):
        self.assertRefused(GOOD.replace("!hurt ${me.name} 40", "!hurt ${you.name} 40"), "no client")
        self.assertRefused(GOOD.replace("!hurt ${me.name} 40", "!hurt ${me.password} 40"), "a client has only")
        self.assertRefused(GOOD.replace("!bot remove ${healer}", "!bot remove ${other}"), "not bound")
        self.assertRefused(GOOD.replace("bind: {healer: who}", "bind: {healer: nope}"), "has no group")

    def test_references_between_steps_captures_and_checks(self):
        self.assertRefused(GOOD.replace("at: {signal: heal}", "at: {signal: hurt}"), "not a wait step")
        self.assertRefused(GOOD.replace("in: [burst-1]", "in: [burst-2]"), "not a capture id")
        self.assertRefused(GOOD.replace("armed_at: hurt", "armed_at: later"), "not an earlier step")

    def test_two_clients_name_the_client_everywhere(self):
        two = GOOD.replace("steps:", "  you:\n    account: {vault: aq-gm-2}\n    world: Bera\n    channel: 3\nsteps:")
        self.assertRefused(two, "two_clients")
        self.assertRefused(two.replace("needs: [gm]", "needs: [gm, two_clients]"), "say which client")
        same = two.replace("aq-gm-2", "aq-gm-1").replace("needs: [gm]", "needs: [gm, two_clients]")
        self.assertRefused(same, "own vault account")

    def test_yaml_booleans_as_keys_and_values_are_caught(self):
        self.assertRefused(GOOD.replace("at: {signal: heal}", "on: {signal: heal}"), "not text")
        self.assertRefused(GOOD.replace("{id: hurt,", "{id: yes,").replace("armed_at: hurt", "armed_at: x"), "expected string")

    def test_held_keys_are_released(self):
        held = GOOD.replace("  - {do: select_character}\n",
                            "  - {do: select_character}\n  - {do: key_down, key: RIGHT}\n").replace("needs: [gm]", "needs: [gm, proposed_steps]")
        self.assertRefused(held, "never released")
        self.assertRefused(GOOD.replace("  - {do: select_character}\n",
                                        "  - {do: select_character}\n  - {do: key_up, key: RIGHT}\n").replace("needs: [gm]", "needs: [gm, proposed_steps]"),
                           "was not held")

    def test_gm_lines_and_chat_are_kept_apart(self):
        self.assertRefused(GOOD.replace("!bot remove ${healer}\"}", "!bot remove ${healer}\"}\n  - {do: chat, text: \"!warp 1\"}"),
                           "do: gm")
        self.assertRefused(GOOD.replace("line: \"!hurt", "line: \"hurt"), "does not match")

    def test_the_id_matches_the_file_name(self):
        errors, _ = qascript.validate_text(GOOD, "other-name")
        self.assertTrue(any("must match the file name" in e for e in errors))


class ContractTest(unittest.TestCase):
    """The schema and the anchor table stay in step with the server and the client-mcp driver."""

    def test_feed_topics_are_the_qa_log_topics(self):
        java = (REPO / "src/main/java/soloMapling/qa/QaLog.java").read_text(encoding="utf-8")
        body = java[java.index("public enum Topic {"):java.index("final long bit")]
        topics = re.findall(r"^\s*([A-Z_]+)[,;]", body, re.M)
        schema = json.loads(qascript.SCHEMA.read_text(encoding="utf-8"))
        server_line = [b for b in schema["$defs"]["signal"]["oneOf"]
                       if b["properties"]["type"].get("const") == "server_line"][0]
        self.assertEqual(sorted(topics + ["QA_RUN"]), sorted(server_line["properties"]["topic"]["enum"]))
        feed = (REPO / "src/main/java/soloMapling/inspect/QaEventFeed.java").read_text(encoding="utf-8")
        self.assertIn('QA_RUN = "QA_RUN"', feed)

    def test_the_staged_b140_script_waits_on_server_lines_only(self):
        """b140: the follower is staged by !qa run hc-1, so nothing after login needs a chat wait or a window."""
        path = REPO / "qa-scripts/b140-bot-heal-column-staged.yaml"
        errors, _ = qascript.validate_file(path)
        self.assertEqual([], errors)
        steps = yaml.safe_load(path.read_text(encoding="utf-8"))["steps"]
        after_login = steps[steps.index(next(s for s in steps if s.get("id") == "in-game")) + 1:]
        waits = [(s.get("signal") or {}).get("type") for s in after_login if s.get("do") == "wait"]
        self.assertEqual(["server_line", "server_line", "server_line"], waits)
        topics = [(s.get("signal") or {}).get("topic") for s in after_login if s.get("do") == "wait"]
        self.assertEqual(["MAP_CHANGE", "STAGING", "COMBAT"], topics)
        self.assertIn("!qa run hc-1", [s.get("line") for s in after_login if s.get("do") == "gm"])
        for name in ("chat.received", "window.open", "window.closed"):
            self.assertNotIn(name, [(s.get("signal") or {}).get("name") for s in steps], name)

    def test_calibrated_anchors_are_points_the_client_mcp_driver_clicks(self):
        anchors, windows = qascript.load_anchors()
        driver = (REPO / "tools/ops/client-mcp-win/src/driver.py").read_text(encoding="utf-8")
        for name, a in anchors.items():
            self.assertIn(a["status"], ("calibrated", "proposed"), name)
            if a["status"] == "calibrated":
                point = a["uijson"]
                if point.startswith("channel_") and point != "channel_confirm":
                    self.assertIn("'channel_' + str(channel)", driver)
                else:
                    self.assertIn("'%s'" % point, driver, name)
        self.assertIn("party_invite", windows)

    def test_the_qa_build_bit_matches_the_gate(self):
        gate = (REPO / "src/main/java/net/server/handlers/login/ClientVersionGate.java").read_text(encoding="utf-8")
        self.assertIn("CAP_QA_BUILD = 0x80000000", gate)


if __name__ == "__main__":
    unittest.main()
