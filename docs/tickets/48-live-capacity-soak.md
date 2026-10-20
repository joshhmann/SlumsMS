# 48: Capacity soak with real players and bots

**Area:** Performance
**Depends on:** -
**Status:** open

## What

- Soak a copy of live at the full daily curve (peak 1,200 roster bots plus about 660 service bots) for 24 h with
  scripted "player" load (logins, warps, chat to bots, trades) to see heap, GC pauses, tick times
  (`BotPerformanceMonitor`), DB pool waits (`BotDbGate`) and LLM queueing.
- Find the safe peak for 4 GB and for 6 GB heap; set `BOT_ROSTER_PEAK_ONLINE` from data.
- Check friends' experience: login time, channel change time and map lag in busy towns with many bots on screen.

## Done when

The docs state a measured safe peak and heap, and a 24 h soak shows no growth in heap or tick time.
