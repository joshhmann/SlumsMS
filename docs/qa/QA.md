# QA guide: auto-QA on the test server, client visual QA on the owner's PC, the owner's checklist

Three layers, cheapest first. All of it runs against the TEST server (164, port 8489), so no go is needed, but tell
the owner before anything that restarts the test server or starts the game on their PC.

## 1. Auto-QA (server side, "did it happen")

Pretend players (SyntheticClient) stage scenes with `!qa` and assert results; health sweeps run every 5 minutes.

```
tools/ops/autoqa-run smoke          # quick set; also: all | <id> | <family> | id,id,...
tools/ops/test-status               # last run summary, health sweep, deployed commit, errors
ssh root@192.168.0.164 cat /opt/mapleroot-dev/persistence-test/Server/data/autoqa/latest.json   # full results
ssh root@192.168.0.164 'ls -t /opt/mapleroot-dev/persistence-test/Server/logs/qa/ | head'      # per-check logs
```
- Scheduled: nightly 10:00 UTC (3 AM PDT) in the server, and 22:00 UTC (3 PM PDT) by cron on 164.
- A request is picked up within 30 s; a run in progress is not interrupted. `all` takes about 20-30 minutes.
- Checks live in the server code under the auto-QA package (search `AutoQa` / `QaScenario`); each has an id like
  `rv-2`, `gw-4`. Add a check for every bug you fix when it can be staged.
- Triage every failure before fixing: staging problem (the `!qa` setup), real bug, working as designed but hard to
  see, or needs the owner. Read that check's log and the journal around it first
  (`.claude/skills/qa-triage/SKILL.md`). Known flakes are listed in `docs/HANDOFF.md` section 3.
- In game (GM on the test server): `!qa list`, `!qa run <id>`, `!qa stop`, `!botwhy`, `!botground`, `!botshowcase`,
  `!setmaxhp <hp> [<mp>]` (1.6.0: MaxHP/MaxMP past 32,767 for the 4-byte client, see `docs/release/1.6.0-hpmp-4byte.md`).

## 2. Client visual QA ("does it look right"), the client MCP on Silvia

A Codex-built driver (`Z:\MSv83\SlumsMS-dev\client-mcp\`, ticket 61) runs a COPY of the RC client against the test
server as `AqClient` (account `autoqaclient`, GM 3, test only; the password is only in its `secrets\` file, never
read or print it). It can start the client, log in, type commands, warp, screenshot and read client logs.
To frame a spot on the current map (NPC screenshots), type `!tp <x> <y>` (GM 3, 1.6.1): a same-map warp to the
point, snapped onto the foothold below it when there is one; `!pos` shows where you stand.

**Limits that make it fail if ignored:** it only works in the owner's logged-in desktop, as admin, with the
controller window in front, the PC idle for 10 minutes, and the real monitor as main display (Parsec as main display
= startup error -2147467259). It never runs from ssh alone.

**Starting it (the owner does this, then leaves the PC alone for 10 minutes):**
1. Empty the old queue: `type nul > Z:\MSv83\SlumsMS-dev\client-mcp\logs\worker-inbox.jsonl` (the worker replays every
   line in it at start; keep the file, the worker fails if it's missing).
2. Admin PowerShell: `Start-Process pythonw.exe -ArgumentList '-B','Z:\MSv83\SlumsMS-dev\client-mcp\scripts\focus-controller.py'`
   and leave the "SlumsMS client-driver controller" window in front.
3. Admin PowerShell: `python -B Z:\MSv83\SlumsMS-dev\client-mcp\scripts\elevated-worker.py` (lasts one hour).

**Driving it from 187** (`tools/ops/client-mcp`, which writes to the worker's queue over ssh):
```
tools/ops/client-mcp status                                   # idle time, display, PIDs, DLL hash
tools/ops/client-mcp start_client
tools/ops/client-mcp login '{"channel":1}'
tools/ops/client-mcp go_to '{"map_id":100000000}'
tools/ops/client-mcp command '{"line":"!botground"}'
tools/ops/client-mcp screenshot '{"label":"henesys-ground"}'
tools/ops/client-mcp read_logs '{"lines":100}'
tools/ops/client-mcp stop_client                              # always end with this
```
Screenshots land in `Z:\MSv83\SlumsMS-dev\client-mcp\shots\`; copy them back with
`scp -i ~/.ssh/id_rsprox_desktop 'crims@192.168.0.186:Z:/MSv83/SlumsMS-dev/client-mcp/shots/<file>.png' .` and look at
them. A Codex agent started on Silvia in `client-mcp\` can call the MCP server directly instead (see its README).

**A typical pass:** pair a server scene with screenshots. Start the scene from 187 (`tools/ops/client-mcp command
'{"line":"!qa run we-1"}'` or `tools/ops/autoqa-run we-1`), warp the client there, screenshot at the moments that
matter, read the client logs, stop. Write the results in `Z:\MSv83\SlumsMS-dev\client-mcp\qa-<version>.md` (step,
result, screenshot path), as `qa-1.5.0.md` does.

**When it refuses:** "owner is using the PC" = wait; "found 0 owned game windows" = startup failed, read the logs and
report; virtual display = tell the owner. Don't edit `local-config.json` (`force`), don't kill other processes, don't
work around a refusal.

## 3. The owner's checklist (what only a person can judge)

For each release write `docs/release/<version>-qa.md`: one line per check, `id | what to do | what you should see`,
grouped by area, reusing ids (`t-xx-n`). Mark which checks auto-QA or the client MCP already cover, so the owner only
does the rest. The owner writes results back in chat or in that file (`PASS` / `FAIL: note` after each line); turn
fails into fixes with the triage steps above and say what to retest by id.

The claude.ai notes/QA pages from 1.5.0 can only be opened by Claude; repo files are the source of truth from now on.

## Ticket 69: ordinary v83 features (`vb-*`)

Auto-QA checks `t-vb-1`..`t-vb-7` (scenario ids `vb-1`..`vb-7`) exercise the everyday paths a real player uses,
one check per feature. Each check runs `!qa run vb-N` and then its steps; every outcome is asserted, and any NPC
dialog that refuses a step the check expects to succeed fails it (`Expects.noRefusal`, diagnostic token
`NPC_REFUSAL`).

| id | what it does | the line that proves it worked |
|---|---|---|
| `t-vb-1` | hair, hair colour, face, lenses and skin at the Henesys salons, each with its coupon | `VB STYLE <step> <id> -> <id> changed` and `VB COUPON <id> 1 -> 0` |
| `t-vb-2` | creates a scratch character and deletes it through the login handler with PIC | `VB DELETE state=<cid> state 0 gone=true` (wrong PIC: `state 20 stillThere=true`) |
| `t-vb-3` | starts a Kerning PQ instance, clears it, takes the reward, exits | `VB KPQ cleared=true` then `VB KPQ reward given=true ... map=103000805` |
| `t-vb-4` | 3rd-job mount chain: the 6002 room and hand-in, Tatamo's 20m bottle (fee and refusal), the 6003 perfume, and catching the hog | `VB MOUNT bottle canComplete=true ... fee=20000000`, `VB MOUNT perfume ...` |
| `t-vb-5` | buys and sells at Henesys NPC 1012004, then makes the Cash Shop EXP coupon take effect | `VB SHOP buy ... owned 0 -> 1`, `VB CASH card=5211048 given=true rate 1 -> 2` |
| `t-vb-6` | Dances with Balrog promotes 100 -> 110, then a real skill point is spent | `VB JOB advance 100 -> 110`, `VB JOB sp spend ... level 0 -> 1` |
| `t-vb-7` | storage round trip, Duey to the other pretend player, and a trade between the two | `VB STORAGE tookOut bagDelta=0`, `VB DUEY claimed recipientDelta=1`, `VB TRADE offered=true itemDelta=1 mesoDelta=5000` |

`t-vb-4` also drives the catch: it spawns the tamable hog if the hourly spawn is not up, drops it to 30% HP and sends
the real `USE_CATCH_ITEM` packet with the perfume, so `VB MOUNT hog ... handler=true ... perfumeUsed=1` is the catch. `t-vb-5` stages the coupon by toggling it in the server's active set and always puts the
toggle back.
