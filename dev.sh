#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: ./dev.sh [--mock | --real-ai]

Without arguments: choose 1 (Mock AI) or 2 (Real AI).
--mock: local profile. --real-ai: local,real-ai profile.
Requires Java 25, Node 26, npm dependencies, and a running Docker engine.
Press Ctrl+C to stop both servers.
EOF
}

if (( $# > 1 )); then usage >&2; exit 1; fi
profiles=local
case "${1:-}" in
  --help|-h) usage; exit 0 ;;
  --mock) profiles=local ;;
  --real-ai) profiles=local,real-ai ;;
  '')
    printf '選擇 AI 模式：\n  1. Mock AI（本機假模型）\n  2. Real AI（Gemini）\n'
    while :; do
      if ! read -r -p '請輸入 1 或 2（Enter 預設 1）：' choice; then
        printf '\n未取得選擇，請使用 --mock 或 --real-ai。\n' >&2
        exit 1
      fi
      case "$choice" in
        1|'') profiles=local; break ;;
        2) profiles=local,real-ai; break ;;
        *) printf '請輸入 1 或 2。\n' >&2 ;;
      esac
    done
    ;;
  *) usage >&2; exit 1 ;;
esac

root_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
backend_pid=''
frontend_pid=''

fail() { printf '%s\n' "$*" >&2; exit 1; }

for command in java node npm docker curl lsof; do
  command -v "$command" >/dev/null 2>&1 || fail "Missing prerequisite: $command. See README.md."
done
docker compose version >/dev/null 2>&1 || fail 'Docker Compose is unavailable.'
docker info >/dev/null 2>&1 || fail 'Docker is not running. Start OrbStack or Docker Desktop first.'
[[ -x "$root_dir/frontend/node_modules/.bin/ng" ]] || fail 'Run npm ci in the frontend directory first.'
for port in 8080 4200; do
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN -t >/dev/null 2>&1; then
    fail "Port $port is already in use. Stop that server before running this script."
  fi
done

cleanup() {
  local exit_code=$?
  trap - EXIT
  trap '' INT TERM
  set +e
  printf '\nStopping frontend and backend...\n'
  local pid deadline
  for pid in "$frontend_pid" "$backend_pid"; do
    [[ -n "$pid" ]] && kill -TERM -- "-$pid" 2>/dev/null
  done
  deadline=$((SECONDS + 30))
  for pid in "$frontend_pid" "$backend_pid"; do
    [[ -n "$pid" ]] || continue
    while kill -0 -- "-$pid" 2>/dev/null && (( SECONDS < deadline )); do sleep 1; done
    if kill -0 -- "-$pid" 2>/dev/null; then
      printf 'Process group %s did not stop within 30 seconds; terminating remaining processes.\n' "$pid" >&2
      kill -KILL -- "-$pid" 2>/dev/null
    fi
    wait "$pid" 2>/dev/null
  done
  exit "$exit_code"
}

check_servers() {
  local pid exit_code
  for pid in "$backend_pid" "$frontend_pid"; do
    [[ -n "$pid" ]] || continue
    if ! kill -0 "$pid" 2>/dev/null; then
      if wait "$pid"; then exit_code=1; else exit_code=$?; fi
      printf 'A server exited; stopping the other server. Check the output above.\n' >&2
      exit "$exit_code"
    fi
  done
}

wait_for() {
  local url=$1 deadline=$((SECONDS + $2))
  while (( SECONDS < deadline )); do
    check_servers
    if curl -fsS --connect-timeout 1 --max-time 2 "$url" >/dev/null 2>&1; then return; fi
    sleep 1
  done
  fail "Timed out waiting for $url. Check the server output above."
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Bash job control puts each background job in its own process group. Signalling only
# those groups also stops npm/Gradle descendants, without touching unrelated services.
# Use exec so each captured PID remains its process-group leader (macOS Bash 3.2).
set -m
printf 'Starting backend (profiles: %s)...\n' "$profiles"
(
  cd "$root_dir/backend"
  # Boot 4.1.2 manages Compose readiness and shutdown. Previously running containers
  # remain running under its default ownership rules; no data/volumes are deleted.
  # https://docs.spring.io/spring-boot/reference/features/dev-services.html
  exec ./gradlew --no-daemon --console=plain bootRun \
    "--args=--spring.profiles.active=$profiles --server.address=127.0.0.1 --server.port=8080"
) </dev/null &
backend_pid=$!
wait_for http://127.0.0.1:8080/actuator/health 180

printf 'Backend ready. Starting frontend...\n'
(
  cd "$root_dir/frontend"
  exec npm start -- --host 127.0.0.1 --port 4200
) </dev/null &
frontend_pid=$!
wait_for http://127.0.0.1:4200/ 90
printf '\nReady: http://localhost:4200/\nBackend: http://127.0.0.1:8080/\nPress Ctrl+C to stop both servers.\n'

while :; do check_servers; sleep 1; done
