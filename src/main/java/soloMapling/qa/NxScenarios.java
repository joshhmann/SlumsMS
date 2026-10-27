package soloMapling.qa;

import client.Character;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.ArtificialPlayer.BotTypes.NXMerchantBot;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 1.6.0 nx-1: the meso to NX path. An NX merchant bot (the FM entrance's "S> 10k NX code 50m") next to the buyer,
 * and the buyer topped up to 50m: trade it, take the code by messenger (or ignore the invite and get it as a note),
 * type it into the Cash Shop coupon box. Auto-QA t-nx-1 / t-nx-2 drive it with the plain synthetic player.
 */
final class NxScenarios {
    static final int PRICE = 50_000_000;

    /** The game calls; tests could swap in a fake. */
    interface NxWorld {
        /** Makes the bot an NX merchant that keeps selling for the run; a line. */
        String makeNxMerchant(int botId);

        /** Mesos added so the buyer holds at least {@code meso}. */
        int topUp(Character buyer, int meso);

        /** Takes back up to {@code meso} the buyer still has above {@code floor}; a line. */
        String takeBack(int buyerId, long floor, int meso);

        /** "name: N mesos, M NX credit". */
        String wallet(Character buyer);
    }

    static volatile NxWorld world = new GameNxWorld();

    private NxScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new Nx1Code());
    }

    static final class Nx1Code implements QaScenario {
        private volatile int buyerId;

        public String id() { return "nx-1"; }
        public String title() { return "NX code: buy a 10k NX code from an NX merchant bot for 50m, then redeem it"; }
        public String usage() { return "[buyer name], default you (a GM cannot trade players: use a non-GM buyer)"; }
        public Set<QaLog.Topic> topics() { return Set.of(QaLog.Topic.TRADE); }
        public int minutes(String[] args) { return 10; }

        public List<String> stage(QaContext ctx) {
            QaWorld w = ctx.world();
            Character gm = ctx.gm();
            if (gm.getMapId() != QaScenarios.FM_ENTRANCE && !w.warp(gm, QaScenarios.FM_ENTRANCE)) {
                throw new QaRefusal("Cannot warp to the FM entrance.");
            }
            Character here = ctx.gmNow() == null ? gm : ctx.gmNow();
            String name = ctx.arg(0, here.getName());
            Character buyer = name.equalsIgnoreCase(here.getName()) ? here : here.getMap().getCharacterByName(name);
            if (buyer == null) {
                throw new QaRefusal("Nobody called " + name + " is at the FM entrance with you: bring them here first.");
            }
            buyerId = buyer.getId();
            List<Integer> bots = w.spawnIdleBots(here, buyer.getPosition(), 1);
            if (bots.isEmpty()) {
                throw new QaRefusal("Could not spawn a bot here.");
            }
            ctx.despawnAtStop(bots, "NX merchant");
            ctx.log(world.makeNxMerchant(bots.get(0)));
            long before = buyer.getMeso();
            int given = world.topUp(buyer, PRICE);
            if (given > 0) {
                int id = buyer.getId();
                long floor = before;
                ctx.onStop("take back the unspent top-up", () -> ctx.run().note("CLEANUP", world.takeBack(id, floor, given)));
            }
            ctx.log("buyer " + world.wallet(buyer) + (given > 0 ? " (topped up " + given + ")" : ""));
            List<String> out = new ArrayList<>();
            out.add("Open a trade with the new bot next to " + buyer.getName() + " and put 50,000,000 mesos in; it"
                    + " locks, then you press Trade.");
            out.add("Accept its Maple Messenger invite to read the code there, or leave it for 30 s: the code then comes"
                    + " as a note.");
            out.add("Type the code in the Cash Shop coupon box (dashes optional) for 10,000 NX. !qa do wallet shows mesos"
                    + " and NX; a second try with the same code must add nothing.");
            if (buyer.isGM()) {
                out.add("You are a GM and GMs cannot trade players: run it as !qa run nx-1 <non-GM name>.");
            }
            return out;
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if (!"wallet".equals(name)) {
                throw new QaRefusal("nx-1 steps: wallet");
            }
            Character buyer = ctx.world().online(buyerId);
            String line = buyer == null ? "the buyer is offline" : world.wallet(buyer);
            ctx.run().note("STEP", line);
            return List.of(line);
        }
    }

    /** The server's side. */
    static final class GameNxWorld implements NxWorld {
        public String makeNxMerchant(int botId) {
            Character bot = BotHelpers.getCharFromChannelStorage(botId);
            if (bot == null || !BotTypeManager.convertBotType(bot, BotTypeManager.BotType.NX_MERCHANT_BOT)) {
                return "bot " + botId + " could NOT be made an NX merchant";
            }
            if (CharacterStorage.getBotById(botId) instanceof NXMerchantBot nx) {
                nx.holdForQa();
            }
            return "bot " + botId + " " + bot.getName() + " is an NX merchant (a 10k NX code for 50m)";
        }

        public int topUp(Character buyer, int meso) {
            int add = Math.max(0, meso - buyer.getMeso());
            if (add > 0) {
                buyer.gainMeso(add, true);
            }
            return add;
        }

        public String takeBack(int buyerId, long floor, int meso) {
            Character buyer = soloMapling.server.SoloMaplingUtilities.world().getPlayerStorage().getCharacterById(buyerId);
            if (buyer == null) {
                return "buyer offline: top-up left";
            }
            int take = (int) Math.max(0, Math.min(meso, buyer.getMeso() - floor));
            if (take > 0) {
                buyer.gainMeso(-take, true);
            }
            return "took back " + take + " unspent top-up mesos from " + buyer.getName();
        }

        public String wallet(Character buyer) {
            int nx = buyer.getCashShop() == null ? -1 : buyer.getCashShop().getCash(1);
            return buyer.getName() + ": " + buyer.getMeso() + " mesos, " + nx + " NX credit";
        }
    }
}
