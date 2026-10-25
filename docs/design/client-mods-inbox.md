# Community client mods: inbox and plan

Mods the owner collected (2026-10-25). Originals: `/root/work/client-mods-inbox/originals/` on 187 (never run; read
only). Our client DLL (`/root/work/client/src`, `slums.dll`) is **Kaentake-based** (`src/hook.h` ATTACH_HOOK /
PatchJmp / PatchMemory, `src/wvs/config.h`, avatar, CWndMan hooks), the same lineage as most of these mods, so
ports are mostly mechanical. Every client change: verify addresses/bytes against our client, ship through the
launcher, hash-diff against the standalone client before publishing, check on a real client (Start-QA).

| Mod | What it does | Side | License | Target | Notes |
|---|---|---|---|---|---|
| Inventory resize (`invresize.zip`) | Drag the item inventory's edges to 4-16 columns x 3-14 rows; size saved per character | client only | none stated | **1.6.0** | Replaces only CUIItem::GetSlotRect / GetSlotPositionFromPoint; detours CUIItem::OnCreate (check nothing else in slums.dll/MapleRoot hooks it); byte guard falls back to stock 4x6; removes the 4-tab "full" view |
| 4-byte HP/MP (`4byte-hp-mp-GW_CharacterStat.md`) | Lifts the client's 32,767 HP/MP limit to 99,999 | **client + server** | none stated | **1.6.0 (decide)** | Our server caps max HP/MP at 30,000 (`Character.java:9057`) and sends shorts; the client and server must change in the same release. Only worth it if we want HP/MP past 30k (MapleRoot buffs, rebirths). Hybrid FakeTear + Fuse hook (crash notes in the doc) |
| D3D8 -> D3D9 wrapper + ImGui (RageZone tutorial, page 403 to us) | DirectX 9 for old clients (Parsec/remote display fix) and ImGui windows (settings window, overlays) | client | tutorial | **1.6.0 (option)** | Alternative to dgVoodoo2 (ticket 39) and the base for the in-client settings window (`src-160-settings`); owner to save the page text |
| UI button animations (`ui-button-animations.rar`) | Animated frames for Cash Shop / CSS / WS / 100NX / 250NX / gacha buttons | client art | none stated | **1.6.0 (small)** | Needs the button hook it was made for; pairs with our cashshopwnd.cpp |
| Login logo art (`login-logo-assets.rar`) | Upscaled logo, rights line, book image (login screen) | client art | none stated | 1.6.0 (small) | RAR uses a method our 7z can't unpack: needs `unrar` |
| Custom character actions (`CustomActions.zip`) | Give a skill a brand-new swing/pose by reusing dead slots of the 162-entry action table; example 4121011 / 1121007 | client only | none stated | 1.7.0 | Hooks CActionMan::Init and CUserLocal::DoActiveSkill (the MapleRoot layer has a commented-out DoActiveSkill hook); needs WZ frames under Character/00002000.img |
| Native Hyper Skill tab (`hyper-skill-tab-community.zip`, KaiokenMS) | HYPER button + second native skill window, separate Hyper SP, reset, GM grant | client + server + DB | none stated | 1.7.0 | MapleRoot has 51 "Hyper Skill" entries in Skill.wz; server half is built around KaiokenMS skills; owner decides how hyper points are earned |
| NpcForge (github.com/DRSCRiPT4u/NPC-Forge) | One PNG -> animated NPC (stand/speak), Npc.wz XML | tooling | MIT | 1.7.0 | Pairs with the WZ Mod Tools (ticket 83) for client+server NPC data |
| Familiar cards (`familiar-card-drops.sql`, `Item-Consume-0238-familiar-cards.img`) | ~250 mobs drop their Familiar card (2%); card item data | server + client data | none stated | not planned | Familiars are "left out on purpose" (need windows and a summon system the v83 client lacks); keep for reference |
| `Consume.img` | A binary Consume WZ image (contents not decoded yet) | data | - | - | Decode with a WZ reader to see what it changes |

Not a mod: `hileso-ricardo.mp4` (meme).
