#!/usr/bin/env python3
"""Compile docs/guidebook/guidebook.yaml into WZ XML for the client's Custom.wz overlay (ticket 40).

    python3 docs/guidebook/compile_guide.py [--check]

Writes docs/guidebook/build/:
  Custom/Item/Etc/0416.img.xml     the book: 0416.img/04161001/book/<page>/<block>/text
  Custom/String/Etc.img.xml        the item's name and description: Etc.img/Etc/4161001/{name,desc}
  preview.html                     the pages as two-page spreads, codes rendered, for a quick read
--check only validates (page sizes, links) and writes nothing.

The XML is the same imgdir/string dump format as the server's wz/ tree, so a WZ editor that imports XML
(HaRepacker-style) can build Custom.wz from it. See docs/guidebook/CLIENT-STEP.md for what the client step does.
Stdlib + PyYAML only.
"""
import argparse
import html
import os
import re
import sys
from xml.sax.saxutils import quoteattr

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "guidebook.yaml")
OUT = os.path.join(HERE, "build")

# MapleRoot's stock 04161001 has pages 0-21, each with blocks 0 and 1 at most. Every one of those is written
# (blank when the new book is shorter), so no stock text can show through if the overlay merges per value.
STOCK_PAGES = 22
STOCK_BLOCKS = 2
MAX_CHARS = 430
MAX_LINES = 11

CODE = re.compile(r"#[bkrenc]")
LINK = re.compile(r"#L(\d+)#(.*?)#l", re.S)


def visible(text):
    """The text a player sees: codes and link markers removed."""
    text = LINK.sub(lambda m: m.group(2), text)
    return CODE.sub("", text)


def load():
    with open(SRC, encoding="utf-8") as f:
        doc = yaml.safe_load(f)
    pages = doc["pages"]
    index = {}
    for i, p in enumerate(pages):
        if p["id"] in index:
            sys.exit(f"duplicate page id {p['id']}")
        index[p["id"]] = i

    def resolve(text):
        def sub(m):
            key = m.group(1)
            if key not in index:
                sys.exit(f"unknown page link {{{{page:{key}}}}}")
            return str(index[key])
        return re.sub(r"\{\{page:([a-z0-9_]+)\}\}", sub, text or "")

    for p in pages:
        p["heading"] = resolve(p.get("heading", ""))
        p["body"] = resolve(p.get("body", ""))
    return doc, pages


def check(pages):
    problems = []
    for i, p in enumerate(pages):
        body = visible(p["body"])
        lines = (1 if p["heading"] else 0) + body.count("\n") + 1
        chars = len(visible(p["heading"])) + len(body)
        if chars > MAX_CHARS or lines > MAX_LINES:
            problems.append(f"page {i} ({p['id']}): {chars} chars, {lines} lines (limit {MAX_CHARS}/{MAX_LINES})")
        for m in LINK.finditer(p["body"]):
            if int(m.group(1)) >= len(pages):
                problems.append(f"page {i} ({p['id']}): link to missing page {m.group(1)}")
    return problems


def wz(text):
    # The dumps write a line break as the two characters backslash-n (see the stock Noblesse guide).
    return text.replace("\r\n", "\n").replace("\n", "\\n")


def write_book(doc, pages):
    n = max(len(pages), STOCK_PAGES)
    out = ['<?xml version="1.0" encoding="UTF-8" standalone="yes"?>', '<imgdir name="0416.img">',
           f'<imgdir name="{doc["item"]:08d}">', '<imgdir name="book">']
    for i in range(n):
        p = pages[i] if i < len(pages) else {"heading": "", "body": ""}
        out.append(f'<imgdir name="{i}">')
        for b, text in enumerate([p["heading"], p["body"]][:STOCK_BLOCKS]):
            out.append(f'<imgdir name="{b}">')
            out.append(f'<string name="text" value={quoteattr(wz(text))}/>')
            out.append('</imgdir>')
        out.append('</imgdir>')
    out += ['</imgdir>', '</imgdir>', '</imgdir>', '']
    path = os.path.join(OUT, "Custom", "Item", "Etc", "0416.img.xml")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(out))
    return path


def write_string(doc):
    out = ['<?xml version="1.0" encoding="UTF-8" standalone="yes"?>', '<imgdir name="Etc.img">',
           '<imgdir name="Etc">', f'<imgdir name="{doc["item"]}">',
           f'<string name="name" value={quoteattr(doc["name"])}/>',
           f'<string name="desc" value={quoteattr(doc["desc"])}/>',
           '</imgdir>', '</imgdir>', '</imgdir>', '']
    path = os.path.join(OUT, "Custom", "String", "Etc.img.xml")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(out))
    return path


def render(text):
    """Book codes to HTML, for the preview only."""
    s = html.escape(text)
    s = LINK.sub(lambda m: f'<a href="#p{m.group(1)}">{m.group(2)}</a>', s)
    out, bold, color = [], False, None
    for tok in re.split(r"(#[bkrenc])", s):
        if tok in ("#b", "#r"):
            if color:
                out.append("</span>")
            color = tok
            out.append(f'<span class="{ "blue" if tok == "#b" else "red" }">')
        elif tok == "#k" or tok == "#c":
            if color:
                out.append("</span>")
                color = None
        elif tok == "#e":
            if not bold:
                out.append("<b>")
                bold = True
        elif tok == "#n":
            if bold:
                out.append("</b>")
                bold = False
        else:
            out.append(tok)
    if color:
        out.append("</span>")
    if bold:
        out.append("</b>")
    return "".join(out).replace("\n", "<br>")


def write_preview(doc, pages):
    spreads = []
    for i in range(0, len(pages), 2):
        cells = []
        for j in (i, i + 1):
            if j < len(pages):
                p = pages[j]
                cells.append(f'<div class="page" id="p{j}"><div class="h">{render(p["heading"])}</div>'
                             f'<div>{render(p["body"])}</div><div class="no">{j}</div></div>')
            else:
                cells.append('<div class="page"></div>')
        spreads.append(f'<div class="spread">{"".join(cells)}</div>')
    css = ("body{background:#d9c9a3;font:12px/1.45 Tahoma,Verdana,sans-serif;margin:16px}"
           ".spread{display:flex;flex-wrap:wrap;gap:6px;max-width:640px;margin:0 auto 14px}"
           ".page{flex:1 1 280px;min-height:250px;background:#fffbea;border:1px solid #8a6d3b;border-radius:4px;"
           "padding:12px;position:relative}.h{margin-bottom:6px}.no{position:absolute;right:8px;bottom:4px;"
           "color:#999;font-size:10px}.blue{color:#2a4fd1}.red{color:#c22}a{text-decoration:none}"
           "h1{font-size:15px;text-align:center}")
    body = "\n".join(spreads)
    page = (f'<!doctype html><html lang="en"><head><meta charset="utf-8">'
            f'<meta name="viewport" content="width=device-width,initial-scale=1"><title>Guidebook preview</title>'
            f'<style>{css}</style></head><body><h1>{html.escape(doc["name"])} - preview (item {doc["item"]})</h1>'
            f'{body}</body></html>\n')
    path = os.path.join(OUT, "preview.html")
    with open(path, "w", encoding="utf-8") as f:
        f.write(page)
    return path


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", action="store_true", help="validate only")
    args = ap.parse_args()
    doc, pages = load()
    problems = check(pages)
    for p in problems:
        print("WARN", p)
    print(f"{len(pages)} pages ({(len(pages) + 1) // 2} spreads), {len(problems)} warnings")
    if args.check:
        return 1 if problems else 0
    for path in (write_book(doc, pages), write_string(doc), write_preview(doc, pages)):
        print("wrote", os.path.relpath(path, os.getcwd()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
