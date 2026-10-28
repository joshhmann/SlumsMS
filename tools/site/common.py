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


def strf(d, fmt):
    """strftime with %-d and %-I (no leading zero) on every OS: Windows' strftime refuses the "-" flag."""
    fmt = fmt.replace("%-d", str(d.day))
    if "%-I" in fmt:
        fmt = fmt.replace("%-I", str(int(d.strftime("%I"))))
    return d.strftime(fmt)


# ── layout ───────────────────────────────────────────────────────────────────────────────────────

NAV = [("index.html", "Home", "home"), ("patch-notes/", "News", "notes"), ("world/", "World", "world"),
       ("guide/", "Guides", "guide"), ("database/", "Slums Street", "database"), ("market/", "Slums Market", "market"),
       ("report/", "Report", "report")]  # "How to play" is the home page's first button
# World needs the server's site export, and Slums Street (the database) and Slums Market are for signed-in players:
# each shows in the nav only in a build that includes it.
OPTIONAL = {"world", "database", "market"}
BUILT = set()  # optional sections in the current build, set by build.py

# The megaphone rail under the header: the latest releases, then these lines. The market feed joins it later.
RAIL_EXTRA = ["<i>Bera</i> 5 channels · 3x EXP · 10x meso · 4x drop",
              "<i>New here?</i> type @guide in game for the quick tour"]
RAIL = []  # filled by build(): list of (html, href or None) for the current build

# Applies a saved light/dark and accent choice before the page paints, so a reload never flashes the wrong look.
THEME_BOOT = ('<script>try{var d=document.documentElement,t=localStorage.getItem("slums-theme"),'
              'a=localStorage.getItem("slums-accent");if(t==="light"||t==="dark")d.setAttribute("data-theme",t);'
              'if(a==="blue"||a==="orange"||a==="slumschan")d.setAttribute("data-accent",a)}catch(e){}</script>')

# The light/dark switch shows icons: half-filled circle (follow the system), sun, moon. The name is in aria-label/title.
_SVG = '<svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">'
THEME_BTNS = [("system", "Auto (follow your device)", '<circle cx="12" cy="12" r="8"/><path d="M12 4a8 8 0 0 1 0 16z" fill="currentColor"/>'),
              ("light", "Light", '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4'
                                 'M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>'),
              ("dark", "Dark", '<path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z"/>')]

ACCENTS = [("green", "Henesys green"), ("blue", "Ultramarine"), ("orange", "Maple orange")]
# Unlocked by finding all three secrets (theme.js); its button stays hidden until then.
SECRET_ACCENT = ("slumschan", "Slums-chan")


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
<meta name="robots" content="noindex, nofollow, noarchive, noimageindex">
{THEME_BOOT}
<link rel="icon" href="{root}assets/brand/badge-32.png" sizes="32x32" type="image/png">
<link rel="icon" href="{root}assets/brand/badge-64.png" sizes="64x64" type="image/png">
<link rel="apple-touch-icon" href="{root}assets/brand/badge-180.png">
<link rel="preload" href="{root}assets/fonts/bricolage-grotesque.woff2" as="font" type="font/woff2" crossorigin>
<link rel="stylesheet" href="{root}assets/tokens.css">
<link rel="stylesheet" href="{root}assets/site.css">
<script src="{root}assets/theme.js" defer></script>
<script src="{root}assets/ticker.js" defer></script>
</head>
<body>
<a class="skip" href="#main">Skip to content</a>
<header class="site-head"><div class="wrap">
  <a class="brand" href="{root}index.html"><img src="{root}assets/brand/wordmark.webp" srcset="{root}assets/brand/wordmark.webp 1x, {root}assets/brand/wordmark-2x.webp 2x" alt="SlumsMS" width="173" height="56"></a>
  <nav class="site-nav" aria-label="Site">{links}</nav>
  <div class="theme" role="group" aria-label="Colour theme" data-theme-switch hidden>{"".join(
      f'<button type="button" data-theme-btn="{k}" aria-label="{n}" title="{n}">{_SVG}{icon}</svg></button>' for k, n, icon in THEME_BTNS)}</div>
  <div class="accents" role="group" aria-label="Accent colour" data-accent-switch hidden>{"".join(
      f'<button type="button" class="sw {k}" data-accent-btn="{k}" aria-label="{n}" title="{n}"></button>' for k, n in ACCENTS)}<button type="button" class="sw {SECRET_ACCENT[0]}" data-accent-btn="{SECRET_ACCENT[0]}" aria-label="{SECRET_ACCENT[1]}" title="{SECRET_ACCENT[1]}" hidden></button></div>{
  f'<a class="signin" href="{root}login/" data-signin>Sign in</a>' if BUILT & {"database", "market"} else ""}
</div></header>
{rail_html(root)}
<main id="main">
{body}
</main>
<footer class="site-foot">
  <span>SlumsMS is Slums Story: a small private world for friends. A fan project; not affiliated with any game company.</span>
  <span><a href="{root}patch-notes/">News</a> &middot; {f'<a href="{root}world/">World</a> &middot; ' if "world" in BUILT else ""}<a href="{root}guide/">Guides</a> &middot; <a href="{root}report/">Report a bug</a></span>
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
