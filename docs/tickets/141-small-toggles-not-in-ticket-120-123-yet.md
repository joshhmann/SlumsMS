# 141: Small toggles not in ticket 120/123 yet

**Area:** client
**Rank:** 13 of 25 in the PlayKuro client research
**Status:** open (proposed 2026-10-28, not scheduled)

Source: PlayKuro client research (owner request 2026-10-28), static reading only; full note in docs/research/kuro-client-features.md. Ideas only: we build our own version, never copying their art, scripts or code.

## Research

- **For players:** hide the **quest lightbulb**, show/hide **[MapleTip]** lines, show/hide the "not enough HP/MP to use
  this skill" chat warnings, **summons drawn behind the player**, **hide other players' full-body anime cash
  costumes**, and a **cap on how many other players animate fancy cosmetics at once** (your own always animate).
- **Evidence:** "Hide Lightbulb (Quest Helper)", "Show MapleTips in chat", "Show HP/MP skill warnings in chat" (in
  chat_config.ini: showMapleTips, showSkillHpWarningChat, showSkillMpWarningChat), "Summons behind player / Re-summon
  after changing the layer order", "Hide anime cash skins on other players", "Max animated players: How many OTHER players
  may animate their dyes at once". Opacity keys `ChairOpacity`, `OwnDamageOpacity`, `MobEffectOpacity`, `PetOpacity`,
  `SummonOpacity`, `SkillOpacity`. Their note: "Boss and monster attack effects are never affected" and "Smoke Screen's
  smoke always stays visible", so gameplay-relevant effects stay visible.
- **Size:** S each. Add these items to ticket 120's scope. The animation cap pairs with our Coloring Prism.
