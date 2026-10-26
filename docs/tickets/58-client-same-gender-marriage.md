# 58 (client lane): same-gender marriage - what to check on the v83 client

**For the client lane only.** The server side is done on `bots-affinity-150` (1.5.0): the server no longer refuses a
proposal between two characters of the same gender, Moony crafts ring boxes for anyone, and breaking an engagement no
longer works out the partner's token from gender. Nothing below has been run on a real client yet.

## What the server sends, and where gender still appears

- `WeddingPackets.OnMarriageResult` (opcode MARRIAGE_RESULT, mode 11) writes "groom id, bride id, groom name, bride
  name". It is built **per recipient**: `RingActionHandler` sends the proposer (source) a packet built from the source
  and the accepter (target) one built from the target (`OnMarriageResult(marriageId, source, false)` /
  `(marriageId, target, false)`; a bot partner's engagement in `BotRomance` sends the player one built from the
  player). Each packet puts its own character first when that character is male, else the partner first. For a mixed
  pair both packets come out the same (the man first). For two men each one receives itself first; for two women each
  one receives its partner first. So the two clients do not get the same order, and neither order means "male,
  female".
- The wedding event scripts (`WeddingChapel.js`, `WeddingCathedral.js`, `sendWeddingAction`) send
  `Wedding.OnWeddingProgress(step, groomId, brideId)` with the reserving player as "groom". Server side this is only
  ids; any pair works.
- Rings are created by the stock `Ring.createRing` path; the `rings` rows have no gender.

## What the client might check (to verify by playing, then patch in slums.dll only if needed)

1. **Proposal UI:** using an engagement box (2240000-2240003) on a same-gender character. Does the client open the
   proposal window and send RING_ACTION mode 0, or refuse locally (a "you can only propose to ..." popup that never
   reaches the server)? If it refuses, the DLL needs to skip that check.
2. **Engagement / marriage result:** after "yes", both clients get MARRIAGE_RESULT mode 11. Check that spouse chat
   works and the engagement ring shows, with two men and with two women.
3. **Ceremony:** Chapel and Cathedral `OnWeddingProgress` with two same-gender ids: the vow and kiss steps, the
   priest text, and that both get the wedding ring.
4. **Character info / ring effect:** the marriage ring effect and the spouse name in the character info window for a
   same-gender pair.

If any of these is blocked client-side, write a DLL change for that check only. Do not change packet layouts: the
server keeps sending the stock shapes.
