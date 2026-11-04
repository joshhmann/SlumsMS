# Ticket 186: three pieces of dead code, and F08 as filed is wrong

**Status:** open for 1.6.4. Filed 2026-11-02 09:04 UTC (01:04 PST) from the 1.6.4 content hunt. Each item is
independent and small; they share a ticket only because none of them is worth a card on its own. **A lane exists:**
branch `164-deadcode` (worktree `/root/work/SlumsMS-164-deadcode`), which took this ticket together with 185; a lane
existing is not acceptance, and the criteria below stand unchanged. Line numbers as of `release-1.6.4` tip
`c0e0a2cd7`.

## 1. `RedisJobQueue` is dead, and it is the only reason `jedis` is a dependency

`src/main/java/net/server/RedisJobQueue.java` (64 lines) has no construction site anywhere in the repo — the only
references to the class name are its own declaration (`:9`) and its own constructor (`:16`,
`public RedisJobQueue(JedisPool jedisPool, String jobName)`). There is no config key that names it, nothing in
`soloMapling/` uses it, and it is the only file that imports `redis.clients.jedis` (`:3-5`).

`pom.xml:201-204` therefore declares a runtime dependency nothing else uses:

```xml
<dependency>
    <groupId>redis.clients</groupId>
    <artifactId>jedis</artifactId>
    <version>5.1.2</version>
</dependency>
```

Action: delete `RedisJobQueue.java` and the `jedis` dependency, and confirm `mvn -q test-compile` still resolves. If
someone plans to use a Redis job queue for the bot fleet, that plan belongs in a ticket — keeping a dead class and a
shaded dependency on the off-chance is how a release ends up carrying an unused network client. Note the deploy
side: if the launcher or the shaded jar's size/contents are checked anywhere (they are not, as far as this tree
shows), removing jedis changes the jar — worth one line in the REPORT, nothing more.

## 2. `Ola.startOla()` is GM-only and half-written

`src/main/java/server/events/gm/Ola.java:50`:

```java
public void startOla() { // TODO: Messages
```

The Ola/Ola-2/3/4 event is reached only from `src/main/java/server/maps/MapleMap.java:4759`
(`chr.getOla().startOla();` in the `EVENT_OLA_OLA_1..4` branch that opens at `:4757`), i.e. from a GM-placed event map.
The `// TODO: Messages` marks the missing flavour text, not missing mechanics — the timer, the portal unlock and
`timeStarted` are all set.

Action: finish the message or delete the method and its call, and say which in the hand-back. It is player-invisible
unless a GM starts the event, so this is a housekeeping item, not a defect.

## 3. F08 as filed is stale — do not "fix" it

The audit's F08 says "`!bosslog` command body is commented out, so no in-game boss-log surface exists" and parks it.
That is not what the code does, on this branch or on `163-integration` (`6cea00b4e`):

`src/main/java/client/command/commands/gm0/BossLogCommand.java` (64 lines) has a live `execute`:

```java
@Override
public void execute(Client c, String[] params) {          // :22
    c.getAbstractPlayerInteraction().openNpc(3004030, "3004015");   // :23
}
```

The legacy `dropMessage` body is what is commented out (`:25-63`, ending `} */`), and it is not reachable. The
replacement is a real surface: `scripts/npc/3004015.js` exists in this tree and its `action` sends a "Daily Bosses"
menu of boss-log entries through `cm.sendOk`. So `!bosslog` opens a boss-log NPC window; what is dead is the old
command body. (There is also no `src/main/java/tools/BossLogCommand.java` — the class lives under
`client/command/commands/gm0/`.)

Action: **park unless the command is wanted.** If nobody wants the commented body, delete `:25-63` so the next audit
does not report it again; if the NPC route is unacceptable (NPC 3004030 must exist in the deployed `Npc.wz`, which the
repo cannot show), then rewrite the command against `ExpeditionBossLog.getWeeklyBossEntries` — but that is a feature
decision, not a bug fix.

## Acceptance

- `RedisJobQueue.java`, its `jedis` dependency and any now-unused imports are gone; the tree compiles.
- `Ola.startOla()` is either finished with its message or removed with its only call site.
- `BossLogCommand.java` carries no commented-out body, and the ticket that closes this one states which of
  "command opens the NPC" / "command prints the log itself" is intended.

## For the release book

- **Card:** Housekeeping | dead `RedisJobQueue` plus its `jedis` dependency, a GM-only half-written `Ola.startOla`, and 39 lines of commented-out `!bosslog` body | S4 | lane Server | closer: delete the dead class/dependency and the commented body; unit tests plus `mvn test-compile` are the proof (no new test needed for a deletion).
- **Patch notes (draft, player words):** staff only, no line.
- **Confign line:** none.
- **Test-tab row:** none.
