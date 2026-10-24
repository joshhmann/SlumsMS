# Starter prompts for Codex / DeepSeek

Pick the one that fits, paste it as the first message, and fill in the `<...>` part. Every prompt makes the agent
read the rules first. Live, Discord and anything outside the test server still need the owner's go.

## Where to start the agent

| Work | Machine | Folder |
|---|---|---|
| Server code, bots, chat, QA, test deploys, releases, website, Discord, launcher publishing | dev box 192.168.0.187 | `/root/work/SlumsMS-150` |
| Client DLL (slums.dll), launcher builds, client visual QA | owner's PC (Silvia) | `Z:\MSv83\SlumsMS-dev` |

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

Start in `Z:\MSv83\SlumsMS-dev` on the owner's PC.

```
Read AGENTS.md and HANDOFF.md in this folder first and follow them exactly: write only inside
Z:\MSv83\SlumsMS-dev, make a new folder for this task, never touch test-client\ or the owner's installs, never close
the owner's programs, run the game only from your own copy pointed at the TEST server.

Task: <describe it, e.g. "Guide NPC pages show a scrollbar; check the window size">.

Clone SlumsMS-Client branch <client-1.5.0-fixes3 or newer> into client-<topic>\src, build with CMake (Win32,
Release), run ctest. Read stock bytes from client-1.5.0-fixes2\exe-copy\MapleStory.exe before patching an address.
Push your branch, write NOTES.md in your folder (what changed, DLL SHA-256, what you tested). If the task also needs
server changes, describe them in NOTES.md; an agent on 192.168.0.187 does those.
```

## 5. Launcher work

Start in `Z:\MSv83\SlumsMS-dev` on the owner's PC to build; signing and publishing happen on 187.

```
Read AGENTS.md and HANDOFF.md in this folder first. Task: <describe it>. Clone slumsms-launcher branch
launcher-ui-compact into a new folder launcher-<topic>, npm ci, npm test, build as in its README. Bump the version.
Never put a key in the source or the build. Copy the unsigned exe and its SHA-256 to NOTES.md; signing and
publishing are done on 192.168.0.187 (docs/release/RUNBOOK.md step 2.6), Test channel first, Live only on the
owner's go.
```

## 6. Client visual QA with the client MCP

The owner first starts the worker and controller on Silvia (`docs/qa/QA.md` section 2) and leaves the PC for 10
minutes. Then start the agent in `/root/work/SlumsMS-150` on 187.

```
Read AGENTS.md and docs/qa/QA.md. The client-MCP worker on the owner's PC is running for about an hour. Use
tools/ops/client-mcp to: status, start_client, login channel 1, then <what to check, e.g. "warp to Henesys
(100000000), run !botground, screenshot; open @guide, screenshot each page">, read_logs, stop_client. Copy the
screenshots back and look at them. Write the results to Z:\MSv83\SlumsMS-dev\client-mcp\qa-<version>.md (step,
result, screenshot). If a tool refuses, stop and report; don't work around it.
```

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
