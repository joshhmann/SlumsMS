# Starter prompts for Codex / DeepSeek

Pick the one that fits, paste it as the first message, and fill in the `<...>` part. Every prompt makes the agent
read the rules first. Live, Discord and anything outside the test server still need the owner's go.

## Where to start the agent

| Work | Machine | Folder |
|---|---|---|
| Server code, bots, chat, QA, test deploys, releases, website, Discord, launcher publishing | dev box 192.168.0.187 | `/root/work/SlumsMS-150` |
| Client DLL (slums.dll), client visual QA | 187; builds on Silvia over ssh | `/root/work/client/src`, `Z:\MSv83\SlumsMS-dev` |

## 1. Server fix or feature (most work)

Start in `/root/work/SlumsMS-150` on 187.

```
You're working on SlumsMS, a private MapleStory v83 server with bots. Read AGENTS.md, docs/HANDOFF.md and
docs/release/RUNBOOK.md first and follow their rules exactly.

Task: <describe it, e.g. "1.5.1 backlog: a bot you're talking to walks away mid-conversation">.

Work on a new branch 151-<topic> from origin/release-1.5.0 in its own git worktree. Find the cause before changing
anything, add tests, run mvn -q -o test. Deploy to the TEST server with tools/ops/deploy-test and check it with
tools/ops/test-status and tools/ops/autoqa-run (see docs/qa/QA.md). Never touch live (port 8485, the slumsms
service, the Live launcher feed) or post to Discord. Commit and push your branch, merge into release-1.5.1 when it's
green, update docs/HANDOFF.md, and finish with a short report: cause, fix, tests, what you checked on test, and what
the owner should look at in game.
```

## 2. Triage auto-QA or the owner's QA notes

Start in `/root/work/SlumsMS-150` on 187.

```
Read AGENTS.md, docs/HANDOFF.md and docs/qa/QA.md, then follow .claude/skills/qa-triage/SKILL.md.
Look at the latest auto-QA results (tools/ops/test-status and the test server's data/autoqa/latest.json)
<and the owner's notes: paste them here>. For each failure, read its log before deciding, and sort it into: staging
problem, real bug, works as designed, or needs the owner. Fix the real bugs and staging problems on 151-<topic>
branches with tests, deploy to TEST, rerun those checks with tools/ops/autoqa-run <ids>, and report a table: check
id, verdict, cause, fix, rerun result. Don't touch live.
```

## 3. Prepare a release (1.5.1 and later)

Start in `/root/work/SlumsMS-150` on 187.

```
Read AGENTS.md, docs/HANDOFF.md and docs/release/RUNBOOK.md. Prepare release <1.5.1> from branch release-<1.5.1>:
run the RUNBOOK's section 0-1 steps (full suite, deploy to TEST, auto-QA all, write docs/release/<v>-qa.md and
docs/site/releases/<v>.md, docs/release/<v>.conf, tools/release/build, stage, rehearse, client publish dry-run if
the client changed, release --dry-run). Include the roster settings change listed in docs/HANDOFF.md.
STOP before anything marked GO. Report: what's in the release, test and rehearsal results, the exact GO commands
in order with rollback, and a Discord draft under 2,000 characters. The owner runs the live steps.
```

## 4. Client DLL work (slums.dll)

Start on 187 (helpers reach Silvia over ssh, `crims@192.168.0.186`, key `~/.ssh/id_rsprox_desktop`). Read
`docs/sessions/EXTERNAL-AGENTS.md`, Client lane, too.

```
Read AGENTS.md (section 4) and docs/sessions/EXTERNAL-AGENTS.md first and follow them exactly: write only inside
Z:\MSv83\SlumsMS-dev, make a new folder for this task, never touch test-client\ or the owner's installs, never close
the owner's programs, and don't start the game yourself.

Task: <describe it, e.g. "Guide NPC pages show a scrollbar; check the window size">.

Work on a branch client-162-<topic> from client-162-maker. Build in client-<topic>\ on Silvia: clone from the newest
RC folder, fetch your branch from a full git bundle, git submodule update --init --recursive, CMake (Win32,
Release), run ctest. Read stock bytes from client-1.5.0-fixes2\exe-copy\MapleStory.exe before patching an address.
Do not push. Write REPORT.md in your folder (what changed, DLL SHA-256, ctest result, the in-game checks you want
run). If the task also needs server changes, describe them in REPORT.md; a server agent does those.
```

## 5. Launcher work

Start in `Z:\MSv83\SlumsMS-dev` on the owner's PC to build; signing and publishing happen on 187.

```
Read AGENTS.md and HANDOFF.md in this folder first. Task: <describe it>. Clone slumsms-launcher branch
main into a new folder launcher-<topic>, npm ci, npm test, build as in its README. Bump the version.
Never put a key in the source or the build. Copy the unsigned exe and its SHA-256 to NOTES.md; signing and
publishing are done on 192.168.0.187 (docs/release/RUNBOOK.md step 2.6), Test channel first, Live only on the
owner's go.
```

## 6. Client visual QA with the client MCP

The owner starts the worker on Silvia (a scheduled task) with
`Z:\MSv83\SlumsMS-dev\client-mcp\scripts\qa-task.ps1 start` and leaves the PC for 10 minutes. Helpers can run
`status` only; they never start the worker. The tool on 187 is
`/root/work/SlumsMS-ops-client-mcp-vm/tools/ops/client-mcp` (`CLIENT_MCP_TARGET=silvia|vm`; the VM can't run the
client yet). Start the agent in `/root/work/SlumsMS-150` on 187.

```
Read AGENTS.md and docs/qa/QA.md. The client-MCP worker on the owner's PC is running for about an hour. Use
the client-mcp tool to: status, start_client, login channel 1, then <what to check, e.g. "warp to Henesys
(100000000), run !botground, screenshot; open @guide, screenshot each page">, read_logs, stop_client. Copy the
screenshots back and look at them. Write the results to Z:\MSv83\SlumsMS-dev\client-mcp\qa-<version>.md (step,
result, screenshot). If a tool refuses, stop and report; don't work around it.
```

Chat traps:
- `!` and `@` commands parse only from All chat (press ENTER, then 1).
- A command typed into an already-open chat box loses its first character.
- `go_to {"channel":N}` can leak "changechannel" as public chat. Use MENU, then Change Channel.
- The login tool needs the login screen. If the client sits at world select with no channel list, restart the client.

## 7. Bigger feature from the roadmap (1.6.0)

Start in `/root/work/SlumsMS-150` on 187.

```
Read AGENTS.md, docs/HANDOFF.md, docs/ROADMAP.md and docs/tickets/<60-bot-brain.md>. Build <the item> for 1.6.0
on branch 160-<topic> from origin/release-1.5.0. Start with a short plan in the ticket file (shape, files, tests, open
questions for the owner) and commit it, then build in small tested steps, deploying to TEST and adding !qa checks
and GM commands so it can be staged. Don't change the economy, live, or anything the ticket leaves to the owner;
list those questions instead. Report progress and update docs/HANDOFF.md at the end.
```

## Reviewing an agent's work

```
Review branch <name> against origin/release-1.5.0 in /root/work/SlumsMS-150. Read AGENTS.md first. Check: does it
fix the cause, are there tests, did the full suite pass, does anything touch live, secrets, the economy or the
owner's PC outside SlumsMS-dev. List problems by severity with file:line. Don't change the code.
```
