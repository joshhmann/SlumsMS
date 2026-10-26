"""Shared by every section of the site build: paths, the page layout, the megaphone rail and small text helpers."""
import html
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
SRC = os.path.join(REPO, "docs", "site")
WIKI = os.path.join(REPO, "docs", "wiki")
SITE = "SlumsMS"
esc = html.escape


# ── layout ───────────────────────────────────────────────────────────────────────────────────────

NAV = [("index.html", "Home", "home"), ("patch-notes/", "News", "notes"), ("guide/", "Guides", "guide"),
       ("database/", "Database", "database"), ("market/", "Market", "market"),
       ("report/", "Report", "report"), ("index.html#play", "Play", "play")]
# Database and Market are private until 1.6.0 is live: they show in the nav only in a build that includes them.
OPTIONAL = {"database", "market"}
BUILT = set()  # optional sections in the current build, set by build.py

# The megaphone rail under the header: the latest releases, then these lines. The market feed joins it later.
RAIL_EXTRA = ["<i>Bera</i> 5 channels · 3x EXP · 10x meso · 4x drop",
              "<i>New here?</i> type @guide in game for the quick tour"]
RAIL = []  # filled by build(): list of (html, href or None) for the current build

# Applies a saved light/dark and accent choice before the page paints, so a reload never flashes the wrong look.
THEME_BOOT = ('<script>try{var d=document.documentElement,t=localStorage.getItem("slums-theme"),'
              'a=localStorage.getItem("slums-accent");if(t==="light"||t==="dark")d.setAttribute("data-theme",t);'
              'if(a==="blue"||a==="orange")d.setAttribute("data-accent",a)}catch(e){}</script>')

# The leaf drawn inline so it takes the accent colour (assets/leaf.svg, the favicon, is the same shape in green).
LEAF = ('<svg class="leaf" viewBox="0 0 100 100" width="30" height="30" aria-hidden="true"><path class="leaf-body" '
        'd="M50 3 58 21 70 15 66 39 84 29 80 42 97 44 84 55 90 61 61 63 63 75 53 70 52 97 48 97 47 70 37 75 39 63 '
        '10 61 16 55 3 44 20 42 16 29 34 39 30 15 42 21Z"/><path class="leaf-vein" fill="none" stroke-width="2.5" '
        'stroke-linecap="round" d="M50 92V30M50 62 30 44M50 62 70 44M50 48 40 30M50 48 60 30"/></svg>')
ACCENTS = [("green", "Henesys green"), ("blue", "Ultramarine"), ("orange", "Maple orange")]


def rail_html(root):
    def items(hidden):
        out = []
        for text, href in RAIL:
            attrs = ' aria-hidden="true"' if hidden else ""
            if href:
                tab = ' tabindex="-1"' if hidden else ""
                out.append(f'<a href="{root}{href}"{attrs}{tab}>{text}</a>')
            else:
                out.append(f"<span{attrs}>{text}</span>")
        return "".join(out)
    if not RAIL:
        return ""
    # the list is written twice so the scroll loops without a jump; the copy is hidden from screen readers
    return (f'<div class="rail" aria-label="Megaphone"><b>Megaphone</b><div class="roll"><div>'
            f'{items(False)}{items(True)}</div></div></div>')


def layout(title, body, root, section, description="", base=""):
    """root: relative path from this page to the site root ('' or '../' ...)."""
    links = "".join(
        f'<a href="{root}{href}"{" aria-current=page" if key == section else ""}>{esc(label)}</a>'
        for href, label, key in NAV if key not in OPTIONAL or key in BUILT)
    full = SITE if title == SITE else f"{title} - {SITE}"
    desc = f'\n<meta name="description" content="{esc(description)}">' if description else ""
    return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">{f'\n<base href="{base}">' if base else ""}
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>{esc(full)}</title>{desc}
<meta name="referrer" content="no-referrer">
{THEME_BOOT}
<link rel="icon" href="{root}assets/leaf.svg" type="image/svg+xml">
<link rel="preload" href="{root}assets/fonts/bricolage-grotesque.woff2" as="font" type="font/woff2" crossorigin>
<link rel="stylesheet" href="{root}assets/tokens.css">
<link rel="stylesheet" href="{root}assets/site.css">
<script src="{root}assets/theme.js" defer></script>
</head>
<body>
<a class="skip" href="#main">Skip to content</a>
<header class="site-head"><div class="wrap">
  <a class="brand" href="{root}index.html">{LEAF}<b>Slums<span>MS</span></b></a>
  <nav class="site-nav" aria-label="Site">{links}</nav>
  <div class="theme" role="group" aria-label="Colour theme" data-theme-switch hidden><button type="button" data-theme-btn="system">Auto</button><button type="button" data-theme-btn="light">Light</button><button type="button" data-theme-btn="dark">Dark</button></div>
  <div class="accents" role="group" aria-label="Accent colour" data-accent-switch hidden>{"".join(
      f'<button type="button" class="sw {k}" data-accent-btn="{k}" aria-label="{n}" title="{n}"></button>' for k, n in ACCENTS)}</div>
</div></header>
{rail_html(root)}
<main id="main">
{body}
</main>
<footer class="site-foot">
  <span>SlumsMS is a small private MapleStory v83 world for friends. A fan project, not affiliated with Nexon.</span>
  <span><a href="{root}patch-notes/">News</a> &middot; <a href="{root}guide/">Guides</a> &middot; <a href="{root}report/">Report a bug</a></span>
</footer>
</body>
</html>
"""


def write(out, rel, text):
    path = os.path.join(out, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def meta_comment(text):
    """Leading '<!-- k: v | k: v -->' comment -> (dict, rest)."""
    m = re.match(r"\s*<!--(.*?)-->\s*", text, re.S)
    meta = {}
    if not m:
        return meta, text
    for part in m.group(1).split("|"):
        if ":" in part:
            k, v = part.split(":", 1)
            meta[k.strip()] = v.strip()
    return meta, text[m.end():]



# ── inline markdown ──────────────────────────────────────────────────────────────────────────────

def inline(s):
    s = esc(s, quote=True)
    codes = []

    def keep(m):
        codes.append(m.group(1))
        return f"\x00{len(codes) - 1}\x00"

    s = re.sub(r"`([^`]+)`", keep, s)
    s = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", s)
    s = re.sub(r"(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])", r"<i>\1</i>", s)
    s = re.sub(r"\[([^\]]+)\]\(([^)\s]+)\)", r'<a href="\2">\1</a>', s)
    return re.sub("\x00(\\d+)\x00", lambda m: f"<code>{codes[int(m.group(1))]}</code>", s)


def slug(s):
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-") or "section"
