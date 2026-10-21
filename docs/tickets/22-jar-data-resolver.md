# 22: Jar-only data loading

**Area:** Later  
**Depends on:** -  
**Status:** shipped in v1.4.0 (branch `data-resolver`, via `test-rc2`)

## What

About 23 SoloMapling classes read data from src/main/java paths. Add one resolver (override dir, then source tree, then classpath) and log any missing file at startup.

## Done when

A jar-only install has named, talking bots.

## Done

- `soloMapling.DataFiles` resolves every shipped data read: `-Dsolomapling.dataDir=<dir>` (`<dir>/soloMapling/...`),
  then `src/main/java/soloMapling/...` in the working dir (same path as before), then the classpath (a jar resource is
  unpacked once to a temp dir). A miss is logged once and the caller gets the old source path, so it fails as before.
- `pom.xml` packs `soloMapling/**/*.{yaml,txt,csv,bin}` from `src/main/java` into the jar (938 files).
- `MapleRootSolo.start()` logs `SoloMapling data: N/N resolved (override a, source tree b, classpath c), m missing`.
  `java -cp Cosmic.jar soloMapling.DataFiles` prints the same line and exits 1 on a miss.
- Writers (movement recordings, `TownPins.txt`, nav cache, `graph.dot`) still write to the working dir.
- Checked from an empty dir holding only the jar: all 22 known paths from the classpath, 21 dialogue packs, FM names,
  map 100000000 graph and recordings, regions, transit edges, towns, chatter.
- Watch out: a staged tree beats the jar, so a stale `src/main/java/soloMapling` (live has 1.2.1's) shadows it.
