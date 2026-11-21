#!/usr/bin/env bash
# End-to-end smoke test: DBs (compose) -> both services (bootRun) -> 4 checks over real HTTP -> cleanup.
# Run from anywhere; needs Docker + Compose v2, curl, jq. App logs go to a temp dir printed below.
set -euo pipefail
cd "$(dirname "$0")/.."

command -v jq >/dev/null || { echo "jq is required (macOS: brew install jq, Debian/Ubuntu: apt-get install jq)" >&2; exit 1; }
command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }

UNGROUPED=pq12td4qds # seed id with no group (see group-service db/seed/V100__seed.sql)
LOG_DIR=$(mktemp -d)
LOG="$LOG_DIR/bootRun.log"
GRADLE_PID=""
STARTED_DB=""

for port in 8080 8081 9080 9081; do
  if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then echo "port $port is already in use; stop it first" >&2; exit 1; fi
done

cleanup() {
  status=$?
  if [ -n "$GRADLE_PID" ]; then
    kill "$GRADLE_PID" 2>/dev/null || true
    wait "$GRADLE_PID" 2>/dev/null || true
    # The Gradle daemon stops both app JVMs once the client is gone; give them a moment to release the ports.
    for _ in $(seq 1 20); do (exec 3<>/dev/tcp/127.0.0.1/8080) 2>/dev/null || break; sleep 1; done
  fi
  # Only tear down the DBs if this script started them.
  if [ -n "$STARTED_DB" ]; then docker compose down -v >/dev/null 2>&1 || true; fi
  [ "$status" -eq 0 ] && echo "SMOKE OK" || echo "SMOKE FAILED (logs: $LOG)" >&2
}
trap cleanup EXIT

[ -z "$(docker compose ps -q)" ] && STARTED_DB=1
docker compose up -d --wait

# One Gradle invocation runs both apps in parallel (two concurrent ./gradlew calls would contend on the project lock).
echo "starting services, log: $LOG"
./gradlew --parallel :group-service:bootRun :member-service:bootRun >"$LOG" 2>&1 &
GRADLE_PID=$!

wait_ready() { # $1 = management port
  for _ in $(seq 1 90); do
    curl -fs "http://127.0.0.1:$1/actuator/health/readiness" >/dev/null && return 0
    kill -0 "$GRADLE_PID" 2>/dev/null || { tail -n 40 "$LOG" >&2; return 1; }
    sleep 1
  done
  echo "readiness timeout on :$1" >&2; tail -n 40 "$LOG" >&2; return 1
}
wait_ready 9081
wait_ready 9080

check() { # $1 = label, $2 = expected status, $3 = url, $4 = jq assertion on body
  body=$(curl -s -o - -w '\n%{http_code}' "$3")
  code=${body##*$'\n'}
  body=${body%$'\n'*}
  if [ "$code" = "$2" ] && jq -e "$4" >/dev/null <<<"$body"; then echo "ok   $1"; else echo "FAIL $1 (status $code): $body" >&2; return 1; fi
}

check "list offset size=5"        200 "http://localhost:8080/api/members?size=5"           '.members | length == 5'
check "list cursor first page"    200 "http://localhost:8080/api/members?pagination=cursor" '.cursor.hasNext == true'
check "ungrouped member detail"   200 "http://localhost:8080/api/member/$UNGROUPED"        '.group == null'
check "invalid member id"         400 "http://localhost:8080/api/member/ABC"               '.code == "INVALID_REQUEST"'
