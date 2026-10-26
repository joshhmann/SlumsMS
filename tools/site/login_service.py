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
   good, 401 otherwise. POST /api/login/logout ends the session; GET /api/login/me says who is signed in.

Listens on 127.0.0.1 only. Sessions are HMAC-SHA256 signed with --key-file (32+ random bytes; the service refuses to
start without it). An account listed in --revoked (one account id per line, e.g. banned accounts) is signed out.

Settings (argument beats environment beats default):
    --key-file / SLUMS_LOGIN_KEY_FILE     /etc/slums-login/key   (required)
    --approvals / SLUMS_LOGIN_APPROVALS   /var/lib/slums-login/approvals   (the game server writes here)
    --revoked / SLUMS_LOGIN_REVOKED       /var/lib/slums-login/revoked.txt  (optional)
    --port / SLUMS_LOGIN_PORT             8094
    --secure / SLUMS_LOGIN_SECURE         1: cookies get the Secure flag (the site is served over HTTPS)
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
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  # no 0/O or 1/I, so it reads cleanly in game
CODE_LEN = 6
CODE_TTL = 10 * 60
SESSION_TTL = 30 * 86400
MAX_PENDING = 500
START_PER_IP_MIN = 6
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

    def sign(self, payload):
        body = b64(json.dumps(payload, separators=(",", ":"), sort_keys=True).encode())
        mac = b64(hmac.new(self.key, body.encode(), hashlib.sha256).digest())
        return f"{body}.{mac}"

    def issue(self, approval):
        t = int(self.now())
        payload = {"a": int(approval["account_id"]), "n": str(approval.get("account", ""))[:32],
                   "c": str(approval.get("character", ""))[:32], "iat": t, "exp": t + SESSION_TTL}
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
        return payload


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


def cookies(header):
    out = {}
    for part in (header or "").split(";"):
        if "=" in part:
            k, v = part.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def make_handler(sessions, pending, approvals_dir, secure):
    flags = "; Path=/; HttpOnly; SameSite=Lax" + ("; Secure" if secure else "")

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
                return self.send(200, {"signed_in": bool(s), "character": s and s.get("c")})
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
                return self.send(200, {"ok": True}, [f"{SESSION_COOKIE}=; Max-Age=0{flags}"])
            return self.send(404, {"ok": False})

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
    args = ap.parse_args()
    key_file = setting(args, "key_file", "SLUMS_LOGIN_KEY_FILE", "/etc/slums-login/key")
    approvals = setting(args, "approvals", "SLUMS_LOGIN_APPROVALS", "/var/lib/slums-login/approvals")
    revoked = setting(args, "revoked", "SLUMS_LOGIN_REVOKED", "/var/lib/slums-login/revoked.txt")
    port = int(setting(args, "port", "SLUMS_LOGIN_PORT", 8094))
    secure = str(setting(args, "secure", "SLUMS_LOGIN_SECURE", "1")) == "1"
    try:
        with open(key_file, "rb") as f:
            key = f.read().strip()
    except OSError:
        raise SystemExit(f"no key file at {key_file}; make one with: head -c 48 /dev/urandom > {key_file}")
    if len(key) < 32:
        raise SystemExit(f"{key_file} is too short; use at least 32 random bytes")
    os.makedirs(approvals, exist_ok=True)
    server = ThreadingHTTPServer(("127.0.0.1", port),
                                 make_handler(Sessions(key, revoked), Pending(), approvals, secure))
    print(f"login service on 127.0.0.1:{port}, approvals in {approvals}", file=sys.stderr)
    server.serve_forever()


if __name__ == "__main__":
    main()
