#!/usr/bin/env python3
"""Render a SlumsMS player patch-notes draft (the site's release markdown) to a review page for an Artifact.

usage: render_notes.py <notes.md> <out.html> ["owner questions, one line"]
The draft's trailing <!-- REVIEW ... --> block is left out of the page (it is for the coordinator). No dependencies.
"""
import html
import re
import sys

src_path, out_path = sys.argv[1], sys.argv[2]
ask = sys.argv[3] if len(sys.argv) > 3 else ""
src = open(src_path, encoding="utf-8").read()
_, fm, body = src.split("---", 2)
meta = dict(l.split(": ", 1) for l in fm.strip().splitlines() if ": " in l)
body = body.split("<!-- REVIEW")[0]


def inline(t):
    t = html.escape(t)
    t = re.sub(r"`([^`]+)`", r"<code>\1</code>", t)
    t = re.sub(r"\*\*([^*]+)\*\*", r"<b>\1</b>", t)
    t = re.sub(r"\[(New|Change|Fix)\]", lambda m: f'<span class="tag {m.group(1).lower()}">{m.group(1)}</span>', t)
    return t


out, depth = [], 0
for line in body.strip().splitlines():
    m = re.match(r"^(\s*)- (.*)", line)
    if m:
        lvl = 1 + len(m.group(1)) // 2
        while depth < lvl:
            out.append("<ul>"); depth += 1
        while depth > lvl:
            out.append("</ul>"); depth -= 1
        out.append(f"<li>{inline(m.group(2))}</li>")
        continue
    while depth:
        out.append("</ul>"); depth -= 1
    head = re.sub(r" \{.*\}$", "", line)
    if line.startswith("## "):
        out.append(f"<h2>{inline(head[3:])}</h2>")
    elif line.startswith("### "):
        out.append(f"<h3>{inline(head[4:])}</h3>")
    elif line.startswith("> "):
        out.append(f'<p class="box">{inline(line[2:])}</p>')
    elif line.strip():
        out.append(f"<p>{inline(line)}</p>")
while depth:
    out.append("</ul>"); depth -= 1

v = html.escape(meta.get("version", ""))
ask_html = f'<div class="ask"><b>Draft for your review.</b> {html.escape(ask)}</div>' if ask else ""
page = f'''<title>SlumsMS {v} Notes Draft</title>
<meta name="claude:viewport-fit-sides" content="cover">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Baloo+2:wght@600;700&family=Source+Sans+3:wght@400;600;700&display=swap">
<style>
:root{{--bg:#f3f6f1;--panel:#fff;--ink:#1f2a22;--muted:#5b6a5f;--line:#d7e1d8;--new:#2f7a3a;--chg:#8a5d0f;--fix:#2d5f8f;--code:#eef3ed;--warn:#fff4dc}}
@media (prefers-color-scheme:dark){{:root:not([data-theme="light"]){{--bg:#141a16;--panel:#1c241f;--ink:#e3ece5;--muted:#9aaa9e;--line:#2f3a33;--new:#7cc787;--chg:#e0b35a;--fix:#8ab8e6;--code:#232d27;--warn:#3a2f17;color-scheme:dark}}}}
:root[data-theme="dark"]{{--bg:#141a16;--panel:#1c241f;--ink:#e3ece5;--muted:#9aaa9e;--line:#2f3a33;--new:#7cc787;--chg:#e0b35a;--fix:#8ab8e6;--code:#232d27;--warn:#3a2f17;color-scheme:dark}}
body{{background:var(--bg);color:var(--ink);font:16px/1.6 "Source Sans 3",system-ui,sans-serif;padding-left:max(16px,env(safe-area-inset-left,0px));padding-right:max(16px,env(safe-area-inset-right,0px));padding-block:24px 60px}}
main{{max-width:780px;margin:0 auto;background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:20px 24px}}
h1{{font:700 32px/1.1 "Baloo 2",system-ui,sans-serif;margin:0 0 4px}} h2{{font:700 22px "Baloo 2",system-ui,sans-serif;margin:26px 0 6px}} h3{{font:700 18px "Baloo 2",system-ui,sans-serif;margin:18px 0 4px}}
.muted{{color:var(--muted)}} code{{background:var(--code);padding:1px 5px;border-radius:5px;font-size:.92em}}
ul{{padding-left:20px;margin:6px 0}} li{{margin:4px 0}} .box{{background:var(--code);border-left:4px solid var(--new);padding:8px 12px;border-radius:6px}}
.tag{{font-size:11px;font-weight:700;letter-spacing:.06em;text-transform:uppercase;border:1px solid currentColor;border-radius:4px;padding:0 4px;margin-right:4px}}
.tag.new{{color:var(--new)}} .tag.change{{color:var(--chg)}} .tag.fix{{color:var(--fix)}}
.ask{{background:var(--warn);border-radius:10px;padding:10px 14px;margin-bottom:18px}}
</style>
<main>
{ask_html}
<p class="muted">{html.escape(meta.get("date", ""))} &middot; version {v}</p>
<h1>{html.escape(meta.get("title", ""))}</h1>
<p><i>{html.escape(meta.get("summary", ""))}</i></p>
{chr(10).join(out)}
</main>'''
open(out_path, "w", encoding="utf-8").write(page)
print(out_path)
