#!/usr/bin/env bash
# Fabric dev SERVER (for testing real network connections: `connect_server localhost:25565`).
#   scripts/run-server.sh start [version] | stop | status | log [n]
# Uses the same fabric/run dir as the client (world: devserver-world, online-mode=false).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="$ROOT/run"; LOG="$RUN/devserver.log"; PIDFILE="$RUN/.server.pid"
mkdir -p "$RUN" "$ROOT/fabric/run"
case "${1:-start}" in
  start)
    [ -f "$ROOT/fabric/run/eula.txt" ] || printf 'eula=true\n' > "$ROOT/fabric/run/eula.txt"
    [ -f "$ROOT/fabric/run/server.properties" ] || printf 'online-mode=false\nserver-port=25565\nlevel-name=devserver-world\nspawn-protection=0\nmotd=MC-Inspector dev server\nview-distance=6\n' > "$ROOT/fabric/run/server.properties"
    TASK=":fabric:runServer"; [ -n "${2:-}" ] && TASK=":fabric:$2:runServer"
    cd "$ROOT"; nohup ./gradlew "$TASK" --console=plain > "$LOG" 2>&1 &
    echo $! > "$PIDFILE"; echo "started gradle $TASK (pid $!) — log: $LOG"
    for i in $(seq 1 120); do
      grep -q 'Done (' "$LOG" 2>/dev/null && { echo "server ready on localhost:25565"; exit 0; }
      kill -0 "$(cat "$PIDFILE")" 2>/dev/null || { echo "gradle exited early"; tail -20 "$LOG"; exit 1; }
      sleep 2
    done; echo "timed out"; tail -20 "$LOG"; exit 1 ;;
  stop)
    pkill -f "fabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotServer" 2>/dev/null || true
    [ -f "$PIDFILE" ] && { kill "$(cat "$PIDFILE")" 2>/dev/null || true; rm -f "$PIDFILE"; }
    echo "stopped" ;;
  status) pgrep -f "knot.KnotServer" >/dev/null && echo "running" || echo "not running" ;;
  log) tail -n "${2:-50}" "$LOG" ;;
  *) echo "usage: $0 start|stop|status|log"; exit 2 ;;
esac
