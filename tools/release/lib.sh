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

valid_version() { [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?(-[A-Za-z0-9.]+)?$ ]]; }   # a hotfix may add a fourth part
# The version a jar prints at boot comes from ServerConstants.SLUMSMS_VERSION.
source_version() { sed -n 's/.*SLUMSMS_VERSION *= *"\([^"]*\)".*/\1/p' "$1/src/main/java/constants/net/ServerConstants.java"; }
# Ledger id a migration file writes (001-preserve-arcane.sql -> 001-preserve-arcane-for-classic-fm).
ledger_id() { grep -o "$LEDGER_TABLE($LEDGER_COL) VALUES ('[^']*')" "$1" | sed "s/.*('\(.*\)')/\1/" | head -1; }
on_host() { [ "$(hostname)" = "$HOST_NAME" ]; }
require_host() { on_host || die "run this on the release host ($HOST_NAME), not $(hostname)"; }
require_workstation() { on_host && die "run this on the workstation, not on $HOST_NAME"; return 0; }
