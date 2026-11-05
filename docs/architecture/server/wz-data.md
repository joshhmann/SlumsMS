# WZ data

## Purpose

Every static game fact the server knows — map layouts, mob and NPC stats, item names, skill effects, quest requirements — comes from the MapleStory client's WZ archives, which this server reads as an extracted **XML** tree: `<wz>/Map.wz/Map0/100000000.img.xml` and friends. `provider/` turns that tree into a lazy, read-only `Data` tree that the factories (maps, life, items, skills, quests, reactors) query by path. There is no binary `.wz` reader in this codebase; the only `DataProvider` implementation parses XML (`src/main/java/provider/DataProviderFactory.java:29-36`). The `wz/` directory itself is host data, never in git and never copied by a deploy.

## Entry points

| Entry | File:line | What |
|---|---|---|
| `DataProviderFactory.getDataProvider(WZFiles)` | src/main/java/provider/DataProviderFactory.java:34-36 | 61 call sites in `src/main/java`; the only way data is obtained |
| `DataProviderFactory.getWZ(Path)` | src/main/java/provider/DataProviderFactory.java:29-32 | returns `new XMLWZFile(in)` — the only implementation |
| `WZFiles` enum | src/main/java/provider/wz/WZFiles.java:6-20 | 14 archives; `getFile()` = `Path.of(DIRECTORY, name + ".wz")` (:27, :30-32) |
| `WZFiles.DIRECTORY` | src/main/java/provider/wz/WZFiles.java:22 | resolved once at class init from `-Dwz-path` (:38-46) |
| `XMLWZFile.getData(String)` | src/main/java/provider/wz/XMLWZFile.java:66-83 | path → parsed `Data`, `null` when the file is absent |
| `XMLWZFile.getRoot()` | src/main/java/provider/wz/XMLWZFile.java:86-88 | directory index (`WZDirectoryEntry`) for whole-archive scans |
| `Data` / `DataTool` | src/main/java/provider/Data.java:22-35, src/main/java/provider/DataTool.java:29-177 | tree navigation and typed reads with defaults |
| Big consumers | src/main/java/server/maps/MapFactory.java:69-75, src/main/java/server/life/LifeFactory.java:41-57, src/main/java/server/ItemInformationProvider.java:124-135, src/main/java/client/SkillFactory.java:35, src/main/java/server/maps/MapRouting.java:48-102 | maps, life, items, skills, routing |

## How it works

1. **Pick the directory.** `WZFiles.getWzDirectory()` (src/main/java/provider/wz/WZFiles.java:38-46) reads `System.getProperty("wz-path")` (:40) and uses it only if `Files.isDirectory` says it exists (:41); otherwise it silently returns the literal `"wz"` (:45). The result is frozen into the `DIRECTORY` constant at class-initialisation time (:22), so `-Dwz-path` must be set on the JVM command line, not at runtime.
2. **Open one archive.** `WZFiles.getFile()` (src/main/java/provider/wz/WZFiles.java:30-32) yields `<DIRECTORY>/<Name>.wz` (e.g. `wz/Map.wz`) — a *directory* of `.xml` files after extraction, not a binary WZ. `DataProviderFactory.getWZ` wraps it in `XMLWZFile` (src/main/java/provider/DataProviderFactory.java:30-32).
3. **Index the archive once.** The `XMLWZFile` constructor calls `fillMapleDataEntitys` (src/main/java/provider/wz/XMLWZFile.java:42-46, :48-64), which walks the directory with `Files.newDirectoryStream`: subdirectories not named `*.img` become `WZDirectoryEntry` (:53), every `*.xml` becomes a `WZFileEntry` with the extension stripped (:58). An unreadable entry logs `"Can not open file/directory at …"` and is skipped (:62). This index is what `getRoot()` (:86-88) serves and is the only thing cached anywhere in `provider/`.
4. **Resolve a node.** `XMLWZFile.getData(path)` (src/main/java/provider/wz/XMLWZFile.java:66-83) is `synchronized`, appends `".xml"` to the path (:68) and returns **`null`** if that file does not exist (:70-72) — a missing WZ image is a normal, handled outcome. Otherwise it parses the file into a fresh `XMLDomMapleData` (:75); a `FileNotFoundException` (the file vanished between the check and the open) becomes `RuntimeException("Datafile … does not exist in …")` (:77). Nothing is memoised: every `getData` call re-parses the whole XML document.
5. **Navigate inside an image.** `XMLDomMapleData` is a DOM wrapper: the constructor parses with `DocumentBuilderFactory` and keeps `document.getFirstChild()` as its node (src/main/java/provider/wz/XMLDomMapleData.java:72-87). `getChildByPath` splits on `/`, delegates a leading `..` to the parent (:97-99), walks `childNodes` comparing the `name` attribute under `synchronized (domLock)` (:102-120), and returns **`null`** when a segment is missing (:117). `getChildren()` (:128-140) and `iterator()` (:244-247) expose siblings; `getType()` (:189-221) maps the node name to `DataType` (`imgdir`→PROPERTY :195, `canvas`→CANVAS :197, `int`→INT :209, `null`→IMG_0x00 :217, unknown→`null` :219).
6. **Concurrency.** All wrappers over one document share one lock: `DOCUMENT_LOCKS` is a `Collections.synchronizedMap(new WeakHashMap<>())` keyed by root node (src/main/java/provider/wz/XMLDomMapleData.java:58), and every wrapper created from a document reuses `lockFor(node)` (:64-70, :86, :89-92). The comment at :49-54 records why: per-wrapper `synchronized` serialised nothing, and concurrent readers tripped Xerces' lazy `NodeListCache` (`"Cannot assign field fChildIndex because this.fNodeListCache is null"`).
7. **Read a value.** `getData()` (src/main/java/provider/wz/XMLDomMapleData.java:146-186) switches on the type: `INT`/`SHORT`/`FLOAT`/`DOUBLE` via `GameConstants.parseNumber` (:157), `STRING`/`UOL` return the `value` attribute, `VECTOR` builds a `Point`. `DataTool` (src/main/java/provider/DataTool.java) then converts with defaults — `getString(Data,String)` (:33), `getInt(Data)` → 0 on null (:57-62), `getLong` (:64), and `getIntConvert(String,Data,int)` (:139-159) which is the only overload with full null/type guards (:140-147) and swallows `NumberFormatException` into the default (:155-157).
8. **Boot.** No separate WZ bootstrap exists. `Server.init` submits `SkillFactory.loadAllSkills()`, `CashItemFactory.loadAllCashItems()`, `Quest.loadAllQuests()`, `SkillbookInformationProvider.loadAllSkillbookInformation()` and `PlayerNPCFactory.loadFactoryMetadata()` to a 10-thread pool (src/main/java/net/server/Server.java:946-953); each touches static `DataProvider` fields, so the archives are opened there. `MapFactory`'s own static block reads `String.wz/Map.img` and opens `Map.wz` lazily on first map load (src/main/java/server/maps/MapFactory.java:72-75). The pool is joined at src/main/java/net/server/Server.java:1008-1016; any exception aborts the boot with `IllegalStateException`. Nothing verifies that `wz/` exists or is complete.
9. **Per-map lookup and the null contract.** `MapFactory.getMapName` builds `Map/Map<area>/<9-digit-padded>.img` (src/main/java/server/maps/MapFactory.java:417-427); `loadMapFromWz` fetches it (:151-155) and on `null` logs `"MapFactory: no image for map …"` and returns null (:156-161), likewise for a dead `info/link` redirect (:164-172, second null check :168-171). `MapManager.loadMapFromWz` refuses maps the routing table blocks (src/main/java/server/maps/MapManager.java:72-76), calls the factory (:90) and caches **only non-null** results (:92-99) so a missing image does not poison the cache.
10. **Fallbacks for missing nodes.** `LifeFactory` returns mob stats only after a `link` recursion (:104-117) and logs `"[SEVERE] MOB {} failed to load."` once per id (:369-389); an NPC absent from `Npc.wz` still exists with the name `"MISSINGNO"` (src/main/java/server/life/LifeFactory.java:417-419). Item names fall through `getStringData`'s id-range switch (src/main/java/server/ItemInformationProvider.java:192-259) to `getName` returning `null` (src/main/java/server/ItemInformationProvider.java:1672-1683). `Storage` fees default to 100/0 when the NPC image is missing (src/main/java/server/Storage.java:328-361). `PartySearchCoordinator` degrades to an empty neighbour table without `Etc.wz` (src/main/java/net/server/coordinator/partysearch/PartySearchCoordinator.java:63, :77-98). Map routing is fail-closed: a table that cannot be read clears every route so questionable moves are refused rather than allowed (src/main/java/server/maps/MapRouting.java:82-93).
11. **Where data therefore has to exist.** A map only loads if its `Map.wz` image exists *and* every mob/NPC it places has a `Mob.wz`/`Npc.wz` image — `loadLife` does `myLife.setCy(...)` unconditionally after `LifeFactory.getLife` (src/main/java/server/maps/MapFactory.java:391-393), so a missing life is an NPE that fails the whole map, which is why `tools/data-align/map_routing.py` refuses to route into such a map.

## Data

| Archive (`WZFiles`) | Used by (file:line) | Shape read |
|---|---|---|
| `Map.wz` | src/main/java/server/maps/MapFactory.java:74; src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/GCWorldGraph.java:357 (nav graph directory scan) | `Map/Map<0-9>/<9-digit>.img.xml` — `info`, `portal`, `life`, `reactor`, `back`, `foothold`, `clock` |
| `String.wz` | src/main/java/server/maps/MapFactory.java:73; src/main/java/server/life/LifeFactory.java:42-44; src/main/java/server/ItemInformationProvider.java:128-135; src/main/java/client/SkillFactory.java:343 | `Map.img`, `Mob.img`, `Npc.img`, `Skill.img`, `Cash/Consume/Eqp/Etc/Ins/Pet.img` |
| `Mob.wz` | src/main/java/server/life/LifeFactory.java:41; src/main/java/server/life/MobAttackInfoFactory.java:39 | `<padded-7-digit>.img.xml` — `info`, `attack`, `skill`, `revive` |
| `Npc.wz` | src/main/java/server/life/LifeFactory.java:417; src/main/java/server/Storage.java:332, :350 | `<npcid>.img.xml` — `info/trunkPut`, `info/trunkGet` |
| `Item.wz` | src/main/java/server/ItemInformationProvider.java:126; src/main/java/client/inventory/PetDataFactory.java:37 | `<4-digit>.img.xml` with 8-digit child nodes |
| `Character.wz` | src/main/java/server/ItemInformationProvider.java:127 (equips) | `<5-digit>.img.xml` |
| `Skill.wz` | src/main/java/client/SkillFactory.java:35; src/main/java/server/life/MobSkillFactory.java:44 | `<jobid>.img.xml` with a `skill` node |
| `Etc.wz` | src/main/java/net/server/coordinator/partysearch/PartySearchCoordinator.java:80; src/main/java/server/CashShop.java:162; src/main/java/server/events/gm/OxQuiz.java:45 | `MapNeighbors.img` and other single images |
| `Reactor.wz` | src/main/java/server/maps/ReactorFactory.java:44 | `<padded-11-digit>.img.xml` |
| `UI.wz` | src/main/java/server/life/LifeFactory.java:46 | `UIWindow.img` `MobGage/Mob` (boss HP bars) |
| `Quest.wz` | src/main/java/server/quest/Quest.java:76; src/main/java/server/SkillbookInformationProvider.java:123; src/main/java/soloMapling/ArtificialPlayer/BotQuestSystem/BotQuestIndex.java:151 | `Check.img`, `Act.img`, `QuestInfo.img` |
| `Sound.wz` | src/main/java/server/life/PlayerNPCFactory.java:57 | single images behind player-NPC metadata |
| `Effect.wz` | src/main/java/client/DamageSkin.java:17 only; its caller is commented out (src/main/java/net/server/Server.java:1050) | `DamageSkin.img` |
| `List.wz` | no reference (`WZFiles.LIST` has 0 hits across src/main and src/test) | — |

- **In-repo or deployed?** Deployed separately. `/wz/` is git-ignored (.gitignore:6 = `/wz/`), the worktree has no `wz/`, and `tools/ops/deploy-test` copies only `target/Cosmic.jar`, `scripts/**`, `src/main/**` non-`.java` and `cashshop/` (tools/ops/deploy-test:9-12) filtered from `git diff --name-only -- scripts src/main cashshop` (tools/ops/deploy-test:54-58) — WZ files can never be part of that diff. `tools/data-align/portal-fixes.tsv:1` calls the WZ XML "host data, not in git"; release WZ edits are applied in place on the host with a backup (`tools/data-align/wz_set_values.py:1-15`).
- **Size.** Live unit is 839M of which `wz` is 471M (docs/ops/lxc125-cleanup-review.md:31). Test tooling assumes `/root/slums-nav-v70/wz` (docs/1.6.4-content-hunt-handoff.md:143).
- **Runtime table derived from WZ.** `src/main/resources/soloMapling/map-routing.tsv` — 63 route rows plus 49 `blocked_maps` rows in the current file; header says it is generated from `Map.wz` and must not be hand-edited (first line of the file), generator `tools/data-align/map_routing.py:1-32`.
- **Offline readers bypass the provider.** The 35 programs in `src/main/java/tools/mapletools/` use `WZFiles.<X>.getFile()` / `getFilePath()` for a path and then tokenise the XML themselves (src/main/java/tools/mapletools/ToolConstants.java:6-7 fixes `tools/input` and `tools/output`; e.g. src/main/java/tools/mapletools/QuestItemFetcher.java:427-436 reads `Check.img.xml`/`Act.img.xml` with `BufferedReader`). They are run by hand, never at boot.
- **XML only.** Every archive above is a directory of `.xml` files; there is no `WZFile`/binary reader and no `MapleDataProviderFactory` (only a commented-out reference at src/main/java/scripting/npc/NPCConversationManager.java:660 using the older `"wzpath"` key).

## Switches

| Switch | Default | Read at | Effect |
|---|---|---|---|
| `-Dwz-path` | `"wz"` | src/main/java/provider/wz/WZFiles.java:40-45 | root of the extracted XML; used only if it is an existing directory, otherwise the literal `wz` is used **silently** |
| `-Dslums.maps.routing` | `true` | src/main/java/server/maps/MapRouting.java:50, :66 | master switch for the WZ-derived routing table; `false` makes `route`/`move` return null |
| `-Dslums.maps.routing.table` | `/soloMapling/map-routing.tsv` (classpath) | src/main/java/server/maps/MapRouting.java:49, :163-171 | reads a file instead of the packaged table |
| `WZ_PATH` (env, tooling only) | `wz` | tools/ops/map-integrity:217 | directory for the offline integrity checker |

- **Not in config.yaml.** `src/main/java/config/ServerConfig.java` has no WZ field and `config.example.yaml` has no `wz` key; the location is a `-D` property alone.
- **Live:** `docs/release/1.6.4.conf:201-216` (`SERVER_SET`) sets only env-style keys and `:271` (`UNIT_EDITS`) carries no WZ flag, so live relies on either a `-Dwz-path` set outside these lists or the process CWD containing `wz/` — which of the two is not recorded.
- **TEST:** `start-test.sh` lives on the test host and is not reachable from this worktree — unknown.
- **Nav tooling** passes `-Dwz-path=…` explicitly (src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/NavDeepBake.java:508 and the javadoc examples in src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/NavDashboardData.java:102, src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/NavPlayerReplay.java:31 and src/main/java/soloMapling/dashboard/DashboardMapExport.java:40).

## Invariants

- Data is read-only. Nothing in `provider/` or its callers mutates a DOM node; `Data` exposes only getters (src/main/java/provider/Data.java:22-35).
- All access to one document is serialised on that document's lock (src/main/java/provider/wz/XMLDomMapleData.java:58, :86, :102, :190, :225); two documents must not share a lock (asserted by src/test/java/provider/wz/XMLDomMapleDataConcurrencyTest.java:40-60).
- A missing image is `null`, not an exception — `XMLWZFile.getData` returns null for an absent file (src/main/java/provider/wz/XMLWZFile.java:70-72) and callers branch on it (src/main/java/server/maps/MapFactory.java:156-161). The one exception path is a file that disappears between `exists` and `open` (:77).
- No caching below the directory index: every `getData` re-parses the XML (src/main/java/provider/wz/XMLWZFile.java:66-83). Callers that care memoise themselves (`ItemInformationProvider` name/msg caches at src/main/java/server/ItemInformationProvider.java:87-88, `Storage` fee caches at src/main/java/server/Storage.java:50-51, used at :327-345, `AttackCapture` skill names at src/main/java/soloMapling/server/AttackCapture.java:269).
- A `MapleMap` is never cached when the image is missing (src/main/java/server/maps/MapManager.java:92-99, comment at :68-70).
- A map whose life list names a mob absent from `Mob.wz` cannot load — the NPE at src/main/java/server/maps/MapFactory.java:393 aborts `loadMapFromWz`.
- `wz/` never travels through git or `deploy-test`; WZ edits are applied on the host, gated on the previous value, with a `<wz>/../wz-backup-<UTC>` copy (tools/data-align/wz_set_values.py:1-15).

## Origin

| Path | Origin | Evidence |
|---|---|---|
| src/main/java/provider/DataProviderFactory.java | base | byte-identical to /root/workspace/cosmicms |
| src/main/java/provider/wz/XMLWZFile.java | base | byte-identical to upstream |
| src/main/java/provider/Data.java, src/main/java/provider/wz/WZEntry.java, src/main/java/provider/wz/WZFileEntry.java, src/main/java/provider/wz/WZDirectoryEntry.java | base | byte-identical to /root/workspace/cosmicms |
| src/main/java/provider/wz/DataType.java | modified | cosmetic only (enum declaration reflowed) |
| src/main/java/provider/wz/WZFiles.java | modified | adds `EFFECT("Effect")` at :8; nothing else differs |
| src/main/java/provider/DataTool.java | modified | adds `getLong` (:64), `getLongConvert` (:111) and hardens `getIntConvert(String,Data,int)` (:139-159) |
| src/main/java/provider/wz/XMLDomMapleData.java | modified | per-document shared lock + rewritten `getChildByPath`; the reason is in the comment at :49-54 (Xerces `NodeListCache` race). Only two commits touch `provider/`: `03d13cb21` 2026-10-18 (baseline repack) and `1512c08a6` 2026-10-19 (persistence-fork merge, which rewrote this file) |
| src/main/java/server/maps/MapRouting.java | custom | absent from /root/workspace/cosmicms; oldest commit `6aadd9c81` 2026-10-31 (ticket s-maproute) |
| src/main/resources/soloMapling/map-routing.tsv + tools/data-align/map_routing.py | custom | generator docstring cites ticket s-maproute; no upstream counterpart |
| src/main/java/server/maps/MapFactory.java, src/main/java/server/maps/MapManager.java, src/main/java/server/life/LifeFactory.java, src/main/java/server/ItemInformationProvider.java, src/main/java/client/SkillFactory.java | modified | all differ from /root/workspace/cosmicms |
| tools/cosmicdiff/classifications.json | no entry | 82 keys, none matches a `provider/` path; that file classifies method-level Cosmic-vs-fork differences, and the provider layer was never selectively rewritten |

## Tests and QA

| Coverage | Where |
|---|---|
| DOM lock correctness (needs no `wz/`) | src/test/java/provider/wz/XMLDomMapleDataConcurrencyTest.java — 16 threads × 2,000 rounds × 40 dirs (:33-35), shared lock per document (:40-51), distinct locks across documents (:54-58), parent navigation keeps the lock (:63-78) |
| Skip gate for all data-dependent tests | src/test/java/soloMapling/WzTestData.java:8-11 (javadoc: not in the repo, skip never fail), `dir()` = `-Dwz-path` else `wz` (:17-19), `assumePresent` aborts with `"needs wz/…, not in this checkout"` (:21-24); src/test/java/soloMapling/WzTestDataTest.java asserts that abort behaviour |
| WZ-dependent suites (61 files reference `WzTestData`) | e.g. src/test/java/server/maps/MapRoutingWzTest.java, LowerGrandisPortalsWzTest.java, src/test/java/server/life/LifeFactoryFailedLoadCacheTest.java (asserts one `[SEVERE]` line and `null`), src/test/java/soloMapling/mechanics/ContentRowWzIntegrityTest.java, MechanicsRowWzIntegrityTest.java |
| Auto-QA | `t-mp-1` "Map routing: no door leads into a map that fails to load or crashes the client" (src/main/java/soloMapling/autoqa/AutoQaCatalog.java:1552-1558) backed by `Probes164.mapRouting()` (src/main/java/soloMapling/autoqa/Probes164.java:423-470): SKIPs when routing is off, fails when routes<40 or blocked<30, checks every blocked map's fallback and three specific doors |
| Offline integrity | `tools/ops/map-integrity` (portals, placed mobs/NPCs/reactors, return maps, text; exit 1 on findings), `tools/data-align/map_routing.py --check`, generator self-tests `tools/data-align/test_map_routing.py` |
| Owner-run baseline | "WZ integrity green … 57 rows, 163 map ids, 129 mob ids, 14 reactor ids, 1 door item" (/root/work/OWNER-DECISIONS.md:37) |

**Not covered:** there is no test or check that the WZ tree is *present* at boot — a missing or wrong `wz/` surfaces only as a failed startup task (src/main/java/net/server/Server.java:1008-1016). No auto-QA check reads map, item or skill data generically; `t-mp-1` covers routing only. CI never has `wz/` (`.github/workflows/build.yml:15-16` copies `config.example.yaml` and runs `./mvnw --batch-mode test package`), so every WZ-gated test skips there, and a passing CI run says nothing about data. `!qa` scenarios under `src/main/java/soloMapling/qa/` contain no WZ-loading case. The 14 archives have no per-archive presence test.

## Known issues

| Id | One line |
|---|---|
| b300 | Map 272000000 "Crack in Time" traps characters; fixed by editing two host `Map.wz` files with `wz_set_values.py --tsv portal-fixes.tsv` (/root/work/STATUS-1.6.4.md:49, docs/release/1.6.4.conf:263-266) |
| b251 | v83 client crashes entering Rose Garden 211080100; the routing table blocks that id and routes its `east00` door back to 211080000 (src/main/resources/soloMapling/map-routing.tsv:24, :93; /root/work/STATUS-1.6.4.md:72) |
| 272 | 247 map ids still carry fatal-class findings and 1,231 whole-BGM image references are missing; portal `tn` repair is a host-WZ edit (docs/tickets/272-client-map-integrity-and-crashes.md:22, :317-322) |
| 274 | TEST WZ has 11,356 map images / 6,789 mob templates; 270 of 1,980 placed mob ids have no template (docs/tickets/274-missing-monster-and-boss-loot-tables.md:18-21) |
| data gap | Server `wz/` is 9,528 NPCs and 3,456 mobs short of what the client's patch layers ship (Big-Bang-era maps); needs re-extraction and a test deploy (docs/HANDOFF.md:102, docs/design/track-d-plan.md:185) |
| PQ report | CygnusBattle 8850011 and VonLeonBattle 8840000 never spawn because they are absent from `Mob.wz`; Hard Lotus maps 67 and 1065 are absent from `Map.wz` (/root/work/PQ-UNFINISHED-REPORT.md:102-104, :143) |
| owner note | "A revive id missing from `Mob.wz` killed the death task" — the NPE surfaced inside `TimerManager` and the mob's death never completed (/root/work/OWNER-DECISIONS.md:83) |
| 229 maps | Maps that spawn a mob with no `Mob.wz` image cannot load at all (`MapFactory.loadLife` NPE at src/main/java/server/maps/MapFactory.java:393); docs/ops/map-routing.md records the class |
| NEW-FINDING | Silent fallback: a typo in `-Dwz-path` (or a non-directory) is ignored and `"wz"` is used instead (src/main/java/provider/wz/WZFiles.java:41-45), so the server can boot against a different data tree with no log line |
| NEW-FINDING | No memoisation under `XMLWZFile.getData` (src/main/java/provider/wz/XMLWZFile.java:66-83): `SkillFactory.getSkillName` re-parses the whole `String.wz/Skill.img.xml` on every call (src/main/java/client/SkillFactory.java:342-343), and `GameConstants.getMaxObstacleMobDamageFromWz` parses every `Obj` image at class-init (src/main/java/constants/game/GameConstants.java:681-705) |
| NEW-FINDING | Inconsistent miss contract: `getData` returns `null` for an absent file (:70-72) but throws `RuntimeException` if the same file disappears between the check and the open (:77) — callers such as src/main/java/server/maps/MapFactory.java:156-161 only handle `null` |
| NEW-FINDING | `Effect.wz` is dead weight: the only reader is `DamageSkin.loadDamageSkins` (src/main/java/client/DamageSkin.java:17-19) and its call is commented out (src/main/java/net/server/Server.java:1050, src/main/java/net/server/channel/handlers/PlayerLoggedinHandler.java:239); `WZFiles.LIST` has no reference at all |
| NEW-FINDING | `MapFactory` swallows the background-layer parse failure with `e.printStackTrace(); // swallow cause I'm cool` (src/main/java/server/maps/MapFactory.java:379-382), so a malformed `back` node costs visual layers with no log entry |

## State

**working** — the provider layer is exercised on every boot and every map load: the live unit serves from a 471M `wz/` (docs/ops/lxc125-cleanup-review.md:31), the routing table it derives is loaded and asserted (src/main/java/server/maps/MapRouting.java:82-93, src/test/java/server/maps/MapRoutingWzTest.java), and the owner's WZ integrity run is green (/root/work/OWNER-DECISIONS.md:37). The *data* it reads is **partial**: the documented 9,528-NPC / 3,456-mob gap and the 270 mob ids without templates mean maps and spawns silently disappear rather than fail loudly.

## Open questions

- Which client version the host `wz/` was extracted from, and whether it is the same extraction as `/root/slums-nav-v70/wz` — docs/PROVENANCE.md:325 records that the dump used for mechanics rows is **not** version-pinned to this branch.
- Whether live passes `-Dwz-path` at all: it appears in neither `SERVER_SET` nor `UNIT_EDITS` (docs/release/1.6.4.conf:201-216, :271), which would leave the server on the CWD-relative `wz/`. Not verifiable from this worktree.
- TEST's actual `-Dwz-path` — `start-test.sh` is on the test host and unreachable from here.
- Whether the missing 9,528 NPCs / 3,456 mobs can be re-extracted without breaking map ids the routing table and quests already reference.
- Why `List.wz` is enumerated but never read, and whether `Effect.wz`/`DamageSkin` should be deleted or restored.
