#!/usr/bin/env python3
"""Website sign-in with a SlumsMS game account: the game username and password, or a new account with an invite code
(ticket 105). Python 3 standard library only.

    python3 tools/site/login_service.py [--key-file PATH] [--port 8094] [--auth-url URL]

For the site and the launcher alike (owner, 2026-10-26: one way in, `@weblogin` is gone):
- POST /api/login/password {"user", "pass"}: sign in with the game username and password.
- POST /api/login/signup {"code", "user", "pass"}: make an account with an invite code from `!invite`, signed in.
Forgotten passwords (ticket 107): there's no email, and an admin approves each reset in game (`!resetpw approve`).
- POST /api/login/reset/request {"user", "contact"}: asks for a reset. It answers the same for any username, and keeps
  the request in an HttpOnly cookie in this browser.
- POST /api/login/reset/status: {"ok": true, "status": "pending" | "approved" | "denied" | "expired" | "none"}.
- POST /api/login/reset/complete {"pass"}: once approved, sets the new password, signs every session of the account
  out (sessions issued before now stop working, here and in the market service), and signs this browser in.
All of these need a JSON body and the header "X-Slums: 1", and a browser Origin must match the Host (the launcher sends
no Origin). This service never checks or stores a password itself: it passes it on to the game server's auth API on
localhost (--auth-url, POST /verify and /signup, header X-Slums-Auth from --auth-secret), with a salted hash of the
visitor's address (--ip-salt) and never the address itself. Passwords are never logged. Answers: 200 {"ok": true,
"account", "character"} plus a signed session cookie (30 days), or {"ok": false, "error": "<message to show>"} with
400 (a rule), 401 (wrong login), 403 (bad code), 409 (name taken), 423 (locked), 429 (too many tries) or 503 (the
auth API is unreachable, not set up, or can't reach its database).

nginx asks GET /api/auth/check (auth_request) before serving the friends-only sections: 204 when the session is good,
401 otherwise. POST /api/login/logout ends the session on this server too, not only in the browser that asked.
GET /api/login/me says who is signed in: {"signed_in", "character", "account"}.

Download pass (launcher 1.6.1, ticket 106): POST /api/login/download-pass (session cookie, "X-Slums: 1") gives
{"ok": true, "pass", "expiresAt"}, a token the launcher sends as "X-Slums-Pass" on every patch download. It lives 12 h
(never past the session), 401 when not signed in, 403 when the account is banned or revoked, 503 when this server has
no pass key. nginx on the patch host asks GET /api/auth/pass (auth_request, internal): 204 plus "X-Slums-Account:
<account id>" (for the access log) when the pass is good, 401 otherwise. The check is an HMAC and two in-memory lists
(revoked.txt, and password resets' revoked-before.txt), reloaded when the files change: no database, no game call.
Passes are signed with their own key (--pass-key-file), separate from the session key and different on Live and TEST,
so a TEST pass never opens Live downloads. No secret is ever in the launcher.

Listens on 127.0.0.1 only. Sessions are HMAC-SHA256 signed with --key-file (32+ random bytes; the service refuses to
start without it). An account listed in --revoked (one account id per line, e.g. banned accounts) is signed out.

Settings (argument beats environment beats default):
    --key-file / SLUMS_LOGIN_KEY_FILE     /etc/slums-login/key   (required)
    --revoked / SLUMS_LOGIN_REVOKED       /var/lib/slums-login/revoked.txt  (optional)
    --port / SLUMS_LOGIN_PORT             8094
    --secure / SLUMS_LOGIN_SECURE         1: cookies get the Secure flag (the site is served over HTTPS)
    --auth-url / SLUMS_LOGIN_AUTH_URL     the game server's auth API, e.g. http://127.0.0.1:8186 (empty: sign-in and
                                          sign-up answer 503)
    --auth-secret / SLUMS_LOGIN_AUTH_SECRET_FILE   /etc/slums-auth/secret (read by path, never printed)
    --ip-salt / SLUMS_LOGIN_IP_SALT_FILE  /etc/slums-login/ip-salt (32+ random bytes)
    --pass-key-file / SLUMS_LOGIN_PASS_KEY_FILE   /etc/slums-login/pass-key (32+ random bytes; missing: no passes)
Signed-out sessions are listed in revoked-sessions.txt next to --revoked, until they would have expired anyway.
"""
import argparse
import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import sys
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

SESSION_TTL = 30 * 86400
AUTH_PER_IP_MIN = 10  # sign-ins and sign-ups per address a minute (the game server also locks accounts)
MAX_BODY = 1024
SESSION_COOKIE, RESET_COOKIE = "slums_session", "slums_reset"
RESET_TTL = 7 * 86400  # the cookie's life; the server decides when a request expires


def b64(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def unb64(text):
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


class Sessions:
    """Signed session tokens: base64(json) + "." + base64(hmac). Nothing about a session is stored server-side."""

    def __init__(self, key, revoked_file=None, now=time.time):
        self.key, self.revoked_file, self.now = key, revoked_file, now
        # signed-out sessions ("<sid> <exp>" per line), so a copied cookie stops working after sign-out
        self.ended_file = os.path.join(os.path.dirname(revoked_file), "revoked-sessions.txt") if revoked_file else None
        # password resets: "<account> <time>" per line; sessions of that account issued before the time stop working
        self.before_file = os.path.join(os.path.dirname(revoked_file), "revoked-before.txt") if revoked_file else None
        self.ended_mem, self.before_mem, self.lock = {}, {}, threading.Lock()

    def sign(self, payload):
        body = b64(json.dumps(payload, separators=(",", ":"), sort_keys=True).encode())
        mac = b64(hmac.new(self.key, body.encode(), hashlib.sha256).digest())
        return f"{body}.{mac}"

    def issue(self, approval, not_before=0):
        """approval: the game auth API's answer ({account_id, account, character, characters, gm}) -> a token.
        not_before: a password reset's mark, so the new session is issued after it (old ones at or before it end)."""
        t = max(int(self.now()), int(not_before) + 1 if not_before else 0)
        payload = {"a": int(approval["account_id"]), "n": str(approval.get("account", ""))[:32],
                   "c": str(approval.get("character", ""))[:32], "iat": t, "exp": t + SESSION_TTL,
                   "sid": secrets.token_urlsafe(9)}
        # the account's characters on Bera (the Buy/Sell board posts as one of them) and the GM flag, when sent
        cs = approval.get("characters")
        if isinstance(cs, list):
            payload["cs"] = [str(c)[:13] for c in cs if isinstance(c, str) and c][:30]
        if str(approval.get("gm", "0")).isdigit() and int(approval.get("gm", 0)) > 0:
            payload["g"] = 1
        return self.sign(payload)

    def revoked(self):
        if not self.revoked_file or not os.path.isfile(self.revoked_file):
            return set()
        with open(self.revoked_file, encoding="utf-8") as f:
            return {line.strip() for line in f if line.strip().isdigit()}

    def check(self, token):
        """token -> payload dict, or None if it is forged, expired or revoked."""
        if not token or token.count(".") != 1:
            return None
        body, mac = token.split(".")
        want = b64(hmac.new(self.key, body.encode(), hashlib.sha256).digest())
        if not hmac.compare_digest(mac, want):
            return None
        try:
            payload = json.loads(unb64(body))
        except (ValueError, json.JSONDecodeError):
            return None
        if not isinstance(payload, dict) or payload.get("exp", 0) < self.now():
            return None
        if str(payload.get("a")) in self.revoked():
            return None
        if payload.get("sid") and payload["sid"] in self.ended():
            return None
        if payload.get("iat", 0) <= self.before().get(str(payload.get("a")), -1):
            return None
        return payload

    def revoked_account(self, token):
        """True when token is genuine and unexpired but its account is revoked (banned): 403 rather than 401."""
        if not token or token.count(".") != 1:
            return False
        body, mac = token.split(".")
        if not hmac.compare_digest(mac, b64(hmac.new(self.key, body.encode(), hashlib.sha256).digest())):
            return False
        try:
            payload = json.loads(unb64(body))
        except (ValueError, json.JSONDecodeError):
            return False
        return isinstance(payload, dict) and payload.get("exp", 0) >= self.now() and str(payload.get("a")) in self.revoked()

    def before(self):
        if not self.before_file:
            return self.before_mem
        try:
            with open(self.before_file, encoding="utf-8") as f:
                return {p[0]: float(p[1]) for p in (line.split() for line in f) if len(p) == 2}
        except (OSError, ValueError):
            return {}

    def end_all(self, account_id):
        """A password reset: every session of the account issued up to now stops working, everywhere. -> the mark."""
        with self.lock:
            mark = int(self.now())
            marks = dict(self.before(), **{str(int(account_id)): mark})
            if not self.before_file:
                self.before_mem = marks
                return mark
            tmp = self.before_file + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                f.writelines(f"{k} {int(v)}\n" for k, v in marks.items())
            os.replace(tmp, self.before_file)
            return mark

    def ended(self):
        if not self.ended_file:
            return self.ended_mem
        try:
            with open(self.ended_file, encoding="utf-8") as f:
                return {p[0]: float(p[1]) for p in (line.split() for line in f) if len(p) == 2}
        except (OSError, ValueError):
            return {}

    def end(self, payload):
        """Sign-out: this session stops working everywhere, not only in the browser that asked."""
        sid = payload.get("sid")
        if not sid:
            return
        with self.lock:
            t = self.now()
            keep = {k: v for k, v in self.ended().items() if v > t}
            keep[sid] = payload.get("exp", t + SESSION_TTL)
            if not self.ended_file:
                self.ended_mem = keep
                return
            tmp = self.ended_file + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                f.writelines(f"{k} {int(v)}\n" for k, v in keep.items())
            os.replace(tmp, self.ended_file)


PASS_TTL = 12 * 3600
PASS_PREFIX = "dp1."  # a pass never parses as a session cookie, and the other way round


class Passes:
    """Download passes: "dp1." + base64(json {a, iat, exp}) + "." + base64(hmac), signed with the pass key. The check
    is for nginx's auth_request on every patch download (cached there 60 s), so it reads no database: the revoked and
    reset lists are kept in memory and reloaded only when their files change."""

    def __init__(self, key, sessions, now=time.time):
        self.key, self.sessions, self.now = key, sessions, now
        self.cache = {}  # path -> (mtime, value)

    def _load(self, path, read):
        try:  # mtime alone can miss two writes within one clock tick; size and inode catch most of those
            st = os.stat(path) if path else None
            m = st and (st.st_mtime_ns, st.st_size, st.st_ino)
        except OSError:
            m = None
        hit = self.cache.get(path)
        if hit and hit[0] == m:
            return hit[1]
        value = read()
        self.cache[path] = (m, value)
        return value

    def revoked(self):
        return self._load(self.sessions.revoked_file, self.sessions.revoked)

    def before(self):
        if not self.sessions.before_file:
            return self.sessions.before()
        return self._load(self.sessions.before_file, self.sessions.before)

    def mac(self, body):
        return b64(hmac.new(self.key, (PASS_PREFIX + body).encode(), hashlib.sha256).digest())

    def issue(self, session):
        t = int(self.now())
        exp = min(t + PASS_TTL, int(session.get("exp", t + PASS_TTL)))
        body = b64(json.dumps({"a": int(session["a"]), "iat": t, "exp": exp}, separators=(",", ":"),
                              sort_keys=True).encode())
        return f"{PASS_PREFIX}{body}.{self.mac(body)}", exp

    def check(self, token):
        """token -> the account id, or None if it is forged, expired, revoked or older than a password reset."""
        if not token or not token.startswith(PASS_PREFIX) or len(token) > 512:
            return None
        rest = token[len(PASS_PREFIX):]
        if rest.count(".") != 1:
            return None
        body, mac = rest.split(".")
        if not hmac.compare_digest(mac, self.mac(body)):
            return None
        try:
            p = json.loads(unb64(body))
        except (ValueError, json.JSONDecodeError):
            return None
        if not isinstance(p, dict) or not isinstance(p.get("a"), int) or p.get("exp", 0) < self.now():
            return None
        a = str(p["a"])
        if a in self.revoked() or p.get("iat", 0) <= self.before().get(a, -1):
            return None
        return p["a"]


USER_RE = re.compile(r"^[A-Za-z0-9]+$")
PASS_RE = re.compile(r"^[\x21-\x7e]+$")  # printable, no spaces
RULES = {"user_length": "Usernames are 4 to 12 letters or numbers.",
         "reserved": "That username isn't allowed. Pick another.",
         "user_chars": "Usernames are 4 to 12 letters or numbers.",
         "pass_length": "Passwords are 6 to 12 characters (the game's login box takes 12).",
         "pass_chars": "Passwords can't have spaces or special symbols.",
         "bot_name": "That username is taken."}
ERRORS = {"BAD_CODE": (403, "That invite code doesn't work."),
          "UNAVAILABLE": (503, "Sign-in is down right now. Try again in a bit."),
          "NOT_APPROVED": (409, "That reset isn't approved (yet). Check its status above."),
          "USED": (410, "That reset was already used. Sign in with your new password."),
          "EXPIRED": (410, "That request ran out. Ask again."),
          "NAME_TAKEN": (409, "That username is taken."),
          "WRONG_LOGIN": (401, "Wrong username or password."),
          "LOCKED": (423, "Too many wrong tries. Try again in 15 minutes."),
          "RATE_LIMITED": (429, "Too many tries. Wait a minute and try again.")}
DOWN = (503, "Sign-in is down right now. Try again in a bit.")


def sign_in_broken(user, pw):
    """Sign-in: only what can't be a game login. Older accounts may break the sign-up rules, so the game decides."""
    if not isinstance(user, str) or not 1 <= len(user) <= 64 or not PASS_RE.match(user):
        return "user_length"
    if not isinstance(pw, str) or not 1 <= len(pw) <= 64:
        return "pass_length"
    return None


def rule_broken(user, pw):
    """The sign-up rules (ticket 105), checked before anything is sent; the game server checks too."""
    if not isinstance(user, str) or not 4 <= len(user) <= 12:
        return "user_length"
    if not USER_RE.match(user):
        return "user_chars"
    if not isinstance(pw, str) or not 6 <= len(pw) <= 12:
        return "pass_length"
    if not PASS_RE.match(pw):
        return "pass_chars"
    return None


def clean_code(code):
    return re.sub(r"[\s-]", "", code).upper() if isinstance(code, str) else ""


class GameAuth:
    """The game server's auth API on localhost (POST /verify, /signup). The secret and salt are read by path each
    call, so they can be made after the service starts; neither is ever printed."""

    def __init__(self, url, secret_file, salt_file, timeout=8):
        self.url, self.secret_file, self.salt_file, self.timeout = (url or "").rstrip("/"), secret_file, salt_file, timeout

    def _read(self, path):
        try:
            with open(path, "rb") as f:
                return f.read().strip()
        except (OSError, TypeError):
            return b""

    def ip_hash(self, ip):
        salt = self._read(self.salt_file)
        return hmac.new(salt, ip.encode(), hashlib.sha256).hexdigest()[:32] if len(salt) >= 16 else None

    def call(self, path, body, ip):
        """-> the API's JSON answer, or None when it can't be reached or isn't set up."""
        secret, ip_hash = self._read(self.secret_file), self.ip_hash(ip)
        if not self.url or not secret or not ip_hash:
            return None
        req = urllib.request.Request(self.url + path, method="POST", data=json.dumps(dict(body, ipHash=ip_hash)).encode(),
                                     headers={"Content-Type": "application/json", "X-Slums-Auth": secret.decode()})
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                data = json.loads(r.read())
        except urllib.error.HTTPError as e:
            try:
                data = json.loads(e.read())
            except (ValueError, OSError):
                return None
        except (OSError, ValueError):
            return None
        return data if isinstance(data, dict) else None


class Limiter:
    def __init__(self, per_min, now=time.time):
        self.per_min, self.now, self.lock, self.hits = per_min, now, threading.Lock(), {}

    def allow(self, key):
        with self.lock:
            t = self.now()
            recent = [h for h in self.hits.get(key, []) if h > t - 60]
            if len(recent) >= self.per_min:
                self.hits[key] = recent
                return False
            self.hits[key] = recent + [t]
            if len(self.hits) > 5000:  # forget quiet addresses
                self.hits = {k: v for k, v in self.hits.items() if v and v[-1] > t - 60}
            return True


def cookies(header):
    out = {}
    for part in (header or "").split(";"):
        if "=" in part:
            k, v = part.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def make_handler(sessions, secure, auth=None, limiter=None, passes=None):
    flags = "; Path=/; HttpOnly; SameSite=Lax" + ("; Secure" if secure else "")
    auth = auth or GameAuth(None, None, None)
    limiter = limiter or Limiter(AUTH_PER_IP_MIN)

    class Handler(BaseHTTPRequestHandler):
        server_version = "slums-login"
        sys_version = ""

        def log_message(self, fmt, *args):  # no client addresses or cookies in the log
            sys.stderr.write("%s %s\n" % (self.command, urlsplit(self.path).path))

        def client(self):
            fwd = self.headers.get("X-Forwarded-For", "")
            return fwd.split(",")[0].strip() or self.client_address[0]

        def send(self, status, body=None, set_cookies=(), headers=None):
            data = b"" if body is None else json.dumps(body).encode()
            self.send_response(status)
            self.send_header("Cache-Control", "no-store")
            for k, v in (headers or {}).items():
                self.send_header(k, v)
            for c in set_cookies:
                self.send_header("Set-Cookie", c)
            if body is not None:
                self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            if data:
                self.wfile.write(data)

        def session(self):
            return sessions.check(cookies(self.headers.get("Cookie")).get(SESSION_COOKIE))

        def do_GET(self):
            path = urlsplit(self.path).path
            if path == "/api/auth/check":
                return self.send(204 if self.session() else 401)
            if path == "/api/auth/pass":
                a = passes.check(self.headers.get("X-Slums-Pass")) if passes else None
                return self.send(401) if a is None else self.send(204, headers={"X-Slums-Account": str(a)})
            if path == "/api/login/me":
                s = self.session()
                return self.send(200, {"signed_in": bool(s), "character": (s.get("c") or None) if s else None,
                                       "account": s.get("n") if s else None})
            if path == "/api/login/health":
                return self.send(200, {"ok": True})
            return self.send(404, {"ok": False})

        def do_POST(self):
            path = urlsplit(self.path).path
            if path == "/api/login/logout":
                s = self.session()
                if s:
                    sessions.end(s)
                return self.send(200, {"ok": True}, [f"{SESSION_COOKIE}=; Max-Age=0{flags}"])
            if path in ("/api/login/password", "/api/login/signup"):
                return self.account_sign_in(path.rsplit("/", 1)[1])
            if path == "/api/login/download-pass":
                return self.download_pass()
            if path in ("/api/login/reset/request", "/api/login/reset/status", "/api/login/reset/complete"):
                return self.reset(path.rsplit("/", 1)[1])
            return self.send(404, {"ok": False})

        def json_body(self):
            """A JSON object sent by this site or the launcher, or None. A cross-site form can't set X-Slums."""
            if self.headers.get("X-Slums") != "1":
                return None
            origin = self.headers.get("Origin")
            if origin and urlsplit(origin).netloc != self.headers.get("Host", ""):
                return None
            try:
                n = int(self.headers.get("Content-Length") or 0)
                data = json.loads(self.rfile.read(n)) if 0 < n <= MAX_BODY else None
            except (ValueError, UnicodeDecodeError):
                return None
            return data if isinstance(data, dict) else None

        def account_sign_in(self, kind):
            body = self.json_body()
            if body is None:
                return self.send(403, {"ok": False, "error": "Refused."})
            if not limiter.allow(self.client()):
                return self.send(*self.err(ERRORS["RATE_LIMITED"]))
            user, pw = body.get("user"), body.get("pass")
            broken = rule_broken(user, pw) if kind == "signup" else sign_in_broken(user, pw)
            if broken:
                return self.send(400, {"ok": False, "error": RULES[broken], "rule": broken})
            if kind == "signup":
                code = clean_code(body.get("code"))
                if not 4 <= len(code) <= 32:
                    return self.send(*self.err(ERRORS["BAD_CODE"]))
                got = auth.call("/signup", {"code": code, "user": user, "pass": pw}, self.client())
            else:
                got = auth.call("/verify", {"user": user, "pass": pw}, self.client())
            if not got:
                return self.send(*self.err(DOWN))
            if not got.get("ok"):
                if got.get("error") == "BAD_INPUT" and got.get("rule") in RULES:
                    return self.send(400, {"ok": False, "error": RULES[got["rule"]], "rule": got["rule"]})
                return self.send(*self.err(ERRORS.get(got.get("error"), ERRORS["WRONG_LOGIN"]
                                                      if kind == "password" else DOWN)))
            if not str(got.get("account_id", "")).isdigit():
                return self.send(*self.err(DOWN))
            return self.signed_in(got, user)

        def signed_in(self, got, user, extra_cookies=(), not_before=0):
            chars = [c for c in got.get("characters") or [] if isinstance(c, str) and c]
            token = sessions.issue({"account_id": got["account_id"], "account": got.get("account") or user,
                                    "character": chars[0] if chars else "", "characters": chars,
                                    "gm": got.get("gm_level", 0)}, not_before)
            return self.send(200, {"ok": True, "account": got.get("account") or user, "character": chars[0] if chars else None},
                             [f"{SESSION_COOKIE}={token}; Max-Age={SESSION_TTL}{flags}", *extra_cookies])

        def reset(self, step):
            """Ticket 107: a password reset an admin approves in game. The request lives in an HttpOnly cookie."""
            body = self.json_body()
            if body is None:
                return self.send(403, {"ok": False, "error": "Refused."})
            if not limiter.allow(self.client()):
                return self.send(*self.err(ERRORS["RATE_LIMITED"]))
            rid, _, secret = (cookies(self.headers.get("Cookie")).get(RESET_COOKIE) or "").partition(".")
            if step == "request":
                user, contact = body.get("user"), body.get("contact")
                if sign_in_broken(user, "x"):
                    return self.send(400, {"ok": False, "error": "Type your game username."})
                if not isinstance(contact, str) or not 2 <= len(contact.strip()) <= 64:
                    return self.send(400, {"ok": False, "error": "Say how an admin can reach you (e.g. your Discord name), up to 64 characters."})
                got = auth.call("/reset/request", {"user": user, "contact": contact.strip()}, self.client())
                if not got or not got.get("request_id") or not got.get("secret"):
                    return self.send(*self.err(ERRORS.get((got or {}).get("error"), DOWN)))
                value = f"{got['request_id']}.{got['secret']}"
                if not re.match(r"^[\w-]{1,64}\.[\w-]{8,128}$", value):
                    return self.send(*self.err(DOWN))
                return self.send(200, {"ok": True, "status": "pending"}, [f"{RESET_COOKIE}={value}; Max-Age={RESET_TTL}{flags}"])
            if not rid or not secret:
                return self.send(200, {"ok": True, "status": "none"})
            if step == "status":
                got = auth.call("/reset/status", {"request_id": rid, "secret": secret}, self.client())
                st = (got or {}).get("status")
                if st not in ("pending", "approved", "denied", "expired"):
                    return self.send(*self.err(ERRORS.get((got or {}).get("error"), DOWN)))
                gone = [f"{RESET_COOKIE}=; Max-Age=0{flags}"] if st in ("denied", "expired") else []
                return self.send(200, {"ok": True, "status": st}, gone)
            pw = body.get("pass")
            broken = rule_broken("abcd", pw)
            if broken:
                return self.send(400, {"ok": False, "error": RULES[broken], "rule": broken})
            got = auth.call("/reset/complete", {"request_id": rid, "secret": secret, "pass": pw}, self.client())
            if not got:
                return self.send(*self.err(DOWN))
            if not got.get("ok"):
                if got.get("error") == "BAD_INPUT" and got.get("rule") in RULES:
                    return self.send(400, {"ok": False, "error": RULES[got["rule"]], "rule": got["rule"]})
                done = [f"{RESET_COOKIE}=; Max-Age=0{flags}"] if got.get("error") in ("USED", "EXPIRED") else []
                return self.send(*self.err(ERRORS.get(got.get("error"), ERRORS["NOT_APPROVED"])), done)
            if not str(got.get("account_id", "")).isdigit():
                return self.send(*self.err(DOWN))
            mark = sessions.end_all(got["account_id"])   # every old session of the account, whatever revoke_sessions says
            return self.signed_in(got, got.get("account", ""), [f"{RESET_COOKIE}=; Max-Age=0{flags}"], mark)

        def download_pass(self):
            if self.headers.get("X-Slums") != "1":
                return self.send(403, {"ok": False, "error": "Missing header."})
            origin = self.headers.get("Origin")
            if origin and urlsplit(origin).netloc != self.headers.get("Host", ""):
                return self.send(403, {"ok": False, "error": "Wrong origin."})
            token = cookies(self.headers.get("Cookie")).get(SESSION_COOKIE)
            s = sessions.check(token)
            if not s:
                if sessions.revoked_account(token):
                    return self.send(403, {"ok": False, "error": "This account can't download right now."})
                return self.send(401, {"ok": False, "error": "Sign in first."})
            if not passes:
                return self.send(*self.err(DOWN))
            pw, exp = passes.issue(s)
            return self.send(200, {"ok": True, "pass": pw,
                                   "expiresAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(exp))})

        @staticmethod
        def err(pair):
            return pair[0], {"ok": False, "error": pair[1]}

    return Handler


def setting(args, name, env, default):
    v = getattr(args, name)
    return v if v is not None else os.environ.get(env, default)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--key-file")
    ap.add_argument("--revoked")
    ap.add_argument("--port", type=int)
    ap.add_argument("--secure")
    ap.add_argument("--auth-url")
    ap.add_argument("--auth-secret")
    ap.add_argument("--ip-salt")
    ap.add_argument("--pass-key-file")
    args = ap.parse_args()
    key_file = setting(args, "key_file", "SLUMS_LOGIN_KEY_FILE", "/etc/slums-login/key")
    revoked = setting(args, "revoked", "SLUMS_LOGIN_REVOKED", "/var/lib/slums-login/revoked.txt")
    port = int(setting(args, "port", "SLUMS_LOGIN_PORT", 8094))
    secure = str(setting(args, "secure", "SLUMS_LOGIN_SECURE", "1")) == "1"
    auth = GameAuth(setting(args, "auth_url", "SLUMS_LOGIN_AUTH_URL", ""),
                    setting(args, "auth_secret", "SLUMS_LOGIN_AUTH_SECRET_FILE", "/etc/slums-auth/secret"),
                    setting(args, "ip_salt", "SLUMS_LOGIN_IP_SALT_FILE", "/etc/slums-login/ip-salt"))
    try:
        with open(key_file, "rb") as f:
            key = f.read().strip()
    except OSError:
        raise SystemExit(f"no key file at {key_file}; make one with: head -c 48 /dev/urandom > {key_file}")
    if len(key) < 32:
        raise SystemExit(f"{key_file} is too short; use at least 32 random bytes")
    sessions = Sessions(key, revoked)
    passes, pass_file = None, setting(args, "pass_key_file", "SLUMS_LOGIN_PASS_KEY_FILE", "/etc/slums-login/pass-key")
    try:
        with open(pass_file, "rb") as f:
            pass_key = f.read().strip()
        if len(pass_key) < 32:
            raise SystemExit(f"{pass_file} is too short; use at least 32 random bytes")
        if hmac.compare_digest(pass_key, key):
            raise SystemExit(f"{pass_file} must not be the session key")
        passes = Passes(pass_key, sessions)
    except FileNotFoundError:
        pass  # no pass key yet: download-pass answers 503 and every pass check 401
    server = ThreadingHTTPServer(("127.0.0.1", port), make_handler(sessions, secure, auth, passes=passes))
    print(f"login service on 127.0.0.1:{port}, game auth API {auth.url or '(not set: sign-in answers 503)'}, "
          f"download passes {'on' if passes else 'off (no pass key)'}", file=sys.stderr)
    server.serve_forever()


if __name__ == "__main__":
    main()
