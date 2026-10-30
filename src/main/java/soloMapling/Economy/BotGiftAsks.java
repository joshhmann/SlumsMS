package soloMapling.Economy;

import client.Character;
import client.inventory.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotHoldings;
import soloMapling.ArtificialPlayer.BotPartySystem.BotCommandParser;
import soloMapling.ArtificialPlayer.BotSupplySystem.SupplyShare;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotGiveTrade.Give;
import soloMapling.qa.QaLog;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 1.6.2 (owner, grill 2026-10-30: "bot friends handing over items: yes, small and capped"): a player asks a regular
 * who likes them for something - "got any pots?", "can u spare a pot", "need pots", "any scrolls?" - and it hands over
 * a small, cheap gift from what it really holds, or says a natural no.
 * <ul>
 *   <li><b>Who:</b> a population bot (never an owned alt: those trade through {@code BotGiveTrade}) whose affinity
 *   toward the player is friend ({@link BotAffinity#friend}), on the player's map, that the player is talking to (the
 *   chat router picked it). Anyone else asked says no.</li>
 *   <li><b>What:</b> the companions' supply rules ({@link SupplyShare}: the potions it drinks, the arrows or bolts the
 *   player's weapon fires, never stars or bullets; the bot always keeps its own reserve) or a common scroll from its
 *   bag ({@link BotGifts#giftableScroll}); at most {@code giftAsks.potions} potions or {@code giftAsks.ammo} arrows,
 *   worth at most {@code giftAsks.maxValue} in all, never gear and never what bots may not sell
 *   ({@link BotGifts#giftProblem}). Nothing is minted: the units leave its bag through {@link BotHoldings#payOut}
 *   (the ledger) and land at the player's feet, theirs alone - the same delivery as the unprompted gift.</li>
 *   <li><b>How often:</b> {@code giftAsks.perBotPerDay} (2) a bot, {@code giftAsks.perPlayerPerDay} (3) a player
 *   account (alts share it), {@code giftAsks.worldPerDay} (200) world-wide, all daily meters kept across a restart;
 *   and one gift of any kind per bot and player in {@code giftAsks.perPairDays} (7) days, counted with the
 *   unprompted gifts in {@code botgifts}.</li>
 * </ul>
 * Switch: {@code giftAsks.enabled} in economy.yaml (on for 1.6.2), or {@code -Dsolomapling.economy.giftAsks.enabled}.
 */
public final class BotGiftAsks {

    private static final Logger log = LoggerFactory.getLogger(BotGiftAsks.class);

    /** What happened to an ask. */
    public enum Outcome { NOT_AN_ASK, NOT_A_FRIEND, ALREADY_THIS_WEEK, NOTHING_TO_SPARE, NO_DROPS, CAPPED, FAILED, GAVE }

    /** What the bot gives: an item id, how many, and how it names it. */
    record Pick(int itemId, int quantity, String said) {
    }

    /** What the game does; a seam for the tests. */
    public interface Effects {
        boolean friend(int botId, int playerId);

        /** The bot's line to the player (its chat bubble). */
        void say(Character bot, String line);

        /** The gift landed: the bot says so warmly ({@link BotGifts#greet}). */
        void gave(Character bot, Character player, String scripted);
    }

    static final Effects GAME = new Effects() {
        @Override
        public boolean friend(int botId, int playerId) {
            return BotAffinity.friend(botId, playerId);
        }

        @Override
        public void say(Character bot, String line) {
            SocialCommands.BotChatbubble(bot, line);
        }

        @Override
        public void gave(Character bot, Character player, String scripted) {
            BotGifts.greet(bot, player, scripted);
        }
    };

    private static volatile Effects effects = GAME;
    /** Pairs with an ask being answered right now: a second line in the same breath is not a second gift. */
    private static final Set<Long> answering = ConcurrentHashMap.newKeySet();

    private BotGiftAsks() {
    }

    public static boolean enabled() {
        return EconomyConfig.getBool("giftAsks.enabled", true);
    }

    static long maxValue() {
        return Math.max(0, EconomyConfig.getLong("giftAsks.maxValue", 100_000));
    }

    static long pairMs() {
        return TimeUnit.DAYS.toMillis(Math.max(0, EconomyConfig.getLong("giftAsks.perPairDays", 7)));
    }

    /**
     * A player said {@code line} to this bot. True when it was an ask the bot answered (a gift or a no: the caller
     * then says no more); false leaves the line to the normal chat. Reads the gift store when the pair has no gift in
     * memory, so call it off the game threads (the dispatcher's pool).
     */
    public static boolean tryHandle(Character bot, Character player, String line) {
        return handle(bot, player, line) != Outcome.NOT_AN_ASK;
    }

    static Outcome handle(Character bot, Character player, String line) {
        if (!enabled() || bot == null || player == null || bot == player || !BotHelpers.isRosterBot(bot)
                || BotHelpers.isBot(player) || bot.getMap() == null || bot.getMapId() != player.getMapId()
                || soloMapling.ArtificialPlayer.PlayerBusy.isBusy(player)) {
            return Outcome.NOT_AN_ASK;
        }
        String what = BotCommandParser.supplyAsk(line);
        if (what == null) {
            return Outcome.NOT_AN_ASK;
        }
        long pair = BotGifts.key(bot.getId(), player.getId());
        if (!answering.add(pair)) {
            return Outcome.CAPPED;   // already answering this pair's ask: the first answer stands
        }
        Result r;
        try {
            r = decide(bot, player, what);
        } finally {
            answering.remove(pair);
        }
        Outcome out = r.outcome();
        Pick pick = r.pick();
        String said = saidOf(what);
        switch (out) {
            case NOT_A_FRIEND -> effects.say(bot, pick(NOT_FRIEND_LINES));
            case ALREADY_THIS_WEEK -> effects.say(bot, pick(ALREADY_LINES));
            case NOTHING_TO_SPARE -> effects.say(bot, String.format(pick(NOTHING_LINES), said));
            case NO_DROPS -> effects.say(bot, pick(NO_DROP_LINES));
            case CAPPED, FAILED -> effects.say(bot, pick(CAPPED_LINES));
            case GAVE -> effects.gave(bot, player, pick.said().equals("scroll") ? "found this, u can have it"
                    : String.format(pick(GAVE_LINES), pick.said()));
            default -> { }
        }
        if (QaLog.on(QaLog.Topic.TRADE)) {
            QaLog.any(QaLog.Topic.TRADE, "GIFT-ASK " + player.getName() + " asked " + bot.getName() + " for " + what
                    + ": " + out + (pick == null ? "" : " (" + pick.quantity() + " x " + pick.itemId() + ")"));
        }
        return out;
    }

    /** An ask's outcome and, when it gave, what landed. */
    record Result(Outcome outcome, Pick pick) {
        Result(Outcome outcome) {
            this(outcome, null);
        }
    }

    private static Result decide(Character bot, Character player, String what) {
        if (!effects.friend(bot.getId(), player.getId())) {
            return new Result(Outcome.NOT_A_FRIEND);
        }
        long now = BotGifts.now();
        long last = BotGifts.lastGiftAt(bot.getId(), player.getId());
        if (last > 0 && now - last < pairMs()) {
            return new Result(Outcome.ALREADY_THIS_WEEK);
        }
        Pick pick = choose(bot, player, what, maxValue());
        if (pick == null) {
            return new Result(Outcome.NOTHING_TO_SPARE);
        }
        if (player.getMap() == null || !BotGifts.dropsAllowed(player.getMap().getFieldLimit())) {
            return new Result(Outcome.NO_DROPS);   // a Free Market room: drops vanish (t-gf-1, rc6)
        }
        int account = player.getAccountID();
        if (!claim(bot.getId(), account)) {
            return new Result(Outcome.CAPPED);
        }
        int landed = BotHoldings.payOut(bot, pick.itemId(), pick.quantity(), player, player.getPosition(), true);
        if (landed <= 0) {
            release(bot.getId(), account);
            return new Result(Outcome.FAILED);
        }
        String line = String.format("%s gave %s %d x %s (asked for %s)", bot.getName(), player.getName(), landed,
                BotShops.nameOf(pick.itemId()), what);
        BotGifts.record(bot, player, line);
        log.info("[GiftAsk] {}", line);
        return new Result(Outcome.GAVE, new Pick(pick.itemId(), landed, pick.said()));
    }

    /**
     * What the bot can give for this ask, or null: the supply rules ({@link SupplyShare#handover}: its spare above its
     * own reserve, fullest stack first) or a common scroll ({@link BotGifts#giftableScroll}), cut down to the ask's
     * size and value cap and passed through the one gift rule ({@link BotGifts#giftProblem}).
     */
    static Pick choose(Character bot, Character player, String what, long max) {
        if (what.equals("scrolls")) {
            Item scroll = BotGifts.giftableScroll(bot,
                    Math.min(max, EconomyConfig.getLong("gifts.scrollMaxValue", 500_000)));
            return scroll == null || BotGifts.giftProblem(scroll, 1, max) != null ? null
                    : new Pick(scroll.getItemId(), 1, "scroll");
        }
        for (SupplyShare.Kind kind : SupplyShare.kindsOf(what)) {
            if (kind == SupplyShare.Kind.ROCKS) {
                continue;
            }
            List<Give> spare = SupplyShare.handover(bot, kind, player);
            if (spare.isEmpty()) {
                continue;
            }
            Give top = spare.get(0);
            int spareOfIt = 0;   // the spare of the fullest stack's item, across its stacks (payOut takes by id)
            for (Give g : spare) {
                if (g.item().getItemId() == top.item().getItemId()) {
                    spareOfIt += g.quantity();
                }
            }
            int most = kind == SupplyShare.Kind.AMMO ? EconomyConfig.getInt("giftAsks.ammo", 200)
                    : EconomyConfig.getInt("giftAsks.potions", 20);
            int q = BotGifts.unitsUnder(top.item(), Math.min(spareOfIt, Math.max(0, most)), max);
            if (q > 0 && BotGifts.giftProblem(top.item(), q, max) == null) {
                return new Pick(top.item().getItemId(), q, SupplyShare.said(kind));
            }
        }
        return null;
    }

    /** Reserves one of today's asked-for gifts for this bot, this account and the world; all three or none. */
    static boolean claim(int botId, int account) {
        if (!EconomyMeters.tryAdd(botMeter(botId), 1, Math.max(0, EconomyConfig.getLong("giftAsks.perBotPerDay", 2)))) {
            return false;
        }
        if (!EconomyMeters.tryAdd(accountMeter(account), 1,
                Math.max(0, EconomyConfig.getLong("giftAsks.perPlayerPerDay", 3)))) {
            EconomyMeters.add(botMeter(botId), -1);
            return false;
        }
        if (!EconomyMeters.tryAdd(EconomyMeters.GIFT_ASKS, 1,
                Math.max(0, EconomyConfig.getLong("giftAsks.worldPerDay", 200)))) {
            EconomyMeters.add(botMeter(botId), -1);
            EconomyMeters.add(accountMeter(account), -1);
            return false;
        }
        return true;
    }

    static void release(int botId, int account) {
        EconomyMeters.add(botMeter(botId), -1);
        EconomyMeters.add(accountMeter(account), -1);
        EconomyMeters.add(EconomyMeters.GIFT_ASKS, -1);
    }

    static String botMeter(int botId) {
        return EconomyMeters.GIFT_ASK_BOT_PREFIX + botId;
    }

    static String accountMeter(int account) {
        return EconomyMeters.GIFT_ASK_ACCOUNT_PREFIX + account;
    }

    static String saidOf(String what) {
        return switch (what) {
            case "hp" -> "hp pots";
            case "mp" -> "mp pots";
            case "ammo" -> "arrows";
            case "scrolls" -> "scrolls";
            default -> "pots";
        };
    }

    // The bot's own words: a friend's, never anything about what it is.
    static final String[] NOT_FRIEND_LINES = {"sorry, need mine rn", "nah im running low myself",
            "cant spare any, sry", "i only got enough for me rn"};
    static final String[] ALREADY_LINES = {"i already gave u some this week lol", "gave u stuff not long ago, ask me another time",
            "been generous enough this week haha, next time"};
    static final String[] NOTHING_LINES = {"dont have any spare %s, sry", "im out of %s myself", "no extra %s on me rn"};
    static final String[] NO_DROP_LINES = {"cant hand stuff over in here, catch me outside", "not in here, ask me outside the market"};
    static final String[] CAPPED_LINES = {"im tapped out today, sorry", "cant today, maybe tmrw",
            "gave away too much already today lol"};
    static final String[] GAVE_LINES = {"here, have some %s", "sure, take these %s", "np, here u go"};

    private static String pick(String[] lines) {
        return lines[ThreadLocalRandom.current().nextInt(lines.length)];
    }

    /**
     * QA re-tests (tr-9): today's asked-for gifts of this bot and this account back to 0; the world's count stays.
     * The pair's gift time is {@link BotGifts#forget}.
     */
    public static void forgetForQa(int botId, int account) {
        EconomyMeters.add(botMeter(botId), -EconomyMeters.get(botMeter(botId)));
        EconomyMeters.add(accountMeter(account), -EconomyMeters.get(accountMeter(account)));
    }

    // ---- test seams ----

    static void useForTest(Effects test) {
        effects = test == null ? GAME : test;
        answering.clear();
    }
}
