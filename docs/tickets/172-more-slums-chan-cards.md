# 172: more Slums-chan cards in My cards, the cheekier kind (Work list w10)

Owner, 2026-11-01 ~03:05 UTC (Website session): "id like a new ticket as well to add more lewd cards lmao".
Target: after 1.6.2. Website only (no game or client change); it can ship as a website update, on the owner's go.

## Today
My cards (market/cards) has 16 cards: 14 secret stickers (Konami code, typing "slums", 7 clicks on the hero, 5 clicks
on a speech bubble, ...) plus 2 reward cards. They are saved per account by the market service (SECRET_CARDS in
market_service.py / market_app.py; SECRETS in docs/site/assets/theme.js; the answer key in SECRETS.md; "N of 16" in
LAUNCH.md). The art lives in docs/site/secret/ and docs/site/secret/cards/, sign-in only (nginx /secret/). Several are
already on the cheeky side (from behind, bend, lying, lounge). Site art changes only on the owner's ask (art locked
2026-10-26); this ticket is that ask.

## What this adds
1. **New cards: the owner supplies the art** (sheets, as before). They pick which stickers go in and skip the deformed
   ones. We cut, crop and compress each to webp like the current set.
2. **Content line (the site is friends-only, but it's still the site):** cheeky and suggestive is fine (pin-up poses,
   from behind, swimsuit/lingerie-style outfits, winks). No nudity, no sexual acts, nothing explicit, and Slums-chan
   always clearly an adult. Same sign-in-only gate as the current secrets: never on public pages, the hero rotor, the
   launcher or the Discord.
3. **How they're found:** each new card gets its own secret, so a few new triggers (ideas: a key sequence on My cards,
   a long-press on the cards counter, a 10th rebirth-page visit, a night-time-only click). The owner picks. Or the owner
   may prefer a separate "after dark" set unlocked by finding all 16 first.
4. **Wiring:** SECRET_CARDS (both market apps), theme.js SECRETS, SECRETS.md answer key, the total ("N of 16" → the
   new count) and the "Double peace"/all-found reward, if it should still need every secret. Old accounts keep what
   they found.
5. **Tests:** each new id saves and shows; the total updates; the all-found reward follows the new rule; a signed-out
   visitor gets 404 for every new image.

## Art received
2026-11-01 ~07:00 UTC: 13 files (11 singles plus sheets of 12, 35 and 40 stickers), kept at
/root/work/agent-runs/site/172-cards/ (README lists each one; not in git, about 40 MB). Owner: "Don't add these yet":
they wait for the owner's picks.

## Owner decisions needed
- How many cards, and the art sheets.
- Mixed into the current secrets, or a separate "after dark" set?
- Does the all-found reward need the new ones too?

## Not in scope
Any change to the existing 16 cards or to the hero art; anything outside the signed-in site.
