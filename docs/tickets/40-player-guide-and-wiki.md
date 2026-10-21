# 40: Player guidebook and server wiki

**Area:** Content / Docs
**Depends on:** 1.3.0 (features to describe); 37 (chat features change how talking works)
**Status:** text and wiki shipped in the v1.4.0 tree (branch `guide-wiki`): guidebook source + compiler, client-step brief,
static wiki; not published anywhere; the client release (Custom.wz) and the owner checks below remain

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

## Progress (branch `guide-wiki`, 2026-10-20)

- **Guidebook text:** `docs/guidebook/guidebook.yaml`: 26 pages, 12 chapters, one two-page spread each.
  - The chapters: what SlumsMS is, rates, Base of Roots and the FM, hub NPCs, jobs and the Job Master, rebirths,
    the 5th tier, the town's regulars, talking to them, megaphones and `@quiet`, commands, getting help.
  - `python3 docs/guidebook/compile_guide.py` checks page sizes and links. It writes WZ XML for `Custom.wz` under
    `docs/guidebook/build/Custom/`, plus `build/preview.html`.
- **Client step:** `docs/guidebook/CLIENT-STEP.md`. In short:
  - All three creators give 4161001. 4161047 and 4161048 are never handed out.
  - The book text lives in `Item.wz/Etc/0416.img/04161001/book/<page>/<block>/text`. MapleRoot already replaced
    it with its own "MapleRoot Guide": 22 pages, with wrong rebirth rates for SlumsMS.
  - The tooltip lives in `String.wz/Etc.img/Etc/4161001`.
  - Open question for the client step: Kaentake's Custom.wz serialize hook adds values with `Add(..., false)`, and it
    must be tested whether that overwrites existing pages.
- **Wiki:** `docs/wiki/`. It is plain HTML and CSS with no scripts and works on phones.
  - Rebuild with `python3 docs/wiki/build.py`. Preview by opening `docs/wiki/index.html`, or run
    `python3 -m http.server -d docs/wiki`.
  - Generated pages:
    - `leveling.html` from `LevelingPath.yaml`;
    - `travel.html` from `BotTransit.yaml`, with names from `docs/wiki/data/names.json`, which
      `build.py --names-from <server wz>` refreshes;
    - `guidebook.html` from the guidebook source.
  - Hand-written pages (`docs/wiki/src/`): home, getting started, rates, hubs, jobs, rebirths, the town's regulars,
    commands, making mesos, FAQ and known issues, changelog.
  - Facts that need the owner are marked "owner check" on the pages.
- **Still to do:**
  - The client release.
  - Hosting: the owner decides between public, friends-only behind Caddy, or a launcher news link.
  - Changelog text from the launcher news JSON.
  - Rules.
  - Boss notes.
  - The optional `@guide` NPC window.
  - Add "update the guide/wiki" to the release checklist (ticket 44).
