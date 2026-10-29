# 13: Owned alt-bots (alts as companions)

**Area:** Companions  
**Depends on:** 05  
**Status:** full alts built on branch `161-alts` (2026-10-29), not merged, not on TEST; live keeps `@botme`
summoning off (`OWNED_ALTS_SUMMON_ENABLED=false` in 1.6.1.conf) until the owner says ship

## What

A new identity kind: a real `characters` row loaded through BotClient, saveCharToDB allowed, outside the roster, director and 1e9 range. Commands to summon your alt.

## Done when

An alt joins your party as a bot and logs in normally later with its progress kept.

Parent: ticket 99 (Companions epic).

Owner 2026-10-25: in 1.6.0 as a child of ticket 99.

## Foundation checkpoint (branch `160-owned-alts`)

The default-off identity, same-account/world load, gm0 summon/dismiss commands, synchronous final save,
login reservation/takeover, owner logout/account cleanup and shared five-companion capacity are implemented.
Population progression, persona chat and generated trade stock exclude alts. Local unit tests cover save and
login races; no deployment or real-client validation has been performed.

This ticket remains open for companion drivers and TEST acceptance. Following, formations, combat/loot,
automatic normal-trade offers, and PQ roles are later work. See
[`docs/qa/owned-alts-foundation.md`](../qa/owned-alts-foundation.md) for identity contracts and `!qa run oa-1` checks.

## 1.6.0.4 hotfix (owner 2026-10-28)

Alts now follow their owner (same map and through portals, never into an instance they did not enter, never by
warp, fare or boat), a channel change or leaving the party sends them home saved, `@botme here` brings them over,
invites to an alt are refused, and `@botme`/`@dismiss` run off the packet thread. See the 1.6.0.4 section of
[`docs/qa/owned-alts-foundation.md`](../qa/owned-alts-foundation.md). Formations, combat/loot and PQ roles remain open.

## Full alts, 1.6.1 (owner 2026-10-28; branch `161-alts`, 2026-10-29)

Owner: "if they don't fight or anything I'm not sure if we should have shipped it. Would of rather fully implemented
it." The spec: alts **follow** and **fight**, **loot**, **take damage and can die**, behind an **activity gate**.

### Status

Built on `161-alts` (off release-1.6.1 96bd46317; the 1.6.0.4 follow commits 1e2314a0e and 116b6a8fb cherry-picked
first). Unit tests pass; the full suite result is in the commit. **Not merged, not on TEST, not seen in a real client.**
Switches: none new. TEST runs it with `OWNED_ALTS_SUMMON_ENABLED` at its code default (true); 1.6.1.conf keeps it
**false** for live until the owner decides. Check: auto-QA **t-oa-1** (extended, below), `!qa run oa-1`.

### What an alt does

1. **Follow** (1.6.0.4, cherry-picked): tails its owner on the map and through portals and towns by walking; never
   into an instance it did not enter with the party, never pays a fare or boards. New: an alt **left behind** (a boat,
   a dead end) is put down beside its owner after 8 s of not walking or 45 s in all (as `@botme here`; same channel,
   never into or out of an instance). A channel change or leaving the party sends it home (saved).
2. **Fight** (`OwnedAltCompanion`, `OwnedAltCombat`): it attacks a mob its owner or party (itself included) damaged,
   or a mob that just touched its owner or itself, within 650 px of its owner; it walks into reach and swings through
   the same attack system and packets the on-screen bots use (`BotAttackDriver`: its class skills at its skill
   levels, its real stats and gear, its MP and ammo, the skill-vs-weapon pose rules the client checks). It keeps its
   own weapon (`BotWeapons.ensureFittingWeapon` never hands an alt one); with no weapon, or one the client cannot
   draw, it does not fight and says so once. **KS rules:** it never touches a mob a real player outside the party (or
   their alt) has hit, a mob a population bot is on before the party, or a mob a real stranger stands by before the
   party touched it - and that holds for every mob its area attacks catch (a per-bot target filter in
   `BotAttackDriver`). **Never in a town.**
3. **Loot** (`OwnedAltLoot`): its own drops and its party's (by the drop's owner), through the player pickup path
   (`Character.pickupItem`: loot rules, quest items it needs, party meso split) into **its own** inventory. Never a
   stranger's drop (even after its ownership window), never an item a player dropped, never a fresh drop still
   settling. A drop its bag has no room for stays on the ground and the alt says so once in party chat ("My Etc
   inventory is full, I'm leaving those drops on the ground."), again only after it had room.
4. **Damage and death**: mobs touching it hit it for real (the on-screen bots' contact damage: knockback, the hurt
   packet, off its own HP); it drinks its **own** potions with the bots' potion logic (`BotVitals`) and recovers HP/MP
   like a player between fights. A hit that takes it to 0 **kills** it through the game's death path (the normal EXP
   penalty; a Safety Charm works). It lies a few seconds, gets up in its map's return town with 50 HP, and goes home
   saved; the owner is told ("<alt> died and lost EXP, as a player does. It got up in town and went home (saved).
   Call it again with @botme <alt>."). No fall damage (the movement engine's physics, not the game's).
5. **Activity gate** (`OwnedAltGate`, all must hold, checked every 200 ms; otherwise it only follows):
   - its owner is online, on the **same map** (the same instance), **not a town**;
   - within **900 px across / 500 px up-down** of its owner (about a screen);
   - its owner made a player action (move, attack, skill, item, chat) in the last **3 minutes**
     (`-Dslums.alts.idleMs`); an idle owner hears once "Your alts stopped fighting: you have been idle for a while.
     Move or attack and they join in again.";
   - at most **6 alts fight on one map** at once, all owners together;
   - per owner: the shared **5 companions** (alts plus bot friends), as before.
   - Anti-abuse: alts never trade, open or use shops, take party/guild/ring invites (refused), or play a PQ alone (an
     alt whose owner left the instance does nothing there). Population systems (offscreen EXP, progression, persona
     chat, trade stock) still exclude alts; EXP only through normal party play while the owner is online.

### Auto-QA t-oa-1 (extended)

Besides the setup checks (list, summon onto the owner's map and party, follow, through a portal, duplicate refused,
cap 5, dismiss saves): on Henesys Hunting Ground I (104040000; `@botme here`; a Sword is given once if the QA alt
holds none) (1) a Blue Snail spawned on the alt **damages it**; (2) a Blue Snail the owner hits is **attacked and
damaged by the alt**; (3) a staged party drop beside it lands **in the alt's own ETC bag**; (4) with the owner idle
(QA switch `OwnedAltGate.forceIdleForQa`) the gate reads OWNER_IDLE and the alt **does not swing** at a mob its owner
hits, then fights it once the owner acts again.

### Needs a real client (the owner's screen)

How the alt's attacks look to its owner (poses, skill effects, damage numbers; the client drops a wrong pose/weapon
attack), mobs turning on it (mob aggro is the controlling client's), its knockback and death pose, a boat catch-up,
and a PQ with alts. Death, potions and catch-up have unit tests only.

### Player notes draft

**Your alts fight with you.** Call one of your other characters with `@botme <name>`: it joins your party and
follows you everywhere you walk (through portals and towns; after a boat it catches up when you arrive). On a
hunting map it fights what you fight - the monsters you hit and the ones that hit you or it - with its own skills,
stats, MP and potions, and it picks up its own and your party's drops into its own bag (it tells you when its bag is
full). It never steals another player's monster or loot, and never fights in town. Mobs hit it, and it can die: it
loses EXP like you would, wakes up in town and goes home; call it again with `@botme`. Alts play only while you do:
they stop fighting if you stand idle for 3 minutes or wander off a screen away, and start again when you move. Up to
five companions (alts and bot friends together), and at most six fighting alts on a map. `@botme here` brings them to
you, `@dismiss <name>` sends one home (saved).
