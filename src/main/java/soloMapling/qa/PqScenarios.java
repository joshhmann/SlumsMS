package soloMapling.qa;

import client.Character;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQLobbyChat;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQConstants;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQPartyDuty;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static soloMapling.qa.QaLog.Topic.BOT_CHAT;
import static soloMapling.qa.QaLog.Topic.PQ;

/**
 * {@code !qa} scenarios for the party quests the bots take part in (today only Orbis PQ has a bot brain:
 * soloMapling/ArtificialPlayer/BotTypes/OPQ).
 */
public final class PqScenarios {

    private PqScenarios() {
    }

    public static List<QaScenario> all() {
        return List.of(new Pq1Lobby());
    }

    /**
     * pq-1 (1.5.2, "PQ bots are not great"): the GM stands in the Orbis PQ lobby with a handful of live OPQ bots
     * around it, so it can invite one from the party window and walk the run: lobby recruit lines (the log shows
     * how often the lobby actually speaks, against the per-lobby cap), the bot accepting, the stage 1 warp and
     * clouds, the tower, the boxes and the exit. The bots leave the party on their own if the GM never starts an
     * OPQ (OPQPartyDuty), which is the other half of what this test is for: invite a bot, then do nothing and watch
     * it go back to recruiting after the grace period.
     */
    static final class Pq1Lobby implements QaScenario {

        private static final int BOTS = 6;

        public String id() {
            return "pq-1";
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
