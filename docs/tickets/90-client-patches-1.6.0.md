# 90: Client patches for 1.6.0

Owner, 2026-10-25: "lets add client patches to 1.6.0 for some of this stuff". Catalogue and originals:
`docs/design/client-mods-inbox.md`, `/root/work/client-mods-inbox/originals/`. slums.dll is Kaentake-based, the same
lineage as the mods.

In 1.6.0 (client line, alongside the settings window and the Parsec fix already planned):
1. **Inventory resize** (drag-to-resize item inventory). Check CUIItem::OnCreate isn't hooked elsewhere; keep the
   byte guard; decide on losing the 4-tab view.
2. **D3D9 wrapper + ImGui**, evaluated against dgVoodoo2 (ticket 39) as the Parsec fix and the host for the
   in-client settings window (`src-160-settings`). Needs the tutorial text from the owner.
3. **UI polish**: animated Cash Shop / gacha buttons and the upscaled login logo (unrar needed).
4. **4-byte HP/MP**: owner decides whether HP/MP should go past 30,000; if yes, client and server change together
   (server writeInt + lift the 30,000 cap in Character.java:9057) in one release.

Later (1.7.0): custom skill actions, native Hyper Skill tab, NpcForge. Not planned: Familiars.

Each patch: own branch in /root/work/client, address/byte verification, launcher RC build, Start-QA real-client
check, hash-diff before publishing. Ask the original authors' terms where a mod has no license before shipping it.
