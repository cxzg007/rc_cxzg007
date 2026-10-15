#!/usr/bin/env python3
"""Verify storage failure responses using a PostgreSQL instance owned by this script."""
import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent.parent


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def postgres_bin():
    configured = os.environ.get("PG_BIN")
    if configured:
        candidates = [Path(configured)]
    else:
        initdb = shutil.which("initdb")
        candidates = ([Path(initdb).parent] if initdb else []) + [
            Path("/opt/homebrew/opt/postgresql@17/bin"),
            Path("/usr/local/opt/postgresql@17/bin"),
            Path("/usr/lib/postgresql/17/bin")]
    for candidate in candidates:
        if all(os.access(candidate / name, os.X_OK) for name in ("initdb", "pg_ctl", "createdb")):
            return candidate
    raise SystemExit("Install native PostgreSQL 17 and set PG_BIN to its bin directory.")


def verify(jar):
    pg = postgres_bin()
    # libpq hostaddr/service settings must not redirect createdb to another server.
    pg_env = {key: value for key, value in os.environ.items() if not key.startswith("PG")}
    app_port, db_port = free_port(), free_port()
    work = Path(tempfile.mkdtemp(prefix="rc-storage-outage-"))
    data = work / "data"
    app = None
    log_path = ROOT / "target/storage-outage-app.log"
    log_path.parent.mkdir(exist_ok=True)

    def pg_command(*args):
        subprocess.run([str(pg / args[0]), *map(str, args[1:])], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=45, env=pg_env)

    def start_db():
        pg_command("pg_ctl", "-D", data, "-l", work / "postgres.log", "-o",
                   f"-h 127.0.0.1 -p {db_port} -k {work}", "-w", "start")

    def call(method, path, body=None):
        request = Request(f"http://127.0.0.1:{app_port}{path}", method=method,
                          data=None if body is None else json.dumps(body).encode(),
                          headers={"X-API-Key": "outage-test-key", "Content-Type": "application/json"})
        try:
            response = urlopen(request, timeout=6)
        except HTTPError as error:
            response = error
        with response:
            return response.status, json.load(response)

    def payload(key):
        return {"request_id": key, "url": "http://127.0.0.1:1/hook",
                "method": "POST", "headers": {}, "body": "{}"}

    def unavailable(response):
        if response != (503, {"error": "storage_unavailable"}):
            raise AssertionError(f"Expected a sanitized 503 response, got {response}")

    def interrupted(signum, _frame):
        raise SystemExit(128 + signum)

    previous_handlers = {}
    try:
        for signum in (signal.SIGINT, signal.SIGTERM):
            previous_handlers[signum] = signal.signal(signum, interrupted)
        pg_command("initdb", "-D", data, "-U", "postgres", "-A", "trust", "--encoding=UTF8", "--locale=C")
        start_db()
        pg_command("createdb", "-h", "127.0.0.1", "-p", db_port, "-U", "postgres", "notification_test")
        with log_path.open("w") as log:
            app = subprocess.Popen([
                "java", "-jar", str(jar), "--server.address=127.0.0.1", f"--server.port={app_port}",
                f"--spring.datasource.url=jdbc:postgresql://127.0.0.1:{db_port}/notification_test",
                "--spring.datasource.username=postgres", "--spring.datasource.password=",
                "--spring.sql.init.mode=always", "--notify.clients.business=outage-test-key",
                "--notify.allowed-origins=http://127.0.0.1:1", "--notify.worker-enabled=false"],
                cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            if app.poll() is not None:
                raise RuntimeError(f"Application exited; inspect {log_path}")
            try:
                if call("GET", "/notifications?status=failed")[0] == 200:
                    break
            except (URLError, TimeoutError):
                pass
            time.sleep(0.1)
        else:
            raise RuntimeError(f"Application did not become ready; inspect {log_path}")

        status, accepted = call("POST", "/notifications", payload("before-outage"))
        if status != 202:
            raise AssertionError((status, accepted))
        pg_command("pg_ctl", "-D", data, "-m", "fast", "-w", "stop")
        unavailable(call("POST", "/notifications", payload("during-outage")))
        unavailable(call("GET", "/notifications/" + accepted["id"]))
        unavailable(call("POST", "/notifications", payload("during-outage")))

        start_db()
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            status, recovered = call("POST", "/notifications", payload("during-outage"))
            if status in (200, 202):
                break
            unavailable((status, recovered))
            time.sleep(0.25)
        else:
            raise AssertionError("API did not recover after PostgreSQL restarted")
        repeated = call("POST", "/notifications", payload("during-outage"))
        if repeated[0] != 200 or repeated[1]["id"] != recovered["id"]:
            raise AssertionError(f"Same-key retry created a different task: {repeated}")
        retained = call("GET", "/notifications/" + accepted["id"])
        if retained[0] != 200 or retained[1]["status"] != "pending":
            raise AssertionError(f"Previously accepted task was not retained: {retained}")
        print(json.dumps({"storage_outage": "passed", "offline_post": 503, "offline_get": 503,
                          "recovery": "passed", "same_key_retry": 200, "accepted_task_retained": True}))
    finally:
        # A repeated interrupt must not interrupt the cleanup already in progress.
        for signum in previous_handlers:
            signal.signal(signum, signal.SIG_IGN)
        try:
            if app is not None and app.poll() is None:
                app.terminate()
                try:
                    app.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    app.kill()
                    app.wait(timeout=5)
            if (data / "postmaster.pid").exists():
                # If shutdown fails, retain the owned directory rather than deleting a live database.
                pg_command("pg_ctl", "-D", data, "-m", "fast", "-w", "stop")
            shutil.rmtree(work)
        finally:
            for signum, handler in previous_handlers.items():
                signal.signal(signum, handler)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/notification-service-0.1.0.jar")
    args = parser.parse_args()
    if not args.jar.is_file():
        parser.error("JAR not found; run bash scripts/verify.sh first, or pass --jar PATH")
    verify(args.jar.resolve())
