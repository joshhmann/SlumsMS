package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.mechanics.KpqStaging;
import soloMapling.mechanics.Mechanics;
import soloMapling.mechanics.MechanicsRuns;
import soloMapling.server.ExecutorServiceManager;

import java.util.Locale;

import static soloMapling.BotLogger.log;

/*
 * 1.7.0 ticket 130 (bots play the mechanics), TEST only: registered when -Dslums.mechanics=true.
 *   !mechanics kpq [me] [bots] [perfect]  stage a Kerning PQ run: fresh bots (4, or 3 with "me") on Kerning City's PQ
 *                                         map, a party, the PQ started as Lakelis starts it; bots clear stage 1
 *                                         (Cloto's question, Ligator coupons, passes to the leader). "me": you lead
 *                                         the party and play (collect the passes and talk to Cloto yourself).
 *                                         "perfect": no wrong tries. The bots leave 20 s after the run ends.
 *   !mechanics status                     the runs going on
 *   !mechanics stop                       end every run now (bots released, staged bots despawned)
 */
public class MechanicsCommand extends Command {
    {
        setDescription("Test server: bots play the mechanics (ticket 130). !mechanics kpq [me] [bots] [perfect]");
    }

    /** A parsed {@code !mechanics kpq ...}: who plays and how many bots. */
    public record KpqRequest(boolean withGm, int bots, boolean perfect) {
    }

    static final String USAGE = "Syntax: !mechanics kpq [me] [bots 2-4] [perfect] | status | stop";

    /** Parses the words after {@code kpq}; null when they make no sense. */
    public static KpqRequest parseKpq(String[] params, int from) {
        boolean me = false;
        boolean perfect = false;
        Integer bots = null;
        for (int i = from; i < params.length; i++) {
            String p = params[i].toLowerCase(Locale.ROOT);
            switch (p) {
                case "me" -> me = true;
                case "perfect" -> perfect = true;
                case "bots" -> {
                }
                default -> {
                    try {
                        bots = Integer.parseInt(p);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
            }
        }
        int n = bots != null ? bots : (me ? 3 : 4);
        int min = me ? 2 : 3;
        if (n < min || n > (me ? 3 : 4)) {
            return null;
        }
        return new KpqRequest(me, n, perfect);
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        if (!Mechanics.enabled()) {
            gm.yellowMessage("Mechanics is off: the server runs without -D" + Mechanics.FLAG + "=true.");
            return;
        }
        String what = params.length == 0 ? "" : params[0].toLowerCase(Locale.ROOT);
        switch (what) {
            case "kpq" -> {
                KpqRequest req = parseKpq(params, 1);
                if (req == null) {
                    gm.yellowMessage(USAGE + "  (bots: 3-4 alone, 2-3 with me)");
                    return;
                }
                gm.yellowMessage("Staging KPQ: " + (req.withGm() ? "you + " : "") + req.bots() + " bots"
                        + (req.perfect() ? ", perfect" : "") + "...");
                ExecutorServiceManager.getExecutorService().execute(() -> {
                    KpqStaging.Staged s = KpqStaging.start(gm, req.withGm(), req.bots(), req.perfect(), 0,
                            line -> {
                                log("[Mechanics] " + line);
                                gm.yellowMessage(line);
                            }, null);
                    if (!s.ok()) {
                        gm.yellowMessage("KPQ run did not start: " + s.error());
                    } else if (!req.withGm()) {
                        gm.yellowMessage("Bot-only run in " + s.active().instanceName() + " (channel "
                                + c.getChannel() + "): progress lines follow; !mechanics status any time."
                                + " Phase 1 ends on the stage 1 clear.");
                    }
                });
            }
            case "status" -> MechanicsRuns.get().status().forEach(gm::yellowMessage);
            case "stop" -> {
                int n = MechanicsRuns.get().active().size();
                MechanicsRuns.get().stopAll("stopped by " + gm.getName());
                gm.yellowMessage("Stopped " + n + " run(s).");
            }
            default -> gm.yellowMessage(USAGE);
        }
    }
}
