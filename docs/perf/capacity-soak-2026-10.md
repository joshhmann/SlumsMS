# Capacity soak, October 2026 (ticket 48) - PARTIAL, 24 h soak postponed

**Status:** partial. The owner postponed the 24 h soak until the pending changes are merged; it will be rerun on the
next release candidate on the dedicated soak server `slums-soak` (see `tools/soak/README.md`). What is here: a 46-minute
smoke run on the owner's test server, and (below, once done) the 30-minute proving run of `slums-soak`.
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


