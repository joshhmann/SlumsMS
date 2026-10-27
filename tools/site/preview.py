#!/usr/bin/env python3
"""Preview the whole site (private build) on the local network, with a pretend sign-in. Standard library only.

    python3 tools/site/build.py --private --world --out dist/preview --no-tar
    python3 tools/site/preview.py [--dir dist/preview] [--host 192.168.0.187] [--port 8780]

For looking at the site before launch, never for players. Nothing here talks to a game server:
- The login page works: "Get a code" gives a code, and about 5 seconds later the browser counts as signed in as
  "Previewer", as if `@weblogin` had been typed. Sign out works too.
- Password sign-in takes any username with the password "preview1"; sign-up takes the invite code "PREVIEW"
  (any other code is refused, like a used one). The username and password rules are the real ones.
- /database/, /market/ and /secret/ need that pretend sign-in, like nginx does on 164 (auth_request).
- The market API (/api/market/) answers 503, so the market pages show their read-only view.
World and market data are whatever the build used (the samples unless --site-data / --market-data were given).
"""
import argparse
import functools
import sys
import json
import os
import secrets
import time
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import quote, urlsplit

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import login_service as ls  # noqa: E402  (the real rules and messages)
SESSION, PENDING = "slums_session", "slums_pending"
GATED = ("/database/", "/market/", "/secret/")
WAIT = 5  # seconds before a pretend code counts as typed in game
pending = {}  # pending secret -> (code, started)


class Handler(SimpleHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def cookies(self):
        out = {}
        for part in (self.headers.get("Cookie") or "").split(";"):
            if "=" in part:
                k, v = part.split("=", 1)
                out[k.strip()] = v.strip()
        return out

    def signed_in(self):
        return self.cookies().get(SESSION) == "preview"

    def reply(self, status, body=None, set_cookies=(), headers=()):
        data = b"" if body is None else json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Cache-Control", "no-store")
        for c in set_cookies:
            self.send_header("Set-Cookie", c + "; Path=/; HttpOnly; SameSite=Lax")
        for k, v in headers:
            self.send_header(k, v)
        if body is not None:
            self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        path = urlsplit(self.path).path
        if path == "/api/login/me":
            return self.reply(200, {"signed_in": self.signed_in(), "character": "Previewer" if self.signed_in() else None})
        if path == "/api/login/poll":
            got = pending.get(self.cookies().get(PENDING, ""))
            if not got:
                return self.reply(410, {"ok": False, "error": "That code ran out. Get a new one."})
            if time.time() - got[1] < WAIT:
                return self.reply(200, {"ok": False, "waiting": True})
            pending.pop(self.cookies()[PENDING], None)
            return self.reply(200, {"ok": True, "character": "Previewer"}, [f"{SESSION}=preview; Max-Age=2592000",
                                                                            f"{PENDING}=; Max-Age=0"])
        if path.startswith("/api/"):
            return self.reply(503, {"ok": False, "error": "Not in the preview."})
        if path.startswith(GATED) and not self.signed_in():
            if path.startswith("/secret/"):
                return self.reply(401)
            return self.reply(302, headers=[("Location", "/login/?next=" + quote(path))])
        return super().do_GET()

    def do_POST(self):
        path = urlsplit(self.path).path
        if path == "/api/login/start":
            secret = secrets.token_urlsafe(16)
            code = "".join(secrets.choice("ABCDEFGHJKLMNPQRSTUVWXYZ23456789") for _ in range(6))
            pending[secret] = (code, time.time())
            return self.reply(200, {"ok": True, "code": code, "expires_in": 600}, [f"{PENDING}={secret}; Max-Age=600"])
        if path == "/api/login/logout":
            return self.reply(200, {"ok": True}, [f"{SESSION}=; Max-Age=0"])
        if path in ("/api/login/password", "/api/login/signup"):
            try:
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)) or b"{}")
            except ValueError:
                body = {}
            if self.headers.get("X-Slums") != "1" or not isinstance(body, dict):
                return self.reply(403, {"ok": False, "error": "Refused."})
            broken = ls.rule_broken(body.get("user"), body.get("pass"))
            if broken:
                return self.reply(400, {"ok": False, "error": ls.RULES[broken], "rule": broken})
            if path.endswith("signup") and ls.clean_code(body.get("code")) != "PREVIEW":
                return self.reply(403, {"ok": False, "error": ls.ERRORS["BAD_CODE"][1]})
            if path.endswith("password") and body["pass"] != "preview1":
                return self.reply(401, {"ok": False, "error": ls.ERRORS["WRONG_LOGIN"][1]})
            return self.reply(200, {"ok": True, "account": body["user"], "character": None},
                              [f"{SESSION}=preview; Max-Age=2592000"])
        return self.reply(503, {"ok": False, "error": "Not in the preview."})


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dir", default=os.path.join(HERE, "..", "..", "dist", "preview"))
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8780)
    args = ap.parse_args()
    d = os.path.abspath(args.dir)
    if not os.path.isfile(os.path.join(d, "index.html")):
        raise SystemExit(f"no site in {d}; build it first (see the top of this file)")
    server = ThreadingHTTPServer((args.host, args.port), functools.partial(Handler, directory=d))
    print(f"site preview of {d} on http://{args.host}:{args.port}/", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
