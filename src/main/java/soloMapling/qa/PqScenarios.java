package soloMapling.qa;

import client.Character;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQConstants;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQLobbyChat;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQPartyDuty;

import java.awt.Point;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static soloMapling.qa.QaLog.Topic.BOT_CHAT;
import static soloMapling.qa.QaLog.Topic.PQ;

/**
 * Party quest {@code !qa} scenarios.
 *
 * <p>Ticket 75: {@code pq-1}, the Amorian Challenge (Amoria PQ) for two players of one gender. The scenario stages the
 * GM where the party starts it, hands out the entrance ticket and checks the map data the PQ depends on: the host
 * NPCs, the two stage-1 rooms with their mobs, and the portals out of stage 1. {@code !qa do check} repeats the map
 * checks after a stage has been played; {@code !qa do where} says where the GM stands.
 *
 * <p>1.5.2: {@code pq-2}, the Orbis PQ lobby with live OPQ bots (today only Orbis PQ has a bot brain:
 * soloMapling/ArtificialPlayer/BotTypes/OPQ).
 *
 * <p>Registered in {@link QaScenarios#all()}; docs/ops/qa-suite.md has the rows.
 */
final class PqScenarios {

    static final int AMORIA = 680000000;
    static final int ENTRANCE = 670010000;      // Amos the Strong, the ticket vendor
    static final int RECRUIT = 670010100;       // the host that starts the PQ
    static final int STAGE1 = 670010200;        // kill both gender rooms, then Glimmer Man, then the mirror
    static final int ENTRANCE_TICKET = 4031592;

    static final int AMOS_TICKETS = 9201043;
    static final int AMOS_HOST = 9201048;
    static final int GLIMMER_MAN = 9201047;

    /** The portals a single-gender party has to be able to use, and the ones stage 1 opens when it is cleared. */
    private static final String[] STAGE1_PORTALS = {"male00", "female00", "go00", "go01", "go02"};

    private PqScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new Apq1AmoriaPq(), new Pq2OpqLobby());
    }

    static final class Apq1AmoriaPq implements QaScenario {

        public String id() { return "pq-1"; }

        public String title() {
            return "Amoria PQ: two players of one gender start it and clear stage 1 (map data checked)";
        }

        public Set<QaLog.Topic> topics() { return EnumSet.of(QaLog.Topic.PQ); }

        public Set<Integer> watchedMaps(Character gm, String[] args) {
            return Set.of(RECRUIT, STAGE1);
        }

        public int minutes(String[] args) { return 30; }

        public List<String> stage(QaContext ctx) {
            Character gm = ctx.gmNow() == null ? ctx.gm() : ctx.gmNow();
            if (!ctx.world().warp(gm, RECRUIT)) {
                throw new QaRefusal("The Amoria PQ recruit map " + RECRUIT + " does not exist.");
            }
            if (ctx.world().npcPosition(gm, AMOS_HOST) == null) {
                throw new QaRefusal("NPC " + AMOS_HOST + " (the PQ host) does not stand on " + RECRUIT
                        + ": nobody can start the Amorian Challenge there.");
            }
            ctx.log("PQ host " + AMOS_HOST + " is on " + RECRUIT + ", " + ctx.world().whereIs(gm));

            ctx.giveBack(ENTRANCE_TICKET, 1);
            checkStage1(ctx, gm).forEach(ctx::log);

            return List.of(
                    "Make a party of 2 to 6 characters, all level 40+, all the same gender is fine (ticket 75), and none"
                            + " of you needs to be married. Everyone stands on this map.",
                    "The party leader talks to Amos and picks the top entry. The PQ starts and warps the party to the"
                            + " stage 1 map: kill every mob in BOTH rooms. Each of you can take either door now, so a"
                            + " single-gender party is not stuck on one side.",
                    "When the map is empty the leader talks to Glimmer Man, kills the fierry it spawns, uses the"
                            + " shard on the Magik Mirror, then hands the mirror's shard to Glimmer Man again. One of the"
                            + " three portals at the top then leads to stage 2.",
                    "!qa do check re-reads the map data (hosts, stage 1 mobs, the five stage 1 portals).");
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            Character gm = ctx.gmNow() == null ? ctx.gm() : ctx.gmNow();
            List<String> out = new ArrayList<>();
            if ("check".equalsIgnoreCase(name)) {
                out.addAll(checkStage1(ctx, gm));
            } else if ("where".equalsIgnoreCase(name)) {
                out.add(ctx.world().whereIs(gm));
            } else {
                throw new QaRefusal(id() + " steps: check, where.");
            }
            out.forEach(line -> ctx.run().note("STEP", line));
            return out;
        }

        /**
         * The stage 1 map has to hold both rooms' mobs and the portals a single-gender party needs, otherwise the
         * party starts and is stuck. Returns the lines it logged.
         */
        private static List<String> checkStage1(QaContext ctx, Character gm) {
            List<String> out = new ArrayList<>();
            if (!ctx.world().warp(gm, STAGE1)) {
                throw new QaRefusal("The Amoria PQ stage 1 map " + STAGE1 + " does not exist.");
            }
            int mobs = ctx.world().liveMobCount(gm);
            out.add("stage 1 has " + mobs + " live mobs" + (mobs == 0 ? " (nothing to clear: staging problem)" : ""));
            if (ctx.world().npcPosition(gm, GLIMMER_MAN) == null) {
                out.add("Glimmer Man " + GLIMMER_MAN + " is missing on " + STAGE1 + " (stage 1 cannot be cleared)");
            }
            for (String portal : STAGE1_PORTALS) {
                boolean there = ctx.world().warpToPortal(gm, STAGE1, portal);
                out.add("portal " + portal + (there ? " is on " + STAGE1 : " is MISSING from " + STAGE1));
            }
            return out;
        }
    }

    /**
     * pq-2 (1.5.2, "PQ bots are not great"): the GM stands in the Orbis PQ lobby with a handful of live OPQ bots
     * around it, so it can invite one from the party window and walk the run: lobby recruit lines (the log shows
     * how often the lobby actually speaks, against the per-lobby cap), the bot accepting, the stage 1 warp and
     * clouds, the tower, the boxes and the exit. The bots leave the party on their own if the GM never starts an
     * OPQ (OPQPartyDuty), which is the other half of what this test is for: invite a bot, then do nothing and watch
     * it go back to recruiting after the grace period.
     */
    static final class Pq2OpqLobby implements QaScenario {

        private static final int BOTS = 6;

        public String id() {
            return "pq-2";
        }

        public String title() {
            return "OPQ lobby: invite a bot, run the PQ - lobby chat rate and every stage step logged";
        }

        public Set<QaLog.Topic> topics() {
            return EnumSet.of(PQ, BOT_CHAT);
        }

        public Set<Integer> watchedMaps(Character gm, String[] args) {
            Set<Integer> maps = new LinkedHashSet<>();
            maps.add(OPQConstants.OPQ_LOBBY);
            maps.addAll(OPQConstants.OPQ_INSIDE_MAPS);
            return maps;
        }

        public Set<String> claims(Character gm, String[] args) {
            return Set.of("map:" + OPQConstants.OPQ_LOBBY);
        }

        public int minutes(String[] args) {
            return 30;
        }

        public List<String> stage(QaContext ctx) {
            Character gm = ctx.gm();
            if (!ctx.world().warp(gm, OPQConstants.OPQ_LOBBY)) {
                throw new QaRefusal("The Orbis PQ lobby (" + OPQConstants.OPQ_LOBBY + ") did not load.");
            }
            ctx.log("GM warped to the Orbis PQ lobby");
            List<Integer> bots = ctx.world().spawnOpqLobbyBots(ctx.gm(), gm.getPosition(), BOTS);
            if (bots.isEmpty()) {
                throw new QaRefusal("No OPQ lobby bots could be spawned here.");
            }
            ctx.despawnAtStop(bots, "OPQ lobby bots");

            List<String> out = new ArrayList<>();
            for (int id : bots) {
                out.add(ctx.world().botLabel(ctx.gm(), id));
            }
            out.add("Open the party window (P) and invite one of these bots: it accepts, then warps into stage 1 with"
                    + " you. Break clouds, drop the pieces at the altar and it follows you up.");
            out.add("Watch the chat line count: the lobby as a whole is capped at " + OPQLobbyChat.maxLinesPerMinute()
                    + " recruit lines a minute, and one bot waits at least "
                    + (OPQLobbyChat.DEFAULT_BOT_INTERVAL_MIN_MS / 1000) + " s between its own lines.");
            out.add("Invite a second bot and then stand still: it leaves the party on its own after "
                    + (OPQPartyDuty.PRE_RUN_GRACE_MS / 60_000)
                    + " minutes and goes back to recruiting. That is the check for \"joins and does nothing\".");
            return out;
        }
    }
}
