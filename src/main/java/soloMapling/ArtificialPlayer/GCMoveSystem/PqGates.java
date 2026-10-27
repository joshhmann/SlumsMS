package soloMapling.ArtificialPlayer.GCMoveSystem;

import scripting.event.EventInstanceManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
 * 1.6.0: PQ stage gates for bots. GCPortals never runs a portal script (all bots share one BotClient), so a bot could
 * walk through a closed party-quest gate: kpq0..kpq4 only open once the event instance holds "<N>stageclear".
 *
 * Most PQ gate scripts share one shape: enter()'s first if (after an optional "eim != null" guard) is a single test of
 * one event-instance property, and one branch warps while the other refuses:
 *   kpq0..4, lpq6       if (eim.getProperty("1stageclear") != null) { warp } else { refuse }
 *   lpq0..7             var avail = eim.getProperty("1stageclear"); if (avail == null) { refuse } else { warp }
 *   jnr/rnj, party3_*   if (pi.getEventInstance().getIntProperty("statusStg1") == 1) { warp } else { refuse }
 *   glpqPortal0..6      if (eim.getIntProperty("glpq2") == 5) { warp } else { refuse }   (or the reverse)
 * Such a gate is parsed once per script (a small file read, cached) and checked against the bot's instance. A
 * scripted portal inside an event that does not fit the shape (side effects, reactor states, combos, leader-only
 * doors) stays shut for bots: walking through it would skip what the script does.
 */
final class PqGates {

    private PqGates() {
    }

    /** One parsed gate: the property the script tests, how, and whether the test passing opens the portal. */
    record Gate(String key, boolean intProp, String op, int value, boolean openWhenTrue) {
        boolean open(EventInstanceManager eim) {
            boolean test;
            if (intProp) {
                int v = eim.getIntProperty(key);
                test = switch (op) {
                    case "==", "===" -> v == value;
                    case "!=", "!==" -> v != value;
                    case "<" -> v < value;
                    case "<=" -> v <= value;
                    case ">" -> v > value;
                    case ">=" -> v >= value;
                    default -> false;
                };
            } else {
                boolean isNull = eim.getProperty(key) == null;
                test = op.startsWith("=") == isNull;
            }
            return test == openWhenTrue;
        }
    }

    private static final Map<String, Optional<Gate>> CACHE = new ConcurrentHashMap<>();

    private static final String EIM = "(?:eim|pi\\.getEventInstance\\(\\)|pi\\.getPlayer\\(\\)\\.getEventInstance\\(\\))";
    private static final Pattern NULL_TEST =
            Pattern.compile("^" + EIM + "\\.getProperty\\(\"([^\"]+)\"\\)\\s*(===?|!==?)\\s*null$");
    private static final Pattern VAR_NULL_TEST = Pattern.compile("^(\\w+)\\s*(===?|!==?)\\s*null$");
    private static final Pattern INT_TEST =
            Pattern.compile("^" + EIM + "\\.getIntProperty\\(\"([^\"]+)\"\\)\\s*(===?|!==?|<=?|>=?)\\s*(-?\\d+)$");
    private static final Pattern EIM_GUARD = Pattern.compile("^eim\\s*!==?\\s*null$");

    /** True when a bot in eim may pass the scripted portal: its gate parsed and is open. */
    static boolean open(EventInstanceManager eim, String script) {
        if (eim == null || script == null) {
            return false;
        }
        Optional<Gate> gate = CACHE.computeIfAbsent(script, PqGates::load);
        return gate.isPresent() && gate.get().open(eim);
    }

    private static Optional<Gate> load(String script) {
        try {
            return Optional.ofNullable(parse(Files.readString(Path.of("scripts", "portal", script + ".js"),
                    StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The gate in a portal script's enter(), or null when the script does not fit the shared shape. */
    static Gate parse(String src) {
        int fn = src.indexOf("function enter");
        if (fn < 0) {
            return null;
        }
        String body = src.substring(fn);
        for (int depth = 0; depth < 2; depth++) { // at most one "eim != null" guard around the gate
            Matcher m = Pattern.compile("\\bif\\s*\\(").matcher(body);
            if (!m.find()) {
                return null;
            }
            int condEnd = close(body, m.end() - 1, '(', ')');
            if (condEnd < 0) {
                return null;
            }
            String cond = body.substring(m.end(), condEnd).trim();
            int braceAt = body.indexOf('{', condEnd);
            if (braceAt < 0 || !body.substring(condEnd + 1, braceAt).isBlank()) {
                return null;
            }
            int thenEnd = close(body, braceAt, '{', '}');
            if (thenEnd < 0) {
                return null;
            }
            String then = body.substring(braceAt + 1, thenEnd);
            if (EIM_GUARD.matcher(cond).matches()) {
                body = then;
                continue;
            }
            Boolean opens = opens(then);
            if (opens == null) {
                return null;
            }
            Matcher t;
            if ((t = NULL_TEST.matcher(cond)).matches()) {
                return new Gate(t.group(1), false, t.group(2), 0, opens);
            }
            if ((t = INT_TEST.matcher(cond)).matches()) {
                return new Gate(t.group(1), true, t.group(2), Integer.parseInt(t.group(3)), opens);
            }
            if ((t = VAR_NULL_TEST.matcher(cond)).matches()) {
                Matcher v = Pattern.compile("\\b(?:var|let|const)\\s+" + Pattern.quote(t.group(1)) + "\\s*=\\s*" + EIM
                        + "\\.getProperty\\(\"([^\"]+)\"\\)\\s*;").matcher(src);
                if (v.find()) {
                    return new Gate(v.group(1), false, t.group(2), 0, opens);
                }
            }
            return null;
        }
        return null;
    }

    /* Whether a branch warps (true) or refuses (false); null when it does neither plainly. */
    private static Boolean opens(String branch) {
        boolean yes = branch.contains("return true");
        boolean no = branch.contains("return false");
        if (yes == no) {
            return null;
        }
        return yes;
    }

    /* Index of the bracket closing the one at open, or -1. */
    private static int close(String s, int open, char o, char c) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == o) {
                depth++;
            } else if (ch == c && --depth == 0) {
                return i;
            }
        }
        return -1;
    }
}
