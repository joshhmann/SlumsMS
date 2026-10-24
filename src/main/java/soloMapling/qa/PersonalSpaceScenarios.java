package soloMapling.qa;

import client.Character;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static soloMapling.qa.QaLog.Topic.SPREAD;

/**
 * rc6 personal space (owner: town bots stood pixel to pixel, their name tags on top of each other). ps-1 stacks a few
 * town bots on the GM's own pixel and logs how they make room.
 */
final class PersonalSpaceScenarios {

    private PersonalSpaceScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new Ps1PersonalSpace());
    }

    /** ps-1: town bots on one pixel step apart (and off the GM), 40-120 px, on their own ground. */
    static final class Ps1PersonalSpace implements QaScenario {
        public String id() { return "ps-1"; }
        public String title() { return "Personal space: town bots stacked on one pixel step apart, never on a player"; }
        public String usage() { return "[bots 2-8, default 4]"; }
        public Set<QaLog.Topic> topics() { return EnumSet.of(SPREAD); }
        public int minutes(String[] args) { return 5; }
        public int tickSeconds() { return 10; }

        public Set<Integer> watchedMaps(Character gm, String[] args) {
            return Set.of(gm.getMapId());
        }

        public List<String> stage(QaContext ctx) {
            Character gm = ctx.gm();
            if (!ctx.world().personalSpaceApplies(gm)) {
                throw new QaRefusal("Stand in a town or hangout map (Henesys is the classic).");
            }
            int n = 4;
            for (String a : ctx.args()) {
                if (a.matches("\\d")) {
                    n = Math.max(2, Math.min(8, Integer.parseInt(a)));
                }
            }
            List<Integer> ids = ctx.world().stageStackedTownBots(gm, n);
            if (ids.isEmpty()) {
                throw new QaRefusal("No bot could be staged here.");
            }
            ctx.despawnAtStop(ids, "town bots stacked on the GM's pixel");
            tally(ctx, "START");
            return List.of(ids.size() + " town bots appear on your own pixel, on top of you and each other.",
                    "Within a few seconds each one takes a short walk (40-120 px, never off the ledge) to a free spot:"
                            + " PERSONAL SPACE lines in the log, one per step. None stays on you.",
                    "A tally every 10 s: overlapping pairs (|dx| < 12, |dy| < 5) and pairs closer than 25 px should"
                            + " drop to 0. Click each name tag: every one is clickable.",
                    "Walk onto a bot and stand still: it steps away from you within about 5 s. !qa stop when done.");
        }

        public void tick(QaContext ctx) {
            tally(ctx, "TALLY");
        }

        private void tally(QaContext ctx, String kind) {
            Character gm = ctx.gmNow();
            if (gm != null) {
                ctx.world().overlapTally(gm).forEach(l -> ctx.run().note(kind, l));
            }
        }
    }
}
