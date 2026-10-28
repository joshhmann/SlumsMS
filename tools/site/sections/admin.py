"""The staff dashboard inside the website (admin/; owner, 2026-10-28): the soloMapling dashboard (tools/dashboard/web,
ticket 35) in the site's own look, for GM level 3 and up only.

Private builds only. nginx serves admin/ and admin/data/ (the server's dashboard snapshots) only after the login
service says yes (GET /api/auth/admin: GM level 3+, a sign-in at most 12 h old); everyone else gets the 404 page.
Nothing on the public site links here: the header's Admin link is added by theme.js when /api/login/me says admin.

The dashboard is a separate app that shares many class names with the site (.card, .tile, .pill, .status ...), so it
runs as it is in its own page (admin/app.html), framed by the site's page (admin/index.html, header and all):
- app.html loads the site's tokens.css, and the dashboard's colour variables are renamed --d-* (the site already uses
  --bg, --border, --accent ...); the ones that have a site token (background, surfaces, text, accent, good/bad) follow
  it, so light/dark and the accent colour match, and change with the header's switches (it copies the framing page's
  data-theme and data-accent); the chart series and map colours stay the dashboard's;
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
body { font-family: var(--f-body); }
header.top { background: var(--accent); color: var(--on-accent); border-bottom: var(--border) solid var(--edge); }
header.top .brand, header.top .brand span, header.top .status { color: var(--on-accent); }
header.top [role=tab] { border-color: transparent; background: transparent; color: var(--on-accent); font-weight: 700; }
header.top [role=tab][aria-selected=true] { background: var(--surface); color: var(--ink); border-color: var(--edge); }
h2, h3 { font-family: var(--f-display); }
"""
# the staff pages: the dashboard and the invite codes
ADMIN_TABS = (("./", "Dashboard", "index"), ("invites", "Invite codes", "invites"))
INVITES = """<h1>Invite codes</h1><p class="id">Every code on the server: made here, with <code>!invite</code> or by players with
<code>@invite</code>.</p>
<div class="inv" x-data="xinvites(true)" data-root="../">
<section class="panel"><h2 class="bar">Make a code</h2><div class="in inv-make">
<div class="inv-staff"><label>Works for (days) <input type="number" min="1" max="90" x-model.number="life"></label>
<label>Note (who it's for) <input type="text" maxlength="64" x-model="note" placeholder="e.g. Bob from work"></label>
<button class="btn primary" type="button" @click="make()" :disabled="busy">Make a code</button></div>
<div class="inv-new" x-show="made" x-cloak><b class="inv-code" x-text="made && made.code"></b>
<button class="btn" type="button" @click="copy(made.code)">Copy</button>
<p class="small" x-show="made && made.expiresAt" x-text="'Works until ' + when(made && made.expiresAt) + '.'"></p></div>
<p class="status" :class="msgClass" role="status" aria-live="polite" x-text="msg"></p></div></section>
<section class="panel"><h2 class="bar">All codes <small x-show="page" x-text="'page ' + (page + 1)"></small></h2>
<div class="tbl"><table class="t"><thead><tr><th>Code</th><th>Made by</th><th>Note</th><th>Made</th><th>Works until</th><th>Status</th><th></th></tr></thead>
<tbody><template x-for="c in codes" :key="c.code"><tr><td><b class="inv-code" x-text="c.code"></b></td>
<td x-text="c.owner || c.createdBy || ''"></td><td x-text="c.note || ''"></td><td x-text="when(c.createdAt)"></td>
<td x-text="when(c.expiresAt)"></td><td x-text="status(c)"></td>
<td><button class="btn" type="button" x-show="open(c)" @click="copy(c.code)">Copy</button>
<button class="btn" type="button" x-show="open(c)" @click="cancel(c)" :disabled="busy">Cancel</button></td></tr></template></tbody></table></div>
<p class="empty" x-show="state === 'ok' && !codes.length" style="padding:12px 16px;margin:0">No codes yet.</p>
<p class="small" style="padding:8px 16px;margin:0"><button class="btn" type="button" x-show="page" @click="go(-1)">Newer</button>
<button class="btn" type="button" x-show="more" @click="go(1)">Older</button></p></section>
</div>
<script src="../assets/invites.js"></script><script defer src="../assets/vendor/alpine-3.14.1.min.js"></script>"""
FRAME_CSS = """<style>
.adminframe { display: block; width: 100%; height: calc(100vh - 200px); min-height: 640px; background: var(--surface);
  border: var(--border) solid var(--edge); border-radius: var(--radius); box-shadow: 6px 6px 0 var(--drop); }
</style>"""
# in app.html: follow the framing page's light/dark and accent, now and whenever its switches change them
FOLLOW_JS = """<script>(function(){try{var p=window.parent.document.documentElement,d=document.documentElement;
function copy(){["data-theme","data-accent"].forEach(function(a){var v=p.getAttribute(a);if(v)d.setAttribute(a,v);else d.removeAttribute(a);});}
copy();new MutationObserver(copy).observe(p,{attributes:true,attributeFilter:["data-theme","data-accent"]});}catch(e){}})();</script>"""


def names(css):
    """The dashboard's colour variables, longest first (so --surface-2 is renamed before --surface)."""
    return sorted(set(re.findall(r"--([a-z][a-z0-9-]*)\s*:", css)), key=len, reverse=True)


def rename(text, vs):
    return re.sub(r"--(" + "|".join(map(re.escape, vs)) + r")(?![\w-])", r"--d-\1", text) if vs else text


def drop_followed(css):
    """Remove the dashboard's own values for the colours that follow the site (in every :root block)."""
    return re.sub(r"--d-(" + "|".join(map(re.escape, FOLLOW)) + r")\s*:[^;}]*;?", "", css)


def tabs(here):
    return '<p class="chips">' + "".join(
        f'<a class="chip" href="{h}"{" aria-current=page" if k == here else ""}>{t}</a>' for h, t, k in ADMIN_TABS) + "</p>"


def build(out, web=None):
    web = web or DEFAULT_WEB
    with open(os.path.join(web, "index.html"), encoding="utf-8") as f:
        page = f.read()
    with open(os.path.join(web, "style.css"), encoding="utf-8") as f:
        css = f.read()
    vs = names(css)
    css = drop_followed(rename(css, vs))
    css += THEME % " ".join(f"--d-{k}: {v};" for k, v in FOLLOW.items())
    write(out, f"{ADMIN}/dashboard.css", css)
    for js in SCRIPTS:
        with open(os.path.join(web, js), encoding="utf-8") as f:
            write(out, f"{ADMIN}/{js}", rename(f.read(), vs))
    # the dashboard's own page, with the site's tokens and theme; its stylesheet link becomes ours
    if 'href="style.css"' not in page or "</title>" not in page:
        raise SystemExit(f"{web}/index.html: no <title> or style.css link to hang the site's theme on")
    app = page.replace('href="style.css"', 'href="../assets/tokens.css"><link rel="stylesheet" href="dashboard.css"')
    app = app.replace("</title>", '</title><meta name="robots" content="noindex, nofollow">' + FOLLOW_JS, 1).replace("<title>SlumsMS Admin</title>", "<title>Admin</title>")
    write(out, f"{ADMIN}/app.html", app)
    html = (f'{FRAME_CSS}<h1 class="vh">Admin</h1>{tabs("index")}'
            f'<iframe class="adminframe" src="app.html" title="Staff dashboard"></iframe>')
    write(out, f"{ADMIN}/index.html", layout("Admin", html, "../", "admin", "The staff dashboard."))
    write(out, f"{ADMIN}/invites.html", layout("Invite codes", tabs("invites") + INVITES, "../", "admin",
                                                "Every invite code on the server."))
    return {"web": web, "scripts": len(SCRIPTS), "colours": len(vs)}
