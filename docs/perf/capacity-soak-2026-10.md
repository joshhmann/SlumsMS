# Capacity soak, October 2026 (ticket 48) - PARTIAL, 24 h soak postponed

**Status:** partial. The owner postponed the 24 h soak until the pending changes are merged; it will be rerun on the
next release candidate on the dedicated soak server `slums-soak` (see `tools/soak/README.md`). What is here: a 46-minute
smoke run on the owner's test server, the 30-minute proving run of `slums-soak`, and the continuous baseline soak
that has run on `slums-soak` since 2026-10-20 19:22 UTC.
Not yet measured: the safe peak for 4 GB / 6 GB heap, a recommended `BOT_ROSTER_PEAK_ONLINE`, and 24 h growth.

## How it is measured

- **Live guard** (`tools/soak/guard.sh`, unit `soak-guard`): every 30 s, read-only, live's CPU, live's CPU stall
  (its cgroup `cpu.pressure`), live GC ms/min and heap (`jstat`, no attach), WARN/ERROR and BotTickService
  "wheel lag" lines in live's journal, load, MemAvailable. A 300 s baseline first; then it halves the load
  generator's client count (and finally stops it) when live degrades for 3 samples in a row, live logs a wheel-lag
  line, or MemAvailable < 2 GB. It never touches live or the test servers.
- **Collector** (`collect.py`): the dashboard exporter's `live.json` (bots online/target, players, heap, DB pool,
  `BotDbGate`, `BotPerformanceMonitor` phases), `jstat -gc`, RSS/CPU, and the target's journal (LLM fallbacks by
  reason, wheel-lag lines, BotDbGate overrun warnings, errors).
- **GC log**: `-Xlog:gc*,safepoint` on the target.
- **Scripted players** (`LoadGen.java`, a headless v83 client built on the ticket 43 one): each logs in (timed to
  `SET_FIELD`, then until the map's packet burst ends = "settled"), and at random says a line in map chat (echo time:
  map-lag proxy), walks through a portal next to it and back (timed; it never sends movement, which would teach the
  bots' nav learning bogus edges), changes channel (timed), relogs (timed). All clients together whisper a bot at most
  once every 20-30 s and time the whispered reply (the real LLM path), so the GPU box sees at most one chat call plus
  one notes call per window. 75 % of logins go to channel 1, where the bots are.

Caveats: `tickMsPerS` / the macro-tick figure from `BotPerformanceMonitor` is wall time, and several bot types still
block (`Thread.sleep`) inside their macro tick on virtual threads, so it reads ~250 ms per call and tens of seconds per
second without costing CPU. The CPU-relevant tick figures are the movement tick and process CPU; the tick wheel's own
lag shows up as `[BotTickService] wheel lag` lines (none so far, on live or the soak target).

## Smoke run 1: the owner's test server (2026-10-20 16:59-17:45 UTC)

Target `slums-persistence-test` (jar = the batch-overnight build: chat-natural + movement-flaws + leveling-path, -Xmx4G,
its own unit limits CPUQuota=300% Nice=19 MemoryMax=6G), restarted once (42.7 s down) to add GC logging and the
dashboard exporter. Its config: `BOT_ROSTER_PEAK_ONLINE: 1200`, curve `flat`, `BOT_POPULATION_SCALE: 0.5`, so
**1,535 bots** online (1,200 roster + 335 service bots; live has about 660 service bots), real Qwen LLM.
20 scripted players (accounts `soak_01..20`, deleted afterwards with their characters, items, skills, quests, keymaps,
storages and the 236 bot-memory notes about them), whisper every 20 s, plus the owner.

What it showed:

- **Heap**: 1.7-2.3 GB used of 4 GB; heap after GC rose 1.24 to 1.47 GB over the 45 minutes, which is the first hour
  after a restart (caches filling), so no growth verdict. No full GC.
- **GC pauses**: 362 young/mixed/remark pauses, p99 18-20 ms, max 26.5 ms, about 60 ms of pause per minute.
- **CPU**: 30-40 % of one core for 1,535 bots and 21 players. The boot (bots spawning) took ~2.5 cores for about a
  minute.
- **DB**: pool waiting 0 in every sample, `BotDbGate` 6/6 permits free, 0 overruns.
- **Ticks**: movement tick 80-93 ms/s (i.e. ~9 % of a core), no wheel-lag lines.
- **LLM** (real Qwen at 1 whisper / 20 s): reply p50 3.5 s, p95 5.2 s, 129/134 answered; 19 fallbacks (13 empty after
  cleaning, 5 voice check, 1 rate limited), none from queueing (`no free slot` / `deadline passed while queued`).
- **Players**: login p50 364 ms / p95 427 ms to the map, settled (all players, NPCs, drops received) p50 0.8 s /
  p95 1.8 s; Henesys with 38-74 players on screen: settled p50 0.9 s / p95 1.8 s; channel change p50 46 ms / p95
  68 ms (settled p95 1.1 s); portal warp settled p95 1.2 s; map chat echo p50 0.2 ms, max 9 ms. A client standing in
  Henesys receives ~68 packets/s (peaks 216/s, 2-7 KB/s), nearly all bot movement. 5 of 55 logins failed once with a
  closed connection and succeeded on the retry 15 s later (not explained yet; a real client would show "unable to
  connect").
- **Live** during the run: CPU median ~10 % (baseline 8.9 %), GC unchanged, no wheel-lag lines, MemAvailable
  >= 12.8 GB, no guard action. The guard's first CPU-wait measure (summed per-thread schedstat) showed isolated
  spikes with the box idle (PSI ~3 %), a counting artifact; it now reads live's cgroup `cpu.pressure`.

Tables (15-minute buckets; "old gen" is jstat's OU, "players on screen" counts bots and players):

#### Soak target over time (15 min buckets, from 2026-10-20 16:59:46 UTC)

| UTC | bots online (min-max) | players | heap used MB avg / max | old gen MB min / max | GC ms/min avg | CPU % avg (of one core) | RSS MB max | DB pool waiting max | BotDbGate free min / overruns | macro tick avg ms | movement tick ms/s | wheel-lag lines | LLM fallbacks | errors |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 10-20 16:59 | 1535-1535 | 20 | 1737 / 2133 | 1216 / 1361 | 104 | 37 | 2662 | 0 | 6 / 0 | 246 | 80.9 | 0 | 8 | 0 |
| 10-20 17:14 | 1529-1535 | 21 | 1782 / 2202 | 1363 / 1418 | 56 | 30 | 2746 | 0 | 6 / 0 | 243 | 79.9 | 0 | 5 | 0 |
| 10-20 17:29 | 1528-1532 | 21 | 1891 / 2307 | 1420 / 1456 | 63 | 29 | 2806 | 0 | 6 / 0 | 262 | 79.8 | 0 | 6 | 2 |
| 10-20 17:44 | 1531-1531 | 20 | 1682 / 1812 | 1456 / 1457 | 59 | 33 | 2812 | 0 | 6 / 0 | 274 | 92.6 | 0 | 0 | 0 |

LLM fallbacks by reason over the window: `empty after cleaning` 13, `voice check: assistant phrasing` 5, `rate limited` 1

Trend after the first bucket (least squares): old gen minimum 186.0 MB/h, movement tick 25.49 ms/s per h, macro tick avg 61.9 ms per h (3 buckets).

#### GC pauses (362 pauses, G1, from the -Xlog:gc* log)

| pause kind | count | p50 ms | p99 ms | max ms |
|---|---|---|---|---|
| Pause Young (Mixed) (G1 Evacuation Pause) | 70 | 11.4 | 19.6 | 26.5 |
| Pause Remark | 69 | 9.7 | 16.5 | 17.8 |
| Pause Cleanup | 69 | 0.3 | 0.4 | 0.4 |
| Pause Young (Prepare Mixed) (G1 Evacuation Pause) | 69 | 11.7 | 20.9 | 21.8 |
| Pause Young (Concurrent Start) (G1 Evacuation Pause) | 66 | 9.9 | 17.3 | 18.3 |
| Pause Young (Normal) (G1 Evacuation Pause) | 16 | 11.6 | 13.3 | 13.4 |
| Pause Young (Concurrent Start) (G1 Humongous Allocation) | 3 | 13.9 | 18.3 | 18.4 |

| UTC | pauses | p50 ms | p99 ms | max ms | heap after GC MB min / max | pause ms per min |
|---|---|---|---|---|---|---|
| 10-20 16:59 | 154 | 11.3 | 18.4 | 26.5 | 1244 / 1977 | 104 |
| 10-20 17:14 | 101 | 9.7 | 17.4 | 21.8 | 1390 / 1976 | 57 |
| 10-20 17:29 | 101 | 10.0 | 19.2 | 20.6 | 1434 / 2155 | 61 |
| 10-20 17:44 | 6 | 9.1 | 14.4 | 14.6 | 1469 / 1582 | 3 |

Heap after GC, bucket minimum, trend after the first bucket: 158.0 MB/h over 3 buckets.

#### Player experience (load generator, run `smoke`)

| what | n | ok % | p50 ms | p95 ms | max ms | settled p50 / p95 ms (map fully loaded) |
|---|---|---|---|---|---|---|
| login | 50 | 100 | 364 | 427 | 1272 | 788 / 1793 |
| cc | 103 | 100 | 46 | 68 | 102 | 452 / 1124 |
| warp | 778 | 96 | 0 | 2 | 14 | 402 / 1201 |
| chat_echo | 1267 | 100 | 0.2 | 0.3 | 9.1 |  /  |
| whisper_reply | 134 | 96 | 3527 | 5247 | 6752 |  /  |
| error | 5 | 0 |  |  |  |  /  |
| warp_skip | 121 | 0 |  |  |  |  /  |

Map entry by map (players on screen at entry and time until the map's packet burst ends; >= 400 ms by construction, the client waits for 400 ms of quiet):

| map | entries | players on screen avg / max | settled p50 / p95 ms |
|---|---|---|---|
| 100000000 | 64 | 38 / 74 | 942 / 1830 |
| 100000100 | 67 | 29 / 63 | 771 / 2502 |
| 910000000 | 58 | 26 / 48 | 1000 / 1510 |
| 910000011 | 2 | 26 / 27 | 647 / 868 |
| 910000004 | 2 | 21 / 22 | 667 / 906 |
| 910000006 | 2 | 20 / 20 | 565 / 712 |
| 910000008 | 1 | 19 / 19 | 401 / 401 |
| 910000022 | 1 | 16 / 16 | 401 / 401 |
| 910000010 | 5 | 15 / 20 | 401 / 403 |
| 910000015 | 1 | 15 / 15 | 401 / 401 |
| 910000014 | 1 | 12 / 12 | 401 / 401 |
| 910000018 | 2 | 11 / 22 | 400 / 401 |

| UTC | logins p50 / p95 ms | cc p50 / p95 ms | warp settled p95 ms | chat echo p50 / p95 / max ms | whisper reply p50 / p95 ms (ok/n) | errors |
|---|---|---|---|---|---|---|
| 10-20 16:59 | 395 / 480 | 54 / 69 | 1355 | 0.2 / 0.3 / 9.1 | 3728 / 5241 (43/45) | 4 |
| 10-20 17:14 | 347 / 365 | 31 / 60 | 1255 | 0.2 / 0.3 / 0.4 | 3392 / 5559 (42/45) | 1 |
| 10-20 17:29 | 362 / 368 | 42 / 55 | 1116 | 0.2 / 0.3 / 0.4 | 3523 / 5120 (42/43) | 0 |
| 10-20 17:44 |  /  |  /  | 401 | 0.2 / 0.2 / 0.2 | 5247 / 5247 (1/1) | 0 |

Traffic a client receives while standing in a map (per-minute samples):

| map | client-minutes | players on screen avg | packets/s avg / max | KB/s avg / max | movement packets/s avg |
|---|---|---|---|---|---|
| 220000001 | 2 | 2 | 116.4 / 232.5 | 3.4 / 6.8 | 114.8 |
| 220000000 | 79 | 8 | 75.2 / 220.6 | 2.2 / 6.5 | 72.9 |
| 100000000 | 79 | 42 | 68.0 / 216.2 | 2.1 / 6.7 | 61.5 |
| 910000011 | 1 | 24 | 62.4 / 62.4 | 2.0 / 2.0 | 58.2 |
| 910000020 | 1 | 23 | 56.0 / 56.0 | 1.8 / 1.8 | 51.5 |
| 100000103 | 2 | 0 | 46.5 / 64.2 | 1.4 / 2.0 | 42.0 |
| 100000104 | 4 | 0 | 41.9 / 59.1 | 1.4 / 2.0 | 38.2 |
| 100050000 | 8 | 1 | 39.2 / 94.7 | 1.2 / 2.8 | 28.5 |
| 910000000 | 76 | 26 | 37.1 / 112.2 | 1.2 / 3.5 | 34.3 |
| 101040000 | 2 | 1 | 30.7 / 54.1 | 0.9 / 1.6 | 22.5 |

#### Live (slumsms.service) during the soak, from the guard

Baseline (before the soak's load generator; the test server already ran its bots): `samples=9 rq_median=28.8 rq_p95=36.6 cpu_median=8.9 gc_ms_min_median=0 gc_ms_min_p95=15`

| UTC | live CPU % median | live run-queue wait ms/s median / p95 / max | live GC ms/min median / max | live heap MB max | MemAvailable MB min | load1 max | live wheel-lag lines | guard actions |
|---|---|---|---|---|---|---|---|---|
| 10-20 16:59 | 10.8 | 46.9 / 78.8 / 141.0 | 10 / 454 | 2076 | 13011 | 11.81 | 0 | - |
| 10-20 17:14 | 9.7 | 39.4 / 137.0 / 388.3 | 0 / 80 | 2077 | 12916 | 6.43 | 0 | - |
| 10-20 17:29 | 9.5 | 40.0 / 1303.5 / 2282.9 | 15 / 48 | 2104 | 12825 | 8.43 | 0 | - |
| 10-20 17:44 | 8.8 | 50.7 / 50.7 / 50.7 | 0 / 0 | 1774 | 12821 | 6.20 | 0 | - |



## Smoke run 2: slums-soak, the dedicated soak server (2026-10-20 17:57-18:28 UTC)

`slums-soak` = fresh copy of live (DB dumped with `--single-transaction` into `mapleroot_soak`, Server folder, jar
v1.3.1 = live's, sha 2234665001f8), login 8509, -Xmx4G, Nice=19 CPUWeight=20 CPUQuota=300% MemoryMax=6G, LLM and Decide
on the local mock (1-3 s per chat reply). For the smoke only `BOT_ROSTER_CURVE: "flat"` (soak override) held the
peak: **1,546 bots** (1,200 roster + ~346 service bots with live's config). 20 scripted players, a whisper every 5 s
(the mock can take it), guard on.

- **Heap** 1.6-2.2 GB used of 4 GB, heap after GC 1.13 to 1.40 GB in the first 30 minutes (warm-up), no full GC.
  Without players it went on to 1.45 GB by 19:20 (about +50 MB/h after the first hour; the 24 h run decides).
- **GC pauses**: 496 in 30 min, p50 10 ms, p99 21-34 ms, max 46 ms (boot), ~115-200 ms of pause per minute while the
  20 players were on.
- **CPU** 35-54 % of one core with players, ~18 % without; RSS 2.7 GB.
- **DB**: pool waiting 0, BotDbGate overruns 0. **Ticks**: movement tick 70-87 ms/s, no wheel-lag lines.
- **LLM queueing** (mock, 727 chat calls in 31 min, ~23/min, server cap `BOT_LLM_MAX_CONCURRENT: 2`): whisper reply
  p50 2.5 s / p95 3.4-3.9 s (mock latency 1-3 s, so queueing adds well under a second at this rate), 335/351 answered;
  8 fallbacks, all "rate limited" (the per-player interval), none from a full queue.
- **Players**: login p50 360 ms / p95 392 ms (settled p95 1.9 s), channel change p50 47 ms / p95 75 ms, chat echo p50
  0.2 ms (max 16 ms), Henesys with up to 76 on screen settled p95 1.7 s. Portal warps: SET_FIELD p95 5 ms, but settled
  p95 7.5 s: a few warps into the busiest maps took 10-15 s for the packet burst to end (to look at on the RC run).
  6 of 46 logins were refused at the TCP connect (the server closed the socket before the handshake) and worked on the
  retry; same as on the test server, likely the login server's per-IP connection limit (all scripted players share
  127.0.0.1), not seen by real players on separate IPs.
- **Live**: CPU median 12 % (baseline 11 %), CPU stall 8-11 ms/s median (baseline 6.6), p95 45 ms/s only while
  slums-soak booted, GC unchanged, no wheel-lag lines, MemAvailable >= 10.2 GB, no guard action.

#### Soak target over time (10 min buckets, from 2026-10-20 17:57:54 UTC)

| UTC | bots online (min-max) | players | heap used MB avg / max | old gen MB min / max | GC ms/min avg | CPU % avg (of one core) | RSS MB max | DB pool waiting max | BotDbGate free min / overruns | macro tick avg ms | movement tick ms/s | wheel-lag lines | LLM fallbacks | errors |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 10-20 17:57 | 1544-1546 | 20 | 1591 / 1957 | 1097 / 1274 | 238 | 54 | 2477 | 0 | 6 / 0 | 268 | 72.4 | 0 | 2 | 0 |
| 10-20 18:07 | 1546-1546 | 20 | 1620 / 2120 | 1270 / 1347 | 169 | 43 | 2610 | 0 | 6 / 0 | 275 | 83.7 | 0 | 3 | 0 |
| 10-20 18:17 | 1546-1546 | 20 | 1834 / 2189 | 1347 / 1391 | 115 | 35 | 2689 | 0 | 6 / 0 | 241 | 86.9 | 0 | 3 | 0 |
| 10-20 18:27 | 1546-1546 | 20 | 1669 / 1669 | 1391 / 1391 | 40 | 26 | 2689 | 0 | 6 / 0 | 275 | 70.2 | 0 | 0 | 0 |

LLM fallbacks by reason over the window: `rate limited` 8

Trend after the first bucket (least squares): old gen minimum 363.0 MB/h, movement tick -40.59 ms/s per h, macro tick avg -2.3 ms per h (3 buckets).

#### GC pauses (496 pauses, G1, from the -Xlog:gc* log)

| pause kind | count | p50 ms | p99 ms | max ms |
|---|---|---|---|---|
| Pause Young (Mixed) (G1 Evacuation Pause) | 85 | 11.6 | 32.8 | 36.8 |
| Pause Young (Concurrent Start) (G1 Evacuation Pause) | 85 | 10.1 | 33.1 | 44.7 |
| Pause Remark | 85 | 9.7 | 31.3 | 33.2 |
| Pause Cleanup | 85 | 0.3 | 0.6 | 1.4 |
| Pause Young (Prepare Mixed) (G1 Evacuation Pause) | 85 | 10.8 | 30.6 | 45.8 |
| Pause Young (Normal) (G1 Evacuation Pause) | 71 | 9.9 | 22.7 | 23.6 |

| UTC | pauses | p50 ms | p99 ms | max ms | heap after GC MB min / max | pause ms per min |
|---|---|---|---|---|---|---|
| 10-20 17:57 | 191 | 9.2 | 34.4 | 45.8 | 1129 / 2020 | 200 |
| 10-20 18:07 | 183 | 9.6 | 21.2 | 25.0 | 1282 / 2034 | 164 |
| 10-20 18:17 | 121 | 9.7 | 30.9 | 36.8 | 1361 / 2163 | 115 |
| 10-20 18:27 | 1 | 10.0 | 10.0 | 10.0 | 1401 / 1401 | 1 |

Heap after GC, bucket minimum, trend after the first bucket: 357.0 MB/h over 3 buckets.

#### Player experience (load generator, run `smoke`)

| what | n | ok % | p50 ms | p95 ms | max ms | settled p50 / p95 ms (map fully loaded) |
|---|---|---|---|---|---|---|
| login | 40 | 100 | 360 | 392 | 1406 | 792 / 1887 |
| cc | 66 | 100 | 47 | 75 | 142 | 456 / 1217 |
| warp | 525 | 99 | 0 | 5 | 30 | 402 / 7539 |
| chat_echo | 822 | 100 | 0.2 | 0.4 | 16.5 |  /  |
| whisper_reply | 351 | 95 | 2520 | 3442 | 6426 |  /  |
| error | 6 | 0 |  |  |  |  /  |
| warp_skip | 72 | 0 |  |  |  |  /  |

Map entry by map (players on screen at entry and time until the map's packet burst ends; >= 400 ms by construction, the client waits for 400 ms of quiet):

| map | entries | players on screen avg / max | settled p50 / p95 ms |
|---|---|---|---|
| 100000000 | 46 | 29 / 76 | 446 / 1725 |
| 910000000 | 38 | 28 / 48 | 1036 / 1433 |
| 100000200 | 32 | 26 / 59 | 406 / 1645 |
| 100000100 | 40 | 25 / 59 | 441 / 2961 |
| 910000009 | 4 | 19 / 19 | 451 / 689 |
| 910000014 | 1 | 19 / 19 | 401 / 401 |
| 910000021 | 1 | 16 / 16 | 401 / 401 |
| 910000016 | 1 | 13 / 13 | 401 / 401 |
| 910000007 | 4 | 12 / 16 | 403 / 741 |
| 910000004 | 3 | 11 / 17 | 402 / 403 |
| 910000019 | 3 | 11 / 16 | 402 / 413 |
| 910000005 | 2 | 9 / 18 | 402 / 403 |

| UTC | logins p50 / p95 ms | cc p50 / p95 ms | warp settled p95 ms | chat echo p50 / p95 / max ms | whisper reply p50 / p95 ms (ok/n) | errors |
|---|---|---|---|---|---|---|
| 10-20 17:57 | 376 / 476 | 46 / 71 | 15018 | 0.3 / 0.5 / 16.5 | 2521 / 3282 (111/115) | 5 |
| 10-20 18:07 | 323 / 346 | 39 / 64 | 1233 | 0.2 / 0.4 / 6.4 | 2521 / 3862 (113/118) | 1 |
| 10-20 18:17 | 337 / 360 | 49 / 77 | 10723 | 0.2 / 0.3 / 2.2 | 2414 / 3485 (111/117) | 0 |
| 10-20 18:27 |  /  | 47 / 47 |  |  /  /  |  /  (0/1) | 0 |

Traffic a client receives while standing in a map (per-minute samples):

| map | client-minutes | players on screen avg | packets/s avg / max | KB/s avg / max | movement packets/s avg |
|---|---|---|---|---|---|
| 101040000 | 2 | 5 | 92.7 / 108.1 | 2.7 / 3.2 | 79.5 |
| 105040301 | 4 | 8 | 78.1 / 87.1 | 2.4 / 2.6 | 62.4 |
| 910000004 | 1 | 16 | 75.7 / 75.7 | 2.4 / 2.4 | 70.7 |
| 100050000 | 7 | 5 | 63.3 / 118.3 | 1.9 / 3.5 | 40.4 |
| 910000007 | 1 | 16 | 57.2 / 57.2 | 1.9 / 1.9 | 52.9 |
| 101000000 | 49 | 6 | 53.7 / 146.3 | 1.6 / 4.4 | 43.6 |
| 105050000 | 2 | 0 | 51.4 / 82.5 | 1.5 / 2.4 | 46.7 |
| 100000000 | 49 | 26 | 44.3 / 193.7 | 1.4 / 6.1 | 40.9 |
| 102000000 | 51 | 4 | 43.4 / 215.1 | 1.3 / 6.3 | 36.0 |
| 100000200 | 6 | 32 | 42.5 / 105.6 | 1.5 / 3.5 | 38.4 |

#### Live (slumsms.service) during the soak, from the guard

Baseline (before the soak's load generator; the test server already ran its bots): `samples=9 rq_median=6.6 rq_p95=7.5 cpu_median=11.1 gc_ms_min_median=15 gc_ms_min_p95=34`

| UTC | live CPU % median | live CPU stall ms/s median / p95 / max | live GC ms/min median / max | live heap MB max | MemAvailable MB min | load1 max | live wheel-lag lines | guard actions |
|---|---|---|---|---|---|---|---|---|
| 10-20 17:57 | 12.1 | 7.9 / 44.7 / 52.7 | 17 / 38 | 2189 | 10247 | 12.44 | 0 | - |
| 10-20 18:07 | 12.4 | 10.8 / 12.6 / 15.3 | 19 / 139 | 2201 | 10455 | 23.93 | 0 | - |
| 10-20 18:17 | 11.7 | 8.9 / 10.8 / 11.3 | 11 / 226 | 2197 | 10213 | 13.99 | 0 | - |
| 10-20 18:27 | 16.8 | 7.4 / 7.4 / 7.4 | 51 / 51 | 1680 | 10219 | 8.78 | 0 | - |


## The continuous baseline soak (running)

Since 2026-10-20 19:22 UTC `slums-soak` runs v1.3.1 with live's own daily curve (peak 1,200 roster bots at 20:00
America/Los_Angeles = 03:00 UTC, trough 0.15 at 06:00 LA = 13:00 UTC), 10 scripted players with a whisper every 30 s,
the collector and the live guard. It stays up as the v1.3.1 baseline; the release candidate goes in with
`refresh-soak.sh <staging dir>` and is compared against it. The 24 h figures (heap/GC/tick growth, safe peak) are
added here at 2026-10-21 19:22 UTC.
