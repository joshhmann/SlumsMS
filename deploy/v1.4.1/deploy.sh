#!/bin/bash
# SlumsMS 1.4.1 live deploy (live runs 1.4.0 on Bera). Stops on the first error; every check that can fail runs
# BEFORE the stop.
#
#   bash /opt/mapleroot-deployments/v1.4.1/deploy.sh --dry-run   # pre-checks against live; changes nothing
#   bash /opt/mapleroot-deployments/v1.4.1/deploy.sh
#
# What changes (DEPLOY-NOTES.md has the table):
#   jar 1.4.1; scripts and src/main data; cashshop/catalog.tsv (10,363 rows: the Coloring Prism 5782000 is sold
#   again, the newer pet equips stay hidden);
#   config.yaml server: COLOR_PRISM_ENABLED: true (added in place; DAILY_CHECKIN_ENABLED stays true; CHANNEL_LOAD,
#   the worlds block and its 8 channels do not change);
#   server WZ: tools/data-align/portal-fixes.tsv (wz_set_values.py --column new_value: 271010400 north00 -> out00,
#   1 value in 1 file). The file is backed up first (backup/wz-before.tgz).
# No migrations: the ledger must already be 001-010 (1.4.0); the script refuses otherwise (010 = the prism tables).
# The unit, its flags (no -Dslums.qa / -Dslums.attackcapture: the !qa suite stays unregistered), the ports
# (8485, 7585-7592) do not change. Rollback: rollback.sh here.
#
# The rehearsal ran this same file on a copy of live; it overrides SRV, B, LOG, RECEIPT, SQL_CMD, DUMP_CMD,
# STOP_CMD, START_CMD, IS_ACTIVE_CMD, JOURNAL_UNIT, LOGIN_PORT, CHANNEL_BASE and MONITOR. Live uses the defaults.
set -euo pipefail
DRY_RUN=0
for a in "$@"; do case $a in --dry-run) DRY_RUN=1;; *) echo "usage: $0 [--dry-run]"; exit 2;; esac; done
VERSION=1.4.1
D=${D:-/opt/mapleroot-deployments/v1.4.1}
ST=${ST:-$D/staging}
JAR_SHA16=4f2d879c8e3dc179
COMMIT=b4c59e0ff
OLD_JAR_SHA16=218111b2bd784074   # live 1.4.0 (tag v1.4.0 = 9a3c9d1e8)
LEDGER="001-preserve-arcane-for-classic-fm,002-classic-fm-return,003-solomapling-bots,004-daily-checkin,005-bot-real-stats,006-bot-yield-caps,007-bot-starter-stash,008-world-bera,009-gm-audit,010-color-prism"
PRISM_TABLES=(equip_tints character_look_tints)
FLAGS=(DAILY_CHECKIN_ENABLED COLOR_PRISM_ENABLED)   # DAILY_CHECKIN_ENABLED is already true (1.4.0): only the prism changes
CATALOG_ROWS=10363
EXPECT_LOG=("SlumsMS v$VERSION (MapleStory v83) starting up" "Starting world 1 (Bera)" "Finished loading world 1"
            "is now online" "SoloMapling data: 24/24 resolved" "Cash Shop catalog: $CATALOG_ROWS rows")
FORBID_LOG=("Starting world 0" "failed to load" "migration 010" "Coloring Prism stays off" "Could not check for the Coloring Prism tables"
            "built-in reward table" "SoloMapling data file not found" "Cash Shop catalog line")
SMOKE_SECONDS=${SMOKE_SECONDS:-30}

SRV=${SRV:-"/opt/mapleroot/MapleRoot Full Repack/Server"}
B=${B:-$D/backup}
if [ "$DRY_RUN" = 1 ]; then LOG=${LOG:-$D/deploy-dryrun.log}; else LOG=${LOG:-$D/deploy.log}; fi
RECEIPT=${RECEIPT:-$D/RECEIPT.md}
JOURNAL_UNIT=${JOURNAL_UNIT:-slumsms}
UNIT_FILE=${UNIT_FILE:-/etc/systemd/system/slumsms.service}
SQL_CMD=${SQL_CMD:-"docker exec -i buns-clean-db-1 sh -c 'mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -N mapleroot'"}
DUMP_CMD=${DUMP_CMD:-"docker exec buns-clean-db-1 sh -c 'mysqldump -uroot -p\"\$MYSQL_ROOT_PASSWORD\" --lock-tables --routines --triggers mapleroot'"}
STOP_CMD=${STOP_CMD:-"systemctl stop slumsms"}
START_CMD=${START_CMD:-"systemctl start slumsms"}
IS_ACTIVE_CMD=${IS_ACTIVE_CMD:-"systemctl is-active --quiet slumsms"}
LOGIN_PORT=${LOGIN_PORT:-8485}
CHANNEL_BASE=${CHANNEL_BASE:-7585}
ONLINE_TIMEOUT=${ONLINE_TIMEOUT:-120}
# Maintenance mode + the public "going down" / "back up" post as Cody (slumsms-monitor, tickets 42/46). Soft: a
# missing monitor, or one without the `maintenance` command, never stops the deploy. The rehearsal sets MONITOR="".
MONITOR=${MONITOR-/usr/local/bin/slumsms-monitor}
MONITOR_TARGET=${MONITOR_TARGET:-live}
MAINT_MINUTES=${MAINT_MINUTES:-5}
MAINT_REASON=${MAINT_REASON:-"SlumsMS 1.4.1: the Coloring Prism is back, Fredrick and Rosen fixed, guild creation fixed, smarter bots."}

exec > >(tee -a "$LOG") 2>&1
t0=$(date +%s); step() { echo "[$(date -u +%H:%M:%S) +$(( $(date +%s) - t0 ))s] $*"; }
has() { grep -c "$@" > /dev/null; }   # like grep -q, but reads all input (grep -q + pipefail = SIGPIPE false negatives)
sql() { eval "$SQL_CMD" 2>&1 | { grep -v 'Using a password' || true; }; }
die() { echo "ABORT: $*"; exit 1; }
down() { echo "ABORT: $*"; echo "ABORT: the server is DOWN - run: bash $D/rollback.sh"
  [ -n "$MONITOR" ] && echo "(maintenance mode stays on: rollback.sh ends it, or: $MONITOR maintenance $MONITOR_TARGET end)"
  exit 1; }
maint() {   # maint start|end [--dry-run]: never fails the script
  if [ -z "$MONITOR" ] || [ ! -x "$MONITOR" ]; then echo "(no slumsms-monitor${MONITOR:+ at $MONITOR}: maintenance $1 skipped)"; return 0; fi
  if ! has -F 'def cmd_maintenance' "$MONITOR"; then
    echo "(the installed slumsms-monitor has no maintenance command: no public post; install deploy/monitor first to get one)"; return 0; fi
  local extra=()
  if [ "$1" = start ]; then extra=(--minutes "$MAINT_MINUTES"); if [ -n "$MAINT_REASON" ]; then extra+=(--reason "$MAINT_REASON"); fi; fi
  timeout 60 "$MONITOR" maintenance "$MONITOR_TARGET" "$@" "${extra[@]}" || echo "WARN: slumsms-monitor maintenance $1 failed (carrying on)"
}
now() { date +%s.%N; }
dur() { awk -v a="$1" -v b="$2" 'BEGIN{printf "%.1f", b-a}'; }
ledger() { echo "SELECT IFNULL(GROUP_CONCAT(migration_id ORDER BY migration_id),'') FROM mapleroot_integration_migrations;" | sql | tail -1; }
table_count() { echo "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ($(printf "'%s'," "$@" | sed 's/,$//'));" | sql | tail -1; }
C="$SRV/config.yaml"
W="$SRV/wz"
TOOLS=$ST/tools   # tools/data-align from the release commit
PORTALTSV=$TOOLS/data-align/portal-fixes.tsv
wzset() { python3 "$TOOLS/data-align/wz_set_values.py" --wz "$W" --tsv "$PORTALTSV" --column new_value "$@"; }
declare -A T

# config.yaml flags: each key is set to true in place (present and false), or added as the first key under the one
# top-level server: line with the file's indent and newline style (absent). A parse then checks that only these keys
# changed. --check only reports. Never touches anything above server: (the worlds block).
flags() {
  python3 - "$1" "$C" "${FLAGS[@]}" <<'PY'
import re, sys, yaml
mode, p, keys = sys.argv[1], sys.argv[2], sys.argv[3:]
s = open(p, newline='').read()
c = yaml.safe_load(s)
assert isinstance(c, dict) and isinstance(c.get("server"), dict), "config.yaml has no server: mapping"
todo = [k for k in keys if c["server"].get(k) is not True]
for k in keys:
    v = c["server"].get(k, "<absent>")
    assert v in (True, False, "<absent>"), "%s has an unexpected value %r" % (k, v)
    print("  %s: %s" % (k, v))
if mode == "--check" or not todo:
    print("  to set: " + (", ".join(todo) or "nothing"))
    sys.exit(0)
nl = "\r\n" if "\r\n" in s else "\n"
lines = s.split(nl)
hdr = [i for i, l in enumerate(lines) if re.match(r"^server:\s*(#.*)?$", l)]
assert len(hdr) == 1, "expected exactly one top-level server: line, found %d" % len(hdr)
h = hdr[0]
end = next((i for i in range(h + 1, len(lines)) if re.match(r"^\S", lines[i]) and not lines[i].startswith("#")), len(lines))
indent = None
for l in lines[h + 1:end]:
    m = re.match(r"^([ \t]+)[A-Za-z_]", l)
    if m: indent = m.group(1); break
assert indent, "could not find the indent of keys under server:"
for k in todo:
    if k in c["server"]:
        hits = [i for i in range(h + 1, end) if re.match(r"^[ \t]+%s\s*:" % k, lines[i])]
        assert len(hits) == 1, "expected one %s line under server:, found %d" % (k, len(hits))
        m = re.match(r"^([ \t]+%s\s*:\s*)(\S+)(\s*(#.*)?)$" % k, lines[hits[0]])
        assert m and yaml.safe_load(m.group(2)) is False, "unexpected %s line: %r" % (k, lines[hits[0]])
        lines[hits[0]] = m.group(1) + "true" + m.group(3)
        print("  changed %s -> true (line %d)" % (k, hits[0] + 1))
    else:
        lines.insert(h + 1, indent + k + ": true")
        end += 1
        print("  added %s: true under server:" % k)
out = nl.join(lines)
c2 = yaml.safe_load(out)
assert all(c2["server"][k] is True for k in keys)
assert {k: v for k, v in c2["server"].items() if k not in keys} == {k: v for k, v in c["server"].items() if k not in keys}, "server block changed"
assert {k: v for k, v in c2.items() if k != "server"} == {k: v for k, v in c.items() if k != "server"}, "other blocks changed"
open(p + ".new", "w", newline='').write(out)
PY
}

echo "== SlumsMS $VERSION deploy$([ "$DRY_RUN" = 1 ] && echo ' (DRY RUN: pre-checks only, nothing is stopped or changed)') =="
echo "staging $ST (commit $COMMIT, jar $JAR_SHA16); server $SRV"
step "pre-checks (live still up)"
(cd "$ST" && sha256sum -c --quiet SHA256SUMS) || die "staged files do not match SHA256SUMS"
[ "$(sha256sum "$ST/Cosmic.jar" | cut -c1-16)" = "$JAR_SHA16" ] || die "staged jar is not $JAR_SHA16"
[ -f "$C" ] && [ -f "$SRV/target/Cosmic.jar" ] && [ -d "$SRV/scripts" ] && [ -d "$SRV/src/main" ] && [ -d "$W/Map.wz" ] \
  || die "server folder is missing config.yaml, target/Cosmic.jar, scripts/, src/main/ or wz/Map.wz"
[ ! -L "$W" ] || die "$W is a symlink (a rehearsal copy pointing at live's WZ?): the WZ step would write through it"
old=$(sha256sum "$SRV/target/Cosmic.jar" | cut -c1-16)
[ "$old" = "$OLD_JAR_SHA16" ] || die "the installed jar is $old, not 1.4.0's $OLD_JAR_SHA16: this deploy is for a 1.4.0 server"
command -v python3 > /dev/null && python3 -c 'import yaml' || die "python3 with yaml is needed"
[ -f "$ST/catalog.tsv" ] && [ -f "$PORTALTSV" ] && [ -f "$TOOLS/data-align/wz_set_values.py" ] || die "staging lacks catalog.tsv or the WZ tool"
[ "$(grep -cvE '^(#|\s*$)' "$ST/catalog.tsv")" = "$CATALOG_ROWS" ] || die "staged catalog.tsv is not $CATALOG_ROWS rows"
# The catalogue sells 5782000: the server WZ must have the prism item (installed by 1.4.0's deploy)
[ -f "$W/Item.wz/Cash/0578.img.xml" ] && has -F '<imgdir name="5782000"' "$W/String.wz/Cash.img.xml" \
  || die "server WZ lacks the Coloring Prism item (Item.wz/Cash/0578.img.xml / String.wz 5782000): 1.4.0's WZ step missing?"
echo "config.yaml flags now:"; flags --check || die "config.yaml check failed"
echo "WZ: portal fixes (dry run):"
pw=$(wzset 2>&1) || { echo "$pw"; die "portal-fixes.tsv: a row neither holds its server_value nor its new_value (WZ moved on?)"; }
echo "  $pw"
echo "$pw" | has -E "^dry run: [01] value\(s\) in [01] file\(s\), 0 skipped$" || die "unexpected portal-fixes dry run: $pw"
l=$(ledger); echo "ledger: $l"
[ "$l" = "$LEDGER" ] || die "migration ledger is not 1.4.0's '$LEDGER' (010-color-prism missing?); 1.4.1 has no migrations of its own"
[ "$(table_count "${PRISM_TABLES[@]}")" = "${#PRISM_TABLES[@]}" ] || die "the prism tables ${PRISM_TABLES[*]} are not both there"
echo "SELECT 'characters by world', world, COUNT(*) FROM characters GROUP BY world;" | sql
echo "SELECT 'players online', COUNT(*) FROM accounts WHERE loggedin > 0;" | sql
[ ! -e "$B/mapleroot.sql.gz" ] || die "$B already holds a backup (an earlier run?). Move it away first; never overwrite a backup"
[ "$DRY_RUN" = 1 ] || mkdir -p "$B"   # a dry run creates nothing (an existing backup/ marks a deployed version)
free_mb=$(df -Pm "$(dirname "$B")" | awk 'NR==2{print $4}')
[ "$free_mb" -gt 4096 ] || die "only ${free_mb} MB free for the backup"
echo "old jar: $old  new jar: $JAR_SHA16"
eval "$IS_ACTIVE_CMD" && echo "server is running (will be stopped)" || echo "server is NOT running now"

if [ "$DRY_RUN" = 1 ]; then
  step "DRY RUN OK: every pre-check passed. A real run would now:"
  echo "  maintenance on + public post; stop ($STOP_CMD)"
  echo "  back up DB (--lock-tables), jar/config/scripts/src/main/cashshop, the WZ file(s) portal-fixes.tsv names, unit -> $B"
  echo "  migrations: none (ledger stays 001-010)"
  echo "  config.yaml: ${FLAGS[*]} -> true"
  echo "  WZ: wz_set_values.py --tsv portal-fixes.tsv --column new_value --apply"
  echo "  install Cosmic.jar ($JAR_SHA16), scripts.tar ($(wc -l < "$ST/scripts.list") files), srcdata.tar ($(wc -l < "$ST/srcdata.list") files), cashshop/catalog.tsv ($CATALOG_ROWS rows)"
  echo "  start ($START_CMD), wait for 'is now online', maintenance off, smoke test ${SMOKE_SECONDS}s, receipt $RECEIPT"
  maint start --dry-run
  exit 0
fi

step "maintenance mode on + public notice (back in about $MAINT_MINUTES min)"
maint start
step "stop"
T[stop0]=$(now)
eval "$STOP_CMD"
for i in $(seq 1 300); do eval "$IS_ACTIVE_CMD" || break; sleep 1; done
eval "$IS_ACTIVE_CMD" && down "server still active after stop"
T[stop1]=$(now)

step "backup: DB with table locks, runtime, WZ files, unit"
eval "$DUMP_CMD" 2>/dev/null | gzip > "$B/mapleroot.sql.gz"
gzip -t "$B/mapleroot.sql.gz" && [ "$(zcat "$B/mapleroot.sql.gz" | tail -1 | grep -c 'Dump completed')" = 1 ] || down "DB backup is incomplete"
ls -la "$B/mapleroot.sql.gz"
tar -czf "$B/server-runtime.tgz" -C "$SRV" target/Cosmic.jar config.yaml scripts src/main $([ -d "$SRV/cashshop" ] && echo cashshop)
tar -tzf "$B/server-runtime.tgz" > /dev/null || down "runtime backup unreadable"
# WZ: every file the portal TSV names, as it is now
( cd "$W" && awk -F'\t' '!/^#/ && $1 != "file" && NF > 1 {print $1}' "$PORTALTSV" | sort -u \
    | while read -r f; do if [ -f "$f" ]; then echo "$f"; fi; done ) > "$B/wz-before.list"
tar -czf "$B/wz-before.tgz" -C "$W" -T "$B/wz-before.list"
[ "$(tar -tzf "$B/wz-before.tgz" | wc -l)" = "$(wc -l < "$B/wz-before.list")" ] || down "WZ backup incomplete"
echo "WZ backup: $(wc -l < "$B/wz-before.list") file(s)"
cp "$C" "$B/config.yaml"
[ -f "$UNIT_FILE" ] && cp "$UNIT_FILE" "$B/slumsms.service" || true
sha256sum "$SRV/target/Cosmic.jar" > "$B/old-jar.sha256"
echo "$LEDGER" > "$B/ledger-before.txt"
T[bak1]=$(now)

step "migrations: none in 1.4.1 (ledger check)"
l=$(ledger); [ "$l" = "$LEDGER" ] || down "ledger is '$l', expected '$LEDGER'"
echo "ledger: $l"
T[mig1]=$(now)

step "config.yaml: ${FLAGS[*]} -> true"
flags --apply || down "config.yaml edit failed"
if [ -f "$C.new" ]; then mv "$C.new" "$C"; fi
python3 -c 'import sys,yaml; s=yaml.safe_load(open(sys.argv[1]))["server"]; assert all(s[k] is True for k in sys.argv[2:]); print("  parse ok:", ", ".join(k+"="+str(s[k]) for k in sys.argv[2:]))' "$C" "${FLAGS[@]}" \
  || down "config.yaml flags not true after the edit"

step "WZ: portal fixes (portal-fixes.tsv, new_value)"
wzset --apply | tail -2
n=$(wzset 2>&1) || down "portal fixes re-check failed: $n"
echo "  re-check: $n"
echo "$n" | has -E '^dry run: 0 value\(s\) in 0 file\(s\), 0 skipped$' || down "portal values still pending after --apply"

step "install jar, scripts, src/main data, cash shop catalogue"
cp "$ST/Cosmic.jar" "$SRV/target/Cosmic.jar"
tar -xf "$ST/scripts.tar" -C "$SRV"
tar -xf "$ST/srcdata.tar" -C "$SRV"
mkdir -p "$SRV/cashshop"; cp "$ST/catalog.tsv" "$SRV/cashshop/catalog.tsv"
cmp -s "$ST/Cosmic.jar" "$SRV/target/Cosmic.jar" || down "staged jar did not land"
echo "installed jar $(sha256sum "$SRV/target/Cosmic.jar" | cut -c1-16), catalog $(grep -cvE '^(#|\s*$)' "$SRV/cashshop/catalog.tsv") lines"
T[stage1]=$(now)

step "start"
START_TS=$(date '+%Y-%m-%d %H:%M:%S')
eval "$START_CMD"
online=0
for i in $(seq 1 "$ONLINE_TIMEOUT"); do
  journalctl -u "$JOURNAL_UNIT" --since "$START_TS" --no-pager | has "is now online" && { online=1; break; }
  sleep 1
done
T[up]=$(now)
J() { journalctl -u "$JOURNAL_UNIT" --since "$START_TS" --no-pager; }
J | grep -E "SlumsMS v|Starting world|Finished loading world|Listening on port|is now online|Cash Shop catalog|SoloMapling data|Daily Check-In|Set items|automatic population|failed to load" || true
[ "$online" = 1 ] || { J | tail -40; down "not online after ${ONLINE_TIMEOUT}s"; }
echo "downtime (stop -> online): $(dur "${T[stop0]}" "${T[up]}") s"
maint end

step "smoke test (${SMOKE_SECONDS}s)"
sleep "$SMOKE_SECONDS"
fail=()
for s in "${EXPECT_LOG[@]}"; do if J | has -F -- "$s"; then echo "ok   log has '$s'"; else echo "FAIL log lacks '$s'"; fail+=("missing '$s'"); fi; done
for s in "${FORBID_LOG[@]}"; do n=$(J | grep -cF -- "$s" || true); if [ "$n" = 0 ]; then echo "ok   no '$s'"; else echo "FAIL $n x '$s'"; fail+=("$n x '$s'"); fi; done
errs=$(J | grep -cE 'ERROR|Exception' || true); echo "ERROR/Exception lines: $errs"
[ "$errs" = 0 ] || { J | grep -E 'ERROR|Exception' | head -10; fail+=("$errs ERROR/Exception lines"); }
for p in $LOGIN_PORT $CHANNEL_BASE; do
  if ss -ltn | has ":$p "; then echo "ok   listening on $p"; else echo "FAIL nothing on $p"; fail+=("port $p"); fi
done
l=$(ledger); [ "$l" = "$LEDGER" ] && echo "ok   ledger 001-010" || fail+=("ledger now '$l'")
python3 -c 'import sys,yaml; s=yaml.safe_load(open(sys.argv[1]))["server"]; assert s.get("COLOR_PRISM_ENABLED") is True' "$C" && echo "ok   COLOR_PRISM_ENABLED true" || fail+=("prism flag")
echo "SELECT 'characters by world', world, COUNT(*) FROM characters GROUP BY world;" | sql
T[smoke1]=$(now)

result=PASS; [ "${#fail[@]}" = 0 ] || result="FAIL (${fail[*]})"
{
  echo "# SlumsMS $VERSION deploy receipt"
  echo
  echo "- Result: **$result**"
  echo "- Ran: $(date -u -d @"${t0}" '+%Y-%m-%d %H:%M:%S UTC') on $(hostname), server folder \`$SRV\`"
  echo "- Commit $COMMIT, jar $(sha256sum "$SRV/target/Cosmic.jar" | cut -c1-16) (was $(cut -c1-16 "$B/old-jar.sha256"))"
  echo "- Ledger: \`$l\` (no migrations in 1.4.1)"
  echo "- Backup: \`$B\` (mapleroot.sql.gz $(du -h "$B/mapleroot.sql.gz" | cut -f1), server-runtime.tgz $(du -h "$B/server-runtime.tgz" | cut -f1), wz-before.tgz $(wc -l < "$B/wz-before.list") file(s))"
  echo "- Log: \`$LOG\`"
  echo
  echo "| Step | Seconds |"
  echo "|---|---|"
  echo "| stop | $(dur "${T[stop0]}" "${T[stop1]}") |"
  echo "| backup | $(dur "${T[stop1]}" "${T[bak1]}") |"
  echo "| ledger check | $(dur "${T[bak1]}" "${T[mig1]}") |"
  echo "| config + WZ + install | $(dur "${T[mig1]}" "${T[stage1]}") |"
  echo "| start -> is now online | $(dur "${T[stage1]}" "${T[up]}") |"
  echo "| **downtime (stop -> online)** | **$(dur "${T[stop0]}" "${T[up]}")** |"
  echo "| smoke test | $(dur "${T[up]}" "${T[smoke1]}") |"
  echo
  echo "Smoke: expected log lines: $(printf "'%s' " "${EXPECT_LOG[@]}"); forbidden: $(printf "'%s' " "${FORBID_LOG[@]}"); ERROR/Exception lines: $errs."
  echo "Rollback: \`bash $D/rollback.sh\`"
} > "$RECEIPT"
cat "$RECEIPT"
[ "$result" = PASS ] || { step "smoke test FAILED; the server is up. Decide: fix forward or bash $D/rollback.sh"; exit 1; }
step "done. In game: log in, enter a character, change channel; buy a Coloring Prism in the Cash Shop and dye something (client 1.4.1); talk to Fredrick and Rosen; make a guild."
