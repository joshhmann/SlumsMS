package soloMapling.qa;

import scripting.npc.NPCConversationManager;
import server.ItemInformationProvider;

import java.util.List;
import java.util.Set;

/** Read-only checks of the same data/name filter used by town salon scripts. */
final class CosmeticScenarios {
    private CosmeticScenarios() { }
    static List<QaScenario> all() { return List.of(new Lookup()); }

    static final class Lookup implements QaScenario {
        public String id() { return "sl-1"; }
        public String title() { return "Town salons recognize stock and extended cosmetic names"; }
        public Set<QaLog.Topic> topics() { return Set.of(); }
        public int minutes(String[] args) { return 1; }
        public List<String> stage(QaContext ctx) {
            int[] styles = {20000, 21700, 54859, 30060, 36000, 44000, 64467};
            var names = ItemInformationProvider.getInstance();
            var salon = new NPCConversationManager(ctx.gm().getClient(), 1012104, "1012104");
            for (int style : styles) {
                if (names.getName(style) == null || salon.getCosmeticItem(style) != style)
                    throw new QaRefusal("Salon name/filter rejected style " + style);
            }
            ctx.run().note("SALON", "LOOKUP PASS styles=7; no items or appearance changed");
            return List.of("Seven stock/extended styles passed the actual salon name filter.",
                    "No appearance, coupon or inventory changed. Check salon menus and previews in a real client.");
        }
    }
}
