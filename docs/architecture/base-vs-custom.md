# Base vs custom: how to tell, and what the tool says today

Every subsystem doc in this review has an `## Origin` section claiming *base*, *modified* or *custom*. This page is the
method those claims rest on, plus the result of running the comparison tool on this branch
(`164-archreview`, HEAD `9851e4aba`).

## Method

Three sources, in order of strength:

1. **Upstream tree.** `/root/workspace/cosmicms` is a checkout of Cosmic at `fec53bc77`. `diff -u` against it is the
   strongest evidence for a Java file: identical = base, small hunks = modified, no upstream counterpart = custom.
2. **The diff tool.** `python3 tools/cosmicdiff/cosmic_diff.py --cosmic /root/workspace/cosmicms --ours .
   --baseline 03d13cb21 --out <file>` (tools/cosmicdiff/cosmic_diff.py:1-19). `03d13cb21` is the repack baseline (our
   first commit), so the report can say whether a difference came in with the repack or from a later SlumsMS commit
   ("provenance" column). It detects: files missing here, files much shorter (below 50% of upstream code lines, and
   upstream ≥ 40 lines), methods shrunk below 45% of upstream statements (upstream ≥ 4 statements), methods emptied
   to `{}`/`return null`/etc., commented-out blocks that are live upstream, and stub/rewritten scripts
   (tools/cosmicdiff/cosmic_diff.py:33-40).
3. **`git log --follow`.** `git log --follow --format=%h <path> | tail -1` gives the file's first commit: `03d13cb21` =
   it arrived with the repack; anything later = written by us. Used for files upstream never had.

Classifications for the tool's findings live in `tools/cosmicdiff/classifications.json` (`{id: {class, why}}`), so a
rerun only surfaces *new* findings. Classes: `DELIBERATE`, `BROKEN`, `RESTORED`, `UNSURE`, `UPSTREAM-NEW`, `NOISE`
(tools/cosmicdiff/cosmic_diff.py:9-27).

## Result on this branch

| Class | Findings |
|---|---|
| DELIBERATE | 56 |
| UPSTREAM-NEW (added upstream after the fork; nothing was stripped) | 57 |
| NOISE | 3 |
| UNCLASSIFIED | 51 |
| total open | 167 |
| RESTORED (fixed by us since an earlier run; no longer detected) | 14 |

Wall time 22 s. The report also notes "7 classification(s) no longer match a finding" — the ledger carries entries
whose finding disappeared (the file changed), so the ledger is stale in 7 places and should be pruned by whoever owns
the tool next.

## Clusters in the 51 UNCLASSIFIED, resolved by reading the code

The unclassified bulk is **not** a backlog of broken features. Reading the five biggest clusters:

| Cluster | Findings | Verdict | Evidence |
|---|---|---|---|
| Gachapon | 13 (`Gachapon.java` 57 vs 122 lines, `GachaponItems`, `ElNath`, `Ellinia`, `Global`, `Henesys`, `KerningCity`, `Ludibrium`, `MushroomShrine`, `NautilusHarbor`, `NewLeafCity`, `Perion`, `ShowaSpa{Female,Male}`, `Sleepywood` missing) | **DELIBERATE** — rewritten as data | `Gachapon`'s own class comment: "1.5.0: the per-town item lists that were Java classes here now live in `soloMapling/Gachapon/gachapon-pools.yaml`" (src/main/java/server/gachapon/Gachapon.java:38-40); pools in `GachaPools`/`GachaMachine`, operator commands `GachaRollCommand`/`GachaSimCommand`/`GachaCommand`, bots use `BotGacha` |
| Delegation refactors (body moved to a static/service helper, detector sees a short body) | `AcceptFamilyHandler.handlePacket` 8 vs 56 statements, `FamilyUseHandler`, `FamilySummonResponseHandler`, `MakerProcessor.makerAction` 51 vs 118, `WeddingReservationTask.run`, `ScriptedItemHandler.handlePacket` 4 vs 12, `PortalScriptManager.executePortalScript` 1 vs 6, `Channel.acceptOngoingWedding`, `RingActionHandler.breakMarriageRing` | **DELIBERATE** (mostly) | Read three: `AcceptFamilyHandler` moved its body to `AcceptFamilyHandler.answer(chr, inviter, accept)` so a bot junior can answer (src/main/java/net/server/channel/handlers/AcceptFamilyHandler.java:58-77, comment cites 1.6.1 ticket 53); `MakerProcessor` type-3 crystal path now calls `MakerService.makeLeftoverCrystal` "the same path the bot batch uses (ticket 112)" (src/main/java/client/processor/action/MakerProcessor.java); `ScriptedItemHandler` extracted `handle(c, itemSlot, itemId, info)` to send `enableActions` on every exit ("s-crystal", src/main/java/net/server/channel/handlers/ScriptedItemHandler.java) |
| Portal scripting | `PortalScriptManager.executePortalScript` 1 vs 6 statements, `scripts/portal/gendergo.js` 10 vs 22 lines | **DELIBERATE** — outcome reporting added | `runPortalScript` now returns `Outcome {RAN, REFUSED, NO_SCRIPT, ERROR}` and callers log it, added for 1.6.1 when the Rose Garden walk-in was silent (src/main/java/scripting/portal/PortalScriptManager.java:91-107) |
| Vote points | `gm3/GiveVpCommand` missing | **DELIBERATE** — renamed | `addCommand(new String[]{"givestamps","givevp"}, 3, GiveStampsCommand.class); // vote points became stamps (ticket 111)` (src/main/java/client/command/CommandsExecutor.java:478) |
| Inner portal | `InnerPortalHandler` missing, `USE_INNER_PORTAL` unregistered (`RecvOpcode` has the opcode) | **NOISE** for gameplay, small log cost | Upstream's `InnerPortalHandler.handlePacket` is an empty method (/root/workspace/cosmicms/src/main/java/net/server/channel/handlers/InnerPortalHandler.java:28-31), so registering it does nothing but suppress an unhandled-opcode line |

Remaining UNCLASSIFIED items not yet resolved (leads, each worth one read before anyone calls the file "base"):

| File | Finding |
|---|---|
| src/main/java/client/Character.java | `gainExp/5` 3 statements vs 9 upstream (line 3935) — Curse halving was restored here per the RESTORED list, so this is a two-way difference; `lines 4097-4100` commented-out block live upstream |
| src/main/java/client/Client.java | `checkHash/3` gone (hashing lives in `AccountPasswords`) |
| src/main/java/client/command/CommandsExecutor.java | `lines 321-323` commented-out block live upstream |
| src/main/java/net/server/channel/handlers/AbstractDealDamageHandler.java | `lines 677-712`, 20/24 commented lines live upstream |
| src/main/java/net/server/channel/handlers/FamilyUseHandler.java, `FamilySummonResponseHandler` | 23 vs 55 and 5 vs 18 statements |
| src/main/java/net/server/coordinator/partysearch/PartySearchEchelon.java | constructor `{}` (3 statements upstream) |
| src/main/java/net/server/coordinator/session/SessionCoordinator.java | `clearLoginRemoteHost/1` gone |
| src/main/java/net/server/handlers/login/LoginPasswordHandler.java | `hashpwSHA512/1` gone (same `AccountPasswords` move) |
| src/main/java/scripting/event/EventInstanceManager.java | `giveEventReward/2` 7 vs 25 statements |
| src/main/java/server/expeditions/Expedition.java | `scheduleRegistrationEnd/0` 2 vs 8 statements |
| src/main/java/server/maps/MiniGame.java | `shuffleList/0` 1 statement, `getCardId/1` 3 vs 7 (RNG/omok) |
| scripts/npc/gachaponRemote.js | `action` is `cm.dispose();`; 8 vs 30 lines |
| scripts/npc/9270043.js | 26% of lines shared with upstream |
| src/main/java/server/gachapon/Gachapon.java | `remote/1`, `pct`, `getLootInfo` were not matched to upstream methods (expected after the rewrite) |

None of these is claimed broken here: each is a lead with a command to settle it, e.g.
`diff -u /root/workspace/cosmicms/src/main/java/server/maps/MiniGame.java src/main/java/server/maps/MiniGame.java`.

## RESTORED (14) — already fixed, listed for context

These were stripped by the repack and restored on `release-1.4.2`/later; the tool no longer reports them: storage NPC
`scripts/npc/2110000.js`, Fredrick `scripts/npc/9030000.js` + `FredrickProcessor.fredrickRetrieveItems` lost
`merchant.clearItems()`, Curse EXP halving in `Character.gainExp`, `AutobanFactory.alert` logging, `@buffme` at GM 1,
summon damage clamp in `SummonDamageHandler`, Mana Reflection bounce in `TakeDamageHandler`, Papulatus gates
(`scripts/event/PapulatusBattle.js`), Rydole `scripts/npc/2040022.js`, quest end scripts `2001.js`, `3414.js`,
`3414_free10rate.js`, and the crit flag in `AbstractDealDamageHandler.parseDamage`.

## What this page does not say

- It compares **Java and scripts**, not WZ data or YAML content, so a subsystem built entirely on data files
  (`mechanics/content/*.yaml`, `cashshop/catalog.tsv`, `gachapon-pools.yaml`) shows as "custom" only through the class
  that reads it.
- `UPSTREAM-NEW` (57) means upstream added the code *after* the fork (about Aug 2023); nothing was stripped from us.
  A backport decision is the owner's, not a defect.
- The tool's UNCLASSIFIED count is a measure of the *detector*, not of our health: a delegation refactor (body moved
  one call away) lands there every time, and the `DELEGATION` regex only catches the single-statement form
  (tools/cosmicdiff/cosmic_diff.py:42).
