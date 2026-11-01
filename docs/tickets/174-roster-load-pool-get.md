# 174: The roster and store snapshot reads bypass the bot share

**Status:** open — S3, documented by the b269 pool fix, not changed. Opened 2026-11-01 from
`agent-runs/server/269-dbpool-forensics/AUDIT.md` (finding F4, branch `269-dbpoolfix`).

Two one-time reads borrow a connection from the pool directly instead of through `BotDbGate`, and both do it while
holding a monitor that other bot threads queue on:

- `BotRoster.all()` (`src/main/java/soloMapling/ArtificialPlayer/BotRoster.java:398-420`, the borrow at `:407`) runs
  under `LOAD_LOCK` (`BotRoster.java:143`). It is entered by every roster query —
  `member(int)` `BotRoster.java:165`, `has(int)` `:161`, `allMembers()` `:176`, `load()` `:196` — and the first call on a
  cold boot does `SELECT * FROM botroster` (`BotRoster.java:133`) on the calling thread. A `SQLException` there is
  rethrown as `IllegalStateException("Could not read the bot roster", e)`; the load is not retried.
- `BotStore.lockedReadConnection()` (`src/main/java/soloMapling/ArtificialPlayer/BotStore.java:738-741`) is used by
  `BotStore.roster()` (`BotStore.java:1101-1103`) under `ROSTER_LOCK` (`BotStore.java:160`), which every spawning bot
  queues on.

## Why it is built that way

Both reads are deliberately outside the gate (`BotRoster.java:402-406`, `BotStore.java:733-737`): the caller holds a
monitor that other bot threads need, and on Java 21 a virtual thread blocked on a monitor pins its carrier. Paying a
gate permit for a read taken under that lock nests a second demand on top of the character load that is already
running, and a reader waiting for the share there could wait on threads whose carriers cannot run — the
2026-11-01 shape in miniature. One unfenced statement on one connection is cheaper than that.

## The risk that remains

Since the b269 fix, Hikari's `connectionTimeout` is 10 s (`src/main/java/tools/DatabaseConnection.java:79`), so a
borrow from a jammed pool blocks for at most 10 seconds instead of 30. Every second of it is spent by a thread holding a
monitor that other bot threads are blocked on, and a virtual thread in that state pins its carrier. It is bounded, not
a livelock: the pool either hands over a connection, fails, or a player disconnects and the share clears. With the
paced boot fill (`BotPopulationDirector.LoginPace`) and the hard six-permit cap, the pool is not meant to be jammed at
all after boot, so this is a cold-start cliff rather than a steady-state one.

It is also uncounted against the bots' share (`BotDbGate`): the pool's borrower count can be `PERMITS` plus this one
read, which is why the minute line's `waiting=` figure is the thing to watch rather than `permits in use` alone.

## If it is ever worth fixing

Do not count the borrow in the gate — that is the one change that turns a bounded wait into the deadlock the class
comments warn about. Either:

- give the roster and store snapshot their own connection (opened once, never returned to the pool, closed at
  shutdown), so the read cannot contend with players or bots at all; or
- read the snapshot on the store's writer thread (`ExecutorServiceManager.runBotDb`) before the first bot can spawn, so
  the borrow happens on a platform thread that may wait without pinning anything, and the lock-holding threads see a
  populated map.

Both are post-1.6.2 work: the current behaviour is one statement per process and it fails loudly rather than spinning.
Track it here so nobody "fixes" it by adding a gate acquisition inside the locked read.
