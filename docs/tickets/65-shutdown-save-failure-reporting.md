# Ticket 65: report failed player saves accurately at shutdown

**Target:** 1.5.1

**Priority:** P2 — misleading persistence success reporting

**Status:** closed 2026-10-25 (done in 1.5.1, commits 32806f0a3 and 2b990a20f).

**Area:** Server / shutdown observability

**Related:** ticket 62 (correct active-session worklist)

## Problem and evidence

`ShutdownSaves.forEachWithin()` adds timed-out/interrupted tasks to its returned list, but catches
`ExecutionException` by logging and continuing without adding the failed item. `Server.shutdownInternal()` logs
`clients.size() - late.size()` as the number disconnected and saved. A save task that throws is therefore counted
as successful in that summary, although its error appears separately.

Also audit lower layers that swallow save exceptions before the future completes: the summary should describe
what is known, rather than equate a returned disconnect call with a confirmed durable write.

Source locations:

- `src/main/java/soloMapling/server/ShutdownSaves.java`: `forEachWithin` (1.5.0 lines 103–115).
- `src/main/java/net/server/Server.java`: `saveRealPlayers` (2097–2105).
- `src/main/java/client/Client.java`: `disconnectInternal` and character-save call.
- Existing `ShutdownSavesTest` covers ordering and a hanging task, but not a task that throws.

## Scope and acceptance

- Track completed-success, failed, and unfinished saves distinctly, or choose a similarly explicit result model.
- Add regression coverage with one success, one exception, and one timeout; counts account for every task and
  failed tasks never appear in the success total.
- Include enough account/character context to investigate a failure without exposing credentials.
- Define how disconnects already in progress and lower-level save failures are reported, in coordination with
  ticket 62. Avoid claiming durability without evidence from the save path.
- Retain bounded shutdown behavior. No live restart is needed to validate the helper regression.

No implementation or tests were run when filing this ticket.
