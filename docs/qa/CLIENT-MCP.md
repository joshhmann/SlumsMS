# Client visual QA through the Windows worker

Read AGENTS.md, docs/ops/silvia-AGENTS.md and docs/qa/QA.md first. This driver operates only its owned
`Z:\MSv83\SlumsMS-dev\client-mcp\client` copy and the dedicated TEST account. It accepts only
192.168.0.164:8489. The isolated feature server is on **8491**, so this driver currently cannot be pointed at it.
Do not loosen the endpoint guard as a workaround or replace shared 1.5.1 TEST just to run feature QA.

## Verified implementation

Ticket 61 is built, not merely a specification. The Windows README and saved `logs/acceptance.json` record three
consecutive successful `start_client -> login -> go_to -> screenshot -> stop_client` cycles, each step `ok=true`,
from **5:42:58–5:44:02 PM PT on Oct 23 (00:42:58–00:44:02 UTC on Oct 24, 2026)**. The README says the three
game-only screenshots were reviewed and 24 safety/protocol tests passed. This sprint verified the saved receipt;
it did not rerun those client cycles or claim a new visual result.

Current sprint status: `tools/ops/client-mcp status` received no worker answer. The expired/stopped worker was
not started or bypassed. Source, README and receipt remain in `Z:\MSv83\SlumsMS-dev\client-mcp`.

## Owner starts the worker (long-running since 2026-10-27)

The owner double-clicks **`Z:\MSv83\SlumsMS-dev\client-mcp\Start-QA.cmd`** in their logged-on desktop, accepts the
one admin prompt, and lets the "skip the idle wait?" question time out (N after 20 s). That starts the focus
controller and the worker; leave the **SlumsMS client-driver controller** window open and the black Start-QA
window open (closing it stops the worker). The agent must not trigger UAC, change the idle/force guard or steal
focus from other programs.

What changed (owner: "I hate having to always restart it"):

- The worker lives **12 h after the last request** (each request renews it), not one hour. `lifetime_hours: 0` in
  `client-mcp\worker-config.json` makes it run until stopped.
- The owner guard stays, but **waits instead of failing**: while the owner used the PC in the last `idle_minutes`
  (default 10), requests are queued and run by themselves once the PC has been idle that long. `status` and
  `heartbeat` always answer at once.
- **Self-healing:** after a `login`, the worker restarts the client and logs back in (same channel, last `go_to`
  map) when it exits, hangs, loses its window or drops to the login screen; at most 3 times an hour, only while
  the owner is idle, credentials read by the driver from its secret file as before. If the worker itself crashes,
  Start-QA starts it again.
- **Heartbeat:** `client-mcp\logs\worker-heartbeat.json`; `tools/ops/client-mcp heartbeat` (or `status`) prints
  ALIVE/STALE, uptime, expiry, owner idle, queue, client PIDs and the last recovery.

Settings (`worker-config.json`, refused if unknown/out of range): `lifetime_hours` 12, `idle_minutes` 10,
`queue_wait_minutes` 120, `auto_recover` true, `max_recoveries_per_hour` 3, `heartbeat_seconds` 15,
`health_seconds` 20. A copy of the changed Windows files is in `tools/ops/client-mcp-win/` (the deployed copy on
Silvia is authoritative; originals in `client-mcp\backup-20261027-longrun\`).

## Agent calls from 187

```bash
tools/ops/client-mcp heartbeat         # alive? uptime, expiry, queue, last recovery (no worker call)
CLIENT_MCP_WAIT=20 tools/ops/client-mcp status
tools/ops/client-mcp start_client
tools/ops/client-mcp login '{"channel":1}'
tools/ops/client-mcp go_to '{"map_id":100000000}'
tools/ops/client-mcp screenshot '{"label":"henesys"}'
tools/ops/client-mcp stop_client
```

Never run a new client or call input tools without first telling the owner. A request made while the owner is at
the PC prints `queued` and waits (up to `CLIENT_MCP_QUEUE_WAIT`, default 3600 s; exit 4 if it never ran). On a
focus/display/endpoint refusal, stop and report it; do not try another path. `stop_client` closes only the copy owned by the driver.
Copy screenshots for review only from the task's `shots/` folder. Never use the owner's accounts, installed
client, test-client reference folder, or personal applications. A game screenshot or a native UI test does not
prove another client version is visually correct.

The new settings panel was built and tested in a separate task folder; it was not installed into the driver copy.
Feature-server visual QA needs a deliberate, reviewed test-client setup that preserves this driver's 8489 guard.

## Worker startup authorized by owner after sprint handback

The owner explicitly authorized starting the QA worker from 187. Started it in the logged-on Windows session
using the new manual-only task `SlumsMS-Sprint-QA-Worker` (interactive user, elevated pythonw, no UAC prompt,
no recurring trigger). Operational notes: `Z:\MSv83\SlumsMS-dev\client-mcp-sprint-worker\NOTES.md`.
The queue was cleared only after verifying no worker was running. Status call succeeded: interactive desktop,
owner idle ~3,588 s, real XG270/NVIDIA primary display, `local_force=false`, no owned game PIDs.
No controller or game was launched and no foreground window was changed. That worker expired after one hour;
the manual-only task definition remains and can be removed after expiry.

This supersedes the earlier unavailable-worker observation, not the endpoint restriction. The current driver
still controls its original 8489 client with DLL `ffffe1437c91df1fff9aaa8c74bfaf4bbd119085c0cab2435dcdcabecb3d93fa`.
It cannot verify fishing/settings on feature 8491. No endpoint guard, DLL or client setup changed. Preparing a
feature-client driver needs a deliberate setup following the PC rules; do not redirect this existing driver or
replace shared 1.5.1 TEST. No new game/visual result is claimed.
