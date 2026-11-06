#!/bin/bash
# The TEST-host half of tools/ops/deploy-test, run there as `bash -s -- D HEAD MIGRATE BASE UNIT DBN` (P0-3: moved out
# of a heredoc so tools/ops/test_deploy_test_remote.sh can run it against a fixture). Never run by hand.
set -euo pipefail
D=$1; HEAD=$2; MIGRATE=$3
BASE=$4; SRV=$BASE/Server; UNIT=$5
DBC=mapleroot-integration-db; DBN=$6
case "${DEPLOY_TEST_FIXTURE:+fixture}$BASE:$UNIT:$DBN" in
 fixture*) ;;  # tools/ops/test_deploy_test_remote.sh only: a temp dir, fake systemctl/journalctl/docker
 /opt/mapleroot-dev/persistence-test:slums-persistence-test:mapleroot_persist|/opt/mapleroot-dev/bot-life-160:slums-life160:mapleroot_life160) ;;
 *) echo "refusing unknown test target" >&2; exit 1 ;;
esac
db() { docker exec -i $DBC sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B '"$DBN" 2>/dev/null; }

systemctl stop $UNIT || true
for i in $(seq 1 60); do systemctl is-active -q $UNIT || break; sleep 5; done
systemctl reset-failed $UNIT 2>/dev/null || true

cp -p $SRV/target/Cosmic.jar $D/Cosmic.jar.before
if [ -f $D/data.tgz ]; then
  (cd $SRV && tar tzf $D/data.tgz | xargs -r tar czf $D/data.before.tgz --ignore-failed-read 2>/dev/null || true)
  tar xzf $D/data.tgz -C $SRV
fi
while read -r f; do [ -n "$f" ] && [ -e "$SRV/$f" ] && { mkdir -p "$D/gone/$(dirname "$f")"; mv "$SRV/$f" "$D/gone/$f"; }; done < $D/deleted.txt

if [ "$MIGRATE" = 1 ] && ls $D/files/*.sql >/dev/null 2>&1; then
  docker exec $DBC sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction '"$DBN" 2>/dev/null | gzip > $D/db-before.sql.gz
  for m in $(ls $D/files/*.sql | sort); do
    id=$(basename "$m" .sql)
    have=$(echo "SELECT COUNT(*) FROM mapleroot_integration_migrations WHERE migration_id='$id';" | db || echo 0)
    if [ "$have" = 1 ]; then echo "migration $id already applied"; else echo "applying $id"; db < "$m"; fi
  done
fi

cp $D/Cosmic.jar $SRV/target/Cosmic.jar
S=$(date -u +%H:%M:%S)
bash $BASE/start-test.sh >/dev/null 2>&1
# P0-3: ready means the log says online (or Auto-QA on) while the unit is active. The journal is read into a variable:
# `journalctl | grep -q` under pipefail failed on journalctl's SIGPIPE even when the line was there, so the old loop
# could neither succeed early nor fail. A failed unit stops the wait at once; a stall times out. Either way the
# deploy fails and DEPLOYED_COMMIT keeps naming the last proven commit.
ready=0
for i in $(seq 1 90); do
  log=$(journalctl -u $UNIT --since "$S" 2>/dev/null || true)
  if systemctl is-active -q $UNIT; then
    case "$log" in *"is now online"*|*"Auto-QA on"*) ready=1; break ;; esac
  elif [ "$i" -gt 2 ]; then
    break  # the unit is not running any more: a failed boot
  fi
  sleep 5
done
log=$(journalctl -u $UNIT --since "$S" 2>/dev/null || true)
printf '%s\n' "$log" | grep -E "SlumsMS v|is now online|Population director started|Auto-QA on|SEVERE" | sed -E 's/^.{0,60}//' | cut -c1-140 || true
E=$(printf '%s\n' "$log" | grep -cE "ERROR|Exception" || true)
echo "boot ERROR/Exception lines: $E"
systemctl is-active $UNIT || true
[ "$ready" = 1 ] || { echo "TEST DID NOT COME UP (no 'is now online' while active): read journalctl -u $UNIT; DEPLOYED_COMMIT unchanged; roll back with the files in $D" >&2; exit 1; }
[ "$E" = 0 ] || { echo "BOOT HAS ERRORS: read journalctl -u $UNIT; DEPLOYED_COMMIT unchanged; roll back with the files in $D" >&2; exit 1; }
echo "$HEAD" > $SRV/DEPLOYED_COMMIT
