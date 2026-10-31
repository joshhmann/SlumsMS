# What the owner checks by hand today, and what a machine can take over (ticket 166/170)

Source: the 1.6.2 release book's **Test** tab (62 rows, read 2026-10-30 evening PT / 2026-10-31 UTC), `docs/qa/QA.md`
and the auto-QA catalog's HALF checks (the look is left to a person) and MANUAL checks (client-only).

Two labels:

- **script** - a client script (or, for website rows, a browser script on the TEST site) can do it: the steps are
  mechanical and the result is a line, a number, a packet, or something an agent can see in frames.
- **judgment** - needs a person's feel, taste or fun. The machine still stages it and records frames/lines, so the owner
  looks at a run folder instead of setting it up; but the verdict stays the owner's.

The owner's hand list shrinks to the **judgment** rows (11) and the launcher's own checklist (1).

## Totals

| source | rows | script | judgment | stays by hand (other) |
|---|---|---|---|---|
| Release book Test tab, game | 46 | 39 | 7 | 0 |
| Release book Test tab, website | 16 | 14 | 2 | 0 |
| Auto-QA HALF checks (the look part) | 16 | 14 | 2 | 0 |
| Auto-QA MANUAL checks | 11 | 10 | 0 | 1 (t-la-1, the launcher) |
| **All** | **89** | **77** | **11** | **1** |

Scripts written so far (drafts, validated, not yet run): b140, b233, KPQ stage 1 with a real leader, @botme basics and
the ten MANUAL checks below. Everything else marked **script** still needs its script written.

## The judgment list (what stays with the owner)

| id | check | why a person | what the machine still does |
|---|---|---|---|
| q04 | New-area monsters feel right | "feel right" is balance taste | logs HP, EXP per kill and drops for each mob (@mobhp, @wdf) |
| q18 | Bots talk to each other | whether it reads like people | stages bt-1, records the chat and the BT1 PASS lines |
| q19 | Bots remember chat and whispers | whether the follow-up "makes sense" to a player | sends the two lines and records the replies |
| q20 | Trash talk vs real threats | tone of the reaction | sends both lines, records replies and the affinity lines |
| q31 | General look and feel (rc11 client) | feel | crash check and frames of each window it opens |
| q45 | Kerning PQ with bots | "bots look like players working it out" | `kpq-stage1-leader` clears the stage and records it |
| q49 | Scrolled-gear prices look right | economy taste | the price report and bot-shop listings |
| qw06 | Admin dashboard in the site's colours | visual taste across themes | screenshots in light/dark and each accent |
| qw10 | Slums Exchange layout ("lots of whitespace") | visual taste | desktop and phone screenshots |
| t-cv-4 | Four voices, human timing | casing and slang read as four people | the lines side by side with timing |
| t-lk-1 | Bots camp AFK, walk when watched, type like 2009 players | "like 2009 players" | the scene staged and recorded |

## Release book Test tab, game (46)

| id | check | label | how a script does it / note |
|---|---|---|---|
| q01 | 120 areas open at level 120 (or 1st rebirth) | script | world-map clicks at lv119/120/rebirth; the refusal notice text vs the landing map (`field.ready`) |
| q02 | Gate of Time offers Leafre of the Past | script | walk into the portal, npc dialog menu frames, pick line 3, `field.ready 272000000` |
| q03 | Deeper Grandis and Arcane wait for rebirths | script | as q01 with rebirth 0/1/2; refusal texts are exact |
| q04 | New-area monsters feel right | judgment | see above |
| q05 | Lion King's Castle and Rose Garden | script | portal and Up on next00, `field.ready 211080000`; HP/EXP numbers from chat (marked Fail today) |
| q06 | Leafre / Minar, Temple of Time, Ulu +40% EXP | script | spawn, kill, read the EXP gain chat line vs the expected numbers |
| q07 | Cygnus and Magnus HP | script | @bosshp chat line equals 110,000,000 / 2,100,000,000 |
| q08 | Achievements and medals | script | kill Mano, chat "Mano Vanquisher", @achievements lines, equip-tab frame |
| q09 | Family perks | script | fy-1 staging, Family window anchors, perk lines (needs window anchors) |
| q10 | Slums Stamps and Rooty's shop | script | !givestamps, Rooty's header text, balance after each buy |
| q11 | Slums-chan | script | new character first login (`new_character`), dialog window, relog: not shown |
| q12 | Start on Maple Island | script | new character, Cody's Yes, `field.ready 10000`, Shanks' lines, mesos unchanged |
| q13 | @invite | script | three @invite calls, chat lines exact (needs a non-GM vault char) |
| q14 | @bug | script | chat "Bug #B-n sent", report row on TEST |
| q15 | Lazy Penguins | script | warp to 15 towns, !tp, burst each, click: the lazy line |
| q16 | Monster Book cards | script | spawn/kill until the card drops (chat), book window frame |
| q17 | New commands | script | each command's chat line vs the expected text |
| q18 | Bots talk to each other | judgment | see above |
| q19 | Bots remember chat and whispers | judgment | see above |
| q20 | Trash talk vs real threats | judgment | see above |
| q21 | A regular asks to be your junior | script | not forceable for a real player; auto-QA t-fy-4 already proves it with a pretend player |
| q22 | Weapon scrolls in bot shops | script | the TEST market export or FM room frames; count scroll kinds |
| q23 | @botme summon and follow | script | `botme-alt-basics` |
| q24 | @botme fights only your fight | script | `botme-alt-basics` (+ the 3-min idle line and the boat: extend the script) |
| q25 | @botme idle and dismiss | script | `botme-alt-basics` (duplicate, dismiss; the 6th companion: extend) |
| q26 | Edit UI card (Ctrl+F11) | script | Ctrl+F11, drag a bar (needs a drag step), relog: card not shown, layout kept |
| q27 | HUD modules (Ctrl+F10) | script | tick EXP rate and DPS (anchors), kill mobs, frames of the HUD numbers |
| q28 | Short damage numbers | script | tick the option, hit Magnus, frames read "1.23M" style numbers |
| q29 | Hide other players' cash effects | script | frames before/after the switch in the FM; an agent compares |
| q30 | World map rock link | script | W, Temple of Time page, click the Grandis ribbon, teleport `field.ready` |
| q31 | General look and feel | judgment | see above |
| q36 | Permanent items show no 1970 expiry | script | hover the pet in the Cash tab, tooltip frame has no "1970" |
| q37 | Adventurer's Bag | script | @bp window, store/retrieve, counts identical, refusals |
| q38 | Custom NPCs not behind scenery | script | burst at each NPC spot; an agent sees the NPC drawn in front |
| q39 | Will spawns | script | spawn, @bosshp numbers, `client_alive` |
| q40 | Wyatt makes Essence Stones | script | items given, dialog, stone in bag, 2nd try refused |
| q41 | Bot shops don't sell boss drops / Chaos / White | script | tr-1 refusal line + t-bs-2 (server) |
| q42 | Bots stay and loot after an event boss | script | ev-1, QA_RUN lines, a clip of the scramble |
| q43 | Maker: Moren's chain and making an item | script | the three lessons, Maker window, crystal from 100 leftovers |
| q44 | Companion orders and trades | script | party-chat orders, trade window (needs trade anchors) |
| q45 | Kerning PQ with bots | judgment | see above |
| q46 | Bots doing quests | script | ask, reply names a quest; !botwhy "Quests:" line agrees |
| q47 | Bots walk their job-advance errands | script | t-jb-1 server check + a watched bot's walk recorded |
| q48 | Party heals | script | `b140-bot-heal-column` |
| q49 | Scrolled-gear prices look right | judgment | see above |
| q50 | Ask a regular the price, then haggle | script | tr-3, the three replies: price, counter, countered price |

## Release book Test tab, website (16)

These are not the game runner's job: a browser script (headless Chromium on 187 against the TEST site) does the
**script** ones. Listed so the owner's hand list is complete.

| id | check | label |
|---|---|---|
| qw01 | Sign in | script |
| qw02 | Icons in Slums Street | script (images load, none broken) |
| qw03 | Old links still work | script (redirect target) |
| qw04 | Most hunted | script (two tables, "(est.)") |
| qw05 | My invites | script |
| qw06 | Admin dashboard in the site's colours | judgment |
| qw07 | Invite codes (staff) | script |
| qw08 | Reports (new) | script (filters; no IP/e-mail in the page) |
| qw09 | Report page | script |
| qw10 | Slums Exchange layout | judgment |
| qw11 | Board wording, one page on a phone | script (text; no horizontal scroll at 390 px) |
| qw12 | Watchlist and a price alert | script |
| qw13 | Post and remove a board listing | script |
| qw14 | Invite wording on Join / Getting started / FAQ | script |
| qw15 | Board shows prices for the item you pick | script |
| qw16 | My stamps shows your own stamp count | script (equals @stamps) |

## Auto-QA HALF checks: the look part (16)

| id | what the person looks at | label | script |
|---|---|---|---|
| t-cv-4 | the four voices' casing and slang side by side | judgment | - |
| t-ga-3 | the label the client shows for the last remote slot | script | open the remote ticket list, frame |
| t-td-1 | selling a scroll to a bot | script | trade window frames |
| t-td-2 | the trade window with the sword in it | script | trade window frames |
| t-cb-2 | facing and spacing of the swings | script | 30 fps bursts next to the bots |
| t-sl-1 | salon menus and previews | script | npc dialog frames per salon |
| t-pt-1 | pets following and the loot animation | script | burst while bots loot |
| t-bk-1 | swing timing, held Hurricane and Rapid Fire | script | 30 fps bursts + the ATTACK lines' timing |
| t-at-1 | bots attack at a player's pace | script | bursts of your swings and theirs side by side; packet times |
| t-fh-2 | fishing chair and catch effects | script | burst on the catch line |
| t-bl-2 | one request starts one restock trip | script | the bot walking to town, recorded |
| t-bl-3 | restock from a real supply need | script | as t-bl-2 |
| t-lk-1 | AFK campers, walk when watched, 2009 typing | judgment | - |
| t-bc-1 | a bot says cc and appears on a quieter channel | script | chat line + the bot seen on the other channel |
| t-nn-1 | swim wall jump on a real client | script | burst of the swimmer at the wall |
| t-nn-2 | blocked-position watchdog | script | burst of the parked bot giving up |

## Auto-QA MANUAL checks (11)

| id | check | label | script |
|---|---|---|---|
| t-ga-4 | Bots' show rolls vanish | script | `qa-scripts/t-ga-4-show-rolls-vanish.yaml` |
| t-rg-1 | Buy a friendship ring for two | script | `qa-scripts/t-rg-1-friendship-ring.yaml` |
| t-rg-2 | Buy a couple ring, same gender too | script | `qa-scripts/t-rg-2-couple-ring.yaml` |
| t-mv-1 | Attacking stops you | script | `qa-scripts/t-mv-1-attack-stops.yaml` |
| t-jq-1 | No bounces in jump quests | script | `qa-scripts/t-jq-1-no-bounce.yaml` |
| t-cl-1 | Ropes have no box around them | script | `qa-scripts/t-cl-1-rope-no-box.yaml` (an agent judges the frames: a box is there or not) |
| t-cl-2 | Cash Shop mouse wheel | script | `qa-scripts/t-cl-2-cash-shop-wheel.yaml` |
| t-cl-3 | Pet equips don't crash | script | `qa-scripts/t-cl-3-pet-equip.yaml` |
| t-cc-1 | Character creation is never "disabled" | script | `qa-scripts/t-cc-1-create-character.yaml` |
| t-mr-2 | A woman can propose | script | `qa-scripts/t-mr-2-woman-proposes.yaml` |
| t-la-1 | The Tauri launcher's own checklist | stays by hand | the launcher is a desktop app outside the game client; the runner does not drive it |

When a MANUAL check's script passes on TEST, its line in `src/main/resources/soloMapling/autoqa-release-skips.tsv`
can point at the run folder instead of "eyes only" (owner decision; from 1.7.0 the scripts gate releases).
