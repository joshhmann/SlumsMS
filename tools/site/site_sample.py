#!/usr/bin/env python3
"""Placeholder World data in the shape of the server's PublicSiteExport, until that export is live.

    python3 tools/site/site_sample.py [--out cache/site-sample]

Writes, like the export will (working shapes from the release lead, 2026-10-26; the contract will be the "Site export
contract" section of docs/design/track-d-plan.md):
    feed.json          {items: [{id, t, kind: event|milestone|megaphone|drama, text, names[], map?, channel?, top?}]}
    world.json         {channels: [{ch, online}], busiestMaps: [{map, name, count}], tonight: {name, startsAt, map?},
                        running: [{name, map?, channel?, endsAt?}]}
    leaderboards.json  {level, levelByClass, fame, guilds, richest, dojo, bosses}
Every file carries "sample": true and generated_at; the World pages show a banner while it does. Names are made up and
seeded, so each run gives the same files. Bots and players are mixed with no label, as on the real boards.
"""
import argparse
import datetime as dt
import json
import os
import random

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))

NAMES = ["Wolf4life", "MapleMomo", "kerningcat", "LilBoogie", "xXHenesysXx", "DrJrNecki", "ShroomKing", "PerionPapi",
         "ellinia_elf", "OrbisOtter", "slimeylimey", "NautilusNate", "Bonkers", "zakumbait", "sleepyhood", "Peachy",
         "HotStuff99", "dexxx", "Ludibunny", "AquaPrincess", "RedSnail", "ilikepie", "Gunsmoke", "BerryBerry"]
GUILDS = ["Slumlords", "HenesysHomies", "PinkBeanFC", "Leafers", "NoKS", "Elnath Nights", "Kerning Klub", "Amorians",
          "Ellinia Elves", "Buccaneers"]
JOBS = {"Warrior": ["Hero", "Paladin", "Dark Knight"], "Magician": ["Arch Mage (F/P)", "Arch Mage (I/L)", "Bishop"],
        "Bowman": ["Bowmaster", "Marksman"], "Thief": ["Night Lord", "Shadower"], "Pirate": ["Buccaneer", "Corsair"]}
MAPS = [(100000000, "Henesys"), (910000000, "Free Market Entrance"), (102000000, "Perion"), (103000000, "Kerning City"),
        (101000000, "Ellinia"), (220000000, "Ludibrium"), (200000000, "Orbis"), (211000000, "El Nath"),
        (105040300, "The Forest Down South"), (240040511, "The Dragon Nest Left Behind")]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=os.path.join(REPO, "cache", "site-sample"))
    ap.add_argument("--now", default="2026-10-26T07:00:00+00:00")
    args = ap.parse_args()
    rnd = random.Random(160)
    now = dt.datetime.fromisoformat(args.now)
    os.makedirs(args.out, exist_ok=True)

    def dump(name, obj):
        with open(os.path.join(args.out, name), "w", encoding="utf-8") as f:
            json.dump(dict(obj, sample=True, schema=1, generated_at=now.isoformat()), f, indent=1, sort_keys=True)

    # ── feed, newest first ──
    items, t = [], now
    for k in range(80):
        t -= dt.timedelta(minutes=rnd.choice([3, 7, 12, 25, 40, 66]))
        a, b = rnd.sample(NAMES, 2)
        kind = rnd.choice(["event", "milestone", "milestone", "megaphone", "megaphone", "megaphone", "drama"])
        row = {"id": f"s{k:04d}", "t": t.replace(microsecond=0).isoformat(), "kind": kind, "names": [a]}
        if kind == "event":
            name = rnd.choice(["Giant Cake", "Henesys Invasion", "Mushroom Invasion", "Boar Stampede", "Field Boss"])
            top = rnd.sample(NAMES, 3)
            dmg = sorted((rnd.randint(2, 90) * 1_000_000 for _ in top), reverse=True)
            row.update(text=f"{name} is over: {rnd.randint(40, 400)} monsters down.",
                       names=top, map=100000000, channel=rnd.randint(1, 5),
                       top=[{"name": n, "damage": d} for n, d in zip(top, dmg)])
        elif kind == "milestone":
            what = rnd.choice([f"reached level {rnd.choice([120, 200])}", "made 4th job", "was reborn (rebirth 1)",
                               f"founded the guild {rnd.choice(GUILDS)}", f"married {b} in Amoria"])
            row["text"] = f"{a} {what}!"
            if "married" in what:
                row["names"] = [a, b]
        elif kind == "megaphone":
            row["text"] = f"{a}: " + rnd.choice(["S> zak helm 10m ch2 fm room 4", "LF party for kerning pq",
                                                 "who wants to duo the cave", "B> 60% glove att, paying well",
                                                 "henesys cake tonight, be there!!"])
            row["channel"] = rnd.randint(1, 5)
        else:
            row["text"] = f"{a} and {b} " + rnd.choice(["are feuding over a KS in the Ant Tunnel.",
                                                        "broke up. It was about the pet.",
                                                        "made up after a week of not talking."])
            row["names"] = [a, b]
        items.append(row)
    dump("feed.json", {"items": items})

    # ── world now ──
    eight_pm_pt = dt.datetime.combine(now.date(), dt.time(3, 0), dt.timezone.utc)  # 8 PM PDT the evening before (UTC)
    while eight_pm_pt < now:
        eight_pm_pt += dt.timedelta(days=1)
    channels = [{"ch": c, "online": rnd.randint(380, 720)} for c in range(1, 6)]
    busiest = sorted(({"map": m, "name": n, "count": rnd.randint(20, 260)} for m, n in MAPS),
                     key=lambda r: -r["count"])[:8]
    dump("world.json", {"channels": channels, "busiestMaps": busiest,
                        "tonight": {"name": "Giant Cake", "startsAt": eight_pm_pt.isoformat(), "map": 100000000},
                        "running": [{"name": "Boar Stampede", "map": 102000000, "channel": 3,
                                     "endsAt": (now + dt.timedelta(minutes=12)).isoformat()}]})

    # ── leaderboards ──
    people = []
    for n in NAMES:
        cls = rnd.choice(list(JOBS))
        people.append({"name": n, "class": cls, "job": rnd.choice(JOBS[cls]), "level": rnd.randint(70, 200),
                       "rebirths": rnd.choice([0, 0, 0, 1, 1, 2, 3]), "guild": rnd.choice(GUILDS + [None]),
                       "fame": rnd.randint(-5, 220), "mesos": rnd.randint(1, 4000) * 1_000_003})
    by_level = sorted(people, key=lambda p: (-p["rebirths"], -p["level"]))
    lv = [{k: p[k] for k in ("name", "level", "rebirths", "job", "guild") if p[k] is not None} for p in by_level]
    by_class = {c: [r for r, p in zip(lv, by_level) if p["class"] == c][:10] for c in JOBS}
    dump("leaderboards.json", {
        "level": lv[:50], "levelByClass": by_class,
        "fame": [{"name": p["name"], "fame": p["fame"], "job": p["job"]} for p in sorted(people, key=lambda p: -p["fame"])][:20],
        "guilds": sorted(({"name": g, "level": rnd.randint(1, 5), "members": rnd.randint(4, 120), "points": rnd.randint(500, 90000),
                           "leader": rnd.choice(NAMES)} for g in GUILDS), key=lambda r: -r["points"]),
        "richest": [{"name": p["name"], "mesos": p["mesos"]} for p in sorted(people, key=lambda p: -p["mesos"])][:10],
        "dojo": sorted(({"name": n, "floor": rnd.randint(10, 38), "time_s": rnd.randint(600, 2400)} for n in rnd.sample(NAMES, 12)),
                       key=lambda r: (-r["floor"], r["time_s"])),
        "bosses": sorted(({"name": n, "clears": rnd.randint(1, 60), "boss": rnd.choice(["Zakum", "Horntail", "Papulatus", "Pianus"])}
                          for n in rnd.sample(NAMES, 14)), key=lambda r: -r["clears"]),
    })
    print(f"sample World data ({len(items)} feed items) -> {args.out}")


if __name__ == "__main__":
    main()
