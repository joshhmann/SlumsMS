package soloMapling.qa;

import client.Character;

import java.util.List;
import java.util.Set;

/**
 * One {@code !qa} test scenario. Its id is the test id on the release notes page (two letters, a dash, a number:
 * {@code gd-1}), so the owner reads the page and types {@code !qa run gd-1}.
 * <p>
 * To add one: implement this (or extend {@link QaScenarios.Simple}), register it in {@link QaScenarios#all()}, and
 * add a QaLog hook where the game does the thing the test checks. docs/ops/qa-suite.md walks through it.
 */
public interface QaScenario {

    /** The release-notes test id, e.g. {@code gd-1}. */
    String id();

    /** Other ids that run this scenario (a second test on the page that needs the same staging). */
    default List<String> aliases() {
        return List.of();
    }

    /** One line for {@code !qa list}. */
    String title();

    /** Arguments {@code !qa run <id>} takes, for the help line; empty when none. */
    default String usage() {
        return "";
    }

    /** What the scenario writes to its log. */
    Set<QaLog.Topic> topics();

    /** The maps whose events reach the log; empty = every map. Computed before staging. */
    default Set<Integer> watchedMaps(Character gm, String[] args) {
        return Set.of();
    }

    /**
     * What this run holds exclusively (e.g. {@code map:200000301}); a second GM's run that wants the same claim is
     * refused. Computed before staging.
     */
    default Set<String> claims(Character gm, String[] args) {
        return Set.of();
    }

    /** Minutes until the run stops by itself. */
    default int minutes(String[] args) {
        return 15;
    }

    /** Seconds between {@link #tick} calls; 0 = no tick. */
    default int tickSeconds() {
        return 0;
    }

    /**
     * Stages the scenario for the GM: warps, items, bots. Registers an undo with {@link QaContext#onStop} for
     * everything it hands out, right after handing it out. Returns the lines to show the GM (what to do next).
     * Throws {@link QaRefusal} to refuse; the undos registered so far still run.
     */
    List<String> stage(QaContext ctx);

    /** {@code !qa do <name>}: a step the scenario can take on request. */
    default List<String> action(QaContext ctx, String name, String[] args) {
        throw new QaRefusal(id() + " has no '" + name + "' step.");
    }

    /** Periodic work while the run is active (e.g. sampling the channel bars). */
    default void tick(QaContext ctx) {
    }
}
