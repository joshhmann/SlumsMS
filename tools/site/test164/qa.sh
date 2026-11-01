#!/usr/bin/env bash
# Website QA against the TEST copy of the site; see qa.py for what it checks. Run on 187 from the repo root.
# The TEST host is one setting, SLUMS_TEST_HOST (default 192.168.0.111, "mapletest"; it was 192.168.0.164 until 1.6.3),
# read by qa.py; pass it on the command line or in the environment to check another host.
: "${SLUMS_TEST_HOST:=192.168.0.111}"
export SLUMS_TEST_HOST
exec python3 "$(dirname "$0")/qa.py" "$@"
