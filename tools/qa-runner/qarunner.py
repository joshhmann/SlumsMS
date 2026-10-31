"""The 187 orchestrator for auto-QA client scripts (ticket 166/170, Server Dev half of the split runner).

It reads and validates a script, arms the QA event feed on TEST, sends the steps one at a time to the Silvia worker
(client-mcp run_script, Client lane), waits on client signals the worker relays and on server lines from the feed,
triggers bursts, then writes the run folder's manifest.json and the feed lines it saw (run-folder.md).

The feed and its secret stay on 164: every feed call is `curl` run ON 164 over ssh, with the header built there from
the secret file by path (printf is a shell builtin, so the secret is never on a command line or on 187).

Two seams for tests and the Client lane:
  Feed   - poll(since, chars, topics, match, wait) -> the feed's JSON answer (SshFeed on TEST, FakeFeed in tests)
  Worker - login/send/wait_signal/burst/... on Silvia (SilviaWorker; FakeWorker in tests)
SilviaWorker makes each step one client-mcp tool call through the worker's file queue (WorkerTransport: one small
python program per call over ssh, the same protocol as tools/ops/client-mcp). Client signals come from the QA pipe's
event log by position (qa_events), never from a sleep: a signal this client build does not send fails its step at
once ("client signal <name> not available in this client build") instead of looking like a timeout.

Usage: tools/qa-runner/run qa-scripts/<id>.yaml [--dry-run]   (--dry-run prints the plan and touches nothing)
"""
import base64
import datetime
import hashlib
import io
import json
import os
import re
import shlex
import subprocess
import sys
import time
import uuid
import zipfile
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
# The client logs this once at login: the only place on the box that says which capabilities the client has.
BUILD_STAMP_CAPS = re.compile(r"Build stamp: caps (0x[0-9A-Fa-f]{8})")


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

# The QA box and its client-mcp folder, as tools/ops/client-mcp's CLIENT_MCP_TARGET picks them.
WORKER_TARGETS = {
    "vm": ("crims@192.168.0.175", "~/.ssh/id_slums_winvm", r"C:\SlumsQA\client-mcp"),
    "silvia": ("crims@192.168.0.186", "~/.ssh/id_rsprox_desktop", r"Z:\MSv83\SlumsMS-dev\client-mcp"),
}
PYTHON = os.environ.get("CLIENT_MCP_PYTHON", "python")
# Runs on the QA box (python 3 there), fed on stdin: appends one request to the worker's inbox and waits for its
# answer in the outbox, exactly as tools/ops/client-mcp does, and prints one JSON outcome line. Nothing is quoted into
# a command line (Windows ssh runs the remote command through cmd.exe), so the folder and the request travel as
# base64 inside the program text. It never prints the request back.
WORKER_PROGRAM = r"""import base64, json, sys, time
from pathlib import Path
root = Path(base64.b64decode('%(root)s').decode('utf-8')) / 'logs'
wait, queue_wait = int(sys.argv[1]), int(sys.argv[2])
req = json.loads(base64.b64decode('%(request)s').decode('utf-8'))
inbox, outbox = root / 'worker-inbox.jsonl', root / 'worker-outbox.jsonl'
def answer(payload):
    print(json.dumps(payload))
    sys.exit(0)
if not outbox.exists():
    answer({'outcome': 'no-worker'})
offset = outbox.stat().st_size
req['wait_until'] = time.time() + queue_wait
with inbox.open('a', encoding='utf-8') as stream:
    stream.write(json.dumps(req) + '\n')
deadline, queued = time.monotonic() + wait, False
while time.monotonic() < deadline:
    with outbox.open('rb') as stream:   # bytes: a text-mode offset drifts on CRLF lines
        stream.seek(offset)
        for line in stream:
            if not line.endswith(b'\n'):
                break
            offset += len(line)
            try:
                event = json.loads(line)
            except ValueError:
                answer({'outcome': 'bad-line'})
            if event.get('id') != req['id']:
                continue
            if event.get('event') == 'queued':
                queued = True
                deadline = time.monotonic() + queue_wait
                continue
            if event.get('event') == 'started':
                queued = False
                deadline = time.monotonic() + wait
                continue
            if event.get('exited'):
                answer({'outcome': 'exited'})
            if event.get('error'):
                answer({'outcome': 'error', 'message': str(event['error'])[:300]})
            result = event.get('result') or {}
            answer({'outcome': 'answered', 'isError': bool(result.get('isError')),
                    'content': [c.get('text', '') for c in result.get('content', []) if c.get('type') == 'text']})
    time.sleep(0.5)
with inbox.open('a', encoding='utf-8') as stream:   # drop it if the worker still has it queued
    stream.write(json.dumps({'id': 'cancel-' + req['id'], 'action': 'cancel', 'target': req['id']}) + '\n')
answer({'outcome': 'queued' if queued else 'no-answer'})
"""
# Also on the QA box, also on stdin: one folder as a zip, base64 on stdout (no scp, and no tar on Windows).
FETCH_PROGRAM = r"""import base64, io, sys, zipfile
from pathlib import Path
root = base64.b64decode(sys.argv[1]).decode('utf-8')
what = base64.b64decode(sys.argv[2]).decode('utf-8')
src = Path(root) / what
if not src.is_dir():
    sys.stderr.write('no folder %s on the box\n' % what)
    sys.exit(3)
buf = io.BytesIO()
with zipfile.ZipFile(buf, 'w', zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(src.rglob('*')):
        if path.is_file():
            archive.write(path, path.relative_to(src).as_posix())
sys.stdout.write(base64.b64encode(buf.getvalue()).decode('ascii'))
"""
FETCH_MAX_BYTES = 256 * 1024 * 1024


def base64_text(text):
    return base64.b64encode(text.encode("utf-8")).decode("ascii")


class WorkerTransport:
    """One client-MCP tool call on the QA box, through the worker's file queue (tools/ops/client-mcp's protocol).

    Every call is one small python program on the box's stdin (`python -B - <wait> <queue_wait>`, the same shape as
    the wrapper's heredoc), so the request never has to survive a Windows shell. `wrap=False` (tests) runs that same
    program here, against the same files, instead of over ssh.
    """

    def __init__(self, target="silvia", runner=subprocess.run, wait=120, queue_wait=3600, wrap=True,
                 root=None, python=PYTHON, clock=time.time):
        if target not in WORKER_TARGETS:
            raise RunFailed("unknown worker target %r: use %s" % (target, " or ".join(WORKER_TARGETS)), "error")
        self.target, self.runner, self.wait, self.queue_wait = target, runner, wait, queue_wait
        self.wrap, self.python, self.clock = wrap, python, clock
        host, key, default_root = WORKER_TARGETS[target]
        self.host, self.key, self.root = host, key, root or default_root

    def remote(self, extra=()):
        """The argv that runs `python -B - <extra>` on the box, or here when wrap is off."""
        inner = [self.python, "-B", "-"] + [str(a) for a in extra]
        if not self.wrap:
            return inner
        return ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", "-o", "IdentitiesOnly=yes",
                "-i", os.path.expanduser(self.key), self.host, " ".join(inner)]

    def run(self, program, extra, timeout):
        argv = self.remote(extra)
        try:
            return self.runner(argv, input=program.encode("utf-8"), capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired:
            raise RunFailed("the worker call on %s did not return within %d s" % (self.target, timeout), "error")
        except OSError as error:
            raise RunFailed("cannot run ssh to %s: %s" % (self.target, error), "error")

    def call(self, name, arguments=None):
        """{"isError", "text": [...]} for one tool call, or RunFailed when the worker never answered."""
        request = {"id": "run-" + uuid.uuid4().hex, "action": "call", "name": name,
                   "arguments": arguments or {}, "wait_until": self.clock() + self.queue_wait}
        program = WORKER_PROGRAM % {"root": base64_text(self.root),
                                    "request": base64_text(json.dumps(request))}
        r = self.run(program, [int(self.wait), int(self.queue_wait)], self.wait + self.queue_wait + 60)
        outcome = self.outcome(r)
        if outcome["outcome"] == "answered":
            return {"isError": outcome.get("isError"), "text": outcome.get("content") or []}
        if outcome["outcome"] == "error":
            raise RunFailed("%s refused the call: %s" % (name, outcome.get("message")), "error")
        if outcome["outcome"] == "no-worker":
            raise RunFailed("no client-MCP worker is running on %s: start it there first (docs/qa/CLIENT-MCP.md)"
                            % self.target, "error")
        if outcome["outcome"] == "queued":
            raise RunFailed("the client-MCP worker kept the call queued for %d s: the owner kept using the PC"
                            % self.queue_wait, "error")
        if outcome["outcome"] == "exited":
            raise RunFailed("the client-MCP worker exited while the call was queued", "error")
        if outcome["outcome"] == "bad-line":
            raise RunFailed("the worker's outbox on %s has a line that is not JSON" % self.target, "error")
        raise RunFailed("no answer from the client-MCP worker on %s within %d s" % (self.target, self.wait), "error")

    def outcome(self, finished):
        """The outcome line of a remote program; ssh's own failure wins when there is no line."""
        text = (finished.stdout or "")
        if isinstance(text, bytes):
            text = text.decode("utf-8", "replace")
        for line in reversed(text.strip().splitlines()):
            try:
                parsed = json.loads(line)
            except ValueError:
                continue
            if isinstance(parsed, dict) and "outcome" in parsed:
                return parsed
        code = getattr(finished, "returncode", None)
        if code:
            stderr = finished.stderr or b""
            if isinstance(stderr, bytes):
                stderr = stderr.decode("utf-8", "replace")
            raise RunFailed("ssh to %s failed (exit %s): %s" % (self.target, code, stderr.strip()[:200]), "error")
        raise RunFailed("the worker call on %s printed no outcome" % self.target, "error")

    def fetch(self, relpath, dest):
        """Copies <root>/<relpath> from the box into `dest` (one zip, base64 over one ssh call)."""
        r = self.run(FETCH_PROGRAM, [base64_text(self.root), base64_text(relpath)], 600)
        text = r.stdout if isinstance(r.stdout, str) else (r.stdout or b"").decode("ascii", "replace")
        if not text.strip():
            stderr = r.stderr if isinstance(r.stderr, str) else (r.stderr or b"").decode("utf-8", "replace")
            raise RunFailed("the copy of %s from %s failed (exit %s): %s"
                            % (relpath, self.target, getattr(r, "returncode", "-"), stderr.strip()[:200]), "error")
        try:
            data = base64.b64decode(text.strip(), validate=True)
        except (ValueError, TypeError):
            raise RunFailed("the copy of %s from %s came back unreadable" % (relpath, self.target), "error")
        if len(data) > FETCH_MAX_BYTES:
            raise RunFailed("%s on %s is larger than %d MB" % (relpath, self.target, FETCH_MAX_BYTES // 2**20),
                            "error")
        dest = Path(dest)
        dest.mkdir(parents=True, exist_ok=True)
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                for member in archive.namelist():
                    if member.startswith("/") or ".." in Path(member).parts:
                        raise RunFailed("the copy of %s has a path outside it: %s" % (relpath, member), "error")
                archive.extractall(dest)
        except zipfile.BadZipFile:
            raise RunFailed("the copy of %s from %s is not a zip" % (relpath, self.target), "error")
        return dest


class SilviaWorker:
    """The client-MCP worker on the QA box (Silvia), one tool call per orchestrator call (ticket 170).

    The orchestrator never types into the client itself: each step is a tool call the worker accepts only while the
    owner is idle, and each wait reads the QA pipe's event log by position (qa_events). A signal the QA build does not
    send is refused up front (missing_signal) and no step is ever reported as passed without its evidence.

    A run logs in with login_credentials (a vault), which the worker does not track, so its self-healing stays off
    for the length of the run: a client that dies mid-script fails the step instead of being restarted into the
    wrong account (worker recovery logs in with secrets/account.txt, not the script's vault).
    """

    SIGNALS = ("worldselect.ready", "channels.ready", "charselect.ready", "field.ready")
    # field.ready already carries no map id (the QA module sends the name only), so "the map I asked for loaded"
    # cannot be waited on until the client says which map it is: client work, ticket 166.
    NO_PAYLOAD = {"field.ready"}
    KEY_STEPS = {"key": ("key", "hold_ms"), "move": ("dir", "ms")}

    def __init__(self, transport=None, sleep=time.sleep, clock=time.time, target="silvia"):
        self.transport = transport or WorkerTransport(target)
        self.sleep, self.clock = sleep, clock
        self.cursor = {}     # alias -> position in the pipe's event log
        self.seen = {}       # alias -> every event this run read, in order (the run folder's pipe.log)
        self.info = {}       # alias -> build/release/caps/dll_sha256 learned so far
        self.labels = {}     # burst label -> capture id, when the caller registers it through pull
        self.closed = False

    # -- the transport --

    def tool(self, name, arguments=None):
        return self.transport.call(name, arguments)

    def json_tool(self, name, arguments=None):
        answer = self.tool(name, arguments)
        text = "\n".join(answer.get("text") or []).strip()
        if answer.get("isError"):
            raise RunFailed(text or (name + " was refused"), "refused")
        try:
            return json.loads(text)
        except ValueError:
            raise RunFailed("%s answered with something other than JSON: %s" % (name, text[:200]), "error")

    def events(self, alias):
        """The pipe's events since the last call for this alias; also the position for the next one."""
        since = self.cursor.get(alias, 0)
        answer = self.json_tool("qa_events", {"since": since})
        got = answer.get("events") or []
        self.seen.setdefault(alias, []).extend(got)
        self.cursor[alias] = int(answer.get("next", since + len(got)))
        return got

    # -- the orchestrator's calls --

    def missing_signal(self, name, extra):
        """Why this build cannot produce that wait, or None. Called before any waiting, so a missing signal fails the
        step at once instead of burning its timeout and calling the silence a failure of the game."""
        if name not in self.SIGNALS:
            return "client signal %s not available in this client build" % name
        if extra.get("map_id") is not None:
            return "client signal %s not available in this client build (it sends no %s)" % (
                name, "map id" if name == "field.ready" else "payload")
        return None

    def login(self, alias, client):
        """Starts a fresh QA client (a running one is stopped first: a client left over from before this run could be
        half played, and stopping it also disarms the worker's self-healing), types the vault account, and returns
        what the client says it is."""
        status = self.json_tool("status")
        if status.get("client_running"):
            self.json_tool("stop_client")
        self.json_tool("start_client")
        self.events(alias)   # the log up to now: the login's own signals start here
        vault = (client.get("account") or {}).get("vault")
        if not vault:
            raise RunFailed("client %s has no account.vault" % alias, "error")
        self.json_tool("login_credentials", {"vault": vault})
        return self.character(alias)

    def send(self, alias, step):
        """Plays one action step; {"result": "ok"|"refused", "detail"} (the caller turns a refusal into a failure)."""
        kind = step["do"]
        name, arguments = kind, None
        if kind in ("gm", "chat"):
            name, arguments = "command", {"line": step.get("line") if kind == "gm" else step.get("text")}
        elif kind == "click":
            name, arguments = "click_anchor", {"name": step["anchor"]}
        elif kind == "select_world":
            name, arguments = "select_world", {"world": step["world"]}
        elif kind == "select_channel":
            name, arguments = "select_channel", {"channel": step["channel"]}
        elif kind == "select_character":
            name, arguments = "select_character", {"slot": step.get("slot", 0)}
        elif kind in ("key", "move"):
            key, hold = self.KEY_STEPS[kind]
            value = step.get(key)
            if not value:
                return {"result": "refused", "detail": "a %s step needs %s" % (kind, key)}
            name = "press_key"
            arguments = {"name": str(value).upper(), "hold_ms": int(step.get("hold_ms" if kind == "key" else "ms")
                                                                     or 0)}
        elif kind == "jump":
            return {"result": "refused", "detail": "a jump step needs the client's jump key and timing: not "
                                                   "available in this client build"}
        elif kind in ("click_npc", "wheel", "type", "key_down", "key_up"):
            return {"result": "refused", "detail": "%s is not available in this client build" % kind}
        answer = self.tool(name, arguments)
        text = "\n".join(answer.get("text") or []).strip()
        if answer.get("isError"):
            return {"result": "refused", "detail": text}
        return {"result": "ok", "detail": text[:200]}

    def wait_signal(self, alias, name, timeout_s, extra):
        """The next event named `name` within timeout_s, or None; the pipe closing fails the wait at once."""
        problem = self.missing_signal(name, extra)
        if problem:
            raise RunFailed(problem, "refused")
        deadline = self.clock() + timeout_s
        while True:
            got = self.events(alias)
            hit = next((event for event in got if event.get("ev") == name), None)
            if hit:
                return {"name": name, "detail": self.payload(hit), "utc": hit.get("utc") or iso(utc_now()),
                        "text": self.text_of(hit)}
            if any(event.get("ev") == "_closed" for event in got) or self.qa_closed(alias):
                raise RunFailed("the QA pipe closed while waiting for %s: the client exited or disconnected"
                                % name, "refused")
            left = deadline - self.clock()
            if left <= 0:
                return None
            self.sleep(min(0.25, left))

    def qa_closed(self, alias):
        return any(event.get("ev") == "_closed" for event in self.seen.get(alias, []))

    @staticmethod
    def payload(event):
        """The fields of a pipe event that say what happened, for the manifest (the client sends the name only)."""
        keys = ("release", "build", "frames", "label", "pid", "error")
        return " ".join("%s=%s" % (key, event[key]) for key in keys if key in event)[:120]

    @staticmethod
    def text_of(event):
        return str(event.get("text") or event.get("line") or "")

    def character(self, alias):
        """{"build", "release", "caps", "dll_sha256"} for the manifest, or RunFailed when the client is not a QA build
        (no hello) or never logged its build stamp (no caps). char_id/char_name: the QA module does not send them."""
        info = dict(self.info.get(alias) or {})
        hello = next((event for event in self.seen.get(alias, []) if event.get("ev") == "hello"), None)
        if hello is None:
            raise RunFailed("no hello from the QA pipe: the client is not a running SLUMS_QA_MODULE build, so no "
                            "script step can be trusted", "error")
        for key in ("build", "release"):
            if key in hello:
                info[key] = hello[key]
        sha = ((self.json_tool("status").get("build") or {}).get("sha256"))
        if sha:
            info["dll_sha256"] = sha
        if "caps" not in info:
            logs = self.json_tool("read_logs", {"lines": 200})
            found = BUILD_STAMP_CAPS.findall(str(logs.get("integration.log") or ""))
            if found:
                info["caps"] = found[-1]
        self.info[alias] = info
        if not all(key in info for key in ("build", "release", "caps")):
            raise RunFailed("the client has not logged its build stamp (release/build/caps) yet: the manifest cannot "
                            "say which client ran", "error")
        return info

    def burst(self, alias, fps, ms, label):
        return self.json_tool("qa_burst", {"fps": fps, "ms": ms, "label": label})

    def capture(self, alias, kind, cap_id, spec, start):
        """chat_log / packet_trace / samples: the QA build records none of them; never report one as recorded."""
        raise RunFailed("a %s capture is not available in this client build (it records no chat log, packet trace or "
                        "samples)" % kind, "refused")

    def pull(self, dest, labels=None):
        """Copies the run's frames and the pipe log into dest (frames/<capture id>/, client/<alias>/).

        `labels` maps each burst label to its capture id (the run knows both); without it nothing is copied."""
        dest = Path(dest)
        pulled = 0
        for label, cap_id in (labels or self.labels).items():
            folder = dest / "frames" / cap_id
            self.transport.fetch("client/qa-captures/" + label, folder)
            convert_frames(folder)
            pulled += 1
        for alias, events in self.seen.items():
            client = dest / "client" / alias
            client.mkdir(parents=True, exist_ok=True)
            with (client / "pipe.log").open("w", encoding="utf-8") as out:
                for event in events:
                    out.write(json.dumps(event) + "\n")
            logs = self.json_tool("read_logs", {"lines": 200})
            (client / "client.log").write_text("\n\n".join("%s:\n%s" % (name, text)
                                                           for name, text in logs.items()), encoding="utf-8")
        return pulled

    def close(self):
        """Leaves the box as it was found: stops the worker's own client (its run folder has the frames already)."""
        if self.closed:
            return
        self.closed = True
        try:
            self.transport.call("stop_client", {})
        except RunFailed:
            pass   # a client that already stopped, or an owner now at the PC: nothing to clean up either way


# ── the run ───────────────────────────────────────────────────────────────────────────────────────

def convert_frames(folder):
    """A burst's BMPs to the PNGs a run folder holds (PIL on 187). Returns the frames converted; 0 when PIL is not
    installed, and the BMPs are left as they came from the worker."""
    try:
        from PIL import Image
    except ImportError:
        return 0
    count = 0
    for bmp in sorted(Path(folder).glob("*.bmp")):
        with Image.open(bmp) as image:
            image.save(bmp.with_suffix(".png"))
        bmp.unlink()
        count += 1
    return count


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
            "clients": {}, "runner": {"orchestrator": "tools/qa-runner 1", "host": "187",
                                      "worker": "client-mcp " + self.worker_target()},
            "trigger": trigger, "started_utc": iso(self.started), "finished_utc": None, "result": "RUNNING",
            "failed_at": None, "steps": [], "captures": [], "checks": [], "findings": []}
        self.burst_caps = {}       # burst label -> capture id, for the pull
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

    def worker_target(self):
        transport = getattr(self.worker, "transport", None)
        return str(getattr(transport, "target", "fake"))

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
        label = "%s-%s" % (self.run_id, cap["id"])
        self.burst_caps[label] = cap["id"]
        self.worker.burst(alias, body["fps"], body["ms"], label)
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
            self.pull()
            self.write()
            self.worker.close()
        return self.manifest

    def pull(self):
        """Copies what the client produced (frames, the pipe log, its logs) into the run folder. A copy that fails is
        recorded and the capture says "missing": the manifest never points at files the folder does not have."""
        try:
            self.worker.pull(self.folder, dict(self.burst_caps))
        except RunFailed as e:
            self.manifest["runner"]["pull"] = "failed: %s" % e
            for cap in self.manifest["captures"]:
                if cap.get("path", "").startswith(("frames/", "client/")):
                    cap["result"] = "missing"

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
            self.learn_character(alias, self.worker.login(alias, self.doc["clients"][alias]))
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

    def learn_character(self, alias, info=None):
        """Fills the manifest's client entry from what the client reported (login, then the first field.ready).
        char_id/char_name only when the build sends them; never invented, so ${alias.name} stays unfilled if not."""
        if info is None:
            info = self.worker.character(alias)
        info = info or {}
        if "char_id" in info:
            self.char_ids[alias] = int(info["char_id"])
            self.values[alias + ".id"] = str(info["char_id"])
        if "char_name" in info:
            self.values[alias + ".name"] = info["char_name"]
        entry = self.manifest["clients"].setdefault(alias, {})
        for key in ("build", "release", "caps", "dll_sha256", "char_id", "char_name", "channel"):
            if key in info:
                entry[key] = info[key]

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
    run = Run(script, SshFeed(), SilviaWorker(target=os.environ.get("CLIENT_MCP_TARGET", "silvia")),
              commit=read_deployed_commit())
    manifest = run.run()
    print("%s %s -> %s" % (manifest["result"], run.run_id, run.folder))
    return 0 if manifest["result"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
