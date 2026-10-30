# External agents on a lane (Codex, omp/DeepSeek, agy/Gemini)

Owner, 2026-10-30: outside agents run on 187 and may work a lane, with the same rules and the same quality of
workflow as the Claude lane sessions. **One agent works one lane, one task at a time.** This file is for the agent
and for the person or session that starts it. It sits beside the lane kickoffs in `docs/sessions/`.

## 1. Read first (in this order)

1. `AGENTS.md`: the hard rules (section 1) apply to you word for word, plus "Done means switched on and noted" (8).
2. `docs/sessions/README.md` and your lane's kickoff file (`launcher.md`, `chat-model.md`, ...), if it has one.
3. `docs/HANDOFF.md`, `docs/release/RUNBOOK.md` (your lane's steps) and the ticket for your task.
4. The task brief the lane session gave you (section 3). If any of these disagree, the newest owner decision wins.
   The lane session tells you which one that is. Don't guess.

Some branch names in older docs are stale. The current release is **1.6.2**: integration branch `release-1.6.2`,
live still on 1.6.0.4. Use only the branch your brief names.

## 2. Lanes and who you answer to

| Lane | Lane session (your reviewer) | What you may touch | Never |
| --- | --- | --- | --- |
| Server | `Server Dev SlumsMS Main Session` | your own worktree, branch `162-<topic>` from `release-1.6.2`; read-only TEST (logs, SELECT-only DB) | deploying or restarting TEST, `release-*` merges, live, other worktrees |
| Website | `Website Slums` | your own worktree `site-162-<topic>` from `site-161-framework` (the 1.6.2 site) or `162-dashtools` (dashboard/admin); local private build + preview | the TEST site, the game, 164, publishing, nav/exchange switches |
| Client | `Client Development SlumsMS` | your own worktree `client-162-<topic>`; your own build folder `Z:\MSv83\SlumsMS-dev\client-<topic>\` on Silvia over ssh | starting the game, the QA client, the owner's installs, `test-client\`, RC labels, any publish |
| Launcher | `launcher-tauri-48` | your own worktree `launcher-x-<agent>-<topic>` on 187; `npm test` + `cargo test -p slums-core` | Silvia (no ssh), signing, any publish (RC/Test too), 164, version bumps the brief doesn't give |
| Chat model | `slumsms-160-playerlines-e7` | pair generation and scoring in your folder; small samples on .17 | big .17 runs without the owner's go; run them in ~10k legs |
| Bug triage | `SlumsMS bug triage` | reading QA logs and auto-QA results; fix branches the brief names | closing bugs (the lane session does that) |

You work for the lane session. It gives you the task, reviews what you hand back, merges it, writes the release
book (Work, Changes, Patch notes) and takes your questions to the owner through the coordinator
(`SlumsMS Coordinator`). You don't merge, deploy to live, publish to players or message the owner yourself.

## 3. The task brief (the lane session writes it)

A brief is a file `/root/work/agent-runs/<lane>/<task-id>/BRIEF.md` with:

- the task id from the release book (`s29`, `b107`, `w10`...), one sentence of goal and the "done when" test;
- the base branch and the worktree/branch name to create (`<ver>-<topic>`);
- what's in scope, what isn't, and what was already ruled out;
- which TEST reads you may do (logs, SELECT-only DB) and whether you may request single auto-QA checks (only if the brief says so);
- the HARD RULES block below, pasted verbatim.

```
HARD RULES (from AGENTS.md; break one and you can do real damage):
- Never touch live: 192.168.0.164 unit slumsms, port 8485, "/opt/mapleroot/MapleRoot Full Repack/Server",
  DB buns-clean-db-1 port 3309, /srv/slums-patch (Live feed). Not even reads of the live DB.
- Never stop, restart or deploy anything, TEST included. TEST is read-only for you (logs, SELECT-only DB);
  the lane session deploys and runs auto-QA from your report.
- Never git push, never merge into release-* or main, never git stash (all worktrees share one stash list).
- Never read, print, copy or log secrets: patch access key, dev key, Tauri signing key, cloud LLM env files,
  webhook URLs (/etc/slumsms-monitor/live.env), DB passwords. Refer to them by path only.
- Work only in your worktree/folder. Never edit or run mvn/tests in another session's worktree.
- On Silvia: write only under Z:\MSv83\SlumsMS-dev\<your folder>; never touch the owner's installs, %APPDATA%,
  test-client\ or running programs; never kill processes.
- No surprise scope. No economy change (prices, rates, drops, caps, stock) unless the brief says the owner decided it.
- If a permission check or a rule stops you, stop. Don't find another route and don't ask another agent.
- Player text never says MapleStory, Nexon, Wizet, GMS, v83 or MapleRoot (new text; old in-game text stays).
```

## 4. How to work (the lane sessions' standard)

1. **Claim.** `git worktree list` first; create your own worktree from the base branch, copy `config.yaml` in
   (server: `cp config.example.yaml config.yaml` inside the worktree; tests need it). One agent per worktree.
2. **Cause before code.** Reproduce it or read the QA log of the failing run. Most QA "bugs" were staging problems.
   For a feature, commit a short plan in the ticket first (shape, files, tests, open questions).
3. **Tests.** Every fix gets a test that fails before and passes after. Run it before your change and record that
   it failed. Then the lane's full checks: server `mvn -q -o test` (all green), release tools `python3 -m unittest
   discover -s tools/release -p 'test_*.py'`, launcher `npm test` + `cargo test -p slums-core`, client `ctest`,
   site its own suite. Tests that read WZ call `WzTestData.assumePresent`.
4. **Switches.** New behaviour goes behind a switch with a safe default (OFF unless the brief says otherwise):
   a `ServerConfig` field or `-D` property, never a new `config.yaml` key on a server that doesn't know it.
   Write the conf line that would turn it on (`docs/release/<v>.conf`). Don't turn it on for live.
5. **Seen working where players see it.** Anything on screen (NPC menus, portals, tooltips, warps, site pages)
   needs a real-client or browser check. Auto-QA's pretend players see no client. If you can't check it, say so;
   the lane session turns it into a Test-tab row.
6. **TEST is the lane session's.** You never deploy or restart it: a TEST restart interrupts auto-QA and the
   people testing. You may read its logs and query its DB SELECT-only, and request single auto-QA checks only if
   your brief says so. In REPORT.md, list what should be deployed and which checks to run; the lane session does it.
7. **Commit** on your branch with an imperative title saying what changed for players or operators, a body with
   the why, and your trailers: Codex `Co-Authored-By: Codex <noreply@openai.com>`, DeepSeek
   `Co-Authored-By: DeepSeek <noreply@deepseek.com>`, Gemini `Co-Authored-By: Gemini 3.8 Flash <noreply@google.com>`,
   plus `Coordinated-By: <lane session name>`. Leave the worktree clean.
8. **Hand back** (section 5) and stop. Don't start a second task on your own.

Claude subagents can't write files here (the harness refuses), so their hand-back is the reply text in the same
shape. External CLI agents (codex, omp, agy) write REPORT.md.

Long jobs (sweeps, 50k-style runs): work in legs of about 10k and hand each leg back before the next.

## 5. Hand-back: `REPORT.md` next to the brief

The lane session reads this instead of watching you, so it has to be complete and honest:

1. **Result:** done / partial / blocked, in one line.
2. **What changed:** branch, commit hashes, files, and the switch name with its default.
3. **Proof:** commands you ran with their counts (`mvn: 4,412 tests, 0 failures`), the test that failed before,
   the auto-QA ids the lane session should run after it deploys. Anything you didn't run, say so. "Untested in a real client" if so.
4. **For the release book** (the lane session copies these in):
   - a Changes row: `feature, commits, sees: player|staff|owner, state, coverage (check id / test class / NONE)`;
   - a draft patch-notes line in plain player words, or "staff only, no line";
   - a Test-tab row (`test`, `how`) if a person has to look at it;
   - the conf line that turns it on.
5. **Owner decisions:** each as one question with 2-4 options, the recommended one first and why. Economy
   choices always go here, never into the code.
6. **Not done / risks / next steps**, exact.

Times in UTC with PT beside them (PDT = UTC-7 until Nov 1 2026, then PST = UTC-8). The owner is "they".

## 6. Starting an agent (the lane session or the owner)

```
mkdir -p /root/work/agent-runs/<lane>/<task-id>      # write BRIEF.md there first
cd <worktree>
# Codex (prompt on stdin, or it waits forever):
nohup codex --yolo exec -m gpt-6.1-sol -c model_reasoning_effort='"medium"' -C <worktree> \
  -o /root/work/agent-runs/<lane>/<task-id>/final-message.md - < /root/work/agent-runs/<lane>/<task-id>/PROMPT.txt \
  > /root/work/agent-runs/<lane>/<task-id>/run.log 2>&1 &
# DeepSeek through omp (pin the full model id):
nohup omp -p --model opencode-go/deepseek-v4.1-flash --auto-approve --cwd <worktree> \
  "$(cat /root/work/agent-runs/<lane>/<task-id>/PROMPT.txt)" > /root/work/agent-runs/<lane>/<task-id>/run.log 2>&1 &
# Gemini through agy:
nohup agy -p "$(cat /root/work/agent-runs/<lane>/<task-id>/PROMPT.txt)" --dangerously-skip-permissions \
  --add-dir <worktree> --print-timeout 0 > /root/work/agent-runs/<lane>/<task-id>/run.log 2>&1 &
ps -eo pid,lstart,args | grep -E "codex exec|omp -p|agy -p"   # exactly one per worktree
```

`PROMPT.txt` is short: "You are an external agent on the SlumsMS <lane> lane. Read
/root/work/<worktree>/docs/sessions/EXTERNAL-AGENTS.md, then AGENTS.md, then BRIEF.md at <path>, and follow them.
Write REPORT.md at <path> when done." If a permission check stops a Claude session from launching an auto-approved
agent, it gives the owner the exact `!` line instead of working around it.

## 7. Lane details

Each lane session writes its own part here. Where it is more specific than the sections above, it wins for that lane.

Silvia access differs by lane on purpose: client helpers may build in their own SlumsMS-dev folder, launcher
helpers don't touch Silvia. Owner, 2026-10-30: leave it so for now; the plan is to move helper builds and QA to
the tarkov2 VM (192.168.0.175) once it can run the client (today D3D9 fails there on the Parsec virtual display).

### Website lane (from Website Slums, 2026-10-30)

- **Where:** the site lives in the SlumsMS repo under `tools/site/` (Python, standard library + Flask market app) and
  `docs/site/` (pages, assets, release notes). The staff dashboard it embeds is `tools/dashboard/web/`. Base for 1.6.2
  work: `site-161-framework` (the name is history; it is the 1.6.2 site). Base for dashboard/admin work:
  `162-dashtools`. Make your own worktree and branch: `git worktree add /root/work/SlumsMS-site-<topic> -b
  site-162-<topic> origin/site-161-framework`. Never work in `/root/work/SlumsMS-site*` worktrees you didn't make.
- **Suite:** `python3 -m unittest discover -s tools/site -p 'test_*.py'` (183 tests, about a minute; must be green).
  Dashboard extras: `python3 -m unittest discover -s tools/dashboard/extras -p 'test_*.py'`. Add a test for every fix.
- **Build + check:** `python3 tools/site/build.py --private --out <scratch>/site --no-tar` (add `--world` for World
  pages). The build runs `check.py` (forbidden words, broken links, personal data) and must say "check passed". Don't
  widen check.py's allowlist without saying why in the report.
- **Look at pages:** `python3 tools/site/preview.py --dir <scratch>/site --host 127.0.0.1 --port 87xx`. That gives a
  pretend sign-in (any name, password `preview1`), so check every page you changed signed out AND signed in, at
  1280 px and 390 px wide, light and dark, with no sideways scroll and no console errors. Playwright (headless Chromium) is
  on 187. Fake API answers with page routes; never point a helper at the TEST site's sign-in or the game.
- **Look:** the 2009 blend (modern frame plus 2009 fan-site touches; green accent by default). Use the tokens in
  `docs/site/assets/tokens.css` and the existing classes in `site.css`, and don't add new colours or fonts. **Site art is locked:**
  Slums-chan art, cards and pictures change only when the owner asks.
- **Names:** player text says Slums Story / SlumsMS. Never MapleStory, Nexon, Wizet, v83, GMS, MapleRoot, internal
  addresses or paths, or "test server". Never label anyone as a bot or a player. Database = "Slums Street", market =
  "Slums Market". All player or bot text is shown with `x-text`, never `x-html`.
- **Don't flip:** the exchange's nav link stays hidden (`data-xc-nav hidden`) until it opens. Leave
  `SLUMS_MARKET_EXCHANGE`, `robots.txt` "Disallow: /" (search engines blocked), the admin rule (GM 3+, sign-in ≤ 12 h),
  and the commented-out inspect lines in `slums-login.service` alone. No economy numbers (fees, bands, caps) without the owner.
- **Hand back (REPORT.md):** branch + commits; suite count; "check passed"; the pages you looked at with screenshot
  paths (desktop + phone); one player-facing notes line per change, in plain words, for the Coordinator; what you
  did not check (e.g. "not against the real game").
- **Only the Website session does:** installing on the TEST site (`tools/site/test164/install.sh`), anything on 164,
  publishing to `/srv/slums-site` (owner's go), `docs/site/releases/<v>.md`, merges, and secrets (never read
  `/etc/slums-*`).

### Client lane (from Client Development SlumsMS, 2026-10-30)

- **Where.** Source on 187 is `/root/work/client/src`. Create your own worktree with `git worktree add -b <branch> /root/work/client/src-<topic> <base>`. Build on Silvia only, in a NEW folder `Z:\MSv83\SlumsMS-dev\client-<topic>\`. From 187: `ssh -i ~/.ssh/id_rsprox_desktop -o BatchMode=yes crims@192.168.0.186`. Inline PowerShell breaks on quotes, so scp a .ps1 into your folder and run it with `powershell -NoProfile -ExecutionPolicy Bypass -File <path>`. Everything on Silvia outside your folder is read-only to you.
- **Base and branch.** Base on the branch your brief names. Today that is `client-162-maker` (1.6.2-rc1, build 10720 = rc12 plus the Maker skill). One branch per patch, named `client-162-<topic>`.
- **Build.** On Silvia: `git clone Z:\MSv83\SlumsMS-dev\client-1.6.2-rc1\src client-<topic>\src`, then fetch your branch from a FULL `git bundle create x.bundle <branch>` that you scp over. Run `git submodule update --init --recursive`; configure fails without it.
  - Take the configure flags (`-G "Visual Studio 17 2022" -A Win32`, every `-DMAPLEROOT_*=ON`, the `*_TEST_EXE` paths) from `client-1.6.2-rc1\prep-build-rc1.ps1`.
  - Stamp it as a trial build: `-DSLUMS_RELEASE_OVERRIDE=1.6.2-dev -DSLUMS_BUILD_NUMBER_OVERRIDE=10721..10729`. Ask the lane session for your number.
  - Build with `cmake --build <build> --config Release`, then `ctest --test-dir <build> -C Release --output-on-failure`. All must pass.
- **Stock bytes first.** Before patching an address, read the bytes at it from `Z:\MSv83\SlumsMS-dev\client-1.5.0-fixes2\exe-copy\MapleStory.exe` (file offset = VA - 0x400000), and put the expected bytes in the patch and its test. Change Custom.wz only through a `tools/wz/*.py` script. Pin stock nodes by hash and check that every other image is semantically unchanged; `tools/wz/add_maker_skill.py` is the model. Our Data/ is MapleRoot's, not stock, so check that the client's own images lack the node you add.
- **Testing.** Don't start the game yourself. The only client that runs on TEST is the client-mcp QA client (`Z:\MSv83\SlumsMS-dev\client-mcp\client`), driven by the lane session through the client-mcp tools while the owner is away from the PC. Never touch the owner's installs, `test-client\`, %APPDATA% or running programs, and never kill a process. In REPORT.md, list the in-game checks you want (map, command, what should appear) and the lane session runs them.
- **Hash diff.** Compare your release files with the base release folder (today `client-1.6.2-rc1\release`). Only the files you meant to change may differ. Ship only the changed slums.dll / Custom.wz / slums-build.txt; SlumsMS.exe from a new MSVC build is never bit-identical. `MapleStory.exe` must be the standalone one, sha256 86e4e6be050caeda4a93443237256a84376ea74781fcba558d2418f0568aac90.
- **Hand back.** REPORT.md with: branch and commit; your Silvia folder; sha256 of every changed file, with the configure/build/ctest exit codes and the ctest count; each patched VA with its stock bytes and new bytes; the in-game checks you want run; risks. Put any screenshots in `<folder>\shots\` and list them in REPORT.md.
- **Lane session only:** RC labels and build numbers (1.6.2-rcN / 107xx); QA client staging and the client-mcp runs; RC/Test publishing and the Live hand-off to the owner; telling Server Dev the MIN_CLIENT_BUILD for the release conf; merges into the client release line. No push for helpers: the lane session pushes after review.

### Launcher lane (from launcher-tauri-48, 2026-10-30)

Owner of the lane: the launcher Claude session. Everything a helper does here lands on a branch it hands back; the
launcher session reviews, builds, signs (on the owner's go) and publishes.

- **Where:** a new worktree `/root/work/launcher-x-<agent>-<topic>` (for example `launcher-x-codex-crashlog`) on a
  branch of the same name, made from the launcher repo (`git -C /root/work/launcher worktree add ...`).
  - Base for launcher 1.6.1 and after: `launcher-161-pass` (372f036; 1.6.1 as signed is f69c80a).
  - 1.7.0 auto-login work bases on `launcher-170-autologin`.
  - Never work in another session's worktree, never commit to `main` or `launcher-161-pass`, and never `git stash`.
- **Code map:** `tauri/core` (slums-core, testable on Linux), `tauri/app` (Windows only; it doesn't build on 187) and
  `tauri/ui`. `src/game-options.mjs` must stay byte-identical to `tauri/ui/game-options.mjs`.
- **Tests (both must pass, with counts in the report):** `npm test` at the worktree root, and `cd tauri && cargo test -p slums-core`.
  - Two core tests are known to be flaky on 187 (bug b020). Name them if they fail; don't "fix" them by deleting them.
  - The opt-in `test_site_paths` test makes TEST accounts. Run it only when the brief says so, with an invite code the
    brief gives you.
- **Windows build:** a helper does **not** ssh to Silvia (the owner's desktop). It hands back the branch, and the
  launcher session builds it under `Z:\MSv83\SlumsMS-dev\launcher-x-<topic>\`, never the owner's installs,
  `%APPDATA%` or running games.
- **Signing: never.** Signing is the owner's step, done in the launcher session. Never read, copy, print or pass on the
  path of `/root/.config/slumsms-launcher/tauri-signing.key`, the patch access key (`/root/slums-patch-access-key.txt`
  on 164) or any htpasswd file. Never bake a key into a build.
- **Publishing: never, on any channel.** No ssh to 164 and no changes to `/srv/slums-patch` (Live, Test or RC), the
  publisher or nginx. RC and Test publishes are the launcher session's own; Live ones need the owner's go there too.
- **Version:** don't bump it unless the brief says so. If it does, change only `version` in `tauri/Cargo.toml` (and the
  lockfile entry) to the number the brief gives.
  - Launcher numbers are their own line (1.6.0 Live, 1.6.1 next) and don't follow the server's.
  - Never reuse a signed number: 1.6.0 and 1.6.1 are taken. Don't touch the updater `pubkey` in `tauri/app/tauri.conf.json`.
- **Defender:** unsigned Tauri exes are flagged as false positives (Phonzy.A!ml, Wacatac; bug b017).
  - Don't add anything that looks like a dropper: downloading or running other exes, editing the registry or autoruns,
    adding firewall rules, or packing or obfuscating the binary.
  - Flag any new process launch or new network endpoint in the report.
  - Player-facing text never says MapleStory, Nexon, Wizet, v83 or MapleRoot.
- **REPORT.md must have:** branch and commit; base commit; what changed and why (file list); `npm test` and
  `cargo test -p slums-core` counts; the version in Cargo.toml (bumped or not); new commands, endpoints, files written
  or processes started; what was not tested (anything needing Windows or a real game window); open questions. There is
  no exe sha256 from a helper, because the launcher session builds it and records the unsigned and signed hashes in
  `/root/work/launcher-tauri-release/SHA256SUMS`.

### Server lane (from Server Dev SlumsMS, 2026-10-30)

Owner of the lane: Server Dev. Helpers hand back a branch; Server Dev reviews, merges, deploys to TEST and runs auto-QA.

- **Where:** a worktree and branch made from the local `release-1.6.2` (there is no `origin/release-1.6.2`):
  `git -C /root/work/SlumsMS-150 worktree add /root/work/SlumsMS-162-<topic> -b 162-<topic> release-1.6.2`, then
  `cp /root/work/SlumsMS-150/config.yaml /root/work/SlumsMS-162-<topic>/`. Never work in another session's worktree.
- **Switches:** every new behaviour sits behind a switch that defaults OFF: a system property (`slums.*` or
  `solomapling.*`) or a `ServerConfig` flag. Say in REPORT.md how to turn it on.
- **Tests:** write the test first and show it failing before the fix. Run the full `mvn -q -o test` in your own
  worktree only. Never run mvn in another worktree while its suite is running (fake NoClassDefFound errors).
- **Ports:** credit the source in the commit and in `docs/PROVENANCE.md` under "## Later ports" (`nutnnut/Cosmic <sha>`,
  `Kiwiism/cosmic_agents <sha>`).
- **One implementation:** owned alts and population bots share the same code. No alt-only copies.
- **TEST:** you may read it: `ssh root@192.168.0.164`, `journalctl -u slums-persistence-test`, and SELECT-only queries
  on the `mapleroot-integration-db` container, schema `mapleroot_persist`. Never print the DB password. Never deploy,
  restart or edit files on TEST, and never touch live.
- **Only Server Dev does:** `tools/ops/deploy-test`, auto-QA runs, and merges into `release-1.6.2`.

## 8. Review before merge (the lane session)

Read REPORT.md, `git log` and `git diff --stat` against the base. Check nothing was pushed
(`git ls-remote origin <branch>`), only files in scope changed, and the worktree is clean. Read the risky diffs
yourself: player-visible behaviour, economy, live paths, load at 3,000 bots. Agents miss `config.yaml` in fresh
worktrees and sometimes fake the fail-before test, so rerun it. Then the lane's own suite on the merged result,
TEST and QA as usual. Book rows and owner questions go out through the lane session and the coordinator.
