#!/bin/bash
# Ticket 48 capacity soak driver, on LXC 125. Target: the owner's test server slums-persistence-test (login 8489,
# schema mapleroot_persist on the test DB 3307), started by its start-test.sh with GC logging and the dashboard
# exporter writing into $S (see docs/perf/capacity-soak-2026-10.md). Live is only read, by guard.sh.
#
#   soak.sh install                       copy the tools to $S and compile LoadGen against a copy of the target's jar
#   soak.sh guard                         start the live guard (unit soak-guard; first 300 s are the baseline)
#   soak.sh collect                       start the metrics collector (unit soak-collect)
#   soak.sh accounts <n>                  create soak_01..n / soak01..n and put them in the busy towns
#   soak.sh loadgen <run> <clients> <seconds> [whisperEverySec=20]   start the load generator (unit soak-loadgen)
#   soak.sh stop                          stop loadgen, collector and guard
#   soak.sh cleanup-accounts              delete the soak_ accounts, their characters and rows that point at them
#   soak.sh restore-target                put back the target's pre-soak start-test.sh (takes effect on its next start)
set -euo pipefail
S=${SOAK_DIR:-/opt/mapleroot-dev/soak-t48}
T=/opt/mapleroot-dev/persistence-test
here=$(cd "$(dirname "$0")" && pwd)
DB="mysql --defaults-extra-file=/opt/mapleroot-dev/solomapling-test/secrets/root.cnf -h127.0.0.1 -P3307 -N mapleroot_persist"
cmd=${1:-}; shift || true
case $cmd in
  install)
    mkdir -p "$S"/{guard,gc,dashboard,bin,runs,receipts}
    [ "$here" = "$S" ] || for f in guard.sh collect.py LoadGen.java place-chars.sql soak.sh analyze.py; do [ -f "$here/$f" ] && cp "$here/$f" "$S/"; done
    chmod +x "$S"/*.sh "$S"/*.py
    cp "$T/Server/target/Cosmic.jar" "$S/Cosmic-ro.jar"
    nice -n 19 javac -nowarn -cp "$S/Cosmic-ro.jar" -d "$S/bin" "$S/LoadGen.java"
    echo "installed in $S";;
  guard)
    systemd-run --unit=soak-guard -p Nice=10 -p CPUQuota=20% "$S/guard.sh" "$S/guard" --interval 30 --baseline 300;;
  collect)
    systemd-run --unit=soak-collect -p Nice=10 -p CPUQuota=20% /usr/bin/python3 "$S/collect.py" "$S/collect" --interval 30;;
  accounts)
    n=${1:?accounts <n>}
    nice -n 19 java -cp "$S/bin:$S/Cosmic-ro.jar" LoadGen create 127.0.0.1 8489 0 "$n" "$S/runs/create"
    $DB < "$S/place-chars.sql";;
  loadgen)
    run=${1:?run name}; clients=${2:?clients}; secs=${3:?seconds}; wh=${4:-20}
    mkdir -p "$S/runs/$run"; echo "$clients" > "$S/guard/loadgen.clients"
    systemctl reset-failed soak-loadgen 2>/dev/null || true
    systemd-run --unit=soak-loadgen -p Nice=19 -p CPUQuota=100% -p MemoryMax=1G -p WorkingDirectory="$S" \
      /usr/bin/java -Xmx384m -XX:+UseSerialGC -Dsoak.ctl="$S/guard/loadgen.clients" -cp "$S/bin:$S/Cosmic-ro.jar" \
      LoadGen run 127.0.0.1 8489 0 "$clients" "$S/runs/$run" "$T/Server/wz" "$secs" "$wh";;
  stop)
    for u in soak-loadgen soak-collect soak-guard; do systemctl stop "$u" 2>/dev/null || true; systemctl reset-failed "$u" 2>/dev/null || true; done;;
  cleanup-accounts)
    ! systemctl is-active --quiet soak-loadgen || { echo "stop the load generator first"; exit 1; }
    $DB <<'Q'
SET @ids := (SELECT GROUP_CONCAT(c.id) FROM characters c JOIN accounts a ON a.id = c.accountid WHERE a.name LIKE 'soak\_%');
SELECT 'soak accounts', COUNT(*) FROM accounts WHERE name LIKE 'soak\_%' AND loggedin = 0;
SELECT 'soak characters', @ids;
Q
    ids=$($DB -e "SELECT GROUP_CONCAT(c.id) FROM characters c JOIN accounts a ON a.id = c.accountid WHERE a.name LIKE 'soak\\_%'")
    [ -n "$ids" ] && [ "$ids" != NULL ] || { echo "no soak characters"; exit 0; }
    # every table with a character-id column, plus bot memory about these players
    tables=$($DB -e "SELECT CONCAT(TABLE_NAME,'.',COLUMN_NAME) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='mapleroot_persist' AND COLUMN_NAME IN ('characterid','charid','playerid','player_id','cid') AND TABLE_NAME <> 'characters'")
    for tc in $tables; do t=${tc%.*}; c=${tc#*.}
      n=$($DB -e "SELECT COUNT(*) FROM \`$t\` WHERE \`$c\` IN ($ids)"); [ "$n" = 0 ] && continue
      echo "delete $n from $t.$c"; $DB -e "DELETE FROM \`$t\` WHERE \`$c\` IN ($ids)"; done
    $DB -e "DELETE FROM characters WHERE id IN ($ids); DELETE FROM accounts WHERE name LIKE 'soak\\_%';"
    echo "removed soak accounts and characters ($ids)";;
  restore-target)
    cp -p "$T/start-test.sh.pre-t48" "$T/start-test.sh"; echo "restored $T/start-test.sh (no restart done)";;
  *) sed -n '2,16p' "$0"; exit 2;;
esac
