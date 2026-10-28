# Ticket 13: owned-alt foundation

This is the lifecycle foundation for ticket 13, not the completed Companions epic. It is disabled by default:
`ServerConfig.OWNED_ALTS_ENABLED = false`. No migration or client change is required. Only enable the setting on
an isolated TEST build after review; this work does not deploy, restart or change any running configuration.

`@botme` privately lists eligible offline characters on your account and world. `@botme <name>` loads one into
your channel/map and joins your party through the normal party primitive. You must lead a party with room;
the command creates one if normal party creation allows it. Call alts before entering an instance. Summoning
inside an instance refuses rather than bypassing that event's entry rules.

`@dismiss <name>` and `@dismiss all` return normal trade escrow, save synchronously, and remove the alt.
Owner logout/account changes and headless shutdown use the same lifecycle reservation. A real login saves and
removes an active alt before reloading its character row. Login refuses during loading, saving or a failed save;
retry from character selection after a pending save finishes. A failed save retains its character object and
reservation. The same owner can log back into their main character and retry `@dismiss <name>`; if that fails,
leave the alt blocked and investigate the database error. Never remove the reservation to bypass a save failure.

The reservation also covers the gap between a real login's database load and registration. There is one final
save per successful dismissal; retrying cleanup after a committed save does not perform another save. Normal
hourly autosaves remain available while an alt is active. Account-wide Cash Shop, monster book, storage and
quickslot snapshots are excluded from alt saves so they cannot overwrite the owner's newer account state.
Character EXP, mesos, inventories, skills and quests use the ordinary character transaction.

Identity is explicit: `Character.isOwnedAlt()` identifies an alt; `Character.isBot()` means a population character
for event/PQ script counting; `BotHelpers.isBot()` continues to mean headless for network/social guards;
`BotHelpers.isRosterBot()` selects population-only persistence, progression and generated trade stock. Alts never
enter `CharacterStorage` or roster session writes. They have private `BotClient`s whose player is the real alt.

The five-companion limit includes active and pending owned alts, recruited friends and pending friend accepts.
When enabled this replaces the old world-wide follower limit for player recruitment.

EXP (owner, 2026-10-26): an alt in your party gets the normal party EXP split with the usual level-gap rules and no
special leech handling either way - Monster's split decides who shares a kill, as for anyone, whether or not the owner
is alive or on that map. It stops only when the owner's session is gone. In a PQ an alt is a full member: it gets
the PQ's EXP (stage clears, `givePartyExp`, `giveEventPlayersExp`) and, because it cannot talk to the reward NPC or
use the reward portal, its reward from `giveEventReward` when its owner claims theirs. Outside an event instance
script and quest EXP stays blocked: quests are per character and an owner turning in a quest never completes it for
an alt. Offscreen EXP and population AP/SP/job advancement are blocked. Monster kills an alt shares advance the alt's own kill quests (owner 2026-10-26); it never auto-completes, the owner turns it in on the alt.

Formations, combat/loot drivers, chat orders, automatic trade acceptance/offers, supplies and PQ worker roles are
deferred. The normal trade inventory path is ready for those later drivers; this foundation does not automatically
accept a trade or transfer anything between inventories.

## 1.6.0.4: following and safety (owner approved the hotfix 2026-10-28)

- **Follow.** A summoned alt follows its owner (`GCMovement.follow` after the party join): same map through the 50 ms
  driver, across maps by walking through portals (GCFollow ~400 ms poll + GCTravel). An alt's trips never warp, pay a
  fare or board transit (`GCTravel.Trip.ownedAlt`: noWarp + noFares, boarding refused). Where it cannot walk (the owner
  used a scroll, the Free Market button, a scripted door) it waits and the owner hears once: "<alt> can't walk to where
  you are and waits here. Use @botme here ...". `@dismiss`, logout and every removal stop the driver first
  (`GCMovement.disable` in detach).
- **Instances.** An alt follows only where it is itself a member: never into an event instance (PQ, boss, event map) it
  did not enter with the party, nor onto a map an event owns (`GCFollow.altBlockReason`; same map id is not enough,
  an instance map shares its id with the public map). It waits outside and the owner hears so once. Alts called before
  a PQ entry are warped in by the event as party members and follow inside it.
- **No population side effects on the move.** The movement driver never hurts an alt (no contact/fall damage, so no
  HP, potion or bot-death changes), it has no persona reactions (no lines, drama, affinity or gifts), it never casts
  movement skills (no MP/potion use) and does not run the idle-safety walk.
- **Channel change.** Any owner channel (or world) change saves every alt as `@dismiss` does and removes it; on
  arrival the owner reads "Your alts went home. ...". A summon still loading when the owner changes channel is refused
  and never attached. Party EXP looks up the live owner by id (`Character.liveOwnedAltOwner`).
- **Party.** An alt that leaves or is expelled from the party, an owner who leaves the party their alts are in, or a
  disband sends those alts home (saved), and the owner hears "<alt> is no longer in your party, so it went home
  (saved)." Runs off the party thread; a dismissal's own party leave is not a second save.
- **`@botme here`** puts the owner's alts down beside them (same channel; never into an instance; an alt that is
  itself inside an instance stays) and re-arms the follow.
- **Invites.** Trade, party, guild and ring invites to an owned alt are refused with a line (an alt is headless and
  would leave the invite pending).
- **Threading.** `@botme` (list, summon, here) and `@dismiss` run on the ThreadManager pool and reply when done; the
  lifecycle reservation still decides races (two summons of one alt load it once).
- **Messages.** The cap line counts bot friends; `@dismiss` on a bot friend says to expel it from the party; the summon
  line says a party was created when it was, and that changing channel sends alts home.

EXP split, PQ rewards and every other economy rule are unchanged.

## TEST checklist (not run during implementation)

Start `!qa run oa-1` with `-Dslums.qa=true` and owned alts enabled. The scenario adds instructions and captures
`OWNED_ALT` lifecycle events; it does not create characters or mutate your inventory. Use a disposable account
with at least two characters in world 1. Stop with `!qa stop` after dismissing every alt.

1. Log into the alt normally and record EXP, level, mesos, item IDs/quantities, skills and quest state. Log out.
2. On the main, use `@botme`, then `@botme <alt>`. Verify one character appears in the same channel/map and party.
   Repeat the summon: it must refuse. Characters from other accounts/worlds and online characters must refuse.
3. Earn a party kill near the alt. Verify normal party EXP, also with the owner dead or on another map; log the owner out and verify no
   additional EXP. Check a level-up leaves AP/SP unspent and does not advance jobs or quests.
4. Check the five-companion cap using both alts and friend followers, including simultaneous friend accepts.
5. Dismiss the alt. The QA log must show one `save committed`, then `removed`. Log into it normally and compare
   its state with the expected party gains. Inventory and mesos must match; no items are created by summon/dismiss.
6. Repeat summon -> owner logout -> immediate alt login 50 times. A pending save may refuse login; retry from
   character selection. Check inventory checksums and one final committed save per lifecycle.
7. Check `@dismiss all`, changing accounts and shutdown on a disposable TEST instance. Every alt disappears and
   saves once. No background progress after logout. Do not use a running shared QA session for shutdown testing.

Automated local tests cover state-machine contention with latches, handler save-before-load ordering, save
failure/retry, cleanup retry without another save, the actual character SQL transaction (mock JDBC), private
commands, population exclusions, friend capacity and real-inventory trade receipt/cancellation. They do not
replace MySQL integration, the 50-cycle TEST exercise, or a real-client look. Trade acceptance/offer and PQ play
need the subsequent companion drivers before end-to-end testing is possible.
