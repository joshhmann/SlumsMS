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
echo "$HEAD" > $SRV/DEPLOYED_COMMIT
S=$(date -u +%H:%M:%S)
bash $BASE/start-test.sh >/dev/null 2>&1
for i in $(seq 1 90); do journalctl -u $UNIT --since "$S" | grep -q "Auto-QA on\|is now online" && break; sleep 5; done
journalctl -u $UNIT --since "$S" | grep -E "SlumsMS v|is now online|Population director started|Auto-QA on" | sed -E 's/^.{0,60}//' | cut -c1-140
E=$(journalctl -u $UNIT --since "$S" | grep -cE "ERROR|Exception" || true)
echo "boot ERROR/Exception lines: $E"
systemctl is-active $UNIT
[ "$E" = 0 ] || { echo "BOOT HAS ERRORS: read journalctl -u $UNIT; roll back with the files in $D" >&2; exit 1; }
