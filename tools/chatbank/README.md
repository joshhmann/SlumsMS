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

`generate.sh player [gen options] [--target 4000] [--rows a,b] [--repo-root .]` and
`generate.sh backfill [gen options] [--bank tools/chatbank/state/lines.tsv]` write labelled two-line exchanges to
`state/pairs.jsonl` (rejects with the reason in `state/pair-rejects.tsv`). They never write `lines.tsv` or
`chatbank.bin`; `pack` stays bot replies only. One call handles `--batch` items (8 for `player`, 6 for `backfill`); a
second call label-checks them. `--reasoning-effort` is ignored here (on .17, low/high spent the whole token cap
thinking and returned nothing); `--check-url`, `--check-model` and `--check-api-key-env` run the label check on another
model (default: the generating one).

Each row: `convo_id` (p000123), `player_line` (literal text, real names, never a `{slot}`), `player_intent`,
`player_tone`, `player_con` (exactly the Decide labels of `ConvoSense.INTENT`, `ConvoSense.TONE` and
`ChatTriage.CON`; con is always a label string, `normal` included), `player_act` (`ask`, `announce`, `refuse`, `greet`,
`chat`), `bot_reply` (gen: may keep the bank's slots; backfill: the bank line with real names filled in), `bot_label` (a
bank intent), `voice` (the reply's typing style), `place`, `affinity` (the bot's feelings), `variant` (the hard
negative it is, or `-`), `source` (`gen` or `backfill`) and, for backfill, `bank_id`.

- **Plan rows** (`ChatPairGen.ROWS`): plausible (intent, tone, con, act, variant). Requests in three tones; the cons,
  only with the intents where they are real (`ChatPairGen.CON_INTENTS`: scam with talk or trade, rwt with trade,
  manipulation with talk, harassment with talk or insult; an honest "priest buff plz" is never scam); and the **hard negatives**, all con `normal`: talk that reads like a request
  (`omw`, `refusal` "sorry cant do kpq tonite", `hello`, `bye`, `story` "i helped kill balrog
  yesterday", `denial` "im saving for a weapon, not asking u for mesos") and near-misses that read like a con
  (`payback` between friends, `wts` "wts 10k nx code 50m", `priced` fair trades and haggles). A public looking-for-group shout (`lfg`, "lf2m kpq lv 25+") is a party invite (owner
  2026-10-27; holdout-70 labels "lf1 for opq im a cleric" party): intent party, act announce.
- **Earlier lines** (round 5, owner 2026-10-27): every pair has `context`, a list of 0 to 5 earlier lines of the same
  conversation (`{"who": "P"|"B"|"O", "text": ...}`; O = someone nearby); the labels describe `player_line` given
  them. `slow` (con scam) is the ask at the end of 3 to 5 trust-building lines; `slow_ok` (con normal) is the same
  build-up ending honestly, so build-up alone never reads as a scam. Both have a floor (target/20, 200 at 4000) and
  count toward the hard-negative share; about 30% of calls for other normal rows ask for 1 to 3 ordinary earlier lines. Harassment rows always come
  with 2 to 4 earlier lines in which P is already going after B (a lone jab reads as trash talk), and haggles with 1 to 3
  that name the item and its asking price (without them a haggle reads as a trade offer).
  The model writes them as `CP:` / `CB:` / `CO:` before `P:` and `B:`. Every earlier line passes the player line's
  realism, length, brace, hard-limit, money and frozen-set checks, may not repeat the player line, and their number must
  be in the asked range. The label check sees them and labels the P line given them. Backfill pairs have none.
- **Cells**: a row times the bot labels that fit it (`ChatPairGen.botLabels`: party → party_yes/no/already, any scam →
  brush_scam, talk greet → greeting or bye, talk ask → talk_q ...) times six voices and seven places.
- **Coverage plan** (`--target`, default 4000): every intent, con, tone and act at least target/20 (200), talk at most
  20% of pairs, neutral tone aimed at 65%. The picker takes the row furthest below its floors (rows the model keeps
  failing are discounted), then its least-filled cell. `--rows story,denial` stages only rows whose key contains one
  of these (it skips the plan). `--hard-share` (0.25): at least that share of calls goes to the hard negatives, one
  variant after another, ahead of the floors and the talk cap.
- **Backfill**: for each bank row whose label implies an incoming line (`ChatPairGen.BACKFILL`: party_* → party, help_*
  → help_kill, brush_scam → con scam with the intent chosen by the model, greeting → talk/greet, thanks → talk/chat ...;
  the `thread_*` lines are skipped), the model writes the player line that reply answers. The training text is that
  generated player line, never the bank line. Done bank ids go to `state/backfill-done.txt`, so a rerun goes on.
- **Player line checks:** at most 70 characters, no braces or markup, not self-describing ("i am asking you for a
  party"), only the hard PG-13 limits (`ContentGuard.hardProblem`: nothing sexual, no slurs, no real-life threats,
  self-harm or wishing death; bot/AI questions, scams and harassment are the point), real-money words only on `rwt`
  lines, no exact duplicate (file-wide) or near duplicate (same intent and con), no copied prompt or variant example,
  no frozen-set line. The prompt asks for 5-40 characters, lowercase and 2008 shorthand.
- **Reply checks:** the bank's own (`ChatBankGen.lineProblem`: voice check and guard, slots of its bot label, length).
- **Label check** (round 3, replaces the yes/no verifier): the model gets the numbered lines without their intended
  labels (only what B is meant to do) and labels each one blind, as JSON: intent, tone, con, act (the classifier's own
  label words, with the hard cases spelled out), `reply_ok` and `typed`. A pair is kept only when intent, con and act
  equal its cell's, the tone matches (or is neutral against kind), the reply is ok and the line is not untypeable. Every
  disagreement goes to `pair-rejects.tsv` as `label mismatch <field> want=<x> got=<y>`. Round 5 review: on a con row the
  intent may read talk or trade (any intent `CON_INTENTS` allows for that con) when the con itself matches; for date, a
  flirty reading counts as kind. A `denial` may read as act refuse (both are talk with con normal). What the check read is kept on the pair as `checked` (intent, tone, con, act). Replies
  that sound like a help desk ("Real money is against the rules") are refused (`reply assistant-like`). Replies that swear at
  the player ("stfu", "go fuk urself") are refused (`reply hostile`); ContentGuard has no profanity list by design.
- `stats` prints pairs per intent, tone, con, act, bot label, source and variant, the player line length
  distribution, every plan floor with how far off it is, the talk, neutral and hard-negative shares, each run's pairs
  per call, reject share and estimated USD spend for generation plus check (`pair-runs.tsv`; from `--price-in` /
  `--price-out`, so notional on the local model), the label mismatches by field and want/got, and rejects per reason.

## Frozen evaluation sets (never trained on)

Checksums in `HOLDOUT-SHA256SUMS` (`sha256sum -c tools/chatbank/HOLDOUT-SHA256SUMS`); `FrozenSetsTest` fails if any
file changes. The pair generator rejects any player line that matches a line of either set exactly or nearly (3-gram
Jaccard >= 0.6), counted per set (`frozen holdout-70`, `frozen bench-96`).

- **holdout-70**, the real-player test: `src/test/resources/chat/convo-holdout.tsv` (30) and
  `src/test/resources/chat/holdout-lines.tsv` (40). These 70 lines are never used for training.
- **bench-96**, the model-comparison set: `tools/convo/benchmark/eval/fresh-v3/cases.jsonl`, 96 hand-labelled
  cases, never trained on, and kept separate from the 70 (its own checksum line and its own test).
