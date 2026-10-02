#!/usr/bin/env bash
# Starts "curfew pair" for a dry-run demo agent and prints the code.  tools/demo/pair.sh a 8787
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CURFEW_DRY=1 CURFEW_PORT=$2 CURFEW_STATE=$ROOT/.dry/$1 nohup python3 -u "$ROOT/agent/curfew.py" pair > "$ROOT/logs/pair-$1.log" 2>&1 &
sleep 1.5; grep -E "Address|Code" "$ROOT/logs/pair-$1.log"
