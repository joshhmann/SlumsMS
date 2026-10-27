# Chat line bank (ticket 59)

The bank is the bots' fallback when the LLM is busy, slow, over budget or down: short lines indexed by intent, the
player's tone, the bot's feelings, the bot's voice and the situation, with `{slots}` filled at runtime. The server
loads `src/main/java/soloMapling/ArtificialPlayer/BotConvo/chatbank.bin` (gzip TSV, packed in the jar); a bigger bank
can be dropped in with `-Dsolomapling.dataDir=<dir>` (`<dir>/soloMapling/ArtificialPlayer/BotConvo/chatbank.bin`)
without a rebuild.

## Files

- `state/lines.tsv`: every accepted line (`id intent tone affinity voice tags text`). The source of truth; committed.
- `state/rejects.tsv`: rejected lines with the reason (not committed).
- `generate.sh`: runs the generator from the built server jar.

## Steps

```sh
mvn -o -q package -DskipTests                 # the generator lives in the server jar
tools/chatbank/generate.sh seed               # the dialogue packs' lines (no LLM), once
tools/chatbank/generate.sh gen --calls 500    # generate (see the throttling below)
tools/chatbank/generate.sh stats
tools/chatbank/generate.sh pack               # writes chatbank.bin; commit it with state/lines.tsv
```

`gen` options: `--url` (http://192.168.0.17:8095/v1), `--model` (qwen9b), `--target` (100000 lines),
`--per-cell` (40), `--batch` (8 lines a call), `--calls` (50 this run), `--pause-ms` (1500), `--hours 2-8` (only
between these hours, server time), `--no-verify`, `--no-idle-check`, and for the cloud provider later
`--url <cloud base> --model <model> --api-key-env SOLOMAPLING_LLM_CLOUD_API_KEY --budget-usd 1 --price-in 0.30
--price-out 1.20 --reasoning-effort none` (it stops at the budget, estimated from the responses' token counts).

## What keeps it safe and varied

- **Never starves live chat:** one request at a time; before each, llama.cpp's `/slots` must show every slot idle
  (it waits up to 5 minutes, then stops); a pause between calls; `--hours` for idle hours only.
- **Every line:** `ContentGuard.chatProblem` (the server's voice check: no assistant phrasing, markdown, real-world
  references; no AI/bot/model talk; PG-13; game-only insults), at most 70 characters with the longest slot values,
  only the slots its intent allows, no copied prompt examples.
- **On purpose:** a second call asks the model which lines of the batch do not do what the cell is for, and drops them.
- **Not repeated:** exact duplicates (normalised) are dropped bank-wide, near duplicates (character 3-gram Jaccard
  >= 0.6) within the same intent and feeling.
- **Resumable:** accepted lines are appended as they come; a restart reads `state/lines.tsv` back. The generator fills
  the intent with the fewest lines first, then its least-filled cell (voices and places at random).

## Size

About 6 MB of heap per 100k lines (`ChatBankTest` measures it); the gzip file is about 1.5 MB per 100k lines.

## Player-side pairs (1.6.0, for the chat classifier)

`generate.sh player [gen options] [--repo-root .]` writes labelled two-line exchanges to `state/pairs.jsonl`
(rejects with the reason in `state/pair-rejects.tsv`). It never writes `lines.tsv` or `chatbank.bin`; `pack` stays
bot replies only. One call writes `--batch` (6) exchanges for one cell; a second call verifies them. Default
`--per-cell` is 10.

Each row: `convo_id` (p000123), `player_line` (literal text, real names, never a `{slot}`), `player_intent`,
`player_tone`, `player_con` (exactly the Decide labels of `ConvoSense.INTENT`, `ConvoSense.TONE` and
`ChatTriage.CON`), `bot_reply` (may keep the bank's slots), `bot_label` (a bank intent), `voice` (the reply's
typing style), `place`, `affinity` (the bot's feelings).

- **Cells:** a curated table of plausible (intent, tone, con) triples (`ChatPairGen.TRIPLES`) times the bot labels that
  fit (`ChatPairGen.botLabels`: party → party_yes/no/already, any scam → brush_scam, rwt → brush_rwt, and so on) times
  six voices and seven places; the intent with the fewest pairs first, then its least-filled cell.
- **Player line checks:** only the hard PG-13 limits (`ContentGuard.hardProblem`: nothing sexual, no slurs, no
  real-life threats, self-harm or wishing death); bot/AI questions, scams and harassment are the point. Real-money words
  only on `rwt` lines. No placeholders, markup or over-long lines; no exact duplicate (file-wide) or near duplicate
  (same intent and con); no copied prompt example.
- **Reply checks:** the bank's own (`ChatBankGen.lineProblem`: voice check and guard, slots of its bot label, length).
- **Verifier:** asks which exchanges do not match the cell's intent, tone and con (in the classifier's own label
  words) or whose reply does not do what its bot label says.
- `stats` prints pairs per player intent, tone, con and bot label, and rejects per reason.

## Frozen evaluation sets (never trained on)

Checksums in `HOLDOUT-SHA256SUMS` (`sha256sum -c tools/chatbank/HOLDOUT-SHA256SUMS`); `FrozenSetsTest` fails if any
file changes. The pair generator rejects any player line that matches a line of either set exactly or nearly (3-gram
Jaccard >= 0.6), counted per set (`frozen holdout-70`, `frozen bench-96`).

- **holdout-70**, the real-player test: `src/test/resources/chat/convo-holdout.tsv` (30) and
  `src/test/resources/chat/holdout-lines.tsv` (40). These 70 lines are never used for training.
- **bench-96**, the model-comparison set: `tools/convo/benchmark/eval/fresh-v3/cases.jsonl`, 96 hand-labelled
  cases, never trained on, and kept separate from the 70 (its own checksum line and its own test).
