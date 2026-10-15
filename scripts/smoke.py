#!/usr/bin/env python3
"""Exercise the packaged application through a real process crash and restart."""
import json
import os
from pathlib import Path
import socket
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent.parent
first_receipt = threading.Event()
release_first = threading.Event()
receipt_lock = threading.Lock()
receipts = 0
process = None


class Supplier(BaseHTTPRequestHandler):
    def do_POST(self):
        global receipts
        self.rfile.read(int(self.headers.get("Content-Length", "0")))
        with receipt_lock:
            receipts += 1
            number = receipts
        if number == 1:
            first_receipt.set()
            release_first.wait(10)
        try:
            self.send_response(204)
            self.send_header("Content-Length", "0")
            self.end_headers()
        except (BrokenPipeError, ConnectionResetError):
            pass

    def log_message(self, *_):
        pass


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def call(method, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    request = Request(f"http://127.0.0.1:{app_port}{path}", data=data, method=method,
                      headers={"X-API-Key": "smoke-client-key", "Content-Type": "application/json"})
    with urlopen(request, timeout=2) as response:
        return response.status, json.load(response)


def start_app(log_name):
    global process
    with (ROOT / "target" / log_name).open("w") as log:
        process = subprocess.Popen([
            "java", "-jar", str(ROOT / "target/notification-service-0.1.0.jar"),
            "--notify.poll-millis=50", "--notify.request-timeout=2s", "--notify.lease-duration=3s",
            "--notify.backoff-base=100ms", "--notify.backoff-cap=1s"], cwd=ROOT, env=env,
            stdout=log, stderr=subprocess.STDOUT)
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"Application exited; inspect target/{log_name}")
        try:
            if call("GET", "/notifications?status=failed")[0] == 200:
                return
        except (HTTPError, URLError, TimeoutError):
            pass
        time.sleep(0.05)
    raise RuntimeError(f"Application startup timed out; inspect target/{log_name}")


if __name__ == "__main__":
    if not os.environ.get("TEST_DB_URL"):
        raise SystemExit("TEST_DB_URL must refer to a disposable PostgreSQL database")
    server = ThreadingHTTPServer(("127.0.0.1", 0), Supplier)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    app_port = free_port()
    origin = f"http://127.0.0.1:{server.server_port}"
    env = dict(os.environ, DB_URL=os.environ["TEST_DB_URL"], DB_USER=os.environ.get("TEST_DB_USER", "postgres"),
               DB_PASSWORD=os.environ.get("TEST_DB_PASSWORD", ""), NOTIFY_API_KEY="smoke-client-key",
               NOTIFY_ALLOWED_ORIGINS=origin, NOTIFY_WORKER_ENABLED="true", PORT=str(app_port))
    try:
        start_app("smoke-before-crash.log")
        status, accepted = call("POST", "/notifications", {"request_id": f"smoke-{time.time_ns()}",
            "url": origin + "/hook", "method": "POST", "headers": {"Content-Type": "application/json"}, "body": "{}"})
        assert status == 202, accepted
        task_path = "/notifications/" + accepted["id"]
        assert first_receipt.wait(5), "Supplier never received the first attempt"
        assert call("GET", task_path)[1]["status"] == "processing"
        process.kill()
        process.wait(timeout=5)
        release_first.set()
        start_app("smoke-after-restart.log")
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            result = call("GET", task_path)[1]
            if result["status"] == "succeeded":
                break
            time.sleep(0.05)
        assert result["status"] == "succeeded", result
        assert result["attempts"] >= 2 and receipts >= 2, result
        assert result["history"][0]["outcome"] == "unknown", result
        print(json.dumps({"packaged_app": "passed", "crash_recovery": "passed",
                          "attempts": result["attempts"], "supplier_receipts": receipts}))
    finally:
        release_first.set()
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        server.shutdown()
        server.server_close()
