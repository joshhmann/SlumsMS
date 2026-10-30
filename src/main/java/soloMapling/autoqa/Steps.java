package soloMapling.autoqa;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static soloMapling.autoqa.Check.Step;
import static soloMapling.autoqa.Check.StepResult;

/**
 * The steps a check is made of: what a tester types and does, and the waits in between. Every chat line goes
 * through the synthetic player's real packet handlers, so {@code !qa run af-1} is exactly what the owner types.
 */
public final class Steps {

    /** Gap between two lines a player sends: the chat handler drops lines under 200 ms apart as spam. */
    static final long SPAM_GAP_MS = 400;

    /**
     * A refusal of {@code !qa run} that means "not here, not now" (a precondition), so the check is a SKIP, not a
     * FAIL: bot guilds missing, the wrong channel, a guild state, a level ...
     */
    static final Pattern PRECONDITION = Pattern.compile("(?i)(must be|you need|needs? |no bot guild|channel 1|level 50|"
            + "guildless|in a guild|already running|wait for it|no boss run|not blocked|nobody|no bot could|"
            + "no live mobs|cannot warp|world events run)");

    /** The suite's "Logging to logs/qa/af-1-....log. Stops in ..." answer to {@code !qa run}: the run's log file. */
    static final Pattern QA_LOG = Pattern.compile("^Logging to (\\S+?)\\.? Stops in ");

    private Steps() {
    }

    /** A step from a description and a body. */
    static Step of(String describe, StepBody body) {
        return new Step() {
            @Override
            public StepResult run(CheckRun run) throws InterruptedException {
                return body.run(run);
            }

            @Override
            public String describe() {
                return describe;
            }
        };
    }

    @FunctionalInterface
    interface StepBody {
        StepResult run(CheckRun run) throws InterruptedException;
    }

    /** Player 1 types a line (chat or command), placeholders filled. */
    public static Step say(String text) {
        return say(1, text);
    }

    public static Step say(int slot, String text) {
        return of("p" + slot + " says '" + text + "'", r -> {
            String line = r.fill(text);
            if (line.contains("{")) {
                return StepResult.fail("could not fill " + line + " (no staged bot or variable)");
            }
            r.actor(slot).say(line);
            r.world.sleep(SPAM_GAP_MS);
            return StepResult.OK;
        });
    }

    /**
     * 1.6.2 (audit 163, KS manners at the swing): player 1 hides ({@code !hide}) for the rest of the check, and the
     * runner un-hides it at cleanup whatever happens. For checks that stage bots fighting right next to the GM: a
     * visible player standing by a mob makes it theirs, and neutral bots leave it alone - which is the game's rule,
     * not something to exempt the auto-QA GM from.
     */
    public static Step hiddenGm() {
        return of("p1 hides (!hide), un-hidden at cleanup", r -> {
            r.hid(1);
            r.actor(1).say("!hide");
            r.world.sleep(SPAM_GAP_MS);
            return StepResult.OK;
        });
    }

    /**
     * {@code !qa run <scenario> [args]} by player 1, then waits for the suite's answer: "QA <id> running" carries on,
     * a precondition refusal SKIPs the check (with the suite's words), any other answer FAILs it.
     */
    public static Step run(String scenario, String... args) {
        String cmd = "!qa run " + scenario + (args.length == 0 ? "" : " " + String.join(" ", args));
        return of(cmd, r -> {
            AutoQaWorld.Actor gm = r.actor(1);
            long t = r.world.now();
            gm.say(cmd);
            r.startedScenario(1);
            long deadline = t + 20_000;
            while (r.world.now() < deadline) {
                r.world.sleep(500);
                List<AutoQaLine> heard = gm.heard(t);
                for (AutoQaLine l : heard) {
                    if (l.message().startsWith("QA " + scenario + " running") || l.message().startsWith("QA ")
                            && l.message().contains(" running: ")) {
                        r.world.sleep(1500); // let the staged bots log in and the STAGE lines land
                        r.lines(); // learns {bot} ...
                        r.note("started: " + l.message());
                        for (AutoQaLine said : gm.heard(t)) {
                            Matcher log = QA_LOG.matcher(said.message());
                            if (log.find()) {
                                r.logged(log.group(1)); // b222: the result cites the run's QA log
                            }
                        }
                        return StepResult.OK;
                    }
                }
                for (AutoQaLine l : heard) {
                    if ("YELLOW".equals(l.kind()) && !l.message().startsWith("QA ")) {
                        String why = l.message();
                        return PRECONDITION.matcher(why).find() ? StepResult.skip("!qa run " + scenario + " refused: " + why)
                                : StepResult.fail("!qa run " + scenario + " refused: " + why);
                    }
                }
            }
            return StepResult.fail("no answer to '" + cmd + "' within 20 s");
        });
    }

    /** {@code !qa do <step> [args]} by player 1. */
    public static Step qaDo(String step, String... args) {
        return say("!qa do " + step + (args.length == 0 ? "" : " " + String.join(" ", args)));
    }

    /** {@code !qa stop} by player 1 (the runner also stops every run at the end of a check). */
    public static Step stop() {
        return of("!qa stop", r -> {
            r.actor(1).say("!qa stop");
            r.world.sleep(2500);
            return StepResult.OK;
        });
    }

    public static Step waitSeconds(int seconds) {
        return of("wait " + seconds + " s", r -> {
            r.world.sleep(seconds * 1000L);
            return StepResult.OK;
        });
    }

    /**
     * b222 (owner 2026-10-30, "a check must not go green without testing"): the evidence line and step reason of a
     * wait that ran out. Every wait that ends without what it waited for FAILs the check with this line; a window
     * that is allowed to stay empty says so with {@link #quietFor} (must stay empty) or {@link #optionalWait}.
     */
    static String timedOut(String what, int seconds) {
        return "timed out waiting for " + what + " after " + seconds + " s";
    }

    /** Ends the check as FAIL with the timed-out line, also kept as an evidence note. */
    private static StepResult timeout(CheckRun r, String what, int seconds, String detail) {
        String line = timedOut(what, seconds) + (detail == null ? "" : ": " + detail);
        r.note(line);
        return StepResult.fail(line);
    }

    /**
     * Waits until a line matches (qa log or heard) from the start of the step before this one on, at most
     * {@code seconds}, and returns as soon as it shows up. Not seeing it in time FAILs the check
     * ("timed out waiting for /x/ after N s"): before b222 it only left a note and the expectations decided, which
     * let a check pass on an earlier line or another alternative without the step ever having been tested.
     */
    public static Step waitFor(String regex, int seconds) {
        return of("wait up to " + seconds + " s for /" + regex + "/",
                r -> awaitLine(r, regex, seconds, r.previousStepStart()));
    }

    /**
     * As {@link #waitFor(String, int)}, but the line may have come at any time since the mark ({@code null}: since
     * the check started): for a line a step two or more steps back may already have caused (a death during the
     * staging, a decision logged with the request).
     */
    public static Step waitForSince(String mark, String regex, int seconds) {
        return of("wait up to " + seconds + " s for /" + regex + "/ (since " + (mark == null ? "the start" : "mark " + mark)
                + ")", r -> awaitLine(r, regex, seconds, r.markTime(mark)));
    }

    private static StepResult awaitLine(CheckRun r, String regex, int seconds, long from) throws InterruptedException {
        long deadline = r.world.now() + seconds * 1000L;
        Pattern p = r.compile(regex);
        if (p == null) {
            return StepResult.fail("unknown placeholder in /" + regex + "/ (no staged bot or variable)");
        }
        while (true) {
            for (AutoQaLine l : r.lines()) {
                if (l.timeMs() >= from && p.matcher(l.text()).find()) {
                    return StepResult.OK;
                }
            }
            if (r.world.now() >= deadline) {
                return timeout(r, "/" + regex + "/", seconds, null);
            }
            r.world.sleep(Math.min(1000, Math.max(1, deadline - r.world.now())));
        }
    }

    /**
     * b222: the explicit form of "wait, then check that nothing happened": waits the whole {@code seconds} and FAILs
     * as soon as a line matching the pattern shows up (from this step's start on). Use it, not a wait, whenever the
     * window is meant to stay empty.
     */
    public static Step quietFor(String regex, int seconds) {
        return of("stay quiet " + seconds + " s: no /" + regex + "/", r -> {
            long from = r.world.now();
            long deadline = from + seconds * 1000L;
            Pattern p = r.compile(regex);
            if (p == null) {
                return StepResult.fail("unknown placeholder in /" + regex + "/ (no staged bot or variable)");
            }
            while (true) {
                for (AutoQaLine l : r.lines()) {
                    if (l.timeMs() >= from && p.matcher(l.text()).find()) {
                        String line = "not quiet: /" + regex + "/ showed up after " + (l.timeMs() - from) / 1000
                                + " s of " + seconds + " s: " + l.text();
                        r.note(line);
                        return StepResult.fail(line);
                    }
                }
                if (r.world.now() >= deadline) {
                    return StepResult.OK;
                }
                r.world.sleep(Math.min(1000, Math.max(1, deadline - r.world.now())));
            }
        });
    }

    /**
     * b222: a window that may legitimately stay empty - a branch, not a requirement (t-ev-2's natural-death window
     * before the forced death). Waits up to {@code seconds} for the pattern and never fails; {@code why} says why an
     * empty window is fine and goes into the note with the outcome. A later step or an expectation must judge what
     * the window was for: {@code AutoQaCatalogTest} pins the checks allowed to use it.
     */
    public static Step optionalWait(String regex, int seconds, String why) {
        return of("optional: up to " + seconds + " s for /" + regex + "/ (" + why + ")", r -> {
            long from = r.previousStepStart();
            long deadline = r.world.now() + seconds * 1000L;
            Pattern p = r.compile(regex);
            if (p == null) {
                return StepResult.fail("unknown placeholder in /" + regex + "/ (no staged bot or variable)");
            }
            while (true) {
                for (AutoQaLine l : r.lines()) {
                    if (l.timeMs() >= from && p.matcher(l.text()).find()) {
                        return StepResult.OK;
                    }
                }
                if (r.world.now() >= deadline) {
                    r.note("optional window: no /" + regex + "/ in " + seconds + " s (" + why + ")");
                    return StepResult.OK;
                }
                r.world.sleep(Math.min(1000, Math.max(1, deadline - r.world.now())));
            }
        });
    }

    /**
     * Waits until the pattern has been seen {@code want} times since the previous step, at most {@code capSeconds}.
     *
     * <p>A wait that exists only because an assertion needs a positive outcome ends as soon as it has it: the
     * sample count is what the check asserts, so reaching it early is the same evidence. The cap is the real
     * budget and is never scaled - shortening it could turn a slow-but-passing check into a failure. Not reaching
     * {@code want} in time FAILs the check (b222), with how far it got.
     */
    public static Step until(String regex, int want, int capSeconds) {
        return of("wait up to " + capSeconds + " s for " + want + " x /" + regex + "/", r -> {
            long start = r.world().now();
            long from = r.previousStepStart();
            long deadline = start + capSeconds * 1000L;
            Pattern p = r.compile(regex);
            if (p == null) {
                return StepResult.fail("unknown placeholder in /" + regex + "/ (no staged bot or variable)");
            }
            while (true) {
                int seen = 0;
                for (AutoQaLine l : r.lines()) {
                    if (l.timeMs() >= from && p.matcher(l.text()).find()) {
                        seen++;
                    }
                }
                long waited = (r.world().now() - start) / 1000;
                if (seen >= want) {
                    if (waited > 0) {
                        r.note("reached " + want + " x /" + regex + "/ after " + waited + " s of the " + capSeconds + " s cap");
                    }
                    return StepResult.OK;
                }
                if (r.world().now() >= deadline) {
                    return timeout(r, want + " x /" + regex + "/", capSeconds, "saw " + seen);
                }
                r.world().sleep(Math.min(1000, Math.max(1, deadline - r.world().now())));
            }
        });
    }

    /**
     * Polls a fixture (see {@link AutoQaWorld#probe}) until its first line is PASS, at most {@code capSeconds}: a
     * window that waits for something to become true (a bot accepting a buddy request) ends when it is true. The
     * note prints how long that took; running out FAILs the check with the last verdict (b222).
     */
    public static Step untilProbe(String what, int capSeconds, String... args) {
        return of("wait up to " + capSeconds + " s for " + what + " to pass", r -> {
            long start = r.world().now();
            long deadline = start + capSeconds * 1000L;
            while (true) {
                String[] filled = new String[args.length];
                for (int i = 0; i < args.length; i++) {
                    filled[i] = r.fill(args[i]);
                }
                List<String> out = r.world().probe(what, r.actor(1), filled);
                String verdict = out.isEmpty() ? "no answer" : out.get(0);
                long waited = (r.world().now() - start) / 1000;
                if (verdict.startsWith("PASS")) {
                    r.note(what + " after " + waited + " s: " + verdict);
                    return StepResult.OK;
                }
                if (r.world().now() >= deadline) {
                    return timeout(r, "probe " + what + " to pass", capSeconds, "last answer " + verdict);
                }
                r.world().sleep(Math.min(2000, Math.max(1, deadline - r.world().now())));
            }
        });
    }

    /**
     * A staging wait - "let the bots settle" - scaled by {@link soloMapling.qa.QaTiming}. This is only for waits
     * whose length is not what the check asserts; a cooldown, delay or expiry the check observes stays
     * {@link #waitSeconds(int)} or {@link #waitFor}. The description and the note print the seconds actually
     * waited, so a shortened settle never reads as the real one.
     */
    public static Step settle(int seconds) {
        return new Step() {
            @Override
            public StepResult run(CheckRun run) throws InterruptedException {
                int actual = soloMapling.qa.QaTiming.seconds(seconds);
                run.world().sleep(actual * 1000L);
                if (actual != seconds) {
                    run.note("settled " + actual + " s of " + seconds + " s" + soloMapling.qa.QaTiming.note());
                }
                return StepResult.OK;
            }

            @Override
            public String describe() {
                return "settle " + soloMapling.qa.QaTiming.seconds(seconds) + " s" + soloMapling.qa.QaTiming.note();
            }
        };
    }

    /** Sets a mark (expectations can look only before or after it). */
    public static Step mark(String name) {
        return of("mark " + name, r -> {
            r.mark(name);
            return StepResult.OK;
        });
    }

    /**
     * Stores group 1 of the first line matching the pattern into a variable ({@code {name}} in later steps);
     * FAILs when none matches.
     */
    public static Step capture(String var, String regex) {
        return of("capture {" + var + "} from /" + regex + "/", r -> {
            Pattern p = r.compile(regex);
            if (p == null) {
                return StepResult.fail("unknown placeholder in /" + regex + "/");
            }
            for (AutoQaLine l : r.lines()) {
                Matcher m = p.matcher(l.text());
                if (m.find()) {
                    r.set(var, m.group(1));
                    return StepResult.OK;
                }
            }
            return StepResult.fail("nothing matched /" + regex + "/ to fill {" + var + "}");
        });
    }

    /** Player 1 whispers someone. */
    public static Step whisper(String target, String text) {
        return of("whisper " + target + ": " + text, r -> {
            r.actor(1).whisper(r.fill(target), r.fill(text));
            r.world.sleep(SPAM_GAP_MS);
            return StepResult.OK;
        });
    }

    public static Step partyInvite(String name) {
        return of("party invite " + name, r -> {
            r.actor(1).partyInvite(r.fill(name));
            r.world.sleep(SPAM_GAP_MS);
            return StepResult.OK;
        });
    }

    public static Step guildInvite(String name) {
        return of("guild invite " + name, r -> {
            r.actor(1).guildInvite(r.fill(name));
            r.world.sleep(SPAM_GAP_MS);
            return StepResult.OK;
        });
    }

    public static Step acceptGuildInvite(int seconds) {
        return of("accept the guild invite (wait up to " + seconds + " s)", r -> {
            long deadline = r.world.now() + seconds * 1000L;
            while (true) {
                if (r.actor(1).acceptGuildInvite()) {
                    r.world.sleep(1000);
                    return StepResult.OK;
                }
                if (r.world.now() >= deadline) {
                    return timeout(r, "a guild invite", seconds, null);
                }
                r.world.sleep(1000);
            }
        });
    }

    /** Guild (2), party (1) or buddy (0) chat by player 1. */
    public static Step multiChat(int type, String text) {
        return of("chat type " + type + ": " + text, r -> {
            r.actor(1).multiChat(type, r.fill(text));
            r.world.sleep(SPAM_GAP_MS);
            return StepResult.OK;
        });
    }

    public static Step buddyAdd(String name) {
        return of("buddy add " + name, r -> {
            r.actor(1).buddyAdd(r.fill(name));
            r.world.sleep(SPAM_GAP_MS);
            return StepResult.OK;
        });
    }

    /**
     * A server-side fixture or fact (see {@link GameAutoQaWorld#probe}); a "FAIL"/"SKIP" first line ends the check
     * that way. Used for set-up such as "be guildless" or "be in a player guild".
     */
    public static Step fixture(String what, String... args) {
        return of("fixture " + what + " " + String.join(" ", args), r -> {
            String[] filled = new String[args.length];
            for (int i = 0; i < args.length; i++) {
                filled[i] = r.fill(args[i]);
            }
            List<String> out = r.world.probe(what, r.actor(1), filled);
            String first = out.isEmpty() ? "FAIL no answer" : out.get(0);
            r.note("fixture " + what + ": " + String.join(" | ", out));
            for (String line : out) {
                if (line.startsWith("VAR ") && line.indexOf('=') > 4) {
                    r.set(line.substring(4, line.indexOf('=')), line.substring(line.indexOf('=') + 1));
                }
            }
            if (first.startsWith("FAIL")) {
                return StepResult.fail("fixture " + what + ": " + first.substring(4).trim());
            }
            if (first.startsWith("SKIP")) {
                return StepResult.skip("fixture " + what + ": " + first.substring(4).trim());
            }
            return StepResult.OK;
        });
    }
}
