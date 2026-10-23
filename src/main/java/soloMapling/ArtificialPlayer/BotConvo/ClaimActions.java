package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
import soloMapling.ArtificialPlayer.BotChatActions;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotStore;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * rc6 say-do: what makes a claim come true. Two uses:
 * <ul>
 *   <li><b>Ambient and scripted chatter</b> ({@code SocialCommands.BotAmbientSpeak}, the SocialHotPotato picker): a
 *   line with a claim is said only when {@link #canBack} says this bot will do it (or it is true already); after the
 *   line is said, {@link #start} does it. One behaviour per claim, short and cheap: "brb pots" -> the real resupply
 *   trip (TrainingBot); "brb"/"afk"/"mom's calling" -> it stops and maybe sits for 30-90 s; "gtg"/"night all" -> a roster
 *   bot logs out through the director's normal path within a minute; "going to orbis" -> a town wanderer walks there.
 *   A grinder mid-fight never takes a "brb" line, and nobody promises to come, follow, buff or invite from ambient
 *   chatter (there is nobody it is talking to).</li>
 *   <li><b>Replies</b> ({@code BotConversation.deliver}): {@link #guardReply} removes a claim the decided action does not
 *   back ("omw" when the walk was refused, "inv me" with no party decided).</li>
 * </ul>
 */
public final class ClaimActions {

    /** Test seam: the game side (null = the real game). */
    public interface Game {
        boolean canRestock(Character bot);

        void restock(Character bot);

        /** True when a "brb/afk" is true without doing anything (resting, in town), or the bot can go idle now. */
        boolean canIdle(Character bot);

        void idle(Character bot, long ms);

        boolean canLogOut(Character bot);

        void logOutSoon(Character bot, long ms);

        boolean canTravel(Character bot, int mapId);

        void travel(Character bot, int mapId);

        boolean partied(Character bot);

        boolean holdsOrWants(Character bot, ClaimRules.Claim claim);
    }

    private static volatile Game game = new GameSide();

    private ClaimActions() {
    }

    public static void useForTest(Game g) {
        game = g == null ? new GameSide() : g;
    }

    /** Whether every strong claim in the line is true already or one this bot will now make true. */
    public static boolean canBack(Character bot, List<ClaimRules.Claim> claims) {
        if (claims == null || claims.isEmpty()) {
            return true;
        }
        if (bot == null) {
            return false;
        }
        for (ClaimRules.Claim c : claims) {
            if (c.weak()) {
                continue;
            }
            boolean ok = switch (c.kind()) {
                case RESUPPLY -> game.canRestock(bot);
                case AWAY -> game.canIdle(bot);
                case LEAVING -> game.canLogOut(bot);
                case GO_TO -> ClaimRules.sameArea(bot.getMapId(), c.mapId()) || game.canTravel(bot, c.mapId());
                case TRAIN_AT -> ClaimRules.sameArea(bot.getMapId(), c.mapId());
                case PARTY_ASK -> !game.partied(bot);
                case SELL, BUY -> game.holdsOrWants(bot, c);
                default -> false; // come, follow, invite, buff, trade, gift, leave: nobody to do it for here
            };
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** The line was said: make its claims true (call only after {@link #canBack} said yes). */
    public static void start(Character bot, List<ClaimRules.Claim> claims) {
        if (bot == null || claims == null) {
            return;
        }
        for (ClaimRules.Claim c : claims) {
            if (c.weak()) {
                continue;
            }
            switch (c.kind()) {
                case RESUPPLY -> game.restock(bot);
                case AWAY -> game.idle(bot, 30_000 + ThreadLocalRandom.current().nextInt(60_001));
                case LEAVING -> game.logOutSoon(bot, 10_000 + ThreadLocalRandom.current().nextInt(30_001));
                case GO_TO -> {
                    if (!ClaimRules.sameArea(bot.getMapId(), c.mapId())) {
                        game.travel(bot, c.mapId());
                    }
                }
                default -> {
                }
            }
        }
    }

    /** One ambient line: false (and nothing said) when its claims cannot be backed; else says it and backs it. */
    public static boolean sayBacked(Character bot, String line, java.util.function.Consumer<String> say) {
        List<ClaimRules.Claim> claims = ClaimRules.classify(line);
        if (!canBack(bot, claims)) {
            refused(bot, line, claims);
            return false;
        }
        say.accept(line);
        start(bot, claims);
        return true;
    }

    static void refused(Character bot, String line, List<ClaimRules.Claim> claims) {
        if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.CONVO) && bot != null) {
            soloMapling.qa.QaLog.map(soloMapling.qa.QaLog.Topic.CONVO, bot.getMapId(), "SAYDO SKIP " + bot.getName()
                    + " " + claims + " \"" + line + "\" (it would not do it)");
        }
    }

    // ── replies ──

    /** Claim kinds a reply may carry when the conversation layer decided {@code outcome}. */
    public static Set<ClaimRules.Kind> allowedFor(BotChatActions.Outcome outcome) {
        if (outcome == null) {
            return EnumSet.noneOf(ClaimRules.Kind.class);
        }
        return switch (outcome) {
            case COME_YES, HELP_YES -> EnumSet.of(ClaimRules.Kind.COME, ClaimRules.Kind.GO_TO, ClaimRules.Kind.TRAIN_AT,
                    ClaimRules.Kind.FOLLOW);
            case GUIDE_YES, FOLLOW_ACCEPTED -> EnumSet.of(ClaimRules.Kind.FOLLOW, ClaimRules.Kind.COME,
                    ClaimRules.Kind.GO_TO);
            case PARTY_ACCEPTED -> EnumSet.of(ClaimRules.Kind.PARTY_OFFER, ClaimRules.Kind.PARTY_ASK);
            case BUFF_YES -> EnumSet.of(ClaimRules.Kind.BUFF, ClaimRules.Kind.GIFT);
            case TRADE_SHOP, TRADE_OFFER -> EnumSet.of(ClaimRules.Kind.TRADE, ClaimRules.Kind.SELL, ClaimRules.Kind.BUY);
            default -> EnumSet.noneOf(ClaimRules.Kind.class);
        };
    }

    /** The claims a reply is checked for (the ones a reply can promise to the player it answers). */
    static final Set<ClaimRules.Kind> GUARDED = EnumSet.of(ClaimRules.Kind.COME, ClaimRules.Kind.FOLLOW,
            ClaimRules.Kind.PARTY_OFFER, ClaimRules.Kind.PARTY_ASK, ClaimRules.Kind.BUFF, ClaimRules.Kind.GIFT,
            ClaimRules.Kind.TRADE, ClaimRules.Kind.GO_TO, ClaimRules.Kind.RESUPPLY, ClaimRules.Kind.LEAVING,
            ClaimRules.Kind.AWAY);

    /** A "brb"/"afk" tacked on at either end of a line (the chat model's habit): cut off before anything else. */
    private static final java.util.regex.Pattern TACKED_AWAY = java.util.regex.Pattern.compile(
            "(?i)^\\s*(?:brb|afk|bbl|bbs)\\b[,.!]*\\s*|\\s*[,.]?\\s*\\b(?:brb|afk|bbl|bbs)[.!]*\\s*$");

    /**
     * A reply with its unbacked claims taken out: each clause (split at , . ! ? ; and "then") carrying a strong
     * guarded claim the decision does not allow is dropped. Returns the text unchanged when nothing needs dropping, and
     * null when nothing is left.
     */
    public static String guardReply(String text, Set<ClaimRules.Kind> allowed) {
        if (text == null || text.isBlank() || !hasUnbacked(text, allowed)) {
            return text;
        }
        if (!allowed.contains(ClaimRules.Kind.AWAY)) {
            String cut = TACKED_AWAY.matcher(text).replaceAll("").trim();
            if (!cut.equals(text) && !cut.isEmpty()) {
                text = cut;
                if (!hasUnbacked(text, allowed)) {
                    return text;
                }
            }
        }
        String[] parts = text.split("(?<=[,.!?;])\\s+|\\s+(?=then\\b)|\\s+(?=and (?:ill|i'?ll|im|i'm)\\b)");
        StringBuilder out = new StringBuilder();
        for (String p : parts) {
            if (hasUnbacked(p, allowed)) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(p.trim());
        }
        String s = out.toString().replaceAll("^(?i)(then|and)\\s+", "").replaceAll("[,;\\s]+$", "").trim();
        return s.length() < 2 || !s.matches(".*[A-Za-z].*") ? null : s;
    }

    static boolean hasUnbacked(String text, Set<ClaimRules.Kind> allowed) {
        for (ClaimRules.Claim c : ClaimRules.classify(text)) {
            if (!c.weak() && GUARDED.contains(c.kind()) && !allowed.contains(c.kind())) {
                return true;
            }
        }
        return false;
    }

    // ── S>/B> ──

    /** Whether the bot holds what its S> line sells (bag, trade window, shop) or wants what its B> line buys. */
    public static boolean holdsOrWants(Character bot, ClaimRules.Claim c) {
        return game.holdsOrWants(bot, c);
    }

    /** Words of an item name in an ad, matched loosely ("ilbi" against "Ilbi Throwing-Stars"). */
    static boolean nameMatches(String adItem, String itemName) {
        if (adItem == null || adItem.isBlank() || itemName == null) {
            return false;
        }
        String a = adItem.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9% ]", " ");
        String n = itemName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9% ]", " ");
        int hits = 0, words = 0;
        for (String w : a.split("\\s+")) {
            if (w.length() < 3 || w.matches("clean|cheap|ea|each|set|sets|pm|me|offer|for|the")) {
                continue;
            }
            words++;
            if (n.contains(w) || (w.endsWith("s") && n.contains(w.substring(0, w.length() - 1)))) {
                hits++;
            }
        }
        return words > 0 && hits * 2 >= words;
    }

    static final class GameSide implements Game {

        private static BotSM sm(Character bot) {
            return CharacterStorage.getBotById(bot.getId());
        }

        @Override
        public boolean canRestock(Character bot) {
            return sm(bot) instanceof soloMapling.ArtificialPlayer.BotTypes.TrainingBot tb && tb.isGrinding();
        }

        @Override
        public void restock(Character bot) {
            if (sm(bot) instanceof soloMapling.ArtificialPlayer.BotTypes.TrainingBot tb) {
                tb.requestRestockTrip();
            }
        }

        @Override
        public boolean canIdle(Character bot) {
            BotSM s = sm(bot);
            if (s == null) {
                return false;
            }
            if (s instanceof soloMapling.ArtificialPlayer.BotTypes.TrainingBot tb) {
                return tb.isIdleNow(); // mid-grind: no "brb" line; resting or in town: it is true as it is
            }
            return !soloMapling.ArtificialPlayer.BotCommitments.inFlight(s);
        }

        @Override
        public void idle(Character bot, long ms) {
            BotSM s = sm(bot);
            if (s == null) {
                return;
            }
            if (s instanceof soloMapling.ArtificialPlayer.BotTypes.TrainingBot) {
                SayDo.event(bot, SayDo.Ev.IDLE, bot.getMapId()); // already resting
                return;
            }
            s.waitFor(ms);
            soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.stop(bot);
            boolean sit = ThreadLocalRandom.current().nextBoolean();
            if (sit) {
                soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.botSitChair(bot,
                        soloMapling.ArtificialPlayer.BotCustomization.getRandomChairId());
                soloMapling.server.BotTiming.after(ms, () -> {
                    if (bot.getChair() > 0) {
                        soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.standUp(bot);
                    }
                });
            }
            SayDo.event(bot, SayDo.Ev.IDLE, bot.getMapId());
        }

        @Override
        public boolean canLogOut(Character bot) {
            BotSM s = sm(bot);
            return s != null && soloMapling.ArtificialPlayer.BotRoster.isMember(bot.getId() - BotStore.BOT_ID_BASE)
                    && soloMapling.ArtificialPlayer.BotPopulationDirector.mayLogOut(s)
                    && !soloMapling.ArtificialPlayer.BotLogout.isLeaving(bot.getId());
        }

        @Override
        public void logOutSoon(Character bot, long ms) {
            int id = bot.getId();
            soloMapling.server.BotTiming.after(ms, () -> soloMapling.ArtificialPlayer.BotLogout.request(id,
                    System.currentTimeMillis()));
        }

        @Override
        public boolean canTravel(Character bot, int mapId) {
            BotSM s = sm(bot);
            return s instanceof soloMapling.ArtificialPlayer.BotTypes.TownWandererBot
                    && !soloMapling.ArtificialPlayer.BotCommitments.inFlight(s)
                    && soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.walkable(bot, mapId);
        }

        @Override
        public void travel(Character bot, int mapId) {
            soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.travel(bot, mapId);
            SayDo.event(bot, SayDo.Ev.TRAVEL, mapId);
        }

        @Override
        public boolean partied(Character bot) {
            return bot.getParty() != null;
        }

        @Override
        public boolean holdsOrWants(Character bot, ClaimRules.Claim c) {
            BotSM s = sm(bot);
            try {
                if (c.kind() == ClaimRules.Kind.BUY) {
                    if (s != null) {
                        for (int i = 0; ; i++) {
                            var iq = s.getTradeWants().getItemQuantityAt(i);
                            if (iq == null) {
                                break;
                            }
                            if (nameMatches(c.item(), soloMapling.ArtificialPlayer.BotHelpers.convertItemIdToName(iq.getItemId()))) {
                                return true;
                            }
                        }
                    }
                    return false;
                }
                if (s != null && s.getTradeInventory() != null && s.getTradeInventory().getMainItemForSale() != null
                        && nameMatches(c.item(), soloMapling.ArtificialPlayer.BotHelpers.convertItemIdToName(
                        s.getTradeInventory().getMainItemForSale().getItemId()))) {
                    return true;
                }
                for (client.inventory.InventoryType t : client.inventory.InventoryType.values()) {
                    if (t == client.inventory.InventoryType.UNDEFINED || t == client.inventory.InventoryType.EQUIPPED) {
                        continue;
                    }
                    client.inventory.Inventory inv = bot.getInventory(t);
                    if (inv == null) {
                        continue;
                    }
                    for (client.inventory.Item it : inv.list()) {
                        if (nameMatches(c.item(), soloMapling.ArtificialPlayer.BotHelpers.convertItemIdToName(it.getItemId()))) {
                            return true;
                        }
                    }
                }
            } catch (RuntimeException | LinkageError e) {
                return false;
            }
            return false;
        }
    }
}
