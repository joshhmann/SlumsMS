# 118: DPS meter, server notes

**Area:** Client / combat  **Status:** server side built (branch 161-dmgrank), not yet checked on a real client.

The client's DPS meter (client ticket 118) needs the party's damage on a mob. Contract with the Client Development session:

- LP `BOSS_DAMAGE_RANK` **0x3739** (was free: 0x3738 is the build stamp, 0x373A+ untouched).
- Payload: `int mobId; byte n; n x { int charId; mapleAsciiString name; long damage }`, sorted by damage descending, n at most 6.
- Source: `Monster.damageSnapshot()`, filtered to the hitter's party members. Bots in the party are ranked with their names.
- Sent from the hit path, at most once a second per mob (the first hit after a quiet second sends; the killing blow's
  final numbers are not guaranteed). Recipients: real players only (bots have no client), same map, in the party, have
  damaged that mob, client build >= `DAMAGE_RANK_MIN_BUILD` (default 10690; no released client handles 0x3739 yet, the real number comes with the client handler). No party = nothing sent.
- Which mobs: bosses, plus any mob with max HP >= `DAMAGE_RANK_MIN_MOB_HP` (default 5,000,000). Ask the client side if every mob is wanted.
- Switch: `DAMAGE_RANK_ENABLED` (default false) or `-Dslums.dmgrank=true`. On on TEST; in docs/release/1.6.1.conf SERVER_SET.
- Test: `DamageRankTest` (layout, throttle, recipients, sorting). Code: `server.life.DamageRank`, hook at the end of `Monster.applyDamage`.
