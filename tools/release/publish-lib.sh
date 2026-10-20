# Shared by publish-client and publish-launcher (sourced). They run on the workstation and act on the host over SSH.
PATCH_DIR=${PATCH_DIR:-/srv/slums-patch}
PUBLISHER=${PUBLISHER:-/opt/slums-tools/launcher}
NODE=${NODE:-/opt/slums-tools/node/bin/node}
LAUNCHER_REPO=${LAUNCHER_REPO:-/root/work/launcher}
REFERENCE=${REFERENCE:-"/opt/mapleroot-control/MapleRoot Full Repack/Game Files"}
REF_CACHE=${REF_CACHE:-/srv/slums-staging/reference-client.hashes.json}
h() { ssh -o BatchMode=yes "$RELEASE_HOST" "$@"; }
q() { printf '%q ' "$@"; }

# The host's publisher must be the launcher repo's: same tools/ and src/ bytes. --sync-publisher copies them over.
check_publisher() {
  local sync=$1 l r
  [ -f "$LAUNCHER_REPO/tools/publish.mjs" ] || die "no launcher repo at $LAUNCHER_REPO"
  echo "launcher repo: $(git -C "$LAUNCHER_REPO" log --oneline -1)"
  l=$(cd "$LAUNCHER_REPO" && find tools src package.json -type f | LC_ALL=C sort | xargs sha256sum | sha256sum | cut -c1-16)
  r=$(h "cd $PUBLISHER && find tools src package.json -type f | LC_ALL=C sort | xargs sha256sum | sha256sum | cut -c1-16")
  if [ "$l" = "$r" ]; then echo "host publisher matches the launcher repo ($l)"; return; fi
  [ "$sync" = 1 ] || die "host publisher $PUBLISHER ($r) differs from $LAUNCHER_REPO ($l); rerun with --sync-publisher"
  run rsync -a --delete "$LAUNCHER_REPO/tools/" "$RELEASE_HOST:$PUBLISHER/tools/"
  run rsync -a --delete "$LAUNCHER_REPO/src/" "$RELEASE_HOST:$PUBLISHER/src/"
  run rsync -a "$LAUNCHER_REPO/package.json" "$RELEASE_HOST:$PUBLISHER/package.json"
  echo "host publisher synced from the launcher repo"
}
