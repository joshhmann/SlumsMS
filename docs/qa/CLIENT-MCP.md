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

## Owner starts the worker

The owner runs this in an **administrator PowerShell in their logged-on desktop**, only when ready to leave the
PC idle. The RC executable requires elevation; an SSH launch has no usable interactive desktop. The agent must
not trigger UAC, change the idle/force guard or steal focus from other programs.

```powershell
Set-Location Z:\MSv83\SlumsMS-dev\client-mcp
# Only when the previous worker is stopped: it replays its inbox from the start.
cmd /c "type nul > logs\worker-inbox.jsonl"
Start-Process pythonw.exe -ArgumentList '-B', 'Z:\MSv83\SlumsMS-dev\client-mcp\scripts\focus-controller.py' -WindowStyle Normal
python -u -B .\scripts\elevated-worker.py
```

After starting it, leave the **SlumsMS client-driver controller** window foreground, then allow ten minutes with
no mouse/keyboard input. The worker expires after one hour. These are owner instructions; they were not executed
by this sprint. The existing secret account file is read only by the driver; never print, copy or pass its values.

## Agent calls from 187

```bash
CLIENT_MCP_WAIT=20 tools/ops/client-mcp status
tools/ops/client-mcp start_client
tools/ops/client-mcp login '{"channel":1}'
tools/ops/client-mcp go_to '{"map_id":100000000}'
tools/ops/client-mcp screenshot '{"label":"henesys"}'
tools/ops/client-mcp stop_client
```

Never run a new client or call input tools without first telling the owner. On an idle/focus/display/endpoint
refusal, stop and report it; do not try another path. `stop_client` closes only the copy owned by the driver.
Copy screenshots for review only from the task's `shots/` folder. Never use the owner's accounts, installed
client, test-client reference folder, or personal applications. A game screenshot or a native UI test does not
prove another client version is visually correct.

The new settings panel was built and tested in a separate task folder; it was not installed into the driver copy.
Feature-server visual QA needs a deliberate, reviewed test-client setup that preserves this driver's 8489 guard.
