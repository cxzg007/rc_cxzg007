#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

verify() {
  mvn -B "$@" verify
  python3 scripts/smoke.py
}

# An explicit TEST_DB_URL must point to a disposable database: integration tests truncate its tables.
if [[ -n "${TEST_DB_URL:-}" ]]; then
  verify "$@"
  exit
fi

notify_pg_bin="${PG_BIN:-}"
if [[ -z "$notify_pg_bin" ]] && command -v initdb >/dev/null 2>&1; then
  notify_pg_bin="$(dirname "$(command -v initdb)")"
fi
if [[ -z "$notify_pg_bin" ]]; then
  for candidate in /opt/homebrew/opt/postgresql@17/bin /usr/local/opt/postgresql@17/bin /usr/lib/postgresql/17/bin; do
    if [[ -x "$candidate/initdb" ]]; then notify_pg_bin="$candidate"; break; fi
  done
fi
if [[ ! -x "$notify_pg_bin/initdb" || ! -x "$notify_pg_bin/createdb" ]]; then
  printf '%s\n' 'Install PostgreSQL 17 and set PG_BIN, or provide TEST_DB_URL for a disposable PostgreSQL database.' >&2
  exit 1
fi

# Keep the selected tool path, but prevent libpq settings from redirecting createdb.
for notify_pg_env in "${!PG@}"; do
  unset "$notify_pg_env"
done

notify_test_dir="$(mktemp -d /tmp/rc-notify-test.XXXXXX)"
notify_started=0
cleanup() {
  if [[ "$notify_started" == 1 ]]; then "$notify_pg_bin/pg_ctl" -D "$notify_test_dir/data" -m fast -w stop >/dev/null; fi
  rm -rf "$notify_test_dir"
}
trap cleanup EXIT
notify_test_port="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')"
"$notify_pg_bin/initdb" -D "$notify_test_dir/data" -U postgres -A trust --encoding=UTF8 --locale=C > "$notify_test_dir/initdb.log"
"$notify_pg_bin/pg_ctl" -D "$notify_test_dir/data" -l "$notify_test_dir/postgres.log" -o "-h 127.0.0.1 -p $notify_test_port -k $notify_test_dir" -w start
notify_started=1
"$notify_pg_bin/createdb" -h 127.0.0.1 -p "$notify_test_port" -U postgres notification_test
export TEST_DB_URL="jdbc:postgresql://127.0.0.1:$notify_test_port/notification_test"
export TEST_DB_USER=postgres TEST_DB_PASSWORD=
verify "$@"
