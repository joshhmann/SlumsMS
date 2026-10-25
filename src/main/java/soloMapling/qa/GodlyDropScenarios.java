package soloMapling.qa;

import client.Character;

import java.util.List;
import java.util.Set;

/** 1.5.2 gl-1: a godly mob drop (rare, +1..+5 on a few stats), the looter's note, and bots refusing to sell it. */
final class GodlyDropScenarios {
    static final int DEFAULT_ITEM = server.GodlyDrops.STAGE_DEFAULT_ITEM;

    private GodlyDropScenarios() { }

    static List<QaScenario> all() { return List.of(new Gl1Drop()); }

    static final class Gl1Drop implements QaScenario {
        public String id() { return "gl-1"; }
        public String title() { return "Godly drops: a godly-rolled equip, the [Godly] loot note, bots won't sell it"; }
        public String usage() { return "[equip item id]"; }
        public Set<QaLog.Topic> topics() { return Set.of(); }
        public int minutes(String[] args) { return 10; }

        public List<String> stage(QaContext ctx) {
            int itemId = itemId(ctx.args());
            return List.of(drop(ctx, itemId),
                    "Loot it: a [Godly] note in the chat box. Hover it: up to 3 of its stats sit 1-5 above a clean one.",
                    "!qa do drop [item id] drops another. Give one to a bot shop or the FM shoppers: they refuse it.");
        }

        @Override
        public List<String> action(QaContext ctx, String name, String[] args) {
            if (!"drop".equals(name)) {
                return QaScenario.super.action(ctx, name, args);
            }
            return List.of(drop(ctx, itemId(args)));
        }

        private static String drop(QaContext ctx, int itemId) {
            Character gm = ctx.gm();
            String dropped = ctx.world().dropGodly(gm, itemId);
            if (dropped == null) {
                throw new QaRefusal(itemId + " is not an equip with stats to bump.");
            }
            ctx.run().note("GODLY", "DROP " + dropped);
            return "Dropped at your feet: " + dropped + ".";
        }

        private static int itemId(String[] args) {
            if (args != null && args.length > 0) {
                try {
                    return Integer.parseInt(args[0]);
                } catch (NumberFormatException ignored) {
                    throw new QaRefusal("Usage: !qa run gl-1 [equip item id]");
                }
            }
            return DEFAULT_ITEM;
        }
    }
}
