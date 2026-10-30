#!/bin/bash
# Run ON .17 as root (owner 2026-10-30): make qwen9b (:8095, live's local chat model) start at boot. It does NOT
# start, stop or restart anything: the hand-started llama-server keeps serving until the next reboot, when this unit
# starts it instead (and refuses to start while something already listens on :8095). Undo: systemctl disable
# qwen9b-8095 and remove /etc/systemd/system/qwen9b-8095.service.
set -eu
cd "$(dirname "$0")"
[ ! -e /etc/systemd/system/qwen9b-8095.service ] || cp -p /etc/systemd/system/qwen9b-8095.service \
  /etc/systemd/system/qwen9b-8095.service.pre-boot
cp qwen9b-8095.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable qwen9b-8095
echo "qwen9b-8095 enabled=$(systemctl is-enabled qwen9b-8095) unit=$(systemctl is-active qwen9b-8095) (inactive is" \
     "right until the next boot)" "http=$(curl -s -o /dev/null -w %{http_code} -m 5 localhost:8095/v1/models)"
