#!/usr/bin/env python3
"""Local supplier demo; records counts, never headers or request payloads."""
import argparse
import threading
import time
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

counts = Counter()
lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def handle_request(self):
        self.rfile.read(int(self.headers.get("Content-Length", "0")))
        path = urlsplit(self.path).path
        with lock:
            counts[path] += 1
            attempt = counts[path]
        status = {"/ok": 204, "/permanent-fail": 400, "/always-fail": 503,
                  "/redirect": 302, "/retry-after": 429}.get(path, 404)
        if path == "/flaky":
            status = 503 if attempt <= 2 else 204
        if path == "/slow":
            time.sleep(args.slow_seconds)
            status = 204
        self.send_response(status)
        if status == 302:
            self.send_header("Location", "/ok")
        if status == 429:
            self.send_header("Retry-After", "5")
        self.send_header("Content-Length", "0")
        self.end_headers()
        print(f"{self.command} {path} count={attempt} status={status}", flush=True)

    do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = handle_request

    def log_message(self, *_):
        pass


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=9090)
    parser.add_argument("--slow-seconds", type=float, default=15)
    args = parser.parse_args()
    print(f"Mock supplier listening on 127.0.0.1:{args.port}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
