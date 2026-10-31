# 161: Bot build variety and party support

**Status:** open, 1.6.2. **Owner:** Server Dev. Source: owner, 2026-10-30, after the nutnnut/Cosmic README_SERVER_BOTS.md review ("what we havent adopted yes update tickets or create new ones"). Upstream is AGPL-3.0 like ours: credit `nutnnut/Cosmic <sha>` in port commits and docs/PROVENANCE.md. Matrix: 157-nutnnut-tools/adoption-matrix.md.

1. **AP build variants** (S-M): dexless, lukless, strless, pure and "25 dex" builds as persona traits, so some
   regulars play like real players who chose them (BotBuild today has one target per class).
2. **Party buffs and heals** (S-M): bots buff and heal their party with their own skills and use buff pots
   (BotBuffRequestSystem/BotBuffDriver exist; upstream BotBuffManager is fuller).
   - 162-healamt (owner 2026-10-30): a bot's Heal heals like a player's: every party member in the Heal's box and
     the caster get max HP x hp% / (people healed), through the same StatEffect selection and formula (it used to
     give one member the whole single-target amount). A real player's Heal now also sends the cast effect
     (SHOW_FOREIGN_EFFECT effect 1) to the rest of the map, so other players see the Heal column; bot and player
     casts send the same packets. Switch: `slums.bots.partysupport` (on in 1.6.2.conf). Tests:
     BotPartyHealCastTest. Not yet checked on a real client.
3. **Fame on request** (S): a friendly bot may fame a player who asks, with limits.
4. **Magic Guard check** (S): confirm our combat formula splits damage like upstream 930ca5a01.
Tests per item; buffs must be visible on a real client (client remote-attack rule).
