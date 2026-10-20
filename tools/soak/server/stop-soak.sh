#!/bin/bash
# Stop slums-soak (graceful: bots log out and save). --all also stops the LLM mock and the soak tools
# (load generator, collector, guard).
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd); . "$here/soak.env"
units=("$UNIT")
[ "${1:-}" = --all ] && units=(soak-loadgen "$UNIT" "$MOCK_UNIT" soak-collect soak-guard)
for u in "${units[@]}"; do
  if systemctl is-active --quiet "$u"; then echo "stopping $u"; systemctl stop "$u"; fi
  systemctl reset-failed "$u" 2>/dev/null || true
done
