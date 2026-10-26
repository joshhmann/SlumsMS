#!/usr/bin/env bash
# Copy what the 15-minute market rebuild needs to 164 (/opt/slums-site-build). Run on 187 from the repo root.
#   tools/site/deploy-build-164.sh [--dry-run]
# Copies tools/site, docs/site, docs/wiki and the Hidden Street dataset. Nothing else on 164 changes; the timer
# itself is installed by hand (docs/site/LAUNCH.md step 6). Needs the owner's go the first time.
set -euo pipefail
HOST=root@192.168.0.164
DEST=/opt/slums-site-build
DATA=${SLUMS_HS_DATA:-/root/work/SlumsMS-160-market/data/hiddenstreet}
DRY=()
[ "${1:-}" = "--dry-run" ] && DRY=(--dry-run --itemize-changes)
cd "$(git rev-parse --show-toplevel)"
for f in tools/site/build.py docs/site/home.html "$DATA/items.json"; do
  [ -e "$f" ] || { echo "missing $f" >&2; exit 1; }
done
ssh "$HOST" "mkdir -p $DEST/tools $DEST/docs $DEST/hiddenstreet"
rsync -a --delete "${DRY[@]}" --exclude __pycache__ --exclude testdata tools/site/ "$HOST:$DEST/tools/site/"
rsync -a --delete "${DRY[@]}" docs/site/ "$HOST:$DEST/docs/site/"
rsync -a --delete "${DRY[@]}" docs/wiki/ "$HOST:$DEST/docs/wiki/"
rsync -a --delete "${DRY[@]}" --include '*.json' --exclude '*' "$DATA/" "$HOST:$DEST/hiddenstreet/"
ssh "$HOST" "echo $(git rev-parse --short HEAD) > $DEST/DEPLOYED_COMMIT"
echo "build folder on $HOST:$DEST is at $(git rev-parse --short HEAD)"
