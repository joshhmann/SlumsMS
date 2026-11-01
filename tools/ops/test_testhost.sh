#!/bin/bash
# Shell-level test of tools/ops/lib.sh and the TEST-host default of the ops tools (o03). No remote calls: ssh is
# replaced by a printer, and the two tools with a dry run are exercised in it.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
d="$(mktemp -d)"
trap 'rm -rf "$d"' EXIT
fail() { echo "FAIL: $*"; exit 1; }

# ssh_argv <env assignments...>: the argv test_ssh would run, space-separated
ssh_argv() {
    env -u SLUMS_TEST_HOST "$@" bash -c '
        . "$1/lib.sh"
        ssh() { printf "%s " "$@"; }
        test_ssh "true"
    ' _ "$here"
}

# 1. Default until the 1.6.3 TEST cutover (o03 step 3): the played TEST on 164, default key. Flip with lib.sh.
out=$(ssh_argv)
[[ "$out" == *"root@192.168.0.164"* ]] || fail "default host: $out"
[[ "$out" == *"-o BatchMode=yes"* ]] || fail "BatchMode: $out"
# mapletest (192.168.0.111) is reached with its own key.
out=$(ssh_argv SLUMS_TEST_HOST=192.168.0.111)
[[ "$out" == *"root@192.168.0.111"* ]] || fail "mapletest host: $out"
[[ "$out" == *"/.ssh/id_slums_lxc"* ]] || fail "mapletest key: $out"

# 2. SLUMS_TEST_HOST moves the tools (a name, as the Website lane's installer uses it).
out=$(ssh_argv SLUMS_TEST_HOST=mapletest)
[[ "$out" == *"root@mapletest"* ]] || fail "override by name: $out"
[[ "$out" == *"id_slums_lxc"* ]] || fail "override key: $out"

# 3. 164 is the one host that keeps ssh's default key (it is live's box now, but a tool can be pointed back at it).
out=$(ssh_argv SLUMS_TEST_HOST=192.168.0.164)
[[ "$out" == *"root@192.168.0.164"* ]] || fail "164 host: $out"
[[ "$out" != *"id_slums_lxc"* ]] || fail "164 must not ask for the TEST key: $out"

# 4. Every TEST-facing tool goes through the helper and names no 164 address for TEST work.
for tool in deploy-test autoqa-run test-status qa-run-collect; do
    grep -q 'lib.sh' "$here/$tool" || fail "$tool does not source lib.sh"
    if grep -qE 'root@192\.168\.0\.164' "$here/$tool"; then fail "$tool still hard-codes 164"; fi
done

# 5. qa-run-collect --dry-run prints the TEST box it would read.
out=$(SLUMS_TEST_HOST=192.168.0.111 QA_RUNS_ROOT="$d/runs" "$here/qa-run-collect" --run "$d/runs/2026-10-31/x-20261031T000000Z" \
      --from 2026-10-31T00:00:00Z --to 2026-10-31T00:10:00Z --dry-run)
[[ "$out" == *"root@192.168.0.111"* ]] || fail "qa-run-collect dry run: $out"
[[ "$out" == *"id_slums_lxc"* ]] || fail "qa-run-collect key: $out"
out=$(SLUMS_TEST_HOST=mapletest QA_RUNS_ROOT="$d/runs" "$here/qa-run-collect" \
      --run "$d/runs/2026-10-31/y-20261031T000000Z" --from 2026-10-31T00:00:00Z --to 2026-10-31T00:10:00Z --dry-run)
[[ "$out" == *"root@mapletest"* ]] || fail "qa-run-collect override: $out"

# 6. deploy-test --dry-run names the host it would deploy to (no ssh, --since given).
cd "$(git -C "$here" rev-parse --show-toplevel)"
out=$("$here/deploy-test" --dry-run --since HEAD 2>&1)
[[ "$out" == *"to root@192.168.0.164"* ]] || fail "deploy-test dry run: $out"
out=$(SLUMS_TEST_HOST=mapletest "$here/deploy-test" --dry-run --since HEAD 2>&1)
[[ "$out" == *"to root@mapletest"* ]] || fail "deploy-test override: $out"

echo "PASS"
