# 48: Capacity soak with real players and bots

**Area:** Performance
**Depends on:** -
**Status:** postponed until the next release candidate (owner, 2026-10-20). The soak tools shipped in v1.4.0 (via
`test-rc2`); the last smoke-run commit (0d72832ae) is only on branch `capacity-soak`. Partial results and the
method: `docs/perf/capacity-soak-2026-10.md`; tools and the dedicated soak server `slums-soak`: `tools/soak/README.md`.

## What

- Soak a copy of live at the full daily curve (peak 1,200 roster bots plus about 660 service bots) for 24 h with
  scripted "player" load (logins, warps, chat to bots, trades) to see heap, GC pauses, tick times
  (`BotPerformanceMonitor`), DB pool waits (`BotDbGate`) and LLM queueing.
- Find the safe peak for 4 GB and for 6 GB heap; set `BOT_ROSTER_PEAK_ONLINE` from data.
- Check friends' experience: login time, channel change time and map lag in busy towns with many bots on screen.

## Done when

The docs state a measured safe peak and heap, and a 24 h soak shows no growth in heap or tick time.
