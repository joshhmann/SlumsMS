# 22: Jar-only data loading

**Area:** Later  
**Depends on:** -  
**Status:** open

## What

About 23 SoloMapling classes read data from src/main/java paths. Add one resolver (override dir, then source tree, then classpath) and log any missing file at startup.

## Done when

A jar-only install has named, talking bots.
