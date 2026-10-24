#!/usr/bin/env python3
"""Checks for the built public site (dist/site/). Standard library only.

    python3 tools/site/check.py [dist/site]

1. Forbidden strings: nothing private may reach the public site (LAN addresses, test-server ports, the patch
   site's name and credentials, QA tooling). Matching ignores case.
2. Links: every relative href/src must point at a file in the output, and every #fragment at an id on that page.
3. No external requests: no http(s) URLs at all, so there are no trackers, CDNs or remote fonts.
Exit status 1 if anything is found.
"""
import os
import re
import sys
from html.parser import HTMLParser
from urllib.parse import unquote, urlsplit

# The list the owner asked for, then a few more of the same kind.
FORBIDDEN = [
    "192.168.", "164", ":8489", ":7585", "access key", "htpasswd", "slumspatch", "password", "AutoQa", "!qa",
    # extras: other private addresses, paths and leftovers from the internal wiki
    "slums-patch", "slumsms.asslorde.com", ":8485", ":3309", "/opt/", "/srv/", "/root/", "/etc/", "cloud-llm",
    "claude.ai/artifact", "owner check", 'class="owner"', "test server", "test-server",
]

# Places where a forbidden string is harmless data, matched exactly. Keep this list short and explain each entry.
ALLOW = [
    # "Where to train": a map whose monsters are level 164 (Creepy Humming Forest Trail). Not an address.
    ("guide/leveling.html", "<td>164</td>"),
]

TEXT_EXT = {".html", ".css", ".svg", ".txt", ".xml", ".json", ".js", ".md"}


def iter_files(root):
    for dirpath, _, files in os.walk(root):
        for name in sorted(files):
            path = os.path.join(dirpath, name)
            yield os.path.relpath(path, root).replace(os.sep, "/"), path


def check_forbidden(root):
    problems = []
    for rel, path in iter_files(root):
        if os.path.splitext(rel)[1].lower() not in TEXT_EXT:
            continue
        with open(path, encoding="utf-8", errors="replace") as f:
            text = f.read()
        for page, literal in ALLOW:
            if page == rel:
                text = text.replace(literal, "")
        low = text.lower()
        for bad in FORBIDDEN:
            i = low.find(bad.lower())
            while i != -1:
                line = low.count("\n", 0, i) + 1
                snippet = text[max(0, i - 30):i + len(bad) + 30].replace("\n", " ")
                problems.append(f"{rel}:{line}: forbidden {bad!r}: ...{snippet}...")
                i = low.find(bad.lower(), i + 1)
    return problems


class _Refs(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.refs, self.ids = [], set()

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if a.get("id"):
            self.ids.add(a["id"])
        if tag == "a" and a.get("name"):
            self.ids.add(a["name"])
        for key in ("href", "src"):
            if a.get(key) is not None:
                self.refs.append((tag, a[key]))

    handle_startendtag = handle_starttag


def _parse(path, cache):
    if path not in cache:
        p = _Refs()
        with open(path, encoding="utf-8") as f:
            p.feed(f.read())
        cache[path] = p
    return cache[path]


def check_links(root):
    problems, cache = [], {}
    css_url = re.compile(r"url\(\s*['\"]?([^'\")]+)['\"]?\s*\)")
    for rel, path in iter_files(root):
        ext = os.path.splitext(rel)[1].lower()
        if ext == ".html":
            # <base href="/"> on 404.html is the one root-relative URL allowed: that page is served at any depth
            refs = [r for tag, r in _parse(path, cache).refs if not (tag == "base" and r == "/")]
        elif ext == ".css":
            with open(path, encoding="utf-8") as f:
                refs = css_url.findall(f.read())
        else:
            continue
        for ref in refs:
            ref = ref.strip()
            parts = urlsplit(ref)
            if parts.scheme or ref.startswith("//"):
                if parts.scheme not in ("mailto", "data"):
                    problems.append(f"{rel}: external URL {ref!r} (the site loads nothing from elsewhere)")
                continue
            if ref.startswith("/"):
                problems.append(f"{rel}: root-relative link {ref!r} (use a relative link so the site works anywhere)")
                continue
            if parts.path:
                target = os.path.normpath(os.path.join(os.path.dirname(path), unquote(parts.path)))
                if ref.endswith("/") or os.path.isdir(target):
                    target = os.path.join(target, "index.html")
            else:
                target = path
            if not os.path.abspath(target).startswith(os.path.abspath(root) + os.sep):
                problems.append(f"{rel}: link {ref!r} leaves the site")
                continue
            if not os.path.isfile(target):
                problems.append(f"{rel}: broken link {ref!r}")
                continue
            if parts.fragment and target.endswith(".html"):
                if unquote(parts.fragment) not in _parse(target, cache).ids:
                    problems.append(f"{rel}: link {ref!r} points at a missing #{parts.fragment}")
    return problems


def check_all(root):
    return check_forbidden(root) + check_links(root)


def main(argv):
    root = argv[1] if len(argv) > 1 else os.path.join(os.path.dirname(__file__), "..", "..", "dist", "site")
    problems = check_all(root)
    for p in problems:
        print(p)
    print(f"site check: {len(problems)} problem(s) in {os.path.relpath(root)}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
