#!/usr/bin/env python3
"""Read-only: warn when the launcher's patch feed is behind the site's newest release page.
  python3 tools/site/feed_check.py [--feed PATH | --ssh HOST] [--releases DIR]
The feed is manifest.json ("version" and a "news" list); the site's newest release is the highest version in
docs/site/releases/*.md. Exit 0 when the feed's version and newest news title match it, 1 when behind, 2 when unreadable.
Never writes anything, never prints more than versions and titles."""
import argparse
import json
import os
import re
import subprocess
import sys

DEFAULT_FEED = "/srv/slums-patch/manifest.json"


def vkey(v):
    return tuple(int(p) for p in re.findall(r"\d+", v))


def newest_release(releases_dir):
    found = []
    for name in os.listdir(releases_dir):
        m = re.fullmatch(r"(\d+(?:\.\d+)+)\.md", name)
        if m:
            found.append(m.group(1))
    if not found:
        raise ValueError("no release pages in " + releases_dir)
    return max(found, key=vkey)


def problems(feed, site_version):
    out = []
    fv = str(feed.get("version", ""))
    if not fv or vkey(fv) < vkey(site_version):
        out.append(f"feed version is {fv or 'missing'}, the site's newest release is {site_version}")
    news = feed.get("news") or []
    top = str(news[0].get("title", "")) if news and isinstance(news[0], dict) else ""
    if site_version not in top:
        out.append(f"feed's newest news entry is '{top or 'none'}', expected one for {site_version}")
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--feed", default=DEFAULT_FEED, help="manifest.json path (default: the live feed on this host)")
    ap.add_argument("--ssh", help="read the feed over ssh from this host instead")
    ap.add_argument("--releases", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "docs", "site", "releases"))
    a = ap.parse_args(argv)
    try:
        site_version = newest_release(a.releases)
        if a.ssh:
            raw = subprocess.check_output(["ssh", "-o", "BatchMode=yes", a.ssh, "cat", a.feed], text=True, timeout=30)
        else:
            with open(a.feed, encoding="utf-8") as f:
                raw = f.read()
        feed = json.loads(raw)
    except (OSError, ValueError, subprocess.SubprocessError) as e:
        print(f"feed check: cannot read ({type(e).__name__})", file=sys.stderr)
        return 2
    bad = problems(feed, site_version)
    for line in bad:
        print("WARNING: " + line)
    if not bad:
        print(f"feed check: feed and site both at {site_version}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
