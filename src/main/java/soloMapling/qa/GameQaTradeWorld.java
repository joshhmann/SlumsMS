package soloMapling.qa;

import client.Character;
import client.inventory.Item;
import server.ItemInformationProvider;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.Economy.BotDirectTrade;
import soloMapling.Economy.BotGifts;
import soloMapling.Economy.BotShops;
import soloMapling.Economy.EconomyLab;
import soloMapling.Economy.EconomyMeters;
import soloMapling.Economy.HaggleBrain;
import soloMapling.Economy.PriceBook;
import soloMapling.Economy.TradeSession;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatDecide;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** The server's {@link QaTradeWorld}: the economy lane's real calls. */
public final class GameQaTradeWorld implements QaTradeWorld {

    static final String GIFT_CHANCE = "solomapling.economy.gifts.chancePerSighting";

    private static Character bot(int botId) {
        return BotHelpers.getCharFromChannelStorage(botId);
    }

    @Override
    public List<String> openBotShop(int botId, Character gm) {
        Character b = bot(botId);
        if (b == null) {
            return List.of("bot " + botId + " is gone");
        }
        return BotShops.open(b, gm.getClient().getChannel(), gm.getMapId(), gm.getPosition(), true);
    }

    @Override
    public String closeBotShop(int botId) {
        return BotShops.close(botId, "qa stop");
    }

    @Override
    public String collectBotShop(int botId) {
        Character b = bot(botId);
        if (b == null) {
            return "bot " + botId + " is gone";
        }
        String line = BotShops.collect(b);
        return line == null ? b.getName() + " is owed nothing yet (holds " + String.format("%,d", b.getMeso()) + ")"
                : line + "; holds " + String.format("%,d", b.getMeso());
    }

    @Override
    public String gift(int botId, Character gm, String kind) {
        Character b = bot(botId);
        if (b == null) {
            return "bot " + botId + " is gone";
        }
        BotGifts.Kind k = kind == null ? null : BotGifts.Kind.valueOf(kind.toUpperCase());
        String line = BotGifts.give(b, gm, k, ThreadLocalRandom.current());
        if (line != null) {
            return line;
        }
        boolean noDrops = gm.getMap() != null
                && server.maps.FieldLimit.DROP_LIMIT.check(gm.getMap().getFieldLimit());
        String why = noDrops && k != BotGifts.Kind.MESOS ? "items cannot be dropped on this map (Free Market)"
                : k == BotGifts.Kind.SCROLL ? String.join("; ", BotGifts.scrollReasons(b))
                : (b.getMapId() == gm.getMapId() ? "same map" : "NOT on your map (" + b.getMapId() + ")")
                + String.format(", holds %,d mesos, ceiling %,d", b.getMeso(),
                soloMapling.ArtificialPlayer.BotWealth.ceiling(b));
        return b.getName() + " had nothing suitable to give (" + why + ")";
    }

    @Override
    public void certainGifts(boolean on) {
        if (on) {
            System.setProperty(GIFT_CHANCE, "1");
        } else {
            System.clearProperty(GIFT_CHANCE);
        }
    }

    @Override
    public void forgetGift(int botId, int playerId) {
        BotGifts.forget(botId, playerId);
    }

    @Override
    public String quote(int botId, Character gm, String words) {
        Character b = bot(botId);
        if (b == null) {
            return "bot " + botId + " is gone";
        }
        BotDirectTrade.Answer a = BotDirectTrade.stageQuote(b, gm, "how much for " + words);
        return a == null ? b.getName() + " does not trade in person" : b.getName() + " says: " + a.scripted()
                + " [" + a.fact() + "]";
    }

    @Override
    public List<String> haggle(long fair, String line) {
        return EconomyLab.haggle(fair, line, false);
    }

    @Override
    public List<String> price(int itemId) {
        Integer sv = null;
        try {
            sv = soloMapling.itemPool.ItemDatabase.getInstance().getItemPrice(itemId);
        } catch (RuntimeException ignored) {
            // no pool price
        }
        return EconomyLab.price(itemId, sv == null ? 0 : sv);
    }

    @Override
    public List<String> tax(long price) {
        return EconomyLab.tax(price);
    }

    @Override
    public String meters() {
        return EconomyMeters.line();
    }

    @Override
    public List<String> simulateSale(int botId, int itemId, int quantity, List<String> lines) {
        Character b = bot(botId);
        List<String> out = new ArrayList<>();
        Item item;
        if (itemId / 1000000 == 1) {
            item = ItemInformationProvider.getInstance().getEquipById(itemId);
        } else {
            item = new Item(itemId, (short) 0, (short) Math.max(1, quantity));
        }
        if (item == null) {
            return List.of("no item " + itemId);
        }
        SimWindow window = new SimWindow(List.of(item), out);
        long purse = b == null ? 50_000_000L : b.getMeso();
        TradeSession s = new TradeSession(TradeSession.Mode.BOT_BUYS, window, new TradeSession.Wallet() {
            public long spendable() {
                return purse;
            }

            public boolean reserve(long more) {
                return true;
            }

            public void release(long amount) {
            }

            public boolean friend() {
                return false;
            }
        }, PriceBook::botBuyValue);
        s.tick();
        for (String line : lines) {
            HaggleBrain.Read r = HaggleBrain.read(line, ChatDecide.get());
            out.add("player: \"" + line + "\" -> " + r.move() + (r.price() > 0 ? " " + r.price() : ""));
            s.onChat(r);
        }
        window.locked = true;
        s.tick();
        out.addAll(s.log());
        out.add("result: " + s.state() + ", bot posted " + String.format("%,d", window.posted) + (s.band() == null ? ""
                : String.format(" (band %,d..%,d)", s.band().low(), s.band().high())));
        return out;
    }

    /** A window for the headless sale: the player's items fixed, the bot's lines collected. */
    static final class SimWindow implements TradeSession.Window {
        final List<Item> items;
        final List<String> said;
        boolean locked;
        int posted;

        SimWindow(List<Item> items, List<String> said) {
            this.items = items;
            this.said = said;
        }

        public List<Item> partnerItems() {
            return items;
        }

        public int partnerMeso() {
            return 0;
        }

        public boolean partnerLocked() {
            return locked;
        }

        public int postedMeso() {
            return posted;
        }

        public boolean postMore(int delta) {
            posted += delta;
            return true;
        }

        public void say(String fact, String scripted, String mustMention) {
            said.add("bot: " + scripted);
        }

        public void confirm() {
            said.add("bot confirms");
        }

        public void cancel() {
            said.add("bot cancels");
        }
    }
}
