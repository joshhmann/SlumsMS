#!/bin/bash
# Ticket 48 soak tools driver, on LXC 125, for the dedicated soak server slums-soak (see README.md; settings in
# server/soak.env, installed as /opt/mapleroot-dev/soak-server/soak.env). Live is only read, by guard.sh.
#
#   soak.sh install                      copy tools to $D, compile LoadGen against the soak server's jar
#   soak.sh guard                        start the live guard (unit soak-guard; its first 300 s are the baseline)
#   soak.sh collect                      start the metrics collector on slums-soak (unit soak-collect)
#   soak.sh accounts <n>                 create soak_01..n / soak01..n on slums-soak and put them in the busy towns
#   soak.sh loadgen <run> <clients> <seconds> [whisperEverySec=30]   scripted players (unit soak-loadgen)
#   soak.sh analyze <run> [bucketMin] [since] [until]               markdown tables from the raw files
#   soak.sh stop                         stop loadgen, collector and guard (not the server: stop-soak.sh)
#   soak.sh cleanup-accounts             delete the soak_ accounts and everything that points at them
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
ENV=$here/soak.env; [ -f "$ENV" ] || ENV=$here/server/soak.env
. "$ENV"
DB="mysql ${TESTDB[*]} -N $SCHEMA"
cmd=${1:-}; shift || true
case $cmd in
  install)
    mkdir -p "$D" "$DATA"/{guard,collect,runs,receipts} "$D/bin"
    if [ "$here" != "$D" ]; then
      cp "$here"/{guard.sh,collect.py,analyze.py,LoadGen.java,place-chars.sql,soak.sh} "$D/"
      cp "$here"/server/{soak.env,start-soak.sh,stop-soak.sh,refresh-soak.sh,llm-mock.py} "$D/"
    fi
    chmod +x "$D"/*.sh "$D"/*.py
    nice -n 19 javac -nowarn -cp "$SRV/target/Cosmic.jar" -d "$D/bin" "$D/LoadGen.java"
    cp "$SRV/target/Cosmic.jar" "$D/bin/Cosmic-ro.jar"
    echo "installed in $D";;
  guard)
    systemctl reset-failed soak-guard 2>/dev/null || true
    systemd-run --unit=soak-guard -p Nice=10 -p CPUQuota=20% "$D/guard.sh" "$DATA/guard" --interval 30 --baseline 300 \
      --target-unit "$UNIT";;
  collect)
    systemctl reset-failed soak-collect 2>/dev/null || true
    systemd-run --unit=soak-collect -p Nice=10 -p CPUQuota=20% /usr/bin/python3 "$D/collect.py" "$DATA/collect" \
      --unit "$UNIT" --dashboard "$DATA/dashboard" --interval 30;;
  accounts)
    n=${1:?accounts <n>}
    nice -n 19 java -cp "$D/bin:$D/bin/Cosmic-ro.jar" LoadGen create 127.0.0.1 "$LOGIN_PORT" "$WORLD" "$n" "$DATA/runs/create"
    $DB < "$D/place-chars.sql";;
  loadgen)
    run=${1:?run name}; clients=${2:?clients}; secs=${3:?seconds}; wh=${4:-30}
    mkdir -p "$DATA/runs/$run"; echo "$clients" > "$DATA/guard/loadgen.clients"
    systemctl reset-failed soak-loadgen 2>/dev/null || true
    systemd-run --unit=soak-loadgen -p Nice=19 -p CPUQuota=100% -p MemoryMax=1G -p WorkingDirectory="$D" \
      /usr/bin/java -Xmx384m -XX:+UseSerialGC -Dsoak.ctl="$DATA/guard/loadgen.clients" -cp "$D/bin:$D/bin/Cosmic-ro.jar" \
      LoadGen run 127.0.0.1 "$LOGIN_PORT" "$WORLD" "$clients" "$DATA/runs/$run" "$SRV/wz" "$secs" "$wh";;
  analyze)
    run=${1:?run}; b=${2:-60}
    args=(--bucket-min "$b"); [ -n "${3:-}" ] && args+=(--since "$3"); [ -n "${4:-}" ] && args+=(--until "$4")
    nice -n 19 python3 "$D/analyze.py" "$DATA" "$run" "${args[@]}";;
  stop)
    for u in soak-loadgen soak-collect soak-guard; do systemctl stop "$u" 2>/dev/null || true; systemctl reset-failed "$u" 2>/dev/null || true; done;;
  cleanup-accounts)
    ! systemctl is-active --quiet soak-loadgen || { echo "stop the load generator first"; exit 1; }
    ids=$($DB -e "SELECT GROUP_CONCAT(c.id) FROM characters c JOIN accounts a ON a.id = c.accountid WHERE a.name LIKE 'soak\\_%' AND c.name LIKE 'soak%'")
    aids=$($DB -e "SELECT GROUP_CONCAT(id) FROM accounts WHERE name LIKE 'soak\\_%'")
    [ "$aids" != NULL ] || { echo "no soak accounts"; exit 0; }
    [ "$ids" != NULL ] || ids=0
    $DB -e "START TRANSACTION;
      DELETE e FROM inventoryequipment e JOIN inventoryitems i ON i.inventoryitemid = e.inventoryitemid WHERE i.characterid IN ($ids);
      DELETE FROM inventoryitems WHERE characterid IN ($ids);
      DELETE FROM keymap WHERE characterid IN ($ids);
      DELETE FROM medalmaps WHERE characterid IN ($ids);
      DELETE FROM questprogress WHERE characterid IN ($ids);
      DELETE FROM queststatus WHERE characterid IN ($ids);
      DELETE FROM skills WHERE characterid IN ($ids);
      DELETE FROM botmemory WHERE AboutCharacterId IN ($ids);
      DELETE FROM characters WHERE id IN ($ids);
      DELETE FROM hwidaccounts WHERE accountid IN ($aids);
      DELETE FROM monsterbook_stats WHERE accountid IN ($aids);
      DELETE FROM storages WHERE accountid IN ($aids);
      DELETE FROM accounts WHERE id IN ($aids);
      COMMIT;"
    echo "removed soak accounts ($aids) and characters ($ids)";;
  *) sed -n '2,14p' "$0"; exit 2;;
esac
