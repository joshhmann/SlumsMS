# Switches

Cross-cutting reference for every way a feature is turned on. Each subsystem doc has its own `## Switches` table; this
page is the index, the enumeration method, and the live set.

## Three homes

| Home | Example | Read by | Notes |
|---|---|---|---|
| `config.yaml` key → `ServerConfig` field | `USE_FAMILY_SYSTEM` | `YamlConfig` | **Unknown keys abort boot**, so a key may only be added to a server that already runs a build that knows it (AGENTS.md, "Config is strict") |
| `-D` system property | `-Dslums.mechanics.opq=true` | `System.getProperty` / `Boolean.getBoolean` at the point of use | the preferred home for per-feature rollout; the live set lives in the systemd unit |
| environment / files named by path | secret files, data dirs, `-Dwz-path` | depending on subsystem | never quoted in this review (paths only) |

## How to enumerate

```sh
# every literal property key the code reads (116 distinct keys on this branch)
grep -rhoE 'System\.getProperty\("[^"]+"' --include=*.java src/main/java | sed 's/.*("//' | sort -u

# by prefix: 79 solomapling.*, 37 slums.*, 12 bot.*, plus wz-path/wzpath/cashshop-path/mapleroot.login-port/slumsms.gmAudit
... | awk -F. '{print $1}' | sort | uniq -c | sort -rn
```

A literal grep **undercounts**: several switch families are built from data, so their names never appear in Java:

| Family | Where the name comes from | Resolved at |
|---|---|---|
| `slums.mechanics.<row>` (57 rows: 29 boss, 28 party quest) | the row's own YAML: `flag: slums.mechanics.<row>` (e.g. src/main/java/soloMapling/mechanics/content/nwill.yaml:31, src/main/java/soloMapling/mechanics/content/zakumpq.yaml:26, src/main/java/soloMapling/mechanics/content/bossrush0.yaml:21) | `Mechanics.enabled(content)` → `Boolean.getBoolean(content.flag())` (src/main/java/soloMapling/mechanics/Mechanics.java:200, :211) |
| `slums.exchange.<key>` | an options table parsed in `ExchangeConfig` (src/main/java/soloMapling/Economy/exchange/ExchangeConfig.java:38-94) | same |
| `slums.exchange.traders.*` (`pct`, `count`, `nx`, `nx.perBotHour`, `nx.worldHour`) | documented in the class header (src/main/java/soloMapling/Economy/exchange/volatility/MarketConfig.java:9-20) | same |
| `slums.bottalk.<topic>` | topic list (src/main/java/soloMapling/ArtificialPlayer/BotConvo/BotTalk.java:162-166) | same |
| `slums.family.<key>` | `BotFamily` (src/main/java/soloMapling/ArtificialPlayer/BotFamily.java:92) | same |

That is why 19 of the 69 live flags have no literal reader hit; each of them resolves through one of the tables above.

## The live set for 1.6.4

Source: `UNIT_EDITS` in docs/release/1.6.4.conf:271 — one sed replacement that appends these to the unit's
`ExecStart` before `-jar`. 69 flags. TEST: unknown (its `start-test.sh` on mapletest was not readable for this review).

| Area | Flags (live values) | Reader |
|---|---|---|
| bots, brain, chatter | `slums.bottalk=true`, `slums.crews=true`, `solomapling.convo.planner=true`, `slums.scroll.ev=true` | src/main/java/soloMapling/ArtificialPlayer/BotConvo/BotTalk.java:162, src/main/java/soloMapling/ArtificialPlayer/BotAffinitySystem/BotCrews.java:67, src/main/java/soloMapling/ArtificialPlayer/BotConvo/ConvoSwitches.java:33, src/main/java/soloMapling/ArtificialPlayer/BotScrollSystem/BotScrolling.java:234 |
| bots, support | `slums.bots.apvariants=true` + `.fromSlot=auto`, `slums.bots.partysupport=true`, `slums.bots.famerequest=true`, `slums.bots.magicguard=true`, `slums.killcalib=true` | src/main/java/soloMapling/ArtificialPlayer/BotSupportSwitches.java:25,55,29,33,37; src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/KillCalibration.java:40 |
| bots, progression | `solomapling.jobErrand=true`, `solomapling.botSkillBooks=true`, `slums.botquests.enabled=true`, `slums.temple.enabled=true`, `slums.gear.dexless=true`, `slums.grind.gearFirst=true`, `slums.gear.optimizer=pareto` | src/main/java/soloMapling/ArtificialPlayer/BotProgressionSystem/JobErrand.java:48, src/main/java/soloMapling/ArtificialPlayer/BotProgressionSystem/BotSkillBooks.java:43, src/main/java/soloMapling/ArtificialPlayer/BotQuestSystem/BotQuests.java:63, src/main/java/soloMapling/ArtificialPlayer/BotQuestSystem/TempleOfTime.java:48, src/main/java/soloMapling/ArtificialPlayer/BotProgressionSystem/BotProgression.java:223, src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/GearFirstAdvisor.java:42, src/main/java/soloMapling/ArtificialPlayer/BotGearSystem/BotGear.java:70 |
| bots, supply/inventory | `slums.junk.sort=true` | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/JunkSort.java:42 |
| bots, navigation | `bot.nav.swimWallJump=true`, `bot.nav.blockedPosWatchdog=true`, `slums.navTrace=true` | src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/NavPortSwitches.java:23,28; src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/NavTrace.java:39 |
| bots, social/bonds | `slums.bonds=true` | src/main/java/soloMapling/ArtificialPlayer/RelationView.java:31 |
| companions | `slums.alts.lanes=true`, `slums.ownedalts.marry=true`, `slums.marriage.expbonus=true` | src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/CompanionSwitches.java:45, src/main/java/soloMapling/companions/OwnedAltWedding.java:23, src/main/java/soloMapling/companions/MarriageExpBonus.java:13 |
| economy, exchange | `slums.exchange=true`, `.startHalted=true`, `.houseAccount=15`, `.traders=true`, `.traders.pct=35`, `.traders.nx=true`, `.traders.nx.worldHour=5000`, `.catalog=scrolls,use,etc`, `.itemMaker=true`, `.items.sellCapMesosPerDay=20000000`, `.desk=true` | src/main/java/soloMapling/Economy/exchange/ExchangeConfig.java:38, src/main/java/soloMapling/Economy/exchange/ExchangeService.java:93, src/main/java/soloMapling/Economy/exchange/ExchangeLimits.java:26-29, MarketConfig.java, src/main/java/soloMapling/Economy/exchange/DeskConfig.java:23 |
| economy, crafting/economy extras | `slums.forge.coreexchange=true`, `slums.gear.bridge=true`, `solomapling.economy.maker.botBatch=true` | src/main/java/soloMapling/crafting/WeaponCoreExchange.java:11, src/main/java/soloMapling/crafting/NormalGearBridge.java:15, no literal hit (dynamic) |
| mechanics | `slums.mechanics=true`, `.lobby=true`, `.boss=true`, `.opq .lpq .hpq .cafepq .ludimazepq .piratepq .treasurepq .dellibattle .magatiapq .magatiapqz .ellinpq .cwkpq .bossrush .zakumpq .guildquest .nwill .nlucid .nverus .ndarknell=true`, `slums.chaos.zakum=true`, `slums.bossfight.revivepause=true` | src/main/java/soloMapling/mechanics/Mechanics.java:20, src/main/java/soloMapling/mechanics/KpqLobby.java:40, src/main/java/soloMapling/mechanics/Mechanics.java:34-92, src/main/java/soloMapling/mechanics/RevivePause.java:25; row flags via YAML |
| achievements | `slums.achievements=true` | src/main/java/soloMapling/achievements/Achievements.java:18 |
| site / ops | `slums.inspect=true`, `.port=8098`, `.secretFile=` (path only), `solomapling.reportsFile=` (path only), `slums.maps.routing=true` | src/main/java/soloMapling/inspect/BotInspectApi.java:60-62, src/main/java/server/reports/ReportStore.java:115, src/main/java/server/maps/MapRouting.java:50 |

Notably **absent** from the live set: `-Dwz-path` (so live uses the default data location), `-Dslums.qa` and
`-Dslums.autoqa` (live has neither, so `!qa`/`!autoqa` are never registered — docs/ops/qa-suite.md), and
`-Dsolomapling.fads=false` (removed by 1.6.4 after the b267 lock fix, docs/release/1.6.4.conf:18).

`-Dslums.exchange.startHalted=true` is deliberate: the desk boots halted and a GM unhalts it after the funding order
(conf:20-25, `ExchangeConfig`/`Exchange` house-account checks at src/main/java/soloMapling/Economy/exchange/Exchange.java:189, :971).

## Two flags the conf says are still the owner's call

docs/release/1.6.4.conf:28-31: `-Dsolomapling.convo.planner` (m02, needs its `t-pl-1` proof) and
`-Dslums.exchange.desk` (s44, needs its TEST proof) are "written ON below; drop the flag if the proof failed". They are
in `UNIT_EDITS`. Nothing in the repo records the go/no-go outcome of either proof, so a reader cannot tell from this
branch whether they are meant to ship on. That is a release-time decision, not a code fact.

**NEW-FINDING (doc drift, ops):** the same conf, docs/release/1.6.4.conf:33-40, lists `-Dslums.bonds=true` as one of two
ticket-181 switches that are "not in UNIT_EDITS below; append the flag there" — but `UNIT_EDITS`
(docs/release/1.6.4.conf:271) does contain `-Dslums.bonds=true`. The prose block was not updated when bonds was turned
on, so the file argues with itself about whether `@bonds` is live.

## What a switch does not cover

- A flag does not add its own guard to the data it reads: `-Dslums.mechanics.opq=true` arms rows only if
  `-Dslums.mechanics=true` is also set (src/main/java/soloMapling/mechanics/Mechanics.java:200), and a row whose `flag:` line is missing stays inert.
- Unknown `-D` names are silently ignored (unlike `config.yaml` keys) — a typo in the unit is invisible.
  **NEW-FINDING:** no code compares the live `-D` set against the flags the jar knows, so a misspelled or retired flag
  cannot be detected after a restart; the only signal is a feature that quietly stays off.
