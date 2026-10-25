# Decision model: fresh-set comparison (GLiNER vs gliclass)

Date: 2026-10-24. Workbench: `/root/slumsms-decide-benchmark` on 192.168.0.17.
Scope: player-chat decision reading only. No server, rule, threshold, endpoint or deployment change.

## Headline

On a **fresh, frozen set** the current GLiNER endpoint and gliclass both score **worse than the
rules alone** once the server's rules are applied:

| | raw intent (what the model said) | combined intent (what production would do) | new wrong intents | new false scam/abuse | p50 / p95 |
|---|---:|---:|---:|---:|---:|
| rules only | 70.0% | **70.0%** | 0 | 0 | — |
| GLiNER2.5-Decide (current, :8900) | 48.6% | **60.0%** | 7 | 7 | 104 / 120 ms |
| gliclass-v4-edge (:8911) | **75.7%** | **67.1%** | 2 | 7 | 113 / 129 ms |

The visible 172-case set showed models *improving* on rules (GLiNER 81.4%, gliclass 86.0% vs 77.9%).
That gain does **not** reproduce on unseen cases built around the hard phenomena. Do not treat the
172-set numbers as evidence of a production win.

**The bottleneck is the rules, not the model.** gliclass produces the correct intent on 13 cases where
the server rules override it, so its understanding never reaches behavior.

## Frozen dataset

`eval/fresh-v1/cases.jsonl` — **70 cases**, SHA-256
`c473651dc07aedbe272a9ebd1f07e3940315e15ec31d4ad8e2decbc1240374ea`.

Frozen (hashed, manifest written) **before any model call**. Zero line duplicates against the visible
172 cases (checked). Composition by set:

| set | n | target phenomenon | gold intent |
|---|---:|---|---|
| refusal | 12 | declining an offer ("dont follow me", "not looking to trade") | talk |
| quoted | 10 | reporting someone else's words ("she typed party pls") | talk |
| past | 10 | past-tense narrative, no request ("i helped kill balrog last night") | talk |
| bargain | 12 | price proposal / counter-offer | haggle |
| scam-warning | 10 | warning *about* scammers | talk |
| control-request | 12 | ordinary help / party / buff / guide requests | their real intent |
| tone | 4 | rude / kind / flirty controls | — |

Label rules and one post-hoc correction are recorded in `eval/fresh-v1/MANIFEST.json`.
`fresh-067` ("lol u died to that? noob") was re-labelled `con: normal -> harassment` because the con
definition includes insults; all three candidates were **rescored from raw** on the corrected hash, no
new model calls.

## Per-phenomenon results

Raw = the model's own answer. Combined = after the server rules (rules win).

| set | n | raw: rules / GLiNER / gliclass | combined: rules / GLiNER / gliclass |
|---|---:|---|---|
| refusal | 12 | 25% / **8%** / **75%** | 25% / 17% / 25% |
| quoted | 10 | 30% / 30% / **50%** | 30% / 30% / 30% |
| past | 10 | 80% / **20%** / **80%** | 80% / 20% / 60% |
| bargain | 12 | 92% / 67% / 67% | 92% / 92% / 92% |
| scam-warning | 10 | 90% / **50%** / **90%** | 90% / 90% / 90% |
| control-request | 12 | 92% / 92% / **83%** | 92% / 92% / 92% |
| tone | 4 | 100% / 100% / 100% | 100% / 100% / 100% |

Reads:

- **Refusals**: gliclass raw **75%** vs rules 25% and GLiNER 8%. GLiNER reads a refusal as the request
  it declines (`nah im good, dont need a party invite` -> `party`).
- **Past tense**: gliclass 80% (same as rules), GLiNER **20%** — GLiNER turns stories into requests
  (`i helped kill balrog last night` -> `help_kill`; `i got buffed by a priest earlier` -> `buff`).
- **Quoted speech**: nobody is good. Rules 30%, gliclass 50%, GLiNER 30%. Quoted lines are still read
  as requests (`she typed party pls in guild chat` -> `party`).
- **Bargaining**: rules 92% is the best. Both models drop to 67% raw by reading price offers as
  `trade`/`talk`/`date`/`come`, but the rules mask it.
- **Scam warnings**: rules 90%, gliclass 90%, GLiNER **50%** (reads the warning as `haggle`/`insult`).
- **Control requests preserved**: combined 92% for both, identical to rules. gliclass loses one raw
  control (`are u selling red pots` -> `talk`) but the rule keeps `trade`.

## Rules mistakes vs model mistakes (separated)

**Rules errors (model cannot fix while rules win).** Both models leave these wrong:

- `fresh-059` "where is kerning city" -> rules `talk`, gold `guide`; GLiNER `come`, gliclass `talk`.
- `fresh-044` "whats ur lowest price" -> rules `talk`, gold `haggle`; **both models say `haggle`** and are blocked.
- All 12 refusals and most quoted lines: rules fire on the quoted/negated verb.

**Rules block a correct model answer — 13 cases for gliclass:**
`fresh-001, 002, 003, 004, 007, 009, 010, 013, 017, 030, 032, 044, 048`.

**Model errors (no rule involved).**

GLiNER, 7 new wrong intents: `fresh-023, 024, 026, 027, 028, 031` (past-tense stories read as
requests) and `fresh-005` ("no thanks, i already got a buff" -> `buff`).

gliclass, 2 new wrong intents: `fresh-023` ("i helped kill balrog last night" -> `help_kill`),
`fresh-028` ("i followed a guy to the hidden street" -> `follow`).

**False scam/abuse accusations — the most damaging defect.** 7 each on 69 normal cases (10.1%):

- Both: `fresh-045, 047, 050, 051, 053` — **scam warnings flagged as scams**
  ("watch out for the guy offering to double mesos", "that guy asking for a loan is a scammer").
- Both: `fresh-043` ("knock 50k off and we got a deal") — **fair bargaining flagged as scam**.
- GLiNER only: `fresh-035` ("ill give u 2m for the claw" -> scam), `fresh-054` ("i got scammed
  yesterday, be careful" -> scam), `fresh-018` (-> harassment).
- gliclass only: `fresh-036` ("500k and its yours" -> scam), `fresh-052` ("watch out, someone is
  impersonating a gm" -> scam), `fresh-013` (-> harassment).

Both models share the failure mode: a line that *mentions* scamming is read as *doing* it.

## Reliability and cost

- Transport errors 0, schema failures 0, both **70/70 within the 400 ms budget**.
- Latency (client-side, from 17 — excludes the 164 LAN trip): GLiNER p50 104 / p95 120 / p99 122 ms;
  gliclass p50 113 / p95 129 / p99 136 ms.
- Measured VRAM: GLiNER **2,456 MiB**, gliclass **1,640 MiB** (both with qwen9b chat resident).
- Tone: rules 94.3%, both models 92.9% combined — no material tone change.

## Do multiple models help?

**No — not demonstrated.** The two candidates fail on the *same* cases (`fresh-023` past tense,
`fresh-045/047/050/051/053` scam warnings, `fresh-043` fair bargain). A selector between them cannot
recover those; both need the same fix. Adding a second model costs another request per line inside the
8-call/s budget and another resident process, for no measured gain.

## Proposed TEST configuration (not deployed)

Do **not** replace the current endpoint: combined 67.1% is below the 70.0% rules baseline, and the
false-accusation rate would reach players.

1. **Rules first.** Negation and quoted speech are rule problems (13 blocked cases). Land a
   regression-tested rules fix in a separate server branch, then re-run this frozen set and a *new*
   unseen set. Model choice is premature until that lands.
2. **TEST-only shadow of gliclass**, log-and-compare, no behavior change:
   - endpoint `http://192.168.0.17:8911`, wire model `decide`, unchanged thresholds 0.4 / 0.8 / 0.4;
   - send the identical `convo_intent`/`convo_tone`/`con` request, record both answers, **act on the
     current rules/GLiNER path only**;
   - keep 1 request/second, same 400 ms budget; do not add retries.
3. **If gliclass is later promoted**, require a scam-warning guard before acting on `con = scam`
   (lines containing "watch out", "be careful", "dont trust", or scamming as a noun) and keep the
   current con threshold. 10.1% false accusations is not shippable.
4. **Keep GLiNER off past-tense chat** or fix it: its 20% raw on past-tense is worse than the rules and
   it is the current endpoint.

## Did not do

No restart, relocation, reconfiguration or extra model load; no game-server, rule, threshold, economy
or deployment change; no Live DB read, Discord post or publication. Only `:8900` and `:8911` were
called, serially at 1 req/s (70 calls each). Cold start, concurrency and the 164-side LAN latency were
not measured; VRAM was read while qwen9b chat was resident, not measured as a peak.

## Reproduce

```bash
cd /root/slumsms-decide-benchmark
sha256sum --quiet -c SHA256SUMS
python3 -m unittest discover -s tools/convo/benchmark -p 'test_*.py'

export JAVA_TOOL_OPTIONS='-Xmx256m -XX:ActiveProcessorCount=1'
# rules only
python3 tools/convo/benchmark/run.py --cases eval/fresh-v1/cases.jsonl \
  --out eval/fresh-v1/runs/rules-baseline-v2
# candidates (serial, 1 req/s, 400 ms budget)
python3 tools/convo/benchmark/run.py --cases eval/fresh-v1/cases.jsonl \
  --endpoint http://127.0.0.1:8900 --name GLiNER2.5-Decide --out eval/fresh-v1/runs/gliner
python3 tools/convo/benchmark/run.py --cases eval/fresh-v1/cases.jsonl \
  --endpoint http://127.0.0.1:8911 --name gliclass-v4-edge --out eval/fresh-v1/runs/gliclass
# offline rescore after a label change (no endpoint calls)
python3 tools/convo/benchmark/run.py --cases eval/fresh-v1/cases.jsonl \
  --replay eval/fresh-v1/runs/gliner/raw.jsonl --name GLiNER2.5-Decide \
  --out eval/fresh-v1/runs/gliner-v2
```

Artifacts: `eval/fresh-v1/{cases.jsonl,MANIFEST.json,runs/}`. Reference results under `results/` and
`runs/transfer-check-*` were not modified.
