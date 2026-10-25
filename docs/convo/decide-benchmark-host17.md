# SlumsMS benchmark on 192.168.0.17

Start here: `/root/slumsms-decide-benchmark`.
Source snapshot: branch `160-decide-benchmark`, commit `a6c3692e0` (benchmark implementation `647e1d5f1`).
This is a standalone, selected-file snapshot, not a git checkout or a runnable game-server installation.
`target/Cosmic.jar` supplies the production classifier and its dependencies to the test-only bridge.
Only run the named bridge through the Python tool. Do not run the jar's game-server main.

Read `AGENTS.md`, then `tools/convo/benchmark/README.md` and
`docs/convo/decide-model-benchmark.md`. Current server context is in `docs/HANDOFF.md` and ticket 60.
All original model responses and comparison scores are in `results/`.

Transfer verified on 17: file checksums pass, all 8 Python tests pass, the no-model baseline reproduces 77.9%
intent accuracy, and offline replay reproduces current GLiNER at 81.4%. Java was capped at 256 MiB and one CPU.
No model requests or service changes were made during transfer verification.

## Verify the copy without calling a model

```bash
cd /root/slumsms-decide-benchmark
sha256sum --quiet -c SHA256SUMS
python3 -m unittest discover -s tools/convo/benchmark -p 'test_*.py'
JAVA_TOOL_OPTIONS='-Xmx256m -XX:ActiveProcessorCount=1' \
  python3 tools/convo/benchmark/run.py --out runs/rules-reference
JAVA_TOOL_OPTIONS='-Xmx256m -XX:ActiveProcessorCount=1' \
  python3 tools/convo/benchmark/run.py \
  --replay results/decide-GLiNER2.5-Decide/raw.jsonl \
  --name GLiNER2.5-Decide --out runs/current-model-replay
```

Output directories must be new each time; the runner refuses to overwrite results. The transfer verification uses
`runs/transfer-check-rules` and `runs/transfer-check-replay`, so the example names above remain available.

## Run a candidate locally

```bash
JAVA_TOOL_OPTIONS='-Xmx256m -XX:ActiveProcessorCount=1' \
  python3 tools/convo/benchmark/run.py --endpoint http://127.0.0.1:8911 \
  --name gliclass-v4-edge --out runs/gliclass-new-run
```

Use `--model-id OneJev1.6-0.8B` on port 8910. Other endpoints use default `decide`.
Ports: current GLiNER 8900, gliclass 8911, Amber 8912, OneJev 8910, Laya 8800. Qwen chat on 8095 is separate.
Default: one request at a time, at most one start per second. Local timings exclude the game server's LAN trip.
The 400 ms budget and production confidence thresholds remain unchanged.

## Work boundaries

The owner authorized gentle benchmarking of these five decision endpoints and is open to evaluating more than one
model. That does not authorize changing game-server endpoints, rules, thresholds or deployments, or stopping,
restarting, relocating or reconfiguring shared model services. Current services also support Live. Do experiments
in new folders, preserve these reference results, and record changes/model versions. The A4000 has little spare
VRAM: don't load another model or move OneJev. No heavy load test without an agreed window.

The bot daily-life brain is weighted math. This benchmark reads player chat, not every bot's daily decision.
No Live game-server operation or DB read, no shared TEST deployment, no Discord posts, and no outward publication.

## What to improve

Gliclass is the strongest development candidate, but still turns "i helped kill balrog yesterday" into a new help
request and flags "would u take 500k instead" as a scam. Current GLiNER also has errors. Seven incorrect request
intents and three false abuse flags originate in existing server rules and cannot be fixed by swapping models
while rules take priority. Document those separately; don't change game behavior from this workbench.

Compare intent, tone and scam/abuse separately. Look at negation, quoted speech, past tense, fair bargaining,
scam warnings and ordinary game banter. Preserve normal request recall. Confidence fields differ across engines;
use the real returned `confidence` in production replay and document any experimental calibration separately.
Freeze a separate evaluation set before tuning. Do not tune on the historical holdout or claim this visible
172-case development benchmark is blind. Keep all candidate comparisons on the same dataset hash.

Deliver a short report with reproducible commands, source/model versions, raw outputs, per-class scores, new
mistakes versus baseline, latency and measured VRAM. Explain whether multiple models add demonstrated value.
Propose the next configuration for TEST; don't switch the game server yourself.
