"""The 187 orchestrator for auto-QA client scripts (ticket 166/170, Server Dev half of the split runner).

It reads and validates a script, arms the QA event feed on TEST, sends the steps one at a time to the Silvia worker
(client-mcp run_script, Client lane), waits on client signals the worker relays and on server lines from the feed,
triggers bursts, then writes the run folder's manifest.json and the feed lines it saw (run-folder.md).

The feed and its secret stay on 164: every feed call is `curl` run ON 164 over ssh, with the header built there from
the secret file by path (printf is a shell builtin, so the secret is never on a command line or on 187).

Two seams for tests and for the Client lane:
  Feed   - poll(since, chars, topics, match, wait) -> the feed's JSON answer (SshFeed on TEST, FakeFeed in tests)
  Worker - login/send/wait_signal/burst/... on Silvia (SilviaWorker: the run_script protocol, to be wired by the
           Client lane; FakeWorker in tests)

Usage: tools/qa-runner/run qa-scripts/<id>.yaml [--dry-run]   (--dry-run prints the plan and touches nothing)
"""
import datetime
import hashlib
import json
import re
import shlex
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "qa-scripts"))
import qascript  # noqa: E402
import yaml  # noqa: E402

REPO = qascript.REPO
RUNS_ROOT = Path("/root/work/qa-runs")
TEST_HOST = "root@192.168.0.164"
FEED_URL = "http://127.0.0.1:8198/qa/events"
FEED_SECRET_FILE = "/etc/slums-inspect-test/secret"
DEPLOYED_COMMIT = "/opt/mapleroot-dev/persistence-test/Server/DEPLOYED_COMMIT"
PLACEHOLDER = qascript.PLACEHOLDER


class RunFailed(Exception):
    """A step or wait failed: the run is FAIL (a wait timed out) unless `error` says the runner broke."""

    def __init__(self, message, result="timeout"):
        super().__init__(message)
        self.result = result


def utc_now():
    return datetime.datetime.now(datetime.timezone.utc)


def iso(t):
    return t.strftime("%Y-%m-%dT%H:%M:%S.") + "%03dZ" % (t.microsecond // 1000)


# ── the feed ──────────────────────────────────────────────────────────────────────────────────────

class SshFeed:
    """GET /qa/events on TEST, run on 164 over ssh; the secret header is built there from the file."""

    def __init__(self, host=TEST_HOST, runner=subprocess.run):
        self.host = host
        self.runner = runner

    def remote_command(self, query):
        url = FEED_URL + "?" + query
        return ("printf 'X-Slums-Inspect: %s\\n' \"$(cat " + FEED_SECRET_FILE + ")\" | "
                "curl -sS --max-time 30 -H @- " + shlex.quote(url))

    def poll(self, since, chars, topics, match=None, wait=20):
        from urllib.parse import urlencode
        q = {"since": since, "topics": ",".join(topics), "wait": wait, "limit": 1000}
        if chars:
            q["chars"] = ",".join(str(c) for c in chars)
        if match:
            q["match"] = match
        r = self.runner(["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", self.host,
                         self.remote_command(urlencode(q))], capture_output=True, text=True, timeout=60)
        if r.returncode != 0:
            raise RunFailed("feed poll failed: " + (r.stderr or "").strip()[:200], "error")
        answer = json.loads(r.stdout)
        if not answer.get("ok"):
            raise RunFailed("feed refused: %s" % answer.get("error"), "error")
        return answer


def read_deployed_commit(host=TEST_HOST, runner=subprocess.run):
    r = runner(["ssh", "-o", "BatchMode=yes", host, "cat " + DEPLOYED_COMMIT], capture_output=True, text=True,
               timeout=30)
    commit = (r.stdout or "").strip()
    return commit if re.fullmatch(r"[0-9a-f]{7,40}", commit) else "unknown"


# ── the worker ────────────────────────────────────────────────────────────────────────────────────

class SilviaWorker:
    """The client-mcp worker's run_script on Silvia (Client lane). The calls below are the contract this orchestrator
    needs; the transport (ssh to the worker's queue, as tools/ops/client-mcp does) is wired when run_script lands."""

    def login(self, alias, client):
        """Starts the QA client for `alias`, types the vault account, returns {} (char fields come after field.ready)."""
        raise NotImplementedError("run_script is the Client lane's: wire SilviaWorker when it lands")

    def send(self, alias, step):
        """Plays one action step (select_*, chat, gm, key, move, jump, click, ...); returns {"result", "detail"}."""
        raise NotImplementedError

    def wait_signal(self, alias, name, timeout_s, extra):
        """Blocks until the pipe signal comes (or timeout); returns {"name", "detail", "utc"} or None."""
        raise NotImplementedError

    def character(self, alias):
        """After field.ready: {"char_id", "char_name", "build", "release", "caps", "dll_sha256", "channel"}."""
        raise NotImplementedError

    def burst(self, alias, fps, ms, label):
        raise NotImplementedError

    def capture(self, alias, kind, cap_id, spec, start):
        """Starts (start=True) or stops a chat_log / packet_trace / samples capture."""
        raise NotImplementedError

    def pull(self, dest):
        """Copies the run's client files (qa-captures, logs) from Silvia into dest/client and dest/frames."""
        raise NotImplementedError

    def close(self):
        pass


# ── the run ───────────────────────────────────────────────────────────────────────────────────────

def fill(text, values, regex=False):
    """${alias.field} and ${var}; escaped inside a regex."""
    def rep(m):
        key = m.group(1) + ("." + m.group(2) if m.group(2) else "")
        if key not in values:
            raise RunFailed("no value for ${%s}" % key, "error")
        v = str(values[key])
        return re.escape(v) if regex else v
    return PLACEHOLDER.sub(rep, text)


def topics_of(doc):
    out = set()
    for s in doc["steps"]:
        sig = s.get("signal") or {}
        if sig.get("type") == "server_line":
            out.add(sig["topic"])
    for c in doc.get("expect", {}).get("checks", []) or []:
        if c.get("check") == "server_line":
            out.add(c["topic"])
    return sorted(out)


class Run:
    def __init__(self, script_path, feed, worker, runs_root=RUNS_ROOT, commit="unknown", clock=utc_now,
                 sleep=time.sleep, trigger="manual"):
        self.path = Path(script_path)
        self.text = self.path.read_text(encoding="utf-8")
        errors, _ = qascript.validate_text(self.text, self.path.stem)
        if errors:
            raise RunFailed("the script does not validate: " + "; ".join(errors[:5]), "error")
        self.doc = yaml.safe_load(self.text)
        self.feed, self.worker, self.clock, self.sleep = feed, worker, clock, sleep
        self.started = clock()
        stamp = self.started.strftime("%Y%m%dT%H%M%SZ")
        self.run_id = "%s-%s" % (self.doc["id"], stamp)
        self.folder = Path(runs_root) / self.started.strftime("%Y-%m-%d") / self.run_id
        self.values = {}
        self.char_ids = {}
        self.lines = []          # every feed line seen, in order
        self.last_seq = 0
        self.step_seq = {}       # step id -> feed seq when it was sent
        self.step_time = {}      # step id -> utc sent
        self.topics = topics_of(self.doc)
        self.manifest = {
            "format": 1, "run_id": self.run_id,
            "script": {"id": self.doc["id"], "sha256": hashlib.sha256(self.text.encode("utf-8")).hexdigest(),
                       "status": self.doc.get("status", "draft"), "covers": self.doc["covers"]},
            "server": {"host": "TEST", "commit": commit if re.fullmatch(r"[0-9a-f]{7,40}", commit) else "0000000",
                       "commit_source": "Server/DEPLOYED_COMMIT"},
            "clients": {}, "runner": {"orchestrator": "tools/qa-runner 1", "host": "187"},
            "trigger": trigger, "started_utc": iso(self.started), "finished_utc": None, "result": "RUNNING",
            "failed_at": None, "steps": [], "captures": [], "checks": [], "findings": []}
        self.pending_bursts = {}   # signal wait id -> [capture]
        self.step_bursts = {}      # step id -> [capture]
        self.span_caps = {"from": {}, "to": {}}
        for cap in self.doc.get("captures", []) or []:
            kind = next(k for k in cap if k not in ("id", "client", "note"))
            body = cap[kind]
            at = body.get("at") if kind in ("burst", "clip") else None
            if at and "signal" in at:
                self.pending_bursts.setdefault(at["signal"], []).append((cap, kind))
            elif at:
                self.step_bursts.setdefault(at["step"], []).append((cap, kind))
            span = body.get("span") or {}
            if kind not in ("burst", "clip"):
                self.span_caps["from"].setdefault(span.get("from", "__start__"), []).append((cap, kind))
                self.span_caps["to"].setdefault(span.get("to", "__end__"), []).append((cap, kind))

    # -- helpers --

    def alias(self, step):
        return step.get("client") or next(iter(self.doc["clients"]))

    def chars(self):
        return sorted(self.char_ids.values())

    def poll(self, wait):
        answer = self.feed.poll(self.last_seq, self.chars(), self.topics or ["QA_RUN"], None, wait)
        for line in answer.get("lines", []):
            if line["seq"] > self.last_seq:
                self.lines.append(line)
        self.last_seq = max(self.last_seq, int(answer.get("next", self.last_seq)))
        return answer

    def line_matches(self, line, topic, regex, char, after):
        if line["seq"] <= after or line["topic"] != topic:
            return False
        if char != "any":
            cid = self.char_ids.get(char)
            if cid is not None and line["chars"] and cid not in line["chars"]:
                return False
        return re.search(regex, line["line"]) is not None

    def start_burst(self, cap, kind):
        body = cap[kind]
        if body.get("delay_ms"):
            self.sleep(body["delay_ms"] / 1000)
        alias = cap.get("client") or next(iter(self.doc["clients"]))
        self.worker.burst(alias, body["fps"], body["ms"], "%s-%s" % (self.run_id, cap["id"]))
        self.manifest["captures"].append({"id": cap["id"], "kind": kind, "client": alias,
                                          "path": "frames/%s/" % cap["id"], "result": "ok",
                                          "fps": body["fps"], "first_utc": iso(self.clock())})

    def spans(self, key, point):
        for cap, kind in self.span_caps[key].get(point, []):
            alias = cap.get("client") or next(iter(self.doc["clients"]))
            self.worker.capture(alias, kind, cap["id"], cap[kind], key == "from")
            if key == "from":
                self.manifest["captures"].append({"id": cap["id"], "kind": kind, "client": alias,
                                                  "path": "client/%s/" % alias, "result": "ok"})

    # -- steps --

    def run(self):
        self.folder.mkdir(parents=True, exist_ok=True)
        (self.folder / "script.yaml").write_text(self.text, encoding="utf-8")
        try:
            if self.topics:
                self.poll(0)   # arms the topics before step 1
            self.spans("from", "__start__")
            for i, step in enumerate(self.doc["steps"]):
                self.play(i, step)
            self.spans("to", "__end__")
            self.manifest["result"] = "PASS"
        except RunFailed as e:
            self.manifest["result"] = "FAIL" if e.result in ("timeout", "refused") else "ERROR"
            done = {s["index"] for s in self.manifest["steps"]}
            for i, step in enumerate(self.doc["steps"]):
                if i not in done:
                    self.manifest["steps"].append({"index": i, "id": step.get("id"), "do": step["do"],
                                                   "client": self.alias(step), "result": "not run"})
        finally:
            self.manifest["finished_utc"] = iso(self.clock())
            self.evaluate_checks()
            self.write()
            self.worker.close()
        return self.manifest

    def play(self, i, step):
        alias = self.alias(step)
        entry = {"index": i, "id": step.get("id"), "do": step["do"], "client": alias, "sent_utc": iso(self.clock())}
        self.manifest["steps"].append(entry)
        t0 = time.monotonic()
        sid = step.get("id")
        if sid and self.topics and self.needs_baseline(sid):
            self.poll(0)
        if sid:
            self.step_seq[sid] = self.last_seq
            self.step_time[sid] = entry["sent_utc"]
        try:
            for cap, kind in self.step_bursts.get(sid, []):
                self.start_burst(cap, kind)
            if sid:
                self.spans("from", sid)
            self.do(i, step, alias, entry)
            if sid:
                self.spans("to", sid)
            entry["result"] = "ok"
        except RunFailed as e:
            entry["result"] = e.result
            entry["detail"] = str(e)
            self.manifest["failed_at"] = i
            raise
        finally:
            entry["done_utc"] = iso(self.clock())
            entry["ms"] = int((time.monotonic() - t0) * 1000)

    def needs_baseline(self, sid):
        for s in self.doc["steps"]:
            if s.get("do") == "wait" and (s.get("signal") or {}).get("type") == "server_line" \
                    and s.get("armed_at", None) == sid:
                return True
        steps = self.doc["steps"]
        idx = next(k for k, s in enumerate(steps) if s.get("id") == sid)
        nxt = steps[idx + 1] if idx + 1 < len(steps) else None
        return bool(nxt and nxt.get("do") == "wait" and (nxt.get("signal") or {}).get("type") == "server_line"
                    and "armed_at" not in nxt)

    def do(self, i, step, alias, entry):
        kind = step["do"]
        if kind == "mark":
            return
        if kind == "sleep":
            self.sleep(step["ms"] / 1000)
            return
        if kind == "login":
            self.worker.login(alias, self.doc["clients"][alias])
            return
        if kind == "wait":
            self.wait(i, step, alias, entry)
            return
        sent = dict(step)
        for key in ("text", "line"):
            if key in sent:
                sent[key] = fill(sent[key], self.values)
        if kind == "select_character":
            sent["slot"] = (self.doc["clients"][alias].get("character") or {}).get("slot", 0)
        r = self.worker.send(alias, sent) or {}
        if r.get("result", "ok") != "ok":
            raise RunFailed("%s refused: %s" % (kind, r.get("detail", "")), "refused")

    def wait(self, i, step, alias, entry):
        sig = step["signal"]
        timeout_s = step["timeout_ms"] / 1000
        deadline = time.monotonic() + timeout_s
        if sig["type"] == "client_signal":
            retry = step.get("retry")
            every = retry["every_ms"] / 1000 if retry else timeout_s
            tries = 0
            extra = {k: (fill(v, self.values, regex=True) if k == "regex" else v)
                     for k, v in sig.items() if k not in ("type", "name")}
            while True:
                left = deadline - time.monotonic()
                if left <= 0:
                    raise RunFailed("timed out waiting for %s after %d s" % (sig["name"], timeout_s))
                got = self.worker.wait_signal(alias, sig["name"], min(every, left), extra)
                if got:
                    entry["signal"] = {"type": "client_signal", "name": sig["name"],
                                       "matched_utc": got.get("utc") or iso(self.clock()),
                                       "detail": str(got.get("detail", ""))}
                    if retry:
                        entry["retries"] = tries
                    self.bind(step, got.get("text", ""), entry)
                    if sig["name"] == "field.ready" and alias not in self.char_ids:
                        self.learn_character(alias)
                    break
                self.sleep(0.05)   # a worker that answers "not yet" at once must not spin
                if retry and tries < retry["times"] and time.monotonic() < deadline:
                    tries += 1
                    target = next(s for s in self.doc["steps"] if s.get("id") == retry["step"])
                    self.worker.send(alias, dict(target))
        else:
            regex = fill(sig["regex"], self.values, regex=True)
            armed = step.get("armed_at")
            after = self.step_seq.get(armed, 0) if armed else self.step_seq.get(step["id"], self.last_seq)
            if armed:
                entry["armed_at"] = armed
            while True:
                hit = next((ln for ln in self.lines
                            if self.line_matches(ln, sig["topic"], regex, sig["char"], after)), None)
                if hit:
                    entry["signal"] = {"type": "server_line", "topic": sig["topic"], "feed_seq": hit["seq"],
                                       "matched_utc": hit["utc"], "line": hit["line"]}
                    self.bind(step, hit["line"], entry, regex)
                    break
                left = deadline - time.monotonic()
                if left <= 0:
                    raise RunFailed("timed out waiting for /%s/ (%s) after %d s" % (sig["regex"], sig["topic"],
                                                                                   timeout_s))
                self.poll(max(0, min(20, int(left))))
        for cap, kind in self.pending_bursts.get(step["id"], []):
            self.start_burst(cap, kind)

    def bind(self, step, text, entry, regex=None):
        if not step.get("bind"):
            return
        rx = regex or fill(step["signal"].get("regex", ""), self.values, regex=True)
        m = re.search(rx, text or "")
        if not m:
            raise RunFailed("bind: the matched text has no group", "error")
        bound = {var: m.group(group) for var, group in step["bind"].items()}
        self.values.update(bound)
        entry.setdefault("signal", {})["bound"] = bound

    def learn_character(self, alias):
        info = self.worker.character(alias) or {}
        if "char_id" in info:
            self.char_ids[alias] = int(info["char_id"])
            self.values[alias + ".id"] = info["char_id"]
        if "char_name" in info:
            self.values[alias + ".name"] = info["char_name"]
        self.manifest["clients"][alias] = {k: info[k] for k in
                                           ("build", "release", "caps", "dll_sha256", "char_id", "char_name",
                                            "channel") if k in info}

    # -- checks and the folder --

    def evaluate_checks(self):
        for k, chk in enumerate(self.doc.get("expect", {}).get("checks", []) or []):
            out = {"index": k, "check": chk["check"]}
            if chk["check"] == "server_line":
                try:
                    regex = fill(chk["regex"], self.values, regex=True)
                except RunFailed as e:
                    out.update(result="SKIP", detail=str(e))
                    self.manifest["checks"].append(out)
                    continue
                span = chk.get("span") or {}
                lo = self.step_seq.get(span.get("from"), 0)
                hits = [ln for ln in self.lines if self.line_matches(ln, chk["topic"], regex, chk["char"], lo)]
                n = len(hits)
                ok = n >= chk.get("min", 1 if "max" not in chk else 0) and ("max" not in chk or n <= chk["max"])
                out.update(result="PASS" if ok else "FAIL",
                           detail="%d line(s) (min %s, max %s)" % (n, chk.get("min", "-"), chk.get("max", "-")),
                           evidence="server/feed.jsonl" + ("#seq=%d" % hits[0]["seq"] if hits else ""))
            else:
                out.update(result="SKIP", detail="judged from the client files after the pull (reviewer or "
                                                 "tools/qa-runner check pass)")
            self.manifest["checks"].append(out)

    def write(self):
        server = self.folder / "server"
        server.mkdir(parents=True, exist_ok=True)
        with open(server / "feed.jsonl", "w", encoding="utf-8") as f:
            for line in self.lines:
                f.write(json.dumps(line) + "\n")
        (self.folder / "manifest.json").write_text(json.dumps(self.manifest, indent=1), encoding="utf-8")


def plan(script_path):
    """A dry run: what would be sent and waited on, nothing touched."""
    doc = yaml.safe_load(Path(script_path).read_text(encoding="utf-8"))
    out = ["script %s: %d steps, feed topics %s" % (doc["id"], len(doc["steps"]), ",".join(topics_of(doc)) or "-")]
    for i, s in enumerate(doc["steps"]):
        what = s.get("line") or s.get("text") or s.get("anchor") or (s.get("signal") or {}).get("name") \
            or (s.get("signal") or {}).get("regex") or s.get("key") or ""
        out.append("%3d %-8s %-14s %s" % (i, s.get("client", ""), s["do"], what))
    return "\n".join(out)


def main(argv):
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__)
        return 0
    script = argv[0]
    errors, warnings = qascript.validate_file(script)
    if errors:
        print("the script does not validate:\n  " + "\n  ".join(errors))
        return 2
    if "--dry-run" in argv:
        print(plan(script))
        for w in warnings:
            print("warn: " + w)
        return 0
    run = Run(script, SshFeed(), SilviaWorker(), commit=read_deployed_commit())
    manifest = run.run()
    print("%s %s -> %s" % (manifest["result"], run.run_id, run.folder))
    return 0 if manifest["result"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
