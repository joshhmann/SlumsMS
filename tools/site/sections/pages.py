"""Single pages: home, 404 and the report form."""
import os
import re

import common

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
    # the latest patch has its own card, so the second button is life on Bera (World), in builds that have it
    body = body.replace("{{life_button}}", '<a class="btn" href="world/">Life in the Slums</a>' if "world" in common.BUILT else "")
    left = re.findall(r"\{\{\w+\}\}", body)
    if left:
        raise SystemExit(f"home.html: unknown placeholders {left}")
    write(out, "index.html", layout(SITE, body, "", "home", meta.get("description", "")))
    # 404 page: the server sends it for a missing URL at any depth, so <base href="/"> anchors its relative links.
    nf = ('<div class="notfound"><img src="assets/brand/sleepy-zz-480.webp" alt="Slums-chan fast asleep on her arms, Zz" width="480" height="234">'
          '<p class="eyebrow">404</p><h1>Nothing here</h1>'
          '<p class="lede">Slums-chan dozed off looking for this page.</p>'
          '<p><a class="btn primary" href="index.html">Back to the home page</a></p></div>')
    write(out, "404.html", layout("Not found", nf, "", "", base="/"))


def build_login(out):
    body = read(os.path.join(SRC, "login.html"))
    write(out, "login/index.html", layout("Sign in", body, "../", "login",
                                          "Sign in to Slums Street and the Slums Market with your game account."))


def build_join(out):
    body = read(os.path.join(SRC, "join.html"))
    write(out, "join/index.html", layout("How to join", body, "../", "login",
                                         "How to join SlumsMS: an invite code, an account, the launcher."))


def build_forgot(out):
    body = read(os.path.join(SRC, "forgot.html"))
    write(out, "forgot/index.html", layout("Forgot your password?", body, "../", "login",
                                           "Ask an admin to reset your SlumsMS password."))


def build_report(out):
    body = read(os.path.join(SRC, "report.html"))
    write(out, "report/index.html", layout("Report a bug or request a feature", body, "../", "report",
                                           "Tell the SlumsMS developers about a bug or an idea."))
