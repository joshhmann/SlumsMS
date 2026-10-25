# 42: Live monitoring and alerts

**Area:** Ops
**Depends on:** 35 (dashboard)
**Status:** closed 2026-10-25 (done in v1.4.0, branch monitoring; timers on live).
Dashboard strip: `/var/lib/slumsms-monitor/<target>/status.json` is ready for ticket 35, the strip itself is not built.

## What

Tell the owner when live needs attention, instead of finding out in game.
- **Checks every minute (a small timer on 164):** `slumsms.service` up; login port answers; ERROR/Exception rate in the
  journal; heap and process memory against `-Xmx`; DB container healthy; disk free; bot count vs the director's target;
  LLM (`:8095/health`) and Decide (`:8900/health`) reachable; nightly backup and nav bake succeeded.
- **Alerts:** a Discord webhook (or another channel the owner picks), rate-limited, with a "recovered" message. No
  secrets in messages.
- **Dashboard:** the same checks as a status strip on ticket 35's page.

## Done when

Stopping the test copy of live, filling its disk or killing the LLM endpoint each produce one clear alert within two
minutes and a recovery message after.
