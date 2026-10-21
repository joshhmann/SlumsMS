#!/usr/bin/env python3
"""A Discord-compatible webhook receiver for testing slumsms-monitor without a real Discord channel.

    mock-webhook.py --port 8599 --out /tmp/mock-webhook.jsonl

Accepts POST /api/webhooks/<id>/<token>[?wait=true] (returns a message object with an id) and
PATCH /api/webhooks/<id>/<token>/messages/<message_id> (edits; 404 for unknown ids), like Discord does.
Every request is appended to --out as one JSON line: {"at", "method", "path", "message_id", "content", "username",
"avatar_url"}.
Point a target at it with WEBHOOK_URL=http://127.0.0.1:8599/api/webhooks/1/test in its .env file.
GET / lists the stored messages.
"""
import argparse
import itertools
import json
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOCK = threading.Lock()
IDS = itertools.count(int(time.time()) * 1000)
MESSAGES = {}


def make_handler(out_path):
    class Handler(BaseHTTPRequestHandler):
        def _reply(self, code, obj=None):
            body = json.dumps(obj).encode() if obj is not None else b""
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _record(self, method, mid, content, body=None):
            with LOCK, open(out_path, "a") as f:
                f.write(json.dumps({"at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "method": method,
                                    "path": re.sub(r"/[^/]+(/messages|$)", r"/<token>\1", self.path.split("?")[0], 1),
                                    "message_id": mid, "content": content,
                                    "username": (body or {}).get("username"),
                                    "avatar_url": (body or {}).get("avatar_url")}) + "\n")

        def _body(self):
            length = int(self.headers.get("Content-Length") or 0)
            try:
                return json.loads(self.rfile.read(length) or b"{}")
            except ValueError:
                return None

        def do_POST(self):
            if not re.fullmatch(r"/api/webhooks/[^/]+/[^/?]+", self.path.split("?")[0]):
                return self._reply(404, {"message": "Unknown Webhook", "code": 10015})
            body = self._body()
            if not body or not (body.get("content") or body.get("embeds")):
                return self._reply(400, {"message": "Cannot send an empty message", "code": 50006})
            mid = str(next(IDS))
            MESSAGES[mid] = body.get("content")
            self._record("POST", mid, body.get("content"), body)
            if "wait=true" in self.path:
                return self._reply(200, {"id": mid, "content": body.get("content")})
            return self._reply(204)

        def do_PATCH(self):
            m = re.fullmatch(r"/api/webhooks/[^/]+/[^/]+/messages/(\d+)", self.path.split("?")[0])
            if not m or m.group(1) not in MESSAGES:
                return self._reply(404, {"message": "Unknown Message", "code": 10008})
            body = self._body() or {}
            MESSAGES[m.group(1)] = body.get("content")
            self._record("PATCH", m.group(1), body.get("content"))
            return self._reply(200, {"id": m.group(1), "content": body.get("content")})

        def do_GET(self):
            return self._reply(200, MESSAGES)

        def log_message(self, *args):
            pass

    return Handler


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=8599)
    p.add_argument("--out", default="mock-webhook.jsonl")
    a = p.parse_args()
    ThreadingHTTPServer((a.host, a.port), make_handler(a.out)).serve_forever()


if __name__ == "__main__":
    main()
