package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.mechanics.BossStaging;
import soloMapling.mechanics.KpqStaging;
import soloMapling.mechanics.Mechanics;
import soloMapling.mechanics.MechanicsRuns;
import server.expeditions.ExpeditionType;
import soloMapling.server.ExecutorServiceManager;

import java.util.Locale;

import static soloMapling.BotLogger.log;

/*
 * 1.6.4 ticket 130 (bots play the mechanics; KPQ automation ships in 1.6.1), TEST only: registered when
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
 *   !mechanics boss TYPE [bots] [channel] a boss run whose CAPTAIN is a bot (1.6.4): spawn "bots" bots (the row's
 *                                         minimum when omitted) on that type's recruit map, make the first one the
 *                                         party leader, and open the room through ExpeditionBots.startAsCaptain. The
 *                                         answer prints the door's value (STARTED, HUMAN_LEADS, ROOM_TAKEN, NO_SCRIPT,
 *                                         TOO_FEW, SWITCH_OFF, REFUSED) and a sentence. Refused when
 *                                         -Dslums.mechanics.boss is off or the type has no content row. This command is
 *                                         the ONLY trigger for a bot-captained run: nothing opens a boss room on its own.
 *   !mechanics pq ROW [me | for NAME] [N] [rangedK]  1.6.6 mechanics-pq-staging: any party quest row by id (lpq,
 *                                         ellinpq, magatiapq, opq, zakumpq, ...): N regulars (the row's minimum party
 *                                         when omitted) on the row's recruit map, K of them bowmen/magicians, the party
 *                                         made and the PQ started at the row's entry NPC; "me": you lead it. "for NAME":
 *                                         that character leads, and must be online on your channel (QA's leader is a
 *                                         level-0 pretend player who cannot type a GM 3 command; tools/ops/
 *                                         mechanics-stage queues the same request on TEST). Refused when the row's own
 *                                         switch is off. One [Mechanics] log line per use, naming the issuer and the
 *                                         leader.
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
        setDescription("Test server: bots play the mechanics (ticket 130). !mechanics kpq [me] [bots] | boss TYPE [bots]");
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
            + " | pq ROW [me | for NAME] [regulars] [rangedN] | boss TYPE [bots] | status | release | stop"
            + " | dispose kpq [channel]";

    /**
     * 1.6.6 mechanics-pq-staging: a parsed {@code !mechanics pq ROW [me | for NAME] [regulars] [rangedN]} - any party
     * quest row by its id (lpq, ellinpq, magatiapq, opq, zakumpq...), who leads (the GM with "me", the character named
     * after "for", or nobody: a spawned regular does), how many regulars to add (0 = the row's minimum party), and how
     * many of them are ranged (bowman/magician, alternating).
     *
     * <p>{@code leaderName} is the character a {@code for NAME} request names and {@code null} otherwise; together with
     * {@code withGm} it says who leads - see {@link #leads()}.
     */
    public record PqRequest(String rowId, boolean withGm, int regulars, int ranged, String leaderName) {
        public PqRequest(String rowId, boolean withGm, int regulars, int ranged) {
            this(rowId, withGm, regulars, ranged, null);
        }

        /** True when a character the request names leads the party: the issuer ("me") or the one after "for". */
        public boolean leads() {
            return withGm || leaderName != null;
        }
    }

    /** {@code null} when the arguments do not parse; the row and the party size are checked against the row later. */
    public static PqRequest parsePq(String[] params, int from) {
        if (params.length <= from || params[from].isBlank()) {
            return null;
        }
        String row = params[from].toLowerCase(Locale.ROOT);
        boolean me = false;
        String leaderName = null;
        int regulars = 0;
        int ranged = 0;
        for (int i = from + 1; i < params.length; i++) {
            String p = params[i].toLowerCase(Locale.ROOT);
            if (p.equals("for")) {
                // 1.6.6 QA: the leader a QA run needs is a level-0 pretend player who cannot type a GM 3 command
                String name = i + 1 < params.length ? params[++i].trim() : "";
                if (leaderName != null || !name.matches("[A-Za-z0-9]{3,13}") || name.matches("(?i)me|for|perfect|hold")) {
                    return null;
                }
                leaderName = name;
            } else if (p.equals("me")) {
                me = true;
            } else if (p.matches("ranged\\d")) {
                ranged = p.charAt(6) - '0';
            } else if (p.matches("\\d{1,2}")) {
                regulars = Integer.parseInt(p);
            } else {
                return null;
            }
        }
        if (me && leaderName != null) {
            return null; // two leaders: the issuer and the named character
        }
        if (regulars < 0 || regulars > 30 || (regulars > 0 && ranged > regulars)) {
            return null;
        }
        return new PqRequest(row, me, regulars, ranged, leaderName);
    }

    /** The party quest row this id names, or null (boss rows have !mechanics boss; KPQ keeps !mechanics kpq too). */
    public static soloMapling.mechanics.MechanicsModel.Content pqRow(String id) {
        for (soloMapling.mechanics.MechanicsModel.Content c : soloMapling.mechanics.StageData.load().content()) {
            if (c.id().equalsIgnoreCase(id) && c.kind() == soloMapling.mechanics.MechanicsModel.Kind.PQ) {
                return c;
            }
        }
        return null;
    }

    /** A parsed {@code !mechanics boss TYPE [bots]}: the expedition type and how many bots to spawn. */
    public record BossRequest(ExpeditionType type, int bots) {
    }

    /**
     * The row's own-switch refusal sentence, or {@code null} when this row may be staged here. Shared with the TEST
     * stage queue, so a queued request refuses exactly what the command refuses.
     */
    public static String pqSwitchOff(soloMapling.mechanics.MechanicsModel.Content row) {
        if (row == null) {
            return "No such party quest row.";
        }
        return soloMapling.mechanics.Mechanics.enabledFor(row) ? null
                : row.name() + " is switched off on this server (-D" + row.flag() + "=true).";
    }

    /**
     * The character of that name online on any of the world's channels, or {@code null}. The stage queue names no
     * channel, and the leader it stages for is where the leader is; the command looks on the issuer's channel only.
     */
    public static Character findOnline(String name) {
        for (net.server.world.World w : net.server.Server.getInstance().getWorlds()) {
            Character chr = w.getPlayerStorage().getCharacterByName(name);
            if (chr != null) {
                return chr;
            }
        }
        return null;
    }

    /**
     * How many regulars the request adds: the count it names, or the row's minimum party minus the leader's seat when it
     * names none (never below 1). Shared by the command's "Staging ..." line, the staging itself and the stage queue.
     */
    public static int regularsFor(soloMapling.mechanics.MechanicsModel.Content row, PqRequest req) {
        return req.regulars() > 0 ? req.regulars() : Math.max(1, row.minPlayers() - (req.leads() ? 1 : 0));
    }

    /**
     * 1.6.6 mechanics-pq-staging: stages one pq run and answers the issuer - the one path for {@code !mechanics pq} and
     * the TEST stage queue ({@link soloMapling.mechanics.StageQueue}), so a queued request runs exactly what a GM's
     * command runs. {@code leader} leads when the request names one ("me" or "for NAME"); otherwise a spawned regular
     * does. Blocking (it spawns regulars and starts the quest), so the caller runs it off the packet thread. Returns
     * the answer sentence, which is also handed to {@code tell}. The [Mechanics] line names the issuer and the leader.
     */
    public static String runPq(soloMapling.mechanics.MechanicsModel.Content row, net.server.channel.Channel on,
                               Character leader, String issuer, PqRequest req,
                               java.util.function.Consumer<String> tell) {
        String refused = pqSwitchOff(row);
        if (refused != null) {
            tell.accept(refused);
            return refused;
        }
        boolean leads = req.leads();
        int regulars = regularsFor(row, req);
        // one line per use: live arms -Dslums.mechanics too, so every staging is on the record; both names, because a
        // queued request's issuer is not the leader and QA reads these lines back (a bot-led run names no one)
        log("[Mechanics] PQ staging by " + issuer + ": " + row.id() + ", " + regulars + " regular(s), " + req.ranged()
                + " ranged, channel " + on.getId() + (leads ? ", " + leader.getName() + " leading" : ""));
        String answer;
        try {
            KpqStaging.Staged s = KpqStaging.startPq(row, on, leader, leads, regulars, req.ranged(), line -> {
                log("[Mechanics] " + line);
                tell.accept(line);
            }, active -> {
            });
            answer = s.ok() ? row.name() + " started in " + s.active().instanceName()
                    + ": progress lines follow; !mechanics status any time, !mechanics stop to end it."
                    : row.name() + " did not start: " + s.error();
        } catch (RuntimeException e) { // never leave the issuer at "Staging..." with no answer
            log("[Mechanics] PQ staging failed: " + e);
            answer = row.name() + " did not start: " + e.getMessage();
        }
        tell.accept(answer);
        return answer;
    }

    /** Parses {@code boss TYPE [bots]} after the subcommand; null when the words make no sense. */
    public static BossRequest parseBoss(String[] params, int from) {
        if (params.length <= from) {
            return null;
        }
        ExpeditionType type;
        try {
            type = ExpeditionType.valueOf(params[from].trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
        int bots = 0;
        if (params.length > from + 1) {
            try {
                bots = Integer.parseInt(params[from + 1].trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (bots < 0) {
                return null;
            }
        }
        if (params.length > from + 2) {
            return null;
        }
        return new BossRequest(type, bots);
    }

    /**
     * Runs {@code !mechanics boss}: stages the crew and opens the room, then says what the door answered. Blocking
     * (it spawns bots and plays their arrival), so the command calls it off the packet thread.
     */
    public static String runBoss(Character gm, BossRequest req) {
        BossStaging.Staged s = BossStaging.start(gm, req.type(), req.bots());
        String answer = "Boss run " + req.type().name() + (req.bots() > 0 ? " with " + req.bots() + " bots" : "")
                + " -> " + s.door().name() + ": " + s.why();
        log("[Mechanics] " + answer + " (" + gm.getName() + ")");
        return answer;
    }

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
        String what = params.length == 0 ? "" : params[0].toLowerCase(Locale.ROOT);
        // "boss" answers to its own switch (-Dslums.mechanics.boss), so it must still refuse cleanly when only that
        // one is off, instead of dying behind the mechanics switch with a message about the wrong switch
        if (!Mechanics.enabled() && !"boss".equals(what)) {
            gm.yellowMessage("Mechanics is off: the server runs without -D" + Mechanics.FLAG + "=true.");
            return;
        }
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
                    try {
                        // b241: one line per thing at most once per stage in the GM's chat (the per-mob fight
                        // diagnostics repeated every few seconds through the bonus stage); all lines still logged
                        soloMapling.mechanics.StagingChat[] chat = {null};
                        java.util.concurrent.atomic.AtomicReference<KpqStaging.Staged> staged =
                                new java.util.concurrent.atomic.AtomicReference<>();
                        chat[0] = new soloMapling.mechanics.StagingChat(gm::yellowMessage, () -> {
                            KpqStaging.Staged s = staged.get();
                            return s == null || s.active() == null ? 0 : s.active().run().currentStage();
                        });
                        KpqStaging.Staged s = KpqStaging.start(gm, req.withGm(), req.bots(), req.perfect(), 0,
                                req.firstStage(), req.lastStage(), line -> {
                                    log("[Mechanics] " + line);
                                    chat[0].accept(line);
                                }, active -> chat[0].finish());
                        staged.set(s);
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
                    } catch (RuntimeException e) { // never leave the GM at "Staging KPQ..." with no answer
                        log("[Mechanics] KPQ staging failed: " + e);
                        gm.yellowMessage("KPQ run did not start: " + e.getMessage());
                    }
                });
            }
            case "pq" -> {
                PqRequest req = parsePq(params, 1);
                soloMapling.mechanics.MechanicsModel.Content row = req == null ? null : pqRow(req.rowId());
                if (req == null || row == null) {
                    gm.yellowMessage("Syntax: !mechanics pq ROW [me | for NAME] [regulars] [rangedN]  (ROW: a party"
                            + " quest row id, e.g. lpq, ellinpq, magatiapq, opq, zakumpq)");
                    return;
                }
                Character named = null;
                if (req.leaderName() != null) {
                    named = c.getChannelServer().getPlayerStorage().getCharacterByName(req.leaderName());
                    if (named == null) {
                        // the same lookup other GM commands use; a character on another channel is not a typo
                        Character elsewhere = c.getWorldServer().getPlayerStorage()
                                .getCharacterByName(req.leaderName());
                        gm.yellowMessage(elsewhere == null
                                ? "Nobody called " + req.leaderName() + " is online. Staged nothing."
                                : req.leaderName() + " is on channel " + elsewhere.getClient().getChannel()
                                        + ", not yours (" + c.getChannel() + "). Staged nothing.");
                        return;
                    }
                }
                String whose = req.withGm() ? "you" : req.leaderName();
                gm.yellowMessage("Staging " + row.name() + ": " + (req.leads() ? whose + " + " : "")
                        + regularsFor(row, req) + " regular(s)"
                        + (req.ranged() > 0 ? ", " + req.ranged() + " ranged" : "") + "...");
                Character leader = named != null ? named : gm;
                net.server.channel.Channel on = c.getChannelServer();
                ExecutorServiceManager.getExecutorService().execute(
                        () -> runPq(row, on, leader, gm.getName(), req, gm::yellowMessage));
            }
            case "boss" -> {
                BossRequest req = parseBoss(params, 1);
                if (req == null) {
                    gm.yellowMessage("Syntax: !mechanics boss TYPE [bots]  (types: "
                            + java.util.Arrays.toString(ExpeditionType.values()) + ")");
                    return;
                }
                gm.yellowMessage("Staging a bot-captained " + req.type().name() + " run...");
                ExecutorServiceManager.getExecutorService().execute(() -> {
                    try {
                        gm.yellowMessage(runBoss(gm, req));
                    } catch (RuntimeException e) { // never leave the GM at "Staging..." with no answer
                        log("[Mechanics] boss staging failed: " + e);
                        gm.yellowMessage("Boss run did not start: " + e);
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
