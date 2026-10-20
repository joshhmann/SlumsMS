# 05: Persist real stats: skills, AP/SP, job

**Area:** Real bots  
**Depends on:** 01  
**Status:** done 2026-10-19 on branch `real-stats`, not yet merged

## What

botstore gains skills, AP/SP and job progress (migration 005+). Existing bots are back-filled from level, job and class build.

## Done when

A restart keeps each bot's skills and AP/SP; migration plus rollback; tests.

## Result (tickets 05 + 06 together, branch `real-stats` @ 23d42a67a, not merged)

- **Migration 005:**
  - `botstore` gains Str/Dex/Int/Luk, MaxHp/MaxMp, Hp/Mp, RemainingAp, Sp (comma format) and BuildLevel.
  - New table `botskills`.
  - Idempotent, with a rollback.
- **`BotBuild` / `BotSkillPlan`:**
  - Stock Explorers, Cygnus and Aran up to level 200; other jobs are refused and left untouched.
  - Advancement at 10 (8 for mages) / 30 / 70 / 120. The branch comes from the weapon, otherwise a stable pick.
  - AP: the secondary stat to its target first, then the primary.
  - SP: attack, then mastery/booster, then buffs, capped at the server's max.
- **Live progression:** both gain paths (observed and offscreen) spend AP/SP and advance jobs. Stored bots without a
  build get one once.
- **Attacks** use the skills the bot holds.
- **Tests:** 2394 run, 0 failures (74 new).
- **Headless check on 164:** 712 bots built; 501 reloaded identical after a restart.
- **Open issues:**
  - MapleRoot's Skill.wz differs from `constants.skills`: some ids are different (Power Strike, Slash Blast, Iron
    Body, Nimble Body, Keen Eyes, NW skills), and many stock skills are missing (boosters, Big Bang, Heaven's
    Hammer, Assassinate...). `BotAttackConfig`'s fixed table still uses the stale warrior ids — needs a decision.
  - Cygnus and Aran have no attack-table entries (basic swing).
  - `BotBuffConfig` doesn't check the skills a bot holds.
  - Levels set directly (OPQ re-roll, GM spawns) catch up only at the next level-up or reload.
  - `replaceBotSkills` races with readers, the same as the existing `changeSkillLevel`.
