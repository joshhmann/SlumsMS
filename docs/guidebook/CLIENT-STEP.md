# Guidebook: what the client release needs (ticket 40)

The text is ready in `guidebook.yaml`. `python3 docs/guidebook/compile_guide.py` turns it into WZ XML under
`build/`. Nothing in the client has been changed; this page is the brief for the client layer step.

## How the guide gets to a new character today

- **Server:** `BeginnerCreator`, `NoblesseCreator` and `LegendCreator` (`src/main/java/client/creator/novice/`) all
  give `ItemId.BEGINNERS_GUIDE` = **4161001**. `NOBLESSE_GUIDE` (4161047) and `LEGENDS_GUIDE` (4161048) exist only as
  constants; no code gives them out. Nothing on the server handles opening the book (no script, no handler).
- **Client:** double-clicking a 4161xxx guide opens the client's own book window. Its pages come only from client
  data:
  - `Item.wz/Etc/0416.img/04161001/book/<page>/<block>/text` (string), with an optional `align` int on a block.
  - `String.wz/Etc.img/Etc/4161001/name` and `desc` (the tooltip).
- **What MapleRoot ships (read from the server's WZ dump, `/root/work/bake/wz`, which matches the client data):**
  4161001 is already "MapleRoot Guide" ("Client Ver .22"): pages 0-21, at most blocks 0 and 1 per page, covering
  MapleRoot features, skill changes, FAQ, rebirth (with old 85x/45x/18x/4x rates that are wrong for SlumsMS),
  Monster Book ring, credits and changelogs from April 2024. 4161047 and 4161048 still hold the stock GMS Noblesse
  and Legend guides.

## How the book window reads pages (inferred from the stock data - confirm in the client)

- Pages are shown as two-page spreads: 0+1, 2+3, ...
- `#L<n>#label#l` opens the spread that holds page n. MapleRoot's contents page links "Skill Changes" to `#L3`
  while the text starts on page 2, and every other link fits the same rule.
- Codes: `#b` blue, `#r` red, `#k` back to black, `#e` bold on, `#n` bold off. A line break is written `\n` in the
  dumps (the stock Noblesse guide).
- The source keeps each chapter to exactly one spread (heading on the left page, more on the right). The compiler
  warns over 430 visible characters or 11 lines a page. Those limits are a guess from the stock pages and **must be
  checked in the client**: text that overflows the page is cut off.

## What the client step has to do

1. **Build the overlay entries** from `build/Custom/` into the client's `Custom.wz` (client repo `src/Custom.wz`,
   staged next to `SlumsMS.exe` by `scripts/build.ps1`). Custom.wz mirrors the game's tree from its root, as the
   existing `UI/UIWindow.img/...` entries do, so the new nodes go at:
   - `Item/Etc/0416.img/04161001/book/<0-25>/<0-1>/text`
   - `String/Etc.img/Etc/4161001/name` and `.../desc`
   The XML is the same imgdir dump format as the server's `wz/`. Use whatever WZ tool built the current Custom.wz,
   or a dump-to-WZ importer (HaRepacker-style XML import).
2. **Check that the overlay replaces existing values.** In `src/resman.cpp`:
   - The `NAMESPACE.DLL` hook only answers from Custom.wz when the stock lookup **fails**. That covers new nodes,
     pages 22-25 here.
   - The `CWzProperty::Serialize` hook copies Custom's leaf values into the stock property with
     `this->Add(name, value, false)`.
   - Whether that `Add` **overwrites** a value that already exists is not known. Pages 0-21 already exist in
     MapleRoot's 4161001.
   - The existing Custom.wz entries (resolution option, mob HP) only add new nodes, so they do not answer this.
   - **Test:** open the book in the client and look at page 0.
     - "SlumsMS Guide": overwrite works, and you are done.
     - "MapleRoot's Guide": you need one of:
       - (a) change the hook to replace values (for example `Remove` and then `Add`, if IWzProperty offers that);
       - (b) ship a patched `Item.wz` and `String.wz` through the launcher instead of Custom.wz. These are big files,
         and the launcher manifest has to change.
3. **No stale MapleRoot pages.**
   - The compiler writes blocks 0 and 1 of every stock page 0-21, blank when the new book is shorter.
   - Stock pages never have a block 2, so no old text can show between the new pages.
   - If the book later shrinks below 22 pages, the blank pages remain. Check whether the window still lets you turn
     to them.
4. **Optional: 4161047 and 4161048.** No new character receives them, so they can stay as they are. To retire the
   GMS text anyway, give them the same book: same nodes under `04161047`/`04161048`, and their String entries.
5. **Server WZ (optional, not client):** the server's `wz/String.wz/Etc.img` still names 4161001 "MapleRoot Guide".
   Only server-side messages use that name, for example `@whodrops`. Change it in the same release if the names
   should match.
6. **Release rules:**
   - Hash-diff the client release against the standalone client before publishing (owner rule).
   - Add "update the guide/wiki" to the release checklist next to the news entry (ticket 40, part 3).

## Acceptance

- A fresh character on the release client double-clicks the guide and sees "SlumsMS Guide".
- Every contents link opens the right spread.
- No page is cut off.
- The tooltip reads "SlumsMS Guide".
