# Backlog after 1.4.0 (owner QA and requests, 2026-10-21)

## For 1.4.1
- **Coloring Prism (held back from 1.4.0).** QA marked Issue on dye cash weapon, effects layer, hair/eyes, colours
  staying, others seeing them; Fail on reset ("don't see a reset button"). Get the owner's details, fix, re-test.
- **Bots stuck on top of ladders.** Bots climb to a ladder's top and stay there. Owner: "quite funny, I remember doing
  this as a kid" - fix it, maybe keep it as a rare deliberate fumble (ticket 33 style), never a permanent stall.
- **4th-job bot attacks** (ticket 32, branch `bot-4th-job-skills` / client `bot-attack-diag`): AFK check with
  `!botshowcase 4th 10` + attackdiag log, then a day of watching bot EXP pace.
- **DONE (branch `botfixes-1.4.1`) - Channel load bars count bots in full.** ~1,000 bots after a restart filled
  every bar (CHANNEL_LOAD 100 x 8 channels), so the world looked full. Quick fix on live 2026-10-21: `CHANNEL_LOAD: 300`.
  Fixed: `Channel.getChannelCapacity` now counts a bot as 1/4 of a player (`-Dslumsms.channelLoad.botWeight=0.25`,
  clamped to 0..1, bad values fall back to 0.25), real players in full, bar capped at full (`ChannelLoadBarTest`).
  **Deploy note: when 1.4.1 goes live, set live's `CHANNEL_LOAD` back to `100` in config.yaml.** Login refusals and
  the high-population message still count real players only (getConnectedPlayerCount), unchanged.
- **Cash Shop slot expansion items have no icons** (9111000-9114000): add icons via Custom.wz.
- **Newer pet equips** (355 hidden in 1.4.0): the client's GetEquipItem null-reads drawing them. Find what their WZ
  needs (or keep them hidden).
- **Test notes page:** give set-bonus tests ready item commands (`!item` ids for one full set).
- **GM hide:** while hidden, bots still notice the GM and monsters stay frozen.
- **Release tools:** a check that a stray copy like the 1.4.0 `/files` incident can't recur (q() fixed in 40479d0c6).

## Later (social systems, owner's list)
- **Friends with bots:** add bots as buddies, whisper/talk, meet up, invite to party.
- **Marriages.**
- **Guilds** (bots in guilds, guild chat, players joining bot guilds).
- **Family system.**

## Next release: compare against stock Cosmic (owner, 2026-10-21)
The MapleRoot repack shipped ScrollHandler with its body removed (scrolling silently did nothing on live until 1.4.0).
Diff every class against upstream Cosmic (`/root/workspace/cosmicms`) for other stripped or hollowed code: bodies that
only `return`, files much shorter than Cosmic's (first pass found only ScrollHandler; EnterCashShopHandler and
EnterMTSHandler are deliberate rewrites), missing handlers/scripts, empty script functions. Sort each into
deliberate (MapleRoot/SlumsMS feature) vs broken, and restore the broken ones with tests.
