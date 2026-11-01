# 173: LifeRuntime.accepted takes the bot's monitor over a PlayerStorage read

Found by the b267 review, 2026-10-31 (UTC). **S4, same family as b267, no cycle today.** Not a release gate:
the b267 fix (fads reads the game outside its monitor, branch `162-fadslock`) does not depend on this.

## The shape

`src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeRuntime.java:773`:

```java
public synchronized void accepted(Activity activity, long now, boolean announce) {
    Character requestPlayer = activity == Activity.HELP_FRIEND || activity == Activity.PARTY_QUEST ? friend(now) : null;
```

`friend(now)` (`LifeRuntime.java:361-370`) calls
`soloMapling.server.SoloMaplingUtilities.world().getPlayerStorage().getCharacterById(id)` and then
`player.getMap().getChannelId()` / `chr.getMap().getChannelId()`. So the per-bot `LifeRuntime` monitor is held
while `PlayerStorage`'s fair `ReentrantReadWriteLock` read lock is taken — the same monitor-over-PlayerStorage
edge that parked live 1.6.0.4 for `Fads.step()` (b267).

This one does not deadlock today: `PlayerStorage` (`src/main/java/net/server/PlayerStorage.java:51-70`, `:82-96`)
takes and releases its lock inside each method and calls nothing that wants a `LifeRuntime` monitor, so there is no
second edge to close a cycle. The same-file monitor methods that stay memory-only are `phase` (`:198`), the
`accepted` overload (`:768`), `fishingStarted` (`:830`), `prompt` (`:834`), and `synchronized (this)` at `:318`;
the other PlayerStorage reads in the file (`:347`, `:384`, `:588`) are outside monitors.

It is only reachable from the bot's own tick today: `LifeTownLife.java:108` (`runtime.accepted(activity, now, false)`)
and `TrainingBot.java:843` (`dailyLife.accepted(...)`). One monitor, one thread, no contention.

## Why it is still worth closing

- The b267 review's rule for the life/bot systems is that "holds a monitor while calling the game" is the bug
  family, not the individual site. The next caller that wants a runtime method from another thread (a QA seam, a
  town ambience thread, a trade) turns this into a real park, and the dump will again show threads on a monitor
  that has nothing to do with PlayerStorage.
- It hides a game read (`friend()`) inside a method that looks like bookkeeping. `accepted` is called once per
  accepted activity, so the read is cheap and easy to hoist.

## Fix sketch

1. Read the friend before the monitor: `Character requestPlayer = activity == ... ? friend(now) : null;` at the
   top of the three-argument `accepted`, above `synchronized`, or make `accepted` non-synchronized and take the
   monitor only around the field updates (`accepted`/`activityCounts`/`friendId`/`friendUntil`/`memory.talked`).
   Keep one monitor for the whole block if the mutation order matters; the point is only that `friend()` is called
   before it.
2. If any other method in the file grows a game call, do the same: a `LifeRuntime` monitor is a leaf, exactly as
   the Fads comment now says.
3. Regression, in the shape of `FadsLockOrderTest`: a thread holds `PlayerStorage`'s write lock (or a writer queues
   behind a held read), a second thread calls `runtime.accepted(Activity.HELP_FRIEND, now, false)`, a third calls
   `runtime.prompt()`; `prompt()` must answer while the first thread still holds the lock. Today the test fails
   because `accepted` blocks inside `friend()` holding `this`; after the hoist it passes.

Do not change RNG, affinities, dialogue or save timing for this; it is a lock-order change only.
