# SlumsMS player-chat decision benchmark

This tests the model that reads **player chat**, not the bot's daily-life brain or the model that writes replies.
The daily-life brain uses weighted scores and makes no model calls. See ticket 60 and ticket 59.

The benchmark asks exactly the server's current three choice questions in one `/v1/systemone` call:
15 conversation intents, 4 tones, and 5 scam/abuse classes. The Java bridge exports `ChatDecide.request()` and
replays responses through `ChatDecide.parse()` and `ConvoSense.combine()`. It also checks whether
`ConvoSense.read()` would ask the model. No copied Python regexes or approximated rule precedence.

No server starts, database access, game changes, or deployments are needed. Do not run the old `decide_probe.py`
for model comparisons: it uses older question names and overwrites the historical recorded answers.

## Build and run on 187

From this branch's own worktree, with Java 21 and the normal offline Maven cache:

```bash
cp config.example.yaml config.yaml  # only in a new worktree without a config
mvn -q -o test
mvn -q -o -DskipTests package
python3 -m unittest discover -s tools/convo/benchmark -p 'test_*.py'

# No network: rules baseline and exact exported production requests.
python3 tools/convo/benchmark/run.py --out dist/decide-baseline

# One candidate, one call at a time, at most one request start per second.
python3 tools/convo/benchmark/run.py --endpoint http://192.168.0.17:8911 \
  --name gliclass-v4-edge --out dist/decide-gliclass

# OneJev needs a different wire model id. A production replacement would need an alias/adapter.
python3 tools/convo/benchmark/run.py --endpoint http://192.168.0.17:8910 \
  --model-id OneJev1.6-0.8B --name OneJev1.6-0.8B --out dist/decide-onejev

# Offline rescore: verifies every id and exact request; never calls an endpoint.
python3 tools/convo/benchmark/run.py --replay dist/decide-onejev/raw.jsonl \
  --model-id OneJev1.6-0.8B --name OneJev1.6-0.8B --out dist/decide-onejev-replay
```

Outputs refuse to overwrite an existing directory. Use a new directory per run. Results live under ignored `dist/`;
never put raw player logs, credentials or model keys in a result package. All shipped inputs are synthetic.
`--classpath` can point at matching test classes and a matching dependency-inclusive Cosmic.jar, separated by `:`.

## Small capture package for the agent on 17

Java is only needed to export requests and score the server's actual behavior. A Python 3 standard-library
capture can run on 17 using three files exported on 187:

```bash
mkdir -p dist/decide-kit
cp tools/convo/benchmark/run.py tools/convo/benchmark/cases.jsonl dist/decide-kit/
cp dist/decide-baseline/requests.jsonl dist/decide-kit/
```

Copy that folder to your agent's workspace on 17, then:

```bash
python3 run.py --cases cases.jsonl --requests requests.jsonl --capture-only \
  --endpoint http://127.0.0.1:8911 --name gliclass-v4-edge --out runs/gliclass
```

Bring `raw.jsonl` back to 187 and run the offline replay above. A capture does not claim a server score.
Latency measured locally on 17 excludes the game-server-to-model network trip. Before choosing a replacement,
also repeat a gentle run over the LAN. The benchmark's current comparative runs originate on 187, not 164.

## Read the results

- `metadata.json`: model label, wire id, endpoint, source commit, dataset SHA-256, thresholds, timing and seed.
- `requests.jsonl`: exact exported questions, baseline rules and model eligibility.
- `raw.jsonl`: response text, HTTP status, transport time and schema problems, flushed after every call.
- `scored.jsonl`: each expected label, raw model label, rules result and accepted combined result.
- `report.json`: accuracy, per-class precision/recall/F1 and macro F1; full set, each subset and only cases where
  production would call the model. It lists missed requests, fixed intents, new wrong intents, baseline and
  combined false requests, and new false scam/abuse flags. It includes p50/p95/p99 latency and errors.

The HTTP deadline is **400 ms** in the shipped Java default, even though an older config field says 600 ms.
The runner observes a response for up to 3 seconds so raw quality and actual usefulness stay separate.
Anything arriving after 400 ms is scored as a rules fallback. This models the HTTP budget, not the additional
50–100 ms asynchronous scheduling grace in the Java caller. `--deadline-ms` is recorded if testing another
explicit configuration. It does not change a server setting.

Defaults for combined scoring are the shipped intent >= 0.4, tone >= 0.8, con >= 0.4. Rules win.
The model alone cannot initiate trade, haggle, date, flirt or insult. Strict schema validation reports missing
fields, invalid labels and nonfinite/out-of-range confidences separately from what the current Java parser
accepts: production allows partial valid answers and does not reject every out-of-range confidence.
An invalid schema is a compatibility issue, even if the permissive server would salvage a field.

Each request goes directly to the endpoint: there are no Java chat-cache hits. Repeated runs may have warm model
or endpoint caches; do not call them cold-start measurements. Collect cold-start/warm-start and GPU/VRAM usage
separately if needed, on a dedicated endpoint. The test does not simulate the server's 8-call-per-second budget,
chat-generation traffic, a real player session, or a 3,000-bot soak. No load test runs by default.

## Dataset and fair comparison

172 labelled cases, fixed order before seeded shuffle:

- 59 historical tuning lines and 30 historical holdout lines, copied without changing their original intent/tone
  labels (`question`/`chatter` become `talk`). They have no con gold labels: con is excluded from those scores.
- 83 synthetic challenge lines, with all three labels: shorthand, indirect requests, quotes, negation, past tense,
  fair trades versus scams, account abuse, manipulation, benign banter and punctuation cleaning.

The historical holdout was held out when ticket 59 was written; it is now public to developers and is not a new
blind test. The new challenge set is also a development benchmark, not a blind certification. Some language is
ambiguous. Review disputed labels before selecting a model, record corrections/version changes, and rescore
**all** candidates from their raw responses on the same dataset hash. Never tune on the historical holdout.
The published question explicitly includes discussion of guilds, so "did ur guild finish zakum" is `guild`,
although it does not ask the bot to move. Request counts are **intent proxies**, not actual executed actions.

The new negatives reveal existing rule weaknesses too. A model cannot fix a mistaken rule while rules always win.
Report baseline problems separately; don't credit/blame a model for them. This benchmark does not change the rules.

## Candidate list supplied by the owner

All are on 192.168.0.17; no auth. GET `/health` is available.

| Port | Candidate | Wire model id | Note |
|---|---|---|---|
| 8900 | GLiNER2.5-Decide | decide | Current server endpoint; gentle testing only |
| 8911 | gliclass-v4-edge | decide | Same question shape |
| 8912 | amber-0.4b | decide | Choice supported; no ordinal/score questions needed here |
| 8910 | OneJev1.6-0.8B | OneJev1.6-0.8B | Requires exact id; stays on its A4000 |
| 8800 | laya-rl-agent | decide | English-only; evaluate the returned `confidence`, as production does |

Port 8095's qwen9b is the chat-writing model, not a drop-in decision endpoint. It needs a separate benchmark or
explicit adapter; it is not included in these comparisons. Published general benchmark scores are not evidence
of MapleStory accuracy. Confidence values may have different meanings across backends; keep production thresholds
fixed for the first comparison and examine each backend's mistakes before discussing calibration.

## Handoff brief for the agent on 17

> Compare the five decision endpoints using this exact dataset and exported production questions. Keep all model
> settings unchanged for the first pass. Use serial calls and the same seed; include raw responses, source/model
> revisions, dataset hash, runtime settings and GPU/VRAM readings. Do not relocate models or restart the services
> that also serve Live. The A4000 has little spare VRAM, so don't start another model there. Test speed with the
> existing chat writer active; any heavier concurrency test needs a separate agreed window. Bring captures back
> for Java replay. Prioritize no new mistaken action intents or false scam/abuse accusations, then useful request
> recall and the 400 ms deadline. Compare individual intent/tone/con strengths before proposing two-model routing.
> Propose a configuration; don't change the game server's endpoint, thresholds, economy, daily-life scores or Live.

A candidate with better averages but new false action intents or false accusations is not ready to replace the
current setup. All intended replacements still need a fresh unseen set and real in-game friend-request checks.
Multiple models may help only if their different strengths survive those checks and their combined latency,
GPU memory and request budget fit alongside chat. No automatic multi-model router ships with this benchmark.
