#!/usr/bin/env python3
"""Local stand-in for the GPU box during soaks (ticket 48): OpenAI-compatible chat completions and the Decide
endpoint, with realistic latency, so a soak server measures its own LLM queueing without loading 192.168.0.17.

  POST /v1/chat/completions  -> a short casual line after 1-3 s (uniform; --min/--max)
  POST /v1/systemone         -> {"answers":{"need":{"choice":"any","confidence":0.3}}} after 0.1-0.3 s
  GET  /health               -> ok

Every minute it appends a stats row to <statsfile>: requests, max in flight, latency served.
usage: llm-mock.py <port> <statsfile> [--min 1.0] [--max 3.0]      (binds 127.0.0.1 only)
"""
import argparse, json, random, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ap = argparse.ArgumentParser()
ap.add_argument("port", type=int); ap.add_argument("stats")
ap.add_argument("--min", type=float, default=1.0); ap.add_argument("--max", type=float, default=3.0)
a = ap.parse_args()
LINES = ["lol yeah", "im just training rn", "nice", "idk maybe kerning?", "sure why not", "haha same",
         "ya its pretty busy today", "gl with that", "brb gotta pot up", "oh cool", "not sure tbh", "true lol"]
lock = threading.Lock()
st = {"chat": 0, "decide": 0, "inflight": 0, "max_inflight": 0, "lat": 0.0}

class H(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def _send(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)

    def do_GET(self):
        self._send(200, {"ok": True})

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        self.rfile.read(n)
        chat = self.path.endswith("/chat/completions")
        with lock:
            st["inflight"] += 1; st["max_inflight"] = max(st["max_inflight"], st["inflight"])
        d = random.uniform(a.min, a.max) if chat else random.uniform(0.1, 0.3)
        time.sleep(d)
        with lock:
            st["inflight"] -= 1; st["chat" if chat else "decide"] += 1; st["lat"] += d
        if chat:
            self._send(200, {"id": "mock", "object": "chat.completion", "model": "mock",
                             "choices": [{"index": 0, "message": {"role": "assistant", "content": random.choice(LINES)},
                                          "finish_reason": "stop"}]})
        elif self.path.endswith("/systemone"):
            self._send(200, {"answers": {"need": {"choice": "any", "confidence": 0.3}}})
        else:
            self._send(404, {"error": "unknown path"})

def stats():
    with open(a.stats, "a") as f:
        f.write("utc\tchat\tdecide\tmax_inflight\tavg_latency_s\n")
    while True:
        time.sleep(60)
        with lock:
            n = st["chat"] + st["decide"]
            row = (time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime()), st["chat"], st["decide"], st["max_inflight"],
                   f"{st['lat'] / n:.2f}" if n else "")
            st.update(chat=0, decide=0, max_inflight=st["inflight"], lat=0.0)
        with open(a.stats, "a") as f:
            f.write("\t".join(map(str, row)) + "\n")

threading.Thread(target=stats, daemon=True).start()
ThreadingHTTPServer(("127.0.0.1", a.port), H).serve_forever()
