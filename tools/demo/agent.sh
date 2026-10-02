#!/usr/bin/env bash
# Dry-run demo agents:  tools/demo/agent.sh start|stop|cli  a|b  [cli args]
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
case "$2" in a) PORT=8787; FAKE=kids ;; b) PORT=8788; FAKE=stress ;; *) echo "a or b"; exit 1 ;; esac
export CURFEW_DRY=1 CURFEW_PORT=$PORT CURFEW_STATE="$ROOT/.dry/$2" CURFEW_FAKE="$ROOT/tools/demo/$FAKE.json"
case "$1" in
  start) nohup python3 -u "$ROOT/agent/curfew.py" serve >> "$ROOT/logs/agent-$2.log" 2>&1 & ;;
  stop) kill $(ss -ltnpH "sport = :$PORT" | grep -o 'pid=[0-9]*' | cut -d= -f2) 2>/dev/null ;;
  cli) shift 2; python3 "$ROOT/agent/curfew.py" "$@" ;;
esac
