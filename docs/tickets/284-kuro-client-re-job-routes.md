# Kuro v83 client RE: class finishing kit, Evan gate method, and the job-addition route

Recorded 2026-11-09 (Pacific). Read-only reverse-engineering review. No game code, asset, WZ row or data
from Kuro is copied into this repository, nothing was executed or attached to, and their anti-cheat was not
touched. Owner request: "what would it take to port jobs over?" This ticket records the findings and the
proposed route; it implements nothing.


## Owner decisions (2026-11-09)

- **Route A approved** - the finishing-kit cards (Aran, Buccaneer, Dark Knight, Dawn Warrior) may be briefed for 1.7.0.
- **Route C approved as the job-addition route**: new jobs go through a reusable pipeline, with **Dual Blade as the
  pilot** - not one-off ports from Kuro. The five classes excluded by the roadmap stay excluded until the pilot
  proves the pipeline.
- **Evan and the remaining classes are expected to be tough** (owner). Evan stays decided and on its current branch;
  it must not block Route A or the Dual Blade pilot.
- Sequencing recorded: Route A now; Dual Blade pilot next; Evan and the rest after the pilot.
- **Deferred to post-1.7.0 (owner, 2026-11-09):** no job work before 1.7.0 ships - the 1.6.6 -> 1.7.0 window is
  already full. Route A and Route C are parked as ready-to-pick briefs (the Dual Blade pilot stays first when they
  resume). Nothing here should be picked up inside 1.7.0; Evan is unaffected because it is already in the 1.7.0 plan.


## 1. What was reviewed

Source: the installed Kuro client on Silvia, `Z:\MSv83\Client_Sources\kuro\Kuro` (v3.2.0, ~11 GB, v83 base +
MapleEzorsia v2). Three files were copied to 187 for study, never into the repo:

| File | sha256 | Size |
|---|---|---|
| `stock-v83-MapleStory.exe` (our pinned stock reference) | `86e4e6be050caeda4a93443237256a84376ea74781fcba558d2418f0568aac90` | 8,503,296 |
| `Kuro.exe` | `1198fa57ca5a7c489bae43ec13c69681d9cabe0f96762f3dc0357facf2e7d4df` | 8,503,296 |
| `Kuro-dinput8.dll` | `6c954e71cfc2eedd6c6f3633d0b896c1415ede3833fa14cd762203122c3f5073` | 11,832,832 |

Tooling: Ghidra 12.2 with JDK 25 (Corretto) on 187, headless. Both binaries imported and analysed; the exe
yields 98,305 functions, the DLL 1,208 functions whose code references an exe address were decompiled.
No dynamic analysis, no client attach, no anti-cheat interaction, no execution of their binaries.

## 2. Findings

1. **`Kuro.exe` is the stock v83 exe with four bytes changed** at file offset `0x396357` (VA ~`0x796F57`):
   `85 C0 E9 …` (`test eax,eax; jmp`) becomes `85 C0 0F 84 …` (`test eax,eax; je`). Same PE header,
   timestamp (2010-02-17), imports and sections. All client work lives elsewhere.
2. **The client mod is one Detours proxy DLL** (`dinput8.dll`): exports `DirectInput8Create`,
   `D3D8Wrapper_Initialize/Shutdown`, `DirectXBridge_Initialize/Shutdown`, `GetdfDIJoystick`,
   `KuroDamageSkinBindingHotPathVersion`; imports d3d9/d3dx9_43, Discord SDK, dbghelp, bcrypt/ncrypt;
   ships `.detourc`/`.detourd`; carries their own ImGui overlay and game code (`KuroPatch_Init/Shutdown`).
3. **Patch discipline is the same as ours**: hard-coded exe VAs, byte guards verified before install, code
   caves, explicit rollback and verification, every patch logged and switchable. Their logs name the site on
   mismatch ("byte guard mismatch at 0x…"). This is the AGENTS.md rule already enforced in `slums.dll`.
4. **Scale of the patch map**: 57 exe addresses appear in their own log strings; **47 of them are referenced
   from DLL code** (real patch points, not just messages). Across the DLL, 5,031 references to 1,901 distinct
   exe VAs were found; 1,208 referencing functions were decompiled.
5. **Client UI they built** (RTTI classes): `CHyperLegionWnd` (hyper skills), `CPhantomMemoryWnd`,
   `CKuroPrismWnd`, `CDamageSkinNativeWnd`, `CScrollWorkshopWindow`, `CStorageWnd`, `CMakerWnd`,
   `CTotemPanel`, `CDailyLoginWnd`, `COnlinePlayersWnd`, plus guards (`PetUiStyleGuard`,
   `NpcTemplateMissingGuard`, `SoundMissingNodeGuard`).
6. **Data model**: manifest-verified WZ set (manifest from their site), extra WZ files shipped beside the exe,
   and a "custom-action-safety" data-name swap that projects custom skill actions through Character.wz
   equipment data - i.e. they extend client data without editing stock WZ nodes.
7. **Their job roster**: Aran, Dual Blade, Cannoneer, Shade, Pathfinder, Mercedes, Phantom, Luminous, plus
   hyper skills. **No Evan** - verified: zero whole-word `Evan` in 31k strings, no Dragon/Evan window class.
   Their advertised class list has never included Evan. Evan is the one class that needs a whole client
   subsystem (dragon UI/skill tree), which is why their formula-patch approach skipped it.
8. **Per-job client work they needed** (site addresses from their logs; full map in the RE artefacts):

| Job | Client work |
|---|---|
| Aran | Combo Critical direct WZ formula + native formula patch, "Aran renumber" sites |
| Dual Blade | `get_weapon_mastery` prologue `0x00764795`, `get_skill_mastery` prologue `0x00764AFE` (Katara Expert) |
| Cannoneer | bullet-quantity cave `0x00949BF5`, instant projectile timing `5321000`, Barrel Roulette `5311004` |
| Buccaneer | primary attack native speed, Battleship speed inheritance, Blast Back backdash cave, Dragon Strike cooldown map |
| Mercedes | hook set (ballArt) |
| Pathfinder | Dodge native gate |
| Phantom | FastCast/MaterializedAction v1 + PhantomMemory window |
| Shade | skill-renumber sites `0x00957DC3`/`0x00957DDC` |
| Luminous | present, mostly UI/data driven |
| Hyper skills | `HyperLegionWnd` window (large) |

9. **Our roster, for comparison**: Explorers, Cygnus, Aran (`Job.java` 2100-2113, `constants/skills/Aran.java`,
   WZ 2100-2113), Evan (decided, in progress, branch `170-evan`), Legend. Our enum has **no 3xxx/4xxx groups**;
   no Dual Blade, Mercedes, Luminous, Cannoneer job class.
10. **The gaps this RE actually closes are ours, not new jobs.** Ticket 32 records Aran "only Final Blow at 4th
    job … no Aran combo counter" and Buccaneer as "bland" with missing skills. Those are exactly the sites
    Kuro patched. `docs/roadmap/1.7.0.md:87` still says: "Left out on purpose (don't start these): Dual Blade,
    Cannoneer, Mercedes, Demon Slayer, Resistance (no v83 ports exist, only skill covers)".

## 3. Route options

**Route A - finishing kit for classes we already ship (recommended now).** Use the Kuro sites as the patch map
for Aran combo/finishers, Buccaneer attack/cooldown behaviour, Dark Knight Dragon Roar/Berserk gates, Dawn
Warrior 4th-job fallbacks. No new content, no roadmap change, no owner decision beyond "do it". Closes named
ticket-32 gaps. All four are client-patch work in `slums.dll` plus small server-side verification.

**Route B - Evan (already decided, in progress).** Continues `170-evan` and `client-161-evan`. The RE does not
change the plan; it confirms the gate-patch approach is the right one and shows why nobody else on v83 has
Evan. Cost stays XL.

**Route C - new-job pilot (direction change).** Add one new job end to end, using Dual Blade as the pilot
because Kuro needed only two mastery hooks and no new window. Full pipeline: WZ data (the v170 dump already
has the job groups), server (`Job` enum, skill constants, handlers, advancement), client (two patches +
attack data), bots (`BotBuild`/`BotSkillPlan`/attack data), content (items, mastery books). XL. If it lands,
the next jobs are mostly repetition; if it fails, we learn where before spending four jobs' worth.

**Route D - not now.** Mercedes, Phantom, Luminous, Shade, Pathfinder, Cannoneer as individual ports. XL each;
Phantom needs a UI window; the roadmap excludes them. Park until Route C proves a pipeline.

## 4. Recommendation

1. Do **Route A** first (small, no direction change, measurable in-game).
2. Keep **Route B** (Evan) on its current track.
3. Revisit **Route C** only with an owner "go", starting with Dual Blade as the pipeline pilot.
4. Do not start Route D.

## 5. Proposed cards (for the release book; ids are the Coordinator's)

| Card | Work | Size | Depends |
|---|---|---|---|
| c-a | Aran finishing kit: combo counter, Smash/Fenrir/Tempest finishers, renumber sites | S-M | ticket 32, client 161+ |
| c-b | Buccaneer: native speed, cooldown map, Dragon Strike/Blast Back behaviour | S | ticket 32 |
| c-c | Dark Knight: Dragon Roar HP gate and Berserk activation behaviour | S | - |
| c-d | Cygnus: Dawn Warrior 4th-job cash fallback closures | S | - |
| c-e | Job-addition pipeline + Dual Blade pilot | XL | owner decision |
| c-f | RE rig reproducible notes (Ghidra 12.2 + JDK 25, scripts, artefact paths) | XS | - |

## 6. Questions for the owner

1. Approve Route A (the four finishing-kit cards) for 1.7.0?
2. Route C: keep the roadmap exclusion, or scope a Dual Blade pilot? If yes, which release.
3. If Route C is scoped, confirm Dual Blade first, then the order for the rest.
4. Evan keeps its current priority, or moves behind Route A?

## 7. Constraints and risks

- Clean-room only: we take behaviour and addresses-for-verification, never bytes, strings, assets or WZ rows
  from their client. Our own stock exe and WZ remain the source of truth; every hook is verified against our
  pinned stock exe SHA256 the way AGENTS.md requires.
- Their anti-cheat (integrity check, TPM attestation, anti-debug, tool blocklist) was not touched; no
  dynamic analysis was done and none is planned.
- Their binary changes weekly; every address in this ticket is a lead, to be re-verified against our exe
  before any patch is written.
- RE rig lives on 187 and needs JDK 25 + Ghidra 12.2; do not install it on Silvia.

## 8. Artefacts (187, outside the repo)

- `/root/work/rea-kuro/triage/REPORT.md` - binary triage, hashes, 4-byte exe patch, string inventory
- `/root/work/rea-kuro/CLIENT-PATCH-MAP.md` - architecture + feature-to-site map
- `/root/work/rea-kuro/ghidra-out/hook-map.tsv` - 1,901 referenced exe VAs and their containing function
- `/root/work/rea-kuro/ghidra-out/sites/*.c` - decompiled context for the named sites
- `/root/work/rea-kuro/ghidra-out/Kuro-dinput8.dll-refs-decompiled.c` - the 1,208 decompiled functions
- `/root/work/rea-kuro/ghidra-proj` - analysed Ghidra project; scripts in `ghidra-scripts/`

## 9. Acceptance for this ticket

Docs only. Done when the owner answers section 6, the Coordinator has the card list, and the finishing-kit
briefs (cards c-a..c-d) each carry the exact site addresses and a fail-before plan. No code lands here.