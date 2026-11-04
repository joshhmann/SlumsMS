# Ticket 183: the daily expedition-attempt ledger is never written, so the attempt quota counts clears (F02)

**Status:** open for 1.6.4. Filed 2026-11-02 09:04 UTC (01:04 PST) from the 1.6.4 content hunt (boss/expedition audit
row F02, impact 3, "FIX"). Line numbers as of `release-1.6.4` tip `c0e0a2cd7`.

## The defect

`src/main/java/server/expeditions/ExpeditionBossLog.java:220-239` is now:

```java
public static boolean attemptBoss(int cid, int channel, Expedition exped, boolean log) {
    if (!YamlConfig.config.server.USE_ENABLE_DAILY_EXPEDITIONS) {
        return true;
    }
    BossLogEntry boss = BossLogEntry.getBossEntryByName(exped.getType().name());
    if (boss == null) { return true; }
    if (channel < boss.minChannel || channel > boss.maxChannel) { return false; }
    if (countPlayerEntries(cid, boss) >= boss.entries) { return false; }
    return true;
}
```

Stock has two more lines before `return true;`:

```java
if (log) {
    insertPlayerEntry(cid, boss);
}
```

The fork dropped them. `grep "if (log)"` over the file finds nothing, so the `log` parameter is dead.

## What that breaks

- `Expedition.registerExpeditionAttempt()` (`src/main/java/server/expeditions/Expedition.java:249-255`) exists only to
  pass `log = true` — its body is `for (Character chr : getActiveMembers()) { ExpeditionBossLog.attemptBoss(chr.getId(), channel, this, true); }`.
  With the insert gone it is a pure no-op that still costs one `SELECT COUNT(*)` per member per start. Its caller
  `Expedition.java:197` throws the boolean away.
- The only remaining insert on the normal path is at the clear: `scripting/event/EventInstanceManager.java:902`
  (`ExpeditionBossLog.insertPlayerEntry(chr.getId(), ent)` inside `logBossClear`, `:895-905`), which iterates active
  members when the boss dies. `insertPlayerEntry` itself is `ExpeditionBossLog.java:209`. A GM can also insert by hand
  (`client/command/commands/gm6/SetBossExpeditionCommand.java:53`) and the 1.6.1 off-screen boss run inserts directly
  (`soloMapling/ArtificialPlayer/BotProgressionSystem/OffscreenBossRun.java:194`).
- So `countPlayerEntries(cid, boss)` counts **clears, not attempts**. The recruiter line that says
  "you've already reached the quota of attempts for this expedition" can only appear after `boss.entries` *successful
  clears*, and every NPC that says "attempts" is describing something the code does not count.
- `countPlayerEntriesByHwid` (`ExpeditionBossLog.java:302`) counts `completed=1` rows too, so the HWID limit in ticket
  184 has the same shape.

## Reachability

`USE_ENABLE_DAILY_EXPEDITIONS` is declared `src/main/java/config/ServerConfig.java:346` with no inline initialiser and
has no key in this repo's `config.yaml` or `config.example.yaml`, so it reads `false` and `attemptBoss` returns at its
first line: nothing is counted at all. With the switch on (ledger MP-009 says "daily expeditions on"), the quota is
live but clear-based. The fork's entry counts are large — `ExpeditionBossLog.java:41` is
`ZAKUM(1, 5, 25, 24, false)`, i.e. 25 entries where stock had 2 — so the practical limit is 25 clears a day.

## Consequence for stocking this release

The switch and the ledger have to be decided together: turning `USE_ENABLE_DAILY_EXPEDITIONS=true` on without this fix
advertises an attempt quota that only ever counts successful clears. Either fix the insert and turn the switch on, or
leave the switch off and say the quota is clear-based.

## Acceptance

- `attemptBoss(..., true)` writes one `bosslog_*` row per member at registration again; `attemptBoss(..., false)` writes
  nothing and returns the same verdict as today.
- A test registers an expedition with `log = true`, then asserts `countPlayerEntries` went up by one per member and that
  a member who starts (but does not clear) `boss.entries` expeditions is refused at the quota.
- Fail-before: the same test shows the count unchanged after registration.
- `registerExpeditionAttempt()` either becomes meaningful again or is removed with its caller — pick one; do not leave a
  method that costs a query per member per start and cannot change an outcome.

## For the release book

- **Card:** Expeditions | the daily attempt quota counts successful clears because the registration insert was deleted, so "attempts" are not counted anywhere | S3 | lane Server | closer: restore `if (log) { insertPlayerEntry(cid, boss); }` in `ExpeditionBossLog.attemptBoss` and prove it with a registration test (count rises by one per member; the quota refuses the N+1th start).
- **Patch notes (draft, player words):** "Daily expedition limits now count every attempt you start, not only the runs you finish." (only if the switch is turned on this release; otherwise "staff only, no line")
- **Confign line:** `SERVER_SET` gains `USE_ENABLE_DAILY_EXPEDITIONS=true` — only together with this fix, and only if the owner wants the daily quota live; with the bug in place the switch advertises a quota that counts clears.
- **Test-tab row:** test: daily expedition quota | how: start the same expedition `entries` times without clearing, and confirm the recruiter refuses the next start.
