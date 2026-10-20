#!/bin/bash
# Re-copy live into the soak server and drop in a build (ticket 48). Live is only read: a mysqldump
# --single-transaction of its DB and an rsync of its Server folder.
#
#   refresh-soak.sh <live | path/to/Cosmic.jar | release staging dir> [--keep-db]
#
#   live          the jar live runs now (its Server folder as it is)
#   Cosmic.jar    that jar on top of a fresh copy of live's Server folder
#   staging dir   /opt/mapleroot-deployments/v<ver>/staging from tools/release/stage: Cosmic.jar, scripts.tar,
#                 srcdata.tar (non-Java src/main data), config files and migrations (*.sql, guarded and ledgered, so
#                 every non-rollback one is applied in name order; the ones live already has are no-ops)
#   --keep-db     keep mapleroot_soak as it is (only swap the files)
#
# The soak server is stopped first if it runs (stop-soak.sh); start it again with start-soak.sh.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd); . "$here/soak.env"
SRCARG=${1:?usage: refresh-soak.sh <live|Cosmic.jar|staging dir> [--keep-db]}; KEEPDB=0
[ "${2:-}" = --keep-db ] && KEEPDB=1
log() { echo "[$(date -u +%T)] $*"; }
mkdir -p "$DATA"/receipts
exec > >(tee -a "$DATA/receipts/refresh.log") 2>&1
log "refresh from $SRCARG (keep db: $KEEPDB)"
[ -f "$TESTDB_CNF" ] && mysql "${TESTDB[@]}" -N -e "SELECT 1" > /dev/null || { echo "test DB 127.0.0.1:3307 not reachable"; exit 1; }
[ -d "$LIVE_SRV/wz" ] || { echo "no live server folder"; exit 1; }
if [ -d "$SRCARG" ]; then [ -f "$SRCARG/Cosmic.jar" ] || { echo "$SRCARG has no Cosmic.jar"; exit 1; }
elif [ "$SRCARG" != live ]; then [ -f "$SRCARG" ] || { echo "no jar $SRCARG"; exit 1; }; fi
if systemctl is-active --quiet "$UNIT"; then log "stopping $UNIT"; bash "$here/stop-soak.sh"; fi

if [ "$KEEPDB" = 0 ]; then
  log "dump live ($LIVE_DB, --single-transaction)"
  docker exec "$LIVE_DB" sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers mapleroot' 2>/dev/null \
    | gzip > "$D/live.sql.gz"
  zcat "$D/live.sql.gz" | tail -1 | grep -q 'Dump completed' || { echo "live dump incomplete"; exit 1; }
  bad=$(zcat "$D/live.sql.gz" | grep -E '^(USE|CREATE DATABASE)' | grep -vE '^(USE `mapleroot`;|CREATE DATABASE .*`mapleroot`)' || true)
  [ -z "$bad" ] || { echo "dump names another schema: $bad"; exit 1; }
  [ -f "$D/db.env" ] || { umask 077; echo "DBPASS=$(head -c 18 /dev/urandom | base64 | tr -d '/+=')" > "$D/db.env"; }
  . "$D/db.env"
  log "load into $SCHEMA (test DB 3307)"
  mysql "${TESTDB[@]}" -e "DROP DATABASE IF EXISTS \`$SCHEMA\`; CREATE DATABASE \`$SCHEMA\`;
    CREATE USER IF NOT EXISTS '$DBUSER'@'%' IDENTIFIED BY '$DBPASS'; ALTER USER '$DBUSER'@'%' IDENTIFIED BY '$DBPASS';
    GRANT ALL ON \`$SCHEMA\`.* TO '$DBUSER'@'%';"
  zcat "$D/live.sql.gz" | grep -vE '^(USE `mapleroot`;|CREATE DATABASE .*`mapleroot`)' | mysql "${TESTDB[@]}" "$SCHEMA"
  rm -f "$D/live.sql.gz"
  log "loaded: $(mysql "${TESTDB[@]}" -N "$SCHEMA" -e "SELECT CONCAT((SELECT COUNT(*) FROM accounts),' accounts, ',(SELECT COUNT(*) FROM characters),' characters')")"
fi
. "$D/db.env"

log "copy live Server/ (wz symlinked)"
mkdir -p "$SRV"
rsync -a --delete --exclude /wz --exclude /logs --exclude /.git --exclude /reports "$LIVE_SRV/" "$SRV/"
[ -L "$SRV/wz" ] || ln -s "$LIVE_SRV/wz" "$SRV/wz"
if [ -d "$SRCARG" ]; then
  cp "$SRCARG/Cosmic.jar" "$SRV/target/Cosmic.jar"
  [ -f "$SRCARG/scripts.tar" ] && tar -xf "$SRCARG/scripts.tar" -C "$SRV"
  [ -f "$SRCARG/srcdata.tar" ] && tar -xf "$SRCARG/srcdata.tar" -C "$SRV"
  for m in $(find "$SRCARG" -maxdepth 2 -name '*.sql' ! -name '*rollback*' | sort); do
    log "migration $(basename "$m")"; mysql "${TESTDB[@]}" "$SCHEMA" < "$m"
  done
  ls "$SRCARG"/config-*.yaml >/dev/null 2>&1 && log "NOTE: $SRCARG has config fragments ($(ls "$SRCARG"/config-*.yaml | xargs -n1 basename | tr '\n' ' ')); merge them into $SRV/config.yaml by hand if live does not have them yet"
elif [ "$SRCARG" != live ]; then
  cp "$SRCARG" "$SRV/target/Cosmic.jar"
fi

C=$SRV/config.yaml
sed -i -E \
  -e "s#^([[:space:]]*DB_HOST:).*(\r?)\$#\1 127.0.0.1\2#" \
  -e "s#^([[:space:]]*DB_URL_FORMAT:).*(\r?)\$#\1 \"jdbc:mysql://%s:3307/$SCHEMA\"\2#" \
  -e "s#^([[:space:]]*DB_USER:).*(\r?)\$#\1 $DBUSER\2#" \
  -e "s#^([[:space:]]*DB_PASS:).*(\r?)\$#\1 \"$DBPASS\"\2#" \
  -e "s#^([[:space:]]*HOST:).*(\r?)\$#\1 192.168.0.164\2#" \
  -e "s#^([[:space:]]*BOT_LLM_BASE_URL:).*(\r?)\$#\1 \"http://127.0.0.1:$MOCK_PORT/v1\"\2#" \
  -e "s#^([[:space:]]*BOT_DECIDE_URL:).*(\r?)\$#\1 \"http://127.0.0.1:$MOCK_PORT\"\2#" "$C"
grep -qE "DB_URL_FORMAT: \"jdbc:mysql://%s:3307/$SCHEMA\"" "$C" && grep -qE "^[[:space:]]*DB_USER: $DBUSER" "$C" \
  || { echo "could not point config.yaml at $SCHEMA"; exit 1; }
if grep -E '^[[:space:]]*(BOT_LLM_BASE_URL|BOT_DECIDE_URL|BOT_LLM_NOTES_BASE_URL):' "$C" | grep -vq "127.0.0.1:$MOCK_PORT"; then
  echo "an LLM URL in config.yaml does not point at the mock:"; grep -E 'BOT_LLM.*URL|BOT_DECIDE_URL' "$C"; exit 1
fi
if grep -nE 'https?://' "$C" | grep -vE "127.0.0.1:$MOCK_PORT|^[0-9]+:[[:space:]]*#" | grep -q .; then
  log "NOTE: other URLs in config.yaml: $(grep -nE 'https?://' "$C" | grep -v "127.0.0.1:$MOCK_PORT" | tr '\n' ' ')"
fi
grep -E '^[[:space:]]*(DB_HOST|DB_URL_FORMAT|DB_USER|HOST|BOT_LLM_BASE_URL|BOT_DECIDE_URL):' "$C" | tr -d '\r'
{
  echo "refreshed: $(date -u '+%F %T') UTC from $SRCARG"
  echo "jar sha256: $(sha256sum "$SRV/target/Cosmic.jar" | cut -c1-16) (live: $(sha256sum "$LIVE_SRV/target/Cosmic.jar" | cut -c1-16))"
  echo "db: $([ "$KEEPDB" = 1 ] && echo kept || echo "fresh copy of live")"
} > "$D/VERSION.txt"
cat "$D/VERSION.txt"
log "done; start with $D/start-soak.sh"
