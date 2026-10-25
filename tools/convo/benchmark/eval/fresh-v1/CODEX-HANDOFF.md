# Handoff to Codex: chat-decision results + rules fix spec

Written 2026-10-24 by the benchmark agent on 192.168.0.17.
Workbench: `/root/slumsms-decide-benchmark` (selected-file snapshot; **no production Java source**,
only test-side `DecideBenchmarkBridge` and `target/Cosmic.jar`).
The rule code (`ConvoSense`, `ChatDecide`) lives in the server repo on 187. Implement the fix there.

Nothing was deployed, restarted, reconfigured or moved. No rule, threshold, endpoint or economy change.

---

## 1. What was measured

A **fresh 70-case set**, frozen and hashed before any model call, built around the phenomena that
matter in player chat. It has **zero line duplicates** with the visible 172-case development set.

- File: `eval/fresh-v1/cases.jsonl`
- SHA-256: `c473651dc07aedbe272a9ebd1f07e3940315e15ec31d4ad8e2decbc1240374ea`
- Composition: refusal 12, quoted 10, past 10, bargain 12, scam-warning 10, control-request 12, tone 4.
- Label rules and one post-hoc correction (`fresh-067 con: normal -> harassment`) are in `eval/fresh-v1/MANIFEST.json`.
  All candidates were rescored from raw on the corrected hash; no extra model calls.

Models compared with unchanged thresholds (intent 0.4 / tone 0.8 / con 0.4) and the 400 ms deadline,
serial at 1 request/second, 70 calls each. Only `:8900` (GLiNER) and `:8911` (gliclass) were called.

## 2. Results

| | raw intent (model's own answer) | combined (after rules) | new wrong intents | new false scam/abuse | p50 / p95 / p99 |
|---|---:|---:|---:|---:|---:|
| rules only | 70.0% | **70.0%** | 0 | 0 | — |
| GLiNER2.5-Decide (current, :8900) | 48.6% | **60.0%** | 7 | 7 | 104 / 120 / 122 ms |
| gliclass-v4-edge (:8911) | **75.7%** | **67.1%** | 2 | 7 | 113 / 129 / 136 ms |

Transport errors 0, schema failures 0, 70/70 within 400 ms both. Measured VRAM (with qwen9b resident):
GLiNER 2,456 MiB, gliclass 1,640 MiB. Tone: rules 94.3%, both models 92.9%.

Per set — raw: rules / GLiNER / gliclass — and combined: rules / GLiNER / gliclass

| set | n | raw | combined |
|---|---:|---|---|
| refusal | 12 | 25% / 8% / **75%** | 25% / 17% / 25% |
| quoted | 10 | 30% / 30% / **50%** | 30% / 30% / 30% |
| past | 10 | 80% / **20%** / **80%** | 80% / 20% / 60% |
| bargain | 12 | 92% / 67% / 67% | 92% / 92% / 92% |
| scam-warning | 10 | 90% / **50%** / **90%** | 90% / 90% / 90% |
| control-request | 12 | 92% / 92% / 83% | 92% / 92% / 92% |
| tone | 4 | 100% / 100% / 100% | 100% / 100% / 100% |

**Do not read this as "a model would help".** Both models make combined accuracy *worse* than the rules
alone on unseen cases. Ordinary help/party/buff/guide requests are preserved (combined 92%, same as rules).

## 3. Rules vs model: the finding that decides the work

The rules now win every conflict, so a model's correct answer is discarded. **gliclass gets the right
intent on 13 cases that the rules override**, and that never reaches behavior.

All 21 rules intent errors (30% of the set), by class:

| class | n | example |
|---|---:|---|
| negation | 8 | `dont follow me` -> `follow` |
| reported / quoted speech | 6 | `she typed party pls in guild chat` -> `party` |
| mention vs use (warning) | 1 | `be careful of the trade offer in fm` -> `trade` |
| past-tense narrative | 2 | `i helped a noob with his quest earlier` -> `insult` (`noob` fires) |
| genuine recall gap | 2 | `where is kerning city` -> `talk`; `whats ur lowest price` -> `talk` |

The 13 rules-blocked correct model answers (gliclass raw correct, combined wrong):

```
fresh-001  nah im good, dont need a party invite        rules=party     model=talk
fresh-002  not looking to trade right now sorry         rules=apology   model=talk
fresh-003  please dont follow me around the map         rules=follow    model=talk
fresh-004  i cant come to henesys, im busy              rules=come      model=talk
fresh-007  dont add me to ur buddy list pls             rules=buddy     model=talk
fresh-009  stop asking me to party                      rules=party     model=talk
fresh-010  no im not selling it                         rules=trade     model=talk
fresh-013  some guy in fm was yelling b> claw 10m       rules=haggle    model=talk
fresh-017  someone wrote add me im pro on the board     rules=buddy     model=talk
fresh-030  i helped a noob with his quest earlier       rules=insult    model=talk
fresh-032  i bargained down to 300k last time           rules=haggle    model=talk
fresh-044  whats ur lowest price                        rules=talk      model=haggle
fresh-048  be careful of the trade offer in fm          rules=trade     model=talk
```

False scam/abuse accusations (7 each on 69 normal cases = 10.1%) — the most damaging defect:
both models flag **warnings about scams** and **fair bargaining** as scams.

- both: `fresh-045, 047, 050, 051, 053` (scam warnings), `fresh-043` (`knock 50k off and we got a deal`)
- GLiNER only: `fresh-035`, `fresh-054`, `fresh-018`
- gliclass only: `fresh-036`, `fresh-052`, `fresh-013`

## 4. Fix spec for the rules (this is the real work)

**Do not add more keywords for the first two classes.** The cue already matches; what is missing is
scope. A global "negator means talk" rule breaks genuine requests such as `dont forget to buff me pls`.

### 4a. Negation scope (≈8 cases)

For each request cue that fires, decide whether the cue is **inside the scope of a negator**:

1. Split the line into clauses at `,` `;` ` but ` ` and ` ` so ` `.`
2. In the clause containing the cue, find a negator **before** the cue: `no`, `not`, `dont`, `don't`,
   `cant`, `can't`, `wont`, `won't`, `never`, `nah`, `im not`, `isnt`, `stop <verb>`, `no thanks`.
3. Guard against a negator that governs a different verb: if the token immediately after the negator is
   `forget`, `be`, `worry`, `mind`, `forgot` or `need` + a request cue later in the clause, the negator
   does **not** govern that cue.
4. If it does govern the cue, demote the hit: intent becomes `talk` (or drops the rule's contribution),
   con unchanged.

Test cases that must flip to `talk`: `fresh-001, 002, 003, 004, 007, 009, 010`.
Test cases that must **stay** requests (regression guards):

```
dont forget to buff me pls                      -> buff
no worries, come to henesys when u can          -> come
i cant believe u killed it, help me next time   -> help_kill
dont be late, party starts at 8                 -> party
```

### 4b. Reported / quoted speech (≈6 cases)

If the cue sits inside a quote or a reporting frame, treat it as mention, not request:

- reporting verb before the cue in the same clause: `said`, `says`, `typed`, `wrote`, `asked`,
  `yelling`, `told me`, `spamming`
- quotation marks around the cue, or `b>` / `s>` trade shorthand attributed to someone else
- `someone`, `some guy`, `ppl`, `people`, `a gm`, `the npc`, `the sign`, `my friend` as the subject
- warning frames: `watch out`, `be careful`, `dont trust`, `never share`, `its a scam` — these are
  **mentions**, and for `con` they must stay `normal`

Test cases: `fresh-013, 017, 030, 032, 048` plus the warning set `fresh-045..054`.
Genuine relays stay requests: `he said you should buff me` is ambiguous — flag it for the owner rather
than guessing; do not silently pick a side.

### 4c. Real recall gaps (only here, add patterns)

```
where is <place> / how do i get to <place>   -> guide
lowest price / best price / how much for     -> haggle
```
Tests: `fresh-059`, `fresh-044`.

### 4d. Wrong-verb triggers on past tense

`i helped a noob with his quest earlier` -> `insult` because `noob` fires. Past-tense narrative
(`i helped`, `i partied`, `i traded`, `i got buffed`) with no imperative is `talk`; and `noob`
addressed at the bot is an insult only in second person (`u r a noob`, `nice dmg noob`).

## 5. Order of work

1. Expand the frozen set first (70 cases, 10–12 per class; one case = 8–10 points). Keep it untouched.
2. Implement 4a and 4b in `ConvoSense` on a `151-<topic>` branch from `origin/release-1.5.0`,
   test-first, one failing-before/passing-after test per behaviour, `mvn -q -o test` green.
3. Then 4c/4d (plain patterns, low risk).
4. Re-run the frozen set. **Do not tune against it** — reserve a third unseen set for the verdict.
5. Only then decide about a model. After scope fixes the rules should reach ~85% and gliclass's 75.7%
   raw would no longer beat them. A model swap is not justified today: 67.1% combined < 70.0% rules,
   with 10.1% false accusations.

## 6. Proposed TEST configuration (not deployed)

- Keep the current endpoint and thresholds. No behavior change is justified by these numbers.
- Optional TEST-only shadow of gliclass on `:8911` (wire id `decide`, thresholds 0.4/0.8/0.4, 400 ms,
  1 req/s): log both answers, **act on the current path only**.
- If gliclass is ever promoted, require a scam-warning guard before acting on `con = scam`
  (`watch out`, `be careful`, `dont trust`, scamming as a noun), and keep GLiNER off past-tense chat.

## 7. Reproduce

```bash
cd /root/slumsms-decide-benchmark
sha256sum --quiet -c SHA256SUMS
python3 -m unittest discover -s tools/convo/benchmark -p 'test_*.py'

export JAVA_TOOL_OPTIONS='-Xmx256m -XX:ActiveProcessorCount=1'
python3 tools/convo/benchmark/run.py --cases eval/fresh-v1/cases.jsonl \
  --out eval/fresh-v1/runs/rules-baseline-v2
python3 tools/convo/benchmark/run.py --cases eval/fresh-v1/cases.jsonl \
  --endpoint http://127.0.0.1:8900 --name GLiNER2.5-Decide --out eval/fresh-v1/runs/gliner
python3 tools/convo/benchmark/run.py --cases eval/fresh-v1/cases.jsonl \
  --endpoint http://127.0.0.1:8911 --name gliclass-v4-edge --out eval/fresh-v1/runs/gliclass
# offline rescore after any label change (no endpoint calls)
python3 tools/convo/benchmark/run.py --cases eval/fresh-v1/cases.jsonl \
  --replay eval/fresh-v1/runs/gliclass/raw.jsonl --name gliclass-v4-edge \
  --out eval/fresh-v1/runs/gliclass-v2
```

Full analysis: `eval/fresh-v1/REPORT.md`. Reference results under `results/` and
`runs/transfer-check-*` were not modified (`SHA256SUMS` still passes).
