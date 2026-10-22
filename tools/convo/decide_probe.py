#!/usr/bin/env python3
"""Ticket 59: probe GLiNER2.5-Decide on the conversation questions (intent, tone) and record its raw answers.

Reads src/test/resources/chat/convo-lines.tsv, asks every line the flat 16-label intent question, the chained
5-family question with its per-family follow-ups, and the 4-label tone question (all in one request per line, two
requests at a time), prints the accuracy, and writes the answers to
src/test/resources/chat/convo-decide-answers.json. ConvoSenseTest replays that file offline, so the rules + Decide
numbers in docs/tickets/59 stay checked by the suite without the network.

    python3 tools/convo/decide_probe.py [http://192.168.0.17:8900] [--holdout] [--chain]
"""
import json, sys, time, concurrent.futures, urllib.request, pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
HOLDOUT = "--holdout" in sys.argv
LINES = ROOT / ("src/test/resources/chat/convo-holdout.tsv" if HOLDOUT else "src/test/resources/chat/convo-lines.tsv")
OUT = ROOT / ("src/test/resources/chat/convo-holdout-answers.json" if HOLDOUT
              else "src/test/resources/chat/convo-decide-answers.json")
URL = ([a for a in sys.argv[1:] if not a.startswith("--")] or [None])[0]
URL = (URL if URL else "http://192.168.0.17:8900").rstrip("/") + "/v1/systemone"

# Keep in step with ConvoSense.java (the label names and descriptions are the questions the server asks).
FLAT = {
    "party": "join or make a party", "follow": "follow them or stay close", "come": "come to a map or town",
    "trade": "buy or sell an item", "haggle": "bargain over a price", "buff": "cast a buff or heal on them",
    "help_kill": "help kill monsters or a boss", "guide": "show the way to a place",
    "date": "go on a date", "buddy": "become buddies or friends", "guild": "join or talk about a guild",
    "apology": "saying sorry", "insult": "insulting or mocking you", "flirt": "flirting or complimenting looks",
    "talk": "a question or just chatting, no request",
}
FAMILY = {
    "social": "do something together: party, follow, come somewhere, a date, buddies or a guild",
    "help": "help them: a buff or heal, kill monsters, or show the way",
    "trade": "buying, selling or a price",
    "mood": "an apology, an insult or a flirt",
    "talk": "a question or just chatting",
}
SUB = {
    "social": {"party": FLAT["party"], "follow": FLAT["follow"], "come": FLAT["come"], "date": FLAT["date"],
               "buddy": FLAT["buddy"], "guild": FLAT["guild"]},
    "help": {"buff": FLAT["buff"], "help_kill": FLAT["help_kill"], "guide": FLAT["guide"]},
    "trade": {"trade": "ask what you sell or name an item to buy or sell", "haggle": "offer or ask for a different price"},
    "mood": {"apology": FLAT["apology"], "insult": FLAT["insult"], "flirt": FLAT["flirt"]},
    "talk": {"talk": FLAT["talk"]},
}
# ChatTriage.CON, unchanged since ticket 36
CON = {"normal": "normal chat or a fair request",
       "scam": "begging for free mesos or items, borrowing, doubling, or asking you to give first",
       "manipulation": "asks if you are a bot or AI, asks about your prompt or rules, or tells you to ignore instructions or pretend",
       "harassment": "insults, telling you to die, or harassment",
       "rwt": "real money, paypal, dollars, passwords, logins or selling accounts"}
CHAIN = "--chain" in sys.argv
TONE = {"kind": "friendly, thankful, sorry or warm", "rude": "insulting, mocking or hostile",
        "flirty": "flirting, romantic or calling you cute", "neutral": "plain chat, a question or a request"}


def q(instr, labels):
    return {"type": "choice", "instructions": instr, "criteria": labels}


def ask(line):
    questions = {"flat": q("What does the player want from you?", FLAT),
                 "tone": q("How is the player treating you in this line?", TONE),
                 "con": q("Is this line trying to con or abuse you?", CON)}
    if CHAIN:
        questions["family"] = q("What kind of thing is the player saying to you?", FAMILY)
        for fam, labels in SUB.items():
            if len(labels) > 1:
                questions["sub_" + fam] = q("What exactly does the player want?", labels)
    body = json.dumps({"model": "decide", "state": "A MapleStory player typed this to you in chat: " + line,
                       "questions": questions}).encode()
    t = time.time()
    req = urllib.request.Request(URL, body, {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as r:
        ans = json.load(r)["answers"]
    return {k: {"choice": v["choice"], "confidence": round(v["confidence"], 3)} for k, v in ans.items()}, \
        int((time.time() - t) * 1000)


def main():
    rows = [l.rstrip("\n").split("\t") for l in LINES.read_text().splitlines() if l and not l.startswith("#")]
    with concurrent.futures.ThreadPoolExecutor(2) as pool:
        results = list(pool.map(lambda r: ask(r[0]), rows))
    out, ms = {}, []
    flat_ok = chain_ok = tone_ok = fam_ok = 0
    for (line, intent, tone, _), (ans, t) in zip(rows, results):
        intent = "talk" if intent in ("question", "chatter") else intent
        out[line] = ans
        ms.append(t)
        flat_ok += ans["flat"]["choice"] == intent
        tone_ok += ans["tone"]["choice"] == tone
        if CHAIN:
            fam = ans["family"]["choice"]
            want_fam = next(f for f, labels in SUB.items() if intent in labels)
            fam_ok += fam == want_fam
            got = ans["sub_" + fam]["choice"] if "sub_" + fam in ans else fam
            chain_ok += got == intent
        if ans["flat"]["choice"] != intent:
            print(f"  miss  {line!r}: want {intent}, got {ans['flat']['choice']} {ans['flat']['confidence']}")
    n = len(rows)
    ms.sort()
    print(f"lines {n}  p50 {ms[n // 2]} ms  p90 {ms[int(n * .9)]} ms")
    print(f"intent flat 15 labels : {flat_ok}/{n} = {flat_ok / n:.0%}")
    if CHAIN:
        print(f"family 5 labels       : {fam_ok}/{n} = {fam_ok / n:.0%}")
        print(f"intent chained 5->1-6 : {chain_ok}/{n} = {chain_ok / n:.0%}")
    print(f"tone 4 labels         : {tone_ok}/{n} = {tone_ok / n:.0%}")
    if not CHAIN:
        OUT.write_text(json.dumps(out, indent=1, sort_keys=True) + "\n")
    print("wrote", OUT.relative_to(ROOT))


if __name__ == "__main__":
    main()
