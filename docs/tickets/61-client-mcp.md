# Ticket 61: a client-driver MCP server on the owner's PC (for visual QA)

Status: **built and previously accepted**, source on Silvia under
`Z:\MSv83\SlumsMS-dev\client-mcp`. Saved receipt `logs/acceptance.json` verifies three consecutive successful
start/login/warp/screenshot/stop cycles at 5:42:58–5:44:02 PM PT on Oct 23 (00:42:58–00:44:02 UTC on Oct 24,
2026). README/NOTES record 24 safety/protocol tests passing and screenshots reviewed. This sprint verified those
saved outcomes, not new in-game cycles. Current worker did not answer status; no restart or workaround was used.
Since 2026-10-27 the worker is long-running (12 h renewed by requests, queues while the owner is active, restarts
and re-logs the client, heartbeat file). Current usage and owner-start instructions: [docs/qa/CLIENT-MCP.md](../qa/CLIENT-MCP.md).
The original specification below remains for design/reference. It accepts only TEST 8489, not feature 8491.
Read AGENTS.md first; its hard rules still apply.

## Goal

Let an agent drive a COPY of the real SlumsMS RC client against the TEST server: start it, log in with a dedicated
test account, move around, type commands, take screenshots and read the client logs. That adds "does it look right"
to auto-QA's "did it happen" (bots sunk or floating, UI glitches, client crashes or freezes).

## Hard limits (do not relax)

- Run only a client copy under `Z:\MSv83\SlumsMS-dev\client-mcp\client\` (copy it from the RC install
  `%LOCALAPPDATA%\SlumsMS-rc`, then never touch the source). Never touch `Z:\MSv83\SlumsMS-dev\test-client`, the
  owner's installs or `%APPDATA%`.
- Connect only to the TEST server: 192.168.0.164, port 8489. Refuse to start if `config.ini` points anywhere else.
- Log in only with a dedicated test account (for example `autoqa-client`). Its password lives in a file only the
  owner's user can read (`Z:\MSv83\SlumsMS-dev\client-mcp\secrets\account.txt`), typed by the tool, never returned by
  any tool, never logged, never in screenshots' metadata. The owner creates the account and the file. The test
  server's account is made the same way the auto-QA accounts are (ask the coordinator).
- Never close, kill or focus-steal other programs. `stop_client` only stops the PID it started.
- Refuse to run while the owner is active: if the last keyboard or mouse input was less than 10 minutes ago, every
  tool except `status` returns "owner is using the PC". The owner can override with a `force` flag in a local
  config file, never from a tool argument.
- The client must run in the owner's interactive desktop session (a scheduled task "run only when user is logged
  on", or a tray app started at login). Processes started from ssh have no desktop and cannot draw.
- If Parsec or another virtual display is the primary display, the client dies at start with -2147467259. Detect
  it (`status` reports the primary display) and refuse with a clear message until dgVoodoo2 is in the copy or the
  real monitor is primary.

## Tools (MCP, stdio or a localhost-only port; no other listeners)

| Tool | Does |
|---|---|
| `status` | owner idle?, client running?, PID, primary display, client build stamp, last log lines |
| `start_client` | start SlumsMS.exe from the copy; wait for the login screen (window title and image match) |
| `login` | type the test account, pick world Bera and the channel, pick the first character, wait for the field |
| `command` | type a line in chat (`!qa run we-1`, `@settings`), Enter |
| `go_to` | `!warp <map>` or change channel; wait for the map to load |
| `screenshot` | capture the game window only (PNG), save it under `client-mcp\shots\<UTC>-<label>.png`, return the path and a downscaled copy |
| `read_logs` | tail `integration.log` and `crash.log` of the copy |
| `stop_client` | close the client it started |

Every tool logs to `client-mcp\logs\<day>.log` (no secrets) and returns errors as text, never throws.

## Login automation

The v83 login screens are image-based. Use fixed click points relative to the window (800x600) and verify each step
with a small template image or pixel check. Time out with a screenshot on failure, so problems can be read later.
Keep the templates and click points in one file so they are easy to fix.

## How it is used

- An agent calls the tools during auto-QA scenes: for example, start a `!qa run we-1` from the server side, have the
  client stand at the altar and take screenshots at "i do"; or walk Henesys and capture after `!botground`.
- A later step can feed screenshots to a model for checks (sunk feet, overlapping windows), but the first version
  just saves them for review.

## Done when

- `start_client` → `login` → `go_to 100000000` → `screenshot` → `stop_client` works three times in a row while the
  owner is away, with no change to anything outside `client-mcp\`.
- A README in `client-mcp\` says how to install, start, stop and uninstall it, and where the secret file goes.
