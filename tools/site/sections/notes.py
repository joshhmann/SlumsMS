"""News: the patch notes, one page per release plus the list. Sources: docs/site/releases/<version>.md."""
import os
import re

from common import SRC, esc, inline, layout, read, slug, write
from sections import buddy


TAGS = {"new": "New", "fix": "Fix", "change": "Change", "same": "Same"}


def parse_release(path):
    text = read(path)
    m = re.match(r"---\n(.*?)\n---\n", text, re.S)
    if not m:
        raise SystemExit(f"{path}: needs front matter between --- lines")
    meta = {}
    for line in m.group(1).splitlines():
        if ":" in line:
            k, v = line.split(":", 1)
            meta[k.strip()] = v.strip()
    for key in ("version", "title", "date", "summary"):
        if not meta.get(key):
            raise SystemExit(f"{path}: front matter is missing '{key}'")
    if os.path.basename(path) != meta["version"] + ".md":
        raise SystemExit(f"{path}: file name must be the version ({meta['version']}.md)")

    blocks = []  # ("p", text) ("quote", text) ("h2", title, id, cards) ("h3", text) ("li", tag, text)
    for raw in text[m.end():].splitlines():
        line = raw.rstrip()
        if not line.strip():
            blocks.append(("blank",))
            continue
        if raw.startswith("  ") and blocks and blocks[-1][0] in ("li", "p", "quote"):
            b = blocks[-1]
            blocks[-1] = b[:-1] + (b[-1] + " " + line.strip(),)
            continue
        if line.startswith("## "):
            title, attrs = line[3:], ""
            am = re.search(r"\s*\{([^}]*)\}\s*$", title)
            if am:
                title, attrs = title[:am.start()], am.group(1)
            ident = re.search(r"#([\w-]+)", attrs)
            blocks.append(("h2", title.strip(), ident.group(1) if ident else slug(title), ".cards" in attrs))
        elif line.startswith("### "):
            blocks.append(("h3", line[4:].strip()))
        elif line.startswith("- "):
            item = line[2:].strip()
            tm = re.match(r"\[(\w+)\]\s+", item)
            tag = None
            if tm and tm.group(1).lower() in TAGS:
                tag, item = tm.group(1).lower(), item[tm.end():]
            elif tm:
                raise SystemExit(f"{path}: unknown tag [{tm.group(1)}] (use {', '.join(TAGS.values())})")
            blocks.append(("li", tag, item))
        elif line.startswith("> "):
            if blocks and blocks[-1][0] == "quote":
                blocks[-1] = ("quote", blocks[-1][1] + " " + line[2:].strip())
            else:
                blocks.append(("quote", line[2:].strip()))
        elif blocks and blocks[-1][0] == "p":
            blocks[-1] = ("p", blocks[-1][1] + " " + line.strip())
        else:
            blocks.append(("p", line.strip()))
    meta["blocks"] = [b for b in blocks if b[0] != "blank"]
    meta["key"] = tuple(int(x) if x.isdigit() else x for x in re.split(r"[.\-]", meta["version"]))
    meta["counts"] = {t: sum(1 for b in meta["blocks"] if b[0] == "li" and b[1] == t) for t in TAGS}
    return meta


def render_release(rel):
    intro, sections, cur = [], [], None
    for b in rel["blocks"]:
        if b[0] == "h2":
            cur = {"title": b[1], "id": b[2], "cards": b[3], "blocks": []}
            sections.append(cur)
        else:
            (cur["blocks"] if cur else intro).append(b)
    ids = [s["id"] for s in sections]
    if len(ids) != len(set(ids)):
        raise SystemExit(f"release {rel['version']}: two sections share an id")

    def blocks_html(blocks, cards=False):
        out, items = [], []

        def flush():
            if not items:
                return
            if cards:
                out.append('<div class="hl-grid">')
                for _, _, text in items:
                    hm = re.match(r"\*\*(.+?)\*\*\s*(.*)", text)
                    head, rest = (hm.group(1).rstrip("."), hm.group(2)) if hm else ("", text)
                    out.append(f'<article class="hl"><h3>{inline(head)}</h3><p>{inline(rest)}</p></article>')
                out.append("</div>")
            else:
                out.append('<ul class="items">')
                for _, tag, text in items:
                    pill = f'<span class="tag {tag}">{TAGS[tag]}</span>' if tag else ""
                    out.append(f"<li>{pill}<div>{inline(text)}</div></li>")
                out.append("</ul>")
            items.clear()

        for b in blocks:
            if b[0] == "li":
                items.append(b)
                continue
            flush()
            if b[0] == "p":
                out.append(f"<p>{inline(b[1])}</p>")
            elif b[0] == "quote":
                out.append(f'<div class="callout"><p>{inline(b[1])}</p></div>')
            elif b[0] == "h3":
                out.append(f"<h3>{inline(b[1])}</h3>")
        flush()
        return "\n".join(out)

    toc = "".join(f'<li><a href="#{s["id"]}">{inline(s["title"])}</a></li>' for s in sections)
    words = {"new": ("new thing", "new things"), "change": ("change", "changes"), "fix": ("fix", "fixes")}
    counts = ", ".join(f"{rel['counts'][k]} {words[k][rel['counts'][k] != 1]}" for k in words if rel["counts"][k])
    body = [f'<div class="notes-head"><h1 class="bigver"><span class="vh">SlumsMS </span>{esc(rel["version"])}</h1>'
            f'<div><p class="eyebrow">Patch notes &middot; {esc(rel["date"])}</p><p class="sub">{esc(rel["title"])}</p>'
            f'{f"<p class=meta>{esc(counts)}</p>" if counts else ""}</div></div>',
            f'<div class="notes"><ul class="toc" aria-label="Sections">{toc}</ul>',
            blocks_html(intro)]
    for s in sections:
        body.append(f'<section id="{esc(s["id"])}"><h2>{inline(s["title"])}</h2>'
                    f'{blocks_html(s["blocks"], s["cards"])}</section>')
    body.append("</div>")
    return "\n".join(body)


def counts_text(rel):
    c = rel["counts"]
    parts = [f"{c['new']} new"] if c["new"] else []
    if c["change"]:
        parts.append(f"{c['change']} change{'s' if c['change'] != 1 else ''}")
    if c["fix"]:
        parts.append(f"{c['fix']} fix{'es' if c['fix'] != 1 else ''}")
    return "".join(f"<span>{p}</span>" for p in parts)


def releases():
    rdir = os.path.join(SRC, "releases")
    rels = [parse_release(os.path.join(rdir, n)) for n in os.listdir(rdir) if n.endswith(".md")]
    if not rels:
        raise SystemExit("no releases in docs/site/releases/")
    rels.sort(key=lambda r: r["key"], reverse=True)
    return rels


def build(out):
    rels = releases()
    for i, rel in enumerate(rels):
        pager = ['<nav class="pager" aria-label="Releases">']
        pager.append(f'<a class="btn" href="../">&larr; All news</a>')
        if i + 1 < len(rels):
            older = rels[i + 1]["version"]
            pager.append(f'<a class="btn" href="../{esc(older)}/">Older: {esc(older)}</a>')
        if i > 0:
            newer = rels[i - 1]["version"]
            pager.append(f'<a class="btn" href="../{esc(newer)}/">Newer: {esc(newer)}</a>')
        pager.append("</nav>")
        body = ('<a class="backlink" href="../">&larr; News</a>\n'
                + buddy.place(render_release(rel), buddy.figure("../../", "release", rel["version"])) + "\n" + "".join(pager))
        write(out, f"patch-notes/{rel['version']}/index.html",
              layout(f"{rel['version']} patch notes", body, "../../", "notes", rel["summary"]))
    # News holds every kind of post; each card says which. Releases are the only kind so far ("Update").
    items = "".join(
        f'<li><a class="rel" href="{esc(r["version"])}/"><span class="v">{esc(r["version"])}</span>'
        f'<span class="t"><span class="kind">Update</span>{esc(r["title"])}</span><span class="d">{esc(r["date"])} &middot; '
        f'{esc(r["summary"])}</span><span class="c">{counts_text(r)}</span></a></li>' for r in rels)
    body = (buddy.figure("../", "news") + '<div class="notes-head plain"><p class="eyebrow">Updates &middot; events &middot; announcements</p><h1>News</h1>'
            '<p class="meta">What&rsquo;s new on Bera, newest first.</p></div>'
            f'<ul class="rel-list">{items}</ul>')
    write(out, "patch-notes/index.html", layout("News", body, "../", "notes",
                                                "SlumsMS updates and news, newest first."))
    return rels
