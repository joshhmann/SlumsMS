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
 * 1.7.0 ticket 130 (bots play the mechanics; KPQ automation ships in 1.6.1), TEST only: registered when
 * -Dslums.mechanics=true.
 *   !mechanics kpq [me] [bots] [perfect] [stageN] [toN]
 *                                         stage a Kerning PQ run: fresh bots (4, or 3 with "me") on Kerning City's PQ
 *                                         map, a party, the PQ started at Lakelis (a bot leader talks to him through
 *                                         his script); bots play all five stages (coupons, ropes, platforms, barrels,
 *                                         bosses and passes), take their prizes, hunt the bonus and leave. "me": you
 *                                         lead the party and play (collect the passes, check the formations and talk
 *                                         to Cloto yourself). "perfect": no wrong tries. "stageN": start on stage N (1.6.2;
 *                                         stages before it are marked cleared through the event's own state, no
 *                                         rewards, everyone walks through the opened portals). "toN": stop after
 *                                         stage N (was "stageN" before 1.6.2).
 *                                         The bots leave 20 s after the run ends.
 *                                         "hold" (1.6.2, with stage2-4, implies perfect): the bots take that stage's
 *                                         positions (ropes, platforms, barrels) and wait on them without checking,
 *                                         until !mechanics release or stop (a QA aid for screenshots).
 *   !mechanics status                     the runs going on
 *   !mechanics release                    let every held run go on (the leader checks, the stage clears)
 *   !mechanics stop                       end every run now (bots released, staged bots despawned); a run that
 *                                         staging started ends its KPQ instance with it (everyone out)
 *   !mechanics dispose kpq [channel]      end a stuck Kerning PQ instance on this (or that) channel: its run stopped,
 *                                         everyone sent out, the instance disposed. Refused for an instance with a
 *                                         real non-GM player inside. Logged as [Mechanics] (and the GM audit line)
 */
public class MechanicsCommand extends Command {
    {
        setDescription("Test server: bots play the mechanics (ticket 130). !mechanics kpq [me] [bots] [perfect]");
    }

    /** A parsed {@code !mechanics kpq ...}: who plays and how many bots. */
    public record KpqRequest(boolean withGm, int bots, boolean perfect, int lastStage, int firstStage, boolean hold) {
        public KpqRequest(boolean withGm, int bots, boolean perfect) {
            this(withGm, bots, perfect, 0, 1);
        }

        public KpqRequest(boolean withGm, int bots, boolean perfect, int lastStage) {
            this(withGm, bots, perfect, lastStage, 1);
        }

        public KpqRequest(boolean withGm, int bots, boolean perfect, int lastStage, int firstStage) {
            this(withGm, bots, perfect, lastStage, firstStage, false);
        }
    }

    static final String USAGE = "Syntax: !mechanics kpq [me] [bots 2-4] [perfect] [stage1-5] [to1-5] [hold]"
            + " | status | release | stop | dispose kpq [channel]";

    /** Parses {@code dispose kpq [channel]}: the channel to look on ({@code here} when none is given), or -1. */
    public static int parseDispose(String[] params, int here) {
        if (params.length < 2 || params.length > 3 || !"kpq".equalsIgnoreCase(params[1])) {
            return -1;
        }
        if (params.length == 2) {
            return here;
        }
        try {
            int ch = Integer.parseInt(params[2]);
            return ch >= 1 ? ch : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Parses the words after {@code kpq}; null when they make no sense. */
    public static KpqRequest parseKpq(String[] params, int from) {
        boolean me = false;
        boolean perfect = false;
        boolean hold = false;
        Integer bots = null;
        int last = 0;
        int first = 1;
        for (int i = from; i < params.length; i++) {
            String p = params[i].toLowerCase(Locale.ROOT);
            if (p.matches("stage[1-5]")) {
                first = p.charAt(5) - '0'; // 1.6.2: start there (QA asked for stage4 to mean the barrels)
                continue;
            }
            if (p.matches("to[1-5]")) {
                last = p.charAt(2) - '0';
                continue;
            }
            switch (p) {
                case "me" -> me = true;
                case "perfect" -> perfect = true;
                case "hold" -> hold = true;
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
        if (last > 0 && last < first) {
            return null;
        }
        if (hold && (first < 2 || first > 4)) {
            return null; // hold shows a formation: the ropes, platforms or barrels (stage2-4)
        }
        // a held formation is the answer's (perfect: no planned wrong formations to hold instead)
        return new KpqRequest(me, n, perfect || hold, last, first, hold);
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
                        + (req.perfect() ? ", perfect" : "") + (req.hold() ? ", held on stage " + req.firstStage() : "")
                        + "...");
                ExecutorServiceManager.getExecutorService().execute(() -> {
                    KpqStaging.Staged s = KpqStaging.start(gm, req.withGm(), req.bots(), req.perfect(), 0,
                            req.firstStage(), req.lastStage(), line -> {
                                log("[Mechanics] " + line);
                                gm.yellowMessage(line);
                            }, null);
                    if (s.ok() && req.hold()) {
                        // set right after the start: the first tick is 250 ms away and no check comes before the
                        // formation has stood still for 2.5 s
                        s.active().run().setHeld(true);
                        log("[Mechanics] RUN held for QA by " + gm.getName() + " on stage " + req.firstStage()
                                + " in " + s.active().instanceName());
                        gm.yellowMessage("Held: the bots take stage " + req.firstStage()
                                + "'s positions and wait there. !mechanics release to let them clear it, "
                                + "!mechanics stop to end the run.");
                    }
                    if (!s.ok()) {
                        gm.yellowMessage("KPQ run did not start: " + s.error());
                    } else if (!req.withGm()) {
                        gm.yellowMessage("Bot-only run in " + s.active().instanceName() + " (channel "
                                + c.getChannel() + "): progress lines follow; !mechanics status any time."
                                + (req.firstStage() > 1 ? " It starts on stage " + req.firstStage()
                                + " (earlier stages marked cleared; they walk through the portals)." : "")
                                + (req.lastStage() > 0 ? " The run ends on the stage " + req.lastStage() + " clear."
                                : " They play all five stages, take their prizes, hunt the bonus and leave."));
                    }
                });
            }
            case "status" -> MechanicsRuns.get().status().forEach(gm::yellowMessage);
            case "release" -> {
                int n = MechanicsRuns.get().release(gm.getName());
                gm.yellowMessage(n == 0 ? "No held runs." : "Released " + n + " held run(s): they clear the stage now.");
            }
            case "stop" -> {
                int n = MechanicsRuns.get().active().size();
                MechanicsRuns.get().stopAll("stopped by " + gm.getName());
                gm.yellowMessage("Stopped " + n + " run(s).");
            }
            case "dispose" -> {
                int ch = parseDispose(params, c.getChannel());
                if (ch < 0) {
                    gm.yellowMessage("Syntax: !mechanics dispose kpq [channel]");
                    return;
                }
                soloMapling.mechanics.MechanicsModel.Content kpq = MechanicsRuns.contentFor(KpqStaging.EVENT);
                net.server.channel.Channel channel = gm.getWorldServer().getChannel(ch);
                if (kpq == null || channel == null) {
                    gm.yellowMessage(kpq == null ? "kpq stage data did not load" : "no channel " + ch);
                    return;
                }
                MechanicsRuns.get().disposeStuck(kpq, channel, gm.getName()).forEach(gm::yellowMessage);
            }
            default -> gm.yellowMessage(USAGE);
        }
    }
}
