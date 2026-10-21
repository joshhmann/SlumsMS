#!/bin/bash
# Host side (164) of tools/release/rehearse-release-flow: a scratch server + scratch patch feed for rehearsing
# tools/release/release end to end. Live is only read (the newest nightly dump, an rsync of the Server folder,
# manifests copied from /srv/slums-patch, the 1.4.1 staging/backup files). Nothing is written outside $R and the
# scratch schema in the TEST DB (3307).
#
#   rehearse-release-flow-host.sh setup | start-old | state <deploy-root-version-dir> | cleanup
#
#   setup      $R/Server: copy of live's Server/ (wz symlinked read-only; the template deploy has no WZ step) put back
#              to 1.4.0 with /opt/mapleroot-deployments/v1.4.1/backup/server-runtime.tgz; config.yaml pointed at the
#              scratch schema rh_flow (newest nightly dump); transient unit slumsms-rh-flow on 8519/8885+ (live's
#              ExecStart, CPUQuota=200% Nice=19 MemoryMax=6G).
#              $R/feed: scratch patch feed: manifest.json = live's, layer manifests copied as real dirs (no files: the
#              publisher only reads them), launcher-test/ with layers -> ../layers like live.
#              $R/staging: a copy of the reference-client hash cache (manifest-before-*.json land here). $R/bin/slumsms-monitor: a fake monitor that
#              records every call and the posts a real one would make ($R/posts.log). $R/env.sh: the overrides.
#   start-old  boot the scratch server (the old version) and wait for "is now online".
#   state DIR  one line: unit state, jar sha16, manifest version/client, launcher-test mirror equal?
#   cleanup    stop the unit, drop the schema and user, delete $R (except $R/receipt).
set -euo pipefail
R=/opt/mapleroot-dev/release-flow-rehearsal
SERVER_SRC="/opt/mapleroot/MapleRoot Full Repack/Server"
UNIT_SRC=/etc/systemd/system/slumsms.service
OLD_RUNTIME=/opt/mapleroot-deployments/v1.4.1/backup/server-runtime.tgz   # live as it was on 1.4.0
SCHEMA=rh_flow DBUSER=rh_flow UNIT=slumsms-rh-flow LOGIN_PORT=8519 CHANNEL_BASE=8885
ROOTCNF=/opt/mapleroot-dev/solomapling-test/secrets/root.cnf
TESTDB=(--defaults-extra-file=$ROOTCNF -h127.0.0.1 -P3307)
NIGHTLY=/opt/mapleroot/backups/nightly
LIVE_FEED=/srv/slums-patch
t0=$(date +%s); step() { echo "[$(date -u +%H:%M:%S) +$(( $(date +%s) - t0 ))s] $*"; }
has() { grep -c "$@" > /dev/null; }
die() { echo "ABORT: $*" >&2; exit 1; }
[ "$(hostname)" = maplestory ] || die "run on 164"
tsql() { mysql "${TESTDB[@]}" -N "$SCHEMA"; }

case ${1:-} in
setup)
  step "checks"
  [ ! -e "$R/Server" ] && [ ! -e "$R/feed" ] || die "$R has an earlier rehearsal (cleanup first)"
  mysql "${TESTDB[@]}" -N -e "SELECT 1" > /dev/null || die "test DB not reachable"
  [ -z "$(mysql "${TESTDB[@]}" -N -e "SHOW DATABASES LIKE '$SCHEMA'")" ] || die "schema $SCHEMA exists"
  ! systemctl is-active --quiet "$UNIT" || die "$UNIT is running"
  for p in $LOGIN_PORT $(seq "$CHANNEL_BASE" $((CHANNEL_BASE + 9))); do ! ss -ltn | has ":$p " || die "port $p in use"; done
  [ -f "$OLD_RUNTIME" ] || die "no $OLD_RUNTIME"
  mkdir -p "$R/receipt" "$R/bin" "$R/staging" "$R/deployments"

  step "DB: newest nightly dump -> $SCHEMA (test DB 3307)"
  src=$(ls -1t "$NIGHTLY"/mapleroot-*.sql.gz | head -1); echo "dump $src"
  zcat "$src" | tail -1 | has 'Dump completed' || die "dump incomplete"
  DBPASS=$(head -c 18 /dev/urandom | base64 | tr -d '/+=')
  mysql "${TESTDB[@]}" -e "CREATE DATABASE \`$SCHEMA\`; CREATE USER '$DBUSER'@'%' IDENTIFIED BY '$DBPASS'; GRANT ALL ON \`$SCHEMA\`.* TO '$DBUSER'@'%';"
  zcat "$src" | grep -vE '^(USE `mapleroot`;|CREATE DATABASE .*`mapleroot`)' | tsql
  echo "ledger: $(echo "SELECT GROUP_CONCAT(migration_id ORDER BY migration_id) FROM mapleroot_integration_migrations;" | tsql)"

  step "Server copy (old = 1.4.0 runtime from $OLD_RUNTIME)"
  mkdir -p "$R/Server"
  rsync -a --exclude /wz --exclude /logs --exclude /.git --exclude /reports "$SERVER_SRC/" "$R/Server/"
  ln -s "$SERVER_SRC/wz" "$R/Server/wz"
  rm -rf "$R/Server/scripts" "$R/Server/src/main" "$R/Server/cashshop"
  tar -xzf "$OLD_RUNTIME" -C "$R/Server"
  C=$R/Server/config.yaml
  sed -i -E \
    -e "s#^([[:space:]]*DB_HOST:).*(\r?)\$#\1 127.0.0.1\2#" \
    -e "s#^([[:space:]]*DB_URL_FORMAT:).*(\r?)\$#\1 \"jdbc:mysql://%s:3307/$SCHEMA\"\2#" \
    -e "s#^([[:space:]]*DB_USER:).*(\r?)\$#\1 $DBUSER\2#" \
    -e "s#^([[:space:]]*DB_PASS:).*(\r?)\$#\1 \"$DBPASS\"\2#" \
    -e "s#^([[:space:]]*BOT_LLM_BASE_URL:).*(\r?)\$#\1 \"\"\2#" \
    -e "s#^([[:space:]]*BOT_DECIDE_URL:).*(\r?)\$#\1 \"\"\2#" "$C"
  grep -qE "DB_URL_FORMAT: \"jdbc:mysql://%s:3307/$SCHEMA\"" "$C" && grep -qE "^[[:space:]]*DB_USER: $DBUSER" "$C" || die "config not pointed at $SCHEMA"
  echo "old jar: $(sha256sum "$R/Server/target/Cosmic.jar" | cut -c1-16)"
  cp "$UNIT_SRC" "$R/slumsms.service"
  cat > "$R/start.sh" <<S
#!/bin/bash
set -euo pipefail
cmd=\$(sed -n 's/^ExecStart=//p' "$R/slumsms.service" | tail -1)
cmd=\$(echo "\$cmd" | sed -E 's/-Dmapleroot.login-port=[0-9]+/-Dmapleroot.login-port=$LOGIN_PORT/; s/-Dmapleroot.channel-base-port=[0-9]+/-Dmapleroot.channel-base-port=$CHANNEL_BASE/; s/ -Dslums.attackcapture=[a-z]+//')
echo "\$cmd" | grep -q -- "-Dmapleroot.login-port=$LOGIN_PORT" || { echo "no port flags"; exit 1; }
systemctl reset-failed $UNIT 2>/dev/null || true
systemd-run --unit=$UNIT -p CPUQuota=200% -p Nice=19 -p MemoryMax=6G -p SuccessExitStatus=143 -p TimeoutStopSec=5min \\
  -p WorkingDirectory="$R/Server" \$cmd
S
  chmod +x "$R/start.sh"

  step "scratch feed $R/feed (from $LIVE_FEED, manifests only)"
  mkdir -p "$R/feed/launcher-test"
  cp "$LIVE_FEED/manifest.json" "$R/feed/manifest.json"
  python3 -I - "$R/feed/manifest.json" "$LIVE_FEED" "$R/feed" <<'PY'
import json, os, shutil, sys
m = json.load(open(sys.argv[1]))
for l in m["layers"]:
    d = os.path.join(sys.argv[3], "layers", l["name"], l["version"])
    os.makedirs(d)
    shutil.copy2(os.path.join(sys.argv[2], "layers", l["name"], l["version"], "manifest.json"), d)
    print("layer manifest", l["name"], l["version"])
PY
  ln -s ../layers "$R/feed/launcher-test/layers"
  cp "$R/feed/manifest.json" "$R/feed/launcher-test/manifest.json"
  cp /srv/slums-staging/reference-client.hashes.json "$R/staging/reference-client.hashes.json"

  step "fake monitor $R/bin/slumsms-monitor (records calls and the posts a real monitor would make)"
  cat > "$R/bin/slumsms-monitor" <<'M'
#!/bin/bash
# Fake slumsms-monitor for the release-flow rehearsal: same CLI for maintenance/client-ready; posts go to posts.log.
R=/opt/mapleroot-dev/release-flow-rehearsal
echo "$(date +%s.%N) $*" >> "$R/monitor-calls.log"
post() { echo "$(date +%s.%N) POST $*" >> "$R/posts.log"; echo "announce sent ($*)"; }
cmd=$1; shift
case $cmd in
  maintenance)
    if [ "${1:-}" = --help ]; then echo "usage: slumsms-monitor maintenance target {start,end} [--minutes N] [--reason R] [--no-announce] [--client VERSION] [--dry-run]"; exit 0; fi
    act=$2; shift 2; client="" dry=0 noann=0
    while [ $# -gt 0 ]; do case $1 in --client) client=$2; shift;; --dry-run) dry=1;; --no-announce) noann=1;; --minutes|--reason) shift;; esac; shift; done
    if [ "$dry" = 1 ]; then echo "[dry-run] maintenance $act"; exit 0; fi
    if [ "$act" = start ]; then
      if [ -e "$R/maintenance.on" ]; then echo "already in maintenance; not announced again"; exit 0; fi
      touch "$R/maintenance.on"; [ "$noann" = 1 ] || post "going-down"
    else
      was=0; [ -e "$R/maintenance.on" ] && was=1; rm -f "$R/maintenance.on"
      if [ "$was" = 0 ] && [ -z "$client" ]; then echo "was not in maintenance; nothing announced"; exit 0; fi
      [ "$noann" = 1 ] || post "back-up${client:+ +client $client}"
    fi;;
  client-ready) post "client-ready $2";;
  *) echo "fake monitor: $cmd ignored";;
esac
M
  chmod +x "$R/bin/slumsms-monitor"

  {
    printf 'SRV=%q\n' "$R/Server"
    printf 'JOURNAL_UNIT=%q\nUNIT_FILE=%q\n' "$UNIT" "$R/slumsms.service"
    printf 'SQL_CMD=%q\n' "mysql --defaults-extra-file=$ROOTCNF -h127.0.0.1 -P3307 -N $SCHEMA"
    printf 'DUMP_CMD=%q\n' "mysqldump --defaults-extra-file=$ROOTCNF -h127.0.0.1 -P3307 --lock-tables --routines --triggers $SCHEMA"
    printf 'STOP_CMD=%q\nSTART_CMD=%q\nIS_ACTIVE_CMD=%q\n' "systemctl stop $UNIT || true" "bash $R/start.sh" "systemctl is-active --quiet $UNIT"
    printf 'DAEMON_RELOAD_CMD=true\nLOGIN_PORT=%s\nCHANNEL_BASE=%s\n' "$LOGIN_PORT" "$CHANNEL_BASE"
    printf 'MONITOR=%q\nMONITOR_TARGET=rehearsal\n' "$R/bin/slumsms-monitor"
  } > "$R/env.sh"
  cat "$R/env.sh"
  step "setup done";;

start-old)
  ! systemctl is-active --quiet "$UNIT" || { echo "already running"; exit 0; }
  s0=$(date '+%Y-%m-%d %H:%M:%S'); bash "$R/start.sh"
  for i in $(seq 1 150); do journalctl -u "$UNIT" --since "$s0" --no-pager | has "is now online" && break; sleep 1; done
  journalctl -u "$UNIT" --since "$s0" --no-pager | grep -E "SlumsMS v|is now online" || die "old version not online";;

state)
  a=$(systemctl is-active "$UNIT" || true)
  j=$(sha256sum "$R/Server/target/Cosmic.jar" | cut -c1-16)
  m=$(python3 -I -c 'import json,sys; m=json.load(open(sys.argv[1])); print(m["version"]+" client="+[l["version"] for l in m["layers"] if l["name"]=="client"][0])' "$R/feed/manifest.json")
  t=$(cmp -s "$R/feed/manifest.json" "$R/feed/launcher-test/manifest.json" && echo same || echo DIFFERENT)
  echo "unit=$a jar=$j manifest=[$m] launcher-test=$t maintenance=$([ -e "$R/maintenance.on" ] && echo ON || echo off)";;

cleanup)
  systemctl stop "$UNIT" 2>/dev/null || true; systemctl reset-failed "$UNIT" 2>/dev/null || true
  mysql "${TESTDB[@]}" -e "DROP DATABASE IF EXISTS \`$SCHEMA\`; DROP USER IF EXISTS '$DBUSER'@'%';" || true
  find "$R" -mindepth 1 -maxdepth 1 ! -name receipt -exec rm -rf {} +
  echo "cleaned $R (kept $R/receipt)";;
*) die "usage: $0 setup|start-old|state|cleanup";;
esac
