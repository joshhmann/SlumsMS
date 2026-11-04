# Monster loot coverage snapshot

See [ticket 182](../../tickets/182-missing-monster-and-boss-loot-tables.md) for the findings, exact scope,
gear-branch comparison and required proof. This is an audit, not a drop-table migration.

- `placed-gaps.csv`: 811 IDs placed in current TEST map XML with empty tables, quest-gated rows only,
  or no ungated non-card item rows. Includes 739 with no rows, 125 of them boss flagged.
- `boss-review.csv`: 503 boss-flagged template candidates with table gaps and placement, revive, direct
  spawn literal or source-reference evidence. Unused phases and indirect references are included deliberately
  for review; this is not a count of active boss fights missing rewards.
- `gear-pair-overlap.csv`: all 367 target pairs from the pinned gear candidate's migrations 059/060/061,
  compared with the SELECT-only Live/TEST drop snapshot. A target already owned by that branch is not new work.

`ordinary_item_rows` means positive chance/maximum quantity, `questid=0`, item ID greater than zero and
outside card range 2380000–2389999. It does not validate item existence, item-level quest flags, droppability,
relevant-loot filtering, map switches or floor pickup. `maps` lists TEST WZ placements, not proven routes.
`source_reference` is a numeric literal in source containing spawn/monster-related code; it can be unrelated
or unused. Revive evidence can also come from unused templates. Review flags do not authorize exclusions.
The raw priority heuristic is not used to claim confirmed defects.

Snapshot: 2026-11-04 ~07:03–07:08 UTC (Nov 3 ~11:03–11:08 PM PST). Gear commit
`0444737f23b1367b6c6f945a47c715da3ed7f100`. No rates, tables or game behavior changed.
