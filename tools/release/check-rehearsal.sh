#!/bin/bash
# The release gate, ON THE HOST: exit 0 only when <version dir>/REHEARSAL.md says Result: PASS and was written for exactly
# the bytes staged now (staging digest + deploy.sh/rollback.sh digest; see rehearsal_gate in lib.sh).
#   bash <version dir>/tools/check-rehearsal.sh <version dir>
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd); . "$here/lib.sh"
[ -d "${1:-}" ] || die "usage: check-rehearsal.sh <version dir>"
rehearsal_gate "$1" || die "no valid rehearsal for what is staged in $1"
echo "rehearsal receipt matches the staged bytes (staging digest $(staging_digest "$1" | cut -c1-16))"
