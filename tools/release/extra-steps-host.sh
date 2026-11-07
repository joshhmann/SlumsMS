#!/bin/bash
# Run (or check) a staged release's extra steps ON THE HOST. The one entry point release-live.sh and `release` use on
# release night; rehearse-host.sh calls run_extra_steps (lib.sh) the same way. The contract is written in lib.sh.
#
#   bash <version dir>/tools/extra-steps-host.sh <version dir> [--check]
#
# --check: no extra steps (exit 0, says so) or syntax + live-path lint of staging/extra/, runs nothing.
# Otherwise runs staging/extra/extra-steps.sh with SRV/SQL_CMD from the environment (defaults: live) and exits with its
# exit code. Output kept in <version dir>/extra-steps-<UTC>.log.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd); . "$here/lib.sh"
D=${1:-}; [ -d "$D/staging" ] || die "usage: extra-steps-host.sh <version dir> [--check]"
X=$D/staging/extra
[ ! -e "$D/extra/extra-steps.sh" ] || die "$D/extra/extra-steps.sh sits outside staging/ (never rehearsed, not in SHA256SUMS): put it in the release conf (EXTRA_STEPS) and restage"
if [ ! -f "$X/extra-steps.sh" ]; then echo "no extra steps staged"; exit 0; fi
bash -n "$X/extra-steps.sh" || die "extra-steps.sh has a syntax error"
extra_steps_lint "$X" || die "extra steps hard-code a live path"
if [ "${2:-}" = --check ]; then echo "extra steps: present, syntax ok, no live path"; exit 0; fi
(cd "$D/staging" && sha256sum -c --quiet SHA256SUMS) || die "staged files do not match SHA256SUMS"
REHEARSAL=${REHEARSAL:-0} run_extra_steps "$X" "$D/extra-steps-$(date -u +%Y%m%dT%H%M%SZ).log"
