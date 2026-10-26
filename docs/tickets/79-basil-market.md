# Ticket 79: Basil Market for SlumsMS: trading posts on the website, with bots selling and buying

Status: owner OK'd 2026-10-25 ("yep! perfect"). Planned for 1.6.x with the bot brain (listing and selling is a brain
activity). Depends on ticket 78's site.

## What

A Basil Market style board on theslumsms.asslorde.com: "S> Zakum Helm 10/10 clean 120m, PM Wolf4life ch2".
- **Bots post** what they really hold (bot shops and bot inventories already exist), at prices they would really
  take (the haggling and `@price` logic), and refresh their posts as they sell.
- **Players post** from the game with `@basil sell <item> <price>` / `@basil buy <item> <price>` / `@basil list` /
  `@basil remove <n>`, so posts are tied to real items and characters and there is no web login.
- **Deals close in game only**: whisper the seller or meet in the FM. A bot seller answers like a player
  ("omw fm ch1 room 4"), walks to the FM and opens a trade; haggling as today; rivals refuse as today. Nothing is bought
  on the website, so no new way for items or mesos to appear.
- Item pages link to ticket 78's database entry and `@price` history.

## Look (owner 2026-10-25): the old Basil Market, not a modern site

Modern references for *features* only (filters by category/job/stat, price charts, listing freshness): cozy.markets/market,
kerning.gg, meowdb.com. The *look* is the original Basil Market (basilmarket.com, ~2006-2010): forum-style board with
the orange/blue header and logo bar, category tabs per item type, a dense listing table (S>/B> tag, item, stats in one
line, price, seller, server/channel, posted time), small fonts, "PM seller" style contact line, seller reputation
counter, and the classic per-item price-check page. Pull reference screenshots from the Wayback Machine before
designing. Same site and nav as ticket 78 so the two feel like one fan site.

## Rules

- No economy change hidden in it: bot prices come from the existing pricing; any new bot spending or selling volume is
  the owner's call. Godly gear and the existing bot-shop exclusions stay excluded.
- Public site shows character names and item posts only; no account data.
- Abuse: one post per item, a cap per character, posts expire after a few days, staff can remove posts.

## Look superseded (owner, 2026-10-26)

The basilmarket.com period look above is **superseded** by style A "Megaphone" (bold modern), the same theme as the
rest of the site and Hidden Street, in light and dark. The features and rules above still stand. Market v1 for 1.6.0
is read-only (prices, FM listings now, recent sales, NX rate, economy) and stays private until 1.6.0 is live; `@basil`
posts are 1.6.x. Plan: `docs/design/track-d-plan.md` §5 (release-1.6.0).
