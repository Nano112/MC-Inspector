#!/usr/bin/env bash
# Launch / stop the dev Minecraft client with the MCP host, detached.
#   scripts/run-client.sh start [version]   (default: active Stonecutter version)
#   scripts/run-client.sh stop
#   scripts/run-client.sh status
#   scripts/run-client.sh log [lines]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="$ROOT/run"
LOG="$RUN/mcinspector-client.log"
PIDFILE="$RUN/.client.pid"
PORT="${MCINSPECTOR_PORT:-38271}"
HEALTH="http://127.0.0.1:$PORT/health"
mkdir -p "$RUN"

health() { command -v curl >/dev/null && curl -fsS --max-time 2 "$HEALTH" 2>/dev/null; }

case "${1:-start}" in
  start)
    if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
      echo "already running (pid $(cat "$PIDFILE"))"; health && echo; exit 0
    fi
    TASK=":fabric:runClient"; [ -n "${2:-}" ] && TASK=":fabric:$2:runClient"
    cd "$ROOT"
    nohup ./gradlew "$TASK" --console=plain > "$LOG" 2>&1 &
    echo $! > "$PIDFILE"
    echo "started gradle $TASK (pid $!) — log: $LOG"
    for i in $(seq 1 180); do
      if out=$(health); then echo "MCP ready: http://127.0.0.1:$PORT/mcp  $out"; exit 0; fi
      if ! kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then echo "gradle exited early; tail of log:"; tail -30 "$LOG"; exit 1; fi
      sleep 2
    done
    echo "timed out waiting for $HEALTH"; tail -20 "$LOG"; exit 1 ;;
  stop)
    if [ -f "$PIDFILE" ]; then
      PID=$(cat "$PIDFILE"); pkill -P "$PID" 2>/dev/null || true; kill "$PID" 2>/dev/null || true; rm -f "$PIDFILE"
    fi
    # The actual MC java process is a grandchild of gradle; match the CLIENT launcher only (not a dev server).
    pkill -f "fabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotClient" 2>/dev/null || true
    echo "stopped" ;;
  status)
    if health; then echo; else echo "not reachable at $HEALTH"; fi ;;
  log)
    tail -n "${2:-50}" "$LOG" ;;
  *) echo "usage: $0 start|stop|status|log"; exit 2 ;;
esac
