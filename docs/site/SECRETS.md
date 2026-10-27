# Slums-chan secrets and cards: the answer key

Spoilers, for the team only. This file is not published: the site build copies only the page sources, assets,
releases and secret/ pictures. Don't put these answers on the site, in patch notes or on Discord.

Players see the cards in **My cards** (`market/cards.html`, in the Slums Market sidebar under "My stuff"). Found cards
show face up with the date. The rest show a "?" and the hint in the last column below.

## Who can find them

- Only **signed-in** players. A signed-out visitor who sets off a secret gets "Sign in to see it" instead. The two
  passive ones (late night and idle) do nothing for signed-out visitors, so they never pop up uninvited.
- "Typing" means pressing the keys on a page with nothing selected, **not** in a search box or form. Upper or lower
  case both work.
- "Clicks in a row" means each click within 0.8 seconds of the last one.

## The 15 cards

| # | Card | Id | How to unlock it | Picture | Hint shown on My cards |
|---|---|---|---|---|---|
| 1 | Peace out | `konami` | Type the Konami code: ↑ ↑ ↓ ↓ ← → ← → B A | `peace.webp` | A classic code from the old days. |
| 2 | Over the shoulder | `slums` | Type `slums` | `shoulder.webp` | Say where you are. |
| 3 | Bustin' since 06 | `clicks` | Home page: click the big Slums-chan picture 7 times in a row | `look.webp` | The home page picture wants attention. |
| 4 | Big smile | `bustin` | Type `bustin` | `lean.webp` | What has she been doing to chat since 2006? |
| 5 | Sidekick | `sidekick` | Click the small Slums-chan next to a page's heading 5 times in a row (Sign in, Forgot password, Report a bug and the World pages) | `kneel.webp` | The Slums-chan by a page's heading wants attention too. |
| 6 | Wide awake | `wake` | Go to any page that doesn't exist (the 404 page) and click the sleeping Slums-chan 3 times in a row | `lying.webp` | Someone's asleep on a page that doesn't exist. |
| 7 | C'mere | `henesys` | Type `henesys` | `reach.webp` | Name the town that's full again. |
| 8 | Night owl | `owl` | Open any page between 2:00 and 4:59 AM on your own computer's clock and wait 4 seconds | `chin.webp` | She's up late. Are you? |
| 9 | Oops | `kerning` | Type `kerning` | `bend.webp` | Say the city where the thieves hang out. |
| 10 | Peace sit | `colours` | Click all three colour dots at the top (green, blue, orange) within 10 seconds | `sitpeace.webp` | Try on every colour, quickly. |
| 11 | AFK | `afk` | Leave a page open and visible for 5 minutes without touching it: no keys, mouse, scrolling or taps | `lounge.webp` | Sit still for a while. |
| 12 | Small print | `footer` | Click the line of small print at the bottom of any page ("SlumsMS is Slums Story...") 3 times in a row | `glance.webp` | Read the small print at the bottom. Then poke it. |
| 13 | Slime hug | `slime` | Type `slime` | `hug.webp` | Say her favourite pet. |
| 14 | Shy | `theme` | **Reward:** find cards 1, 2 and 3. This also unlocks the Slums-chan colour theme (the 4th dot) | `shy.webp` | Unlock the Slums-chan theme. |
| 15 | Double peace | `all` | **Reward:** find all 13 secrets (cards 1-13) | `dpeace.webp` | Find every secret. |

## The Slums-chan theme

- Until it's unlocked, the 4th colour dot shows a "?". Clicking it tells the player how many secrets they've found and
  hints "try a classic code, and say where you are".
- Once it's unlocked and picked, the home page pictures switch to the spicy set: every secret the player has found,
  plus the Shy and Double peace pictures.

## Where it lives

- **Triggers, names and hints:** `docs/site/assets/theme.js`, the `SECRETS` and `REWARDS` lists and the code below
  them.
- **Pictures:**
  - full size in `docs/site/secret/` (820 px tall);
  - card thumbnails in `docs/site/secret/cards/` (360 px tall).
  - Both are private-build only, and nginx serves them only with a sign-in.
- **Saving:** the market service (`tools/site/market_service.py`).
  - `SECRET_CARDS` and `REWARD_CARDS` list the cards. The `cards` table in the market DB stores which account found
    which card, and when.
  - Endpoints: `GET /api/market/cards` and `POST /api/market/cards {ids: [...]}`. Unknown ids are ignored. The two
    reward cards are worked out by the service, never posted.
  - The browser also keeps its list (localStorage `slums-secrets`). Once per visit, the browser and the account merge
    both ways.
- **Page:** `tools/site/sections/market.py`, `build_board()` (`market/cards.html`). The styles are under "My cards"
  in `site.css`.

## Adding a card

1. Cut the sticker out. Save it at 820 px tall in `docs/site/secret/` and at 360 px tall in
   `docs/site/secret/cards/`, both as webp.
2. Add an entry to `SECRETS` in `theme.js`: `src`, `w` (width at 820 tall), `name`, `hint`, `alt`. Then add its
   trigger.
3. Add the same id, in the same position, to `SECRET_CARDS` in `market_service.py`.
4. Add a row to the table above.
5. Run `python3 -m unittest discover -s tools/site -p 'test_*.py'`. A test fails if the two lists don't match or a
   picture is missing.

The "Double peace" card needs every secret, so a player who had it before a new card was added loses it until they
find the new one.

## Testing and resetting

- **TEST** (http://192.168.0.164:8192): sign in with a test account and try the triggers.
- **To start over in one browser:** in the browser console, run `localStorage.removeItem("slums-secrets")`, then
  `sessionStorage.clear()`.
- **The account's saved cards** are separate from the browser. To clear them for a test account on TEST, delete its
  rows from the `cards` table in `/var/lib/slums-market-test/market.db`. Never touch live's market DB without the
  owner's go.
