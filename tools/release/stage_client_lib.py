"""Parse a client build's slums-build.txt (release=/build= lines) for stage-client. Prints "<release> <build>"."""
import re
import sys


def parse(text):
    """release and build from slums-build.txt; build is None when missing or not a non-negative integer."""
    found = {}
    for line in text.splitlines():
        key, sep, value = line.strip().partition('=')
        if sep and key.strip() not in found:
            found[key.strip()] = value.strip()
    build = found.get('build', '')
    return found.get('release', ''), int(build) if re.fullmatch(r'[0-9]{1,9}', build) else None


if __name__ == '__main__':
    with open(sys.argv[1], encoding='ascii') as f:
        release, build = parse(f.read())
    if build is None or not release:
        sys.exit(1)
    print(release, build)
