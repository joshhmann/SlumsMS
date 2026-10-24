# AGENTS.md: Z:\MSv83\SlumsMS-dev (the owner's PC, "Silvia")

Read this before doing anything on this PC. It applies to every agent (Codex, Claude, DeepSeek or a person). The
full project handbook is `AGENTS.md` in the server repo (github.com/joshhmann/SlumsMS, branch `test-1.5.0-rc6` or
newer; a copy sits in `client-mcp\AGENTS.md`). Its hard rules apply here too. This file adds what is specific to this
PC.

## 1. Hard rules on this PC

1. **Only write inside `Z:\MSv83\SlumsMS-dev\`.** Everything else is the owner's: other `Z:\MSv83\` folders, game
   installs, `%APPDATA%`, `%LOCALAPPDATA%` (including `SlumsMS`, `SlumsMS-rc`, `mapleroot-launcher`,
   `com.slumsms.launcher`), the desktop, Downloads. Reading is fine when the task needs it.
2. **Make a new folder for every task** (`client-<topic>`, `launcher-<topic>`, `<tool>-<topic>`). Never build in or
   overwrite someone else's folder. Never touch `test-client\` (the owner's reference client).
3. **Never close, kill or steal focus from the owner's programs.** Stop only processes you started, by PID.
4. **The game runs only from a copy you made under `SlumsMS-dev`,** pointed at the TEST server (192.168.0.164, port
   8489). Never point anything at live (port 8485, slumsms.asslorde.com) and never use the owner's accounts.
5. **No secrets in output.** Access keys, the dev key, account passwords and signing keys are never printed, logged,
   committed or put in a prompt. If a build needs a key baked in, the owner runs that step.
6. **The owner may be using this PC** (often over Parsec). Prefer times when they're away for anything that starts
   the game or takes a lot of CPU, and say what you're about to start.
7. **If something refuses or blocks you, stop and report.** Don't find a way around it.

## 2. What's here

| Folder pattern | What |
|---|---|
| `client-<version or topic>\` | a client source clone (`src\`), its build (`src\build\Release\slums.dll`), sometimes a `test-client\` copy for trying the DLL. Current line: `client-1.5.0-fixes3` |
| `client-*.bundle`, `*-build.log` | git bundles and build logs from earlier work; keep them |
| `launcher-tauri*`, `launcher-*` | launcher source clones and builds (Tauri 2.0 and the older Electron) |
| `launcher-handover-live\` | the Live Electron hand-over build (friends key baked in): don't rebuild or delete without the owner |
| `handover-trial\`, `launcher-tauri-testrun\` | launcher trial folders the owner runs |
| `client-mcp\` | the client-driver MCP server (ticket 61), being built |
| `.venv-wz\` | Python venv for WZ tools |

The owner's own RC game install (from the Tauri launcher's RC channel) is `%LOCALAPPDATA%\SlumsMS-rc`; copy from it,
never change it.

## 3. Tools on this PC

Git, CMake (`C:\Program Files\CMake\bin\cmake.exe`), Visual Studio 2022 (MSBuild, Win32), Node + npm, Rust (cargo),
Codex CLI, PowerShell. Use `cmd /c "npm ... > log 2>&1"` from PowerShell scripts: npm writes harmless warnings to
stderr, and PowerShell with `$ErrorActionPreference = 'Stop'` treats them as errors.

## 4. Building the client (slums.dll)

```
git clone -b <branch> https://github.com/joshhmann/SlumsMS-Client.git Z:\MSv83\SlumsMS-dev\client-<topic>\src
cd Z:\MSv83\SlumsMS-dev\client-<topic>\src
cmake -S . -B build -G "Visual Studio 17 2022" -A Win32
cmake --build build --config Release
ctest --test-dir build -C Release --output-on-failure
```
- The DLL is `build\Release\slums.dll`. Record its SHA-256 (`Get-FileHash`).
- Read stock bytes from the exe copy `client-1.5.0-fixes2\exe-copy\MapleStory.exe` (file offset = VA - 0x400000)
  before patching an address.
- To try a DLL: copy a client folder (from `%LOCALAPPDATA%\SlumsMS-rc` or an existing `test-client`) into your task
  folder, swap in the DLL, and keep `config.ini` on the test server. If the owner has a client open with the DLL
  locked, save yours as `slums.dll.new` and say so.
- Startup error "-2147467259 (Unspecified error)" means the main display is a virtual one (Parsec). Not a code bug:
  the owner makes the real monitor the main display.

## 5. Building the launcher

- Tauri 2.0: clone `slumsms-launcher` (branch `launcher-ui-compact` or newer) into a new folder, `npm ci`, then the
  Tauri build as in that repo's README. Signing is NOT done here: the exe goes to the dev box (192.168.0.187) to be
  signed. The signing key never comes to this PC.
- Electron hand-over: branch `launcher-handover` (must include commit `7f385b0`), `npm ci && npm run build:win`.
  A Live hand-over build needs the friends key and is run by the owner only.

## 6. When you finish

Write what you did in your task folder (`NOTES.md`): commands run, hashes of what you built, what you tested, what
you didn't. Push your branch to GitHub if it's a repo change. Leave the folder in place; the coordinator cleans up.

## 7. When the task needs the server or a release

Server code, the test server, auto-QA, releases and Discord posts are done from the dev box 192.168.0.187, in
`/root/work/SlumsMS-150` (read its `AGENTS.md`, `docs/HANDOFF.md` and `docs/release/RUNBOOK.md`). From this PC: don't
ssh to the servers yourself. Finish your client/launcher part (built files + hashes + NOTES.md here) and tell the owner
what the server side needs; an agent on 187 picks it up. A copy of the current state is `HANDOFF.md` in this folder.
