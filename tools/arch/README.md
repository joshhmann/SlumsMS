# Architecture pages

Builds the SlumsMS architecture pages (one per system) that are published as claude.ai artifacts.

- `pages.py`: the data: each system's parts, origin (base / modified / custom) and state (working / partial / broken / unknown).
- `deep.py`: reads `docs/architecture/HOLES.md` (the Nov 5 deep review) into each page's "Deep review" section;
  `CHECKED` records which leads Claude verified or corrected.
- `gen.py`: `python3 tools/arch/gen.py` writes `tools/arch/out/<system>.html` and `summary.json`.
- `hub.html`, `playerbot.html` and `plan-170.html` are hand-written pages (the hub, the detailed bot page and the 1.7.0 plan), kept here as sources.

The deep review itself is `docs/architecture/` (start at its README.md, then HOLES.md).
