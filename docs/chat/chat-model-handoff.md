# Chat model handoff (1.6.1, October 2026)

The chat-model work wrapped up on 2026-10-28 (owner, through the Server Dev session). This page is what is deployed
where, how the data was made, how to retrain and score a model, and the label rulings. Scores are in
[`tools/chatbank/train/README.md`](../../tools/chatbank/train/README.md); the pair generator is in
[`tools/chatbank/README.md`](../../tools/chatbank/README.md).

## What reads player chat

ConvoSense asks three questions about each line a player says to a bot: **intent** (15 labels: party, trade, haggle,
talk ...), **tone** (neutral, kind, rude, flirty) and **con** (`ChatTriage.CON`: normal, scam, manipulation,
harassment, rwt). The rules in `ChatTriage` run first and win over the model for con. The model is asked through
Decide, `POST /v1/systemone` on a `flide serve` endpoint on 192.168.0.17.

| Where | What it runs |
|---|---|
| Live (1.6.0.4) | the 1.6.0 Decide shadow: the base model scores lines, nothing acts on it. No split, no trained model. |
| TEST (1.6.1) | the split: intent and con from `:8913` (trained), tone from `:8910` (base). `config.yaml`: `BOT_DECIDE_URL`, `BOT_DECIDE_TONE_URL`, `BOT_DECIDE_MODEL` and `BOT_DECIDE_TONE_MODEL` = `jev-latest`. The server sends the earlier chat lines to `:8913`. |
| `.17 :8910` | `decide-8910`: base `OneJev1.6-0.8B`, on the A4000 (GPU-5db843be...) |
| `.17 :8913` | `decide-8913`: **r7** since 2026-10-29 05:42 UTC (10:42 PM PT Oct 28), `/root/models/jev-small/OneJev1.6-0.8B-chat-r7` (SHA256SUMS in the folder), on the 5060 Ti (GPU-e22a7e7d...). r5 before that. |

Both units restart on a crash and do **not** start at boot (no `[Install]`, per `/root/MODEL_DOCTRINE.md` on .17). The
unit files and the install script are in `/root/slumsms-decide-benchmark/units/`. `flide serve` answers to the model
ids `jev-latest` and `OneJev1.6-0.8B`, not `decide`. It has no LoRA adapters, so a trained model is served merged.

Never seen in a real client: everything above was checked by scores and config only.

## Swapping the model on :8913 (owner's go)

`:8913` feeds TEST now and live from 1.6.1, so a swap needs the owner's go.

1. Copy the merged model next to r5: `cp -r /root/OneJev/train/runs-chat3k/<run>/merged
   /root/models/jev-small/OneJev1.6-0.8B-chat-<run>`, then write its `SHA256SUMS`.
2. In `/etc/systemd/system/decide-8913.service` (and the copy in `units/`), point `--model` and `Description` at
   the new folder; `systemctl daemon-reload; systemctl restart decide-8913`.
3. Check: `curl -s localhost:8913/v1/models`, one `/v1/systemone` call per question, `journalctl -u decide-8913`.
4. Going back to r5: `cp -p /root/slumsms-decide-benchmark/units/decide-8913.service.r5 /etc/systemd/system/decide-8913.service && systemctl daemon-reload && systemctl restart decide-8913`.
5. Smoke: `python3 chat-classifier-2026-10-27/smoke_8913.py` (death-wish and bench-96 con against the live endpoint; r7 15/16 and 94/96, r5 11/16 and 87/96).

## The data

Player-side pairs from DeepSeek, made by the pair generator (`ChatBankGen player|backfill|stats`, class
`ChatPairGen`), on .17 in `/root/slumsms-decide-benchmark/chatbank-generator/`.

- `pairs-50k-r6/pairs.jsonl`: **33,573 pairs** (the "50k run", owner 2026-10-28: about 31-32k was accepted; each leg
  made fewer new pairs per call, 3.5 in leg 1 to 2.0 in leg 5, as near-duplicates piled up). Five legs of about 10k
  calls' worth, each reviewed before the next (label counts, frozen-set leak check, 60 read by hand), plus a backfill
  from the bot reply bank. About $7.80 in all. 0 frozen-set leaks.
- Each pair: the player line, its intent, tone, con and act, 0 to 5 earlier lines (`context`), the bot's reply and
  label, the generator `variant` (hard negatives such as `warning`, `slow_ok`, `priced`) and `checked` (a blind label
  check by a second model call).
- Earlier generator states and samples: `pairs-3k-r5`, `manip-r6`, `r6dw`, `warn-trial`, `manip-ctx-trial`.
- Training splits and logs: `/root/slumsms-decide-benchmark/chat-classifier-2026-10-27/` (`r7-train.jsonl`,
  `r7-fresh.jsonl`, `r7.log`, `final-*.json` and `final-preds-*`). OneJev rows: `/root/OneJev/data/chat-r7`.
- Known small faults: some earlier lines don't add up (a haggle whose prices jump around); 5 lines kept the
  generator's `B` stand-in for the bot ("hi B, hows it going"), dropped from training by `split_r7.py`.

**Frozen sets, never trained on or edited:** holdout-70 (`src/test/resources/chat/convo-holdout.tsv`,
`holdout-lines.tsv`) and bench-96 (`tools/convo/benchmark/eval/fresh-v3/cases.jsonl`); their hashes are in
`tools/chatbank/HOLDOUT-SHA256SUMS` and `build_rows_ctx.py` checks them. The 16 death-wish checks
(`tools/chatbank/checks/deathwish.tsv`) are not frozen but are kept out of training.

## Retraining and scoring

All on .17, on a free GPU only, pinned by UUID (the 5060 Ti e22a; `:8913` shares it at about 2 GB). Scripts are in
`tools/chatbank/train/`; copies run from `chat-classifier-2026-10-27/`.

1. `split_r7.py`: 5% of convo ids held out by hash (`sha256(convo_id) % 20 == 0`), minus anything an earlier model
   trained on, so the fresh set is unseen by every model compared; drops the death-wish check lines and the `B`
   stand-ins.
2. `build_rows_ctx.py r7-train.jsonl /root/OneJev/data/chat-r7 r7-fresh.jsonl`: intent and con rows with the earlier
   lines exactly as the server sends them, plus holdout-70, bench-96 and fresh evals. Copy `deathwish_con.jsonl` in.
3. Train in `/root/OneJev` (`/root/venvs/decide/bin/python -m train.sft`): LoRA r16/a32, lr 1e-4, 2 epochs,
   `--max-len 512 --batch-tokens 8192 --max-rows 16 --accum-rows 16`. About 11-12 rows/s; 64k rows take 3 h.
   The exact command is in `/root/OneJev/train/runs-chat3k/r7/args.json`.
4. `merge.py base adapter out` (CPU) to get a model `flide serve` and `eval_mm` can load.
5. `final_eval.sh <name> <model dir>`: every eval set, scored one line at a time as served; preds by `id` (they are
   not in file order).
6. `split_bench.py` for the served split end to end.

**Bar to replace `:8913`** (owner): beat gliclass zero-shot 61.4% on holdout-70, at least r5 everywhere, and all 16
death-wish checks right.

## Label rulings (owner)

- Begging is a **scam**; asking for a password or login is a **scam**; **rwt** is real money only.
- A player **warning** others about a scam, a password ask or a real-money deal is con **normal**.
- Generic trash talk ("ur gear is trash", "noob") is an insult with con **normal**; targeted, repeated or personal
  abuse is **harassment**. A lone "go die" / "kys" is harassment.
- A public looking-for-group shout ("lf1m kpq whisper me") is a **party** invite.
- Context matters: the same ask after trust-building lines can be a scam.
- Asking whether the bot is a bot, or poking at its rules, is **manipulation**. Bots never say they are AI.
- Regulars may swear like real players; slurs, sexual content and real-life insults stay blocked (`ContentGuard`).

## Open

- **On `:8913`: r7** (owner accepted its one death-wish false flag, 2026-10-29; swapped for TEST). Was recommended as **r7** (`OneJev1.6-0.8B-chat-r7`, staged on .17 with SHA256SUMS,
  not served). Against r5 it is better on every frozen set (holdout-70 intent 78.6% vs 68.6%, bench-96 con 97.9% vs
  90.6%) and catches every real death wish, but it gets 15/16 death-wish checks, not the 16/16 bar: it flags "lets go
  die to horntail lol" as harassment. Taking it needs the owner to accept that miss. Without that, r5 stays; r7a meets
  the death-wish bar but regresses bench-96 con (89.6% vs 90.6%). TEST first, live only with 1.6.1 and the owner's go.
- The `ChatTriage` harassment rule flagged "stfu" and "ur so trash"; narrowed to death wishes, slurs and real-life threats on `161-triage` (b54960d2a), not yet merged.
- Whispers reach `:8913` without earlier lines (`BotConversation.java:492` in 1.6.1); r7 is weakest there (noctx intent 75.3%).
- Tone was never trained; the base model handles it (bench-96 tone 67.7%).
- No new data or training rounds after r7 (owner, 2026-10-28).
