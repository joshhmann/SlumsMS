# Editing the gear progression candidate

These commands edit/validate local files only. They do not install drops, reload caches or deploy a server.
Run from the repository root. Keep inputs and generated outputs together in review; never edit shipped050/055
migrations to deliver a new tuning pass.

| Change | Edit | Validate/preview |
|---|---|---|
| Normal item sources | `normal-200/pilot-source-proposal.csv` | `gen_normal_drops.py` checks actors, placement, RB0 gates and two sources per item |
| Normal item chance/binding allowlist | `normal-200/drop-policy.json` | Same generator; global WzItems restrictions stay unchanged |
| Main forge fees, quantities and craft levels | `forge-stages.json` | `rebalance_forge.py` validates templates, inputs and matching prior slots/types |
| Boss trophies and exchange | Actual NPC scripts plus `forge-source-contract.json` | Source audit and actual GraalJS regression tests; a contract does not create a reward |
| HP/EXP overrides | Existing `mob-stat-overrides.tsv` | Existing override tests and rebuilt jar; measure class combat before choosing values |
| Monster attack/accuracy/defence | Paired server/client data after investigation | Client lane verification; human damage cannot be inferred from a server-only edit |

Paths above are relative to `docs/economy/`, except the existing runtime resource/scripts. Useful commands:

```sh
python3 -m unittest discover -s tools/economy -p 'test_*.py'
python3 tools/economy/gen_normal_drops.py --wz /root/work/bake/wz --output /tmp/normal-preview
python3 tools/economy/rebalance_forge.py --wz /root/work/bake/wz --output /tmp/forge-preview
python3 tools/economy/model_forge_budgets.py --output /tmp/forge-budget.csv
python3 tools/economy/audit_forge_sources.py --wz /root/work/bake/wz --output /tmp/forge-sources
```

`rebalance_forge.py --write` applies the reviewed stage profile to237 equipment recipes and the existing
Fragment recipe; its default only previews. Normal generation's `--write-sql` writes059 and its rollback;
`gen_royal_materials.py --wz /root/work/bake/wz` writes060 and rollback. Those filenames belong to this unshipped
candidate: after admission/shipping, reserve a new migration and generator version instead of rewriting history.
The before/after preview compares the current files; preserve the original audit diff separately.

Repeat generation must leave SQL/JSON unchanged. Review the exact fee/material diff and full class catalogue.
Run the server suite and build a compatible jar; the lane deploys jar/scripts/data/migrations together, reloads
drops and compares the active DB rows. Older pairs are preserved, so a successful migration alone does not
establish ordinary supply or the modeled rate. Drop rates multiply stored1x chances once; fixed clear receipts
and one-for-one exchanges do not multiply.

The budget model accepts `--drop-rate`, `--kills-per-hour`, `--boss-minutes` and `--travel-hours`; per-stage
net-income assumptions are in the JSON. It overlaps funding and regional hunting and excludes prior gear cost.
Use measured income/clear limits before claiming8–12 hours. Test the prior gear on its next source fights.
[Current candidate and targeted checks](../release/1.6.4-gear-progression.md).
