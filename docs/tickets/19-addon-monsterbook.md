# 19: MonsterBook addon

**Area:** Addons  
**Depends on:** -  
**Status:** shipped in v1.4.0 (server and client branch `addon-monsterbook`, via `test-rc4`)

## What

Server handler first (DropSearchService does not exist here), then the data pipeline (needs MapleLib), then the client. Opcodes 0x372B/0x372C clash with Beauty's 0x372C; renumber.

## Done when

The book shows live drops; the data pipeline is documented.

## How it was done

**Opcodes.** CP `MONSTER_BOOK_QUERY` 0x3734, LP `MONSTER_BOOK_RESULT` 0x3735: the next free pair after
DAILY_CHECKIN 0x3732/0x3733. The 0x372x block is the client's (Beauty 0x372C/D among them). Recorded in
`/root/work/addon-opcodes.md`.

**Server.**
- `server.monsterbook.DropSearchService` answers the three queries from in-memory indexes:
  - a snapshot of `drop_data` and `monstercarddata` (plus the Item.wz card list), read in two queries on first use;
  - a name index of every item, built once.
- No query touches the database. `!reloaddrops` clears the snapshot.
- The chance math is the drop roll's: `chance x (boss ? bossDropRate : dropRate) x item-up rate`, sent as parts
  per million.
- `MonsterBookQueryHandler` ignores bots and caps a client at 20 queries a second.
- The wire format is in `MonsterBookPackets`:
  - 0: a mob's drop table;
  - 1: item-name search (at least 3 characters, items a carded mob drops, at most 200);
  - 2: the carded mobs that drop an item.

**Data pipeline.** The book's lists (which icons the Dropping tab shows, Found In, Episode) are baked client data.
MapleLib is not needed. They are generated from this server's own data, not from another server's copy:

1. `tools/monsterbook/build_book.py` (this repo) reads:
   - the server's XML `wz/`: cards, Mob.wz stats and links, Map.wz spawns, String names;
   - `scripts/event/AreaBoss*.js`;
   - `drop_data`, either straight from MySQL (`--mysql "--defaults-extra-file=... <db>"`) or from a TSV dump
     (`--drops-tsv`).

   It writes `monsterbook.json`.
2. Copy that file to the client repo as `assets/monsterbook/monsterbook.json`, then run
   `python3 tools/wz/add_monster_book.py --stock-book <Data>/String/MonsterBook.img --stock-cards
   <Data>/Item/Consume/0238.img`. The tool uses the Python WZ writer `tools/wz/native_wz.py` and rewrites
   `src/Custom.wz`:
   - `String/MonsterBookEx.img`: the whole book;
   - `Mob/<id>.img/info/defaultHP`: for mobs whose HP the book cannot print as an int;
   - `UI/UIWindowEx.img/MonsterBook/search/*`: the search-field art.

   The client points the book loader at `MonsterBookEx.img` only when Custom.wz has it.
3. Rebuild and ship the client (`slums.dll` + `Custom.wz`).

The percentages are live. The server computes them from its drop tables and the player's rates each time the
Dropping tab opens. Which icons are listed is baked, so a drop_data change shows its new items only after steps
1-3.

The shipped `monsterbook.json` was built from the `mapleroot_persist` test schema (21,534 drop rows). Before
release, rebuild it against the live `mapleroot` schema.
