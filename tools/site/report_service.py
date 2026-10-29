#!/usr/bin/env python3
"""Tiny receiver for the SlumsMS "Report a bug / Request a feature" form. Python 3 standard library only.

    python3 tools/site/report_service.py [--file PATH] [--salt-file PATH] [--port 8093]

Listens on 127.0.0.1 only (never any other address). nginx proxies POST /api/report to it (see INSTALL-report.md).
Endpoints: POST /api/report (JSON or form-encoded) and GET /api/report/health. Nothing else.

Each accepted report is one JSON line appended to the reports file:
    {"id", "time" (UTC, ISO 8601), "type" ("bug"|"feature"), "title", "details", "character", "where", "ip_hash",
     "source": "site", "versions": {...}}
"versions" is stamped here at submit time, not taken from the browser (owner, 2026-10-28: reports carry versions):
"server" (and "server_commit") from the game's site export (world.json "version", --world-file), "site" from the
build's assets/build.json (--site-build-file). "launcher" and "client" are what the page was opened with (the
launcher adds ?launcher=&client= to the address), checked as short version strings and kept only then.
The raw IP is never stored or logged: only sha256(salt + ip). Submitted text is only stored, or echoed back
HTML-escaped; it is never executed, rendered or used as a path or command.

Settings (argument beats environment beats default):
    --file / SLUMS_REPORT_FILE            /var/lib/slums-feedback/reports.jsonl
    --salt-file / SLUMS_REPORT_SALT_FILE  /etc/slums-report/salt   (required; the service will not start without it)
    --port / SLUMS_REPORT_PORT            8093
    --proxy-hops / SLUMS_REPORT_PROXY_HOPS  1   proxies in front of nginx (Caddy = 1); see client_ip()
    --world-file / SLUMS_REPORT_WORLD_FILE        the game's site export world.json (for the server version)
    --site-build-file / SLUMS_REPORT_SITE_BUILD   the site's assets/build.json (for the site version)
    --staff-port / SLUMS_REPORT_STAFF_PORT  0 (off)  the staff reader (below)

The staff reader (admin/reports; owner, 2026-10-29): a second listener on 127.0.0.1:--staff-port, which only nginx's
GM-level-3 location (/api/report/staff/, behind /api/auth/admin) proxies to; the public port has no staff routes, so
a missing guard can't open them. GET only, read only:
    GET /api/report/staff/list?type=bug|feature&source=site|ingame&q=text&before=ID&limit=N   newest first
    GET /api/report/staff/ID   one report and its in-game context file (context.file, under the reports folder)
Both send only the report's own fields (STAFF_FIELDS): never ip_hash, and no IP, e-mail or user agent at any depth.
"""
import argparse
import fcntl
import hashlib
import html
import json
import os
import re
import sys
import threading
import time
from collections import deque
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs

MAX_BODY = 8 * 1024
LIMITS = {"title": 140, "details": 2000, "character": 13, "where": 200}
TYPES = {"bug", "feature"}
HONEYPOT = "website"
PER_IP_HOUR, PER_IP_DAY, GLOBAL_DAY = 5, 20, 200
HOUR, DAY = 3600, 86400
_CTRL = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")
VERSION = re.compile(r"^[0-9A-Za-z][0-9A-Za-z.+_-]{0,31}$")


def short_version(v):
    v = clean(v) if isinstance(v, str) else ""
    return v if VERSION.match(v) else None


TAIL = 262144  # the in-game @bug writer (ticket 131) re-reads the same window
_ID = re.compile(r'^\{\s*"id"\s*:\s*(\d+)')


def max_id(fd):
    """The highest "id" in the file's last 256 KB: the game server appends to the same file (ticket 131)."""
    size = os.fstat(fd).st_size
    os.lseek(fd, max(0, size - TAIL), os.SEEK_SET)
    best = 0
    for line in os.read(fd, TAIL).decode("utf-8", "replace").splitlines():
        m = _ID.match(line)
        if m:
            best = max(best, int(m.group(1)))
    return best


class Versions:
    """The server's and the site's versions, read from their files when a report comes in (small files, rare reports)."""

    def __init__(self, world_file=None, site_build_file=None):
        self.world_file, self.site_build_file = world_file, site_build_file

    @staticmethod
    def _read(path):
        try:
            with open(path, encoding="utf-8") as f:
                data = json.load(f)
            return data if isinstance(data, dict) else {}
        except (OSError, ValueError, TypeError):
            return {}

    def now(self):
        out = {}
        v = self._read(self.world_file).get("version") if self.world_file else None
        if isinstance(v, dict):  # {"server": "1.6.1", "commit": "2fab9f9b0"}
            if short_version(v.get("server")):
                out["server"] = v["server"]
            if short_version(v.get("commit")):
                out["server_commit"] = v["commit"]
        elif short_version(v):
            out["server"] = v
        site = self._read(self.site_build_file).get("site") if self.site_build_file else None
        if short_version(site):
            out["site"] = site
        return out


STAFF_FIELDS = ("id", "ref", "time", "type", "source", "title", "details", "character", "where", "versions", "world",
                "channel", "mapId", "mapName", "x", "y", "clientBuild", "context")
HIDDEN = re.compile(r"^(ip|ips|ip_?hash|ip_?address|remote_?addr(ess)?|e-?mails?|ua|user_?agent)$", re.I)
CONTEXT_FILE = re.compile(r"^context/[A-Z]-[0-9]{1,9}\.json$")
CONTEXT_MAX = 256 * 1024
READ_MAX = 16 * 1024 * 1024  # the newest 16 MB of the reports file (years of reports)
STAFF_PAGE, STAFF_PAGE_MAX = 50, 200


def scrub(v):
    """Drop IP, e-mail and user-agent keys at any depth (the game scrubs its context too; this is the second net)."""
    if isinstance(v, dict):
        return {k: scrub(x) for k, x in v.items() if not HIDDEN.match(str(k))}
    if isinstance(v, list):
        return [scrub(x) for x in v]
    return v


class Reader:
    """Staff reads of the reports file: list, filter, one report with its context. Never writes."""

    def __init__(self, path):
        self.path = path
        self.folder = os.path.realpath(os.path.dirname(path) or ".")

    def rows(self):
        try:
            with open(self.path, "rb") as f:
                size = os.fstat(f.fileno()).st_size
                f.seek(max(0, size - READ_MAX))
                lines = f.read().decode("utf-8", "replace").splitlines()
        except FileNotFoundError:
            return []
        if size > READ_MAX:
            lines = lines[1:]  # the first line may be cut
        out = {}
        for line in lines:
            try:
                rec = json.loads(line)
                rid = int(rec["id"])
            except (ValueError, KeyError, TypeError):
                continue
            if not isinstance(rec, dict):
                continue
            row = scrub({k: rec[k] for k in STAFF_FIELDS if k in rec})
            row["id"] = rid
            src = rec.get("source")
            row["source"] = "ingame" if src in ("ingame", "game") else "site"
            out[rid] = row  # a QA cleanup that rewrote a line keeps its last copy
        return sorted(out.values(), key=lambda r: r["id"], reverse=True)

    @staticmethod
    def _text(r):
        return " ".join(str(r.get(k) or "") for k in ("ref", "title", "details", "character", "where")).casefold()

    def list(self, args):
        rows = self.rows()
        total = len(rows)
        kind, source, q = args.get("type", ""), args.get("source", ""), args.get("q", "").strip().casefold()[:100]
        if kind in TYPES:
            rows = [r for r in rows if r.get("type") == kind]
        if source in ("site", "ingame"):
            rows = [r for r in rows if r["source"] == source]
        if q:
            rows = [r for r in rows if q in self._text(r)]
        found = len(rows)
        before = args.get("before", "")
        if before.isdigit():
            rows = [r for r in rows if r["id"] < int(before)]
        limit = args.get("limit", "")
        limit = min(int(limit), STAFF_PAGE_MAX) if limit.isdigit() and int(limit) > 0 else STAFF_PAGE
        page = rows[:limit]
        for r in page:  # the list shows the start of the text; the report itself has it all
            r["details"] = (r.get("details") or "")[:200]
        return {"ok": True, "total": total, "found": found, "reports": page,
                "more": len(rows) > limit, "next": page[-1]["id"] if len(rows) > limit else None}

    def context(self, row):
        c = row.get("context")
        name = c.get("file") if isinstance(c, dict) else None
        if not isinstance(name, str) or not CONTEXT_FILE.match(name):
            return None
        full = os.path.realpath(os.path.join(self.folder, name))
        if not full.startswith(os.path.join(self.folder, "context") + os.sep):
            return None
        try:
            with open(full, "rb") as f:
                data = f.read(CONTEXT_MAX + 1)
        except OSError:
            return {"missing": True}
        if len(data) > CONTEXT_MAX:
            return {"tooBig": True}
        try:
            return scrub(json.loads(data.decode("utf-8", "replace")))
        except ValueError:
            return {"unreadable": True}

    def one(self, rid):
        for r in self.rows():
            if r["id"] == rid:
                return {"ok": True, "report": r, "contextFile": self.context(r)}
        return None


class Rejected(Exception):
    def __init__(self, status, message, retry_after=None):
        super().__init__(message)
        self.status, self.message, self.retry_after = status, message, retry_after


def clean(value, keep_newlines=False):
    """Trim a field; drop control characters. Non-strings count as empty."""
    if not isinstance(value, str):
        return ""
    value = value.replace("\r\n", "\n").replace("\r", "\n")
    if not keep_newlines:
        value = value.replace("\n", " ").replace("\t", " ")
    return _CTRL.sub("", value).strip()


def parse_fields(raw, content_type):
    ctype = (content_type or "").split(";")[0].strip().lower()
    if ctype == "application/json":
        try:
            data = json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            raise Rejected(400, "That did not look like a valid report.")
        if not isinstance(data, dict):
            raise Rejected(400, "That did not look like a valid report.")
        return data, True
    if ctype == "application/x-www-form-urlencoded":
        try:
            q = parse_qs(raw.decode("utf-8"), keep_blank_values=True)
        except UnicodeDecodeError:
            raise Rejected(400, "That did not look like a valid report.")
        return {k: v[0] for k, v in q.items()}, False
    raise Rejected(415, "That did not look like a valid report.")


def validate(data):
    """Return the cleaned report dict, None if the honeypot was filled, or raise Rejected."""
    if clean(data.get(HONEYPOT)):
        return None
    rtype = clean(data.get("type")).lower()
    if rtype not in TYPES:
        raise Rejected(400, "Please pick Bug or Feature request.")
    out = {"type": rtype, "title": clean(data.get("title")),
           "details": clean(data.get("details"), keep_newlines=True),
           "character": clean(data.get("character")), "where": clean(data.get("where")),
           "reported": {k: short_version(data.get(k)) for k in ("launcher", "client") if short_version(data.get(k))}}
    if not out["title"]:
        raise Rejected(400, "Please give your report a short title.")
    names = {"title": "The title", "details": "The details", "character": "The character name",
             "where": "The \"where\" box"}
    for key, limit in LIMITS.items():
        if len(out[key]) > limit:
            raise Rejected(400, f"{names[key]} is too long (the limit is {limit} characters).")
    return out


class Store:
    """The reports file, ids, and the rate limits. One lock; reports are small and rare."""

    def __init__(self, path, salt, limits=(PER_IP_HOUR, PER_IP_DAY, GLOBAL_DAY), clock=time.time):
        self.path, self.salt, self.clock = path, salt, clock
        self.per_hour, self.per_day, self.global_day = limits
        self.lock = threading.Lock()
        self.last_id = 0
        self.hits = {}            # ip_hash -> deque of timestamps (last 24h)
        self.all_hits = deque()   # timestamps of every accepted report (last 24h)
        self._load()

    def _load(self):
        now = self.clock()
        if not os.path.exists(self.path):
            return
        with open(self.path, encoding="utf-8") as f:
            for line in f:
                try:
                    rec = json.loads(line)
                    self.last_id = max(self.last_id, int(rec["id"]))
                    t = datetime.fromisoformat(rec["time"]).timestamp()
                except (ValueError, KeyError, TypeError):
                    continue
                if now - t < DAY and rec.get("source") not in ("ingame", "game"):  # in-game reports aren't the form's
                    self.hits.setdefault(rec.get("ip_hash", ""), deque()).append(t)
                    self.all_hits.append(t)

    def hash_ip(self, ip):
        return hashlib.sha256(self.salt + ip.encode("utf-8", "replace")).hexdigest()

    @staticmethod
    def _trim(dq, now):
        while dq and now - dq[0] >= DAY:
            dq.popleft()

    def add(self, ip, report):
        """Check limits and append. Returns the new id or raises Rejected(429)."""
        ip_hash = self.hash_ip(ip)
        with self.lock:
            now = self.clock()
            self._trim(self.all_hits, now)
            mine = self.hits.setdefault(ip_hash, deque())
            self._trim(mine, now)
            if len(self.all_hits) >= self.global_day:
                raise Rejected(429, "We have had a lot of reports today. Please try again tomorrow.", DAY)
            last_hour = [t for t in mine if now - t < HOUR]
            if len(last_hour) >= self.per_hour:
                raise Rejected(429, "You have sent a few reports in a short time. Please try again in an hour.",
                               int(HOUR - (now - last_hour[0])) + 1)
            if len(mine) >= self.per_day:
                raise Rejected(429, "That is the daily limit for reports. Please try again tomorrow.",
                               int(DAY - (now - mine[0])) + 1)
            # The game server's @bug appends to this file too (ticket 131): both take an exclusive POSIX lock for the
            # write and number the report from the highest id in the file, so the two never hand out the same id.
            try:
                fd = os.open(self.path, os.O_RDWR | os.O_APPEND | os.O_CREAT, 0o640)
            except OSError:
                raise Rejected(503, "Sorry, we could not save your report right now. Please try again later.")
            try:
                fcntl.lockf(fd, fcntl.LOCK_EX)
                rid = max(self.last_id, max_id(fd)) + 1
                rec = {"id": rid, "time": datetime.fromtimestamp(now, timezone.utc).strftime("%Y-%m-%dT%H:%M:%S+00:00"),
                       **report, "ip_hash": ip_hash}
                line = json.dumps(rec, ensure_ascii=False, separators=(",", ":")) + "\n"
                os.write(fd, line.encode("utf-8"))
                os.fsync(fd)
            except OSError:
                raise Rejected(503, "Sorry, we could not save your report right now. Please try again later.")
            finally:
                os.close(fd)  # releases the lock
            self.last_id = rid
            mine.append(now)
            self.all_hits.append(now)
            return rid

    def count(self):
        with self.lock:
            return self.last_id


def client_ip(xff, peer, hops=1):
    """The visitor's address. nginx sets X-Forwarded-For to '<what the proxy sent>, <the proxy's own address>'.
    With the owner's Caddy in front (hops=1) Caddy overwrites the header with the real visitor, so the visitor is
    the second entry from the end; entries further left are client-supplied and ignored. Too few entries: use the
    last one, then the socket peer."""
    parts = [p.strip() for p in (xff or "").split(",") if p.strip()]
    if len(parts) > hops:
        return parts[-(hops + 1)]
    return parts[0] if parts else peer


PAGE = """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="no-referrer"><title>{title} - SlumsMS</title>
<style>body{{font:16px/1.6 system-ui,sans-serif;max-width:34rem;margin:12vh auto;padding:0 16px;background:#fbf6ec;color:#2b2117}}
a{{color:#1d5bb8}}h1{{color:#b9480a}}@media(prefers-color-scheme:dark){{body{{background:#12141b;color:#ece5d8}}a{{color:#8db9ff}}h1{{color:#ffb070}}}}</style>
</head><body><h1>{title}</h1>{body}<p><a href="/report/">Back to the report form</a> &middot; <a href="/">Home</a></p></body></html>
"""


def thanks_page(rid, report):
    body = (f"<p>Your report is logged as <b>#{rid}</b>. The developers will see it.</p>"
            f"<p>You wrote: <i>{html.escape(report['title'])}</i></p>")
    return PAGE.format(title="Thanks!", body=body)


def error_page(message):
    return PAGE.format(title="Could not send your report", body=f"<p>{html.escape(message)}</p>")


def make_handler(store, hops=1, versions=None):
    class Handler(BaseHTTPRequestHandler):
        server_version = "slums-report"
        sys_version = ""
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):  # never log addresses or content
            pass

        def _send(self, status, body, ctype, extra=None):
            data = body.encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
            for k, v in (extra or {}).items():
                self.send_header(k, str(v))
            self.end_headers()
            self.wfile.write(data)

        def _json(self, status, obj, extra=None):
            self._send(status, json.dumps(obj), "application/json; charset=utf-8", extra)

        def do_GET(self):
            if self.path == "/api/report/health":
                return self._json(200, {"ok": True, "reports": store.count()})
            if self.path.split("?")[0] == "/api/report":
                return self._json(405, {"ok": False, "error": "Use the form on the Report page."}, {"Allow": "POST"})
            self._json(404, {"ok": False, "error": "Not found."})

        do_HEAD = do_PUT = do_DELETE = do_PATCH = do_OPTIONS = do_GET

        def do_POST(self):
            if self.path.split("?")[0] != "/api/report":
                self.close_connection = True
                return self._json(404, {"ok": False, "error": "Not found."})
            as_json = True
            try:
                length = self.headers.get("Content-Length")
                if length is None or not length.isdigit():
                    self.close_connection = True
                    raise Rejected(411, "That did not look like a valid report.")
                if int(length) > MAX_BODY:
                    self.close_connection = True
                    raise Rejected(413, "That report is too big to send.")
                raw = self.rfile.read(int(length))
                data, as_json = parse_fields(raw, self.headers.get("Content-Type"))
                report = validate(data)
                if report is None:  # honeypot: look like success, store nothing
                    rid, report = 0, {"title": clean(data.get("title"))[:140]}
                else:
                    ip = client_ip(self.headers.get("X-Forwarded-For"), self.client_address[0], hops)
                    reported = report.pop("reported", {})
                    report = dict(report, source="site", versions=dict(versions.now() if versions else {}, **reported))
                    rid = store.add(ip, report)
            except Rejected as e:
                extra = {"Retry-After": e.retry_after} if e.retry_after else None
                if as_json:
                    return self._json(e.status, {"ok": False, "error": e.message}, extra)
                return self._send(e.status, error_page(e.message), "text/html; charset=utf-8", extra)
            if as_json:
                return self._json(200, {"ok": True, "id": rid})
            return self._send(200, thanks_page(rid, report), "text/html; charset=utf-8")

    return Handler


def read_salt(path):
    with open(path, "rb") as f:
        salt = f.read().strip()
    if len(salt) < 16:
        raise SystemExit(f"salt file {path} is too short (use at least 16 random characters)")
    return salt


def make_staff_handler(reader):
    class Staff(BaseHTTPRequestHandler):
        server_version = "slums-report"
        sys_version = ""
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):
            pass

        def _json(self, status, obj):
            data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "private, no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            path, _, query = self.path.partition("?")
            args = {k: v[0] for k, v in parse_qs(query).items()}
            if path == "/api/report/staff/list":
                return self._json(200, reader.list(args))
            tail = path[len("/api/report/staff/"):] if path.startswith("/api/report/staff/") else ""
            if tail.isdigit() and len(tail) < 10:
                found = reader.one(int(tail))
                return self._json(200, found) if found else self._json(404, {"ok": False, "error": "No such report."})
            self._json(404, {"ok": False, "error": "Not found."})

        def do_POST(self):
            self.close_connection = True
            self._json(405, {"ok": False, "error": "Read only."})

        do_HEAD = do_GET
        do_PUT = do_DELETE = do_PATCH = do_OPTIONS = do_POST

    return Staff


def make_staff_server(reader, port):
    ThreadingHTTPServer.daemon_threads = True
    srv = ThreadingHTTPServer(("127.0.0.1", port), make_staff_handler(reader))
    srv.timeout = 10
    return srv


def make_server(store, port=8093, hops=1, versions=None):
    ThreadingHTTPServer.daemon_threads = True
    srv = ThreadingHTTPServer(("127.0.0.1", port), make_handler(store, hops, versions))
    srv.timeout = 10
    return srv


def main(argv=None):
    env = os.environ.get
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--file", default=env("SLUMS_REPORT_FILE", "/var/lib/slums-feedback/reports.jsonl"))
    ap.add_argument("--salt-file", default=env("SLUMS_REPORT_SALT_FILE", "/etc/slums-report/salt"))
    ap.add_argument("--port", type=int, default=int(env("SLUMS_REPORT_PORT", "8093")))
    ap.add_argument("--proxy-hops", type=int, default=int(env("SLUMS_REPORT_PROXY_HOPS", "1")))
    ap.add_argument("--world-file", default=env("SLUMS_REPORT_WORLD_FILE", ""))
    ap.add_argument("--site-build-file", default=env("SLUMS_REPORT_SITE_BUILD", ""))
    ap.add_argument("--staff-port", type=int, default=int(env("SLUMS_REPORT_STAFF_PORT", "0")))
    args = ap.parse_args(argv)
    store = Store(args.file, read_salt(args.salt_file))
    srv = make_server(store, args.port, args.proxy_hops, Versions(args.world_file or None, args.site_build_file or None))
    print(f"slums-report listening on 127.0.0.1:{args.port}, writing {args.file}", flush=True)
    if args.staff_port:
        staff = make_staff_server(Reader(args.file), args.staff_port)
        threading.Thread(target=staff.serve_forever, daemon=True).start()
        print(f"slums-report staff reader on 127.0.0.1:{args.staff_port}", flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
