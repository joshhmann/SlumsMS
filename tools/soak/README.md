# Soak tools and the soak server (ticket 48)

Capacity soaks run on a dedicated third server, **`slums-soak`**, a copy of live on LXC 125 that never touches live or
the owner's test server. Results: `docs/perf/capacity-soak-2026-10.md`.

## slums-soak

| What | Where |
|---|---|
| Folder | `/opt/mapleroot-dev/soak-server` (scripts, `soak.env`, `Server/` copy of live with `wz` symlinked to live's, `data/`, `VERSION.txt`) |
| Unit | `slums-soak`, transient (`systemd-run`, not enabled at boot): Nice=19, CPUWeight=20, IOWeight=20, CPUQuota=300%, MemoryMax = heap + 2 GB, `-Xmx4G` by default |
| Ports | login **8509**, channels **8685-8692** (8 channels), world 1 "Bera" like live |
| DB | schema `mapleroot_soak` on the test DB 127.0.0.1:3307, user `slums_soak` (password in `soak-server/db.env`, 0600) |
| LLM / Decide | local mock `soak-llm-mock` on 127.0.0.1:**8795** (`llm-mock.py`: chat replies after 1-3 s, Decide after 0.1-0.3 s, per-minute stats in `data/llm-mock.tsv`). Never the GPU box at full rate, never :8080 |
| Measurements | `data/gc/gc-<start>.log` (`-Xlog:gc*,safepoint`), `data/dashboard/` (dashboard exporter every 30 s), `data/collect/metrics.tsv`, `data/guard/`, `data/runs/<run>/` |

Scripts (in the folder; sources in `tools/soak/server/`):

- `refresh-soak.sh <live | Cosmic.jar | staging dir> [--keep-db]`: stops slums-soak if it runs, dumps live
  (`mysqldump --single-transaction`, read-only) into a fresh `mapleroot_soak`, rsyncs live's `Server/` (without `wz`,
  logs, reports), drops in the build, applies a staging dir's migrations (guarded, ledgered), and points `config.yaml`
  at the soak schema, the mock and `HOST 192.168.0.164`. For a release candidate:
  `refresh-soak.sh /opt/mapleroot-deployments/v<ver>/staging` (config fragments in the staging dir are reported, merge
  them by hand).
- `start-soak.sh [--heap 4G]`: starts the mock (if needed) and slums-soak with live's `-D` flags; refuses if a port is
  taken or MemAvailable < heap + 2 GB + 3 GB margin.
- `stop-soak.sh [--all]`: graceful stop; `--all` also stops the mock, load generator, collector and guard.

## Soak tools

`soak.sh` (installed next to the scripts) drives them:

| Command | What |
|---|---|
| `soak.sh install` | copy the tools, compile `LoadGen` against the soak server's jar |
| `soak.sh guard` | **live guard** `soak-guard`: 300 s baseline, then every 30 s live's CPU, CPU stall (cgroup `cpu.pressure`), GC, heap, wheel-lag lines, MemAvailable; halves the load generator, then stops it, when live degrades (3 bad samples, any live wheel-lag line, MemAvailable < 2 GB). Writes `data/guard/guard.tsv`, `guard.log`, `baseline.txt` |
| `soak.sh collect` | collector `soak-collect` on slums-soak (`data/collect/metrics.tsv`) |
| `soak.sh accounts <n>` | create `soak_01..n` / characters `soak01..n` on slums-soak and place them in busy towns |
| `soak.sh loadgen <run> <clients> <seconds> [whisperEverySec=30]` | scripted players `soak-loadgen` (Nice=19, CPUQuota=100%) |
| `soak.sh analyze <run> [bucketMin] [since] [until]` | markdown tables (graphs as tables) from all of the above |
| `soak.sh stop` / `cleanup-accounts` | stop the tools / delete the soak accounts and every row that points at them |

The load generator (`LoadGen.java`) is a headless v83 client: login, map chat (echo time), portal warps next to the
spawn and back (no movement packets: they would feed the bots' nav learning), channel changes, relogs, and whispers
to bots on its map (reply time). It follows `data/guard/loadgen.clients` so the guard can scale it down.

## Rules

- Live is only read. Never stop/restart live, its DB, its units or timers.
- The guard runs whenever slums-soak runs.
- `persistence-test` (the owner's server) is not a soak target any more.
