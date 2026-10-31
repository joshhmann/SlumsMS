# The run folder: what one client-script run leaves behind (ticket 166/170)

Every run of a script writes one folder on 187. The reviewing agent reads only this folder; triage closes a card from
it (commit + log + time + frames). Nothing in it is a secret: no account names or passwords, no IPs, no feed secret.

```
/root/work/qa-runs/<YYYY-MM-DD>/<script-id>-<YYYYMMDDTHHMMSSZ>/      date and time = the run's start, UTC
  manifest.json            the run: script, builds, times, every step and wait, verdict, findings (below)
  script.yaml              the script exactly as played (its sha256 is in the manifest)
  frames/<capture-id>/     a burst: NNNN.png (converted from the worker's BMPs) + frames.tsv (n, utc, ms since start)
  clips/<capture-id>.mp4   a clip capture, assembled on 187 from its burst with ffmpeg
  client/<alias>/          per client: chat.log, packets.log, samples.tsv, client.log, pipe.log (signals as received)
  server/                  tools/ops/qa-run-collect output (TEST only, read-only):
    journal.txt            journalctl -u slums-persistence-test for the window +- 60 s
    journal-chars.txt      the journal lines naming the run's character ids
    qa-logs/*.log          Server/logs/qa/<id>-<UTC>.log runs that overlap the window
    autoqa-latest.json     latest.json's summary, the auto-QA runs overlapping the window, the checks the script covers
    deployed-commit.txt    Server/DEPLOYED_COMMIT
    collect.json           what was pulled and any gap
    feed.jsonl             every QA event feed line the orchestrator received (one JSON object a line, as served)
  review.md                optional: the reviewer's longer notes (the verdict itself lives in manifest.json)
```

How it gets there: the orchestrator on 187 runs the script, the Silvia worker writes the client side under its QA
folder (`Z:\MSv83\SlumsMS-dev\<qa folder>\qa-captures\<label>\`), and **187 pulls** that folder over ssh (Silvia never
pushes). Then `tools/ops/qa-run-collect --run <folder> --from <started_utc> --to <finished_utc> --chars <ids>
--checks <t-ids>` fills `server/`. Clips are made on 187 (`ffmpeg -framerate <fps> -i frames/<id>/%04d.png -pix_fmt
yuv420p clips/<id>.mp4`). Bursts are 800x600; keep a run folder under ~50 MB (bursts of 3 s at 10 fps are ~2 MB as PNG).

## manifest.json

Schema: `docs/qa/client-scripts/manifest.schema.json`. Times are UTC ISO 8601 with `Z`; durations in ms.

```json
{
  "format": 1,
  "run_id": "b140-bot-heal-column-20261031T031205Z",
  "script": {"id": "b140-bot-heal-column", "sha256": "3f1c...", "status": "draft", "covers": ["b140", "q48", "t-su-1"]},
  "server": {"host": "TEST", "commit": "d4f103b0e", "commit_source": "Server/DEPLOYED_COMMIT"},
  "clients": {
    "me": {"build": 10701, "release": "1.6.2-qa", "caps": "0x80000001", "dll_sha256": "9a0e...", "char_id": 31,
           "char_name": "AqClient", "channel": 3}
  },
  "runner": {"orchestrator": "tools/qa-runner 1", "worker": "client-mcp run_script 1", "host": "Silvia"},
  "trigger": "nightly | after-deploy d4f103b0e | manual by <agent>",
  "started_utc": "2026-10-31T03:12:05.120Z",
  "finished_utc": "2026-10-31T03:14:40.900Z",
  "result": "PASS | FAIL | ERROR | ABORTED",
  "failed_at": null,
  "steps": [
    {"index": 0, "id": null, "do": "login", "client": "me", "sent_utc": "...", "done_utc": "...", "ms": 2140,
     "result": "ok"},
    {"index": 3, "id": "channels", "do": "wait", "client": "me", "sent_utc": "...", "done_utc": "...", "ms": 5120,
     "result": "ok", "retries": 1,
     "signal": {"type": "client_signal", "name": "channels.ready", "matched_utc": "...", "detail": "SERVERSTATUS 0x03"}},
    {"index": 19, "id": "heal", "do": "wait", "client": "me", "sent_utc": "...", "done_utc": "...", "ms": 3810,
     "result": "ok", "armed_at": "hurt",
     "signal": {"type": "server_line", "topic": "COMBAT", "feed_seq": 1842, "matched_utc": "2026-10-31T03:13:58.410Z",
                "line": "PARTY HEAL Bishbot -> AqClient skill 2301002 lv 30 hp 1912->4210 ...",
                "bound": {}}}
  ],
  "captures": [
    {"id": "column-early", "kind": "burst", "client": "me", "path": "frames/column-early/", "frames": 50,
     "fps": 10, "first_utc": "...", "last_utc": "...", "trigger": {"step": "hurt"}, "result": "ok"}
  ],
  "checks": [
    {"index": 0, "check": "server_line", "result": "PASS", "detail": "1 line (min 1)", "evidence": "server/feed.jsonl#seq=1842"},
    {"index": 2, "check": "sample", "result": "PASS", "detail": "hp 1912 -> 4210", "evidence": "client/me/samples.tsv"}
  ],
  "review": {
    "by": "agent <session or name>", "at_utc": "...", "verdict": "PASS | FAIL | INCONCLUSIVE",
    "reviewer_items": [
      {"index": 0, "result": "seen | not seen | unclear", "evidence": ["frames/column-early/0012.png"],
       "note": "column visible frames 0011-0019"}
    ],
    "summary": "one paragraph in plain words"
  },
  "findings": [
    {"id": "F1", "title": "Heal column draws one frame late on the second cast", "severity": "S4",
     "state": "unconfirmed", "what_seen": "...", "expected": "...",
     "evidence": ["frames/column-on-line/0003.png", "server/feed.jsonl#seq=1850"],
     "where": {"map_id": 104040000, "channel": 3, "utc": "2026-10-31T03:14:02Z"},
     "filed_as": null}
  ]
}
```

### Fields that matter most

- `script.sha256`: of `script.yaml` as played; a script edited after a run is a different script.
- `server.commit`: from TEST's `Server/DEPLOYED_COMMIT` read at the start of the run (also in `server/deployed-commit.txt`).
- `clients.<alias>`: the client's own build stamp (build, release `x.y.z-qa`, caps with bit 31 set) and the DLL hash.
- `steps[]`: one entry per step played, in order, with the UTC time it was sent and finished. A wait records how it
  ended: the matched signal (`client_signal`: name, detail; `server_line`: feed seq, time and **the whole matched line**)
  and the variables it bound. A step that failed has `result: "timeout"` / `"refused"` / `"error"` and `detail`;
  `failed_at` names it and the run's `result` is FAIL (a wait timed out: the b222 rule) or ERROR (the runner broke).
  Steps after a failure are listed with `result: "not run"`.
- `checks[]`: each `expect.checks` entry's machine verdict with the evidence path.
- `review`: written by the reviewing agent after it looked at every capture the `expect.reviewer` items name.
  `verdict` is the run's QA verdict (a machine PASS with a reviewer item "not seen" is FAIL).
- `findings[]`: anything odd that is not a FAIL. **Triage files each one** as an S4 bug in the release book with
  `state: unconfirmed`, the title, what was seen vs expected, and the evidence paths (frames attached), then writes the
  bug id into `filed_as`. A confirmed finding is raised by triage, not by the reviewer.

## Verdicts and where they go

| result | meaning | who acts |
|---|---|---|
| PASS | every step ran, every wait matched, every check passed, every reviewer item seen | the release book links the folder next to the server checks |
| FAIL | a wait timed out, a check failed, or the reviewer did not see an item | triage, from this folder (log + time + frames) |
| INCONCLUSIVE | frames unclear (window in the way, burst too late) | the script's author fixes the script; never counted as PASS |
| ERROR / ABORTED | the runner or the client broke, or Silvia was busy and the run gave up | the Client lane / the next scheduled retry (every 30 min) |

Run folders are kept 30 days; the ones a release book links are kept until that release is retired.
