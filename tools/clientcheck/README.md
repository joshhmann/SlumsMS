# clientcheck: client and server consistency report

`clientcheck.py` compares the SlumsMS server (this repo plus its WZ XML) with the SlumsMS client. On the client side it reads the client repo's branches and the test client's `Data` folder on the owner's desktop. It prints a PASS/INFO/WARN/FAIL report and **exits 1 if anything FAILs**.

```sh
python3 tools/clientcheck/clientcheck.py                       # everything (about 10 s warm, 1 min cold)
python3 tools/clientcheck/clientcheck.py --no-data             # source-only: checks 1, 2, 6 (+ source half of 3/4), < 1 s
python3 tools/clientcheck/clientcheck.py --only 1 2            # just opcodes and packet layouts
python3 tools/clientcheck/clientcheck.py --diff Mob/0000420.img Map/Map/Map2/271010400.img   # explain a difference
```

Useful options:

| Option | Default | Notes |
|---|---|---|
| `--client-repo` | `/root/work/client/src` | Branches are read from git objects, so no checkout is needed. |
| `--client-branches` | `main daily-checkin` | Each branch is checked on its own. |
| `--server-wz` | `/root/work/bake/wz` | The server's XML dump. |
| `--server-config` | `./config.yaml`, else `config.example.yaml` | Supplies `DAILY_CHECKIN_ENABLED`. |
| `--server-flag k=v` | flags from `deploy/slumsms.service.example` | Overrides a `-D` flag. |
| `--remote` | `crims@192.168.0.186` | Uses the key `~/.ssh/id_rsprox_desktop`. |
| `--remote-client` | `Z:\MSv83\SlumsMS-dev\test-client` | |
| `--remote-work` | `Z:\MSv83\SlumsMS-dev\clientcheck` | The only place the tool writes on the desktop. It refuses any path outside `SlumsMS-dev`. |
| `--cache-dir` | `~/.cache/slumsms-clientcheck` or `$CLIENTCHECK_CACHE` | Local caches: scans, key stream, fetched small files. |
| `--max-age` | 60 | Minutes before the cached client scan is refreshed. |
| `--refresh` | | Rescan now. |
| `--offline` | | Use only cached scans; never contact the desktop. |
| `--json FILE` | | Also write the findings as JSON. |
| `-v` | | Show every detail line. |

## How client data is compared without copying 23 GB

`wzscan.py` is standard-library only. It is copied to `--remote-work` along with a 1 MB GMS key stream, which is generated locally with pycryptodome because Windows Python has no AES. It then runs on the desktop with the desktop's Python.

The scan reads each `.img` under `Data\` (memory-mapped, skipping pixel and sound payloads) and writes one record per image:

- a **data** hash
- an **art** hash
- a data hash for each top-level child: map sections, item ids, SNs and so on
- which nodes carry an `info/icon`
- for `String/*.img`, the id-to-name table
- for a few named images, a full node-path dump. These are the UI images that the client source or `Custom.wz` touch.

The records are cached on the desktop by size and mtime, so a rescan only reparses changed files. All 86k images take about 20 s from cold. The server's XML is summarised the same way and cached locally.

Both hashes are Merkle hashes. Siblings are compared as a multiset by name. Ints of any width compare equal, and floats compare to 6 significant digits. Strings get XML attribute whitespace normalisation, because a raw newline in the dump reads back as a space. `_hash` pixel digests and `_outlink` markers that dump tools add are ignored.

- **Data** treats every canvas and UOL as an opaque "art" node. It catches every stat, foothold, portal, string or delay difference exactly.
- **Art** also keeps canvas sizes, origins and children, but never pixels. The server dump is lossy here: UOLs and outlinked canvases come out as 1x1 placeholders. So art differences are only counted, as INFO.
- **Normalised on both sides** before hashing (`wzscan.normalize`): the MapleRoot client's later-layout `Mob` attack data (stat leaves in `info/attack/<N-1>`, only the visual part in `attackN/info`) is folded back into `attackN/info` as the server stores it, and duplicate leaf names under `Mob` `info` keep the first. Map footholds are keyed by layer and id, not by zMass group, so a renumbered group with identical geometry is not a difference.
- **Known deliberate differences** (`KNOWN_DIFFS`) count as identical and are listed as INFO: the SlumsMS weather items `Item/Cash/0512.img` 05120995-9 (client-only) and the shortened Yoshi pet names in `String/Pet.img` 5002103/4.
- **Limitation:** footholds stored *inside* `Map/Obj` canvases are art under this rule and are not compared.

## The checks

1. **Custom opcodes.** Covers `RecvOpcode`/`SendOpcode` values of 0x1000 and above, compared with each client branch:
   - the opcode constants in the client;
   - the `COutPacket(...)` sends;
   - the opcodes `packetdispatcher.cpp` routes, with their `#ifdef` feature.

   Collisions are FAIL: inside the server enum, across client features, or a documented/reserved opcode reused by a live feature under another name (a comment that names the feature now owning the value, such as `SET_ITEM 0x3726/7` for `SET_ITEM_ACTION`/`SET_ITEM_SYNC`, is fine). Local aliases (`kOp_X = kXOpcode;`) are followed when resolving sends. The check also flags:
   - a server opcode with no client counterpart in that branch. This is FAIL when the server flag that gates it is on.
   - a client send that the server cannot handle (FAIL);
   - breaks of the even-request / odd-reply convention;
   - opcodes documented only in comments (the `cashshopwnd.h` list of BAG_WINDOW ... WEAPON_TINT).
2. **Packet layouts.** Field order and types (1/2/4/8 bytes, `S` for a u16-length string, `[...]` for a loop) are extracted from the server writer or reader and the client reader or writer. They are compared per subtype for:
   - the Cash Shop window (LP 0x3731, CP 0x3730);
   - Daily Check-In (LP 0x3733, CP 0x3732);
   - Weather (0x373D), where the client may read a prefix;
   - Lamp preview (0x373F).

   It also checks the limits the two sides share only by convention: `MAX_NAME` against `kMaxNameLen`, `CHUNK` against `kMaxEntriesPerChunk`, `BUY_*` codes against `kBuyMsg`, and `CYCLE_DAYS` against `kDays`. To cover another packet, add an entry to `LAYOUTS`.
3. **Cash Shop.**
   - Every `cashshop/catalog.tsv` tab/category pair must be in the branch's `kCats`.
   - Every catalog item needs client data, a client icon and a client String name.
   - Catalog items whose data or name differs from the server WZ are listed.
   - `Item/Cash`, `Item/Pet`, `String/*`, `Etc/Commodity.img` (per SN) and `Character/*` are compared as whole images.
4. **Custom.wz.** Lists what each branch's `src/Custom.wz` and the deployed one add. An image the client does not have is served whole. An overlay of a stock image only merges leaves, so a new sub-property under a stock image is **not** visible (see `resman.cpp`). The check then:
   - says which branch the deployed `Custom.wz` matches;
   - resolves every `L"X.img/..."` resource path in the client source against Custom.wz and the client's stock tree;
   - checks the paths the server relies on: `UI/UIWindowEx.img/DailyCheckin/backgrnd` and `MobHPRatioInfo`.
5. **Server WZ against client Data.** Every directory is compared as whole images; Map, Mob, Npc, Skill and Item get details:
   - **Missing on client:** server images the client lacks. These FAIL.
   - **Differences:** counts with examples. Maps are split into nav-relevant sections (foothold, ladderRope, portal, info, seat, swimArea) and cosmetic ones. Mobs whose `info` stats differ are listed, because the client computes touch damage from its own copy.
   - **Client-only images:** counted as INFO.
   - **Not counted as missing:** server images filed in the wrong `MapN` folder (the server never loads them) and server-only data such as `Etc/MapNeighbors.img`.
6. **Version and feature flags.**
   - `ServerConstants.VERSION`/`SLUMSMS_VERSION`, compared with the `Custom.wz` package version in the client.
   - `MAPLEROOT_*` defines in each branch's `scripts/build.ps1`, against `cashshop.window.enabled`, `weather.enabled` and `DAILY_CHECKIN_ENABLED`.
   - Both `release/baseline.json` files.
   - Which features the deployed `slums.dll` actually contains. This is detected from wide-string resource paths that only that feature's sources use.

## Hooks

`--no-data` needs neither the desktop nor the WZ, and runs in under a second, so it can run before a commit that touches opcodes or packet code. The exit status is 0 on PASS or WARN and 1 on FAIL.
