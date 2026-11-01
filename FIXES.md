# b269 fix branch — 269-dbpoolfix

Findings are numbered as in `agent-runs/server/269-dbpool-forensics/AUDIT.md`. Commit 1 of this branch is the helper
harness's work verbatim (`s-dbpool`, commandcode), so its diff stays reviewable against that harness's REPORT.md; every
commit after it is one finding, with its own test.

| Finding | State | Commit | What it does |
|---|---|---|---|
| F1 (blocker) | fixed | `b269 fix 1` | `BotPopulationDirector.askLogin` answers `LOGGED_IN` / `QUEUED` / `REFUSED`; the guild show-up spends its 2-hour token only when a login was taken on; the romance show-up spends its 30-minute token only then; a queued rage-quit return keeps its promise |
| F6 (functional) | open | — | paced boot fill |
| F8 (high) | open | — | make `BotDbGateLoadTest`'s gossip test initialise without config.yaml |
| Timeouts/observability | open | — | `connectionTimeout` 10 s, minute `BotDbGate.stats()` log |
| F3/F4/F5 | open | — | documented, not fixed (see the notes at the bottom) |

## F1 — a refused or queued login is not "no"

`BotPopulationDirector.logInBounded` answered a boolean. On a virtual thread (every `ExecutorServiceManager.runAsync`
caller) it hands the login to the `bot-db-*` threads and answers `false` — "not in the world" — which is correct as a
fact but wrong as an answer to "will it come?". Three callers read the `false` as a refusal and reacted by burning
their own cooldown: `BotGuilds.showUp` spent the guild's 2-hour show-up token *before* even asking (so a refused
show-up went silent for two hours), `BotRomance.tend` had the login attempt inside the `take` conjunction (inside the
30-minute partner token), and `RageQuit.step` dropped the `returning` entry, so the rage-quitter's comeback line never
fired for a login that was in fact on its way.

Now `askLogin(botId)` says what happened: `LOGGED_IN`, `QUEUED` (a `bot-db` thread is logging it in now, it lands in a
few hundred milliseconds) or `REFUSED` (nothing was started; ask again later). `logInBounded` keeps its historical
boolean contract and is now a thin wrapper over it, and a queued login is deduplicated per bot (`queuedLogins`), so
repeated asks cost nothing.

- `BotGuilds.showUpNow`: the token is checked with the new `due()` and spent only when at least one member's login was
  taken on; a refused member is skipped and the next one is asked, instead of the whole show-up ending.
- `BotRomance.tend`: `DramaKit.free()` gates the ask, `take()` only after a `QUEUED`/`LOGGED_IN` answer; a refusal logs
  "could not log in yet … asking again next step" instead of passing for "no".
- `RageQuit.RealExit.comeBack`: returns true only for `LOGGED_IN`; `step()` keeps the `returning` entry while
  `loginQueued(charId)` holds, so `onBotLogin` still says the comeback line when the queued login lands.
- `RomanceWedding.java:157` and `GuildBossRuns.java:515` keep `logInBounded`: both run on a platform thread (a packet
  handler and the boss-run tick), where the boolean cannot mean "queued".

Test: `BotLoginAskTest` (3) — a virtual thread's ask is `QUEUED` in under a second and never `REFUSED`, `logInBounded`
on a virtual thread still answers `false` for a queued login, a platform ask for a slot the roster does not hold is
`REFUSED`. `GuildShowUpLoginTest` (3) — a refused show-up returns 0 and leaves the 2-hour token free (the next step may
ask again), a show-up that was taken on holds for two hours, a refusal moves on to the next member, and another guild's
number is never asked for.
