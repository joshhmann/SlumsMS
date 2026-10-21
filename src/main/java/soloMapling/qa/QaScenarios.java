package soloMapling.qa;

import client.Character;
import client.command.commands.gm3.BotShowcaseCommand;

import java.awt.Point;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static soloMapling.qa.QaLog.Topic.ATTACK;
import static soloMapling.qa.QaLog.Topic.BOT_CHAT;
import static soloMapling.qa.QaLog.Topic.CHANNEL_LOAD;
import static soloMapling.qa.QaLog.Topic.EQUIP;
import static soloMapling.qa.QaLog.Topic.FM_BUY;
import static soloMapling.qa.QaLog.Topic.GUILD;
import static soloMapling.qa.QaLog.Topic.HIDE;
import static soloMapling.qa.QaLog.Topic.MERCHANT;
import static soloMapling.qa.QaLog.Topic.MOB_CONTROL;
import static soloMapling.qa.QaLog.Topic.ROPE;
import static soloMapling.qa.QaLog.Topic.SCROLL;
import static soloMapling.qa.QaLog.Topic.STORAGE;

/**
 * The {@code !qa} scenarios, one per test on the 1.4.1 release notes page that the server can stage. Ids are the
 * page's test ids; a second test that needs the same staging is an alias.
 */
public final class QaScenarios {

    /*
     * TODO(1.4.2): scenarios for branches not merged into rc1 yet. Give each the id its test gets on the 1.4.2 notes
     * page when that page is written, and add the QaLog hooks on the branch itself:
     *   - bot buddies (bot-buddies): spawn two bots, have one send the GM a buddy request; log BuddyList adds/accepts.
     *   - multi-channel follow (bots-population-142): a follower bot on the GM; log each channel change it follows.
     *   - 5-channel population (bots-population-142): sample per-channel bot counts like wd-1, plus the director's
     *     channel picks.
     */
    public static final List<String> PLANNED = List.of(
            "bot buddies", "multi-channel follow", "5-channel population");

    public static final int GUILD_HQ = 200000301;
    public static final int HERACLE = 2010007;
    public static final int FM_ENTRANCE = 910000000;
    public static final int MAGATIA = 261000000;
    public static final int HENESYS_HUNTING_GROUND = 104040000;
    public static final int ELLINIA = 101000000, PERION = 102000000, KERNING = 103000000;
    public static final int PLAYER_SHOP_PERMIT = 5140000;
    public static final int HIRED_MERCHANT = 5030000;
    public static final int PET_BROWN_KITTY = 5000000;
    public static final int NEW_PET_EQUIP = 1802085;
    public static final int ONE_HANDED_SWORD = 1302000;
    public static final int SWORD_ATT_SCROLL = 2043000;
    public static final long BUY_CAP = 5_000_000;

    /* Scrolls with a normal value in the bots' price list: fm-1 stocks the shop from these until it passes the cap. */
    static final int[] FM_STOCK = {2040301, 2040302, 2044701, 2044702, 2043301, 2043302, 2043001, 2043002, 2044001,
            2044002, 2040801, 2040802, 2040501, 2040502};

    private QaScenarios() {
    }

    public static List<QaScenario> all() {
        return List.of(new Bk1Showcase(), new Gd1Guild(), new Fm1BuyerCap(), new Ld1Ladders(), new Gh1Hide(),
                new Np1Rosen(), new Np2Fredrick(), new Np3Merchant(), new Cs2PetEquip(), new Gn2Scrolling(),
                new Wd1ChannelBars(), new Wd2DreamyPortal());
    }

    // ---- bk-1 ----

    /** bk-1 / bk-2: !botshowcase with its attacks logged. */
    static final class Bk1Showcase implements QaScenario {
        public String id() { return "bk-1"; }
        public List<String> aliases() { return List.of("bk-2"); }
        public String title() { return "4th-job bot showcase, every bot attack logged (skill, pose, weapon)"; }
        public String usage() { return "[4th | pirates | <job> ...] [minutes], default 4th 10"; }
        public Set<QaLog.Topic> topics() { return EnumSet.of(ATTACK); }
        public Set<Integer> watchedMaps(Character gm, String[] args) { return Set.of(gm.getMapId()); }
        public Set<String> claims(Character gm, String[] args) { return Set.of("map:" + gm.getMapId()); }

        public int minutes(String[] args) {
            BotShowcaseCommand.Request r = BotShowcaseCommand.parse(showcaseArgs(args));
            return r == null ? 15 : r.minutes() + 1;
        }

        static String[] showcaseArgs(String[] args) {
            return args == null || args.length == 0 ? new String[]{"4th", "10"} : args;
        }

        public List<String> stage(QaContext ctx) {
            String[] args = showcaseArgs(ctx.args());
            if (BotShowcaseCommand.parse(args) == null) {
                throw new QaRefusal("Unknown job in '" + String.join(" ", args) + "'. " + usage());
            }
            List<String> report = new ArrayList<>();
            List<Integer> bots = ctx.world().spawnShowcase(ctx.gm(), args, report);
            for (String r : report) {
                ctx.log(r);
            }
            if (bots.isEmpty()) {
                throw new QaRefusal("No showcase bot could be spawned here.");
            }
            ctx.despawnAtStop(bots, "showcase bots");
            List<String> out = new ArrayList<>(report);
            out.add("Stand by the mobs. With [debug] attackdiag=1 in config.ini, attackdiag.log should stay empty.");
            return out;
        }
    }

    // ---- gd-1 ----

    /** gd-1 / gd-2: the Guild Headquarters with bots standing round Heracle, mesos topped up for the fee. */
    static final class Gd1Guild implements QaScenario {
        static final int BOTS = 3;

        public String id() { return "gd-1"; }
        public List<String> aliases() { return List.of("gd-2"); }
        public String title() { return "Guild creation with bots around (Orbis Guild HQ, Heracle)"; }
        public Set<QaLog.Topic> topics() { return EnumSet.of(GUILD, BOT_CHAT); }
        public Set<Integer> watchedMaps(Character gm, String[] args) { return Set.of(GUILD_HQ); }
        public Set<String> claims(Character gm, String[] args) { return Set.of("map:" + GUILD_HQ); }

        public List<String> stage(QaContext ctx) {
            QaWorld w = ctx.world();
            Character gm = ctx.gm();
            List<String> out = new ArrayList<>();
            if (gm.getGuildId() > 0) {
                out.add("Note: you are in a guild already; leave it first or creation is refused.");
            }
            if (gm.getPartyId() > 0) {
                out.add("Note: you are in a party; leave it first (the test is for a GM without a party).");
            }
            if (!w.warp(gm, GUILD_HQ)) {
                throw new QaRefusal("Cannot warp to " + GUILD_HQ + ".");
            }
            ctx.log("warped to " + GUILD_HQ);
            int cost = w.guildCost();
            int have = w.meso(gm);
            if (have < cost) {
                int give = cost - have;
                w.gainMeso(gm, give);
                ctx.log("gave " + give + " mesos (had " + have + ", fee " + cost + ")");
                out.add("Gave you " + give + " mesos for the " + cost + " fee.");
                ctx.onStop("take back up to " + give + " mesos given for the fee", () -> {
                    Character now = ctx.gmNow();
                    if (now != null) {
                        int back = Math.min(give, Math.max(0, w.meso(now)));
                        w.gainMeso(now, -back);
                        ctx.run().note("CLEANUP", "took back " + back + " of " + give + " mesos");
                    }
                });
            } else {
                ctx.log("meso " + have + " covers the fee " + cost);
            }
            Point heracle = w.npcPosition(gm, HERACLE);
            Point at = heracle != null ? heracle : gm.getPosition();
            ctx.despawnAtStop(w.spawnIdleBots(gm, at, BOTS), "idle bots by Heracle");
            out.add("Talk to Heracle and create the guild: it should be created at once, no hang.");
            return out;
        }
    }

    // ---- fm-1 ----

    /** fm-1 / fm-2: a player shop worth more than the bot buy cap, FM shopper bots in the room. */
    static final class Fm1BuyerCap implements QaScenario {
        static final int SHOPPERS = 4;
        static final long STOCK_TARGET = BUY_CAP + 500_000;
        static final int OVERPRICE = 2;

        public String id() { return "fm-1"; }
        public List<String> aliases() { return List.of("fm-2"); }
        public String title() { return "Bot buyer cap: shop stock worth > 5m, FM bots browse it, buys and caps logged"; }
        public String usage() { return "[room 1-12], default 1"; }
        public Set<QaLog.Topic> topics() { return EnumSet.of(FM_BUY); }
        public Set<Integer> watchedMaps(Character gm, String[] args) { return Set.of(roomMap(args)); }
        public Set<String> claims(Character gm, String[] args) { return Set.of("map:" + roomMap(args)); }

        static int roomMap(String[] args) {
            int room = 1;
            if (args != null && args.length > 0 && args[0].matches("\\d{1,2}")) {
                room = Math.max(1, Math.min(12, Integer.parseInt(args[0])));
            }
            return FM_ENTRANCE + room;
        }

        public List<String> stage(QaContext ctx) {
            QaWorld w = ctx.world();
            Character gm = ctx.gm();
            int room = roomMap(ctx.args());
            if (!w.warp(gm, room)) {
                throw new QaRefusal("Cannot warp to FM room " + room + ".");
            }
            ctx.log("warped to " + room);
            if (w.countItem(gm, PLAYER_SHOP_PERMIT) < 1) {
                ctx.giveBack(PLAYER_SHOP_PERMIT, 1);
            }
            List<int[]> stock = new ArrayList<>();
            long total = 0;
            for (int id : FM_STOCK) {
                if (total >= STOCK_TARGET) {
                    break;
                }
                Integer value = w.marketValue(id);
                if (value == null || value <= 0) {
                    continue;
                }
                if (ctx.giveBack(id, 1) > 0) {
                    total += value;
                    stock.add(new int[]{id, value});
                }
            }
            if (stock.isEmpty()) {
                throw new QaRefusal("No priced stock could be given (inventory full, or no item values loaded).");
            }
            // fm-2: one more item at twice its normal value - bots must never buy it.
            for (int id : FM_STOCK) {
                Integer value = w.marketValue(id);
                boolean used = stock.stream().anyMatch(s -> s[0] == id);
                if (!used && value != null && value > 0 && ctx.giveBack(id, 1) > 0) {
                    stock.add(new int[]{id, value * OVERPRICE});
                    ctx.log("overpriced (fm-2): " + w.itemName(id) + " " + id + " at " + value * OVERPRICE
                            + ", normal " + value);
                    break;
                }
            }
            List<String> prices = new ArrayList<>();
            for (int[] s : stock) {
                prices.add(w.itemName(s[0]) + " " + s[0] + " @ " + s[1]);
            }
            ctx.log("stock at normal value " + total + ": " + prices);
            ctx.log("cap before: " + w.sellerCapStatus(gm));
            String failed = w.openPlayerShop(gm, "QA fm-1", stock);
            if (failed != null) {
                throw new QaRefusal("Could not open the test shop: " + failed);
            }
            // Registered after the items were given, so it runs before they are taken back (undos run last first).
            ctx.onStop("close the test shop (unsold items come back, then are taken back) and log the cap", () -> {
                Character now = ctx.gmNow();
                if (now != null) {
                    ctx.run().note("CLEANUP", "cap at stop: " + w.sellerCapStatus(now));
                    ctx.run().note("CLEANUP", w.closePlayerShop(now) ? "closed the player shop" : "no shop open");
                }
            });
            ctx.log("shop open with " + stock.size() + " items");
            ctx.despawnAtStop(w.spawnFmShoppers(gm, SHOPPERS), "FM shopper bots pinned to this room");
            List<String> out = new ArrayList<>();
            if (total < BUY_CAP) {
                out.add("Warning: only " + total + " of priced stock; the cap may not be reached.");
            }
            out.add("Your shop is open with " + stock.size() + " items worth " + total + " (the last one at "
                    + OVERPRICE + "x value, for fm-2). " + SHOPPERS + " FM bots browse this room.");
            out.add("Stay in the shop. Sales stop near 5,000,000 (!botyield); the overpriced item never sells.");
            return out;
        }
    }

    // ---- ld-1 ----

    /** ld-1: watch the busy ladder towns for bots hanging on a rope. */
    static final class Ld1Ladders implements QaScenario {
        static final Set<Integer> TOWNS = Set.of(ELLINIA, PERION, KERNING);

        public String id() { return "ld-1"; }
        public String title() { return "Ladder watch: rope stalls and the rope watchdog in Ellinia, Perion, Kerning"; }
        public String usage() { return "[101000000 | 102000000 | 103000000], default Ellinia"; }
        public Set<QaLog.Topic> topics() { return EnumSet.of(ROPE); }
        public Set<Integer> watchedMaps(Character gm, String[] args) { return TOWNS; }
        public int minutes(String[] args) { return 10; }

        public List<String> stage(QaContext ctx) {
            int map = ELLINIA;
            if (ctx.args().length > 0 && ctx.args()[0].matches("\\d{9}")) {
                map = Integer.parseInt(ctx.args()[0]);
                if (!TOWNS.contains(map)) {
                    throw new QaRefusal("ld-1 watches " + TOWNS + " only.");
                }
            }
            if (!ctx.world().warp(ctx.gm(), map)) {
                throw new QaRefusal("Cannot warp to " + map + ".");
            }
            ctx.log("warped to " + map + "; watching " + TOWNS);
            return List.of("Watch the ropes for about 10 min: no bot should hang at a rope top for more than ~4 s.",
                    "All three towns are logged wherever you stand; warp between them freely.");
        }
    }

    // ---- wd-2 ----

    /** wd-2: Dreamy Forest next to its north portal; where the GM lands is logged. */
    static final class Wd2DreamyPortal implements QaScenario {
        static final int DREAMY_FOREST = 271010400;
        static final String NORTH = "north00";

        public String id() { return "wd-2"; }
        public String title() { return "Dreamy Forest north portal: warp next to it, every map/position you land on logged"; }
        public Set<QaLog.Topic> topics() { return EnumSet.noneOf(QaLog.Topic.class); }
        public int minutes(String[] args) { return 5; }
        public int tickSeconds() { return 2; }

        public List<String> stage(QaContext ctx) {
            if (!ctx.world().warpToPortal(ctx.gm(), DREAMY_FOREST, NORTH)) {
                throw new QaRefusal("Cannot warp to " + DREAMY_FOREST + " portal " + NORTH + ".");
            }
            ctx.log("at " + ctx.world().whereIs(ctx.gm()));
            return List.of("You stand on the north portal: press up. You should arrive at the portal in Six Path "
                    + "Crossway Ruins (out00), not at the map's spawn point.");
        }

        private final java.util.Map<Integer, String> lastMap = new java.util.concurrent.ConcurrentHashMap<>();

        public void tick(QaContext ctx) {
            Character now = ctx.gmNow();
            if (now == null) {
                return;
            }
            String where = ctx.world().whereIs(now);
            String map = where.split(" at ")[0];
            if (!map.equals(lastMap.put(ctx.run().gmId(), map))) {
                ctx.run().note("ARRIVE", where);
            }
        }
    }

    // ---- gh-1 ----

    /** gh-1 / gh-2: Henesys Hunting Ground for the hidden-GM checks. */
    static final class Gh1Hide implements QaScenario {
        public String id() { return "gh-1"; }
        public List<String> aliases() { return List.of("gh-2"); }
        public String title() { return "GM hide: bot reactions to a hidden GM and mob controllers logged"; }
        public Set<QaLog.Topic> topics() { return EnumSet.of(HIDE, BOT_CHAT, MOB_CONTROL); }
        public Set<Integer> watchedMaps(Character gm, String[] args) { return Set.of(HENESYS_HUNTING_GROUND); }
        public Set<String> claims(Character gm, String[] args) { return Set.of("map:" + HENESYS_HUNTING_GROUND); }

        public List<String> stage(QaContext ctx) {
            if (!ctx.world().warp(ctx.gm(), HENESYS_HUNTING_GROUND)) {
                throw new QaRefusal("Cannot warp to " + HENESYS_HUNTING_GROUND + ".");
            }
            ctx.log("warped to " + HENESYS_HUNTING_GROUND + ", hidden=" + ctx.gm().isHidden());
            return List.of("!hide, walk up to bots, say hi and a bot's name, level up: no greetings or replies.",
                    "Alone and hidden, the monsters should keep wandering. Then !unhide: bots react again.");
        }
    }

    // ---- np-1, np-2, np-3 ----

    /** np-1: Rosen's storage in Magatia, with an item to store. */
    static final class Np1Rosen implements QaScenario {
        static final int ITEM = 4000000; // Blue Snail Shell

        public String id() { return "np-1"; }
        public String title() { return "Rosen opens storage (Magatia), storage steps logged"; }
        public Set<QaLog.Topic> topics() { return EnumSet.of(STORAGE); }

        public List<String> stage(QaContext ctx) {
            if (!ctx.world().warp(ctx.gm(), MAGATIA)) {
                throw new QaRefusal("Cannot warp to " + MAGATIA + ".");
            }
            ctx.log("warped to " + MAGATIA);
            ctx.giveBack(ITEM, 1);
            return List.of("Click Rosen: storage opens. Store the Blue Snail Shell, close, take it back.");
        }
    }

    /** np-2: Fredrick at the FM entrance with nothing stored. */
    static final class Np2Fredrick implements QaScenario {
        public String id() { return "np-2"; }
        public String title() { return "Fredrick with nothing stored (FM entrance)"; }
        public Set<QaLog.Topic> topics() { return EnumSet.of(MERCHANT); }

        public List<String> stage(QaContext ctx) {
            if (!ctx.world().warp(ctx.gm(), FM_ENTRANCE)) {
                throw new QaRefusal("Cannot warp to " + FM_ENTRANCE + ".");
            }
            ctx.log("warped to " + FM_ENTRANCE);
            return List.of("Click Fredrick: \"You don't have any items or mesos to be retrieved.\"");
        }
    }

    /** np-3: a hired merchant, closed on request the way a restart closes it, then Fredrick. */
    static final class Np3Merchant implements QaScenario {
        public String id() { return "np-3"; }
        public String title() { return "Fredrick returns a closed merchant; !qa do close stands in for the restart"; }
        public String usage() { return "[room 1-12]; then !qa do close"; }
        public Set<QaLog.Topic> topics() { return EnumSet.of(MERCHANT); }

        public List<String> stage(QaContext ctx) {
            int room = Fm1BuyerCap.roomMap(ctx.args());
            if (!ctx.world().warp(ctx.gm(), room)) {
                throw new QaRefusal("Cannot warp to " + room + ".");
            }
            ctx.log("warped to " + room);
            if (ctx.world().countItem(ctx.gm(), HIRED_MERCHANT) < 1) {
                ctx.giveBack(HIRED_MERCHANT, 1);
            }
            return List.of("Open the hired merchant here with one item; Fredrick then says \"You have a Merchant open.\"",
                    "Then !qa do close: the merchant closes exactly as a server restart closes it.",
                    "Click Fredrick at the FM entrance: take the item and mesos; a second click finds nothing.");
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if (!"close".equals(name)) {
                throw new QaRefusal("np-3 steps: close");
            }
            String result = ctx.world().closeOwnMerchant(ctx.gm());
            ctx.run().note("STEP", result);
            return List.of(result);
        }
    }

    // ---- cs-2 ----

    /** cs-2: a pet and a newer pet equip. */
    static final class Cs2PetEquip implements QaScenario {
        public String id() { return "cs-2"; }
        public String title() { return "Newer pet equip: pet 5000000 and equip 1802085, equips logged"; }
        public Set<QaLog.Topic> topics() { return EnumSet.of(EQUIP); }

        public List<String> stage(QaContext ctx) {
            QaWorld w = ctx.world();
            if (!w.givePet(ctx.gm(), PET_BROWN_KITTY, 7)) {
                throw new QaRefusal("Could not give the pet (cash inventory full?).");
            }
            ctx.log("gave pet " + PET_BROWN_KITTY + " (7 days)");
            ctx.onStop("take back the test pet unless it is out", () -> {
                Character now = ctx.gmNow();
                if (now == null) {
                    return;
                }
                if (w.petSummoned(now, PET_BROWN_KITTY)) {
                    ctx.run().note("CLEANUP", "pet " + PET_BROWN_KITTY + " is summoned: left with you");
                } else {
                    ctx.run().note("CLEANUP", "took back " + w.takeItem(now, PET_BROWN_KITTY, 1) + " pet");
                }
            });
            ctx.giveBack(NEW_PET_EQUIP, 1);
            return List.of("Hover the pet equip in your inventory, summon the pet, put the equip on it. No crash.",
                    "(An equip left on the pet stays with you at !qa stop.)");
        }
    }

    // ---- gn-2 (sc-1) ----

    /** gn-2 (also sc-1): a one-handed sword and its ATT scrolls. */
    static final class Gn2Scrolling implements QaScenario {
        public String id() { return "gn-2"; }
        public List<String> aliases() { return List.of("sc-1"); }
        public String title() { return "Scrolling: a one-handed sword and ATT scrolls, scroll results logged"; }
        public String usage() { return "[scrolls 1-10], default 3"; }
        public Set<QaLog.Topic> topics() { return EnumSet.of(SCROLL); }

        public List<String> stage(QaContext ctx) {
            int n = 3;
            if (ctx.args().length > 0 && ctx.args()[0].matches("\\d{1,2}")) {
                n = Math.max(1, Math.min(10, Integer.parseInt(ctx.args()[0])));
            }
            ctx.giveBack(ONE_HANDED_SWORD, 1);
            ctx.giveBack(SWORD_ATT_SCROLL, n);
            return List.of("Use a " + SWORD_ATT_SCROLL + " on the sword: ATT goes up and one slot is used.");
        }
    }

    // ---- wd-1 ----

    /** wd-1: per-channel players, bots and load bars, sampled every minute and on every channel list sent. */
    static final class Wd1ChannelBars implements QaScenario {
        public String id() { return "wd-1"; }
        public String title() { return "Channel bars: per-channel players, bots and bar values logged every minute"; }
        public Set<QaLog.Topic> topics() { return EnumSet.of(CHANNEL_LOAD); }
        public int tickSeconds() { return 60; }

        public List<String> stage(QaContext ctx) {
            sample(ctx);
            return List.of("Change channel or log out to the channel list and look at the bars.",
                    "Every channel list the server sends is logged with its values.");
        }

        public void tick(QaContext ctx) {
            sample(ctx);
        }

        private static void sample(QaContext ctx) {
            for (String line : ctx.world().channelLoads()) {
                ctx.run().note("SAMPLE", line);
            }
        }
    }
}
