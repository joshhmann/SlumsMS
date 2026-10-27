package soloMapling.qa;

import client.Character;
import net.server.channel.Channel;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionType;
import soloMapling.ArtificialPlayer.BotPartySystem.ExpeditionBots;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static soloMapling.qa.QaLog.Topic.PQ;

/**
 * 1.6.0 (owner 2026-10-26), {@code ex-1}: the bots in a player's party join his boss expedition with him
 * ({@link ExpeditionBots}). The run makes the GM the captain of a Zakum expedition on the map he stands on (no NPC, no
 * warp: the same call the recruiter's "Lets get this going!" makes), with follower bots in his party and one bot on
 * the map outside it. The party bots join, count toward the minimum of six and the outsider stays out; asking again
 * (the next NPC step) adds nobody twice. The expedition is ended and the bots taken away at stop.
 *
 * <p>Registered in {@link QaScenarios#all()}; docs/ops/qa-suite.md has the row.
 */
final class ExpeditionScenarios {

    static final ExpeditionType TYPE = ExpeditionType.ZAKUM;

    private ExpeditionScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new Ex1PartyBotsJoin());
    }

    static final class Ex1PartyBotsJoin implements QaScenario {

        public String id() {
            return "ex-1";
        }

        public String title() {
            return "Your party bots join your boss expedition with you (Zakum, 1.6.0)";
        }

        public String usage() {
            return "[bots 1-5]";
        }

        public Set<QaLog.Topic> topics() {
            return EnumSet.of(PQ);
        }

        public int minutes(String[] args) {
            return 3;
        }

        public List<String> stage(QaContext ctx) {
            Character gm = ctx.gm();
            if (gm.getLevel() < TYPE.getMinLevel()) {
                throw new QaRefusal("Zakum takes level " + TYPE.getMinLevel() + "+: level up first.");
            }
            if (gm.getParty() != null && gm.getParty().getLeaderId() != gm.getId()) {
                throw new QaRefusal("Only a party leader brings his bots: leave your party or lead it.");
            }
            Channel ch = gm.getClient().getChannelServer();
            if (ch.getExpedition(TYPE) != null) {
                throw new QaRefusal("This channel already has a Zakum expedition: try another channel.");
            }
            int count = DojoScenarios.Dj1PartyFollowersFight.count(ctx.args());
            if (ctx.args() == null || ctx.args().length == 0) {
                count = 5; // the six Zakum needs
            }
            List<Integer> party = ctx.world().stageDojoFollowers(gm, count);
            if (party.isEmpty()) {
                throw new QaRefusal("No follower bot could be staged here (is your party full?).");
            }
            ctx.onStop("take " + party.size() + " party bot(s) out of the party and the world",
                    () -> ctx.world().unstageDojoFollowers(party));
            List<Integer> outsider = ctx.world().spawnIdleBots(gm, gm.getPosition(), 1, gm.getLevel());
            ctx.onStop("despawn the bot outside the party", () -> outsider.forEach(ctx.world()::despawnBot));

            Expedition exped = new Expedition(gm, TYPE, true, 0, 0);
            if (!exped.addChannelExpedition(ch)) {
                throw new QaRefusal("The Zakum expedition could not be made on this channel.");
            }
            ctx.onStop("end the Zakum expedition", () -> {
                exped.dispose(false);
                exped.removeChannelExpedition(ch);
            });
            List<String> joined = ExpeditionBots.autoJoin(gm, exped);
            List<String> again = ExpeditionBots.autoJoin(gm, exped);
            boolean outsiderIn = !outsider.isEmpty() && exped.hasMember(outsider.get(0));
            int members = exped.getMembers().size();
            String result = "EX1 RESULT members=" + members + " min=" + exped.getMinSize() + " joined=" + joined.size()
                    + "/" + party.size() + " outsider=" + (outsider.isEmpty() ? "none" : outsiderIn ? "in" : "out")
                    + " again=" + again.size();
            ctx.run().note("EX1", result);
            boolean pass = joined.size() == party.size() && !outsiderIn && again.isEmpty();
            ctx.run().note("EX1", (pass ? "EX1 PASS" : "EX1 FAIL") + " " + joined);

            List<String> out = new ArrayList<>();
            out.add("You are the captain of a Zakum expedition here (made directly, as the recruiter does); "
                    + party.size() + " follower bot(s) are in your party and one bot stands outside it.");
            out.add("Joined with you: " + (joined.isEmpty() ? "nobody" : String.join(", ", joined)) + "; members "
                    + members + " of at least " + exped.getMinSize() + ".");
            out.add("The log has one join line per party bot; the outsider never joins. Stop ends the expedition.");
            return out;
        }
    }
}
