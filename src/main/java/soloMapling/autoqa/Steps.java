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
     * Waits until a line matches (qa log or heard) from the start of the step before this one on, at most
     * {@code seconds}. Not finding it does not end the check: the expectations decide. Returns as soon as it shows up.
     */
    public static Step waitFor(String regex, int seconds) {
        return of("wait up to " + seconds + " s for /" + regex + "/", r -> {
            long deadline = r.world.now() + seconds * 1000L;
            long from = r.previousStepStart();
            Pattern p = r.compile(regex);
            if (p == null) {
                return StepResult.OK; // noted; the expectations will fail on the same unknown
            }
            while (r.world.now() < deadline) {
                for (AutoQaLine l : r.lines()) {
                    if (l.timeMs() >= from && p.matcher(l.text()).find()) {
                        return StepResult.OK;
                    }
                }
                r.world.sleep(1000);
            }
            r.note("waited " + seconds + " s for /" + regex + "/: not seen");
            return StepResult.OK;
        });
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
            while (r.world.now() < deadline) {
                if (r.actor(1).acceptGuildInvite()) {
                    r.world.sleep(1000);
                    return StepResult.OK;
                }
                r.world.sleep(1000);
            }
            r.note("no guild invite arrived in " + seconds + " s");
            return StepResult.OK;
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
