"""Parses the DeepSeek review's HOLES.md (docs/architecture/HOLES.md) into per-system rows for the pages."""
import html, re, pathlib
HOLES = (pathlib.Path(__file__).resolve().parents[2] / "docs/architecture/HOLES.md").read_text()

def md(s):
    out = html.escape(s)
    out = re.sub(r"`([^`]+)`", r"<code>\1</code>", out)
    return re.sub(r"\*\*([^*]+)\*\*", r"<b>\1</b>", out)

def table(section):
    body = HOLES.split(section, 1)[1].split("\n## ", 1)[0]
    rows = []
    for line in body.splitlines():
        if line.startswith("| ") and not line.startswith("| #") and not line.startswith("| Subsystem") and not line.startswith("|---"):
            rows.append([c.strip() for c in line.strip().strip("|").split(" | ")])
    return rows

BROKEN_SYS = {"1": "server", "2": "server", "3": "server", "4": "server", "5": "server", "6": "server", "7": "server",
              "8": "bots", "9": "client", "10": "ops", "11": "website"}
# what Server Dev checked in code on Nov 5 (12:45 UTC), and the verdict
CHECKED = {
    ("broken", "1"): "verified in code", ("broken", "2"): "verified in code", ("broken", "3"): "verified in code",
    ("broken", "6"): "verified in code",
    ("partial", "ops/safety-guards"): "gate part verified and fixed in 1.6.4 (b323, 5011fc88c)",
    ("partial", "server/companions"): "CORRECTED: live config.yaml already has OWNED_ALTS_ENABLED: true (from 1.6.3), so @botme works on live; the 1.6.4 conf now states it too (5011fc88c)",
    ("partial", "bots/owned-alts"): "CORRECTED: the master switch is on live (see server/companions)",
    ("unknown", "What TEST actually passes to the JVM"): "answerable: Server Dev reads start-test.sh on mapletest; TEST mirrors live's flags since Nov 5 10:02 UTC",
}

def per_system():
    out = {}
    for r in table("## 1. Broken"):
        num, where, what, ev = r[0], r[1], r[2], r[3]
        out.setdefault(BROKEN_SYS.get(num, "server"), []).append(("broken", md(where), md(what), CHECKED.get(("broken", num), "")))
    for r in table("## 2. Partial"):
        sub, what = r[0], r[1]
        sysname = sub.split("/")[0].strip("`")
        out.setdefault(sysname, []).append(("partial", md(sub.split("/", 1)[1]), md(what), CHECKED.get(("partial", sub), "")))
    return out

def section(sysname, rows):
    if not rows:
        return ""
    order = {"broken": 0, "partial": 1}
    rows = sorted(rows, key=lambda r: order[r[0]])
    pill = {"broken": '<span class="pill s-bad">broken</span>', "partial": '<span class="pill s-part">partial</span>'}
    body = "".join(f"<tr><td>{pill[s]}</td><td>{w}</td><td>{x}</td><td>{('<b>' + c + '</b>') if c else 'lead, not yet checked'}</td></tr>" for s, w, x, c in rows)
    return (f"<p>An in-depth, code-only review (DeepSeek, Nov 5 2026, 44 doc commits now in <code>docs/architecture/</code>) "
            f"wrote one file per subsystem under <code>docs/architecture/{sysname}/</code> with file:line citations. Nothing was run, so "
            f"\"working\" there means the code reads consistently, not that it was observed. These are its non-working rows; "
            f"each is a lead until Server Dev checks it (card n16, 1.6.5).</p>"
            f'<div class="tbl"><table><tr><th>State</th><th>Where</th><th>What</th><th>Checked</th></tr>{body}</table></div>')

if __name__ == "__main__":
    for k, v in per_system().items():
        print(k, len(v), sum(1 for r in v if r[0] == "broken"))
