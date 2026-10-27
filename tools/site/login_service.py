#!/usr/bin/env python3
"""Website login with a SlumsMS game account, by a code typed in game. Python 3 standard library only.

    python3 tools/site/login_service.py [--key-file PATH] [--approvals DIR] [--port 8094]

No game password ever reaches the website, and this service never reads the game database:
1. The login page calls POST /api/login/start. The service makes a short code (e.g. K7Q4ZP, 10 minutes) and sets a
   private "pending" cookie that only this browser has.
2. The player types `@weblogin K7Q4ZP` in game. The game server writes an approval file to --approvals
   (<CODE>.json: {"code", "account_id", "account", "character", "gm", "time"}, optionally "characters": the
   account's characters on Bera, for the Buy/Sell board in market_service.py).
3. The page polls GET /api/login/poll. When the approval for its code is there, the service deletes the file and sets
   a signed session cookie (30 days). The page then goes back to where the player was headed.
4. nginx asks GET /api/auth/check (auth_request) before serving the friends-only sections: 204 when the session is
   good, 401 otherwise. POST /api/login/logout ends the session (on this server too, not just in the browser);
   GET /api/login/me says who is signed in.

Ticket 105 adds two more ways in, for the site and the launcher, giving the same session:
- POST /api/login/password {"user", "pass"}: the game username and password.
- POST /api/login/signup {"code", "user", "pass"}: a new account with an invite code from `!invite`.
Both need a JSON body and the header "X-Slums: 1", and a browser Origin must match the Host. This service never
checks or stores a password itself: it passes it on to the game server's auth API on localhost (--auth-url, POST
/verify and /signup, header X-Slums-Auth from --auth-secret), with a salted hash of the visitor's address (--ip-salt)
and never the address itself. Passwords are never logged. Answers: 200 {"ok": true, "account", "character"}, or
{"ok": false, "error": "<message to show>"} with 400 (a rule), 401 (wrong login), 403 (bad code), 409 (name taken),
423 (locked), 429 (too many tries) or 503 (auth API unreachable or not set up).

Listens on 127.0.0.1 only. Sessions are HMAC-SHA256 signed with --key-file (32+ random bytes; the service refuses to
start without it). An account listed in --revoked (one account id per line, e.g. banned accounts) is signed out.

Settings (argument beats environment beats default):
    --key-file / SLUMS_LOGIN_KEY_FILE     /etc/slums-login/key   (required)
    --approvals / SLUMS_LOGIN_APPROVALS   /var/lib/slums-login/approvals   (the game server writes here)
    --revoked / SLUMS_LOGIN_REVOKED       /var/lib/slums-login/revoked.txt  (optional)
    --port / SLUMS_LOGIN_PORT             8094
    --secure / SLUMS_LOGIN_SECURE         1: cookies get the Secure flag (the site is served over HTTPS)
    --auth-url / SLUMS_LOGIN_AUTH_URL     the game server's auth API, e.g. http://127.0.0.1:8186 (empty: password
                                          sign-in and sign-up answer 503)
    --auth-secret / SLUMS_LOGIN_AUTH_SECRET_FILE   /etc/slums-auth/secret (read by path, never printed)
    --ip-salt / SLUMS_LOGIN_IP_SALT_FILE  /etc/slums-login/ip-salt (32+ random bytes)
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

CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  # no 0/O or 1/I, so it reads cleanly in game
CODE_LEN = 6
CODE_TTL = 10 * 60
SESSION_TTL = 30 * 86400
MAX_PENDING = 500
START_PER_IP_MIN = 6
AUTH_PER_IP_MIN = 10  # password sign-ins and sign-ups per address a minute (the game server also locks accounts)
MAX_BODY = 1024
PENDING_COOKIE, SESSION_COOKIE = "slums_pending", "slums_session"
_CODE_RE = re.compile(rf"^[{CODE_ALPHABET}]{{{CODE_LEN}}}$")


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
        self.ended_mem, self.lock = {}, threading.Lock()

    def sign(self, payload):
        body = b64(json.dumps(payload, separators=(",", ":"), sort_keys=True).encode())
        mac = b64(hmac.new(self.key, body.encode(), hashlib.sha256).digest())
        return f"{body}.{mac}"

    def issue(self, approval):
        t = int(self.now())
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
        return payload

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


class Pending:
    """Codes waiting for `@weblogin`: code -> (pending secret, expiry). In memory: a restart just means a new code."""

    def __init__(self, now=time.time):
        self.now, self.lock, self.by_secret, self.by_code = now, threading.Lock(), {}, {}
        self.starts = {}

    def _sweep(self):
        t = self.now()
        for code, (secret, exp) in list(self.by_code.items()):
            if exp < t:
                del self.by_code[code]
                self.by_secret.pop(secret, None)

    def start(self, client):
        with self.lock:
            self._sweep()
            t = self.now()
            recent = [s for s in self.starts.get(client, []) if s > t - 60]
            if len(recent) >= START_PER_IP_MIN:
                return None
            self.starts[client] = recent + [t]
            if len(self.by_code) >= MAX_PENDING:
                return None
            while True:
                code = "".join(secrets.choice(CODE_ALPHABET) for _ in range(CODE_LEN))
                if code not in self.by_code:
                    break
            secret = secrets.token_urlsafe(32)
            self.by_code[code] = (secret, t + CODE_TTL)
            self.by_secret[secret] = code
            return code, secret

    def code_for(self, secret):
        with self.lock:
            self._sweep()
            return self.by_secret.get(secret)

    def finish(self, secret):
        with self.lock:
            code = self.by_secret.pop(secret, None)
            if code:
                self.by_code.pop(code, None)


def take_approval(approvals_dir, code, now=time.time):
    """The game server's approval for this code, or None. The file is removed so a code works once."""
    if not _CODE_RE.match(code or ""):
        return None
    path = os.path.join(approvals_dir, f"{code}.json")
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        os.remove(path)
    except (OSError, json.JSONDecodeError):
        return None
    if not isinstance(data, dict) or data.get("code") != code or not str(data.get("account_id", "")).isdigit():
        return None
    try:  # the game server always stamps the approval; an old or unstamped one is refused
        if now() - float(data["time"]) > CODE_TTL:
            return None
    except (KeyError, TypeError, ValueError):
        return None
    return data


USER_RE = re.compile(r"^[A-Za-z0-9]+$")
PASS_RE = re.compile(r"^[\x21-\x7e]+$")  # printable, no spaces
RULES = {"user_length": "Usernames are 4 to 12 letters or numbers.",
         "user_chars": "Usernames are 4 to 12 letters or numbers.",
         "pass_length": "Passwords are 6 to 12 characters (the game's login box takes 12).",
         "pass_chars": "Passwords can't have spaces or special symbols.",
         "bot_name": "That username is taken."}
ERRORS = {"BAD_CODE": (403, "That invite code doesn't work."),
          "NAME_TAKEN": (409, "That username is taken."),
          "WRONG_LOGIN": (401, "Wrong username or password."),
          "LOCKED": (423, "Too many wrong tries. Try again in 15 minutes."),
          "RATE_LIMITED": (429, "Too many tries. Wait a minute and try again.")}
DOWN = (503, "Sign-in is down right now. Try again in a bit.")


def rule_broken(user, pw):
    """The username and password rules (ticket 105), checked before anything is sent; the game server checks too."""
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


def make_handler(sessions, pending, approvals_dir, secure, auth=None, limiter=None):
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

        def send(self, status, body=None, set_cookies=()):
            data = b"" if body is None else json.dumps(body).encode()
            self.send_response(status)
            self.send_header("Cache-Control", "no-store")
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
            if path == "/api/login/me":
                s = self.session()
                return self.send(200, {"signed_in": bool(s), "character": (s.get("c") or None) if s else None,
                                       "account": s.get("n") if s else None})
            if path == "/api/login/poll":
                secret = cookies(self.headers.get("Cookie")).get(PENDING_COOKIE)
                code = pending.code_for(secret) if secret else None
                if not code:
                    return self.send(410, {"ok": False, "error": "That code ran out. Get a new one."})
                approval = take_approval(approvals_dir, code)
                if not approval:
                    return self.send(200, {"ok": False, "waiting": True})
                pending.finish(secret)
                token = sessions.issue(approval)
                return self.send(200, {"ok": True, "character": approval.get("character", "")},
                                 [f"{SESSION_COOKIE}={token}; Max-Age={SESSION_TTL}{flags}",
                                  f"{PENDING_COOKIE}=; Max-Age=0{flags}"])
            if path == "/api/login/health":
                return self.send(200, {"ok": True})
            return self.send(404, {"ok": False})

        def do_POST(self):
            path = urlsplit(self.path).path
            if path == "/api/login/start":
                got = pending.start(self.client())
                if not got:
                    return self.send(429, {"ok": False, "error": "Too many tries. Wait a minute and try again."})
                code, secret = got
                return self.send(200, {"ok": True, "code": code, "expires_in": CODE_TTL},
                                 [f"{PENDING_COOKIE}={secret}; Max-Age={CODE_TTL}{flags}"])
            if path == "/api/login/logout":
                s = self.session()
                if s:
                    sessions.end(s)
                return self.send(200, {"ok": True}, [f"{SESSION_COOKIE}=; Max-Age=0{flags}"])
            if path in ("/api/login/password", "/api/login/signup"):
                return self.account_sign_in(path.rsplit("/", 1)[1])
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
            broken = rule_broken(user, pw)
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
            chars = [c for c in got.get("characters") or [] if isinstance(c, str) and c]
            token = sessions.issue({"account_id": got["account_id"], "account": got.get("account") or user,
                                    "character": chars[0] if chars else "", "characters": chars, "gm": got.get("gm", 0)})
            return self.send(200, {"ok": True, "account": got.get("account") or user, "character": chars[0] if chars else None},
                             [f"{SESSION_COOKIE}={token}; Max-Age={SESSION_TTL}{flags}"])

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
    ap.add_argument("--approvals")
    ap.add_argument("--revoked")
    ap.add_argument("--port", type=int)
    ap.add_argument("--secure")
    ap.add_argument("--auth-url")
    ap.add_argument("--auth-secret")
    ap.add_argument("--ip-salt")
    args = ap.parse_args()
    key_file = setting(args, "key_file", "SLUMS_LOGIN_KEY_FILE", "/etc/slums-login/key")
    approvals = setting(args, "approvals", "SLUMS_LOGIN_APPROVALS", "/var/lib/slums-login/approvals")
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
    os.makedirs(approvals, exist_ok=True)
    server = ThreadingHTTPServer(("127.0.0.1", port),
                                 make_handler(Sessions(key, revoked), Pending(), approvals, secure, auth))
    print(f"login service on 127.0.0.1:{port}, approvals in {approvals}", file=sys.stderr)
    server.serve_forever()


if __name__ == "__main__":
    main()
