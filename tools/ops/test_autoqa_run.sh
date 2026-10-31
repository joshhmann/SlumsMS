#!/bin/bash
# Shell-level test of tools/ops/autoqa-run's queue (b016). Local mode only: it never touches a server.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
d="$(mktemp -d)"
trap 'rm -rf "$d"' EXIT
export AUTOQA_LOCAL_DIR="$d"
o1=$("$here/autoqa-run" t-ld-1)
o2=$("$here/autoqa-run" t-bt-1 --fast)
[[ "$o1" == *"queued (#1, position 1)"* ]] || { echo "FAIL first: $o1"; exit 1; }
[[ "$o2" == *"queued (#2, position 2)"* ]] || { echo "FAIL second: $o2"; exit 1; }
[[ $(wc -l < "$d/run-queue.txt") -eq 2 ]] || { echo "FAIL: two lines expected"; exit 1; }
[[ $(cut -f3 "$d/run-queue.txt" | paste -sd,) == "t-ld-1,t-bt-1 --fast" ]] || { echo "FAIL: order/content"; exit 1; }
grep -qP '^1\t\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ\tt-ld-1$' "$d/run-queue.txt" || { echo "FAIL: line format"; exit 1; }
[[ ! -e "$d/run-request.txt" ]] || { echo "FAIL: legacy file written"; exit 1; }
echo "PASS"
