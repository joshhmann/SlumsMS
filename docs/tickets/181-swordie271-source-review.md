# Swordie Plus v271 source review

Recorded 2026-11-04 09:03 UTC / 1:03 AM PST. Read-only review for ticket181; loot leads relate to ticket182.

## Source identity

Read the two archives in Silvia `Z:\swordie 271`, copied into
`/root/work/agent-runs/client/164-swordie271-review/` and extracted locally. Both local SHA256 values match
Get-FileHash on Silvia:

- swordie-plus-v271-main.zip: `f8014a37d883fddce27f320b010e13bf8b0c6303d5e47a5c02125a5aa856e3bc`
- swordie-plus-launcher-v271-main.zip: `9f797980d30c0749abdba6291937411bb8311e35408aab26f5a0bce42e11d69d`

The server README explicitly identifies GMS v271.4 / Steam build26141834 and v232→v265→v271 ancestry.
This reviews archived source, not proof that the adjacent extracted folders have no later edits.
No dependency installation, donor code execution, build, game launch or remote writes occurred.

## Useful references and compatibility limits

| Finding | Source-relative path | Use for SlumsMS |
|---|---|---|
| Rose Garden daily reset | scripts/field/enter_211080000.py | Behavior reference; our current JS entry script instead starts quest3141 for qrLimit. Preserve that access fix when adapting behavior. |
| Rose flower / imprisoned soul reactors | scripts/reactor/roseGardenRose.py, roseGardenSpirit0.py, roseGardenSpirit1.py | Quest31038/31041 count and daily scoring reference; these named reactor scripts were not found in our reviewed server checkout. Confirm reactor IDs and item IDs against our data. |
| Mob scoring / golem spawn | scripts/field/die_roseGarden.py, rose_garden_golem.py; CustomMobDeathScript.java / CustomFieldScripts.java | Python scripts depend on explicit Java hook registration. Porting just script files would not implement their behavior. Golem script documents a simplified quest-triggered spawn. |
| Temple/Crack routing | scripts/portal/enterTheCrack.py, timeCrack.py, BPReturn_Akayrum.py | Source uses272000000 entry portal1, a destination menu, return270000000 portal6. Portal indexes and Python API must be checked against our XML/JS engine; not a drop-in fix. |
| Split-WZ / MS export | wzexport/Program.cs, wzexport/README.md; jwzlib/WzMsPack.java | Potential tooling reference for the newly downloaded client. Exporter skips _Canvas directories and writes XML, so it alone is not a complete art-restoration pipeline. Needs external MapleLib/.NET; not run here. |
| Drop correctness checks | sql/migration/V155_Drop_Nonexistent_Item.sql | Explicitly removes Water Thief mob2150000 → item2150001 because that is a mob ID. Check candidate items for valid item data before considering imports. |
| Boss reward cleanup | sql/migration/V218_Boss_Drop_Cleanup.sql, V222_Boss_Drop_Counts_GMS_v271.sql | Review leads only. Contains modern progression/rate/quantity decisions, including potion removal, that are not automatically appropriate for our server. |

Source docs/v271-notes.md also warns about old enums under newer packet/buff layers and unverified opcodes.
No v271 packet layouts, addresses or key/stat tables should be applied to v83 without independent adaptation.
Launcher README says the client hook is not included. No client hook source or Rose Garden native crash fix
was found in these archives. This review does not diagnose or close b251.

## Concrete loot leads

Parsed literal numeric INSERT VALUES rows in sql/init/03_drops.sql, restricted to mob_drops and matched
against ticket182's placed-gaps.csv. Found3143 raw rows covering374 mob IDs in that gap inventory.
Separately, explicit INSERT SELECT migration statements yield331 leads across145 gap IDs. These are raw
source rows, not the final state after migrations, effective drops, or approved balance changes.
Artifacts drop-base-leads.json and drop-insert-leads.json retain the matches outside the repository.

All ten low-level Edelstein mobs inspected have base rows:

| Mob ID | Name | Raw base rows |
|---|---|---:|
|150000|Potted Sprout|10|
|150001|Potted Morning Glory|11|
|150002|Grape Juice Bottle|9|
|1150000|Patrol Robot|12|
|1150001|Strange Sign|12|
|1150002|Serpent|12|
|2150000|Water Thief Monster|28|
|2150001|Dust Box|3|
|2150002|Streetlight|16|
|2150003|Patrol Robot S|14|

Review source item existence, quest gating, effective migration changes and our existing gear-progression
branch before selecting any rows. The invalid2150001 item deletion shows why base counts are not import proof.
Do not apply wholesale migrations or copy source rates. No drop/rate/item changes were made.

## Asset and crash result

The server archive contains only two tiny files named resources/Data.wz (6319bytes) and DataTemp.wz
(6144bytes); no loose .img or .ms asset files were found. DataTemp is not a readable PKG1 archive in our
legacy reader. This is not a full Map/Obj/Sound donor. Neither archive supplies the requested mario.img,
Obj/starPlanet.img or PL_RetroWorld.img as asset files. Search references alone do not establish donor coverage.
The actual Steam v271 Data download still needs an independent image/node/link inventory.

Rose211080100 runtime crash and211080700 missing mario art remain open. Existing v232 Crack in Time
candidate remains the verified offline asset result. No native or runtime tests were run for this source review.
Economy changes: none. Next: compare the downloaded v271 assets against the remaining dependency list;
review Edelstein drops separately against ticket182 and the gear branch; adapt only selected behavior fixes
with fail-before tests in their own implementation task.
