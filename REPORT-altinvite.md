# e02 — alts accept their own owner's invites (family, guild)

Branch `164-altinvite`, off `origin/release-1.6.4` (`c0e0a2cd7`). Worktree `/root/work/164-altinvite`, `remote.origin.pushurl` set to `DISABLED`; nothing pushed.

## What changed

| Invite | Before | After (switch on, inviter is the alt's owner) |
|---|---|---|
| Party | already handled: a name that is not online calls the alt (`OwnedAltInvite.onInviteNotFound`, ticket 99) | unchanged |
| Family | `FamilyAddHandler` only recognised `isBot()`, which is false for an owned alt (`client/Character.java:713-715`), so the invite went to a `BotClient` that drops every packet (`client/BotClient.java:26-28`) and expired | the alt answers through `AcceptFamilyHandler.answer(alt, owner, true)` — the same body the accept packet runs |
| Guild | `Guild.sendInvitation` refused it outright: `OwnedAltService.interactionRefusal(...)` → "Alt2 is your alt and can't answer a guild invitation…" (`net/server/guild/Guild.java:797-802`) | the alt answers and joins through `GuildOperationHandler.joinAfterInvite` — the accept packet's own body, so member row, guild info, tooltips and the bot guildmates' welcome are the human ones |
| Wedding | no invite kind exists to accept: `InviteCoordinator.InviteType` has no wedding entry, `WeddingHandler` has modes 6/7 only (gift items in, take items out), and guests enter a wedding through NPC script state (`scripts/npc/9201002.js:27`, `scripts/npc/9201005.js:27`, `scripts/event/WeddingCathedral.js:87` `canJoin`) — there is no packet to answer and no accept path | **left out**, as instructed: nothing to reuse, nothing invented |

New file/API surface: `soloMapling/companions/OwnedAltInvite.java` gains `OWNER_INVITE_SWITCH`, `ownerInvitesAccepted()`, `acceptsOwnerInvite(inviter, target)`, `acceptFamily(alt, owner)`, `acceptGuild(alt, owner)`. `net/server/channel/handlers/GuildOperationHandler.java` gains `joinAfterInvite(Character mc, int gid)`, and its `0x06` branch now calls it (no behaviour change for a human accept).

Every other inviter is still refused: the gate requires `target.isOwnedAlt()`, `!inviter.isOwnedAlt()`, `!inviter.isBot()`, and the alt's owner id == the inviter's id. A population bot, a sibling alt, another player, or another player's alt all get the old path (refusal line for guild, fizzled packet for family).

## The switch

`-Dslums.ownedalts.acceptownerinvite`, **default off** (`OwnedAltInvite.ownerInvitesAccepted()`, a `-D` property and not a `config.yaml` key, for the reason `CompanionSwitches.lanes()` records: an unknown YAML key stops a server booting). Read on every use, so a TEST restart with the flag is enough.

## Commits

- `6bc8e2a93` guild: the accept packet's body is a method, `joinAfterInvite` (extraction only, no behaviour change)
- the e02 change itself: `OwnedAltInvite` helpers + the two invite senders + `src/test/java/soloMapling/companions/OwnedAltOwnerInviteTest.java`

## Fail-before evidence

`OwnedAltOwnerInviteTest` (5 tests, `mvn -o -Dtest=OwnedAltOwnerInviteTest test`):

- `offByDefaultTheOwnerInviteIsStillRefused` — switch off, `Guild.sendInvitation(owner, "Alt2")` still produces the 1.6.0.4 refusal line and never reaches the join; `InviteCoordinator` is not touched.
- `offByDefaultTheFamilyInviteIsStillSentToAHeadlessClient` — switch off, the family invite packet goes to the alt's `BotClient` and nobody answers.
- `anAltAnswersItsOwnOwnersGuildInvite` — switch on, the invite is created and `joinAfterInvite(alt, guildId)` runs; no refusal line.
- `anAltAnswersItsOwnOwnersFamilyInvite` — switch on, `FamilyAddHandler` answers through `AcceptFamilyHandler.answer(alt, owner, true)`.
- `everyOtherInviterIsStillRefused` — population bot, sibling alt, another player's alt, nulls.

Full suite: `mvn -o test` → `Tests run: 8366, Failures: 0, Errors: 0, Skipped: 137`, BUILD SUCCESS (27:37).

## What a human does on TEST to see it

The switch is a boot flag: restart TEST's server with `-Dslums.ownedalts.acceptownerinvite=true` (no `config.yaml` edit).

1. Log in on a character with an alt of your own. Summon it: `@botme <altname>` — the alt stands next to you and follows. Family and guild invites only reach a character that is online in the channel, so the alt has to be out; the party "invite a name of an offline alt" path is a different feature and unchanged.
2. **Family**: the alt has to pass the family rules — same map (it follows you), level above 10, and within 20 levels of you. Open the Family window, *Add junior*, type the alt's name, invite. With the flag on the alt becomes your junior at once (the Family window and `/family` show it); with the flag off the invite sits and expires.
3. **Guild**: you must be rank 1-2 and the alt must not be in a guild. Guild window → *Invite member* → type the alt's name. With the flag on there is no yellow refusal line and the alt is in the guild at rank 5 (window shows the member, guild name appears over it); with the flag off you get "Alt2 is your alt and can't answer a guild invitation while it travels with you."
4. Negative check: have a second player invite your alt to their guild, or invite their alt to yours. Both still get the 1.6.0.4 refusal with the flag on.

No client-side UI is involved beyond the two windows already in the game.

## Not done / noted

- Wedding is out of scope (above): no invite kind, no accept path.
- The family answer runs on the packet thread as the accept packet's body does (the same DB write a human accept performs); nothing else was moved off it.
- No `docs/` or config file changed: the switch is a `-D`, so nothing has to be added to `config.example.yaml`.
