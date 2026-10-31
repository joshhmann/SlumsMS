package soloMapling.qa;

import client.Character;
import client.Job;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static soloMapling.qa.QaLog.Topic.COMBAT;
import static soloMapling.qa.QaLog.Topic.MAP_CHANGE;
import static soloMapling.qa.QaLog.Topic.STAGING;

/**
 * b140: the Heal column a bot Bishop shows when it heals a player (1.6.2's healamt sends SHOW_FOREIGN_EFFECT/1).
 *
 * <p>A client script cannot do the whole scene yet: the driver has no chat anchors and no party-invite window, so
 * "spawn a Bishop, wait for its chat lines, accept its invite" stops at the first chat wait. So the scene is staged
 * server-side - the warp, one Bishop follower in the GM's party, the GM's own HP down to 40% (the runner cannot fill a
 * name into {@code !hurt}), and two {@link QaLog.Topic#STAGING} lines, one naming the bot and one the caller - and
 * {@code qa-scripts/b140-bot-heal-column-staged.yaml} does only what a client can do: wait on those feed lines, film the
 * column and the HP bar, and stop the scene.
 *
 * <p>Registered in {@link QaScenarios#all()}; docs/ops/qa-suite.md has the row.
 */
final class HealColumnScenarios {

    /** A map with mobs (a follower fights there; {@code !bot trainhere} refuses towns). */
    static final int HUNTING_GROUND = QaScenarios.HENESYS_HUNTING_GROUND;
    static final int BISHOP = Job.BISHOP.getId();
    /** A Bishop's skill plan already holds Heal 30 at this level: the level only picks the client's cast art. */
    static final int DEFAULT_LEVEL = 120;
    static final int PARTY_SIZE = 6;
    /** Below the 60% a bot's Heal looks for, so the follower casts at once; the script has no name to pass to !hurt. */
    static final int HURT_TO_PERCENT = 40;

    private HealColumnScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new Hc1BishopFollower());
    }

    static final class Hc1BishopFollower implements QaScenario {

        final Map<Integer, QaWorld.StagedFollower> staged = new ConcurrentHashMap<>();

        public String id() {
            return "hc-1";
        }

        public String title() {
            return "A staged Bishop follower heals you: the Heal column over it (b140)";
        }

        public String usage() {
            return "[level 1-200]";
        }

        public Set<QaLog.Topic> topics() {
            return EnumSet.of(MAP_CHANGE, STAGING, COMBAT);
        }

        public Set<Integer> watchedMaps(Character gm, String[] args) {
            return Set.of(HUNTING_GROUND);
        }

        public List<String> stage(QaContext ctx) {
            Character gm = ctx.gm();
            if (!soloMapling.ArtificialPlayer.BotSupportSwitches.partySupport()) {
                throw new QaRefusal("Bots heal their party only with -Dslums.bots.partysupport=true (on in 1.6.2.conf):"
                        + " without it the Bishop never casts and the script can only time out.");
            }
            if (gm.getParty() != null && gm.getParty().getMembers().size() >= PARTY_SIZE) {
                throw new QaRefusal("Your party is full (" + PARTY_SIZE + "): the staged Bishop needs a slot."
                        + " Leave the party, then run hc-1 again.");
            }
            if (!ctx.world().warp(gm, HUNTING_GROUND)) {
                throw new QaRefusal("The Henesys hunting ground " + HUNTING_GROUND + " did not load.");
            }
            ctx.log("GM warped to " + HUNTING_GROUND);
            int level = level(ctx.args());
            QaWorld.StagedFollower bot = ctx.world().stageFollowerBot(gm, BISHOP, level);
            if (bot == null) {
                throw new QaRefusal("No Bishop follower could be staged on " + HUNTING_GROUND
                        + " (a full party, or the bot did not spawn).");
            }
            staged.put(ctx.run().gmId(), bot);
            ctx.onStop("take the staged Bishop " + bot.botId() + " out of the party and the world", () -> {
                staged.remove(ctx.run().gmId());
                ctx.world().unstageFollowerBot(ctx.gmNow(), bot);
            });
            // The hurt is a step of the staging, not of the script: the runner cannot fill a character name into
            // !hurt, and the feed line it makes is what the script waits on before it films.
            int hpBefore = gm.getHp();
            int hpAfter = ctx.world().hurtTo(gm, HURT_TO_PERCENT);
            if (hpAfter < 0) {
                throw new QaRefusal("Your HP could not be set to " + HURT_TO_PERCENT + "% for the Bishop to heal"
                        + " (nothing staged).");
            }
            String line = feedLine(gm, bot, level);
            QaLog.chars(STAGING, HUNTING_GROUND, new int[]{gm.getId()}, line);
            ctx.log("staged " + line);
            String hurt = hurtLine(gm, hpBefore, hpAfter);
            QaLog.chars(STAGING, HUNTING_GROUND, new int[]{gm.getId()}, hurt);
            ctx.log("hurt " + hurt);
            List<String> out = new ArrayList<>();
            out.add(ctx.world().botLabel(gm, bot.botId()) + " is a level " + level + " Bishop, following you, in party "
                    + bot.partyId() + (bot.partyMade() ? " (made for this run)" : ""));
            out.add("Your HP is at " + HURT_TO_PERCENT + "%: it heals you, so watch its Heal column and your own HP bar"
                    + " (qa-scripts/b140-bot-heal-column-staged.yaml drives the client side and films both)."
                    + " !hurt " + gm.getName() + " " + HURT_TO_PERCENT + " drops you again for another cast.");
            out.add("!qa stop takes the Bishop out of the party and the world"
                    + (bot.partyMade() ? ", and disbands the party this run made." : "."));
            return out;
        }

        /** The feed line the script waits on: the bot's id, and the caller's char id in the line's chars. */
        static String feedLine(Character gm, QaWorld.StagedFollower bot, int level) {
            return "STAGE FOLLOWER cid=" + bot.botId() + " job=BISHOP lv=" + level + " party=" + bot.partyId()
                    + " gm=" + gm.getName() + " id=" + gm.getId() + " map=" + HUNTING_GROUND;
        }

        /** b140: the hurt line - the script's burst starts on it, so it carries no name the runner would have to fill. */
        static String hurtLine(Character gm, int hpBefore, int hpAfter) {
            return "STAGE HURT cid=" + gm.getId() + " hp=" + hpBefore + "->" + hpAfter + " pct=" + HURT_TO_PERCENT
                    + " map=" + HUNTING_GROUND;
        }

        static int level(String[] args) {
            if (args == null || args.length == 0) {
                return DEFAULT_LEVEL;
            }
            try {
                return Math.max(1, Math.min(200, Integer.parseInt(args[0].trim())));
            } catch (NumberFormatException e) {
                return DEFAULT_LEVEL;
            }
        }
    }
}
