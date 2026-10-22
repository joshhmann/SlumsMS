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
`--url <cloud base> --model <model> --api-key-env SOLOMAPLING_LLM_CLOUD_API_KEY --budget-usd 1 --price-in 0.28
--price-out 0.42` (it stops at the budget, estimated from the responses' token counts).

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
