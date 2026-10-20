# 40: Player guidebook and server wiki

**Area:** Content / Docs
**Depends on:** 1.3.0 (features to describe); 37 (chat features change how talking works)
**Status:** open

## Today

- New characters get the stock v83 guide items: Beginner's Guide (4161001, `BeginnerCreator`), Noblesse Guide
  (4161047) and Legend's Guide (4161048). Their pages are client data (Item/String WZ), so they describe GMS, not
  SlumsMS.
- Player-facing information is spread over the launcher news, the repos' READMEs and chat with the owner. There is no
  page that explains the server.

## What

1. **In-game guidebook (client + server):**
   - Rewrite the guide's pages for SlumsMS: rates, the Free Market and Arcane hubs, rebirths, hyper/5th skills, the Job
     Master and other MapleRoot NPCs, the living bot population, how to talk to bots (say their name, whispers, the
     talk window), party/trade with bots, megaphones, commands players can use.
   - Ship the new text through the client's `Custom.wz` overlay (client layer release). Keep the item ids, so every new
     character still gets it.
   - Optional: a "Guide" NPC or `@guide` command that opens the same pages server-side as an NPC window, so content can
     change without a client release.
2. **Wiki / player guide site:**
   - A small static site (or a Docs artifact first) the owner can edit: getting started (launcher, access key, the
     main-display tip for streamers), server features, rates, leveling path by level and job (from ticket 31's data),
     money making (ticket 38 research), boss and party-quest notes, bots FAQ ("are they real?" stays in character:
     describe them as the town's regulars), rules, known issues, changelog (from the launcher news).
   - Generated pages where possible (rates from config, leveling spots from the YAML, changelog from release news) so
     it doesn't go stale.
   - Hosting: behind the same Caddy as the patch server, public or friends-only (owner decides), or as a page the
     launcher's news panel links to.
3. **Keep it current:** each release's checklist gets "update the guide/wiki" next to the news entry.

## Done when

A new character opens the guidebook and reads about SlumsMS, and friends have one link that explains the server,
what's running and how to play with the bots.
