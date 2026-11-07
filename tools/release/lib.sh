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
# The ledger id a migration writes: INSERT ... (migration_id) VALUES ('<id>'), or the guarded form
# INSERT ... (migration_id) SELECT '<id>' FROM DUAL WHERE ... (039-slums-stamps).
ledger_id() { grep -oE "$LEDGER_TABLE\($LEDGER_COL\) (VALUES \('[^']*'\)|SELECT '[^']*' FROM DUAL)" "$1" | sed -E "s/.*(VALUES \('|SELECT ')([^']*)'.*/\2/" | head -1; }
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
  for f in slums.auth.port slums.inspect.port slums.auth.revoked slums.weblogin.dir solomapling.dashboard.dir slums.board.inbox \
           slums.board.outbox slums.board.state slums.market.dir slums.site.dir slums.autoqa.dir; do
    cmd=$(printf '%s' "$cmd" | sed -E "s# -D$f=[^ ]*##g")
  done
  local extra=" -Dslums.auth.port=$((login + 1000)) -Dslums.inspect.port=$((login + 1001)) -Dslums.auth.revoked=$r/scratch/revoked.txt"
  extra+=" -Dslums.weblogin.dir=$r/scratch/weblogin -Dsolomapling.dashboard.dir=$r/scratch/dashboard"
  extra+=" -Dslums.board.inbox=$r/scratch/board/inbox -Dslums.board.outbox=$r/scratch/board/outbox"
  extra+=" -Dslums.board.state=$r/scratch/board/state.json -Dslums.market.dir=$r/scratch/market"
  extra+=" -Dslums.site.dir=$r/scratch/site -Dslums.autoqa.dir=$r/scratch/autoqa"
  case $cmd in *" -jar "*) printf '%s' "${cmd/ -jar /$extra -jar }";; *) printf '%s%s' "$cmd" "$extra";; esac
}

# ---- rehearsal receipt binds the staged bytes (P1-R1) ----
# The rehearsal records the sha256 of staging/SHA256SUMS ("staging digest", it names every staged file by hash) and of
# deploy.sh + rollback.sh ("scripts digest"). `release` and release-live.sh refuse to go live unless REHEARSAL.md says
# PASS AND both digests equal what the folder holds now, so a rehearsal of yesterday's staging cannot pass today's gate.
staging_digest() { sha256sum "$1/staging/SHA256SUMS" | cut -d' ' -f1; }
scripts_digest() { (cd "$1" && sha256sum deploy.sh rollback.sh | sha256sum | cut -d' ' -f1); }
# rehearsal_binding_lines <version dir>: the two receipt lines rehearse-host.sh writes (and rehearsal_gate reads).
rehearsal_binding_lines() {
  echo "- Staging digest: \`$(staging_digest "$1")\` (sha256 of staging/SHA256SUMS; this rehearsal is valid only for these staged bytes)"
  echo "- Scripts digest: \`$(scripts_digest "$1")\` (deploy.sh + rollback.sh)"
}
# rehearsal_gate <version dir>: 0 = REHEARSAL.md exists, says PASS (b323's exact check) and matches the staged bytes;
# otherwise prints why on stderr and returns 1.
rehearsal_gate() {
  local d=$1 r=$1/REHEARSAL.md want got
  [ -f "$r" ] || { echo "no REHEARSAL.md in $d: rehearse first (tools/release/rehearse)" >&2; return 1; }
  grep -q '\*\*Result: PASS\*\*' "$r" || { echo "REHEARSAL.md does not show Result: PASS: rehearse first" >&2; return 1; }
  [ -f "$d/staging/SHA256SUMS" ] && [ -f "$d/deploy.sh" ] && [ -f "$d/rollback.sh" ] \
    || { echo "$d lacks staging/SHA256SUMS, deploy.sh or rollback.sh" >&2; return 1; }
  want=$(sed -n 's/^- Staging digest: `\([0-9a-f]\{64\}\)`.*/\1/p' "$r" | head -1)
  [ -n "$want" ] || { echo "REHEARSAL.md has no staging digest (written by an older rehearse-host.sh, or edited): rerun tools/release/rehearse" >&2; return 1; }
  got=$(staging_digest "$d")
  [ "$want" = "$got" ] || { echo "rehearsal is for different staged bytes (stale or tampered): rerun tools/release/rehearse (receipt $want, staging now $got)" >&2; return 1; }
  (cd "$d/staging" && sha256sum -c --quiet SHA256SUMS >/dev/null 2>&1) \
    || { echo "rehearsal is for different staged bytes (stale or tampered): a staged file no longer matches SHA256SUMS; restage and rerun tools/release/rehearse" >&2; return 1; }
  want=$(sed -n 's/^- Scripts digest: `\([0-9a-f]\{64\}\)`.*/\1/p' "$r" | head -1)
  got=$(scripts_digest "$d")
  [ "$want" = "$got" ] || { echo "rehearsal is for different staged bytes (stale or tampered): deploy.sh/rollback.sh differ from the rehearsed ones; rerun tools/release/rehearse" >&2; return 1; }
}

# ---- extra steps (P1-R1) ----
# THE CONTRACT for a release's hand step (conf: EXTRA_STEPS=<script>, EXTRA_FILES=(<helpers>)). stage copies them flat
# into staging/extra/ (so SHA256SUMS, the rehearsal digest and the release gate cover them). It is run by ONE helper,
# run_extra_steps, from release-live.sh, `release` (both via tools/extra-steps-host.sh) and rehearse-host.sh:
#   cwd   = staging/extra/                         the script finds its helper files next to itself
#   env   = SRV         the server folder to change (live: the default below; rehearsal: the scratch copy)
#           WZ          $SRV/wz
#           SQL_CMD     eval'd command that reads SQL on stdin (live: the default below; rehearsal: the scratch schema)
#           ST          the staging folder; EXTRA_DIR this folder; REHEARSAL=1 in a rehearsal, 0 live
#   args  = none. Exit non-zero = the step failed (live: old version restarted; rehearsal: FAIL).
# It must NEVER hard-code a live path: use $SRV/$WZ/$SQL_CMD. extra_steps_lint refuses the live paths below.
# Files under wz/ it edits in place are listed in the conf as WZ_TARGETS (backed up by deploy.sh, restored by rollback.sh,
# and given a real COPY in the rehearsal); staged replacements are WZ_REPLACE="repo/file=Dir.wz/rel/path" (staging/wz/).
LIVE_SRV_DEFAULT="/opt/mapleroot/MapleRoot Full Repack/Server"   # keep equal to SRV in templates/deploy.body.sh
LIVE_SQL_CMD_DEFAULT='docker exec -i buns-clean-db-1 sh -c '\''mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N mapleroot'\'''   # same as SQL_CMD there
# extra_steps_lint <dir>: no live path, live container or live feed anywhere in the staged extra files.
extra_steps_lint() {
  local hits
  hits=$(grep -rIn -e '/opt/mapleroot/' -e '/opt/mapleroot-deployments/' -e 'buns-clean-db' -e '/srv/slums-patch' -e '/etc/systemd/system' "$1" || true)
  [ -z "$hits" ] || { echo "extra steps hard-code a live path (use \$SRV, \$WZ, \$SQL_CMD, \$EXTRA_DIR; see the contract in lib.sh):" >&2; echo "$hits" >&2; return 1; }
}
# run_extra_steps <staged extra dir> <output file>: env SRV, SQL_CMD, REHEARSAL (defaults: live, live, 0). Returns the
# script's exit code (output echoed and kept in the file); 2 = no script, 3 = lint, 4 = rehearsal pointed at live.
run_extra_steps() {
  local dir srv sql rc=0 rehearsal=${REHEARSAL:-0}
  dir=$(cd "$1" && pwd) || return 2
  srv=${SRV:-$LIVE_SRV_DEFAULT}; sql=${SQL_CMD:-$LIVE_SQL_CMD_DEFAULT}
  [ -f "$dir/extra-steps.sh" ] || { echo "no $dir/extra-steps.sh" >&2; return 2; }
  extra_steps_lint "$dir" || return 3
  if [ "$rehearsal" = 1 ] && [ "$srv" = "$LIVE_SRV_DEFAULT" ]; then echo "REHEARSAL=1 but SRV is live's server folder: refused" >&2; return 4; fi
  echo "extra steps: SRV=$srv WZ=$srv/wz REHEARSAL=$rehearsal"
  (cd "$dir" && env SRV="$srv" WZ="$srv/wz" SQL_CMD="$sql" ST="$(dirname "$dir")" EXTRA_DIR="$dir" REHEARSAL="$rehearsal" bash ./extra-steps.sh) > "$2" 2>&1 || rc=$?
  cat "$2"; echo "extra steps exit $rc"
  return "$rc"
}
# stage_extras <release.conf> <repo> <staging dir>: copy EXTRA_STEPS/EXTRA_FILES to staging/extra/, WZ_REPLACE files to
# staging/wz/, and write staging/wz-targets.txt (WZ_TARGETS + the WZ_REPLACE targets). Call before SHA256SUMS is made.
stage_extras() {
  ( set -euo pipefail
    EXTRA_STEPS="" EXTRA_FILES=() WZ_TARGETS=() WZ_REPLACE=()
    . "$1"
    local repo=$2 st=$3 f kv rel
    if [ -n "$EXTRA_STEPS" ]; then
      [ -f "$repo/$EXTRA_STEPS" ] || die "EXTRA_STEPS $EXTRA_STEPS: no such file in the repo"
      mkdir -p "$st/extra"; cp "$repo/$EXTRA_STEPS" "$st/extra/extra-steps.sh"
      for f in "${EXTRA_FILES[@]}"; do
        [ -e "$repo/$f" ] || die "EXTRA_FILES $f: no such file in the repo"
        [ ! -e "$st/extra/$(basename "$f")" ] || die "EXTRA_FILES: two files named $(basename "$f")"
        cp -r "$repo/$f" "$st/extra/"
      done
      bash -n "$st/extra/extra-steps.sh" || die "extra-steps.sh has a syntax error"
      extra_steps_lint "$st/extra" || die "EXTRA_STEPS/EXTRA_FILES hard-code a live path"
    else
      [ "${#EXTRA_FILES[@]}" = 0 ] || die "EXTRA_FILES without EXTRA_STEPS"
    fi
    : > "$st/.wz-targets.tmp"
    for rel in "${WZ_TARGETS[@]}"; do echo "$rel" >> "$st/.wz-targets.tmp"; done
    for kv in "${WZ_REPLACE[@]}"; do
      f=${kv%%=*}; rel=${kv#*=}; [ "$f" != "$kv" ] || die "WZ_REPLACE entry '$kv' is not repo/file=Dir.wz/rel/path"
      wz_target_ok "$rel" || die "WZ_REPLACE target '$rel' is not a plain relative path under wz/"
      [ -f "$repo/$f" ] || die "WZ_REPLACE $f: no such file in the repo"
      mkdir -p "$st/wz/$(dirname "$rel")"; cp "$repo/$f" "$st/wz/$rel"; echo "$rel" >> "$st/.wz-targets.tmp"
    done
    for rel in "${WZ_TARGETS[@]}"; do wz_target_ok "$rel" || die "WZ_TARGETS '$rel' is not a plain relative path under wz/"; done
    if [ -s "$st/.wz-targets.tmp" ]; then
      LC_ALL=C sort -u "$st/.wz-targets.tmp" > "$st/wz-targets.txt"
      [ -z "$(LC_ALL=C sort "$st/.wz-targets.tmp" | uniq -d)" ] || warn "a WZ target is listed twice"
    fi
    rm -f "$st/.wz-targets.tmp"
  )
}

# ---- WZ in the rehearsal (P1-R1) ----
# A WZ target is a path relative to wz/ (Map.wz/Map/Map2/272000000.img.xml): no leading /, no .., no empty parts.
wz_target_ok() { case $1 in ""|/*|..|../*|*/..|*/../*|*//*|./*|*/./*) return 1;; esac; [[ $1 =~ ^[A-Za-z0-9._+\ ()-]+(/[A-Za-z0-9._+\ ()-]+)*$ ]]; }
# rehearsal_wz_setup <live wz dir> <new wz dir> <targets file>: the scratch server's wz/. Directories are real, every file
# is a symlink to live's (read-only use), EXCEPT the target files, which are real COPIES (live's own bytes, mode kept),
# so a step that writes them can run without touching live. Never hard-links; refuses a target whose path goes through
# a symlink (a write would land in live).
rehearsal_wz_setup() {
  local src dst=$2 rel real exp
  src=$(cd "$1" && pwd -P) || return 1
  [ ! -e "$dst" ] || { echo "wz setup: $dst exists" >&2; return 1; }
  mkdir -p "$dst" && dst=$(cd "$dst" && pwd -P) || return 1
  cp -rs "$src/." "$dst/" || return 1
  while IFS= read -r rel || [ -n "$rel" ]; do
    [ -n "$rel" ] || continue
    wz_target_ok "$rel" || { echo "wz setup: bad target '$rel'" >&2; return 1; }
    mkdir -p "$dst/$(dirname "$rel")"
    exp=$dst/$(dirname "$rel"); [ "$(dirname "$rel")" != . ] || exp=$dst
    real=$(cd "$exp" && pwd -P)
    [ "$real" = "$exp" ] || { echo "wz setup: $rel passes through a symlink" >&2; return 1; }
    if [ -e "$src/$rel" ]; then
      [ -f "$src/$rel" ] || { echo "wz setup: $rel is not a regular file in live's wz" >&2; return 1; }
      rm -f "$dst/$rel"            # drop the symlink first: cp onto it would write through to live
      cp -p "$src/$rel" "$dst/$rel" && chmod u+w "$dst/$rel" || return 1
      [ ! -L "$dst/$rel" ] || return 1
    fi
  done < "$3"
}
# wz_hashes <wz dir> <targets file>: "<sha256|absent>  <rel>" per target, sorted as listed (compare before/after).
wz_hashes() { local rel; while IFS= read -r rel || [ -n "$rel" ]; do [ -n "$rel" ] || continue
  if [ -f "$1/$rel" ]; then echo "$(sha256sum < "$1/$rel" | cut -d' ' -f1)  $rel"; else echo "absent  $rel"; fi; done < "$2"; }
