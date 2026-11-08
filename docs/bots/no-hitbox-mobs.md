# ht-body-no-hitbox: bots never target a mob a player cannot hit (1.6.6 + 1.6.5.1 candidate)

Written before the code. Coordinator GO 2026-11-08 04:2x UTC. Base: release-1.6.6 @ 84a97be27; must cherry-pick
cleanly onto release-1.6.5 @ 83f363df9.

## What happened

TEST, 04:14:58 UTC Nov 8: a Horntail expedition "cleared" while the visible parts were alive. The alts and regulars
had targeted Horntail's invisible linking body 8810018 ("turning to face 'Horntail'", "no lt/rb bounds for mob
8810018"). Its death runs `HorntailBattle.js` monsterKilled -> clearPQ with the full rewards.

Why bots can pick it:
- `BotAttackDriver.botMayTarget` refuses only the transparent-item mob and friendly mobs.
- A swing collects its hits by the mob's centre point, not its hitbox (`mobsInReach`), and the body stands at the
  spawn point in the middle of the room.
- Regulars in a run take the nearest mob (`FollowerFight.nearest`), and alts take any mob the party has hit.

A real client can never hit 8810018: its WZ image has no `lt`/`rb` box in any frame.

## Expected outcome

1. `botMayTarget` also refuses a mob whose WZ image exists and has no `lt` box anywhere (its own image, or the image
   it links to). That is a mob no player can click or swing at. 8810018 is refused. So are Pink Bean's controller
   bodies 8820010-8820014 / 8820019-8820027 and the dead-part images 8810010-8810017.
2. Every visible part stays a target: Horntail Head A 8810002 and the pre-heads 8810000/8810001; Zakum's bodies
   8800000-8800002 and an arm 8800003; ordinary mobs.
3. A mob whose WZ image can't be found (no WZ, mock monsters in tests) is NOT refused, so nothing changes where the
   data is missing.
4. One method covers every bot path: the swing's hit list, the alt's target, the regulars' nearest, the PQ
   executor's pickTarget, and the idle and contact checks all read `botMayTarget`.
5. Cost: one parse per mob id per boot, cached. After that it is a map lookup.

## Proof

`NoHitboxMobsTest`, which needs the WZ data:
- 8810018 is refused by `botMayTarget`, while 8810002, 8810000, 8800000, 8800003 and a Snail 100100 are allowed.
- A mob id with no WZ file is allowed.

The 8810018 assertion fails before the change and passes after.
