# Chat classifier training (1.6.1, on .17)

The model behind ConvoSense's intent and con questions (`:8913`, owner 2026-10-28). Tone stays on the base
OneJev1.6-0.8B at `:8910`. These scripts ran on .17 under `/root/slumsms-decide-benchmark/chat-classifier-2026-10-27/`
and expect its layout (the generator copy at `chatbank-generator/repo`, OneJev at `/root/OneJev`).

- `build_rows_ctx.py pairs.jsonl out_dir [fresh.jsonl]`: pairs to OneJev rows, intent and con only, with the earlier
  lines in the state exactly as the server sends them (`Earlier in this chat:` / `player:` / `you:` /
  `someone nearby:`, oldest first, then `A MapleStory player typed this to you in chat: <line>`; no earlier lines =
  today's state). Checks the frozen sets' hashes, converts holdout-70 and bench-96 for eval only, and turns fresh
  (never trained) pairs into `ctx_*`/`noctx_*` evals.
- Train: `python -m train.sft` in `/root/OneJev` (LoRA r16/a32, lr 1e-4, 2 epochs for r7a and r7, `--max-len 512`), GPU pinned by
  UUID with `CUDA_VISIBLE_DEVICES`, on a free card only.
- `merge.py base adapter out`: merges the LoRA (CPU) for `flide serve` and `train.eval_mm`.
- `split_bench.py`: scores the serving split end to end (intent+con from one endpoint, tone from the other).
- `../checks/deathwish.tsv`: a lone death wish must read harassment, game deaths and trash talk normal. A model
  replaces `:8913` only with every line right, and only if it beats gliclass zero-shot (61.4%) on holdout-70.

- `split_r7.py`, `final_eval.sh`: the r7 split and the final side-by-side scoring (see below).

## Final scores (2026-10-29, `final_eval.sh`)

Every model on the same sets from `data/chat-r7`, one line at a time as served. Fresh: 1,499 generated pairs held out
by convo id that none of these models trained on (595 of them have earlier lines: `ctx` with them, `noctx` with them
stripped). Death-wish: `../checks/deathwish.tsv`, 8 harassment and 8 normal lines.

| Model | h70 intent | h70 con | b96 intent | b96 con | death-wish | fresh intent | fresh con | ctx con | noctx con | noctx intent |
|---|---|---|---|---|---|---|---|---|---|---|
| base OneJev1.6-0.8B (`:8910`) | 58.6% | 95.0% | 65.6% | 79.2% | 12/16 | 60.2% | 87.3% | 87.9% | 83.7% | 55.0% |
| r5, 3k pairs (`:8913` now) | 68.6% | 95.0% | 79.2% | 90.6% | 11/16 | 82.5% | 96.8% | 97.1% | 96.0% | 82.0% |
| r7a, 11.4k pairs | 77.1% | 97.5% | 86.5% | **89.6%** | 16/16 | 88.7% | 98.0% | 98.3% | 94.8% | 75.3% |
| **r7**, 31.9k pairs | **78.6%** | **97.5%** | **89.6%** | **97.9%** | 15/16 | **92.9%** | **99.0%** | **99.3%** | 95.8% | 75.3% |

- r7a's bench-96 con regression (89.6% < r5's 90.6%) was scam warnings read as scams; r7 gets all 7 warnings right.
- r7's death-wish miss is a false flag: "lets go die to horntail lol" read as harassment. It catches all 8 real death
  wishes (r5 misses "kys" and "drop dead noob").
- r7 misses "ur parents must be proud of such a trash player" (harassment), which r5 gets. No model gets "gm here,
  trade me ur best equip for a bug fix" (scam).
- `noctx`: a line whose label depends on earlier lines, scored without them. r7 and r7a are lower than r5 (intent
  75.3% vs 82.0%), so they need the server to send the earlier lines (TEST does).
- Training: r7 is 2 epochs, 63,798 rows, 2 h 59 min on the 5060 Ti; the served copy is
  `/root/models/jev-small/OneJev1.6-0.8B-chat-r7` (SHA256SUMS in the folder), not yet on `:8913`.

### Earlier runs (scored by `train.sft` at the end of training)

| Model | h70 intent | h70 con | b96 intent | b96 con | deathwish |
|---|---|---|---|---|---|
| base OneJev1.6-0.8B | 58.6% | 95.0% | 65.6% | 79.2% | |
| r5 (3k pairs, no context; on `:8913`) | 68.6% | 95.0% | 79.2% | 90.6% | 11/16 |
| r6 (3k pairs, with context) | 71.4% | 92.5% | 85.4% | 91.7% | 9/16 |
