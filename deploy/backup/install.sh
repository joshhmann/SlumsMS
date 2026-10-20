#!/bin/bash
# Installs the ticket-45 backup kit on 164 (run as root from this folder). Owner's step: it changes live's backup
# job (not the game server). What it does:
#   - saves the current /usr/local/bin/slumsms-nightly-backup and slumsms-backup.{service,timer} to
#     /var/lib/slumsms-backup/prev-<ts>/ (undo: install.sh --undo <that folder>)
#   - installs slumsms-nightly-backup (v2), slumsms-rotate-backups, slumsms-restore-drill to /usr/local/bin
#   - installs units slumsms-backup.{service,timer}, slumsms-backup-rotate.{service,timer},
#     slumsms-restore-drill.{service,timer} to /etc/systemd/system
#   - creates /etc/slumsms-backup.conf (from the example) and the off-box key file if they do not exist yet
#   - enables slumsms-backup-rotate.timer (dry run until ROTATE_MODE=apply) and, with --with-drill, the monthly
#     restore drill timer; slumsms-backup.timer stays as it is (enabled)
#   - runs one rotation dry run so the list is visible now (and starts the grace clock)
# Options: --with-drill   also enable slumsms-restore-drill.timer
#          --backup-now   run the new nightly backup once right away (dumps live with a ~0.5 s table lock)
#          --undo DIR     put back the scripts/units saved in DIR, disable the new timers
set -euo pipefail
cd "$(dirname "$0")"
[ "$(id -u)" = 0 ] || { echo "run as root" >&2; exit 1; }

BIN=/usr/local/bin
UNITS=/etc/systemd/system
STATE=/var/lib/slumsms-backup
WITH_DRILL=0 BACKUP_NOW=0
case "${1:-}" in
--undo)
    prev=${2:?usage: install.sh --undo /var/lib/slumsms-backup/prev-<ts>}
    systemctl disable --now slumsms-backup-rotate.timer slumsms-restore-drill.timer 2>/dev/null || true
    install -m 755 "$prev/slumsms-nightly-backup" "$BIN/slumsms-nightly-backup"
    install -m 644 "$prev/slumsms-backup.service" "$UNITS/slumsms-backup.service"
    install -m 644 "$prev/slumsms-backup.timer" "$UNITS/slumsms-backup.timer"
    systemctl daemon-reload
    echo "restored the v1 backup script and units from $prev (new scripts left in $BIN, harmless)"
    exit 0 ;;
esac
for a in "$@"; do
    case "$a" in
        --with-drill) WITH_DRILL=1 ;;
        --backup-now) BACKUP_NOW=1 ;;
        *) echo "unknown option $a" >&2; exit 2 ;;
    esac
done

for f in slumsms-nightly-backup slumsms-rotate-backups slumsms-restore-drill slumsms-backup.conf.example \
         slumsms-backup.service slumsms-backup.timer slumsms-backup-rotate.service slumsms-backup-rotate.timer \
         slumsms-restore-drill.service slumsms-restore-drill.timer; do
    [ -f "$f" ] || { echo "missing $f in $(pwd)" >&2; exit 1; }
done
for f in slumsms-nightly-backup slumsms-rotate-backups slumsms-restore-drill; do bash -n "$f"; done

ts=$(date -u +%Y%m%dT%H%M%SZ)
prev="$STATE/prev-$ts"
mkdir -p "$prev"
chmod 700 "$STATE"
cp -a "$BIN/slumsms-nightly-backup" "$UNITS/slumsms-backup.service" "$UNITS/slumsms-backup.timer" "$prev/" 2>/dev/null || true
echo "saved the current backup script and units to $prev"

install -m 755 slumsms-nightly-backup slumsms-rotate-backups slumsms-restore-drill "$BIN/"
install -m 644 slumsms-backup.service slumsms-backup.timer slumsms-backup-rotate.service slumsms-backup-rotate.timer \
    slumsms-restore-drill.service slumsms-restore-drill.timer "$UNITS/"
[ -e /etc/slumsms-backup.conf ] || install -m 600 slumsms-backup.conf.example /etc/slumsms-backup.conf
key=/root/.config/slumsms-backup/offbox.key
if [ ! -e "$key" ]; then
    install -d -m 700 "$(dirname "$key")"
    (umask 077; head -c 48 /dev/urandom | base64 -w0 > "$key")
    echo "created the off-box encryption key $key - copy its one line into your password manager NOW;"
    echo "without it the off-box copies cannot be decrypted (it lives on this host only)."
fi
systemctl daemon-reload
systemctl enable --now slumsms-backup-rotate.timer
[ "$WITH_DRILL" = 0 ] || systemctl enable --now slumsms-restore-drill.timer
systemctl is-enabled -q slumsms-backup.timer || systemctl enable --now slumsms-backup.timer

echo; echo "== rotation dry run (nothing is deleted)"
"$BIN/slumsms-rotate-backups" --dry-run
if [ "$BACKUP_NOW" = 1 ]; then
    echo; echo "== nightly backup v2, once now"
    systemctl start slumsms-backup.service
    journalctl -u slumsms-backup.service -n 12 -o cat --no-pager
fi
echo
systemctl list-timers --no-pager 'slumsms-*'
echo
echo "installed. Next: fill OFFBOX_DEST in /etc/slumsms-backup.conf when a target exists (README.md);"
echo "set ROTATE_MODE=apply there once the dry-run list looks right. Undo: $0 --undo $prev"
