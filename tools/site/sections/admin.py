"""The staff dashboard inside the website (admin/; owner, 2026-10-28): the soloMapling dashboard (tools/dashboard/web,
ticket 35) in the site's own look, for GM level 3 and up only.

Private builds only. nginx serves admin/ and admin/data/ (the server's dashboard snapshots) only after the login
service says yes (GET /api/auth/admin: GM level 3+, a sign-in at most 12 h old); everyone else gets the 404 page.
Nothing on the public site links here: the header's Admin link is added by theme.js when /api/login/me says admin.

The dashboard is a separate app with its own colour names, so it's copied in, not rewritten:
- its colour variables are renamed --d-* (the site already uses --bg, --border, --accent ...), and the ones that have
  a site token (background, surfaces, text, accent, good/bad) follow the site's theme, light/dark and accent included;
  the chart series and map colours stay the dashboard's;
- its rules are scoped to .dash, so its page-wide ones (body, main, buttons) only touch the dashboard;
- its scripts get the same renames (the charts read the colours from the page).
web: the dashboard to copy (on 164, the installed copy: /srv/slums-dashboard/web or /srv/slums-dashboard-test/web,
so the website always shows the dashboard the server writes data for).
"""
import os
import re

from common import layout, write

ADMIN = "admin"
HERE = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
DEFAULT_WEB = os.path.join(HERE, "tools", "dashboard", "web")
SCRIPTS = ("charts.js", "mapview.js", "app.js")
# dashboard colour -> the site token it follows (the rest keep the dashboard's own light/dark values)
FOLLOW = {"bg": "var(--bg)", "surface": "var(--surface)", "surface-2": "var(--surface-2)", "border": "var(--hair)",
          "text": "var(--ink)", "text-2": "var(--muted)", "muted": "var(--muted)", "grid": "var(--hair)",
          "accent": "var(--accent-ink)", "good": "var(--up)", "bad": "var(--down)"}
THEME = """
/* the website's look (sections/admin.py) */
:root { %s }
.dash { display: flex; flex-direction: column; height: calc(100vh - 170px); min-height: 640px;
  font-family: var(--f-body); background: var(--surface); color: var(--ink);
  border: var(--border) solid var(--edge); border-radius: var(--radius); box-shadow: 6px 6px 0 var(--drop); overflow: hidden; }
.dash header.top { background: var(--accent); color: var(--on-accent); border-bottom: var(--border) solid var(--edge); }
.dash header.top .brand, .dash header.top .status { color: var(--on-accent); }
.dash header.top [role=tab] { border-color: transparent; background: transparent; color: var(--on-accent); font-weight: 700; }
.dash header.top [role=tab][aria-selected=true] { background: var(--surface); color: var(--ink); border-color: var(--edge); }
.dash h2, .dash h3 { font-family: var(--f-display); }
"""


def names(css):
    """The dashboard's colour variables, longest first (so --surface-2 is renamed before --surface)."""
    return sorted(set(re.findall(r"--([a-z][a-z0-9-]*)\s*:", css)), key=len, reverse=True)


def rename(text, vs):
    return re.sub(r"--(" + "|".join(map(re.escape, vs)) + r")(?![\w-])", r"--d-\1", text) if vs else text


def scope_selector(sel):
    out = []
    for part in sel.split(","):
        p = part.strip()
        if p.startswith(":root") or p == "[hidden]":
            out.append(p)
        elif p in ("html", "body"):
            out.append(".dash")
        elif p == "main" or p.startswith("main "):
            out.append(".dash .dmain" + p[4:])
        else:
            out.append(".dash " + p)
    return ", ".join(dict.fromkeys(out))


def scope(css):
    """Prefix every rule with .dash (inside @media blocks too); :root blocks stay as they are."""
    out, i = [], 0
    css = re.sub(r"/\*.*?\*/", "", css, flags=re.S)
    while i < len(css):
        j = css.find("{", i)
        if j < 0:
            out.append(css[i:])
            break
        head = css[i:j].strip()
        depth, k = 1, j + 1
        while depth and k < len(css):
            depth += {"{": 1, "}": -1}.get(css[k], 0)
            k += 1
        inner = css[j + 1:k - 1]
        if head.startswith("@media") or head.startswith("@supports"):
            out.append(f"{head} {{\n{scope(inner)}}}\n")
        elif head.startswith("@"):
            out.append(f"{head} {{{inner}}}\n")
        else:
            out.append(f"{scope_selector(head)} {{{inner}}}\n")
        i = k
    return "".join(out)


def drop_followed(css):
    """Remove the dashboard's own values for the colours that follow the site (in every :root block)."""
    return re.sub(r"--d-(" + "|".join(map(re.escape, FOLLOW)) + r")\s*:[^;}]*;?", "", css)


def body_of(html):
    """The dashboard page's body without its scripts; its <main> becomes a div (the site page has its own main)."""
    m = re.search(r"<body[^>]*>(.*)</body>", html, re.S)
    body = m.group(1) if m else html
    body = re.sub(r"<script\b[^>]*>.*?</script>", "", body, flags=re.S)
    return re.sub(r"<main\b", '<div class="dmain"', body).replace("</main>", "</div>")


def build(out, web=None):
    web = web or DEFAULT_WEB
    with open(os.path.join(web, "index.html"), encoding="utf-8") as f:
        page = f.read()
    with open(os.path.join(web, "style.css"), encoding="utf-8") as f:
        css = f.read()
    vs = names(css)
    css = drop_followed(rename(scope(css), vs))
    css += THEME % " ".join(f"--d-{k}: {v};" for k, v in FOLLOW.items())
    write(out, f"{ADMIN}/dashboard.css", css)
    for js in SCRIPTS:
        with open(os.path.join(web, js), encoding="utf-8") as f:
            write(out, f"{ADMIN}/{js}", rename(f.read(), vs))
    scripts = "".join(f'<script src="{js}"></script>' for js in SCRIPTS)
    html = (f'<link rel="stylesheet" href="dashboard.css"><div class="dash">{body_of(page)}</div>{scripts}')
    write(out, f"{ADMIN}/index.html", layout("Admin", html, "../", "admin", "The staff dashboard."))
    return {"web": web, "scripts": len(SCRIPTS), "colours": len(vs)}
