#!/usr/bin/env python3
"""Tests for the cloud chat checks in slumsms-monitor (ticket s-llmmonitor): today's DeepSeek spend from the server's
cloud-budget.json, and the account balance from the provider. Nothing leaves the machine: the webhooks point at
mock-webhook.py and the balance endpoint is a local http.server. The real API is never called.

    python3 deploy/monitor/test_cloud.py [-v]

Python 3 standard library only (the monitor's PyYAML import is lazy and not reached here).
"""
import json
import os
import re
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import test_announce as announce

mon = announce.mon
KEY = "sk-" + "0123456789abcdef0123456789abcdef"
DAY = "2026-10-31"
NEXT_DAY = "2026-11-01"


def write_budget(path, day=DAY, spent=0.0, cap=2.0, calls=7):
    with open(path, "w") as f:
        json.dump({"day": day, "spent": spent, "calls": calls, "cacheHitTokens": 1200,
                   "cacheMissTokens": 800, "capUsd": cap}, f)


def write_env_file(path, key=KEY):
    with open(path, "w") as f:
        f.write("# fake EnvironmentFile for the monitor tests\nSOLOMAPLING_LLM_CLOUD_API_KEY=%s\n" % key)


class BalanceServer:
    """A stand-in for GET {base}/user/balance. state: usd (float), code (HTTP status), paths, auth."""

    def __init__(self, state):
        self.state = state

        class Handler(BaseHTTPRequestHandler):
            def do_GET(inner, ):
                state.setdefault("paths", []).append(inner.path)
                state.setdefault("auth", []).append(inner.headers.get("Authorization") or "")
                if state.get("code", 200) != 200:
                    inner.send_response(state["code"])
                    inner.send_header("Content-Length", "0")
                    inner.end_headers()
                    return
                infos = state.get("infos")
                if infos is None:
                    infos = [{"currency": "USD", "total_balance": "%.2f" % state.get("usd", 0.0),
                              "granted_balance": "0.00", "topped_up_balance": "%.2f" % state.get("usd", 0.0)}]
                body = json.dumps({"is_available": True, "balance_infos": infos}).encode()
                inner.send_response(200)
                inner.send_header("Content-Type", "application/json")
                inner.send_header("Content-Length", str(len(body)))
                inner.end_headers()
                inner.wfile.write(body)

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def url(self):
        return "http://127.0.0.1:%d" % self.port

    def calls(self):
        return len(self.state.get("paths") or [])

    def close(self):
        self.server.shutdown()
        self.server.server_close()


class CloudBase(unittest.TestCase):
    """One throwaway conf/state/log tree, a mock webhook and a fake balance endpoint."""

    def setUp(self):
        self.e = announce.Env()
        self.root = self.e.root
        self.budget = os.path.join(self.root, "cloud-budget.json")
        self.cloud_env = os.path.join(self.root, "cloud-llm.env")
        self.state = {"usd": 50.0}
        self.api = BalanceServer(self.state)
        self.values = {"LOGIN_PORT": "0", "LLM_URL": "", "DECIDE_URL": "", "CLOUD_BUDGET_FILE": self.budget,
                       "CLOUD_ENV_FILE": self.cloud_env, "CLOUD_BASE_URL": self.api.url()}
        self.conf()
        self.e.secrets(ALERT_WEBHOOK_URL=self.e.url("alerts"))

    def conf(self, **extra):
        """Rewrite the target's conf, keeping the earlier values (Env.conf() replaces the whole file)."""
        self.values.update(extra)
        self.e.conf(**self.values)

    def tearDown(self):
        self.api.close()
        self.e.close()

    # --- helpers
    def check(self, *extra):
        return self.e.run("check", "live", *extra)

    def contents(self):
        return [p["content"] for p in self.e.posts()]

    def everything(self):
        """Every text the run could have shown anyone: posts, the local log, the state files."""
        parts = list(self.contents()) + [self.e.log()]
        for name in sorted(os.listdir(os.path.join(self.e.state_dir, "live"))):
            with open(os.path.join(self.e.state_dir, "live", name)) as f:
                parts.append(f.read())
        return "\n".join(parts)

    def cloud_state(self):
        path = os.path.join(self.e.state_dir, "live", "cloud.json")
        if not os.path.exists(path):
            return None
        with open(path) as f:
            return json.load(f)

    def edit_balance_state(self, **values):
        path = os.path.join(self.e.state_dir, "live", "cloud.json")
        with open(path) as f:
            data = json.load(f)
        data.setdefault("balance", {}).update(values)
        with open(path, "w") as f:
            json.dump(data, f)

    def budget_result(self, budget, warn_pct=80.0, state=None):
        return mon.budget_tick(budget, warn_pct, {} if state is None else state)

    @staticmethod
    def budget(spent, cap=2.0, day=DAY, calls=7):
        return {"day": day, "spent": spent, "cap": cap, "calls": calls}


class BudgetTickTest(unittest.TestCase):
    """budget_tick: one message at the warning, one more at the cap, a recovery at the day rollover."""

    def events(self, results):
        return [r.event for r in results if r.event]

    @staticmethod
    def budget(spent, cap=2.0, day=DAY, calls=7):
        return {"day": day, "spent": spent, "cap": cap, "calls": calls}

    def budget_result(self, budget, warn_pct=80.0, state=None):
        return mon.budget_tick(budget, warn_pct, {} if state is None else state)

    def test_below_the_warning_is_quiet(self):
        state = {}
        results = self.budget_result(CloudBase.budget(0.5), state=state)
        self.assertEqual([], self.events(results))
        self.assertTrue(results[0].ok)
        self.assertIn("$0.50 of $2.00", results[0].detail)
        self.assertEqual(DAY, state["day"])

    def test_at_the_warning_alerts_once(self):
        state = {}
        self.assertEqual(["alert"], self.events(self.budget_result(CloudBase.budget(1.6), state=state)))
        again = self.budget_result(CloudBase.budget(1.7), state=state)
        self.assertEqual([], self.events(again), "the warning is sent once a day")
        self.assertFalse(again[0].ok, "still over the warning line")

    def test_cap_alerts_once_more_after_the_warning(self):
        state = {}
        warn = self.budget_result(CloudBase.budget(1.6), state=state)[0]
        self.assertIn("80%", warn.detail)
        cap = self.budget_result(CloudBase.budget(2.0), state=state)[0]
        self.assertEqual("alert", cap.event)
        self.assertIn("cap reached", cap.detail)
        self.assertIn("until 00:00 UTC", cap.detail)
        self.assertNotIn("alert", self.events(self.budget_result(CloudBase.budget(2.4), state=state)))

    def test_straight_to_the_cap_is_one_alert(self):
        state = {}
        first = self.budget_result(CloudBase.budget(2.5), state=state)
        self.assertEqual(["alert"], self.events(first))
        self.assertIn("cap reached", first[0].detail)
        self.assertEqual([], self.events(self.budget_result(CloudBase.budget(2.6), state=state)))

    def test_rollover_recovers_and_resets(self):
        state = {}
        self.budget_result(CloudBase.budget(1.6), state=state)
        rolled = self.budget_result(CloudBase.budget(0.0, day=NEXT_DAY), state=state)
        self.assertEqual(["recover"], self.events(rolled))
        self.assertIn("new day", rolled[0].detail)
        self.assertEqual(NEXT_DAY, state["day"])
        self.assertEqual(0.0, state["spent"])
        self.assertFalse(state.get("warn"))
        self.assertEqual([], self.events(self.budget_result(CloudBase.budget(0.0, day=NEXT_DAY), state=state)),
                         "nothing left to recover from")

    def test_rollover_without_an_alert_is_quiet(self):
        state = {}
        self.budget_result(CloudBase.budget(0.4), state=state)
        self.assertEqual([], self.events(self.budget_result(CloudBase.budget(0.0, day=NEXT_DAY), state=state)))

    def test_no_cap_in_the_file_never_alerts(self):
        results = self.budget_result(self.budget(9.0, cap=0.0))
        self.assertEqual([], self.events(results))
        self.assertTrue(results[0].ok)

    def test_warning_percentage_is_configurable(self):
        self.assertEqual([], self.events(self.budget_result(CloudBase.budget(1.0), warn_pct=60)))
        self.assertEqual(["alert"], self.events(self.budget_result(CloudBase.budget(1.2), warn_pct=60)))


class BudgetFileTest(CloudBase):
    def test_reads_the_servers_file_shape(self):
        write_budget(self.budget, spent=0.75)
        budget = mon.read_cloud_budget(self.budget)
        self.assertEqual({"day": DAY, "spent": 0.75, "cap": 2.0, "calls": 7}, budget)

    def test_default_path_is_the_server_data_folder(self):
        self.conf(CLOUD_BUDGET_FILE="", SERVER_DIR="/opt/mapleroot/MapleRoot Full Repack/Server")
        mon.CONF_DIR, mon.STATE_ROOT, mon.LOG_DIR = self.e.conf_dir, self.e.state_dir, self.e.log_dir
        t = mon.Target("live")
        self.assertEqual("/opt/mapleroot/MapleRoot Full Repack/Server/data/convo/cloud-budget.json",
                         mon.cloud_budget_path(t))
        self.assertIsNone(mon.read_cloud_budget(""))

    def test_half_written_file_reads_as_missing(self):
        with open(self.budget, "w") as f:
            f.write('{"day": "2026-10-31", "spent": 1.')
        self.assertIsNone(mon.read_cloud_budget(self.budget))

    def test_missing_file_is_a_skip(self):
        code, out = self.check("--dry-run")
        self.assertEqual(0, code, out)
        self.assertIn("no budget file", out)
        self.assertEqual([], self.e.posts())

    def test_alert_once_per_day_then_recovered_at_rollover(self):
        write_budget(self.budget, spent=1.7)
        code, out = self.check()
        self.assertEqual(0, code, out)
        self.assertEqual(1, len(self.e.posts()))
        self.assertIn("**ALERT** cloud budget", self.contents()[0])
        self.assertIn("80%", self.contents()[0])
        self.check()
        self.check()
        self.assertEqual(1, len(self.e.posts()), "one warning a day, not a reminder every run")
        write_budget(self.budget, day=NEXT_DAY, spent=0.0)
        self.check()
        self.assertEqual(2, len(self.e.posts()))
        self.assertIn("**RECOVERED** cloud budget", self.contents()[1])

    def test_daily_cap_alert_reaches_the_alert_webhook(self):
        write_budget(self.budget, spent=2.05)
        self.check()
        self.assertEqual(1, len(self.e.posts()))
        self.assertIn("/api/webhooks/alerts/<token>", [p["path"] for p in self.e.posts()])
        self.assertIn("cap reached", self.contents()[0])

    def test_dry_run_posts_nothing_and_saves_nothing(self):
        write_budget(self.budget, spent=1.7)
        code, out = self.check("--dry-run")
        self.assertEqual(0, code, out)
        self.assertIn("cloud budget", out)
        self.assertEqual([], self.e.posts())
        self.assertIsNone(self.cloud_state(), "a dry run must not spend the day's alert")

    def test_maintenance_keeps_it_in_the_local_log(self):
        with open(self.e.maint, "w"):
            pass
        write_budget(self.budget, spent=1.7)
        self.check()
        self.assertEqual([], self.e.posts())
        self.assertIn("[alert, maintenance mode]", self.e.log())
        os.remove(self.e.maint)
        self.check()
        self.assertEqual(1, len(self.e.posts()), "an alert nobody saw is sent once maintenance ends")
        self.assertIn("80%", self.contents()[0])

    def test_dead_webhook_does_not_use_up_the_alert(self):
        self.conf(HTTP_TIMEOUT="1")
        self.e.secrets(ALERT_WEBHOOK_URL="http://127.0.0.1:%d/api/webhooks/alerts/tok3n" % announce.free_port())
        write_budget(self.budget, spent=2.05)
        code, out = self.check()
        self.assertEqual(0, code, out)
        self.assertEqual([], self.e.posts())
        self.assertIn("alert NOT sent", self.e.log() + out)
        self.e.secrets(ALERT_WEBHOOK_URL=self.e.url("alerts"))
        self.check()
        self.assertEqual(1, len(self.e.posts()), "the cap alert goes out once the webhook is back")
        self.assertIn("cap reached", self.contents()[0])


class BalanceTest(CloudBase):
    def setUp(self):
        super().setUp()
        write_env_file(self.cloud_env)

    def test_plenty_of_balance_is_quiet(self):
        self.state["usd"] = 42.5
        self.check()
        self.assertEqual([], self.e.posts())
        self.assertEqual(42.5, self.cloud_state()["balance"]["usd"])
        self.assertEqual(["/user/balance"], self.state["paths"])
        self.assertEqual(["Bearer " + KEY], self.state["auth"], "the key goes in the Authorization header only")
        self.assertNotIn(KEY, self.everything())

    def test_low_balance_alerts_then_recovers(self):
        self.state["usd"] = 3.4
        self.check()
        self.assertEqual(1, len(self.e.posts()))
        self.assertIn("**ALERT** cloud balance", self.contents()[0])
        self.assertIn("$3.40", self.contents()[0])
        self.check()
        self.assertEqual(1, len(self.e.posts()), "the interval holds the next call back")
        self.edit_balance_state(last_check=0)
        self.state["usd"] = 25.0
        self.check()
        self.assertEqual(2, len(self.e.posts()))
        self.assertIn("**RECOVERED** cloud balance", self.contents()[1])
        self.assertIn("$25.00", self.contents()[1])

    def test_interval_holds_the_calls_back(self):
        self.state["usd"] = 30.0
        self.check()
        self.state["usd"] = 1.0
        code, out = self.check("--dry-run")
        self.assertEqual(1, self.api.calls(), "--dry-run too: one call per BALANCE_EVERY_MIN")
        self.assertIn("last check", out)
        self.assertEqual([], self.e.posts())

    def test_three_failures_make_one_alert(self):
        self.state["code"] = 503
        self.check()
        self.assertEqual([], self.e.posts(), "one failure is not worth a message")
        self.check()
        self.assertEqual([], self.e.posts())
        self.check()
        self.assertEqual(1, len(self.e.posts()))
        self.assertIn("balance check failing", self.contents()[0])
        self.assertNotIn(KEY, self.everything())
        self.edit_balance_state(last_check=0)
        self.check()
        self.assertEqual(1, len(self.e.posts()), "the failure alert is sent once, not every check")
        self.state["code"] = 200
        self.state["usd"] = 40.0
        self.edit_balance_state(last_check=0)
        self.check()
        self.assertEqual(2, len(self.e.posts()))
        self.assertIn("**RECOVERED** cloud balance", self.contents()[1])
        self.assertIn("answering again", self.contents()[1])

    def test_key_never_leaks_on_an_http_error(self):
        self.state["code"] = 401
        self.check()
        for _ in range(2):
            self.edit_balance_state(last_check=0)
            self.check()
        self.assertEqual(1, len(self.e.posts()))
        self.assertIn("HTTP 401", self.contents()[0])
        self.assertNotIn(KEY, self.everything())
        self.assertNotIn("sk-", self.everything())

    def test_unreachable_endpoint_is_counted_not_crashed(self):
        self.conf(CLOUD_BASE_URL="http://127.0.0.1:%d" % announce.free_port())
        self.check()
        for _ in range(2):
            self.edit_balance_state(last_check=0)
            code, out = self.check()
            self.assertEqual(0, code, out)
        self.assertEqual(1, len(self.e.posts()))
        self.assertIn("unreachable", self.contents()[0])
        self.assertIn("balance check failing", self.contents()[0])

    def test_other_currency_only_is_a_skip(self):
        self.state["infos"] = [{"currency": "CNY", "total_balance": "700.00"}]
        code, out = self.check("--dry-run")
        self.assertEqual(0, code, out)
        self.assertEqual([], self.e.posts())
        self.assertIn("no USD balance", out)

    def test_trailing_v1_and_slash_in_the_base_url(self):
        self.conf(CLOUD_BASE_URL=self.api.url() + "/v1/")
        self.check()
        self.assertEqual(["/user/balance"], self.state["paths"])

    def test_base_url_defaults_to_the_server_config(self):
        self.conf(CLOUD_BASE_URL="")
        with open(os.path.join(self.root, "config.yaml"), "w") as f:
            f.write("server:\n  BOT_LLM_CLOUD_BASE_URL: \"http://127.0.0.1:%d\"\n" % self.api.port)
        self.conf(SERVER_DIR=self.root)
        mon.CONF_DIR, mon.STATE_ROOT, mon.LOG_DIR = self.e.conf_dir, self.e.state_dir, self.e.log_dir
        t = mon.Target("live")
        self.assertEqual(self.api.url(), mon.cloud_base_url(t))
        self.conf(CLOUD_BASE_URL="", SERVER_DIR=os.path.join(self.root, "nothing-here"))
        t = mon.Target("live")
        self.assertEqual("https://api.deepseek.com", mon.cloud_base_url(t))

    def test_missing_key_file_is_a_skip(self):
        os.remove(self.cloud_env)
        code, out = self.check("--dry-run")
        self.assertEqual(0, code, out)
        self.assertEqual(0, self.api.calls())
        self.assertEqual([], self.e.posts())
        self.assertIn("SOLOMAPLING_LLM_CLOUD_API_KEY", out)

    def test_balance_min_is_configurable(self):
        self.conf(BALANCE_MIN_USD="60")
        self.state["usd"] = 50.0
        self.check()
        self.assertEqual(1, len(self.e.posts()))
        self.assertIn("$50.00", self.contents()[0])


class ScrubTest(unittest.TestCase):
    def test_provider_keys_are_scrubbed_from_any_text(self):
        text = "key sk-0123456789abcdef0123456789abcdef rejected"
        self.assertEqual("key [redacted] rejected", mon.scrub(text))
        self.assertNotIn("sk-", mon.scrub("SOLOMAPLING_LLM_CLOUD_API_KEY=sk-abc123456789"))


class NoNetworkTest(unittest.TestCase):
    def test_no_real_api_call_without_a_key(self):
        """No env file here, so the balance check must never reach the network."""
        e = announce.Env()
        try:
            e.conf(LOGIN_PORT="0", LLM_URL="", DECIDE_URL="", CLOUD_ENV_FILE=os.path.join(e.root, "none.env"),
                   CLOUD_BUDGET_FILE=os.path.join(e.root, "none.json"))
            code, out = e.run("check", "live", "--dry-run")
            self.assertEqual(0, code, out)
            self.assertIn("no SOLOMAPLING_LLM_CLOUD_API_KEY", out)
        finally:
            e.close()


if __name__ == "__main__":
    unittest.main()
