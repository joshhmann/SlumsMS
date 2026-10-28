# Shared helpers for tools/release (sourced, not run). Bash only; matches the style of the deploy scripts.
# Workstation scripts: build, stage, rehearse, publish-client, publish-launcher.
# Host scripts (run on 164, copied to /opt/mapleroot-deployments/v<version>/tools by stage): *-host.sh, verify-client.
RELEASE_HOST=${RELEASE_HOST:-root@192.168.0.164}
DEPLOY_ROOT=${DEPLOY_ROOT:-/opt/mapleroot-deployments}
HOST_NAME=${HOST_NAME:-maplestory}          # hostname of 164; host scripts refuse to run anywhere else
LEDGER_TABLE=mapleroot_integration_migrations
LEDGER_COL=migration_id

t0=${t0:-$(date +%s)}
has() { grep -c "$@" > /dev/null; }   # like grep -q, but reads all input (grep -q + pipefail = SIGPIPE false negatives)
step() { echo "[$(date -u +%H:%M:%S) +$(( $(date +%s) - t0 ))s] $*"; }
die() { echo "ABORT: $*" >&2; exit 1; }
warn() { echo "WARN: $*" >&2; }
# run: print the command; in dry-run mode only print it.
run() { if [ "${DRY_RUN:-0}" = 1 ]; then echo "  [dry-run] $*"; else "$@"; fi; }

valid_version() { [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?(-[A-Za-z0-9.]+)?$ ]]; }   # 1.6.0.1: a hotfix may add a fourth part
# The version a jar prints at boot comes from ServerConstants.SLUMSMS_VERSION.
source_version() { sed -n 's/.*SLUMSMS_VERSION *= *"\([^"]*\)".*/\1/p' "$1/src/main/java/constants/net/ServerConstants.java"; }
# Ledger id a migration file writes (001-preserve-arcane.sql -> 001-preserve-arcane-for-classic-fm).
ledger_id() { grep -o "$LEDGER_TABLE($LEDGER_COL) VALUES ('[^']*')" "$1" | sed "s/.*('\(.*\)')/\1/" | head -1; }
on_host() { [ "$(hostname)" = "$HOST_NAME" ]; }
require_host() { on_host || die "run this on the release host ($HOST_NAME), not $(hostname)"; }
require_workstation() { on_host && die "run this on the workstation, not on $HOST_NAME"; return 0; }

# rehearsal_cmd <ExecStart command> <login-port> <channel-base> <rehearsal-dir>
# Rewrites a copy of live's ExecStart for the rehearsal: scratch game ports, and every other fixed port or directory
# live uses (auth API port, dashboard, board in/outbox, market/site export, autoqa, revoked/weblogin) moved to a
# port/path under the rehearsal dir, so the copy neither collides with live nor writes into live's folders.
rehearsal_cmd() {
  local cmd=$1 login=$2 chbase=$3 r=$4 f
  cmd=$(printf '%s' "$cmd" | sed -E "s/-Dmapleroot.login-port=[0-9]+/-Dmapleroot.login-port=$login/; s/-Dmapleroot.channel-base-port=[0-9]+/-Dmapleroot.channel-base-port=$chbase/; s/ -Dslums.attackcapture=[a-z]+//")
  for f in slums.auth.port slums.auth.revoked slums.weblogin.dir solomapling.dashboard.dir slums.board.inbox \
           slums.board.outbox slums.board.state slums.market.dir slums.site.dir slums.autoqa.dir; do
    cmd=$(printf '%s' "$cmd" | sed -E "s# -D$f=[^ ]*##g")
  done
  local extra=" -Dslums.auth.port=$((login + 1000)) -Dslums.auth.revoked=$r/scratch/revoked.txt"
  extra+=" -Dslums.weblogin.dir=$r/scratch/weblogin -Dsolomapling.dashboard.dir=$r/scratch/dashboard"
  extra+=" -Dslums.board.inbox=$r/scratch/board/inbox -Dslums.board.outbox=$r/scratch/board/outbox"
  extra+=" -Dslums.board.state=$r/scratch/board/state.json -Dslums.market.dir=$r/scratch/market"
  extra+=" -Dslums.site.dir=$r/scratch/site -Dslums.autoqa.dir=$r/scratch/autoqa"
  case $cmd in *" -jar "*) printf '%s' "${cmd/ -jar /$extra -jar }";; *) printf '%s%s' "$cmd" "$extra";; esac
}
