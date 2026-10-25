#!/bin/bash
# One command for a live server release, run ON 164 from the staged folder (/opt/mapleroot-deployments/v<ver>/):
#
#   bash /opt/mapleroot-deployments/v<ver>/release-live.sh --check   # pre-flight only, changes nothing
#   bash /opt/mapleroot-deployments/v<ver>/release-live.sh --go
#
# Order (docs/release/RUNBOOK.md section 2): pre-flight checks -> stop-backup (maintenance on, Cody "going down",
# stop, DB + runtime backup) -> extra/extra-steps.sh if the release has one -> apply-start -> smoke -> maintenance end
# (Cody "back up"). It checks before every step and stops at the first problem with what to do next.
# Before the stop, nothing has changed. A failure in the extra steps restarts the old version automatically (the
# data steps are compatible with it). A failure in apply-start or smoke does NOT roll back by itself: it prints the
# rollback command and leaves maintenance on, so a person decides.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
VER="${HERE##*/v}"
LOG="$HERE/release-live-$(date -u +%Y%m%dT%H%M%SZ).log"
exec > >(tee -a "$LOG") 2>&1
say() { echo "[$(date -u +%H:%M:%S) UTC] $*"; }
stop_here() { say "STOPPED: $*"; say "log: $LOG"; exit 1; }

MODE="${1:-}"
[ "$MODE" = "--go" ] || [ "$MODE" = "--check" ] || { echo "usage: bash $0 --check   (pre-flight only, changes nothing)"; echo "       bash $0 --go      (releases SlumsMS $VER to LIVE)"; exit 2; }
say "SlumsMS $VER live release from $HERE"

# --- pre-flight: nothing changes here ---
for f in deploy.sh rollback.sh REHEARSAL.md; do [ -f "$HERE/$f" ] || stop_here "missing $f in $HERE (stage it first)"; done
grep -q "rehearsal PASS\|smoke PASS" "$HERE/REHEARSAL.md" || stop_here "REHEARSAL.md does not show a PASS: rehearse first"
systemctl is-active --quiet slumsms || stop_here "live (slumsms) is not running now; check it before releasing"
NAV=$(sed -n "s/^NAV_CACHE_SRC='\(.*\)'/\1/p" "$HERE/deploy.sh")
if [ -n "$NAV" ]; then [ -d "$NAV" ] || stop_here "nav cache $NAV missing"; say "nav cache: $(ls "$NAV" | wc -l) graphs in $NAV"; fi
if [ -f "$HERE/extra/extra-steps.sh" ]; then bash -n "$HERE/extra/extra-steps.sh" || stop_here "extra-steps.sh has a syntax error"; say "extra steps: present"; fi
FREE=$(df -Pk /opt | awk 'NR==2{print $4}'); [ "$FREE" -gt 5000000 ] || stop_here "less than 5 GB free on /opt"
say "pre-flight OK"
[ "$MODE" = "--check" ] && { say "check only: nothing was changed"; exit 0; }

# --- 1. stop + backup ---
say "1/4 stop-backup"
bash "$HERE/deploy.sh" --phase stop-backup || stop_here "stop-backup failed. If live is down: bash $HERE/deploy.sh --phase restart-old"
systemctl is-active --quiet slumsms && stop_here "slumsms still running after stop-backup"
say "live stopped and backed up"

# --- 2. extra steps (data + config the deploy has no slot for) ---
if [ -f "$HERE/extra/extra-steps.sh" ]; then
  say "2/4 extra steps"
  if ! bash "$HERE/extra/extra-steps.sh"; then
    say "extra steps failed: restarting the old version"
    bash "$HERE/deploy.sh" --phase restart-old && stop_here "extra steps failed; the OLD version is back up (maintenance still on: slumsms-monitor maintenance live end when checked)"
    stop_here "extra steps failed AND restart-old failed: live is DOWN. Check journalctl -u slumsms"
  fi
else
  say "2/4 no extra steps"
fi

# --- 3. apply + start, then smoke ---
say "3/4 apply-start"
bash "$HERE/deploy.sh" --phase apply-start || stop_here "apply-start failed. Roll back: bash $HERE/rollback.sh (maintenance stays on)"
say "3/4 smoke"
bash "$HERE/deploy.sh" --phase smoke || stop_here "smoke failed. Live is up on $VER but unchecked. Roll back if needed: bash $HERE/rollback.sh"
systemctl is-active --quiet slumsms || stop_here "slumsms not active after smoke"

# --- 4. maintenance end ---
say "4/4 maintenance end"
/usr/local/bin/slumsms-monitor maintenance live end || say "WARN: maintenance end failed; run it by hand: /usr/local/bin/slumsms-monitor maintenance live end"
say "DONE: SlumsMS $VER is live. Next: log in through the launcher and check CHECKLIST.md's in-game list."
say "log: $LOG"
