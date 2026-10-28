# Chat classifier training (1.6.1, on .17)

The model behind ConvoSense's intent and con questions (`:8913`, owner 2026-10-28). Tone stays on the base
OneJev1.6-0.8B at `:8910`. These scripts ran on .17 under `/root/slumsms-decide-benchmark/chat-classifier-2026-10-27/`
and expect its layout (the generator copy at `chatbank-generator/repo`, OneJev at `/root/OneJev`).

- `build_rows_ctx.py pairs.jsonl out_dir [fresh.jsonl]`: pairs to OneJev rows, intent and con only, with the earlier
  lines in the state exactly as the server sends them (`Earlier in this chat:` / `player:` / `you:` /
  `someone nearby:`, oldest first, then `A MapleStory player typed this to you in chat: <line>`; no earlier lines =
  today's state). Checks the frozen sets' hashes, converts holdout-70 and bench-96 for eval only, and turns fresh
  (never trained) pairs into `ctx_*`/`noctx_*` evals.
- Train: `python -m train.sft` in `/root/OneJev` (LoRA r16/a32, lr 1e-4, 3 epochs, `--max-len 512`), GPU pinned by
  UUID with `CUDA_VISIBLE_DEVICES`, on a free card only.
- `merge.py base adapter out`: merges the LoRA (CPU) for `flide serve` and `train.eval_mm`.
- `split_bench.py`: scores the serving split end to end (intent+con from one endpoint, tone from the other).
- `../checks/deathwish.tsv`: a lone death wish must read harassment, game deaths and trash talk normal. A model
  replaces `:8913` only with every line right, and only if it beats gliclass zero-shot (61.4%) on holdout-70.

| Model | h70 intent | h70 con | b96 intent | b96 con | deathwish |
|---|---|---|---|---|---|
| base OneJev1.6-0.8B | 58.6% | 95.0% | 65.6% | 79.2% | |
| r5 (3k pairs, no context; on `:8913`) | 68.6% | 95.0% | 79.2% | 90.6% | 11/16 |
| r6 (3k pairs, with context) | 71.4% | 92.5% | 85.4% | 91.7% | 9/16 |
