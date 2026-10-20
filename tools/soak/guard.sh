#!/bin/bash
# Ticket 48 live guard. Runs next to a capacity soak on LXC 125 and protects live (slumsms.service).
# Every INTERVAL s it samples, read-only, live's health and the box:
#   live CPU % (from /proc/<pid>/stat), live run-queue wait (ms waited for a CPU per second, summed over live's
#   threads from /proc/<pid>/task/*/schedstat: the direct measure of "the soak is taking live's CPU"), live GC ms and
#   heap (jstat, which reads the hsperfdata file and does not attach), WARN/ERROR lines in live's journal, the
#   BotEquipChecker scan time live prints, BotTickService "wheel lag" lines (live's bot tick wheel falling behind),
#   load average, CPU pressure, MemAvailable, and the soak target's CPU/RSS.
# The first BASELINE s (soak load generator not running yet) set the baseline. After that, if live's run-queue wait
# or GC time per minute degrade noticeably vs the baseline for 3 samples in a row, or live logs a wheel-lag line
# (counts as degraded right away), or MemAvailable < 2 GB, it scales
# the load generator down (writes a smaller client count into $CTL) and, if that is not enough, stops it, and logs
# why. Live and the test servers themselves are never touched.
#
#   guard.sh <outdir> [--interval 30] [--baseline 300] [--target-unit slums-persistence-test] [--loadgen-unit soak-loadgen]
set -uo pipefail
OUT=${1:?usage: guard.sh <outdir> [options]}; shift
INTERVAL=30 BASELINE=300 TARGET=slums-persistence-test LOADGEN=soak-loadgen
while [ $# -gt 0 ]; do case $1 in
  --interval) INTERVAL=$2; shift;; --baseline) BASELINE=$2; shift;;
  --target-unit) TARGET=$2; shift;; --loadgen-unit) LOADGEN=$2; shift;; *) echo "unknown $1"; exit 2;; esac; shift; done
mkdir -p "$OUT"
TSV=$OUT/guard.tsv LOG=$OUT/guard.log CTL=$OUT/loadgen.clients BASEF=$OUT/baseline.txt
HZ=$(getconf CLK_TCK)
log() { echo "$(date -u '+%F %T') $*" | tee -a "$LOG"; }

pid_of() { systemctl show -p MainPID --value "$1" 2>/dev/null; }
cpu_ticks() { awk '{print $14+$15}' "/proc/$1/stat" 2>/dev/null || echo 0; }
rq_wait_ns() { cat /proc/"$1"/task/*/schedstat 2>/dev/null | awk '{s+=$2} END{printf "%.0f", s}'; }
mem_avail() { awk '/MemAvailable/{printf "%d", $2/1024}' /proc/meminfo; }
gc_heap() {  # GCT seconds and heap used MB (S0U+S1U+EU+OU)
  jstat -gc "$1" 2>/dev/null | awk 'NR==2{printf "%s %.0f", $NF, ($3+$4+$6+$8)/1024}' || echo "0 0"; }

[ -f "$TSV" ] || echo -e "utc\tphase\tlive_cpu_pct\tlive_rq_ms_per_s\tlive_gc_ms_per_min\tlive_heap_mb\tlive_warn_err\tlive_equipscan_ms\tload1\tpsi_cpu_some10\tmem_avail_mb\ttarget_cpu_pct\ttarget_rss_mb\tloadgen_clients\taction\tlive_wheel_lag_lines" > "$TSV"
start=$(date +%s)
lp=$(pid_of slumsms); tp=$(pid_of "$TARGET")
p_lc=$(cpu_ticks "$lp"); p_lrq=$(rq_wait_ns "$lp"); read -r p_gct _ <<< "$(gc_heap "$lp")"
p_tc=$(cpu_ticks "$tp"); p_t=$(date +%s.%N); since=$(date '+%Y-%m-%d %H:%M:%S')
bad=0 stage=0
log "guard start: live pid $lp, target $TARGET pid $tp, interval ${INTERVAL}s, baseline ${BASELINE}s"
while :; do
  sleep "$INTERVAL"
  now=$(date +%s.%N); dt=$(awk -v a="$p_t" -v b="$now" 'BEGIN{print b-a}')
  nlp=$(pid_of slumsms); ntp=$(pid_of "$TARGET")
  if [ "$nlp" != "$lp" ]; then log "live pid changed $lp -> $nlp (live restarted by someone else); re-reading counters"; lp=$nlp; p_lc=$(cpu_ticks "$lp"); p_lrq=$(rq_wait_ns "$lp"); read -r p_gct _ <<< "$(gc_heap "$lp")"; fi
  if [ "$ntp" != "$tp" ]; then log "target pid changed $tp -> $ntp"; tp=$ntp; p_tc=$(cpu_ticks "$tp"); fi
  lc=$(cpu_ticks "$lp"); lrq=$(rq_wait_ns "$lp"); read -r gct heap <<< "$(gc_heap "$lp")"; tc=$(cpu_ticks "$tp")
  live_cpu=$(awk -v a="$p_lc" -v b="$lc" -v h="$HZ" -v t="$dt" 'BEGIN{printf "%.1f", (b-a)/h/t*100}')
  live_rq=$(awk -v a="$p_lrq" -v b="$lrq" -v t="$dt" 'BEGIN{printf "%.1f", (b-a)/1e6/t}')
  live_gc=$(awk -v a="$p_gct" -v b="$gct" -v t="$dt" 'BEGIN{v=(b-a)*1000/t*60; if(v<0)v=0; printf "%.0f", v}')
  t_cpu=$(awk -v a="$p_tc" -v b="$tc" -v h="$HZ" -v t="$dt" 'BEGIN{printf "%.1f", (b-a)/h/t*100}')
  t_rss=$(awk '/VmRSS/{printf "%d", $2/1024}' "/proc/$tp/status" 2>/dev/null || echo 0)
  j=$(journalctl -u slumsms --since "$since" --no-pager -o cat 2>/dev/null)
  since=$(date '+%Y-%m-%d %H:%M:%S')
  werr=$(grep -cE ' (WARN|ERROR) |Exception' <<< "$j")
  wl=$(grep -c 'wheel lag avg' <<< "$j")
  eqms=$(grep -oE 'BotEquipChecker\] Scan complete: [0-9/]+ naked \(([0-9]+)ms\)' <<< "$j" | grep -oE '[0-9]+ms' | tr -d ms | sort -n | tail -1)
  load1=$(cut -d' ' -f1 /proc/loadavg); psi=$(awk -F'[ =]' '/^some/{print $3}' /proc/pressure/cpu)
  mem=$(mem_avail); clients=$(cat "$CTL" 2>/dev/null || echo -)
  p_lc=$lc; p_lrq=$lrq; p_gct=$gct; p_tc=$tc; p_t=$now
  el=$(( $(date +%s) - start )); action=""
  if [ "$el" -le "$BASELINE" ] && [ ! -f "$BASEF.final" ]; then
    phase=baseline
  else
    phase=soak
    if [ ! -f "$BASEF.final" ]; then
      awk -F'\t' '$2=="baseline"{n++; rq[n]=$4; cpu[n]=$3; gc[n]=$5} END{
        asort(rq); asort(cpu); asort(gc); p=int(n*0.95); if(p<1)p=1; m=int((n+1)/2); if(m<1)m=1;
        printf "samples=%d rq_median=%.1f rq_p95=%.1f cpu_median=%.1f gc_ms_min_median=%.0f gc_ms_min_p95=%.0f\n", n, rq[m], rq[p], cpu[m], gc[m], gc[p]}' "$TSV" > "$BASEF"
      touch "$BASEF.final"; log "baseline: $(cat "$BASEF")"
    fi
    rq95=$(sed -E 's/.*rq_p95=([0-9.]+).*/\1/' "$BASEF"); gc95=$(sed -E 's/.*gc_ms_min_p95=([0-9]+).*/\1/' "$BASEF")
    # degraded: run-queue wait above 2x the baseline p95 and at least 150 ms/s more (live's threads queueing for a
    # CPU an extra 15% of a core), or GC time per minute above 3x baseline p95 + 500 ms
    deg=$(awk -v r="$live_rq" -v r95="$rq95" -v g="$live_gc" -v g95="$gc95" 'BEGIN{print ((r > 2*r95 && r > r95+150) || (g > 3*g95+500)) ? 1 : 0}')
    lowmem=$([ "$mem" -lt 2048 ] && echo 1 || echo 0)
    if [ "$deg" = 1 ] || [ "$lowmem" = 1 ]; then bad=$((bad+1)); else bad=0; fi
    [ "$wl" -gt 0 ] && bad=3
    if [ "$lowmem" = 1 ] || [ "$bad" -ge 3 ]; then
      why="live wheel-lag lines $wl, live rq ${live_rq}ms/s (base p95 $rq95), live gc ${live_gc}ms/min (base p95 $gc95), MemAvailable ${mem}MB"
      cur=$(cat "$CTL" 2>/dev/null || echo 0)
      if [ "$cur" != - ] && [ "$cur" -gt 1 ] 2>/dev/null; then
        new=$((cur/2)); echo "$new" > "$CTL"; action="scale-down $cur->$new"; log "SCALE DOWN load generator $cur -> $new clients: $why"
      elif systemctl is-active --quiet "$LOADGEN"; then
        systemctl stop "$LOADGEN"; echo 0 > "$CTL"; action="stop-loadgen"; log "STOP load generator: $why"
      else
        action="alert"; log "ALERT live degraded with the load generator already stopped (the soak target's own bots were there before the soak): $why"
        touch "$OUT/ALERT"
      fi
      bad=0
    fi
  fi
  echo -e "$(date -u '+%F %T')\t$phase\t$live_cpu\t$live_rq\t$live_gc\t$heap\t$werr\t${eqms:-}\t$load1\t$psi\t$mem\t$t_cpu\t$t_rss\t$clients\t$action\t$wl" >> "$TSV"
done
