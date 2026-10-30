#!/bin/bash
# Run ON .17 as root (owner 2026-10-30): make decide-8900 (GLiNER, live's chat model) start at boot. It does NOT
# restart the running service, so live chat is not cut; the new unit (card wait, [Install]) applies from the next
# start. Undo: systemctl disable decide-8900, then cp decide-8900.service.pre-boot back and daemon-reload.
set -eu
cd "$(dirname "$0")"
cp -p /etc/systemd/system/decide-8900.service /etc/systemd/system/decide-8900.service.pre-boot
cp decide-8900.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable decide-8900
echo ":8900 $(systemctl is-active decide-8900) enabled=$(systemctl is-enabled decide-8900)" \
     "since=$(systemctl show decide-8900 -p ActiveEnterTimestamp --value)" \
     "http=$(curl -s -o /dev/null -w %{http_code} -m 5 localhost:8900/health)"
