# 156: Bots speak and act from one "state of mind"

**Status:** open, 1.6.2 (owner 2026-10-30: "yes"). **Owner:** Server Dev. **Fixes:** b099 (made-up maps and mobs),
b100 (false familiarity), b101 (answers contradict the bot's own state), b102 (haggling loop). Scope beyond the
snapshot and checker goes to the owner in the next grill.

## Why

Owner, 2026-10-30, after chatting with regulars on TEST: bots need "some state of mind -> where am i, whos this
person im talking to or playerbot, what am i doing, what do i need or want to do, its like some goap system based on
current playerbot status. we do have a flavor of this but think we need to align it better with how the bot responds
and acts."

The pieces exist (life profile and activity, goals that become plan steps shown by `!botwhy`, memory notes and
gossip, affinity, crews and rivals), but a chat reply only gets part of them and nothing checks the reply against
them. The chat model fills the gaps by inventing things; the scripted lines don't see the conversation (the price
loop).

## What to build

1. **One snapshot per bot, built fresh for every reply and every decision:**
   - Where am I: real map name and region, the monsters actually on this map, town or field.
   - Who is this: met before or not (from memory), what I remember, affinity, crew/rival/friend; their real level
     and job, never guessed. Player or bot.
   - What am I doing: current activity and plan step (grinding X, selling Y at Z mesos, heading to W).
   - What do I want: current goal and what would help (party, item, price, a trip).
   - This conversation: the last few lines, any open offer with its price, what I already said.
2. **Speak only from the snapshot.** The chat model's prompt carries the snapshot and the rule "never claim a
   place, monster, level, history or price that isn't in it". Scripted lines fill their slots from it.
3. **Checker:** a reply that names a map or monster not in the snapshot, says "again" or recalls details with
   someone the bot never met, contradicts its own activity or level, or ignores an open offer is rejected and
   regenerated (then falls back to a safe scripted line).
4. **Act from the same snapshot.** Yes/no answers, haggling and trips use the same state the reply used, so what a
   bot says matches what it does (builds on the say-do work, b011/b068).
5. **Tests:** each of b099-b102's transcript lines becomes a fail-before case; an auto-QA chat-grounding check over a
   day's transcript (share of replies with a claim the snapshot can't back).

## Also noted (owner 2026-10-30)

NPCs that don't talk (owner: "to the ones that dont talk"): every NPC players can click that has no script and logs
"NPC ... is not coded" (e.g. Legato 3003111, Dame Appropriation 9010106, Squire Pancho Sanza 9010109 in Arcane River).
Build the list from the TEST journal's "is not coded" lines plus a scan of map NPCs without a script; research what
each did in the original game (game-reference skill); decide per NPC with the owner: script it, give it a line, hide
it, or leave it.

## Auto-QA (ticket 164 b224, 2026-10-30)

Branch `162-qaharden`: **t-gr-1** (no chat model) proves a fresh bot's snapshot holds its real level, map and the
player, the checker passes true claims and rejects a wrong level, a far town and "again" to a stranger (counted), and
the delivery path swaps an untrue scripted line for a safe one. **t-gr-2** (live model) asks a fresh bot its level
(must say the real one) and forces one untrue model draft through the reject and retry (never said). The day's
transcript share in item 5 is still ChatQuality's "grounding rejects" headline, not a check of its own.
