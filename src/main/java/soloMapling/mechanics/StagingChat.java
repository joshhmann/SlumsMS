package soloMapling.mechanics;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The chat relay for a staged run's GM (b241, the owner's TEST playtest 10-31). Every line the run reports still goes
 * to the log; the GM's chat gets each line at most once per stage. The per-mob fight diagnostics were the noise: in
 * the bonus stage he was told "FIGHT VegetaBlack gives up on Horny Mushroom at 202,-286 for 10 s (no way closer in
 * 3 s)" again every few seconds until the last bot left (13 such lines in the 2 minutes of the bonus in
 * Server/BotLog.txt), and the same repeats filled t-mx-2's QA log.
 *
 * <p>A line is one per thing, not per bot: the same mob unreachable by three bots, the same passes nobody can pick
 * up, the same item dropped or taken back, count as one line for the stage (the one shown names the first bot; the
 * log has every one of them). When the stage turns over and when the run ends, the chat says how many repeats it held
 * back, so the GM knows the bots are stuck without being told every few seconds.
 */
public final class StagingChat implements Consumer<String> {

    /**
     * {@code FIGHT <bot> gives up on <mob> at x,y for 10 s (no way closer in 3 s)}: one per mob, not per bot. In the
     * owner's bonus stage three bots took turns on the same Ligator every few seconds (Server/BotLog.txt 03:22-03:24,
     * the same run shape as his), so a bot-keyed line would still have been a wall of them.
     */
    private static final Pattern FIGHT = Pattern.compile("^FIGHT \\S+ gives up on (.+?) at -?\\d+,-?\\d+ .*$");
    /** {@code STALL <bot> on n/target x <item>: ...}: one per item (every bot stalling on the same pass is one fact). */
    private static final Pattern STALL = Pattern.compile("^STALL \\S+ on .* x (\\d+): .*$");
    /** {@code DROP <bot> drops n x <item> for <name>} or {@code ... (extras)}: one per item and owner. */
    private static final Pattern DROP = Pattern.compile("^DROP \\S+ drops \\d+ x (\\d+) (.*)$");
    /** {@code TAKE BACK <bot> picks its <item> back up (...)}: one per item. */
    private static final Pattern TAKE_BACK = Pattern.compile("^TAKE BACK \\S+ picks its (\\d+) back up .*$");

    private final Consumer<String> out;
    private final IntSupplier stage;
    private final Map<String, Integer> seen = new HashMap<>();
    private int at = Integer.MIN_VALUE;

    /**
     * @param out   where the lines go that the GM is shown (his chat)
     * @param stage the run's current stage, read when a line arrives (0 before the run exists); the seen set turns
     *              over with it
     */
    public StagingChat(Consumer<String> out, IntSupplier stage) {
        this.out = out;
        this.stage = stage;
    }

    @Override
    public synchronized void accept(String line) {
        roll();
        String sig = signature(line);
        Integer repeats = seen.get(sig);
        if (repeats == null) {
            seen.put(sig, 0);
            out.accept(line);
        } else {
            seen.put(sig, repeats + 1);
        }
    }

    /** The run ended: say what the last stage's chat held back (the run's own end lines come after this). */
    public synchronized void finish() {
        roll();
        summarize();
    }

    /** What a line says, without the numbers that differ between two reports of the same thing. */
    public static String signature(String line) {
        Matcher m = FIGHT.matcher(line);
        if (m.matches()) {
            return "FIGHT " + m.group(1);
        }
        m = STALL.matcher(line);
        if (m.matches()) {
            return "STALL x " + m.group(1);
        }
        m = DROP.matcher(line);
        if (m.matches()) {
            return "DROP x " + m.group(1) + " " + m.group(2);
        }
        m = TAKE_BACK.matcher(line);
        if (m.matches()) {
            return "TAKE BACK x " + m.group(1);
        }
        return line;
    }

    private void roll() {
        int now = stage.getAsInt();
        if (now != at) {
            summarize();
            at = now;
        }
    }

    private void summarize() {
        int hidden = seen.values().stream().mapToInt(Integer::intValue).sum();
        if (hidden > 0) {
            out.accept(hidden + " repeat(s) held back this stage (the log has every line)");
        }
        seen.clear();
    }
}
