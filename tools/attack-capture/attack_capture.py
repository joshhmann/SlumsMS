"""Summarize an attack capture per job and diff it against what the bots send (ticket 32).

The capture is the server's ``logs/attack-capture.tsv``, written while the test server runs with
``-Dslums.attackcapture=true`` (soloMapling.server.AttackCapture). The bots' side comes from the test-scope
exporter: ``./mvnw -q test -Dtest=BotAttackExportTest -Dbot.attack.export=target/bot-attacks.tsv``.
Both files use the bot side's column names (skillId, bodyActionId, facingMask, speed, numAttacked, ...).

Usage:

    python3 tools/attack-capture/attack_capture.py summary logs/attack-capture.tsv [--char NAME] [--job 512]
    python3 tools/attack-capture/attack_capture.py diff logs/attack-capture.tsv --bots target/bot-attacks.tsv
"""
import argparse
import collections
import csv
import sys

ATTACK_EVENTS = ("close", "ranged", "magic", "touch", "summon")
OTHER_EVENTS = ("cast", "prepare", "cancel")
# The attack header fields compared per skill, as the bots send them.
FIELDS = ("bodyActionId", "facingMask", "speed", "display", "numAttacked", "numDamage", "charge", "projectile",
          "weaponClass")


def read_tsv(path):
    csv.field_size_limit(sys.maxsize)
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f, delimiter="\t"))


def filter_rows(rows, char=None, job=None):
    return [r for r in rows
            if (char is None or r.get("char") == char) and (job is None or r.get("jobId") == str(job))]


def by_job(rows):
    jobs = collections.OrderedDict()
    for r in rows:
        jobs.setdefault((r.get("jobId", ""), r.get("job", "")), []).append(r)
    return jobs


def skill_label(row):
    name = row.get("skillName") or ""
    return f"{row.get('skillId')} {name}".strip() if row.get("skillId") not in ("", "0") else "0 (no skill)"


def rotation(attacks, limit=40):
    """The attack order with repeats folded: ``A x3 > B > A x2``, the first ``limit`` runs."""
    runs = []
    for r in attacks:
        label = skill_label(r)
        if runs and runs[-1][0] == label:
            runs[-1][1] += 1
        else:
            runs.append([label, 1])
    shown = [f"{label} x{n}" if n > 1 else label for label, n in runs[:limit]]
    return " > ".join(shown) + (f" > ... ({len(runs) - limit} more runs)" if len(runs) > limit else "")


def transitions(attacks):
    pairs = collections.Counter()
    for a, b in zip(attacks, attacks[1:]):
        if a.get("skillId") != b.get("skillId"):
            pairs[(skill_label(a), skill_label(b))] += 1
    return pairs


def observed(rows):
    """Per field, how often each value was sent."""
    seen = {}
    for field in FIELDS + ("actionName", "event", "buffs"):
        counts = collections.Counter(r.get(field, "") for r in rows)
        counts.pop("", None)
        if counts:
            seen[field] = counts
    return seen


def fmt_counts(counts):
    return ", ".join(f"{v} ({n})" if n > 1 else v for v, n in counts.most_common())


def summarize(rows, out=sys.stdout):
    for (job_id, job), job_rows in by_job(rows).items():
        attacks = [r for r in job_rows if r.get("event") in ATTACK_EVENTS]
        others = [r for r in job_rows if r.get("event") in OTHER_EVENTS]
        chars = sorted({r.get("char", "") for r in job_rows})
        out.write(f"== {job} ({job_id}) - {len(attacks)} attacks, {len(others)} casts/keydowns by {', '.join(chars)}\n")
        counts = collections.Counter(skill_label(r) for r in attacks)
        out.write("  skills used:\n")
        for label, n in counts.most_common():
            out.write(f"    {n:5d}  {label}\n")
        if attacks:
            out.write(f"  rotation: {rotation(attacks)}\n")
            top = transitions(attacks).most_common(8)
            if top:
                out.write("  most common switches: " + "; ".join(f"{a} -> {b} ({n})" for (a, b), n in top) + "\n")
        per_skill = collections.OrderedDict()
        for r in attacks:
            per_skill.setdefault(skill_label(r), []).append(r)
        for label, skill_rows in per_skill.items():
            out.write(f"  {label}  [level {fmt_counts(collections.Counter(r.get('skillLevel', '') for r in skill_rows))}]\n")
            for field, values in observed(skill_rows).items():
                out.write(f"      {field:13s} {fmt_counts(values)}\n")
        if others:
            out.write("  casts and keydowns:\n")
            cast_counts = collections.Counter((r.get("event"), skill_label(r)) for r in others)
            for (event, label), n in cast_counts.most_common():
                out.write(f"    {n:5d}  {event:8s} {label}\n")
        out.write("\n")


def diff(rows, bots, out=sys.stdout):
    """For each job captured: per skill, the header fields the player sent against what the bots send."""
    bots_by_job = collections.defaultdict(list)
    for b in bots:
        bots_by_job[b["jobId"]].append(b)
    for (job_id, job), job_rows in by_job(rows).items():
        attacks = [r for r in job_rows if r.get("event") in ATTACK_EVENTS and r.get("event") != "summon"]
        bot_rows = bots_by_job.get(job_id, [])
        out.write(f"== {job} ({job_id})\n")
        if not bot_rows:
            out.write("  bots: no attack rows for this job\n\n")
            continue
        player_skills = collections.OrderedDict()
        for r in attacks:
            player_skills.setdefault(r["skillId"], []).append(r)
        bot_skills = collections.OrderedDict()
        for b in bot_rows:
            bot_skills.setdefault(b["skillId"], []).append(b)
        for skill_id, skill_rows in player_skills.items():
            label = skill_label(skill_rows[0])
            mine = bot_skills.get(skill_id)
            if not mine:
                out.write(f"  PLAYER ONLY  {label}: used {len(skill_rows)}x, the bots never use it\n")
                continue
            problems = compare(skill_rows, mine)
            roles = ",".join(sorted({b["role"] for b in mine}))
            if problems:
                out.write(f"  DIFF   {label} (bot {roles}):\n")
                for p in problems:
                    out.write(f"           {p}\n")
            else:
                out.write(f"  MATCH  {label} (bot {roles})\n")
        for skill_id, mine in bot_skills.items():
            if skill_id not in player_skills:
                b = mine[0]
                out.write(f"  BOT ONLY     {skill_id} ({b['role']}, {b['route']}): the player never used it\n")
        player_order = [skill_label(r) for r in attacks]
        if player_order:
            out.write(f"  player rotation: {rotation(attacks, limit=12)}\n")
        out.write("\n")


def compare(player_rows, bot_rows):
    problems = []
    sent = lambda field: {r.get(field, "") for r in player_rows} - {""}
    bot_actions = set()
    for b in bot_rows:
        bot_actions.update(a for a in b["bodyActionIds"].split(",") if a)
    actions = sent("bodyActionId")
    if actions - bot_actions:
        names = {r["bodyActionId"]: r.get("actionName", "") for r in player_rows}
        problems.append("bodyActionId: player " + ", ".join(f"{a} {names.get(a, '')}".strip() for a in sorted(actions, key=int))
                        + f"; bot picks {','.join(sorted(bot_actions, key=int))} ({bot_rows[0]['actionNames']})")
    facing = sent("facingMask")
    bot_facing = set(bot_rows[0]["facingMask"].split(","))
    if facing - bot_facing:
        problems.append(f"facingMask: player {','.join(sorted(facing))}; bot {','.join(sorted(bot_facing))}")
    for field in ("speed", "display", "numDamage", "projectile", "weaponClass"):
        player = sent(field)
        bot = {b.get(field, "") for b in bot_rows} - {""}
        if field == "projectile":
            player -= {"0"}
            bot -= {"0"}
        if player and bot and not player & bot:
            problems.append(f"{field}: player {','.join(sorted(player))}; bot {','.join(sorted(bot))}")
    charge = sent("charge") - {"0"}
    bot_charge = {b.get("charge", "0") for b in bot_rows} - {"0"}
    if charge and not bot_charge:
        problems.append(f"charge: player {','.join(sorted(charge))}; bot sends none")
    most_mobs = max((int(r.get("numAttacked") or 0) for r in player_rows), default=0)
    bot_mobs = max(int(b.get("numAttacked") or 0) for b in bot_rows)
    if most_mobs > bot_mobs:
        problems.append(f"numAttacked: player hit up to {most_mobs}; bot caps at {bot_mobs}")
    return problems


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("summary", "diff"):
        p = sub.add_parser(name)
        p.add_argument("capture", help="logs/attack-capture.tsv from the test server")
        p.add_argument("--char", help="only this character")
        p.add_argument("--job", type=int, help="only this job id")
        if name == "diff":
            p.add_argument("--bots", required=True, help="the BotAttackExportTest export")
    args = parser.parse_args(argv)
    rows = filter_rows(read_tsv(args.capture), args.char, args.job)
    if args.command == "summary":
        summarize(rows)
    else:
        diff(rows, read_tsv(args.bots))


if __name__ == "__main__":
    main()
