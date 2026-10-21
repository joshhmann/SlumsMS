#!/bin/bash
# test-rotate-protect.sh (ticket 45): proves slumsms-rotate-backups never lists or deletes a protected path
# (default PROTECT_PATHS=PreviousServer), in dry run and in --apply, while still rotating the other bulk.
# Runs entirely in a temp folder; touches nothing on the host. Usage: bash test-rotate-protect.sh
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
t=$(mktemp -d)
trap 'rm -rf "$t"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }

dep=$t/deployments
# 4 finished deploys; the oldest (old-r1) falls out of KEEP_DEPLOYS=3, like baseline-20261018-r1 on 164.
for i in 1 2 3 4; do
    d=$dep/d$i; mkdir -p "$d"; echo log > "$d/deploy.log"
    touch -d "2026-10-1$i 12:00" "$d/deploy.log"
done
old=$dep/d1
mkdir -p "$old/PreviousServer/sub" "$old/backup/nested/PreviousServer" "$old/staging"
echo keep > "$old/PreviousServer/sub/Cosmic.jar"
echo keep > "$old/backup/nested/PreviousServer/x"
echo bulk > "$old/staging/x"
echo bulk > "$old/repo.bundle"
find "$old" -exec touch -d "2026-10-11 12:00" {} +

run() { SLUMSMS_BACKUP_CONF=/dev/null PRE_ROOT=$t/pre DEPLOY_ROOT=$dep TEST_ROOT=$t/dev \
        "$here/slumsms-rotate-backups" --state-dir "$t/state" "$@"; }

out=$(run --dry-run 2>&1); echo "$out"
plan=$t/state/rotate-plan.tsv
grep -q "$old/staging" "$plan" || fail "staging/ should be a candidate"
grep -q "$old/repo.bundle" "$plan" || fail "repo.bundle should be a candidate"
grep -q PreviousServer "$plan" && fail "PreviousServer listed as a candidate"
grep -q "$old/backup" "$plan" && fail "backup/ holds a PreviousServer and must not be a candidate"
grep -qE "^  .*$old/PreviousServer  \(would otherwise" <<< "$out" || fail "PreviousServer not reported as protected"

sleep 1
run --apply --grace-hours 0 >/dev/null 2>&1
[ -f "$old/PreviousServer/sub/Cosmic.jar" ] || fail "apply deleted PreviousServer"
[ -f "$old/backup/nested/PreviousServer/x" ] || fail "apply deleted backup/ holding a PreviousServer"
[ ! -e "$old/staging" ] && [ ! -e "$old/repo.bundle" ] || fail "apply did not delete unprotected bulk"

# A path-glob entry protects by full path, and an empty list protects nothing.
mkdir -p "$old/staging"; find "$old" -exec touch -d "2026-10-11 12:00" {} +   # the deletes bumped d1's mtime
PROTECT_PATHS="PreviousServer $dep/*/staging" run --dry-run >/dev/null 2>&1
grep -q "$old/staging" "$plan" && fail "path-glob protection ignored"
PROTECT_PATHS="" run --dry-run >/dev/null 2>&1
grep -q "$old/PreviousServer" "$plan" || fail "empty PROTECT_PATHS should allow PreviousServer"
echo "PASS: PreviousServer protected in dry run and apply; other bulk still rotates"
