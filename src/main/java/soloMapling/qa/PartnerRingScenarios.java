package soloMapling.qa;

import client.BotClient;
import client.Character;
import client.Ring;
import server.cashshop.CashShopCatalog;
import server.cashshop.CashShopPartnerRings;
import server.cashshop.CashShopWindowPackets;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 1.5.0: {@code pr-1} (friendship ring) and {@code pr-2} (couple ring) from the Cash Shop window, player to player.
 * The partner has to be a second real character (bots are refused as partners), so the GM names it:
 * {@code !qa run pr-1 <partner> [ring id]}. Staging gives the GM the NX for two rings and pulls the partner onto the
 * GM's map; the purchase, refusals and both ring records go to the log ({@link QaLog.Topic#RING}), equips too.
 * Registered in {@link QaScenarios#all()}; docs/ops/qa-suite.md has the rows.
 */
final class PartnerRingScenarios {

    /** The game calls; the staging tests swap in a fake. */
    interface RingWorld {
        /** A real (non-bot) player online in the world by name, or null. */
        Character player(String name);

        int nx(Character chr);

        void gainNx(Character chr, int delta);

        /** Puts {@code who} next to {@code gm} (same channel only); false when it cannot. */
        boolean summon(Character who, Character gm);

        /** The character's ring records, one line each. */
        List<String> rings(Character chr);
    }

    static volatile RingWorld rings = new GameRingWorld();

    static final int FRIENDSHIP_DEFAULT = 1112800;   // Friendship Ring : Clover
    static final int COUPLE_DEFAULT = 1112001;       // Crush Ring
    static final int NX_GIVEN = 2 * 6000;            // two rings at the dearest price

    private PartnerRingScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new PartnerRing("pr-1", "friendship", FRIENDSHIP_DEFAULT),
                new PartnerRing("pr-2", "couple", COUPLE_DEFAULT));
    }

    static final class PartnerRing implements QaScenario {
        private final String id;
        private final String kind;
        private final int defaultRing;
        private volatile String partnerName;

        PartnerRing(String id, String kind, int defaultRing) {
            this.id = id;
            this.kind = kind;
            this.defaultRing = defaultRing;
        }

        public String id() { return id; }
        public String title() {
            return "Cash Shop " + kind + " ring: buy one with a second player, both rings and the effect, logged";
        }
        public String usage() { return "<partner character> [ring id], default " + defaultRing; }
        public Set<QaLog.Topic> topics() { return EnumSet.of(QaLog.Topic.RING, QaLog.Topic.EQUIP); }
        public int minutes(String[] args) { return 20; }

        public List<String> stage(QaContext ctx) {
            String name = ctx.arg(0, null);
            if (name == null) {
                throw new QaRefusal(id + " needs a partner: log a second (non-bot) character in, then !qa run " + id
                        + " <its name>.");
            }
            int ring = defaultRing;
            if (ctx.args().length > 1 && ctx.args()[1].matches("\\d{7}")) {
                ring = Integer.parseInt(ctx.args()[1]);
            }
            boolean couple = CashShopPartnerRings.isCoupleRing(ring);
            if (!CashShopPartnerRings.isPartnerRing(ring) || couple != "couple".equals(kind)) {
                throw new QaRefusal(ring + " is not a " + kind + " ring the Cash Shop sells. The " + kind + " rings: "
                        + new java.util.TreeSet<>("couple".equals(kind) ? CashShopPartnerRings.COUPLE_RINGS
                        : CashShopPartnerRings.FRIENDSHIP_RINGS) + ".");
            }
            Character gm = ctx.gmNow() == null ? ctx.gm() : ctx.gmNow();
            Character partner = rings.player(name);
            if (partner == null) {
                throw new QaRefusal(name + " is not a player online in this world (bots cannot share rings).");
            }
            if (partner.getId() == gm.getId()) {
                throw new QaRefusal("The partner must be a second character, not you.");
            }
            partnerName = partner.getName();

            rings.gainNx(gm, NX_GIVEN);
            ctx.log("gave " + NX_GIVEN + " NX credit to " + gm.getName() + " (now " + rings.nx(gm) + ")");
            ctx.onStop("take back the NX left of the " + NX_GIVEN + " given", () -> {
                Character now = ctx.gmNow();
                if (now == null) {
                    ctx.run().note("CLEANUP", "GM offline: the NX stays with the account");
                    return;
                }
                int back = Math.min(NX_GIVEN, rings.nx(now));
                rings.gainNx(now, -back);
                ctx.run().note("CLEANUP", "took back " + back + " NX of " + NX_GIVEN);
            });
            if (rings.summon(partner, gm)) {
                ctx.log("summoned " + partner.getName() + " next to " + gm.getName());
            } else {
                ctx.log(partner.getName() + " is on another channel: move it to the GM's channel and map by hand");
            }
            for (String line : rings.rings(gm)) ctx.log("before: " + gm.getName() + " " + line);
            for (String line : rings.rings(partner)) ctx.log("before: " + partner.getName() + " " + line);

            String ringName = ringName(ring);
            return List.of(
                    "You have " + NX_GIVEN + " NX. Open the Cash Shop (Ring category), pick " + ringName + " (" + ring
                            + ") and press Buy (not Buy Cart). A prompt asks for the partner: type " + partnerName + ".",
                    "Both of you get the ring in the Equip inventory, " + partnerName + " with a popup. Both equip it,"
                            + " both change channel once, then stand next to each other: the ring effect must show"
                            + " on both screens (and a third client's).",
                    "Also try: Esc on the prompt (nothing charged), a wrong or offline name (popup, nothing charged),"
                            + " buying the same ring with " + partnerName + " again (refused).",
                    "!qa do rings lists both characters' ring records now.");
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if ("rings".equalsIgnoreCase(name)) {
                List<String> out = new ArrayList<>();
                Character gm = ctx.gmNow() == null ? ctx.gm() : ctx.gmNow();
                for (String line : rings.rings(gm)) out.add(gm.getName() + " " + line);
                Character partner = partnerName == null ? null : rings.player(partnerName);
                if (partner != null) {
                    for (String line : rings.rings(partner)) out.add(partner.getName() + " " + line);
                }
                if (out.isEmpty()) out.add("no ring records");
                out.forEach(l -> ctx.run().note("STEP", l));
                return out;
            }
            throw new QaRefusal(id + " steps: rings.");
        }
    }

    static String ringName(int itemId) {
        CashShopCatalog.Row row = CashShopCatalog.byItemId(itemId);
        return row == null ? "ring " + itemId + " (NOT IN THE CATALOGUE)" : row.name();
    }

    /** The game. */
    static final class GameRingWorld implements RingWorld {
        public Character player(String name) {
            Character chr = soloMapling.server.SoloMaplingUtilities.world().getPlayerStorage().getCharacterByName(name);
            return chr == null || chr.getClient() == null || chr.getClient() instanceof BotClient ? null : chr;
        }

        public int nx(Character chr) {
            return chr.getCashShop().getCash(CashShopWindowPackets.NX_CREDIT);
        }

        public void gainNx(Character chr, int delta) {
            chr.getCashShop().gainCash(CashShopWindowPackets.NX_CREDIT, delta);
            if (chr.getClient() != null) {
                chr.getClient().sendPacket(CashShopWindowPackets.cash(chr));
            }
        }

        public boolean summon(Character who, Character gm) {
            if (who.getClient() == null || gm.getClient() == null
                    || who.getClient().getChannel() != gm.getClient().getChannel()) {
                return false;
            }
            who.changeMap(gm.getMap(), gm.getPosition());
            return true;
        }

        public List<String> rings(Character chr) {
            List<String> out = new ArrayList<>();
            for (Ring r : chr.getCrushRings()) out.add(line("couple", r));
            for (Ring r : chr.getFriendshipRings()) out.add(line("friendship", r));
            return out;
        }

        private static String line(String kind, Ring r) {
            return kind + " ring " + r.getItemId() + " #" + r.getRingId() + " pair #" + r.getPartnerRingId()
                    + " with " + r.getPartnerName() + " (" + r.getPartnerChrId() + ")"
                    + (r.equipped() ? " EQUIPPED" : "");
        }
    }
}
