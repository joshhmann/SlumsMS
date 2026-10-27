#!/usr/bin/env bash
# Website QA against the TEST copy of the site; see qa.py for what it checks. Run on 187 from the repo root.
exec python3 "$(dirname "$0")/qa.py" "$@"
