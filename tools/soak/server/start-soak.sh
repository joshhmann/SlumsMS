#!/bin/bash
# Start slums-soak (ticket 48): the soak copy of live as a transient unit at low priority, plus the local LLM mock.
# Not enabled at boot (transient). JVM flags = live's ExecStart (bots on, template, autopopulate) with the soak ports,
# GC logging and the dashboard snapshot exporter into $DATA.
#
#   start-soak.sh [--heap 4G]
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd); . "$here/soak.env"
[ "${1:-}" = --heap ] && HEAP=$2
[ -f "$SRV/target/Cosmic.jar" ] || { echo "no Server copy; run refresh-soak.sh live first"; exit 1; }
! systemctl is-active --quiet "$UNIT" || { echo "$UNIT already runs"; exit 1; }
for p in $LOGIN_PORT $(seq "$CHANNEL_BASE" $((CHANNEL_BASE + 7))); do
  ! ss -ltn | grep -q ":$p " || { echo "port $p is in use"; exit 1; }
done
mkdir -p "$DATA"/{gc,dashboard}
heap_mb=$(echo "$HEAP" | awk '/[Gg]$/{print $0*1024; next} /[Mm]$/{print $0+0; next} {print int($0/1048576)}')
max=$(( heap_mb + 2048 ))M
avail=$(awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo)
[ "$avail" -gt $(( heap_mb + 2048 + 3072 )) ] || { echo "MemAvailable ${avail} MB: not enough for heap $HEAP + 2 GB and a 3 GB margin"; exit 1; }

if ! systemctl is-active --quiet "$MOCK_UNIT"; then
  systemctl reset-failed "$MOCK_UNIT" 2>/dev/null || true
  systemd-run --unit="$MOCK_UNIT" -p Nice=10 -p CPUQuota=20% -p MemoryMax=256M \
    /usr/bin/python3 "$here/llm-mock.py" "$MOCK_PORT" "$DATA/llm-mock.tsv" --min 1 --max 3
fi
flags=$(sed -n 's/^ExecStart=//p' "$LIVE_UNIT_FILE" | tail -1 | tr ' ' '\n' \
  | grep -E '^-D(solomapling|cashshop|weather|slums)\.' | grep -v 'attackcapture' | tr '\n' ' ')
systemctl reset-failed "$UNIT" 2>/dev/null || true
set -x
systemd-run --unit="$UNIT" -p Nice=19 -p CPUWeight=20 -p IOWeight=20 -p CPUQuota=300% -p MemoryMax="$max" \
  -p TimeoutStopSec=5min -p SuccessExitStatus=143 -p WorkingDirectory="$SRV" \
  /usr/bin/java -Xmx"$HEAP" "-Xlog:gc*,safepoint:file=$DATA/gc/gc-%t.log:time,uptime,level,tags:filecount=20,filesize=50M" \
  -Dsolomapling.dashboard=true -Dsolomapling.dashboard.dir="$DATA/dashboard" -Dsolomapling.dashboard.periodSeconds=30 \
  -Dmapleroot.login-port="$LOGIN_PORT" -Dmapleroot.channel-base-port="$CHANNEL_BASE" $flags -jar target/Cosmic.jar
