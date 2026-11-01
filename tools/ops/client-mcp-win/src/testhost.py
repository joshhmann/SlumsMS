"""The one guard on where the QA tools point: SLUMS_TEST_HOST, allowlisted to TEST's two boxes, port 8489.

TEST left 164 in 1.6.3: mapletest (192.168.0.111) is TEST's own box, with the same paths and ports. The played TEST
stays on 164 until the cutover (o03 step 3), so DEFAULT_HOST is 164 today and flips to 192.168.0.111 then. Live runs
on 164 too, on login port 8485, which is why the port is part of the guard: 164:8489 is TEST, 164:8485 is live.

Every reader goes through here, so no tool can be aimed at live, at a name ssh resolves elsewhere, or at a typo:
  tools/qa-runner/qarunner.py        test_host() / test_ssh() / SshFeed / read_deployed_commit
  tools/ops/client-mcp-win/src/driver.py   test_host() / verify_config()
  tools/ops/lib.sh                   TEST_HOST / TEST_SSH_OPTS (sourced by deploy-test, autoqa-run, test-status,
                                     qa-run-collect), through this file's --shell mode
Nothing else may read SLUMS_TEST_HOST.

This module is copied to the QA box beside driver.py, so it imports nothing but the standard library. `python3
testhost.py` prints the host, `--shell` prints "<host> <key mode>" for lib.sh, `--key` the ssh key mode alone; a
refused value exits 2 with the reason on stderr.
"""
import os
import sys

ENV = "SLUMS_TEST_HOST"
# The two boxes the QA tools may talk to. Nothing else: no live name, no other IP, no empty value.
TEST_HOSTS = ("192.168.0.164", "192.168.0.111")
DEFAULT_HOST = "192.168.0.164"  # until the 1.6.3 TEST cutover (o03 step 3); flip to 192.168.0.111 then
DEFAULT_KEY_HOST = "192.168.0.164"  # the one box that keeps ssh's default key (it is live's box too)
TEST_KEY = "~/.ssh/id_slums_lxc"
PORT = "8489"  # TEST's login port; 8485 on 164 is live's
LIVE_PORT = "8485"


class Refused(RuntimeError):
    """A host or port outside TEST. The message names the refused value; callers print it as is."""


def _message(value, source):
    text = value.strip() if isinstance(value, str) else ""
    name = "%s=%r" % (source, value)
    if not text:
        return "%s is empty: the QA tools talk to TEST only, %s or %s, port %s." % (
            name, TEST_HOSTS[0], TEST_HOSTS[1], PORT)
    if "asslorde" in text.lower():
        return ("%s is the live site, not TEST: the QA tools talk to TEST only, %s or %s, port %s."
                % (name, TEST_HOSTS[0], TEST_HOSTS[1], PORT))
    return ("%s is not a TEST box: the QA tools talk to TEST only, %s or %s, port %s."
            % (name, TEST_HOSTS[0], TEST_HOSTS[1], PORT))


def host(value=None, source=None):
    """The TEST box's address, validated. `None` reads SLUMS_TEST_HOST; unset means DEFAULT_HOST, a set-but-empty
    value is refused. `source` names where a value the caller supplies came from, for the refusal."""
    if value is None:
        if ENV not in os.environ:
            return DEFAULT_HOST
        value, source = os.environ[ENV], ENV
    text = value.strip() if isinstance(value, str) else ""
    if text in TEST_HOSTS:
        return text
    raise Refused(_message(value, source or "TEST host"))


def port(value, source="port"):
    """TEST's login port, validated: 8485 is live's."""
    text = value.strip() if isinstance(value, str) else ""
    if text == PORT:
        return text
    raise Refused("%s=%r is refused: the QA tools talk to TEST on port %s only (%s is live's login port)."
                  % (source, value, PORT, LIVE_PORT))


def uses_test_key(name=None):
    """TEST's ssh key unless the box is the one 164, which keeps ssh's default key. Validates it too."""
    return host(name) != DEFAULT_KEY_HOST


def _main(argv):
    try:
        name = host()
    except Refused as error:
        sys.stderr.write(str(error) + "\n")
        return 2
    if argv == ["--shell"]:
        print("%s %s" % (name, "test" if uses_test_key(name) else "default"))
    elif argv == ["--key"]:
        print("test" if uses_test_key(name) else "default")
    elif not argv:
        print(name)
    else:
        sys.stderr.write("usage: testhost.py [--shell | --key]\n")
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(_main(sys.argv[1:]))
