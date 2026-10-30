#!/usr/bin/env bash
# Put the key console on 164 (tools/keys/README.md). Run on 187 from the repo root:
#   tools/keys/install.sh            install or update, then (re)start it
#   tools/keys/install.sh --remove   stop and remove it (keeps the passphrase file, the log and every key backup)
# It never reads or prints a key, and changes no key itself.
set -euo pipefail
HOST=${SLUMS_KEYS_INSTALL_HOST:-root@192.168.0.164}
SRC=$(cd "$(dirname "$0")" && pwd)
if [ "${1:-}" = "--remove" ]; then
  ssh "$HOST" 'systemctl disable --now slums-keys 2>/dev/null || true; rm -f /etc/systemd/system/slums-keys.service \
    /usr/local/sbin/slums-keys /usr/local/sbin/slums-keys-remote; rm -rf /usr/local/lib/slums-keys; systemctl daemon-reload'
  echo "removed (kept /root/.config/slums-keys, /var/log/slums-keys.log and the .bak-* files)"; exit 0
fi
ssh "$HOST" 'install -d -m 755 /usr/local/lib/slums-keys; install -d -m 700 /root/.config/slums-keys'
scp -q "$SRC/registry.py" "$SRC/console.py" "$SRC/slums-keys-remote" "$HOST:/usr/local/lib/slums-keys/"
scp -q "$SRC/slums-keys.service" "$HOST:/etc/systemd/system/slums-keys.service"
ssh "$HOST" 'chmod 755 /usr/local/lib/slums-keys/console.py /usr/local/lib/slums-keys/slums-keys-remote
  chmod 644 /usr/local/lib/slums-keys/registry.py
  ln -sf /usr/local/lib/slums-keys/console.py /usr/local/sbin/slums-keys
  ln -sf /usr/local/lib/slums-keys/slums-keys-remote /usr/local/sbin/slums-keys-remote
  touch /var/log/slums-keys.log; chmod 600 /var/log/slums-keys.log
  systemctl daemon-reload; systemctl enable -q slums-keys; systemctl restart slums-keys; sleep 1
  systemctl is-active slums-keys; curl -s -o /dev/null -w "page %{http_code}\n" http://192.168.0.164:8791/'
