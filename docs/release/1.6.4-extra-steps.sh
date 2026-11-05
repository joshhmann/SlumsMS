#!/bin/bash
# 1.6.4 night extras (playbook B3), run by release-live.sh between stop-backup and apply-start; a failure here makes
# release-live.sh restart the old version. b300 Crack in Time portal fix (2 values in 2 Map2 files) and Murt (NPC 2160000).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
W="/opt/mapleroot/MapleRoot Full Repack/Server/wz"
B="/opt/mapleroot/MapleRoot Full Repack/Server/wz-backup-164-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$B/Map.wz/Map/Map2"
cp -p "$W/Map.wz/Map/Map2/272000000.img.xml" "$W/Map.wz/Map/Map2/272000310.img.xml" "$B/Map.wz/Map/Map2/"
echo "backup: $B"
cd "$HERE"
python3 wz_set_values.py --wz "$W" --tsv portal-fixes.tsv --column new_value --apply
python3 add_murt.py --wz "$W" --apply
# both must now have nothing left to do
python3 wz_set_values.py --wz "$W" --tsv portal-fixes.tsv --column new_value | grep -q "0 value(s)" || { echo "portal fix not fully applied"; exit 1; }
python3 add_murt.py --wz "$W" | grep -q "add Npc.wz" && { echo "Murt not applied"; exit 1; }
echo "extra steps done: b300 portals + Murt"
