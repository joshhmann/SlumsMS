#!/bin/bash
# Shell-level test of tools/ops/mechanics-stage's argument checking and queue line. Local mode only: it never sshes.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
d="$(mktemp -d)"
trap 'rm -rf "$d"' EXIT
export MECHANICS_LOCAL_DIR="$d"
tool="$here/mechanics-stage"

fails() { "$@" >/dev/null 2>&1 && { echo "FAIL accepted: $*"; exit 1; }; return 0; }

o=$("$tool" pq lpq for AqClient 4 ranged2)
[[ "$o" == *"queued (#1, position 1)"* ]] || { echo "FAIL first: $o"; exit 1; }
[[ "$o" == *"'pq lpq for AqClient 4 ranged2'"* ]] || { echo "FAIL request: $o"; exit 1; }
o=$("$tool" pq opq for AqClient)
[[ "$o" == *"queued (#2, position 2)"* ]] || { echo "FAIL second: $o"; exit 1; }
[[ $(cut -f3 "$d/stage-queue.txt" | paste -sd,) == "pq lpq for AqClient 4 ranged2,pq opq for AqClient" ]] \
    || { echo "FAIL order/content"; exit 1; }
grep -qP '^1\t\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ\tpq lpq for AqClient 4 ranged2$' "$d/stage-queue.txt" \
    || { echo "FAIL line format"; exit 1; }

# nobody is typing: "me" is refused, a leader is required, and the counts are checked here so no bad line is queued
fails "$tool"
fails "$tool" pq
fails "$tool" pq lpq
fails "$tool" pq lpq for
fails "$tool" pq lpq me 4
fails "$tool" pq lpq for me
fails "$tool" pq lpq for AqClient 2 ranged3
fails "$tool" pq lpq for AqClient for B
fails "$tool" pq lpq for AqClient 31
fails "$tool" boss zakum 4
[[ $(wc -l < "$d/stage-queue.txt") -eq 2 ]] || { echo "FAIL: a refused call queued a line"; exit 1; }
echo "PASS"
