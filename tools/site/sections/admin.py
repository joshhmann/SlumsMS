"""The staff dashboard inside the website (admin/; owner, 2026-10-28): the soloMapling dashboard (tools/dashboard/web,
ticket 35) in the site's own look, for GM level 3 and up only.

Private builds only. nginx serves admin/ and admin/data/ (the server's dashboard snapshots) only after the login
service says yes (GET /api/auth/admin: GM level 3+, a sign-in at most 12 h old). A GM signed in longer ago is sent to
sign in again (/login/?again=1&next=..., b055); everyone else gets the 404 page.
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
# the staff pages: the dashboard, the bots, the invite codes and the bug reports
ADMIN_TABS = (("./", "Dashboard", "index"), ("bots", "Bots", "bots"), ("invites", "Invite codes", "invites"),
              ("reports", "Reports", "reports"))
INVITES = """<h1>Invite codes</h1><p class="id">Every code on the server: made here, with <code>!invite</code> or by players with
<code>@invite</code>.</p>
<div class="inv" x-data="xinvites(true)" data-root="../">
<section class="panel"><h2 class="bar">Make a code</h2><div class="in inv-make">
<div class="inv-staff"><label>Works for (days) <input type="number" min="1" max="90" x-model.number="life" :disabled="forever"></label>
<label class="inv-check"><input type="checkbox" x-model="forever"> Never expires</label>
<label>Note (who it's for) <input type="text" maxlength="64" x-model="note" placeholder="e.g. Bob from work"></label>
<button class="btn primary" type="button" @click="make()" :disabled="busy">Make a code</button></div>
<div class="inv-new" x-show="made" x-cloak><b class="inv-code" x-text="made && made.code"></b>
<button class="btn" type="button" @click="copy(made.code)">Copy</button>
<p class="small" x-show="made" x-text="made && made.expiresAt === null ? 'Never expires.' : 'Works until ' + when(made && made.expiresAt) + '.'"></p></div>
<p class="status" :class="msgClass" role="status" aria-live="polite" x-text="msg"></p></div></section>
<section class="panel"><h2 class="bar">All codes <small x-show="page > 1" x-text="'page ' + page"></small></h2>
<div class="tbl"><table class="t"><thead><tr><th>Code</th><th>Made by</th><th>Note</th><th>Made</th><th>Works until</th><th>Status</th><th></th></tr></thead>
<tbody><template x-for="c in codes" :key="c.code"><tr><td><b class="inv-code" x-text="c.code"></b></td>
<td x-text="c.owner || (c.ownerAccount ? 'account ' + c.ownerAccount : '')"></td><td x-text="c.note || ''"></td><td x-text="when(c.createdAt)"></td>
<td x-text="until(c)"></td><td x-text="status(c)"></td>
<td><button class="btn" type="button" x-show="open(c)" @click="copy(c.code)">Copy</button>
<button class="btn" type="button" x-show="open(c)" @click="cancel(c)" :disabled="busy">Cancel</button></td></tr></template></tbody></table></div>
<p class="empty" x-show="state === 'ok' && !codes.length" style="padding:12px 16px;margin:0">No codes yet.</p>
<p class="small" style="padding:8px 16px;margin:0"><button class="btn" type="button" x-show="page > 1" @click="go(-1)">Newer</button>
<button class="btn" type="button" x-show="more" @click="go(1)">Older</button></p></section>
</div>
<script src="../assets/invites.js"></script><script defer src="../assets/vendor/alpine-3.14.1.min.js"></script>"""
# every report and idea (report_service.py's staff reader); all text goes in with x-text, never as HTML
REPORTS = """<h1>Reports</h1><p class="id">Bug reports and ideas from the website's Report page and from <code>@bug</code> /
<code>@idea</code> in game. Read only. No IP hashes, e-mails or browser details are shown here.</p>
<div class="rp" x-data="xreports" data-root="../">
<section class="panel"><h2 class="bar">Find <small x-show="state === 'ok'" x-text="found + ' of ' + total"></small></h2>
<form class="in rp-find" @submit.prevent="load()">
<label>Kind <select x-model="type" @change="load()"><option value="">All</option><option value="bug">Bugs</option><option value="feature">Ideas</option></select></label>
<label>From <select x-model="source" @change="load()"><option value="">Anywhere</option><option value="site">Website</option><option value="ingame">In game</option></select></label>
<label>Search <input type="search" maxlength="100" x-model="q" placeholder="title, text, character, B-12"></label>
<button class="btn" type="submit">Search</button></form>
<p class="status bad" style="padding:0 16px 12px" x-show="state === 'error' || msg" x-cloak x-text="msg"></p></section>
<section class="panel rp-read" id="rp-read" x-show="cur" x-cloak><h2 class="bar"><span x-text="cur && (label(cur) + ' · ' + kind(cur) + ' · ' + from(cur))"></span>
<button class="btn" type="button" @click="close()">Close</button></h2><div class="in" x-show="cur">
<h3 class="rp-title" x-text="cur && cur.title"></h3>
<dl class="rp-meta"><dt>Sent</dt><dd x-text="cur && (when(cur.time) + ' (' + cur.time + ')')"></dd>
<dt>Character</dt><dd x-text="cur && (cur.character || 'not given')"></dd>
<dt>Where</dt><dd x-text="cur && (cur.where || 'not given')"></dd>
<dt>Versions</dt><dd x-text="vers(cur)"></dd></dl>
<pre class="rp-text" x-text="cur && (cur.details || '(no details)')"></pre>
<template x-if="ctx && !ctx.missing && !ctx.tooBig && !ctx.unreadable"><div class="rp-ctx">
<h3>Captured in game</h3><p x-show="who(ctx)" x-text="who(ctx)"></p>
<details x-show="events(ctx).length"><summary x-text="'Last ' + events(ctx).length + ' things they did'"></summary>
<div class="tbl"><table class="t"><thead><tr><th>Time</th><th>What</th><th>Id</th><th>Name</th><th>Note</th></tr></thead><tbody>
<template x-for="(e, i) in events(ctx)" :key="i"><tr><td x-text="e.t"></td><td x-text="e.kind"></td><td x-text="e.id"></td>
<td x-text="e.name || ''"></td><td x-text="(e.note || '') + (e.count > 1 ? ' x' + e.count : '')"></td></tr></template></tbody></table></div></details>
<details x-show="ctx.serverLog && ctx.serverLog.length"><summary>Server log</summary><pre class="rp-text" x-text="(ctx.serverLog || []).join('\\n')"></pre></details>
<template x-for="k in logs(ctx)" :key="k"><details><summary x-text="'Client log: ' + k"></summary><pre class="rp-text" x-text="ctx.clientLogs.sections[k]"></pre></details></template>
<details><summary>Everything captured (raw)</summary><pre class="rp-text" x-text="pretty(ctx)"></pre></details></div></template>
<p class="small" x-show="ctx && (ctx.missing || ctx.tooBig || ctx.unreadable)" x-text="ctx && (ctx.missing ? 'The in-game context file is gone.' : ctx.tooBig ? 'The in-game context file is too big to show here.' : 'The in-game context file could not be read.')"></p>
</div></section>
<section class="panel"><h2 class="bar">Reports</h2>
<div class="tbl"><table class="t rp-list"><thead><tr><th>Ref</th><th>Title</th><th>Sent</th><th>Kind</th><th>From</th><th>Character</th><th>Versions</th></tr></thead>
<tbody><template x-for="r in rows" :key="r.id"><tr :aria-current="cur && cur.id === r.id ? 'true' : null">
<td class="rp-nw"><a :href="'#r' + r.id" x-text="label(r)"></a></td><td class="rp-ti"><a :href="'#r' + r.id" x-text="r.title || '(no title)'"></a></td>
<td class="rp-nw" x-text="when(r.time)"></td><td x-text="kind(r)"></td><td x-text="from(r)"></td><td x-text="r.character || ''"></td><td class="small" x-text="vers(r)"></td></tr></template></tbody></table></div>
<p class="empty" x-show="state === 'ok' && !rows.length" style="padding:12px 16px;margin:0">No reports match.</p>
<p class="empty" x-show="state === 'loading'" style="padding:12px 16px;margin:0">Loading...</p>
<p class="small" style="padding:8px 16px;margin:0" x-show="more"><button class="btn" type="button" @click="load(true)">Older</button></p></section>
</div>
<script src="../assets/reports.js"></script><script defer src="../assets/vendor/alpine-3.14.1.min.js"></script>"""
# 1.6.2 ticket 157: the game's read-only bot inspect (login_service.py's staff proxy) and the kill calibration
# (admin/data/calibration.json); all text goes in with x-text, never as HTML
BOTS = """<h1>Bots</h1><p class="id">What the bots are doing right now, read from the game. Read only: nothing here changes
the game. Real players are counted, never listed.</p>
<div class="bt" x-data="xbots" data-root="../">
<section class="panel"><h2 class="bar">Find</h2>
<form class="in rp-find" @submit.prevent="find()">
<label>Bot id or map id <input type="search" inputmode="numeric" maxlength="13" x-model="q" placeholder="1000000012 or 100000000"></label>
<button class="btn" type="submit">Open</button>
<button class="btn" type="button" @click="refresh()">Refresh</button>
<label class="inv-check bt-auto"><input type="checkbox" x-model="auto" @change="toggleAuto()"> Every 10 s</label></form>
<p class="status bad" style="padding:0 16px 12px;margin:0" x-show="msg" x-cloak><span x-text="msg"></span>
<a x-show="again" :href="'../login/?again=1&next=' + encodeURIComponent(location.pathname + location.hash)">Sign in again</a></p></section>

<section class="panel" id="bt-bot" x-show="view === 'bot' && bot" x-cloak><h2 class="bar"><span x-text="bot && who(bot)"></span>
<button class="btn" type="button" @click="close()">Close</button></h2><div class="in" x-show="bot">
<dl class="rp-meta"><dt>Id</dt><dd x-text="bot && bot.cid"></dd>
<dt>Kind</dt><dd x-text="bot && ((bot.kind || '') + (bot.ownedAlt ? ' (owned alt)' : ''))"></dd>
<dt>Where</dt><dd><a href="#" @click.prevent="go('#m' + bot.map)" x-show="bot && bot.map" x-text="bot && where(bot)"></a></dd>
<dt>Map tier</dt><dd x-text="bot && (bot.lod || '')"></dd>
<dt>Doing</dt><dd x-text="bot && [bot.doing, bot.lifePhase].filter(Boolean).join(' · ')"></dd>
<dt>Grinding</dt><dd x-text="bot && yes(bot.grinding)"></dd>
<dt>Movement</dt><dd x-text="bot && (bot.nav || '')"></dd>
<dt>Tick cost</dt><dd x-text="bot && tick(bot.tick)"></dd>
<dt>Gear pick</dt><dd x-text="bot && (bot.gear || '')"></dd>
<dt>Remembers</dt><dd x-text="bot && memory(bot.memory)"></dd>
<dt>Read at</dt><dd x-text="bot && clock(bot.at)"></dd></dl>
<template x-if="bot && bot.bartle"><div><h3>Personality</h3><dl class="rp-meta">
<template x-for="r in bartle(bot.bartle)" :key="r[0]"><div class="bt-pair"><dt x-text="r[0]"></dt><dd x-text="r[1]"></dd></div></template></dl></div></template>
<template x-if="bot && bot.goals && bot.goals.length"><div><h3>Goals</h3><div class="tbl"><table class="t"><thead><tr><th>Kind</th><th>Goal</th><th>Pressure</th><th>Target level</th></tr></thead>
<tbody><template x-for="(g, i) in bot.goals" :key="i"><tr><td x-text="g.kind"></td><td x-text="g.description"></td><td x-text="num(g.pressure)"></td><td x-text="num(g.targetLevel)"></td></tr></template></tbody></table></div></div></template>
<template x-if="bot && lines(bot.plan).length"><div><h3>Plan</h3><ul class="bt-lines"><template x-for="(l, i) in lines(bot.plan)" :key="i"><li x-text="l"></li></template></ul></div></template>
<template x-if="bot && lines(bot.learning).length"><div><h3>Learning</h3><ul class="bt-lines"><template x-for="(l, i) in lines(bot.learning)" :key="i"><li x-text="l"></li></template></ul></div></template>
<details class="rp-ctx" x-show="bot && lines(bot.why).length"><summary>Why (as <code>!botwhy</code> shows it)</summary><pre class="rp-text" x-text="bot && lines(bot.why).join('\\n')"></pre></details>
<details class="rp-ctx"><summary>Everything (raw)</summary><pre class="rp-text" x-text="pretty(bot)"></pre></details>
</div></section>

<section class="panel" id="bt-map" x-show="view === 'map' && map" x-cloak><h2 class="bar"><span x-text="map && ((mapName(map.map) || 'Map') + ' (' + map.map + ')')"></span>
<button class="btn" type="button" @click="close()">Close</button></h2><div class="in" x-show="map">
<p class="bt-nums"><span x-text="map && count(map.botCount, 'bot')"></span><span x-text="map && count(map.players, 'player')"></span>
<span x-text="map && count(map.mobCount, 'mob')"></span><span x-text="map && count(map.chatLines, 'chat line') + ' in 3 min'"></span>
<span>tier <b x-text="map && (map.lod || '?')"></b></span><span class="small" x-text="map && 'read at ' + clock(map.at)"></span></p>
<div class="tbl"><table class="t"><thead><tr><th>Bot</th><th>Level</th><th>Job</th><th>Ch</th><th>x, y</th><th>Movement</th></tr></thead>
<tbody><template x-for="b in (map && map.bots) || []" :key="b.cid"><tr><td><a href="#" @click.prevent="go('#b' + b.cid)" x-text="b.name || b.cid"></a></td>
<td x-text="b.level"></td><td x-text="b.job"></td><td x-text="b.channel"></td><td x-text="b.x + ', ' + b.y"></td><td x-text="b.nav || ''"></td></tr></template></tbody></table></div>
<p class="small" x-show="map && map.bots && map.botCount > map.bots.length" x-text="map && 'The first ' + map.bots.length + ' by id.'"></p>
<details class="rp-ctx" x-show="map && map.mobs && map.mobs.length"><summary x-text="map && 'Mobs (' + ((map.mobs || []).length) + ' shown)'"></summary>
<div class="tbl"><table class="t"><thead><tr><th>Mob</th><th>Ch</th><th>x, y</th><th>HP</th></tr></thead>
<tbody><template x-for="(m, i) in (map && map.mobs) || []" :key="i"><tr><td x-text="m.id"></td><td x-text="m.channel"></td><td x-text="m.x + ', ' + m.y"></td><td x-text="hp(m)"></td></tr></template></tbody></table></div></details>
</div></section>

<section class="panel"><h2 class="bar">Where the bots are <small x-show="pos" x-text="pos && (num(pos.count) + ' online · ' + clock(pos.at))"></small></h2>
<div class="tbl" x-show="top.length"><table class="t"><thead><tr><th>Map</th><th>Id</th><th>Bots</th></tr></thead>
<tbody><template x-for="m in top" :key="m.id"><tr><td><a href="#" @click.prevent="go('#m' + m.id)" x-text="mapName(m.id) || 'Map ' + m.id"></a></td><td x-text="m.id"></td><td x-text="num(m.n)"></td></tr></template></tbody></table></div>
<p class="empty" x-show="state === 'loading'" style="padding:12px 16px;margin:0">Loading...</p>
<p class="empty" x-show="state === 'ok' && !top.length" style="padding:12px 16px;margin:0">No bots online.</p>
<p class="small" style="padding:8px 16px;margin:0" x-show="top.length">The 25 busiest maps.</p></section>

<section class="panel"><h2 class="bar">Kill calibration <small x-show="cal" x-text="cal && ('report only · ' + num(cal.totals && cal.totals.killsSeen) + ' kills seen')"></small></h2>
<div class="in"><p class="small" style="margin:0 0 10px">How fast grinding bots really kill on screen, beside what the offscreen
model pays for the same bot and map. Near 1 is right; well above 1 the offscreen grind pays too little (EXP, mesos, drops and
Most hunted all follow kills), well below it pays too much. Nothing tunes itself from this.</p>
<p class="empty" x-show="calState === 'none'" style="margin:0">No calibration yet: the server writes it only while kill calibration is on (TEST).</p>
<p class="empty" x-show="calState === 'error'" style="margin:0">The calibration file didn't load.</p></div>
<div class="tbl" x-show="cal && cal.buckets.length"><table class="t"><thead><tr><th>Job</th><th>Levels</th><th>Kills</th><th>Minutes</th><th>Real / min</th><th>Model / min</th><th>Ratio</th></tr></thead>
<tbody><template x-for="(b, i) in (cal && cal.buckets) || []" :key="i"><tr><td x-text="b.job || b.jobId"></td><td x-text="b.band"></td><td x-text="num(b.kills)"></td>
<td x-text="typeof b.activeMinutes === 'number' ? b.activeMinutes.toFixed(1) : ''"></td><td x-text="typeof b.realKpm === 'number' ? b.realKpm.toFixed(2) : ''"></td>
<td x-text="typeof b.modelKpm === 'number' ? b.modelKpm.toFixed(2) : ''"></td><td><b :class="ratioClass(b)" x-text="ratio(b)"></b></td></tr></template></tbody></table></div></section>
</div>
<script src="../assets/bots.js"></script><script defer src="../assets/vendor/alpine-3.14.1.min.js"></script>"""
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
    write(out, f"{ADMIN}/bots.html", layout("Bots", tabs("bots") + BOTS, "../", "admin",
                                             "What the bots are doing right now, read from the game."))
    write(out, f"{ADMIN}/invites.html", layout("Invite codes", tabs("invites") + INVITES, "../", "admin",
                                                "Every invite code on the server."))
    write(out, f"{ADMIN}/reports.html", layout("Reports", tabs("reports") + REPORTS, "../", "admin",
                                                "Every bug report and idea, from the website and from in game."))
    return {"web": web, "scripts": len(SCRIPTS), "colours": len(vs)}
