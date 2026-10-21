package soloMapling.qa;

import client.Character;

/** What a scenario sees while it stages, ticks or takes a step: the run, the world, the GM and the arguments. */
public final class QaContext {
    private final QaRun run;
    private final QaWorld world;
    private final Character gm;
    private final String[] args;

    QaContext(QaRun run, QaWorld world, Character gm, String[] args) {
        this.run = run;
        this.world = world;
        this.gm = gm;
        this.args = args == null ? new String[0] : args;
    }

    public QaRun run() {
        return run;
    }

    public QaWorld world() {
        return world;
    }

    /** The GM as of the command (may be a stale object after a relog: use {@link #gmNow()} in undos). */
    public Character gm() {
        return gm;
    }

    /** The GM's character now, or null when offline. */
    public Character gmNow() {
        return world.online(run.gmId());
    }

    public String[] args() {
        return args;
    }

    public String arg(int i, String fallback) {
        return i < args.length && !args[i].isBlank() ? args[i] : fallback;
    }

    /** A STAGE line in the run's log. */
    public void log(String message) {
        run.note("STAGE", message);
    }

    public void onStop(String label, Runnable action) {
        run.onStop(label, action);
    }

    /** Gives the GM an item and registers taking back what is left of it at stop. Returns how many were given. */
    public int giveBack(int itemId, int quantity) {
        int given = world.giveItem(gm, itemId, quantity);
        if (given > 0) {
            log("gave " + given + " x " + world.itemName(itemId) + " (" + itemId + ")");
            onStop("take back " + world.itemName(itemId) + " (" + itemId + ") still in the inventory", () -> {
                Character now = gmNow();
                if (now != null) {
                    int taken = world.takeItem(now, itemId, given);
                    run.note("CLEANUP", "took back " + taken + " of " + given + " x " + itemId);
                } else {
                    run.note("CLEANUP", "GM offline: " + given + " x " + itemId + " left with the character");
                }
            });
        } else {
            log("could not give " + itemId + " (inventory full?)");
        }
        return given;
    }

    /** Spawned bots leave at stop. */
    public void despawnAtStop(java.util.List<Integer> botIds, String what) {
        if (botIds.isEmpty()) {
            return;
        }
        log("spawned " + botIds.size() + " " + what + ": " + botIds);
        onStop("despawn " + botIds.size() + " " + what, () -> {
            for (int id : botIds) {
                world.despawnBot(id);
            }
        });
    }
}
