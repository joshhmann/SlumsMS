"""Helpers for stage-client.

    stage_client_lib.py <slums-build.txt>   prints "<release> <build>" (exit 1 without a valid build= line)
    stage_client_lib.py --data <SPEC>       prints "<windows path>\t<client path>" (exit 1 when SPEC is not valid)
"""
import re
import sys

# A --data SPEC is 'Z:\...\file.img=Data/<dir>/.../<file>.img': a plain Windows path on the build desktop, and where the
# file goes in the client tree. Only .img files under Data/, no '..', no odd characters: these paths end up in shell
# commands on the host and in the published file list.
WIN_PATH = re.compile(r"[A-Za-z]:\\[^'\"=\r\n]+")
CLIENT_PATH = re.compile(r'Data(/[A-Za-z0-9_][A-Za-z0-9_.-]*)+\.img')


def parse(text):
    """release and build from slums-build.txt; build is None when missing or not a non-negative integer."""
    found = {}
    for line in text.splitlines():
        key, sep, value = line.strip().partition('=')
        if sep and key.strip() not in found:
            found[key.strip()] = value.strip()
    build = found.get('build', '')
    return found.get('release', ''), int(build) if re.fullmatch(r'[0-9]{1,9}', build) else None


def data_spec(spec):
    """(windows path, client path) for a --data SPEC, or None when it is not valid."""
    win, sep, rel = spec.rpartition('=')
    if not sep or not WIN_PATH.fullmatch(win) or not CLIENT_PATH.fullmatch(rel) or '..' in rel.split('/'):
        return None
    return win, rel


if __name__ == '__main__':
    if sys.argv[1:2] == ['--data']:
        got = data_spec(sys.argv[2]) if len(sys.argv) == 3 else None
        if got is None:
            sys.exit(1)
        print('\t'.join(got))
        sys.exit(0)
    with open(sys.argv[1], encoding='ascii') as f:
        release, build = parse(f.read())
    if build is None or not release:
        sys.exit(1)
    print(release, build)
