#!/usr/bin/env python3
"""Checks for the built public site (dist/site/). Standard library only.

    python3 tools/site/check.py [dist/site]

1. Forbidden strings: nothing private may reach the public site (LAN addresses, test-server ports, the patch
   site's name and credentials, QA tooling) and no real personal information (emails, IP addresses). Matching ignores case. The host "164" is a pattern rather than a
   string, because 164 is also an ordinary number in game data (monster levels, item and map ids).
2. Links: every relative href/src must point at a file in the output, and every #fragment at an id on that page.
3. No external requests: no http(s) URLs at all, so there are no trackers, CDNs or remote fonts.
4. Private sections: a public build has no database/, market/ or secret/ files and no links to them.
5. Friends only: robots.txt disallows everything and every page carries a noindex robots tag.
Exit status 1 if anything is found.
"""
import os
import re
import sys
from html.parser import HTMLParser
from urllib.parse import unquote, urlsplit

# The list the owner asked for, then a few more of the same kind.
FORBIDDEN = [
    "192.168.", ":8489", ":7585", "access key", "htpasswd", "slumspatch", "password", "AutoQa", "!qa",
    # extras: other private addresses, paths and leftovers from the internal wiki
    "slums-patch", "slumsms.asslorde.com", ":8485", ":3309", "/opt/", "/srv/", "/root/", "/etc/", "cloud-llm",
    "claude.ai/artifact", "owner check", 'class="owner"', "test server", "test-server",
    # the public site calls the game Slums Story (owner, 2026-10-26)
    "maplestory", "maple story", "nexon", "v83", "wizet",
    # the server's code base is internal knowledge only (owner, 2026-10-27)
    "mapleroot", "maple root",
]

# The server host "164": as the last part of an address (.164) or named as a machine ("on 164", "164 box").
FORBIDDEN_PATTERNS = [
    re.compile(r"\.164\b"),
    re.compile(r"\b(?:on|port|host|box|server|machine|lxc|ssh|root@)\s*164\b", re.I),
    re.compile(r"\b164\s+(?:box|host|server|machine|lxc)\b", re.I),
    # Real personal information never goes on the site (owner, 2026-10-26): character and guild names are fine,
    # emails and IP addresses are not. Logins, birthdays, PICs and HWIDs are caught as export fields (market.py).
    re.compile(r"[\w.+-]+@[\w-]+(?:\.[\w-]+)*\.[a-z]{2,}\b", re.I),
    re.compile(r"(?<![\w.])(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)(?![\w.])"),
]

# Places where a forbidden string is harmless data, matched exactly. Keep this list short and explain each entry.
ALLOW = [
    # the 1.6.0 notes give the website's address (the owner put it in); it contains the game host's name, which stays banned
    ("patch-notes/1.6.0/index.html", "<b>theslumsms.asslorde.com</b>"),
]

# Forbidden words a single file may use, whole word list for that file only. Keep it short and say why.
WORD_OK = {
    # ticket 105: the sign-in page asks for the game password (it goes to the login service, never into a page)
    "login/index.html": {"password"},
    "assets/login.js": {"password"},
    # ticket 107: the forgot-password page sets a new game password the same way
    "forgot/index.html": {"password"},
    "assets/forgot.js": {"password"},
    # ticket 105: how to join and the getting-started guide explain the sign-up rules
    "join/index.html": {"password"},
    # the staff dashboard (admin/, GM level 3+ only): its "Password resets" panel lists GM-approved reset requests
    "admin/app.html": {"password"},
    "admin/app.js": {"password"},
    "guide/getting-started.html": {"password"},
    # the 1.6.0 notes announce sign-up and password resets on the website (owner-approved text)
    "patch-notes/1.6.0/index.html": {"password"},
}

# Game text that contains a forbidden word, allowed on any page because it is the game's own name for something
# (the database lists every quest). Matched exactly, with the HTML-escaped spellings, so the word alone still fails.
GAME_TEXT = [
    "The Cave's Password", "The Cave&#x27;s Password",  # quest 21730
    "Verifying the Password",                            # quest 3360
]

def _mask(word):
    return word[0] + "*" * (len(word) - 2) + word[-1] if len(word) > 2 else "*" * len(word)


def scrub(obj):
    """Text written in game (character and guild names, megaphones, drama) as the World and market exports carry it:
    forbidden words are masked ("NexonPl0x" -> "N***nPl0x") and emails and IP addresses become "[hidden]", so a
    player's name or megaphone can't put them on the site, nor make a rebuild fail and leave its pages stale. The site's
    own wording still has to pass the check as it is. Numbers are left alone."""
    if isinstance(obj, dict):
        return {k: scrub(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [scrub(v) for v in obj]
    if not isinstance(obj, str) or obj in GAME_TEXT:
        return obj
    for bad in FORBIDDEN:
        if bad.lower() in obj.lower():
            obj = re.sub(re.escape(bad), lambda m: _mask(m.group(0)), obj, flags=re.I)
    for pat in FORBIDDEN_PATTERNS[-2:]:   # emails and IP addresses
        obj = pat.sub("[hidden]", obj)
    for pat in FORBIDDEN_PATTERNS[:-2]:   # the host "164" named as a machine
        obj = pat.sub(lambda m: m.group(0).replace("164", "1*4"), obj)
    return obj


TEXT_EXT = {".html", ".css", ".svg", ".txt", ".xml", ".json", ".js", ".md"}


ONLY = None  # a folder name: check only the files under it (a one-section rebuild); links still resolve site-wide


def iter_files(root):
    for dirpath, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if not d.startswith(".")]  # .stage-*/.old-* folders of a section swap
        rel_dir = os.path.relpath(dirpath, root).replace(os.sep, "/")
        if ONLY and rel_dir != "." and not (rel_dir + "/").startswith(ONLY + "/"):
            dirs[:] = []
            continue
        if ONLY and rel_dir == ".":
            dirs[:] = [d for d in dirs if d == ONLY]
            continue
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
        for literal in GAME_TEXT:
            text = text.replace(literal, "")
        low = text.lower()
        for bad in FORBIDDEN:
            if bad in WORD_OK.get(rel, ()):
                continue
            i = low.find(bad.lower())
            while i != -1:
                line = low.count("\n", 0, i) + 1
                snippet = text[max(0, i - 30):i + len(bad) + 30].replace("\n", " ")
                problems.append(f"{rel}:{line}: forbidden {bad!r}: ...{snippet}...")
                i = low.find(bad.lower(), i + 1)
        for pat in FORBIDDEN_PATTERNS:
            for m in pat.finditer(text):
                line = text.count("\n", 0, m.start()) + 1
                snippet = text[max(0, m.start() - 30):m.end() + 30].replace("\n", " ")
                problems.append(f"{rel}:{line}: forbidden host {m.group(0)!r}: ...{snippet}...")
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
                if parts.path.endswith("/") or (os.path.isdir(target) and not os.path.isfile(target + ".html")):
                    target = os.path.join(target, "index.html")
                elif not os.path.isfile(target) and os.path.isfile(target + ".html"):
                    target += ".html"  # a clean URL (common.clean_links): nginx serves x.html for x
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


# Sections that stay private until 1.6.0 is live. A public build must not contain them or link to them.
PRIVATE_SECTIONS = ["database", "market", "secret", "admin"]


def check_private(root):
    problems = []
    for name in PRIVATE_SECTIONS:
        if os.path.exists(os.path.join(root, name)):
            problems.append(f"{name}/: private section in a public build (build it with --private instead)")
    for rel, path in iter_files(root):
        if rel.endswith(".html"):
            with open(path, encoding="utf-8") as f:
                text = f.read()
            for name in PRIVATE_SECTIONS:
                if re.search(rf'href="(?:\.\./)*{name}/', text):
                    problems.append(f"{rel}: links to the private section {name}/")
    return problems


NOINDEX = '<meta name="robots" content="noindex'


def check_noindex(root):
    """The site is friends only: robots.txt turns crawlers away and every page says noindex."""
    problems = []
    robots = os.path.join(root, "robots.txt")
    if not ONLY and (not os.path.isfile(robots) or
                     "Disallow: /" not in open(robots, encoding="utf-8").read().splitlines()):
        problems.append("robots.txt: missing, or doesn't say 'Disallow: /'")
    for rel, path in iter_files(root):
        if rel.endswith(".html"):
            with open(path, encoding="utf-8") as f:
                if NOINDEX not in f.read(4096):
                    problems.append(f"{rel}: no noindex robots tag in <head>")
    return problems


def check_all(root, private=False, only=None):
    global ONLY
    ONLY = only
    try:
        return (check_forbidden(root) + check_links(root) + check_noindex(root) +
                ([] if private or only else check_private(root)))
    finally:
        ONLY = None


def main(argv):
    root = argv[1] if len(argv) > 1 else os.path.join(os.path.dirname(__file__), "..", "..", "dist", "site")
    problems = check_all(root)
    for p in problems:
        print(p)
    print(f"site check: {len(problems)} problem(s) in {os.path.relpath(root)}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
