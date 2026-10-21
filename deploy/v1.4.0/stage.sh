#!/bin/bash
# Stage SlumsMS 1.4.0 on 164 (workstation; needs tools/release/build first). Copies, never runs anything there.
#
#   bash deploy/v1.4.0/stage.sh [--replace]
#
# -> root@192.168.0.164:/opt/mapleroot-deployments/v1.4.0/: deploy.sh, rollback.sh, rehearse.sh, DEPLOY-NOTES.md,
#    news-1.4.0.json, staging/ (Cosmic.jar, BUILD.txt, COMMIT, migrations/, scripts.tar, srcdata.tar (+ .list),
#    catalog.tsv, tools/{data-align,colorprism,soak}, SHA256SUMS). Refuses a folder that was deployed.
set -euo pipefail
REPO=$(cd "$(dirname "$0")/../.." && pwd); HERE=$REPO/deploy/v1.4.0
HOST=root@192.168.0.164 D=/opt/mapleroot-deployments/v1.4.0
REPLACE=0; [ "${1:-}" = --replace ] && REPLACE=1
cd "$REPO"
BUILD=$REPO/dist/release-build
bcommit=$(sed -n 's/^commit=//p' "$BUILD/BUILD.txt"); sha=$(sed -n 's/^jar_sha256=//p' "$BUILD/BUILD.txt")
[ "$(sed -n 's/^version=//p' "$BUILD/BUILD.txt")" = 1.4.0 ] || { echo "build is not 1.4.0"; exit 1; }
[ "$(sha256sum "$BUILD/Cosmic.jar" | cut -d' ' -f1)" = "$sha" ] || { echo "jar does not match BUILD.txt"; exit 1; }
grep -q "^JAR_SHA16=${sha:0:16}$" "$HERE/deploy.sh" || { echo "deploy.sh JAR_SHA16 is not ${sha:0:16}"; exit 1; }
git diff --quiet "$bcommit" HEAD -- src/main scripts database cashshop tools/data-align tools/colorprism pom.xml \
  || { echo "src/main, scripts, database, cashshop or the WZ tools changed since the build ($bcommit); rebuild"; exit 1; }
for f in "$HERE"/*.sh; do bash -n "$f"; done

OUT=$REPO/dist/release-stage/1.4.0; rm -rf "$OUT"; ST=$OUT/staging; mkdir -p "$ST/migrations" "$ST/tools"
cp "$BUILD/Cosmic.jar" "$BUILD/BUILD.txt" "$ST/"; echo "$bcommit" > "$ST/COMMIT"
git ls-files scripts > "$ST/scripts.list"; git ls-files src/main | grep -v '\.java$' > "$ST/srcdata.list"
tar --owner=0 --group=0 -cf "$ST/scripts.tar" -T "$ST/scripts.list"
tar --owner=0 --group=0 -cf "$ST/srcdata.tar" -T "$ST/srcdata.list"
cp database/migrations/*.sql "$ST/migrations/"
cp cashshop/catalog.tsv "$ST/catalog.tsv"
mkdir -p "$ST/tools/data-align" "$ST/tools/soak"
cp tools/data-align/wz_set_values.py tools/data-align/mob-attack-client-values.tsv "$ST/tools/data-align/"
cp -r tools/colorprism "$ST/tools/"; rm -rf "$ST/tools/colorprism/__pycache__"
cp tools/soak/LoadGen.java tools/soak/CharLoadProbe.java "$ST/tools/soak/"
(cd "$ST" && find . -type f ! -name SHA256SUMS -printf '%P\n' | LC_ALL=C sort | xargs -d '\n' sha256sum > SHA256SUMS)
cp "$HERE"/deploy.sh "$HERE"/rollback.sh "$HERE"/rehearse.sh "$HERE"/DEPLOY-NOTES.md "$HERE"/news-1.4.0.json "$OUT/"
chmod +x "$OUT"/*.sh

state=$(ssh -o BatchMode=yes "$HOST" "if [ -e '$D/deploy.log' ] || [ -e '$D/backup' ]; then echo deployed; elif [ -e '$D' ]; then echo staged; else echo none; fi")
[ "$state" != deployed ] || { echo "$D was deployed; never re-stage it"; exit 1; }
[ "$state" != staged ] || [ "$REPLACE" = 1 ] || { echo "$D is staged; --replace to re-stage"; exit 1; }
ssh -o BatchMode=yes "$HOST" "mkdir -p '$D'"
rsync -a --delete --exclude backup --exclude '*.log' --exclude 'RECEIPT.md' --exclude 'REHEARSAL.md' "$OUT/" "$HOST:$D/"
ssh -o BatchMode=yes "$HOST" "cd '$D/staging' && sha256sum -c --quiet SHA256SUMS && echo 'host staging verified' && for f in ../*.sh; do bash -n \$f || exit 1; done && echo 'bash -n clean'"
echo "staged $D (commit $bcommit, jar ${sha:0:16})"
