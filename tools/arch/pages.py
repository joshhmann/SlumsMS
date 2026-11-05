HUB = "https://claude.ai/artifact/TetRFuyLqi5UGzDDbWh74r"
SRC = "Mapped Nov 5 2026 from release-1.6.4 (447f5c6b1) and the lane repos; read-only checks on TEST and live the same night; book cards from the 1.6.4 release book."

def ul(*items):
    return "<ul>" + "".join(f"<li>{i}</li>" for i in items) + "</ul>"

def flow(*items):
    return '<ol class="flow">' + "".join(f"<li><span>{i}</span></li>" for i in items) + "</ol>"

def grid(*cols):
    return '<div class="grid">' + "".join(f"<div><h3>{h}</h3>{b}</div>" for h, b in cols) + "</div>"

PAGES = {}

PAGES["server"] = dict(
    title="SlumsMS Game Server",
    lede="The Java server players connect to: a Cosmic (HeavenMS-line) v83 server forked at Cosmic b46912afc and repacked as the MapleRoot baseline, with the SlumsMS layer (<code>soloMapling/</code>) built on top. Bot AI has its own page.",
    mermaid="""flowchart LR
  CL["Game client"] -- "encrypted packets" --> LS["Login server :8485"]
  CL --> CH["Channel servers :7585+"]
  LS & CH --> NP["Netty pipeline<br/>PacketCodec (AES-OFB)"]
  NP --> PP["PacketProcessor<br/>RecvOpcode -> handler"]
  PP --> H["~150 channel handlers"]
  H --> W["World / Channel / Map<br/>Character, mobs, NPCs, reactors"]
  W --> SC["Graal JS scripts<br/>npc, quest, portal, event, reactor"]
  W --> WZ["WZ XML data<br/>+ MobStatOverrides"]
  W --> DB[("MySQL via Hikari<br/>migrations 001-065")]
  W --> SM["SlumsMS systems<br/>mechanics, exchange, alts, routing,<br/>fishing, achievements, site/dashboard export"]
  SM --> BOT["Playerbots (own page)"]
  W --> QA["!qa scenarios + auto-QA"]""",
    sections=[
        ("boot", "Boot and shutdown", flow(
            "<code>Server.main</code> sets up GraalVM, then <code>init()</code>: time zone, the Hikari DB pool, packet handler dependencies.",
            "Data loads in parallel: skills, cash items, quests, skill books, player NPCs.",
            "Startup SQL resets (everyone logged out, merchants closed), timers, worlds and channels, families.",
            "Login listener on 8485, then the SlumsMS services: site export, site auth API, inspect API, dashboard export, exchange, market, board, PQ lobbies, bot director.",
            "Shutdown saves real players first, then owned alts, then bots under a time budget. There is no hourly autosave; saves happen on logout and shutdown.")),
        ("net", "Network and packets", flow(
            "Login and channel servers run on Netty; the client gets an unencrypted hello with the IVs.",
            "<code>PacketCodec</code> decrypts (AES-OFB plus the v83 custom cipher).",
            "<code>Client.channelRead</code> reads the opcode; <code>PacketProcessor</code> (one per world and channel) finds the handler by <code>RecvOpcode</code>.",
            "The handler validates state, changes the world, and replies through <code>PacketCreator</code>.")),
        ("world", "World, data and scripts", grid(
            ("World model", ul("<code>World</code>, <code>Channel</code>, maps, <code>Character</code>, inventories, skills.", "Rates per world in config (EXP, meso, drop, boss drop, quest).", "Map routing (custom): <code>MapRouting</code> + <code>map-routing.tsv</code> blocks client-crash maps and sends players back.")),
            ("Data", ul("WZ XML from <code>-Dwz-path</code> (else <code>wz/</code>).", "<code>MobStatOverrides</code>: ~400 newer-area mobs get classic-like HP and EXP.", "Server-side WZ fixes from <code>portal-fixes.tsv</code> (applied by a tool, e.g. Crack in Time).")),
            ("Scripts", ul("Graal JS, one engine per script file.", "<code>scripts/npc, quest, portal, reactor, item, map, event</code>.", "Cosmic diff tool tracks which base scripts were restored or changed on purpose.")),
            ("Persistence", ul("MySQL, Hikari pool; bots get a capped share (<code>BotDbGate</code>).", "Migrations <code>database/migrations</code> 001-065 with rollback pairs; a ledger table records what ran.")))),
        ("custom", "SlumsMS systems on top", grid(
            ("Content", ul("PQ and boss engine (<code>soloMapling/mechanics</code>): one YAML row per PQ or boss room.", "Fishing, achievements, daily check-in, families and family buffs.")),
            ("Economy", ul("Exchange with tickers, the NX desk, the 1.6.4 item catalog (706 tickers on TEST).", "Merchants, market events, price book.")),
            ("Players and alts", ul("Owned alts (<code>soloMapling/companions</code>): summon, fight, loot, weddings, bonds, EXP bonus.", "Cash shop catalog (<code>cashshop/catalog.tsv</code>).")),
            ("Outside feeds", ul("Site export, site auth API (127.0.0.1:8186), board inbox/outbox, exchange site orders.", "Dashboard <code>live.json</code>, inspect API, bug reports file.")))),
        ("qa", "QA, commands and config", grid(
            ("QA", ul("<code>!qa</code>: 45 staged scenarios (<code>soloMapling/qa</code>).", "Auto-QA: ~197 checks, release runs, probes, an LLM judge for chat; results in <code>data/autoqa/latest.json</code>.")),
            ("Commands", ul("<code>@</code> player commands, <code>!</code> GM commands by level gm0-gm6.", "GM actions are audit-logged.")),
            ("Config", ul("<code>config.yaml</code> (~200 server keys, worlds block).", "Feature switches as <code>-D</code> flags in the unit; the release conf carries them.")),
            ("Tests", ul("Maven + surefire, forks of 4-8; 12,137 tests green on the release tip (Nov 5)."))))
    ],
    rows=[
        ("Boot, init, shutdown saves (<code>Server.java</code>)", "modified", "working", "Live runs it; 43 commits on top of base"),
        ("Netty pipeline and packet encryption", "base", "working", "Unchanged base"),
        ("PacketProcessor and opcode routing", "modified", "working", "Live"),
        ("Packet handlers (~150)", "modified", "partial", "Audit fixes b289-b298 on TEST (leader check, MP gaps, Maker level gate, storage save)"),
        ("World, maps, life (mobs, NPCs, reactors)", "modified", "partial", "b307 Furious Targa/Scarlion won't die; b274 Will/Magnus portals"),
        ("Map routing (crash-map guard)", "custom", "working", "63 routes on TEST and live; deploy refuses a release that drops one"),
        ("WZ data + MobStatOverrides", "modified", "working", "Live since Oct 28; attack/defence still WZ (e10)"),
        ("Server WZ portal fixes", "custom", "partial", "Crack in Time fix applied on TEST; release templates lack a WZ step (manual in the playbook)"),
        ("Graal JS scripting engine", "base", "working", "Unchanged base"),
        ("NPC/quest/event scripts", "modified", "partial", "Holiday PQ can't finish, Elnath PQ has no stages, b309 Pio quest, b314 Aldol, b310 Cygnus emblem"),
        ("MySQL + Hikari pool", "modified", "working", "Live"),
        ("Migrations + ledger", "custom", "working", "001-065; b288 rollback fix merged (only undoes what ran)"),
        ("PQ and boss engine (mechanics)", "custom", "partial", "18/26 PQs and 30/31 bosses have rows; Von Leon, Cygnus, Hard Lotus, Balrog Easy, Chaos Horntail missing; t-ex-2/t-ex-3 PASS"),
        ("Exchange, NX desk, item catalog", "custom", "partial", "Catalog on TEST only (waits for the site filter); b287 scroll traders; b302 fixed"),
        ("Owned alts, weddings, bonds, EXP bonus", "custom", "working", "On TEST; alt-out @bonds fixed tonight; b311 Horntail alts to check; b305 romance"),
        ("Cash shop catalog", "modified", "partial", "b315 effects/VIP haircut don't show (fix on an old branch, conflicts)"),
        ("Fishing", "custom", "unknown", "On TEST only; no recent status"),
        ("Achievements, daily check-in", "custom", "unknown", "No recent evidence; check-in default off"),
        ("Families and family buffs", "modified", "unknown", "No recent evidence"),
        ("Site export, site auth API, board", "custom", "working", "Live: site export on, auth on 8186, site services up"),
        ("Dashboard export (live.json)", "custom", "working", "On for live and TEST; feature-health reads it"),
        ("Inspect API", "custom", "working", "On for live (127.0.0.1)"),
        ("Bug reports (in-game + site)", "custom", "working", "Live reports file has 19 entries (B-10..B-19)"),
        ("!qa scenarios + auto-QA", "custom", "working", "Release run 186 PASS / 0 FAIL; full run on the mirrored TEST in progress"),
        ("Unit tests", "modified", "working", "12,137 tests green (one flaky timing test)"),
    ],
    weak=[
        "<b>Content gaps:</b> Holiday PQ, Elnath PQ stages and five boss fights have no working data (1.6.5 cards n10-n13).",
        "<b>Live player reports:</b> b307 bosses that won't die, b306 mounts, b310/b309/b314 quests and NPCs, b315 cash shop looks (1.6.5).",
        "<b>WZ fixes are a manual step</b> on release night (no template step since 1.4.1).",
        "<b>Unknowns:</b> fishing, achievements, check-in and families have no recent checks; worth an auto-QA pass.",
    ],
    sources=SRC,
)

PAGES["ops"] = dict(
    title="SlumsMS Ops and Release",
    lede="Where everything runs, how code reaches TEST and live, how the client and launcher are published, and how we watch and back it all up.",
    mermaid="""flowchart LR
  DEV["Dev box 187<br/>repos, worktrees, agents"] -- "deploy-test" --> TEST["TEST mapletest .111<br/>game + DB + test site + dashboard"]
  DEV -- "build, stage, rehearse" --> STG["164 staging"]
  STG -- "release-live.sh (owner go)" --> LIVE["LIVE 164<br/>slumsms + DB :3309"]
  SIL["Silvia (owner PC)<br/>client + launcher builds"] -- "stage-client" --> STG
  STG -- "publish-client / publish.mjs" --> PATCH["Patch feeds /srv/slums-patch<br/>Live, Test, RC (401 without key)"]
  LIVE -- "nightly 03:30" --> BK["maplebackup .122"]
  LIVE --> MON["slumsms-monitor<br/>every minute"] --> CODY["Discord: Cody"]
  LIVE <-.-> LLM["Local LLM .17 + DeepSeek API"]
  CADDY["Caddy (TLS) + pfSense"] --> LIVE""",
    sections=[
        ("hosts", "Hosts", '<div class="tbl"><table><tr><th>Host</th><th>Runs</th></tr>'
            "<tr><td>187 (this box)</td><td>All repos and worktrees, builds, agent sessions and helpers</td></tr>"
            "<tr><td>164 live</td><td>Game <code>slumsms</code> (login 8485), DB container <code>buns-clean-db-1</code> :3309, patch nginx :8090, site :8092 + login/market/report services, staging, monitor</td></tr>"
            "<tr><td>mapletest .111</td><td>TEST game (login 8489), DB :3307, test site :8192, dashboard :8091, auto-QA</td></tr>"
            "<tr><td>maplebackup .122</td><td>Nightly append-only backups</td></tr>"
            "<tr><td>Silvia .186</td><td>Owner's PC; builds the client DLL and launcher</td></tr>"
            "<tr><td>tarkov2 .175</td><td>Windows VM for client QA (TEST only)</td></tr>"
            "<tr><td>.17</td><td>Local LLM (qwen9b), intent models</td></tr>"
            "</table></div>"),
        ("flow", "Release flow", flow(
            "Work on a branch in its own worktree; Server Dev merges into <code>release-&lt;ver&gt;</code>.",
            "<code>tools/ops/deploy-test</code> to TEST (backs up, applies migrations, fails on boot errors); auto-QA and feature-health.",
            "<code>build</code> → <code>stage &lt;ver&gt;</code> (needs <code>docs/release/&lt;ver&gt;.conf</code>) → <code>rehearse</code> on a copy of live, with rollback.",
            "Owner go: <code>release-live.sh</code> runs stop-backup → extra steps → apply-start → smoke → maintenance end.",
            "Client and launcher publish and the site are separate go steps.")),
        ("guards", "Safety rules built into the tools", ul(
            "TEST deploy refuses live paths; feature-health refuses live without a read-only flag.",
            "A release can't drop a crash-map route or set a TEST-only routing override.",
            "Migrations: the ledger records what ran; rollback undoes only this deploy's migrations (b288).",
            "Client publish: hash-diff against the standalone client, live endpoint check, news checked first (b052), layers immutable.",
            "Minimum client build gate (1.6.4: 10740), never lowered without a flag.",
            "Patch feeds answer 401 without the key.")),
        ("watch", "Watching and backups", ul(
            "<code>slumsms-monitor</code> on live and TEST every minute: unit, login port, log silence (b268), heap, DB, disk, bots, LLM, backups; posts status and alerts.",
            "Nightly DB + jar + config backup to .122; rotation; monthly restore drill (last Oct 20).",
            "Dashboard <code>live.json</code> every 30 s; <code>feature-health</code>, <code>test-status</code>, auto-QA queue on TEST.")),
        ("lanes", "People and agents", ul(
            "Sessions: Server Dev (merges, TEST, releases), Coordinator (release book), Client, Website, bug triage.",
            "Helpers: Haiku explores, Sonnet builds; external DeepSeek (omp), Gemini, Codex; all reviewed before merge.",
            "Rules: live needs the owner's go per action; TEST and merges don't; secrets never pass through agents.")),
    ],
    rows=[
        ("Live game + DB on 164", "base", "working", "Live; base server repack with SlumsMS on top"),
        ("TEST server + DB on mapletest", "custom", "working", "Runs release tip 447f5c6b1, mirrors live switches since Nov 5"),
        ("TEST deploy (deploy-test)", "custom", "working", "Used 8 times tonight, 0 boot errors"),
        ("Release pipeline (build, stage, rehearse, release-live.sh)", "custom", "working", "1.6.3 shipped with it; 1.6.4 rehearsal pending"),
        ("Migration ledger + rollback", "custom", "working", "b288 fixed tonight; proof in the rehearsal"),
        ("Routing guard + min client gate", "custom", "working", "gen-deploy + smoke; 1.6.4 gate 10740"),
        ("Client publish (stage, verify, publish-client)", "custom", "working", "b052/b270 news check merged tonight"),
        ("Patch feeds + basic auth", "custom", "working", "All feeds 401 without key"),
        ("slumsms-monitor + Cody", "custom", "working", "Live and TEST timers running every minute (checked Nov 5)"),
        ("Nightly backups to .122", "custom", "working", "Backup timer ran Nov 4 11:04 UTC"),
        ("Restore drill", "custom", "partial", "Last receipt Oct 20; monthly, next due ~Nov 20"),
        ("DNS (router first)", "custom", "partial", "Live + TEST changed Nov 5; Proxmox container setting not set, a restart may revert"),
        ("Local LLM + cloud chat", "custom", "working", "TEST chat failures 0% after the DNS fix"),
        ("Windows QA VM (tarkov2)", "custom", "partial", "Client QA runs; real-client checks still need the owner's PC at times"),
        ("Server WZ fix step", "custom", "partial", "Manual on release night (portal-fixes.tsv)"),
    ],
    weak=[
        "<b>Proxmox DNS:</b> set the containers' DNS to 192.168.0.1 so a restart keeps the fix.",
        "<b>Release WZ step</b> is manual; add it to the templates in 1.6.5.",
        "<b>Backups share the Proxmox node</b> with live (same physical machine) per the backup notes.",
    ],
    sources=SRC,
)

PAGES["client"] = dict(
    title="SlumsMS Game Client",
    lede="The stock 32-bit v83 client, started by <code>SlumsMS.exe</code> with our <code>slums.dll</code> injected, plus our data in <code>Custom.wz</code>. Current RC: 1.6.4-rc3, build 10740.",
    mermaid="""flowchart LR
  L["Launcher"] --> S["SlumsMS.exe (starter)"]
  S --> E["MapleStory.exe<br/>stock v83, 32-bit"]
  S -- "injects" --> D["slums.dll"]
  D -- "Detours hooks + checked byte patches" --> E
  D --> CFG["config.ini<br/>[config] host/port, [clienthax] ..."]
  E --> WZ["Base WZ data (~23.6 GB)"]
  E --> CWZ["Custom.wz<br/>mob fixes, tooltips, art"]
  D -- "build stamp 10740" --> SRV["Server login check"]""",
    sections=[
        ("pieces", "Pieces", ul(
            "<b>MapleStory.exe</b>: the stock client, unchanged on disk.",
            "<b>SlumsMS.exe</b>: starts the client with <code>slums.dll</code>.",
            "<b>slums.dll</b>: all our client changes, built from <code>src/*.cpp</code> (Kaentake-based, plus the MapleRoot companion code).",
            "<b>config.ini</b>: server address and player settings; player-owned (the launcher never overwrites it).",
            "<b>Custom.wz</b>: our data on top of the base WZ; built by Python tools in <code>tools/wz</code>.",
            "<b>slums-build.txt</b> and the build stamp: 10000 + 7×100 + 40 = 10740 for 1.6.4-rc3; sent before the password so the server can refuse old clients.")),
        ("dll", "Inside slums.dll", ul(
            "Hooks via Microsoft Detours, plus code caves and byte writes at v83 addresses, each checked against stock bytes first; a failed check patches nothing.",
            "4-byte HP/MP, resolution and borderless, D3D9 host, HUD/overlay/layout tweaks, inventory resize, weather, mob damage display.",
            "Crash guards: mob attack info guard (Rose Garden, b251), missing map-object skip (Crack in Time, b300).",
            "QA signals for the client QA driver (map ready, chat, windows).",
            "Crash reporting (ticket 121).")),
        ("build", "Build, test and ship", flow(
            "Built on Windows (Visual Studio 2022, CMake presets) on Silvia.",
            "<code>ctest</code>: ~40 test files; rc3 36/36.",
            "Hash-diff against the standalone client; only <code>slums.dll</code>, <code>Custom.wz</code>, <code>slums-build.txt</code>, <code>config.ini</code> may differ.",
            "Staged to 164, published to RC first, Live on the owner's go.")),
        ("qa", "Client QA", ul(
            "Client MCP driver + QA worker on tarkov2 or Silvia, running YAML client-look scripts against TEST.",
            "The owner's own click-through is still the real proof for new content.")),
    ],
    rows=[
        ("MapleStory.exe (stock v83)", "base", "working", "Unchanged; byte checks against it"),
        ("SlumsMS.exe starter", "custom", "working", "RC rc3"),
        ("slums.dll injector + hooks", "custom", "working", "rc3 ctest 36/36; owner on RC"),
        ("config.ini (server address, settings)", "custom", "working", "Server follows the channel with launcher 1.6.5 (b301)"),
        ("Custom.wz", "modified", "working", "c2c4268d on RC; mob fixes, LKC tooltip, Time Temple art"),
        ("Rose Garden crash guard (b251)", "custom", "working", "Owner ran 211080100-211080700 with no crash; route stays until live has 10740"),
        ("Missing map-object skip (b300)", "custom", "partial", "Crack in Time loads; 10 crack objects still skipped (art in 1.6.5)"),
        ("Mob attack type data", "modified", "partial", "44 attacks without range data left out (none on placed mobs)"),
        ("4-byte HP/MP", "custom", "working", "Since 1.6.0"),
        ("HUD, overlay, layout, inventory resize, weather", "custom", "unknown", "README only; no recent checks"),
        ("QA signals", "custom", "partial", "Byte-verified; in-game names unconfirmed"),
        ("Build stamp / version gate", "custom", "working", "10740 = 1.6.4-rc3; live will require it"),
        ("Crash reporting", "custom", "unknown", "Only its test is cited"),
        ("Will/Magnus boss portals (b274)", "base", "broken", "Client sends no portal-enter; server-side fix in 1.6.5"),
        ("Client size (23.6 GB loose files)", "base", "partial", "First-install packs deferred"),
    ],
    weak=[
        "<b>b274</b> Will and Magnus portals never reach the server (1.6.5, server side).",
        "<b>Unknowns:</b> HUD/overlay/weather features and crash reporting have no recent checks; add client-look scripts.",
        "<b>Time Temple crack art</b> and the Coloring Prism name are 1.6.5.",
    ],
    sources=SRC,
)

PAGES["launcher"] = dict(
    title="SlumsMS Launcher",
    lede="The Windows launcher players install: Tauri 2.12 (Rust core + web UI). It installs and updates the client per channel, launches the game and updates itself. Live: 1.6.4. RC/Test: 1.6.5.",
    mermaid="""flowchart LR
  UI["Web UI<br/>news, Slums-chan, sign-in, report"] --> CORE["Rust core (slums-core)<br/>manifest, install, packs, play, selfupdate"]
  CORE -- "Basic auth (key)" --> FEED["Patch feeds on 164<br/>Live / Test / RC manifests"]
  FEED --> LAY["Layers: base (~23.6 GB) + client"]
  CORE -- "verify SHA-256, stage, move" --> DIR["Game folder per channel"]
  CORE -- "keeps hand edits;<br/>1.6.5 merges host/port" --> INI["config.ini"]
  CORE -- "ShellExecute" --> GAME["SlumsMS.exe"]
  CORE -- "signed update" --> SELF["Launcher self-update"]
  UI --> SITE["Site login / join / report"]""",
    sections=[
        ("stack", "Stack", ul(
            "Tauri 2.12 app: Rust core <code>tauri/core</code> (~8k lines: manifest, release, install, packs, play, channels, game_config, selfupdate, http, site), shell <code>tauri/app</code>, web UI <code>tauri/ui</code>.",
            "Windows x64 only; built on Silvia; core tests run on Linux.",
            "The old Electron launcher is retired; a hand-over build moves old installs.")),
        ("channels", "Channels and manifests", ul(
            "Live (root feed), Test and RC (dev key). Each channel has its own game folder and state.",
            "Manifest v2: version, launch, news (max 10), playerOwned, minClientBuild, layers (base + client, each with its own manifest and SHA-256).",
            "Basic auth to the feed only; 401/403 means the key is missing.")),
        ("install", "Install, update, play", flow(
            "Load and validate the channel manifest.",
            "Compare with what's installed; plan downloads (whole packs for a first install, per file for updates).",
            "Download to staging, verify SHA-256, move into place.",
            "<code>config.ini</code> is installed once and kept; 1.6.5 copies only the channel's host/port and keeps hand edits (b301).",
            "Play: quick size/date check, then launch <code>SlumsMS.exe</code>; Repair does a full hash check.")),
        ("self", "Self-update and publishing", ul(
            "Signed updates (Tauri updater, minisign), signature bound to the version; rollback folder kept.",
            "Friends build has the access key baked in; the owner runs the bake script.",
            "<code>publish.mjs</code> publishes layers, releases and launcher updates; <code>publish-client</code> wraps it with the hash-diff and safety checks.")),
    ],
    rows=[
        ("Tauri framework + updater signing", "base", "working", "Tauri 2.12.5; signature checked"),
        ("Rust core (install, packs, play, channels)", "custom", "working", "129 core tests pass (1.6.5)"),
        ("Web UI (news, Slums-chan, account)", "custom", "working", "Launcher 1.6.4 live"),
        ("Channels Live / Test / RC", "custom", "working", "RC used for 1.6.4-rc1..rc3 tonight"),
        ("Manifest v2 + layers", "custom", "working", "Live and RC manifests"),
        ("config.ini server per channel (b301)", "custom", "partial", "Fixed in 1.6.5 on RC/Test; Live on the owner's go after his switch check"),
        ("First-install packs", "custom", "partial", "In code; not on Live yet"),
        ("Friends build (baked key)", "custom", "working", "1.6.4 friends exe handed out"),
        ("Sign-in / join / report links", "custom", "working", "Live; report fields b064 need the site"),
        ("Auto-login", "custom", "partial", "1.7.0 prep only"),
        ("Electron hand-over", "custom", "partial", "Old installs still on the hand-over build"),
        ("Defender flags the exe (b017)", "base", "broken", "Friends see Phonzy.A!ml; workaround is an exclusion; no paid code signing"),
        ("Small open bugs b018, b019, b020", "custom", "partial", "--install-dir forces Live; fresh data dir imports Electron settings; flaky core tests"),
    ],
    weak=[
        "<b>Defender false positive</b> (b017) still hits new friends.",
        "<b>b301</b> fix is on RC/Test only until you check Live → RC → Live and give the go.",
        "<b>Packs</b> for faster first installs are built but not live.",
    ],
    sources=SRC,
)

PAGES["website"] = dict(
    title="SlumsMS Website",
    lede="The public site, the signed-in player pages and the admin pages: a stdlib-Python static site, rebuilt every 2 minutes from the game's exports, plus three small services on 164.",
    mermaid="""flowchart LR
  GAME["Game server"] -- "site export, market export<br/>(JSON files)" --> BUILD["build.py on 164<br/>timers every 2 min"]
  BUILD --> STATIC["/srv/slums-site (nginx :8092)"]
  CADDY["Caddy TLS<br/>theslumsms.asslorde.com"] --> STATIC
  STATIC --> LOGIN["slums-login :8094"] -- "verify / signup" --> AUTH["game auth API :8186"]
  STATIC --> MARKET["slums-market :8095 (SQLite)"]
  MARKET -- "inbox: posts, exchange requests" --> GAME
  GAME -- "outbox: board, offers, exchange answers" --> MARKET
  STATIC --> REPORT["slums-report :8093"] --> RFILE["bug reports file"]""",
    sections=[
        ("stack", "Stack", ul(
            "Static generator <code>tools/site/build.py</code> with section modules (notes, guide, world, database, market, admin); <code>check.py</code> blocks broken links, forbidden words and outside loads.",
            "nginx :8092 on 164 behind the owner's Caddy (TLS) at <code>theslumsms.asslorde.com</code>; TEST site on .111:8192.",
            "Services on 164: <code>slums-login</code> :8094, <code>slums-market</code> :8095 (SQLite), <code>slums-report</code> :8093.")),
        ("pages", "Pages", grid(
            ("Public", ul("Home, patch notes, guide, World (live world, feed, rankings, economy), report form, join, sign-in, forgot password.")),
            ("Signed in", ul("Slums Street (database), Slums Market (board, watchlist, alerts), the exchange (desk quote, orders, history), My cards.")),
            ("Admin (GM 3+)", ul("Reports, economy, staff dashboard. 1.6.5: the bot Decisions page (e18).")))),
        ("data", "How it talks to the game", ul(
            "The game writes JSON exports (world, market); timers rebuild those pages every 2 minutes.",
            "Orders: the site writes exchange requests to an inbox; the game answers into an outbox; the browser polls for the answer (preview, then confirm within 60 s).",
            "Board posts the same way: inbox from the site, outbox from the game.",
            "Sign-in: the login service asks the game's auth API; sessions are signed; revocations shared with the game.")),
        ("rules", "Rules", ul(
            "Never public: account names, emails, IPs, birthdays, PICs, private chat. In-game character and guild names are fine.",
            "Public copy never names the original game or its makers.",
            "Each live site step needs the owner's go.")),
    ],
    rows=[
        ("Site generator + checks", "custom", "working", "Tests pass; live site built by it"),
        ("nginx + Caddy TLS", "base", "working", "Live (nginx active, checked Nov 5)"),
        ("Home, patch notes, guide", "custom", "working", "Live; patch notes through 1.6.3"),
        ("World pages (live feed, rankings)", "custom", "working", "World timer active on live; site export on"),
        ("Slums Street (database)", "custom", "working", "Live; rename to /street/ waits on site-164-street (w06, 1.6.5)"),
        ("Slums Market + board", "custom", "working", "slums-market active on live"),
        ("Exchange pages (desk, orders)", "custom", "working", "Live desk; catalog items on TEST (L16 at check, b302 fixed)"),
        ("Exchange item catalog browser", "custom", "partial", "On TEST (site-164-exitems); live waits for release order"),
        ("Sign-in, join, forgot password", "custom", "working", "slums-login active; auth API on 8186"),
        ("Bug report form + service", "custom", "working", "slums-report active; reports arriving (B-19 tonight)"),
        ("Admin + staff dashboard", "custom", "working", "Live; dashboard export on"),
        ("Admin Decisions page", "custom", "partial", "Planned for 1.6.5 (e18)"),
        ("Slums-chan cards and stickers", "custom", "working", "Site update live 08:17 UTC Nov 5"),
        ("Icons cache", "custom", "partial", "Deploy script warns --delete can wipe icons (it happened Oct 29)"),
    ],
    weak=[
        "<b>Catalog browser</b> must ship before the exchange catalog switches go live (release order rule).",
        "<b>Icons</b> can be wiped by a careless deploy; keep the exclude.",
        "<b>Admin Decisions page</b> is 1.6.5 (e18).",
    ],
    sources=SRC,
)

PAGES["bots"] = dict(
    title="SlumsMS Playerbots",
    lede="Roster bots and owned alts. The full architecture is on its own page; this is the origin and state table that feeds the hub.",
    mermaid="""flowchart LR
  POP["Population director"] --> TICK["Tick engine"] --> CORE["Decision core<br/>GOAP + utility"] --> ACT["Activities"]
  TICK --> SOC["Social"] --> CHAT["Chat"]
  ALT["Owned alts (200 ms)"] -.-> ACT""",
    sections=[("more", "Full architecture", '<p>See the SlumsMS Playerbot Architecture page for every layer.</p>')],
    rows=[
        ("Population director + roster", "custom", "working", "~2,400-3,000 bots on TEST"),
        ("Tick engine (virtual threads, 250 ms combat)", "custom", "working", "TEST tick cost within limits"),
        ("Bot state machine + training bots", "modified", "partial", "11 of 2,791 bots off their foothold"),
        ("Decision core (LifePolicy, LifePlanner)", "custom", "partial", "Options scored before feasibility: b318 HANG_OUT 172 of 2,734 run"),
        ("Learning (LifeLearning)", "custom", "partial", "Failures that aren't the bot's fault still teach dislike"),
        ("Social acts + budget", "custom", "working", "INVITE and SHARE_INFO fixed tonight; t-sa-1 PASS"),
        ("Bonds, gossip, memory, drama", "custom", "working", "Auto-QA PASS"),
        ("Chat: reply planner + router + say-do", "custom", "working", "t-pl-1/t-cv-1 PASS; say-do 100% backed; cloud failures 0% after DNS"),
        ("Canned town chatter", "custom", "partial", "b317 claims not backed by state"),
        ("PQ and boss runs with bots", "custom", "working", "t-ex-2, t-ex-3 PASS; KPQ proven in client"),
        ("Owned alts (follow, fight, loot, buffs)", "custom", "partial", "b311 Horntail alts; b285 alt upgrade handover"),
        ("Exchange traders", "custom", "partial", "b287 some trader types never sell scrolls"),
        ("Decision trace / review", "custom", "partial", "Counters only; trace + admin page are e18"),
    ],
    weak=["<b>Core first:</b> feasibility before scoring, per-activity targets, fair learning (e18, b318)."],
    sources=SRC,
)
