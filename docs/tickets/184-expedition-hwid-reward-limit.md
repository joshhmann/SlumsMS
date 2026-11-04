# Ticket 184: `EXPEDITION_HWID_LIMIT` reads 0 and nothing calls the limit, so "4 per machine per day" is enforced nowhere (F03)

**Status:** open for 1.6.4. Filed 2026-11-02 09:04 UTC (01:04 PST) from the 1.6.4 content hunt (boss/expedition audit
row F03, impact 3, "FIX"). Line numbers as of `release-1.6.4` tip `c0e0a2cd7`.

## The defect — two halves, both required for the limit to mean anything

**1. The field reads 0.** `src/main/java/config/ServerConfig.java:568`:

```java
// amount of characters that can receive expedition rewards on the same HWID
public int EXPEDITION_HWID_LIMIT;
```

No inline initialiser, and the key appears in neither `config.yaml` nor `config.example.yaml`. `YamlConfig` reads the
file with a plain `YamlReader` and no strictness (`src/main/java/config/YamlConfig.java:19-29`), so the field keeps its
JVM default `0` on every deployment. Its only reader is `ExpeditionBossLog.reachedBossRewardLimit`
(`src/main/java/server/expeditions/ExpeditionBossLog.java:342-346`):

```java
return boss != null && countPlayerEntriesByHwid(cid, boss) > YamlConfig.config.server.EXPEDITION_HWID_LIMIT * boss.entries;
```

With 0 that is `count > 0`, i.e. *every* clear after the account's first is "over the limit".

**2. Nobody asks.** `reachedBossRewardLimit` has no caller in `src/main/java` or `scripts/`. The two wrappers exist and
are uncalled: `Character.java:13962` `public boolean reachedRewardLimit(ExpeditionType type)` and
`scripting/AbstractPlayerInteraction.java:1384` `public boolean reachedRewardLimit(ExpeditionType type)` (the script
API, declared in `scripts/lib.d.ts`). A `grep -rn reachedRewardLimit src scripts` returns the declarations only. So
ledger row SE-002 — `docs/design/divergence-ledger.md:202`, "Expedition HWID limit 4 per machine per day | keep" — is
not enforced at any point, and the field's own comment ("amount of characters that can receive expedition rewards on
the same HWID") describes a gate that does not exist.

## What has to change

1. Give the field a default of 4 and put `EXPEDITION_HWID_LIMIT: 4` in the shipped template, next to the other
   expedition keys. The `164-configtemplate` lane's template already carries it (`EXPEDITION_HWID_LIMIT: 4` at
   `config.example.yaml:511` on that branch), but a template key alone does not help a hand-written config, which is
   why the inline initialiser is part of this ticket.
2. Call the limit at the point the clear reward is claimed. The boss-log row is written for every active member by
   `EventInstanceManager.logBossClear` (`src/main/java/scripting/event/EventInstanceManager.java:896-906`, insert at
   `:902`); the items are handed out by the event scripts' clear handlers (ticket 182's loops) and by the exit NPC
   `scripts/npc/3004006.js`. One call, reused: gate `logBossClear`'s insert and the item hand-out on
   `reachedBossRewardLimit(chr.getId(), type)`, and expose the same verdict to scripts through the existing
   `cm.reachedRewardLimit(type)` so the NPC does not need its own arithmetic.
3. Decide the arithmetic. The stock formula multiplies by `boss.entries`, so with `EXPEDITION_HWID_LIMIT = 4` and
   `ZAKUM(1, 5, 25, 24, false)` (`ExpeditionBossLog.java:41`, 25 entries) the threshold is 100 clears, not 4. The
   ledger row says "4 per machine per day". Either keep the multiplication and say so in the ledger and the patch
   notes, or drop it so the number in the config means what the comment says. Whichever is chosen, the test below
   pins it.

## Acceptance

- `EXPEDITION_HWID_LIMIT` has an inline default of 4 and a key in `config.example.yaml`; a config without the key still
  gets 4.
- The reward claim consults the limit: the boss-log insert and the item hand-out are both gated.
- A test on one HWID proves the fourth clear's reward is granted and the fifth is refused, while a second HWID is
  unaffected. If the `× boss.entries` multiplication is kept, the test uses a `BossLogEntry` whose `entries` is 1 (or
  scales its expectation to `4 × entries`) and says which in a comment, because otherwise the assertion is testing a
  different number from the one in the config file.
- Fail-before: the same test shows the fifth clear rewarded (limit unenforced) and, with the key removed, the second
  clear refused (limit 0).

## For the release book

- **Card:** Expeditions | the per-machine expedition reward limit reads 0 from an absent key and is never consulted, so the documented "4 per machine per day" (SE-002) is enforced nowhere | S3 | lane Server | closer: default 4 + template key + the check wired into the reward claim, proven by a test where the 4th clear on one HWID pays and the 5th does not.
- **Patch notes (draft, player words):** "Expedition rewards are now limited to four clears per machine per day, as the design notes always said."
- **Confign line:** `SERVER_SET` gains `EXPEDITION_HWID_LIMIT=4` (stage checks it against `ServerConfig.java`); rollback is the same key removed, no jar.
- **Test-tab row:** test: per-machine expedition reward limit | how: clear an expedition five times on one machine and confirm the fifth clear pays no reward while a second machine still does.
