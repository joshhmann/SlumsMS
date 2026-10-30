#!/usr/bin/env python3
"""The key console (owner, 2026-10-30): a page on 164, for the home network only, that shows the server's keys and
changes the TEST ones. Python 3 standard library only. See tools/keys/README.md.

    slums-keys passphrase                         the owner sets the passphrase (typed, never shown or stored as is)
    slums-keys serve [--host 192.168.0.164] [--port 8791]

- Anyone on the home network can look: names, ages, permissions and backups, never a key.
- Every change needs the owner's passphrase AND the key's id typed in. 5 wrong passphrases lock changes for 15 min.
- The page passes only an id to slums-keys-remote (on the same host; SLUMS_KEYS_HOST=user@host runs it over ssh
  instead). That script holds the same list (registry.py) and does only what an entry allows. No key ever passes
  through the page. It listens on the home-network address only, never behind the public website's nginx.
"""
import argparse
import getpass
import hashlib
import hmac
import html
import ipaddress
import json
import os
import re
import secrets
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

sys.path.insert(0, os.path.dirname(os.path.realpath(__file__)))  # the real folder, also through /usr/local/sbin
import registry  # noqa: E402

PASS_FILE = os.environ.get("SLUMS_KEYS_PASS_FILE", "/root/.config/slums-keys/owner")
HOST164 = os.environ.get("SLUMS_KEYS_HOST", "local")
REMOTE = os.environ.get("SLUMS_KEYS_REMOTE", "/usr/local/sbin/slums-keys-remote")
LAN = [ipaddress.ip_network("127.0.0.0/8"), ipaddress.ip_network("192.168.0.0/24")]
TRIES, LOCK_S = 5, 15 * 60
SCRYPT = dict(n=2 ** 15, r=8, p=1, maxmem=64 * 1024 * 1024)


def hash_pass(pw, salt=None):
    salt = salt or secrets.token_bytes(16)
    return "scrypt$%s$%s" % (salt.hex(), hashlib.scrypt(pw.encode(), salt=salt, dklen=32, **SCRYPT).hex())


def pass_ok(pw, stored):
    try:
        kind, salt, want = stored.strip().split("$")
        return kind == "scrypt" and hmac.compare_digest(hash_pass(pw, bytes.fromhex(salt)), stored.strip())
    except (ValueError, AttributeError):
        return False


def read_pass_file(path=None):
    try:
        with open(path or PASS_FILE, encoding="utf-8") as f:
            return f.read().strip() or None
    except OSError:
        return None


def set_passphrase():
    pw = getpass.getpass("New key-console passphrase (12+ characters): ")
    if len(pw) < 12:
        raise SystemExit("Too short: use 12 characters or more.")
    if getpass.getpass("Again: ") != pw:
        raise SystemExit("They don't match.")
    os.makedirs(os.path.dirname(PASS_FILE), mode=0o700, exist_ok=True)
    old = os.umask(0o077)
    try:
        with open(PASS_FILE, "w", encoding="utf-8") as f:
            f.write(hash_pass(pw) + "\n")
    finally:
        os.umask(old)
    print("Saved. Only a scrambled form of it is kept, in " + PASS_FILE)


def remote(*args, run=subprocess.run):
    """Ask slums-keys-remote on 164. args are fixed words and a checked id, never anything else."""
    cmd = [REMOTE, *args] if HOST164 == "local" else ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=8", HOST164, REMOTE, *args]
    r = run(cmd, capture_output=True, text=True, timeout=90)
    try:
        return json.loads((r.stdout or "").strip().splitlines()[-1])
    except (ValueError, IndexError):
        return {"ok": False, "error": "164 didn't answer properly" + (f" (exit {r.returncode})" if r.returncode else "")}


class Gate:
    """Wrong passphrases: TRIES in a row lock changes for LOCK_S (all clients: it's one owner)."""

    def __init__(self, now=time.time):
        self.now, self.lock, self.fails, self.until = now, threading.Lock(), 0, 0

    def check(self, pw, stored):
        with self.lock:
            if self.now() < self.until:
                return "locked"
            if stored and pass_ok(pw, stored):
                self.fails = 0
                return "ok"
            self.fails += 1
            if self.fails >= TRIES:
                self.fails, self.until = 0, self.now() + LOCK_S
            return "wrong"


CSS = """
:root { --bg:#f5f3ee; --ink:#1d1f1a; --muted:#5d6357; --line:#d9d6cc; --card:#fff; --accent:#2f8f3a; --bad:#b3261e; --warn:#8a5a00; }
@media (prefers-color-scheme: dark) { :root { --bg:#171915; --ink:#e9ebe4; --muted:#a3a99c; --line:#33372f; --card:#20231d; --accent:#5cc768; --bad:#ff8a80; --warn:#f0b74a; color-scheme: dark; } }
body { margin:0; background:var(--bg); color:var(--ink); font:15px/1.5 system-ui, sans-serif; }
main { max-width:1000px; margin:0 auto; padding:24px max(16px, env(safe-area-inset-right)) 48px max(16px, env(safe-area-inset-left)); }
h1 { margin:0 0 4px; font-size:1.6rem; } h2 { margin:28px 0 8px; font-size:1.1rem; }
.lead { color:var(--muted); margin:0 0 16px; }
.key { background:var(--card); border:1px solid var(--line); border-radius:10px; padding:12px 14px; margin:0 0 10px; }
.key h3 { margin:0; font-size:1rem; display:flex; flex-wrap:wrap; gap:4px 10px; align-items:baseline; }
.key code { font-size:.85rem; color:var(--muted); overflow-wrap:anywhere; }
.key p { margin:6px 0 0; } .muted { color:var(--muted); } .bad { color:var(--bad); } .warn { color:var(--warn); }
.ok { color:var(--accent); }
form.act { display:flex; flex-wrap:wrap; gap:8px; align-items:end; margin-top:10px; }
form.act label { display:grid; gap:2px; font-size:.8rem; color:var(--muted); }
input { font:inherit; padding:6px 8px; border:1px solid var(--line); border-radius:6px; background:var(--bg); color:var(--ink); }
button { font:inherit; padding:6px 12px; border-radius:6px; border:1px solid var(--ink); background:var(--card); color:var(--ink); cursor:pointer; }
button.go { background:var(--accent); border-color:var(--accent); color:#fff; }
.msg { padding:10px 14px; border-radius:8px; border:1px solid var(--line); background:var(--card); margin:0 0 16px; }
"""


def age(ts):
    if not ts:
        return ""
    d = (time.time() - ts) / 86400
    return "changed today" if d < 1 else f"changed {int(d)} day{'s' if int(d) != 1 else ''} ago"


def page(status, msg=None, passphrase_set=True):
    e = html.escape
    facts = {k["id"]: k for k in (status.get("keys") or [])} if status.get("ok") else {}
    rows = {"test": [], "live": []}
    for k in registry.KEYS:
        f = facts.get(k["id"], {})
        state = ("<span class=muted>not looked at</span>" if f.get("seen") is False else
                 "<span class=bad>missing</span>" if f.get("exists") is False else
                 " · ".join(x for x in [
                     age(f.get("changed")),
                     "" if f.get("modeOk", True) and f.get("ownerOk", True) else "<span class=warn>permissions differ</span>",
                     f"{f['backups']} backup{'s' if f['backups'] != 1 else ''}" if f.get("backups") else "",
                     "<span class=bad>empty</span>" if f.get("nonEmpty") is False else ""] if x) if f else
                 "<span class=muted>unknown</span>")
        last = f"<p class=muted>Last: {e(f['last'])}</p>" if f.get("last") else ""
        if registry.changeable(k) and passphrase_set:
            act = (f'<form class=act method=post action="/change"><input type=hidden name=id value="{e(k["id"])}">'
                   f'<label>Type <b>{e(k["id"])}</b><input name=confirm autocomplete=off required size=18></label>'
                   f'<label>Passphrase<input name=pass type=password autocomplete=current-password required size=18></label>'
                   f'<button class=go name=do value=rotate>Make a new key</button>'
                   f'<button name=do value=restore>Put the previous one back</button></form>')
        elif registry.changeable(k):
            act = "<p class=warn>Set the passphrase first (see the top of the page).</p>"
        else:
            act = f"<p class=muted>{e(k['locked'] or 'Not changed here.')}</p>"
        rows[k["env"]].append(
            f'<div class=key><h3>{e(k["title"])} <code>{e(k["id"])}</code></h3><p><code>{e(k["path"])}</code></p>'
            f'<p>{state}</p><p>If it changes: {e(k["breaks"])}</p>{last}{act}</div>')
    note = (f'<p class="msg {"ok" if msg[0] else "bad"}">{e(msg[1])}</p>' if msg else "")
    if not status.get("ok"):
        note += f'<p class="msg bad">Couldn\'t read 164: {e(status.get("error") or "no answer")}</p>'
    if not passphrase_set:
        note += ('<p class="msg warn">No passphrase yet. Set it yourself: <code>ssh -t root@192.168.0.164 slums-keys passphrase</code> '
                 '(in Claude Code: <code>! ssh -t root@192.168.0.164 slums-keys passphrase</code>).</p>')
    return f"""<!doctype html><html lang=en><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<meta name=robots content="noindex,nofollow"><title>Key console</title><style>{CSS}</style></head><body><main>
<h1>Key console</h1><p class=lead>The server's keys: where they are, how old, what a change breaks. Keys themselves are
never shown here. Changes need your passphrase and the key's name typed in, and are logged on 164 (without the key).</p>
{note}<h2>TEST</h2>{''.join(rows['test'])}<h2>Live</h2>{''.join(rows['live'])}</main></body></html>"""


def make_handler(ask=remote, gate=None, pass_file=None):
    gate = gate or Gate()

    class H(BaseHTTPRequestHandler):
        server_version = "slums-keys"
        sys_version = ""

        def log_message(self, fmt, *args):  # the method and path only
            sys.stderr.write("%s %s\n" % (self.command, urlsplit(self.path).path))

        def allowed(self):
            try:
                ip = ipaddress.ip_address(self.client_address[0])
            except ValueError:
                return False
            return any(ip in n for n in LAN)

        def send(self, status, body, ctype="text/html; charset=utf-8", headers=None):
            data = body.encode()
            self.send_response(status)
            for k, v in dict({"Content-Type": ctype, "Cache-Control": "no-store", "X-Frame-Options": "DENY",
                              "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'",
                              "Referrer-Policy": "no-referrer", "Content-Length": str(len(data))}, **(headers or {})).items():
                self.send_header(k, v)
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            if not self.allowed():
                return self.send(403, "home network only", "text/plain")
            if urlsplit(self.path).path != "/":
                return self.send(404, "not here", "text/plain")
            self.send(200, page(ask("status"), passphrase_set=bool(read_pass_file(pass_file))))

        def do_POST(self):
            if not self.allowed():
                return self.send(403, "home network only", "text/plain")
            if urlsplit(self.path).path != "/change":
                return self.send(404, "not here", "text/plain")
            origin = self.headers.get("Origin")
            if origin and urlsplit(origin).netloc != self.headers.get("Host", ""):
                return self.send(403, "refused", "text/plain")
            n = int(self.headers.get("Content-Length") or 0)
            form = parse_qs(self.rfile.read(n).decode("utf-8", "replace")) if 0 < n <= 2048 else {}
            one = lambda k: (form.get(k) or [""])[0]
            key_id, action, typed, pw = one("id"), one("do"), one("confirm").strip(), one("pass")
            key = registry.BY_ID.get(key_id) if re.match(registry.ID_RE, key_id) else None
            if action not in ("rotate", "restore") or not registry.changeable(key):
                msg = (False, "That key can't be changed here.")
            elif typed != key_id:
                msg = (False, f"Type the key's name exactly ({key_id}) to confirm.")
            else:
                verdict = gate.check(pw, read_pass_file(pass_file))
                if verdict == "locked":
                    msg = (False, "Too many wrong passphrases: changes are locked for 15 minutes.")
                elif verdict == "wrong":
                    msg = (False, "Wrong passphrase. Nothing was changed.")
                else:
                    got = ask(action, key_id)
                    msg = (bool(got.get("ok")), (f"{key_id}: " + (got.get("message") or got.get("error") or "no answer")))
            pw = None
            self.send(200, page(ask("status"), msg, bool(read_pass_file(pass_file))))

    return H


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("cmd", choices=["passphrase", "serve"])
    ap.add_argument("--host", default="192.168.0.164")
    ap.add_argument("--port", type=int, default=8791)
    a = ap.parse_args()
    if a.cmd == "passphrase":
        return set_passphrase()
    if not any(ipaddress.ip_address(a.host) in n for n in LAN):
        raise SystemExit("--host must be a home-network or loopback address")
    srv = ThreadingHTTPServer((a.host, a.port), make_handler())
    print(f"key console on http://{a.host}:{a.port}/", file=sys.stderr)
    srv.serve_forever()


if __name__ == "__main__":
    main()
