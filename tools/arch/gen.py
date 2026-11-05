"""Builds the SlumsMS architecture pages from one data file (pages.py) so every page shares one layout and one
origin/state table, and the hub can roll the tables up."""
import html, json, sys, importlib.util, pathlib

HERE = pathlib.Path(__file__).parent
spec = importlib.util.spec_from_file_location("pages", HERE / "pages.py")
pages = importlib.util.module_from_spec(spec); spec.loader.exec_module(pages)
CSS = (HERE / "style.css").read_text()
dspec = importlib.util.spec_from_file_location("deep", HERE / "deep.py")
deep = importlib.util.module_from_spec(dspec); dspec.loader.exec_module(deep)
DEEP = deep.per_system()
FONTS = '<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Fraunces:opsz,wght@9..144,600&family=Source+Sans+3:wght@400;600&family=JetBrains+Mono:wght@500&display=swap">'
ORIGIN = {"base": ("o-base", "base"), "modified": ("o-mod", "modified"), "custom": ("o-custom", "custom")}
STATE = {"working": ("s-ok", "working"), "partial": ("s-part", "partial"), "broken": ("s-bad", "broken"), "unknown": ("s-unk", "unknown")}

def pill(kind, key):
    cls, label = (ORIGIN if kind == "o" else STATE)[key]
    return f'<span class="pill {cls}">{label}</span>'

def table(rows):
    counts = {}
    for _, o, s, _ in rows:
        counts[s] = counts.get(s, 0) + 1
    tot = '<div class="tot">' + "".join(f'{pill("s", k)}<span style="margin-right:8px">{counts[k]}</span>' for k in STATE if k in counts) + "</div>"
    body = "".join(f"<tr><td>{c}</td><td>{pill('o', o)}</td><td>{pill('s', s)}</td><td>{e}</td></tr>" for c, o, s, e in rows)
    return tot + f'<div class="tbl"><table><tr><th>Component</th><th>Origin</th><th>State</th><th>Evidence</th></tr>{body}</table></div>'

def page(p):
    toc = "".join(f'<a href="#{sid}">{html.escape(t)}</a>' for sid, t, _ in p["sections"]) + '<a href="#status">Origin and state</a>'
    if p.get("weak"):
        toc += '<a href="#weak">Holes</a>'
    secs = "".join(f'<section id="{sid}"><h2>{html.escape(t)}</h2>{body}</section>' for sid, t, body in p["sections"])
    weak = ""
    if p.get("weak"):
        weak = '<section id="weak" class="weak"><h2>Holes</h2><ul>' + "".join(f"<li>{w}</li>" for w in p["weak"]) + "</ul></section>"
    dkey = p.get("key")
    deepsec = ""
    if dkey and DEEP.get(dkey):
        toc += '<a href="#deep">Deep review</a>'
        deepsec = f'<section id="deep"><h2>Deep review (Nov 5)</h2>{deep.section(dkey, DEEP[dkey])}</section>'
    hub = f'<p class="foot"><a href="{pages.HUB}">Back to the SlumsMS Systems hub</a></p>' if pages.HUB else ""
    return f"""<title>{html.escape(p['title'])}</title>
<meta name="claude:viewport-fit-sides" content="cover">
{FONTS}
<style>{CSS}</style>
<div class="wrap">
<h1>{html.escape(p['title'])}</h1>
<p class="lede">{p['lede']}</p>
<nav class="toc">{toc}</nav>
<div class="diagram"><pre class="mermaid">{html.escape(p['mermaid'])}</pre></div>
{secs}
<section id="status"><h2>Origin and state</h2><p>Origin: <b>base</b> came with the stock server, client or framework; <b>modified</b> is base we changed; <b>custom</b> is ours. State is from code, tests, TEST/live checks and the book's cards on Nov 5.</p>{table(p['rows'])}</section>
{weak}
{deepsec}
<p class="foot">{p['sources']}</p>
{hub}
</div>"""

out = HERE / "out"; out.mkdir(exist_ok=True)
summary = {}
for key, p in pages.PAGES.items():
    p["key"] = key
    (out / f"{key}.html").write_text(page(p))
    c = {}
    for _, o, s, _ in p["rows"]:
        c.setdefault(s, 0); c[s] += 1
        c.setdefault("o_" + o, 0); c["o_" + o] += 1
    summary[key] = {"title": p["title"], "counts": c, "holes": [r for r in p["rows"] if r[2] in ("broken", "partial", "unknown")]}
(out / "summary.json").write_text(json.dumps(summary, indent=1))
print({k: v["counts"] for k, v in summary.items()})
