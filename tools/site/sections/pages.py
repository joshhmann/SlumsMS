"""Single pages: home, 404 and the report form."""
import os
import re

from common import SITE, SRC, esc, layout, meta_comment, read, write


def notice_board(rels, count=5):
    """The newest releases as an old-style notice board: a tag (UPDATE for x.y.0, PATCH otherwise), title, date."""
    rows = []
    for r in rels[:count]:
        big = r["version"].endswith(".0")
        rows.append(f'<li><span class="ntag{" update" if big else ""}">{"Update" if big else "Patch"}</span>'
                    f'<a href="patch-notes/{esc(r["version"])}/">{esc(r["version"])}: {esc(r["title"])}</a>'
                    f'<time>{esc(r["date"])}</time></li>')
    return '<ul class="board">' + "".join(rows) + "</ul>"


def build_home(out, rels):
    meta, body = meta_comment(read(os.path.join(SRC, "home.html")))
    latest = rels[0]
    for k, v in {"latest_version": latest["version"], "latest_title": latest["title"],
                 "latest_summary": latest["summary"], "latest_date": latest["date"],
                 "latest_url": f"patch-notes/{latest['version']}/"}.items():
        body = body.replace("{{" + k + "}}", esc(v))
    body = body.replace("{{notice_board}}", notice_board(rels))
    left = re.findall(r"\{\{\w+\}\}", body)
    if left:
        raise SystemExit(f"home.html: unknown placeholders {left}")
    write(out, "index.html", layout(SITE, body, "", "home", meta.get("description", "")))
    # 404 page: the server sends it for a missing URL at any depth, so <base href="/"> anchors its relative links.
    nf = ('<div class="notfound"><p class="eyebrow">404</p><h1>Nothing here</h1>'
          '<p class="lede">This page wandered off into the Sleepywood dungeon.</p>'
          '<p><a class="btn primary" href="index.html">Back to the home page</a></p></div>')
    write(out, "404.html", layout("Not found", nf, "", "", base="/"))


def build_report(out):
    body = read(os.path.join(SRC, "report.html"))
    write(out, "report/index.html", layout("Report a bug or request a feature", body, "../", "report",
                                           "Tell the SlumsMS developers about a bug or an idea."))
