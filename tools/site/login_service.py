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
GET /api/login/me says who is signed in: {"signed_in", "character", "account", "admin", "characters"}.

Admin (the staff dashboard at /admin/, owner 2026-10-28): GM level 3 and up, and only within 12 h of signing in (a
stolen old cookie opens nothing; an admin signs in again after that). The session carries the GM level the game sent
at sign-in ("gl"). nginx asks GET /api/auth/admin (auth_request) before /admin/ and its data: 204 for an admin, 401
when not signed in or an admin's sign-in is older than 12 h (nginx sends both to /login/?again=1, b055), 403 for
anyone else. "admin" in /api/login/me is the same
check, for the header's Admin link.

Download pass (launcher 1.6.1, ticket 106): POST /api/login/download-pass (session cookie, "X-Slums: 1") gives
{"ok": true, "pass", "expiresAt": <Unix seconds>}, a token the launcher sends as "X-Slums-Pass" on every patch download. It lives 12 h
(never past the session), 401 when not signed in, 403 when the account is banned or revoked, 503 when this server has
no pass key. nginx on the patch host asks GET /api/auth/pass (auth_request, internal): 204 plus "X-Slums-Account:
<account id>" (for the access log) when the pass is good, 401 otherwise. The check is an HMAC and two in-memory lists
(revoked.txt, and password resets' revoked-before.txt), reloaded when the files change: no database, no game call.
Passes are signed with their own key (--pass-key-file), separate from the session key and different on Live and TEST,
so a TEST pass never opens Live downloads. No secret is ever in the launcher.

Invite codes (1.6.1, ticket 105; owner 2026-10-28: GMs and players): POST /api/login/invites/list {page?},
/invites/create {life?, note?} and /invites/cancel {code} (session cookie, "X-Slums: 1"), each passed to the game's auth
API (/invite/list, /invite/create, /invite/cancel) with the session's account id. The game decides who may do what:
a player sees and makes only their own codes under the in-game @invite rule (level 30+, 2 a week, the same count as
@invite), a GM sees every code. A life or note is only passed on for a staff session (GM level 3+, as /admin/).
Answers: 200 {"ok": true, ...the game's answer}; 401 signed out; 403 not eligible or refused; 429 the weekly limit or
too many tries; 503 the game unreachable. Every make or cancel is logged with the account id (never the code).

Exchange password step (1.6.2, b005): POST /api/login/exchange-approve {token, pass} (session cookie, "X-Slums: 1",
counted with the sign-ins per address) passes the password typed on the exchange's confirm page to the game's
/exchange/approve with the session's account id and name. The game checks it like a sign-in (its rate limit and
lockout, and it must be this account's) and approves that one preview token; the confirm then goes through the market
app as usual. Every site sell and big site buys need it, so a stolen cookie alone can't empty an account. Answers:
200 {"ok": true, "expiresAt"}; 400 bad input; 401 signed out or wrong password; 410 the preview ran out; 423 locked;
429 too many tries; 503 the exchange paused or unreachable. The password is never logged, kept or written anywhere;
only the account id and the answer code are logged.

Bot inspect (1.6.2, ticket 157; the server's docs/design/inspect-api.md): GET /api/login/staff/inspect/bots/<cid>,
/maps/<id> and /positions ("X-Slums: 1") for the staff admin's Bots page. Staff only, as /admin/ (GM level 3+, a
sign-in at most 12 h old: 401 signed out or too old, 403 anyone else), 120 calls per account a minute. Each is passed
on to the game's read-only inspect endpoint on this host's loopback (--inspect-url, header X-Slums-Inspect from
--inspect-secret, read by path each call and never printed or sent to a browser). The game's JSON comes back as it is;
404 that bot isn't online or nobody is on that map; 503 the endpoint is off, unreachable or refused the secret.
Only a loopback --inspect-url (127.x, ::1, localhost) is used; anything else keeps it off.

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
    --proxy-hops / SLUMS_LOGIN_PROXY_HOPS 1: proxies in front of nginx (live: Caddy = 1; TEST: 0), see client_ip()
    --inspect-url / SLUMS_LOGIN_INSPECT_URL       the game's bot inspect endpoint, e.g. http://127.0.0.1:8098 (empty: off)
    --inspect-secret / SLUMS_LOGIN_INSPECT_SECRET_FILE   /etc/slums-inspect/secret (16+ characters, read by path)
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
INVITES_PER_MIN = 30  # invite-page calls per account a minute (the game also limits making codes)
INSPECT_PER_MIN = 120  # bot inspect calls per staff account a minute (the game caches each answer 2 s)
INSPECT_MAX = 4 * 1024 * 1024  # the biggest inspect answer passed on (/positions for 3,000 bots is under 100 KB)
INSPECT_PATH = re.compile(r"^/api/login/staff/inspect/(bots/\d{1,10}|maps/\d{1,10}|positions)$")
MAX_BODY = 1024
SESSION_COOKIE, RESET_COOKIE = "slums_session", "slums_reset"
ADMIN_LEVEL, ADMIN_TTL = 3, 12 * 3600  # the staff dashboard: GM level 3+, a sign-in at most 12 h old
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
            payload["gl"] = min(int(approval["gm"]), 99)
        return self.sign(payload)

    def revoked(self):
        if not self.revoked_file or not os.path.isfile(self.revoked_file):
            return set()
        with open(self.revoked_file, encoding="utf-8") as f:
            return {line.strip() for line in f if line.strip().isdigit()}

    def stale_admin(self, s):
        """GM level ADMIN_LEVEL or up, but signed in more than ADMIN_TTL ago: needs a fresh sign-in, not a refusal."""
        return bool(s) and isinstance(s.get("gl"), int) and s["gl"] >= ADMIN_LEVEL and not self.admin(s)

    def admin(self, s):
        """Is this checked session an admin's: GM level ADMIN_LEVEL or up, signed in at most ADMIN_TTL ago?"""
        return bool(s) and isinstance(s.get("gl"), int) and s["gl"] >= ADMIN_LEVEL and \
            int(self.now()) - int(s.get("iat", 0)) <= ADMIN_TTL

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
PASS_MIN, PASS_MAX = 6, 23  # a new password; the game's login box takes 23 characters (owner, 2026-10-28)
RULES = {"user_length": "Usernames are 4 to 12 letters or numbers.",
         "reserved": "That username isn't allowed. Pick another.",
         "user_chars": "Usernames are 4 to 12 letters or numbers.",
         "pass_length": f"Passwords are {PASS_MIN} to {PASS_MAX} characters (the game's login box takes {PASS_MAX}).",
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
# 1.6.2 b005: the game's /exchange/approve answers -> the site's status and message (the password step of one order)
APPROVE_ERRORS = {"WRONG_LOGIN": (401, "Wrong password."),
                  "LOCKED": ERRORS["LOCKED"],
                  "RATE_LIMITED": ERRORS["RATE_LIMITED"],
                  "TOKEN_EXPIRED": (410, "That preview expired. Preview the order again."),
                  "HALTED": (503, "The exchange is paused right now. Nothing was placed; try again later."),
                  "BAD_INPUT": (400, "That didn't look right. Preview the order again."),
                  "UNAVAILABLE": (503, "The exchange can't check passwords right now. Try again in a bit.")}
TOKEN_RE = re.compile(r"^[0-9a-f]{8,64}$")
# the game's /invite/* answers (ticket 105) -> the site's status and message
INVITES_DOWN = (503, "Invite codes aren't available right now. Try again later.")
INVITE_ERRORS = {"NOT_ELIGIBLE": (403, "Invite codes need a character at level 30 or higher."),
                 "WEEKLY_LIMIT": (429, "That's your 2 invite codes for this week. More next week."),
                 "REFUSED": (403, "This account can't make invite codes."),
                 "NOT_FOUND": (404, "No such unused code."),
                 "ALREADY_USED": (409, "That code was already used."),
                 "ALREADY_CANCELLED": (409, "That code was already cancelled."),
                 "BAD_INPUT": (400, "A code can last 1 to 90 days, or forever."),
                 "BAD_REQUEST": (400, "That didn't look right. Try again."),
                 "RATE_LIMITED": (429, "Too many tries. Wait a minute and try again."),
                 "UNAVAILABLE": INVITES_DOWN}


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
    if not isinstance(pw, str) or not PASS_MIN <= len(pw) <= PASS_MAX:
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

    @staticmethod
    def _read(path):
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


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):  # never follow one: the secret header would go along
        return None


class Inspect:
    """The game's bot inspect endpoint (ticket 157): GET only, on this host's loopback, behind the X-Slums-Inspect
    secret. The secret is read by path each call (it can be made after the service starts) and never printed."""

    def __init__(self, url, secret_file, timeout=4):
        self.url, self.secret_file, self.timeout = (url or "").rstrip("/"), secret_file, timeout
        parts = urlsplit(self.url)
        host = parts.hostname or ""
        self.refused = bool(self.url) and not (parts.scheme == "http" and (host in ("localhost", "::1")
                                                                           or re.match(r"^127\.\d{1,3}\.\d{1,3}\.\d{1,3}$", host)))
        if self.refused:
            self.url = ""
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect)

    def get(self, path):
        """path ('bots/12', 'maps/100000000', 'positions') -> (status, JSON object), or None when it is off, can't
        be reached or doesn't answer JSON."""
        secret = GameAuth._read(self.secret_file)
        if not self.url or len(secret) < 16 or not re.fullmatch(rb"[\x21-\x7e]+", secret):  # header-safe text only
            return None
        req = urllib.request.Request(f"{self.url}/{path}", headers={"X-Slums-Inspect": secret.decode("ascii")})
        try:
            with self.opener.open(req, timeout=self.timeout) as r:
                status, raw = r.status, r.read(INSPECT_MAX + 1)
        except urllib.error.HTTPError as e:
            try:
                status, raw = e.code, e.read(INSPECT_MAX + 1)
            except OSError:
                return None
        except (OSError, ValueError):
            return None
        try:
            data = json.loads(raw) if len(raw) <= INSPECT_MAX else None
        except ValueError:
            return None
        return (status, data) if isinstance(data, dict) else None


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


def client_ip(xff, peer, hops=1):
    """The visitor's address, as report_service.py's client_ip() works it out (audit W11): nginx appends the address it
    saw, so with `hops` proxies in front (live: Caddy = 1; TEST: 0) the visitor is entry -(hops + 1) from the end.
    Entries further left are whatever the visitor sent and are never trusted."""
    parts = [p.strip() for p in (xff or "").split(",") if p.strip()]
    if len(parts) > hops:
        return parts[-(hops + 1)]
    return parts[0] if parts else peer


def make_handler(sessions, secure, auth=None, limiter=None, passes=None, inspect=None, hops=1):
    flags = "; Path=/; HttpOnly; SameSite=Lax" + ("; Secure" if secure else "")
    auth = auth or GameAuth(None, None, None)
    limiter = limiter or Limiter(AUTH_PER_IP_MIN)
    invite_limiter = Limiter(INVITES_PER_MIN)
    inspect_limiter = Limiter(INSPECT_PER_MIN)

    class Handler(BaseHTTPRequestHandler):
        server_version = "slums-login"
        sys_version = ""

        def log_message(self, fmt, *args):  # no client addresses or cookies in the log
            sys.stderr.write("%s %s\n" % (self.command, urlsplit(self.path).path))

        def client(self):
            return client_ip(self.headers.get("X-Forwarded-For"), self.client_address[0], hops)

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
            if path == "/api/auth/admin":
                s = self.session()
                return self.send(204 if sessions.admin(s) else 401 if not s or sessions.stale_admin(s) else 403)
            if path == "/api/auth/pass":
                a = passes.check(self.headers.get("X-Slums-Pass")) if passes else None
                return self.send(401) if a is None else self.send(204, headers={"X-Slums-Account": str(a)})
            if path == "/api/login/me":
                s = self.session()
                return self.send(200, {"signed_in": bool(s), "character": (s.get("c") or None) if s else None,
                                       "account": s.get("n") if s else None, "admin": sessions.admin(s),
                                       "characters": (s.get("cs") or []) if s else []})
            if path == "/api/login/health":
                return self.send(200, {"ok": True})
            m = INSPECT_PATH.match(path)
            if m:
                return self.inspect(m.group(1))
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
            if path in ("/api/login/invites/list", "/api/login/invites/create", "/api/login/invites/cancel"):
                return self.invites(path.rsplit("/", 1)[1])
            if path in ("/api/login/reset/request", "/api/login/reset/status", "/api/login/reset/complete"):
                return self.reset(path.rsplit("/", 1)[1])
            if path == "/api/login/exchange-approve":
                return self.exchange_approve()
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
            return self.send(200, {"ok": True, "pass": pw, "expiresAt": exp})   # Unix seconds (the launcher's contract)

        def invites(self, action):
            body = self.json_body()
            if body is None:
                return self.send(403, {"ok": False, "error": "Refused."})
            s = self.session()
            if not s:
                return self.send(401, {"ok": False, "error": "Sign in first."})
            if not invite_limiter.allow(s["a"]):
                return self.send(*self.err(ERRORS["RATE_LIMITED"]))
            ask = {"account_id": s["a"]}
            if action == "list" and isinstance(body.get("page"), int) and 1 <= body["page"] <= 1000:
                ask["page"] = body["page"]
            if action == "cancel":
                code = clean_code(body.get("code"))
                if not 4 <= len(code) <= 32:
                    return self.send(400, {"ok": False, "error": "That isn't an invite code."})
                ask["code"] = code
            if action == "create" and sessions.admin(s):  # staff may set how long it lasts and a note
                life = body.get("life")
                if life is not None:  # a bad life is refused, never quietly replaced by the default
                    if not (life == "forever" or (isinstance(life, int) and not isinstance(life, bool) and 1 <= life <= 90)):
                        return self.send(400, {"ok": False, "error": INVITE_ERRORS["BAD_INPUT"][1], "code": "BAD_INPUT"})
                    ask["life"] = life
                if isinstance(body.get("note"), str) and body["note"].strip():
                    ask["note"] = body["note"].strip()[:64]
            got = auth.call("/invite/" + action, ask, self.client()) if auth else None
            if not got:
                return self.send(*self.err(INVITES_DOWN))
            if not got.get("ok"):
                status, msg = INVITE_ERRORS.get(got.get("error"), INVITES_DOWN)
                return self.send(status, {"ok": False, "error": msg, "code": got.get("error")})
            if action != "list":
                sys.stderr.write(f"invite {action} by account {s['a']}\n")  # never the code
            return self.send(200, dict({k: v for k, v in got.items() if k != "ok"}, ok=True))

        def exchange_approve(self):
            """1.6.2 b005: the password for one previewed exchange order (every site sell, big site buys). The game
            checks it like a sign-in (its rate limit and lockout) against the session's account and approves that
            preview token; the confirm then goes through the market app as usual. The password is passed on and
            dropped: never logged, kept or written anywhere."""
            body = self.json_body()
            if body is None:
                return self.send(403, {"ok": False, "error": "Refused."})
            s = self.session()
            if not s:
                return self.send(401, {"ok": False, "error": "Sign in first."})
            token, pw = body.get("token"), body.get("pass")
            if not isinstance(token, str) or not TOKEN_RE.match(token):
                return self.send(400, {"ok": False, "error": APPROVE_ERRORS["BAD_INPUT"][1], "code": "BAD_INPUT"})
            if not isinstance(pw, str) or not 1 <= len(pw) <= 64:
                return self.send(400, {"ok": False, "error": "Type your password.", "code": "BAD_INPUT"})
            if not limiter.allow(self.client()):   # a password try: counted with the sign-ins from this address
                return self.send(429, {"ok": False, "error": ERRORS["RATE_LIMITED"][1], "code": "RATE_LIMITED"})
            got = auth.call("/exchange/approve", {"account_id": s["a"], "user": s.get("n", ""), "pass": pw, "token": token},
                            self.client()) if auth else None
            pw = body["pass"] = None
            if not got:
                return self.send(*self.err(APPROVE_ERRORS["UNAVAILABLE"]))
            if not got.get("ok"):
                code = got.get("error") if got.get("error") in APPROVE_ERRORS else "UNAVAILABLE"
                status, msg = APPROVE_ERRORS[code]
                sys.stderr.write(f"exchange approve for account {s['a']}: {code}\n")  # never the password or token
                return self.send(status, {"ok": False, "error": msg, "code": code})
            sys.stderr.write(f"exchange approve for account {s['a']}: ok\n")
            return self.send(200, {"ok": True, "expiresAt": got.get("expires_at")})

        def inspect(self, what):
            """1.6.2 ticket 157: the staff Bots page's reads, passed on to the game's inspect endpoint (read only)."""
            if self.headers.get("X-Slums") != "1":
                return self.send(403, {"ok": False, "error": "Refused."})
            s = self.session()
            if not sessions.admin(s):
                again = not s or sessions.stale_admin(s)
                return self.send(401 if again else 403, {"ok": False, "code": "SIGN_IN" if again else "STAFF",
                                                         "error": "Sign in again." if again else "Staff only."})
            if not inspect_limiter.allow(s["a"]):
                return self.send(429, {"ok": False, "error": "Too many lookups. Wait a minute.", "code": "RATE_LIMITED"})
            got = inspect.get(what) if inspect else None
            if got and got[0] == 200 and got[1].get("ok"):
                return self.send(200, got[1])
            if got and got[0] == 404:
                return self.send(404, {"ok": False, "code": "NOT_FOUND",
                                       "error": "Not found: that bot isn't online, or nobody is on that map."})
            if got and got[0] == 403:
                sys.stderr.write("inspect: the game refused the secret\n")
            return self.send(503, {"ok": False, "code": "UNAVAILABLE", "error": "The game's bot inspect isn't on or can't be reached."})

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
    ap.add_argument("--inspect-url")
    ap.add_argument("--inspect-secret")
    ap.add_argument("--proxy-hops", type=int)
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
    inspect = Inspect(setting(args, "inspect_url", "SLUMS_LOGIN_INSPECT_URL", ""),
                      setting(args, "inspect_secret", "SLUMS_LOGIN_INSPECT_SECRET_FILE", "/etc/slums-inspect/secret"))
    server = ThreadingHTTPServer(("127.0.0.1", port), make_handler(sessions, secure, auth, passes=passes, inspect=inspect,
                                                                   hops=int(setting(args, "proxy_hops", "SLUMS_LOGIN_PROXY_HOPS", 1))))
    print(f"login service on 127.0.0.1:{port}, game auth API {auth.url or '(not set: sign-in answers 503)'}, "
          f"download passes {'on' if passes else 'off (no pass key)'}, bot inspect "
          f"{inspect.url or ('off (not a loopback address)' if inspect.refused else 'off')}", file=sys.stderr)
    server.serve_forever()


if __name__ == "__main__":
    main()
