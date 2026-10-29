#!/bin/bash
# Run ON .17 as root (owner 2026-10-29): install the chat-model units from this folder, enable them at boot, restart
# each and check it answers. Undo: systemctl disable decide-8910 decide-8913 (the units stay; they just stop starting
# at boot). TEST uses :8913 (intent+con) and :8910 (tone); live 1.6.0 uses :8900, which this does not touch.
set -eu
cd "$(dirname "$0")"
for u in decide-8910 decide-8913; do
  cp -p /etc/systemd/system/$u.service /etc/systemd/system/$u.service.pre-boot 2>/dev/null || true
  cp $u.service /etc/systemd/system/
done
systemctl daemon-reload
for port in 8913 8910; do
  systemctl enable decide-$port
  systemctl restart decide-$port
  for i in $(seq 120); do curl -sf localhost:$port/v1/models >/dev/null && break; sleep 1; done
  echo ":$port $(systemctl is-active decide-$port) enabled=$(systemctl is-enabled decide-$port)" \
       "http=$(curl -s -o /dev/null -w %{http_code} localhost:$port/v1/models)" \
       "model=$(curl -s localhost:$port/v1/models | grep -o '"name":"[^"]*"' | head -1)"
done
