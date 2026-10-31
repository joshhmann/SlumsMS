"""The verdict of one client-script run folder (ticket 166, b222 rules): one record per t-id the script covers.

A run folder (docs/qa/client-scripts/run-folder.md) says what happened; this module says what it proves about the
release checks the script covers, so the server can put the answer under the t-id in auto-QA's latest.json without a
person re-reading the folder. It reads only the folder: manifest.json, the script.yaml that was played, and the paths
the manifest cites.

The b222 rules (owner 2026-10-30, ticket 164) decide the status:

  PASS  - the run result is PASS, no machine check FAILed, and either a machine check PASSed or a recorded review
          (manifest.review.verdict PASS with every `expect.reviewer` item seen) vouches for what the script cannot
          measure itself. Nothing goes green on its own.
  FAIL  - the run result is FAIL (a wait that ran out is a FAIL), a machine check FAILED, or the recorded review
          says FAIL.
  SKIP  - anything else: the run is still going, the runner or the client broke (ERROR/ABORTED), the review is
          missing or INCONCLUSIVE, or the script has neither a passing machine check nor a reviewer item. A SKIP is
          never a PASS.

`fresh` is false when the run's TEST commit is not the commit being deployed now (a PASS on another build is not
evidence for this one); the server reads it to label the row old and to keep it out of a release verdict.

Usage: tools/qa-runner/verdict <run folder> [--out DIR] [--deployed-commit SHA] [--quiet]
  prints one JSON record a line (the ids the script's covers name that start with t-), and with --out writes
  <id>.json into DIR (Server/data/autoqa/client-verdicts on TEST). Exit 1 when any covered id is not PASS.
"""
import json
import sys
from pathlib import Path

import yaml

FORMAT = 1
PASS, FAIL, SKIP = "PASS", "FAIL", "SKIP"
T_PREFIX = "t-"


def read_json(path):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        raise ValueError("%s: %s" % (path, e))


def sha256_of(path):
    import hashlib
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def script_of(folder, manifest):
    """(the script pinned in the run folder, why it cannot be trusted) - the run folder's copy must match the hash."""
    path = Path(folder) / "script.yaml"
    if not path.exists():
        return None, "the run folder has no script.yaml"
    try:
        doc = yaml.safe_load(path.read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError) as e:
        return None, "script.yaml is unreadable: %s" % e
    want = (manifest.get("script") or {}).get("sha256")
    if want and sha256_of(path) != want:
        return None, "script.yaml does not match the manifest's sha256: the script was edited after the run"
    return doc, None


def failed_step(manifest):
    i = manifest.get("failed_at")
    if not isinstance(i, int):
        return None
    steps = manifest.get("steps") or []
    s = next((x for x in steps if x.get("index") == i), None)
    if s is None:
        return None
    what = s.get("id") or s.get("do") or "step %d" % i
    detail = s.get("detail") or s.get("result")
    return "step %d %s: %s" % (i, what, detail)


def machine_checks(manifest):
    """The expect.checks results the orchestrator judged itself (server_line, sample...), SKIPs dropped."""
    return [c for c in (manifest.get("checks") or []) if c.get("result") in (PASS, FAIL)]


def review_items(doc):
    return ((doc or {}).get("expect") or {}).get("reviewer") or []


def seen_items(review):
    """The reviewer item indexes the review records as `seen`."""
    out = set()
    for it in (review or {}).get("reviewer_items") or []:
        if it.get("result") == "seen" and isinstance(it.get("index"), int):
            out.add(it["index"])
    return out


def verdict(manifest, doc):
    """(status, reason) for the run as a whole; every covered t-id gets this."""
    result = manifest.get("result")
    if result == "RUNNING":
        return SKIP, "the run is still going"
    if result in ("ERROR", "ABORTED"):
        return SKIP, "run %s: the runner or the client broke, so the run proves nothing" % result
    if result == "FAIL":
        why = failed_step(manifest)
        return FAIL, "the run failed" + (": " + why if why else "")
    if result != PASS:
        return SKIP, "run result %r is not a verdict" % result

    for c in manifest.get("checks") or []:
        if c.get("result") == FAIL:
            return FAIL, "check %s: %s" % (c.get("check"), c.get("detail") or "failed")

    items = review_items(doc)
    review = manifest.get("review")
    if items:
        if not review:
            return SKIP, "no recorded review yet: %d frame item(s) need a reviewer (b222: not a PASS without one)" \
                % len(items)
        v = review.get("verdict")
        if v == FAIL:
            return FAIL, "the review failed: " + (review.get("summary") or "see review.md")
        if v != PASS:
            return SKIP, "the review is %s, not PASS" % (v or "unrecorded")
        unseen = [i for i in range(len(items)) if i not in seen_items(review)]
        if unseen:
            return SKIP, "the review says PASS but item(s) %s are not marked seen" % \
                ", ".join(str(i) for i in unseen)
        return PASS, "%d frame item(s) seen and the server checks passed" % len(items)

    machine = [c for c in machine_checks(manifest) if c.get("result") == PASS]
    if machine:
        return PASS, "machine check(s) passed: " + ", ".join(str(c.get("check")) for c in machine)
    return SKIP, "no machine check and no reviewer item: the script proves nothing by itself"


def evidence_of(folder, manifest, extra=()):
    folder = Path(folder)
    out = [str(folder)]
    for rel in ("review.md", "server/journal.txt", "server/journal-chars.txt", "server/deployed-commit.txt",
                "server/collect.json", "server/feed.jsonl"):
        if (folder / rel).exists():
            out.append(rel)
    for cap in manifest.get("captures") or []:
        rel = cap.get("path") or ("frames/%s/" % cap.get("id"))
        if rel and (folder / rel).exists():
            out.append(rel)
    for alias in sorted((manifest.get("clients") or {})):
        for f in ("chat.log", "packets.log", "samples.tsv", "client.log"):
            rel = "client/%s/%s" % (alias, f)
            if (folder / rel).exists():
                out.append(rel)
    for e in extra:
        if e and e not in out:
            out.append(e)
    return out


def fresh_state(manifest, deployed_commit):
    """True/False when both commits are known (a run on another build is not evidence for this one), else None."""
    ran = (manifest.get("server") or {}).get("commit")
    if not deployed_commit or not ran:
        return None
    a, b = str(ran).lower(), str(deployed_commit).lower()
    if len(a) < 7 or len(b) < 7:
        return None
    return a.startswith(b[:9]) or b.startswith(a[:9])


def judge(folder, deployed_commit=None):
    """One record per covered release id (covers starting with t-) for one run folder."""
    folder = Path(folder)
    manifest = read_json(folder / "manifest.json")
    doc, why_not = script_of(folder, manifest)
    if why_not:
        status, reason = SKIP, why_not
        doc = None
    else:
        status, reason = verdict(manifest, doc)
    script = manifest.get("script") or {}
    covers = [c for c in script.get("covers") or [] if str(c).startswith(T_PREFIX)]
    if not covers:
        raise ValueError("%s: the script covers no release check (t-id)" % folder)
    fresh = fresh_state(manifest, deployed_commit)
    record = {
        "format": FORMAT,
        "status": status,
        "reason": reason,
        "fresh": fresh,
        "commit": (manifest.get("server") or {}).get("commit"),
        "deployed_commit": deployed_commit,
        "script": {"id": script.get("id"), "sha256": script.get("sha256"), "status": script.get("status")},
        "run": {"folder": str(folder), "run_id": manifest.get("run_id"), "result": manifest.get("result"),
                "started_utc": manifest.get("started_utc"), "finished_utc": manifest.get("finished_utc"),
                "trigger": manifest.get("trigger")},
        "server": {"host": (manifest.get("server") or {}).get("host"),
                   "commit": (manifest.get("server") or {}).get("commit")},
        "checks": [{"check": c.get("check"), "result": c.get("result"), "detail": c.get("detail")}
                   for c in manifest.get("checks") or []],
        "review": manifest.get("review"),
        "evidence": evidence_of(folder, manifest, [(folder / "script.yaml").exists() and "script.yaml" or None]),
    }
    out = []
    for t in covers:
        r = dict(record)
        r["id"] = t
        out.append(r)
    return out


def main(argv):
    args = list(argv)
    out_dir = None
    deployed = None
    quiet = False
    folders = []
    while args:
        a = args.pop(0)
        if a == "--out":
            out_dir = Path(args.pop(0))
        elif a == "--deployed-commit":
            deployed = args.pop(0)
        elif a == "--quiet":
            quiet = True
        elif a in ("-h", "--help"):
            print(__doc__.strip())
            return 0
        else:
            folders.append(Path(a))
    if not folders:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    worst = 0
    for folder in folders:
        try:
            records = judge(folder, deployed)
        except ValueError as e:
            print("verdict: %s" % e, file=sys.stderr)
            worst = 2
            continue
        for r in records:
            if out_dir is not None:
                out_dir.mkdir(parents=True, exist_ok=True)
                (out_dir / ("%s.json" % r["id"])).write_text(json.dumps(r, indent=1) + "\n", encoding="utf-8")
            if not quiet:
                print(json.dumps(r))
            if r["status"] != PASS:
                worst = 1
    return worst


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
