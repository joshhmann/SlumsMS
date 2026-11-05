# Social intelligence on TEST: runbook (ticket 271, stage 7)

For Server Dev and the owner. Ticket 271 adds a social mind to the bots: a mood and needs that move, eight-axis
relationships, structured memories, a small set of social acts (greet, chat, gift, argue, apologise, reconcile,
avoid, spend time, invite and answer), intents that outlive a single tick ("meet me at 8"), a social budget that
stops a pair from looping, and a mode switch that lets a bot leave training for social or town life and come back.
The code is built and unit-tested; **none of it has been deployed to TEST or live yet** (TEST's `DEPLOYED_COMMIT`
was `fae9b4849` when this was written, which predates the social stages). Everything below is what to run on TEST
once a build of `164-social-int` (or its successor) is deployed.

Read-only by design: every check here reads logs, `live.json`, the inspect API or SELECTs. Nothing writes to the
game, the DB or the roster.

## 1. What to deploy

- Branch `164-social-int` (or the successor Server Dev hands over) built with `mvn -o -DskipTests package`.
- Data files and migrations ride with `tools/ops/deploy-test`; it backs up and restarts, and fails if the boot
  logs an error. Do **not** hand-copy the jar: the social stores ask once at boot whether their columns exist and
  fall back silently if they do not, so a wrong order looks like "the feature is on but nothing is remembered".
- Nothing in `config.yaml` changes. All social switches are `-D` system properties with defaults already ON, so
  the current `start-test.sh` needs no edit for the feature to run (it already has `-Dslums.botlife.enabled=true`,
  `-Dslums.botlife.execute=true`, `-Dslums.bottalk=true`, `-Dslums.socialtrace=9`, `-Dslums.qa=true`,
  `-Dslums.autoqa=true`, `-Dslums.inspect.port=8198`, `-Dslums.inspect.secretFile=/etc/slums-inspect-test/secret`).

## 2. Switches

All default ON. A missing `-D` is the ON state; the only way to turn one off is the explicit `=false` below.

| What it does | Switch (default ON) | Turn it off with |
|---|---|---|
| Mode switch: leave training for social/town and come back | `slums.modeswitch` | `-Dslums.modeswitch=false` |
| Social acts between bots (greet, chat, gift, argue, ...) | `slums.socialacts` | `-Dslums.socialacts=false` |
| Social budget: per-pair cooldowns, turn limit, rate caps | `slums.socialbudget` | `-Dslums.socialbudget=false` |
| Intents ("meet me", "I'll bring it") that outlive a tick | `slums.intents` | `-Dslums.intents=false` |
| Bot-to-bot gifts | `slums.botgifts` | `-Dslums.botgifts=false` |
| Social LOD (cheap social tick on far maps) | `slums.sociallod` | `-Dslums.sociallod=false` |
| Background bond writes on busy maps | `slums.bonds.background` | `-Dslums.bonds.background=false` |
| Axis fade half-lives (per axis, days) | `solomapling.axes.<axis>.halfLifeDays` | set a large number to freeze an axis |
| Bond event weights | `solomapling.bonds.<event>` | set 0 to mute one event |

The mode switch also needs the daily-life brain, which is why `slums.botlife.enabled` and `slums.botlife.execute`
must stay true: `ModeSwitch.enabled()` is false without `LifeRuntime.reachesAllBots()`, and `t-ms-1` SKIPs then.

Rollback order if something is wrong (least to most disruptive): `-Dslums.modeswitch=false` (bots stay training
bots; minds and bonds keep working), then `-Dslums.intents=false`, then `-Dslums.socialacts=false` (no acts, no
gifts, no arguments; memories and minds still load), then `-Dslums.botlife.execute=false` (the whole daily-life
brain off, back to 1.6.0 behaviour). No switch needs a migration rollback; 062 and 063 are additive columns that
an older jar ignores.

## 3. Before the jar: migrations 062 and 063

Both must be in the release conf `MIGRATIONS` list and applied **before** the new jar starts. They are additive and
idempotent; an older jar runs fine on a migrated database.

- `062-bot-relationship-axes`: ten columns each on `botbond` and `botaffinity` (Familiarity, Trust, Affection,
  Respect, Attraction, Rivalry, Resentment, Obligation, Stage, HasAxes). Ledger id `062-bot-relationship-axes`.
- `063-bot-memory-social`: six columns on `botmemory` (SubjectType, Location, Confidence, Valence, Provenance,
  OtherParticipant). Ledger id `063-bot-memory-social`.

Release conf line (add to the existing list, keeping the file stems, no `.sql`):

```
MIGRATIONS=(... 062-bot-relationship-axes 063-bot-memory-social)
```

Apply on TEST (schema `mapleroot_persist`, container `mapleroot-integration-db`, port 3307):

```
docker exec -i mapleroot-integration-db mysql -uroot -p"$DB_PASS" mapleroot_persist < database/migrations/062-bot-relationship-axes.sql
docker exec -i mapleroot-integration-db mysql -uroot -p"$DB_PASS" mapleroot_persist < database/migrations/063-bot-memory-social.sql
```

Run each twice; the second run must change nothing (`DO 0` branches). Then check the ledger:

```sql
SELECT migration_id, applied_at FROM mapleroot_integration_migrations
 WHERE migration_id IN ('062-bot-relationship-axes', '063-bot-memory-social');
```

Two rows, one per migration. Without them the server still boots, but bonds lose their axes on every restart and
new memories keep their social fields in RAM only — which is exactly the bug the runbook's restart check catches.

## 4. After the boot: read-only checks

Wait 10 to 15 minutes first. The mode switch evaluates each bot at most once a minute and needs a 20-minute dwell
before it will move anyone, so an empty census right after boot is normal.

**The census and the counters** (every 10 minutes per server):

```
journalctl -u slums-persistence-test --since '30 min ago' | grep 'Mode switch: census'
```

Each line is `Mode switch: census {TRAINING=n, SOCIAL=n, TOWN=n} counters {...}`. Over half an hour you want all
three modes non-zero on a populated server, `switch TRAINING>SOCIAL` and `switch SOCIAL>TRAINING` both rising, and
`refused swap refused` near zero (the check fails above 5% of switches).

**`live.json`** (`/srv/slums-dashboard-test/data/live.json`, rewritten every 30 s):

```
jq '.social' /srv/slums-dashboard-test/data/live.json
```

Blocks: `modes {enabled, census {training, social, town}, stats}`, `budget {enabled, counters}`,
`acts {enabled, counters, open {invites, avoids, together}}`, `talk`, `socialTick {calls, msPerS}`,
`bonds {bonds, holders, text}`, `intents {enabled, counters}`, `minds {memoriesLoaded}`.

Quick sanity: `.social.minds.memoriesLoaded` should be roughly the number of online roster bots (each loaded memory
holds one mind); `.social.socialTick.msPerS` should stay in the low single digits (it is one phase of the tick, not
the whole tick); `.social.acts.counters` should show at least three `KIND.ok` keys within half an hour.

**Inspect API** (loopback only, port 8198, header `X-Slums-Inspect: <secret from /etc/slums-inspect-test/secret>`):

```
curl -s -H "X-Slums-Inspect: $(cat /etc/slums-inspect-test/secret)" http://127.0.0.1:8198/bots/<cid> | jq '{mode,mind,bonds,why}'
```

- `mode`: `"training"`, `"social"` or `"town"` (from the bot's class, so it always agrees with the census).
- `mind`: mood in `[-1, 1]`, the eight needs, the strongest need and the top emotions with their deviation from
  baseline. A bot whose `mind` is null has no memory in RAM (not online, or still loading).
- `bonds`: the eight axes and the stage for each partner, with the partner's name.
- `why`: the same lines `!botwhy <name>` prints (goals, plan, mode blocker, strongest needs, recent acts, intents).

**Social trace** (already on for TEST at 9 hours, `-Dslums.socialtrace=9`): one JSON line per event in
`/opt/mapleroot-dev/persistence-test/Server/data/autoqa/social-trace/<UTC date>.jsonl`, plus a running summary in
`data/autoqa/social-trace-latest.json`. `!socialtrace status` prints both paths; `!socialtrace start [hours]` and
`!socialtrace stop` control it. This is where a single conversation, drama thread or gossip spread can be read line
by line; the counters only prove that *something* happened.

**Auto-QA** (the three checks this ticket adds; lane-only, they need the roster population the QA lane sets up):

```
tools/ops/autoqa-run smoke          # starts the run without logging in
```

| Id | What it proves | SKIPs when |
|---|---|---|
| `t-ms-1` | all three modes populated, switches in both directions, swap refusals ≤ 5%, no bot above 3 switches an hour | `slums.modeswitch` off, under 8 bots have been through a decision, or fewer than 240 decisions (8 bots × 30 min) |
| `t-sm-1` | minds in range (mood in `[-1,1]`, no NaN), at least 2 of 5 loaded minds moved off their seed | `slums.socialacts` off, the social layer has made no decision yet, or fewer than 5 online bots have a loaded mind |
| `t-sa-1` | three kinds of act landed, errors ≤ 5%, budget refuses ≤ 50%, the turn limit refuses a scripted A/B ping-pong on fake ids (synthetic proof; organic turn-limit count is an info line, and FAILs only when over half of allowed acts) | `slums.socialacts` off, or under 20 attempts |

Three checks, not four: the Henesys pilot check (`t-pi-1`, probe `socialPilot`, already written and unit-tested) is
**not in the catalogue yet**. It could only SKIP until the `SocialPilot` class exists, and a release run FAILs on
any SKIP that is not on the client-only allow-list, so it is registered in the same change that lands the pilot.
`t-pi-1` rather than `t-sp-1` because `t-sp-1` is already the 1.6.2 "bots spread out" check.

## 5. SELECT-only SQL on the TEST database

Schema `mapleroot_persist`, container `mapleroot-integration-db`, port 3307. Never run anything but SELECT here.

```sql
-- 062: axes are being written, not just derived
SELECT COUNT(*) AS bonds, SUM(HasAxes) AS with_axes, FROM_UNIXTIME(MAX(UpdatedAt)/1000) AS last_write FROM botbond;
SELECT Stage, COUNT(*) FROM botbond GROUP BY Stage ORDER BY Stage;
SELECT COUNT(*) AS affinities, SUM(HasAxes) AS with_axes FROM botaffinity;

-- the axes stay in range
SELECT MIN(Trust), MAX(Trust), MIN(Familiarity), MAX(Familiarity), MIN(Rivalry), MAX(Rivalry) FROM botbond;

-- 063: bots remember each other, with a place, a confidence and a valence
SELECT SubjectType, COUNT(*) FROM botmemory GROUP BY SubjectType;
SELECT Provenance, COUNT(*) FROM botmemory GROUP BY Provenance;
SELECT COUNT(*) AS bot_notes, SUM(Location IS NOT NULL) AS with_place,
       SUM(OtherParticipant IS NOT NULL) AS with_second_bot,
       MIN(Valence), MAX(Valence), MIN(Confidence), MAX(Confidence)
  FROM botmemory WHERE SubjectType = 'BOT';

-- the mind survives a restart: every profile that has a mind has needs and emotions inside it
SELECT COUNT(*) AS profiles,
       SUM(JSON_EXTRACT(Profile, '$.mind') IS NOT NULL) AS with_mind,
       SUM(JSON_EXTRACT(Profile, '$.mind.needs') IS NOT NULL) AS with_needs,
       SUM(JSON_EXTRACT(Profile, '$.mind.emotions') IS NOT NULL) AS with_emotions
  FROM botlifeprofile;

-- a mood and a need, read the way the server stores them: [value, timestamp ms]
SELECT BotId,
       JSON_EXTRACT(Profile, '$.mind.needs.COMPANIONSHIP[0]') AS companionship,
       JSON_EXTRACT(Profile, '$.mind.needs.REST[0]')           AS rest,
       JSON_EXTRACT(Profile, '$.mind.emotions.HAPPINESS[0]')   AS happiness,
       JSON_UNQUOTE(JSON_EXTRACT(Profile, '$.mind.emotions.HAPPINESS[1]')) AS happiness_at_ms
  FROM botlifeprofile
 WHERE JSON_EXTRACT(Profile, '$.mind') IS NOT NULL
 LIMIT 10;
```

Expected: `with_axes` grows toward `bonds` as pairs meet (older rows stay 0 until they next change, which is
correct — the server derives their axes from the score at load); Stage values spread over 0..6; `SubjectType = 'BOT'`
rows appear once two bots have actually interacted; `Valence` stays within `[-1, 1]` and `Confidence` within `[0, 1]`;
`with_mind` is most of the roster after a few minutes.

**The restart check** (the one that proves persistence end to end): before a restart, note a few bots with a mind
and their `HAPPINESS[0]` / `COMPANIONSHIP[0]` values; restart TEST; wait 2 minutes; run the last query again. The
same bots must still have a mind, and the values must be close to (not reset to) the earlier ones — they decay with
time (HAPPINESS half-life 6 h, ANXIETY 2 h, LONELINESS 8 h), so a small drift is correct and a jump back to the
seed (needs around 0.2..0.5 with a fresh timestamp, emotions at baseline) means the mind was not saved.

## 6. Yield per bot hour, before and after the mode switch

The owner asked (2026-11-04) whether letting bots leave training costs meso yield. Measure it, do not assume it.

**Procedure** (two full days, same weekday-to-weekday if you can):

1. **Baseline, 24 h**: start TEST with `-Dslums.modeswitch=false` added to `start-test.sh`. Everything else stays
   as it is. Record the start time.
2. Sample `/srv/slums-dashboard-test/data/live.json` every 30 minutes (a 30-minute cron writing
   `live-<UTC>.json` is enough; the file itself is rewritten in place, so copy it).
3. **After, 24 h**: remove the flag, restart at the same time of day, sample the same way.
4. For each window compute:
   - bot-hours = Σ `bots.online × intervalS / 3600` over the samples;
   - training bot-hours = Σ `bots.types.training × intervalS / 3600` (the type keys are lower-case in
     `bots.types`: `training`, `social`, `town`);
   - meso = the last `economy.mesosToday` minus the first (it resets at midnight UTC, so keep each window inside
     one UTC day or sum the per-day deltas from the series);
   - yield per bot-hour = meso / bot-hours; yield per training bot-hour = meso / training bot-hours.
   - cross-check with the yield store, which holds each bot's meso for the day: `SELECT SUM(Meso) FROM botyield
     WHERE Day = '<date>'` (migration 006; per-bot rows, `BotId` is the roster slot, character id = 1000000000 + BotId).
5. `live.json`'s `bots.types` also gives the light-mode share: `(social + town) / online`.

**Decision rule.** Let `s0` be the training share in the baseline window and `s1` in the after window, `y0` the
baseline yield per bot-hour and `y1` the after yield per bot-hour. The light modes explain a drop down to
`y0 × (s1 / s0)` and no further. So:

- If `y1 ≥ 0.9 × y0 × (s1 / s0)`: the mode switch costs nothing beyond the bots it takes out of training. Keep it.
- If `y1 < 0.9 × y0 × (s1 / s0)`: social and town time is costing yield beyond its share (bots idling in town
  instead of grinding, or a mode that never returns them). Cap the share, do not remove the feature.

Two cautions: bot meso is noisy (drops, events, the exchange), so a single 24-hour pair is a lead and two pairs are
a decision; and `mesosToday` counts every bot, including owned alts, so compare windows with the same
`bots.roster` and `bots.target`.

**There is no share cap switch today.** The mode switch bounds itself (20-minute minimum dwell, at most 3 switches
an hour per bot, a 0.45 score threshold and a 0.5 maximum chance), but it has no world-wide "at most N% in light
modes" knob. If the rule above says the share must be capped, that is a small follow-up change (a
`-Dslums.modeswitch.maxLightShare` read by `ModeSwitch.decide`), not something this runbook can set. Until then the
only lever is `-Dslums.modeswitch=false`.

## 7. Human walkthrough in Henesys (a real client)

Log in on the QA client, go to Henesys (map 100000000), and stand still. Watch for the six things below; each has
the exact evidence that proves it, because "I think I saw two bots talk" is not proof.

1. **Bots loitering in town.** Within 20 minutes several bots should be walking between the free market, the
   fountain and the potion shop without grinding. Evidence: `live.json` `.social.modes.census.town` above zero and
   rising, and `/bots/<cid>.mode` = `"town"` for a bot you can see. Log: a `Mode switch: census` line with
   `TOWN=` above zero.
2. **A greeting between two bots.** Two bots stop, one says hello, the other answers. Evidence:
   `.social.acts.counters["GREET.ok"]` goes up (read it before and after), and `!botwhy <name>` on the greeter
   lists the greet with its partner. The trace line for the same moment is in
   `data/autoqa/social-trace/<date>.jsonl`.
3. **A gift.** One bot hands another an item; the receiver says something. Evidence: the log line
   `[BotGift] <giver> (<id>) -> <receiver> (<id>): <item> (<n> on map <mapid>)` (INFO, so it is in `journalctl`),
   and `.social.acts.counters["GIVE_GIFT.ok"]` up by one. Gifts are rare by design (one per giver per 6 hours, one
   per pair per 24 hours, 3 a day per receiver, 15 an hour world-wide), so expect a handful a day, not a stream.
4. **An argument, then later a reconciliation.** Two bots fall out (a KS, a tease, a refused invite) and the same
   pair is friendly again later. Evidence: `.social.acts.counters["ARGUE.ok"]` then, on a later reading,
   `APOLOGIZE.ok` or `RECONCILE.ok`; `/bots/<cid>.bonds` for that pair shows Trust and Resentment moving in
   opposite directions and the Stage going to RIVAL/ESTRANGED and back; the trace has the drama line. This is the
   slowest of the six — the budget allows one argument per pair every 30 minutes and one reconcile every 30
   minutes, so give it an hour or two.
5. **Bots avoiding each other.** After a bad interaction one bot keeps its distance. Evidence:
   `.social.acts.counters["AVOID.ok"]` and `.social.acts.open.avoids` above zero, and the two bots visibly not
   standing together; `/bots/<cid>.why` names the bot it is avoiding.
6. **A bot says yes to a meeting and then shows up.** A bot agrees to meet another and is on that map at that
   time. Evidence: `.social.intents.counters.created` up, then `kept` up (a `kept INVITE` key appears), with
   `broken` staying low; `/bots/<cid>.why` lists the live intent with its due time. The trace shows the two
   arriving on the same map.

If any of the six cannot be produced in an hour of watching, that is a finding, not a flake: note the time (UTC and
PT), the map, the two character names and the counters before and after, and hand it to Server Dev.

## 8. Rollback

- Feature off, keep the data: `-Dslums.modeswitch=false` (or the other flags in section 2). Nothing to undo in the
  database; minds and bonds simply stop changing.
- Old jar: 062 and 063 are additive with defaults, so the previous jar runs on the migrated schema. Roll the jar
  back with the normal `deploy-test`/deploy-script path, not by editing the DB.
- Migration rollback (only if a migration itself was wrong, and only with the game stopped and a backup taken):
  `062-bot-relationship-axes.rollback.sql` drops the ten columns from both tables; `063-bot-memory-social.rollback.sql`
  drops the six from `botmemory`. Both lose the social data they hold.

## 9. Not verified here

- None of this has run on TEST yet: the checks above are written from the code and the existing TEST setup, not
  from an observed social run.
- The mode switch's effect on meso yield is unmeasured; section 6 is the procedure, not a result.
- The Henesys pilot check (`t-pi-1`) is written but not in the catalogue until the pilot class lands, so there is
  no auto-QA coverage of the pilot yet.
- The walkthrough's six items have not been seen on a real client by the author of this file.
- The numbers in this file (cooldowns, half-lives, dwell, caps) are the code defaults at the time of writing; if a
  flag is changed on TEST, the check that depends on it will say so in its SKIP or FAIL reason.
