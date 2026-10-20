#!/bin/bash
# Installs slumsms-monitor for one target on this host (tickets 42 and 46). Run as root from this folder's copy.
#
#   install.sh <target> [--status] [--news] [--conf FILE] [--replace-conf]
#   install.sh <target> --uninstall
#
#   <target>        name of the watched server; its settings come from targets/<target>.conf (or --conf FILE)
#   --status        also post/edit the Discord status message every 5 minutes (ticket 46)
#   --news          also post new launcher news when NEWS_FILE changes (a .path unit; current entries are seeded
#                   as already posted, so nothing old is re-posted)
#   --replace-conf  overwrite an existing /etc/slumsms-monitor/<target>.conf (default: keep it)
#
# Installs /usr/local/bin/slumsms-monitor, the template units slumsms-{monitor,status,news}@.service/.timer and
# /etc/slumsms-monitor/<target>.{conf,env}. The .env (webhook URLs) is created empty with mode 0600 and never
# overwritten: with no URL, messages go to /var/log/slumsms-monitor/<target>.log. It does not touch the game server.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
TARGET="${1:-}"
[[ -n "$TARGET" && "$TARGET" =~ ^[A-Za-z0-9_.-]+$ ]] || { sed -n '2,16p' "$0"; exit 2; }
shift
STATUS=0; NEWS=0; UNINSTALL=0; REPLACE=0; CONF="$HERE/targets/$TARGET.conf"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --status) STATUS=1 ;;
    --news) NEWS=1 ;;
    --uninstall) UNINSTALL=1 ;;
    --replace-conf) REPLACE=1 ;;
    --conf) CONF="$2"; shift ;;
    *) echo "unknown option $1"; exit 2 ;;
  esac
  shift
done
[[ $EUID -eq 0 ]] || { echo "run as root"; exit 1; }
ETC=/etc/slumsms-monitor

if [[ $UNINSTALL -eq 1 ]]; then
  systemctl disable --now "slumsms-monitor@$TARGET.timer" "slumsms-status@$TARGET.timer" 2>/dev/null || true
  systemctl disable --now "slumsms-news-$TARGET.path" 2>/dev/null || true
  rm -f "/etc/systemd/system/slumsms-news-$TARGET.path"
  systemctl daemon-reload
  echo "Stopped the $TARGET monitor timers. Kept $ETC/$TARGET.* and /var/lib/slumsms-monitor/$TARGET (delete by hand)."
  exit 0
fi

[[ -f "$CONF" ]] || { echo "no target config $CONF"; exit 1; }
python3 -c 'import yaml' || { echo "python3-yaml is needed (apt install python3-yaml)"; exit 1; }

install -m 0755 "$HERE/slumsms-monitor" /usr/local/bin/slumsms-monitor
for u in slumsms-monitor@.service slumsms-monitor@.timer slumsms-status@.service slumsms-status@.timer \
         slumsms-news@.service; do
  install -m 0644 "$HERE/$u" "/etc/systemd/system/$u"
done
install -d -m 0755 "$ETC"
install -d -m 0750 /var/lib/slumsms-monitor /var/log/slumsms-monitor
if [[ ! -f "$ETC/$TARGET.conf" || $REPLACE -eq 1 ]]; then
  install -m 0644 "$CONF" "$ETC/$TARGET.conf"
  echo "wrote $ETC/$TARGET.conf"
else
  echo "kept existing $ETC/$TARGET.conf (--replace-conf to overwrite)"
fi
if [[ ! -f "$ETC/$TARGET.env" ]]; then
  install -m 0600 "$HERE/targets/example.env" "$ETC/$TARGET.env"
  echo "created $ETC/$TARGET.env (0600, no webhook yet: messages go to /var/log/slumsms-monitor/$TARGET.log)"
fi
chown root:root "$ETC/$TARGET.env"; chmod 0600 "$ETC/$TARGET.env"

systemctl daemon-reload
echo "--- first check (dry run) ---"
/usr/local/bin/slumsms-monitor check "$TARGET" --dry-run || true
systemctl enable --now "slumsms-monitor@$TARGET.timer"

if [[ $STATUS -eq 1 ]]; then
  systemctl enable --now "slumsms-status@$TARGET.timer"
fi
if [[ $NEWS -eq 1 ]]; then
  NEWS_FILE="$(sed -n 's/^NEWS_FILE=//p' "$ETC/$TARGET.conf" | tail -1)"
  [[ -n "$NEWS_FILE" && -f "$NEWS_FILE" ]] || { echo "NEWS_FILE missing in $ETC/$TARGET.conf"; exit 1; }
  /usr/local/bin/slumsms-monitor news "$TARGET" --seed
  cat > "/etc/systemd/system/slumsms-news-$TARGET.path" <<EOF
[Unit]
Description=Post SlumsMS launcher news to Discord when $NEWS_FILE changes ($TARGET)

[Path]
PathChanged=$NEWS_FILE
Unit=slumsms-news@$TARGET.service

[Install]
WantedBy=paths.target
EOF
  systemctl daemon-reload
  systemctl enable --now "slumsms-news-$TARGET.path"
fi

echo "--- installed ---"
systemctl list-timers --no-pager "slumsms-monitor@$TARGET.timer" "slumsms-status@$TARGET.timer" 2>/dev/null || true
echo "Webhooks: edit $ETC/$TARGET.env, then: slumsms-monitor test-webhook $TARGET alerts"
