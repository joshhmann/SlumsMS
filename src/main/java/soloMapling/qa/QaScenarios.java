package soloMapling.qa;

import client.Character;
import client.command.commands.gm3.BotShowcaseCommand;

import java.awt.Point;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static soloMapling.qa.QaLog.Topic.AFFINITY;
import static soloMapling.qa.QaLog.Topic.ATTACK;
import static soloMapling.qa.QaLog.Topic.BOUNCE;
import static soloMapling.qa.QaLog.Topic.BOT_CHAT;
import static soloMapling.qa.QaLog.Topic.CHANNEL_LOAD;
import static soloMapling.qa.QaLog.Topic.COUPLE;
import static soloMapling.qa.QaLog.Topic.EQUIP;
import static soloMapling.qa.QaLog.Topic.FM_BUY;
import static soloMapling.qa.QaLog.Topic.GACHA;
import static soloMapling.qa.QaLog.Topic.GUILD;
import static soloMapling.qa.QaLog.Topic.HIDE;
import static soloMapling.qa.QaLog.Topic.IDLE;
import static soloMapling.qa.QaLog.Topic.MAP_ENTRY;
import static soloMapling.qa.QaLog.Topic.MERCHANT;
import static soloMapling.qa.QaLog.Topic.MOB_CONTROL;
import static soloMapling.qa.QaLog.Topic.ROPE;
import static soloMapling.qa.QaLog.Topic.SCROLL;
import static soloMapling.qa.QaLog.Topic.SLIDE;
import static soloMapling.qa.QaLog.Topic.STORAGE;
import static soloMapling.qa.QaLog.Topic.WEALTH;

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
    public static final int SWORD_ATT_60 = 2043001, SWORD_ATT_10 = 2043002, SWORD_ATT_DARK_70 = 2043004;
    public static final int WHITE_SCROLL = 2340000;
    public static final long BUY_CAP = 5_000_000;
    public static final int GACHA_TICKET = 5220000;

    /* Scrolls with a normal value in the bots' price list: fm-1 stocks the shop from these until it passes the cap. */
    static final int[] FM_STOCK = {2040301, 2040302, 2044701, 2044702, 2043301, 2043302, 2043001, 2043002, 2044001,
            2044002, 2040801, 2040802, 2040501, 2040502};

    private QaScenarios() {
    }

    public static List<QaScenario> all() {
        List<QaScenario> all = new ArrayList<>(core());
        all.addAll(CosmeticScenarios.all()); // 1.5.1 salon data/name lookup
        all.addAll(CouponScenarios.all());   // 1.5.1 coupon hours readable: co-1
        all.addAll(GodlyDropScenarios.all()); // 1.5.2 godly drops: gl-1
        all.addAll(GuildScenarios.all());     // 1.5.0 bot guilds: gu-1 .. gu-8
        all.addAll(CombatScenarios.all());    // 1.5.0 rc3: bots fight like people (cb-1 .. cb-4), spread out (sp-1)
        all.addAll(QaTradeScenarios.all());   // 1.5.0 rc3 economy lane: tr-1..tr-7
        all.addAll(PartnerRingScenarios.all()); // 1.5.0: Cash Shop friendship/couple rings (pr-1, pr-2)
        all.addAll(DramaScenarios.all());     // 1.5.0 bot drama: rm-1..rm-4, rv-1..rv-4, gw-1..gw-4
        all.addAll(ConvoScenarios.all());     // ticket 59: natural conversations cv-1..cv-7
        all.addAll(PersonalSpaceScenarios.all()); // rc6: town bots never stand pixel to pixel (ps-1)
        all.addAll(V83Scenarios.all()); // ticket 69: ordinary v83 features a real player uses (vb-1..vb-7)
        all.addAll(PqScenarios.all());            // ticket 75: Amoria PQ for two players (pq-1)
        return List.copyOf(all);
    }

    private static List<QaScenario> core() {
        return List.of(new Bk1Showcase(), new Gd1Guild(), new Fm1BuyerCap(), new Ld1Ladders(), new Gh1Hide(),
                new Np1Rosen(), new Np2Fredrick(), new Np3Merchant(), new Cs2PetEquip(), new Gn2Scrolling(), new Sc2BotScrolling(),
                new Wd1ChannelBars(), new Wd2DreamyPortal(), new Id1IdleSafety(),
                new Mv1AttackSlide(), new Pe1MapEntry(), new Jq1BounceBlock(), new Ga1Gachapon(), new Ga2BotWealth(),
                new Af1Friend(), new Af2Rival(), new Af3DramaOff(), new Cp1Couple(), new We1Wedding());
    }

    // ---- ga-1 ----

    /**
     * ga-1 (1.5.0): the gachapon. Gives the GM 3 Gachapon Tickets and warps it to the chosen town's machine (default
     * Henesys, in the Henesys Market); every roll by anyone (players and bots) goes to the log with its tier, and the
     * odds and a 10,000-roll simulation are logged at the start. {@code !qa do roll [tier]} is a free roll now (a
     * forced {@code rare} or {@code jackpot} shows the server-wide notice), {@code !qa do sim [n]} simulates again.
     */
    static final class Ga1Gachapon implements QaScenario {
        private volatile String town = "henesys";

        public String id() { return "ga-1"; }
        public String title() { return "Gachapon: 3 tickets at a town's machine, rolls, notices and odds logged"; }
        public String usage() { return "[town], default henesys (ellinia, perion, kerning, ludibrium, el_nath, ...)"; }
        public Set<QaLog.Topic> topics() { return EnumSet.of(GACHA); }
        public int minutes(String[] args) { return 20; }

        public List<String> stage(QaContext ctx) {
            String town = ctx.arg(0, "henesys");
            this.town = town;
            QaWorld w = ctx.world();
            List<String> odds = w.gachaOdds(town, 10_000);
            if (odds.size() == 1 && odds.get(0).startsWith("no gachapon town")) {
                throw new QaRefusal(odds.get(0) + ".");
            }
            odds.forEach(ctx::log);
            int map = w.gachaMap(town);
            if (map > 0 && map != ctx.gm().getMapId() && !w.warp(ctx.gm(), map)) {
                ctx.log("could not warp to " + map);
            }
            ctx.giveBack(GACHA_TICKET, 3);
            return List.of("Talk to the Gachapon machine and use your 3 tickets: each prize is logged with its tier.",
                    "The machine's 'What can I win' answer shows the odds; @gacha " + town + " lists every prize.",
                    "!qa do roll rare (or jackpot) gives a free prize of that tier now: the yellow server notice must show"
                            + " your name, the item and the town. A second one within 60 s is not announced.",
                    "!qa do sim 100000 simulates again. A full inventory tab must refuse the roll and keep the ticket.");
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if ("roll".equalsIgnoreCase(name)) {
                String line = ctx.world().gachaRoll(ctx.gmNow() == null ? ctx.gm() : ctx.gmNow(), town,
                        args.length > 0 ? args[0] : null);
                ctx.run().note("ROLL", line);
                return List.of(line);
            }
            if ("sim".equalsIgnoreCase(name)) {
                int n = args.length > 0 && args[0].matches("\\d{1,7}") ? Integer.parseInt(args[0]) : 10_000;
                List<String> lines = ctx.world().gachaOdds(town, n);
                lines.forEach(l -> ctx.run().note("SIM", l));
                return lines;
            }
            throw new QaRefusal("ga-1 steps: roll [common|uncommon|rare|jackpot], sim [n].");
        }
    }

    // ---- ga-2 ----

    /**
     * ga-2 (1.5.0, ticket 56B): a bot over its wealth ceiling spends down. Warps the GM to the town's machine
     * (default Henesys Market), spawns a bot next to it and gives it mesos up to {@code x} times its ceiling (default
     * 2.5). Every ~8 s the bot takes one spending step: a gachapon ticket bought with its own mesos (a rare may be
     * announced, subject to the bot notice limits), then 10% upkeep on what is still over. Each step and the bot's
     * wealth line are logged; the run ends when it is back under its ceiling. {@code !qa do step} steps now.
     */
    static final class Ga2BotWealth implements QaScenario {
        private volatile int botId;
        private volatile boolean done;

        public String id() { return "ga-2"; }
        public String title() { return "Bot wealth ceiling: a rich bot plays the gachapon and pays upkeep until under its ceiling"; }
        public String usage() { return "[town] [times its ceiling], default henesys 3"; }
        /**
         * rc6 (QA t-wc-1): a random bot (lv 13, ceiling 1M) at 2.5x was under its ceiling after two tickets, 17 s in. A
         * lv 100 bot at 3x its ceiling takes about a dozen ~8 s steps (tickets and upkeep): a couple of minutes.
         */
        static final int RICH_LEVEL = 100;
        public Set<QaLog.Topic> topics() { return EnumSet.of(GACHA, WEALTH); }
        public int tickSeconds() { return 8; }
        public int minutes(String[] args) { return 10; }

        public List<String> stage(QaContext ctx) {
            QaWorld w = ctx.world();
            String town = ctx.arg(0, "henesys");
            double times = 3;
            if (ctx.args().length > 1 && ctx.args()[1].matches("\\d+(\\.\\d+)?")) {
                times = Math.max(1.1, Math.min(10, Double.parseDouble(ctx.args()[1])));
            }
            int map = w.gachaMap(town);
            if (map <= 0) {
                throw new QaRefusal("No gachapon town '" + town + "' with a machine map.");
            }
            if (map != ctx.gm().getMapId() && !w.warp(ctx.gm(), map)) {
                throw new QaRefusal("Cannot warp to " + map + ".");
            }
            done = false;
            Character gm = ctx.gmNow() == null ? ctx.gm() : ctx.gmNow();
            List<Integer> bots = w.spawnIdleBots(gm, gm.getPosition(), 1, RICH_LEVEL);
            if (bots.isEmpty()) {
                throw new QaRefusal("Could not spawn a bot here.");
            }
            botId = bots.get(0);
            ctx.despawnAtStop(bots, "rich bot (its mesos and prizes leave with it)");
            long ceiling = w.botCeiling(botId);
            long target = (long) (ceiling * times);
            int give = (int) Math.min(Integer.MAX_VALUE - 1L, Math.max(0, target));
            ctx.log("bot " + botId + " given " + w.giveBotMeso(botId, give) + " mesos (" + times + "x its ceiling "
                    + ceiling + ")");
            ctx.log(w.botWealthLine(botId));
            return List.of(who(ctx, botId) + " holds " + times + "x its wealth ceiling.",
                    "Every ~8 s it buys a gachapon ticket with its own mesos (watch for a yellow notice on a rare; bots"
                            + " are announced at most once per 15 min) and pays 10% upkeep on what is still over.",
                    "Each step and its wealth line are logged; the run ends when it is under its ceiling. !qa do step"
                            + " steps now. !botwealth " + botId + " shows it any time.");
        }

        public void tick(QaContext ctx) {
            if (!done && botId != 0) {
                step(ctx);
            }
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if (!"step".equalsIgnoreCase(name)) {
                throw new QaRefusal("ga-2 has one step: step.");
            }
            return step(ctx);
        }

        private List<String> step(QaContext ctx) {
            QaWorld w = ctx.world();
            List<String> lines = new ArrayList<>(w.botWealthStep(botId));
            String wealth = w.botWealthLine(botId);
            lines.add(wealth);
            for (String l : lines) {
                ctx.run().note("STEP", l);
            }
            if (wealth.contains("is gone") || settled(wealth)) {
                done = true;
                ctx.run().note("DONE", wealth.contains("is gone") ? wealth : "settled at its ceiling: " + wealth);
            }
            return lines;
        }

        private static final java.util.regex.Pattern OVER =
                java.util.regex.Pattern.compile("ceiling ([\\d,]+).*over by ([\\d,]+)");

        /**
         * Under its ceiling, or within 1% over it. rc6 triage (t-wc-1): upkeep is 10% of the excess, so once the daily
         * ticket cap stops the gachapon the excess only shrinks by a tenth a step and never reaches zero ("over by
         * 1,078" of a 12M ceiling after 8 minutes) - the run never ended. The owner then stopped upkeep within 1%
         * ({@link soloMapling.ArtificialPlayer.BotWealth#upkeepDue}); this asks the same rule, so DONE means "upkeep
         * has stopped".
         */
        static boolean settled(String wealth) {
            java.util.regex.Matcher m = OVER.matcher(wealth);
            if (!m.find()) {
                return true;
            }
            long ceiling = Long.parseLong(m.group(1).replace(",", ""));
            long over = Long.parseLong(m.group(2).replace(",", ""));
            return !soloMapling.ArtificialPlayer.BotWealth.upkeepDue(over, ceiling);
        }
    }

    // ---- jq-1 ----

    /**
     * jq-1: the bounce block on a jump-quest map. Warps the GM into a blocked map (default Forest of Patience step 1)
     * with the GM backstop bypass off; the log gets every map the GM lands on with its block status, and every bounce
     * or Flash Jump the server receives (a REFUSED line means the client did not block it itself).
     */
    static final class Jq1BounceBlock implements QaScenario {
        static final int FOREST_OF_PATIENCE_1 = 101000100;

        public String id() { return "jq-1"; }
        public String title() { return "Bounce block: warp into a jump-quest map, bounce/Flash Jump uses and map changes logged"; }
        public String usage() { return "[blocked map id], default 101000100 (Forest of Patience step 1)"; }
        public Set<QaLog.Topic> topics() { return EnumSet.of(BOUNCE); }
        public int minutes(String[] args) { return 15; }
        public int tickSeconds() { return 2; }

        public List<String> stage(QaContext ctx) {
            int map = FOREST_OF_PATIENCE_1;
            if (ctx.args().length > 0) {
                if (!ctx.args()[0].matches("\\d{9}")) {
                    throw new QaRefusal("jq-1 takes a 9-digit map id.");
                }
                map = Integer.parseInt(ctx.args()[0]);
            }
            server.bounce.BounceZones zones = server.bounce.BounceZones.getInstance();
            String category = zones.blockedBy(map).map(server.bounce.BounceZones.Category::name).orElse(null);
            if (category == null) {
                throw new QaRefusal(map + " is not blocked by any enabled category (!bounce here / !bounce list).");
            }
            server.bounce.BounceZoneService.clearBypass(ctx.gm().getId());
            if (!ctx.world().warp(ctx.gm(), map)) {
                throw new QaRefusal("Cannot warp to " + map + ".");
            }
            ctx.log("warped to " + map + " (blocked by " + category + "); " + zones.bounceRanges().size()
                    + " bounce ranges and " + zones.flashJumpRanges().size() + " Flash Jump ranges blocked; GM bypass off");
            return List.of("Hotkey Ninja Bounce (Jump Around!) and Vertical Bounce (Jump Upwards!) and press them, then "
                            + "jump and press jump again in the air (double-tap): nothing may move you.",
                    "A thief also tries Flash Jump: blocked on jump_quests maps (stock fieldLimit forbids it there).",
                    "Then warp to a normal map (e.g. !warp 100000000): bounces and double-tap work again.",
                    "The log must show no REFUSED line: one means the client sent a blocked bounce. !qa do push re-sends the list.");
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if (!name.equals("push")) {
                throw new QaRefusal("jq-1 steps: push");
            }
            Character gm = ctx.gmNow();
            if (gm == null) {
                throw new QaRefusal("You are offline.");
            }
            server.bounce.BounceZoneService.onLogin(gm);
            ctx.run().note("PUSH", "zone list re-sent to " + gm.getName());
            return List.of("Zone list re-sent to your client.");
        }

        private final java.util.Map<Integer, Integer> lastMap = new java.util.concurrent.ConcurrentHashMap<>();

        public void tick(QaContext ctx) {
            Character now = ctx.gmNow();
            if (now == null) {
                return;
            }
            int map = now.getMapId();
            Integer before = lastMap.put(ctx.run().gmId(), map);
            if (before == null || before != map) {
                server.bounce.BounceZones zones = server.bounce.BounceZones.getInstance();
                ctx.run().note("ARRIVE", map + ": bounces " + (zones.blocksBounce(map)
                        ? "BLOCKED (" + zones.blockedBy(map).map(server.bounce.BounceZones.Category::name).orElse("?") + ")"
                        : "allowed") + ", Flash Jump " + (zones.blocksFlashJump(map) ? "blocked" : "allowed"));
            }
        }
    }

    // ---- bk-1 ----

    /** bk-1 / bk-2: !botshowcase with its attacks logged. */
    static final class Bk1Showcase implements QaScenario {
        public String id() { return "bk-1"; }
        public List<String> aliases() { return List.of("bk-2"); }
        public String title() { return "4th-job bot showcase, every bot attack logged (skill, pose, weapon)"; }
        public String usage() { return "[4th | pirates | pacing | <job> ...] [minutes], default 4th 10"; }
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
            out.add("Stay in the shop (closing its window closes it). Within ~30 s the bots walk over and take turns"
                    + " sitting in it, one at a time; most visits buy one item. Sales stop near 5,000,000"
                    + " (!botyield); the overpriced item never sells.");
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

    // ---- sc-2 ----

    /**
     * sc-2: a bot next to the GM scrolls its own one-handed sword for real (ticket 11): 60%, 10% and one dark 70%
     * scroll and a white scroll in its bag; one roll every few seconds by its appetite until the slots or scrolls
     * run out. Every roll is logged (scroll, odds, white scroll, result, stats before and after); the GM sees the
     * scroll effect over the bot. {@code !qa do roll} rolls once more now.
     */
    static final class Sc2BotScrolling implements QaScenario {
        static final int[][] KIT = {{SWORD_ATT_60, 4}, {SWORD_ATT_10, 2}, {SWORD_ATT_DARK_70, 1}, {WHITE_SCROLL, 1}};

        private volatile int botId;
        private volatile String appetite;
        private volatile boolean done;

        public String id() { return "sc-2"; }
        public String title() { return "Bot scrolling: a bot scrolls its own sword live, every roll and its stats logged"; }
        public String usage() { return "[careful|normal|gambler] [equip id], default its own appetite, sword 1302000"; }
        public Set<QaLog.Topic> topics() { return EnumSet.of(SCROLL); }
        public int tickSeconds() { return 6; }
        public int minutes(String[] args) { return 5; }

        public Set<Integer> watchedMaps(Character gm, String[] args) {
            return gm == null ? Set.of() : Set.of(gm.getMapId());
        }

        public List<String> stage(QaContext ctx) {
            QaWorld w = ctx.world();
            Character gm = ctx.gm();
            appetite = null;
            int equip = ONE_HANDED_SWORD;
            for (String a : ctx.args()) {
                if (a.matches("\\d{7}")) {
                    equip = Integer.parseInt(a);
                } else if (a.matches("(?i)careful|normal|gambler")) {
                    appetite = a.toLowerCase(java.util.Locale.ROOT);
                }
            }
            done = false;
            List<Integer> bots = w.spawnIdleBots(gm, gm.getPosition(), 1);
            if (bots.isEmpty()) {
                throw new QaRefusal("Could not spawn a bot here.");
            }
            botId = bots.get(0);
            ctx.despawnAtStop(bots, "scrolling bot (its gear and scrolls leave with it)");
            if (w.giveBotItem(botId, equip, 1) < 1) {
                throw new QaRefusal("The bot could not take equip " + equip + ".");
            }
            ctx.log("bot " + botId + " holds " + w.itemName(equip) + " (" + equip + ")");
            for (int[] k : KIT) {
                ctx.log("bot " + botId + " holds " + w.giveBotItem(botId, k[0], k[1]) + " x " + w.itemName(k[0])
                        + " (" + k[0] + ")");
            }
            ctx.log("appetite: " + (appetite == null ? "the bot's own" : appetite));
            return List.of(who(ctx, botId) + " holds a sword, 4x 60%, 2x 10%, 1x dark 70% and a white scroll.",
                    "Every ~6 s it scrolls the sword with the server's real roll: watch the scroll effect over it.",
                    "Each roll is logged: scroll, odds, white scroll, result, stats before and after. A boom destroys"
                            + " the sword. !qa do roll rolls once now; !qa log shows the rolls.");
        }

        public void tick(QaContext ctx) {
            if (done || botId == 0) {
                return;
            }
            step(ctx);
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if (!"roll".equalsIgnoreCase(name)) {
                throw new QaRefusal("sc-2 has one step: roll.");
            }
            return step(ctx);
        }

        private List<String> step(QaContext ctx) {
            List<String> lines = ctx.world().botScrollStep(botId, appetite);
            for (String line : lines) {
                if (line.contains("has nothing it would scroll") || line.contains("is gone")) {
                    done = true;
                    ctx.run().note("DONE", line);
                } else {
                    ctx.run().note("ROLL", line);
                }
            }
            return lines;
        }
    }

    // ---- wd-1 ----

    /** wd-1: per-channel players, bots and load bars, sampled every minute and on every channel list sent. */
    // ---- pe-1 ----

    /**
     * pe-1: map entry timing (1.4.2 perf). Warps the GM back and forth between two busy maps every few seconds; each
     * entry is measured by {@link MapEntryMeter} and logged as a MAP_ENTRY line, and the stop writes a summary.
     */
    static final class Pe1MapEntry implements QaScenario {
        static final int HENESYS = 100000000, HENESYS_MARKET = 100000100;
        static final int DEFAULT_SECONDS = 8;

        /** Per GM: the two maps, the seconds between warps, the seconds counted so far and the warps done. */
        static final class State {
            final int a, b, every;
            int elapsed, warps;

            State(int a, int b, int every) {
                this.a = a;
                this.b = b;
                this.every = every;
            }
        }

        final java.util.Map<Integer, State> states = new java.util.concurrent.ConcurrentHashMap<>();

        public String id() { return "pe-1"; }
        public String title() { return "Map entry timing: warps you between two busy maps, logs time and packets per entry"; }
        public String usage() { return "[mapA] [mapB] [seconds between warps], default 100000000 100000100 8"; }
        public Set<QaLog.Topic> topics() { return EnumSet.of(MAP_ENTRY); }
        public int minutes(String[] args) { return 10; }
        public int tickSeconds() { return 1; }

        static int[] parse(String[] args) {
            try {
                int a = args.length > 0 ? Integer.parseInt(args[0]) : HENESYS;
                int b = args.length > 1 ? Integer.parseInt(args[1]) : HENESYS_MARKET;
                int every = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_SECONDS;
                if (a == b || every < 3 || every > 120) {
                    return null;
                }
                return new int[]{a, b, every};
            } catch (NumberFormatException e) {
                return null;
            }
        }

        public List<String> stage(QaContext ctx) {
            int[] p = parse(ctx.args());
            if (p == null) {
                throw new QaRefusal("Two different map ids and 3-120 seconds, e.g. !qa run pe-1 " + usage());
            }
            int gmId = ctx.run().gmId();
            MapEntryMeter.clear(gmId);
            if (!ctx.world().warp(ctx.gm(), p[0])) {
                throw new QaRefusal("Cannot warp to " + p[0] + ".");
            }
            states.put(gmId, new State(p[0], p[1], p[2]));
            ctx.log("warping between " + p[0] + " and " + p[1] + " every " + p[2] + " s");
            ctx.onStop("stop warping, write the summary", () -> {
                State st = states.remove(gmId);
                ctx.run().note("SUMMARY", "map entries: " + MapEntryMeter.summary(MapEntryMeter.results(gmId))
                        + (st != null ? ", warps " + st.warps : ""));
                MapEntryMeter.clear(gmId);
            });
            return List.of("You will be warped between " + p[0] + " and " + p[1] + " every " + p[2] + " s.",
                    "Each entry is logged (time, packets, deferred spawns). !qa stop writes the summary.");
        }

        public void tick(QaContext ctx) {
            State st = states.get(ctx.run().gmId());
            Character gm = ctx.gmNow();
            if (st == null || gm == null) {
                return;
            }
            if (++st.elapsed < st.every) {
                return;
            }
            st.elapsed = 0;
            int to = gm.getMapId() == st.a ? st.b : st.a;
            if (ctx.world().warp(gm, to)) {
                st.warps++;
            } else {
                ctx.run().note("STEP", "could not warp to " + to);
            }
        }
    }

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

    // ---- id-1 ----

    /**
     * id-1 (1.4.2, bots lane): idle bots dropped into the thickest mob pack of a busy map. Logs every contact hit a bot
     * takes while idle and every move to a safe spot; every 15 s a per-bot tally. {@code off} runs the same staging
     * with idle safety switched off, for the before/after comparison.
     */
    static final class Id1IdleSafety implements QaScenario {
        static final int DEFAULT_BOTS = 4;
        static final int MAX_BOTS = 6;

        public String id() { return "id-1"; }
        public String title() { return "Idle bots in a mob pack: hits taken while idle and moves to a safe spot"; }
        public String usage() { return "[off] [bots 1-6, default 4] [map id, default your map if it has mobs, else 104040000]"; }
        public Set<QaLog.Topic> topics() { return EnumSet.of(IDLE); }
        public int tickSeconds() { return 15; }

        public Set<Integer> watchedMaps(Character gm, String[] args) {
            Integer map = mapArg(args);
            // No map given: the GM's own map, or the Hunting Ground staging falls back to when it has no mobs.
            return map != null ? Set.of(map) : new java.util.HashSet<>(List.of(gm.getMapId(), HENESYS_HUNTING_GROUND));
        }

        static boolean off(String[] args) {
            for (String a : args) {
                if ("off".equalsIgnoreCase(a)) {
                    return true;
                }
            }
            return false;
        }

        static int bots(String[] args) {
            for (String a : args) {
                if (a.matches("\\d{1,2}")) {
                    return Math.max(1, Math.min(MAX_BOTS, Integer.parseInt(a)));
                }
            }
            return DEFAULT_BOTS;
        }

        static Integer mapArg(String[] args) {
            for (String a : args) {
                if (a.matches("\\d{9}")) {
                    return Integer.parseInt(a);
                }
            }
            return null;
        }

        private final java.util.Map<Integer, List<Integer>> botsByGm = new java.util.concurrent.ConcurrentHashMap<>();

        public List<String> stage(QaContext ctx) {
            QaWorld w = ctx.world();
            Character gm = ctx.gm();
            String[] args = ctx.args();
            Integer map = mapArg(args);
            if (map == null && w.liveMobCount(gm) == 0) {
                map = HENESYS_HUNTING_GROUND;
            }
            if (map != null && map != gm.getMapId()) {
                if (!w.warp(gm, map)) {
                    throw new QaRefusal("Cannot warp to " + map + ".");
                }
                ctx.log("warped to " + map);
            }
            Point spot = w.busiestMobSpot(gm);
            if (spot == null) {
                throw new QaRefusal("No live mobs on this map: pick a busy one (" + usage() + ").");
            }
            boolean on = !off(args);
            boolean was = w.setIdleSafety(on);
            ctx.log("idle safety " + (on ? "on" : "OFF (before/after comparison)") + ", was " + (was ? "on" : "off"));
            ctx.onStop("idle safety back " + (was ? "on" : "off"), () -> w.setIdleSafety(was));
            int count = bots(args);
            List<Integer> ids = w.spawnIdleBots(gm, spot, count);
            if (ids.isEmpty()) {
                throw new QaRefusal("No bot could be spawned here.");
            }
            ctx.despawnAtStop(ids, "idle bots in the mob pack at (" + spot.x + "," + spot.y + ")");
            for (int id : ids) {
                w.holdIdle(id, "qa");
            }
            botsByGm.put(ctx.run().gmId(), ids);
            ctx.log(w.liveMobCount(gm) + " live mobs; densest pack at (" + spot.x + "," + spot.y + ")");
            ctx.onStop("final idle tally", () -> {
                tally(ctx, "FINAL");
                botsByGm.remove(ctx.run().gmId());
            });
            return List.of(count + " idle bots stand in the densest mob pack at (" + spot.x + "," + spot.y + ").",
                    on ? "They should walk out of the pack within a second or two, and step away again if a mob reaches"
                            + " them or knocks them back."
                            : "Idle safety is OFF: they stay put and get tossed around (the 'before').",
                    "Hits and moves go to the log; a tally every 15 s. !qa stop when done.");
        }

        public void tick(QaContext ctx) {
            tally(ctx, "TALLY");
        }

        private void tally(QaContext ctx, String tag) {
            for (int id : botsByGm.getOrDefault(ctx.run().gmId(), List.of())) {
                String stats = ctx.world().idleStats(id);
                if (stats != null) {
                    ctx.run().note(tag, stats);
                }
            }
        }
    }

    // ---- mv-1 ----

    /**
     * mv-1: measures the GM's own attacks. For each attack (close, ranged, magic, touch): where the character stood,
     * then how far it moved in the movement the client sent during the attack window. On vanilla v83 an attack on
     * the ground stops the character, so the slide is ~0 px; a large slide with a direction held is the momentum bug.
     */
    static final class Mv1AttackSlide implements QaScenario {
        static final Map<Integer, SlideMeter> meters = new ConcurrentHashMap<>();

        public String id() { return "mv-1"; }
        public String title() { return "Attack slide: how far you move during each of your own attacks"; }
        public String usage() { return "[attacks 1-200] [window ms 100-3000], default 20 600"; }
        public Set<QaLog.Topic> topics() { return EnumSet.of(SLIDE); }
        public int minutes(String[] args) { return 10; }
        public int tickSeconds() { return 1; }

        static int attacks(String[] args) {
            return intArg(args, 0, SlideMeter.DEFAULT_ATTACKS, 1, 200);
        }

        static int windowMs(String[] args) {
            return intArg(args, 1, SlideMeter.DEFAULT_WINDOW_MS, 100, 3000);
        }

        private static int intArg(String[] args, int i, int fallback, int min, int max) {
            if (args == null || i >= args.length || !args[i].matches("\\d{1,5}")) {
                return fallback;
            }
            return Math.max(min, Math.min(max, Integer.parseInt(args[i])));
        }

        public List<String> stage(QaContext ctx) {
            int gmId = ctx.run().gmId();
            SlideMeter meter = new SlideMeter(windowMs(ctx.args()), attacks(ctx.args()));
            MoveTrace.Listener listener = new MoveTrace.Listener() {
                @Override
                public void attack(long now, int skill, String route, int x, int y, int stance, int speed) {
                    report(ctx, meter, now);
                    if (meter.attack(now, skill, route, x, y, stance, speed)) {
                        ctx.run().note("ATTACK", "skill " + skill + " " + route + " at (" + x + ", " + y
                                + ") stance " + stance + " speed " + speed);
                    }
                }

                @Override
                public void move(long now, List<SlideMeter.Frag> frags) {
                    meter.move(now, frags);
                    if (QaLog.on(SLIDE)) {
                        StringBuilder sb = new StringBuilder();
                        for (SlideMeter.Frag f : frags) {
                            sb.append(sb.length() == 0 ? "" : " | ").append(f.type()).append(f.absolute()
                                    ? " (" + f.x() + "," + f.y() + ") xv " + f.xv() + " fh " + f.fh() : "")
                                    .append(" st ").append(f.stance()).append(' ').append(f.durMs()).append("ms");
                        }
                        ctx.run().note("MOVE", sb.toString());
                    }
                    report(ctx, meter, now);
                }
            };
            meters.put(gmId, meter);
            MoveTrace.arm(gmId, listener);
            ctx.log("armed: " + meter.maxAttacks() + " attacks, window " + meter.windowMs() + " ms");
            ctx.onStop("disarm the mv-1 attack meter", () -> {
                MoveTrace.disarm(gmId, listener);
                meters.remove(gmId, meter);
                emit(ctx, meter.flush());
                summary(ctx, meter, true);
            });
            return List.of("Measuring your next " + meter.maxAttacks() + " attacks (window " + meter.windowMs()
                            + " ms each). Stand on flat ground by a mob.",
                    "Attack standing still, then attack while holding left/right. One line per attack:",
                    "slide = max px from where the attack started (vanilla: ~0 on the ground), held = walk stance seen.");
        }

        public void tick(QaContext ctx) {
            SlideMeter meter = meters.get(ctx.run().gmId());
            if (meter != null) {
                report(ctx, meter, System.currentTimeMillis());
            }
        }

        static void report(QaContext ctx, SlideMeter meter, long now) {
            emit(ctx, meter.due(now));
            if (meter.finished()) {
                summary(ctx, meter, false);
            }
        }

        private static void emit(QaContext ctx, List<SlideMeter.Result> results) {
            for (SlideMeter.Result r : results) {
                tell(ctx, SlideMeter.format(r));
            }
        }

        private static void summary(QaContext ctx, SlideMeter meter, boolean atStop) {
            if (!meter.takeSummary()) {
                return;
            }
            for (String line : SlideMeter.format(meter.summary())) {
                tell(ctx, line);
            }
            if (!atStop) {
                tell(ctx, "mv-1: all " + meter.maxAttacks() + " attacks measured. !qa stop closes the log.");
            }
        }

        private static void tell(QaContext ctx, String line) {
            QaLog.owner(SLIDE, ctx.run().gmId(), line);
            Character gm = ctx.gmNow();
            if (gm != null) {
                ctx.world().message(gm, line);
            }
        }
    }

    /** A staged bot as the GM should look for it: its name, level and where it stands (rc6, QA cv-1). */
    static String who(QaContext ctx, int botId) {
        Character gm = ctx.gmNow() != null ? ctx.gmNow() : ctx.gm();
        return ctx.world().botLabel(gm, botId);
    }

    // ---- af-1 / af-2 / af-3 (1.5.0, ticket 58: bot affinity) ----

    /**
     * Shared staging for the affinity tests: one bot next to the GM that already feels a chosen way about them,
     * a 30 s read-out of its feelings, and every affinity event and action on the map in the log.
     */
    abstract static class AffinityScenario implements QaScenario {
        private final Map<Integer, Integer> botByGm = new ConcurrentHashMap<>();

        public Set<QaLog.Topic> topics() { return EnumSet.of(AFFINITY, BOT_CHAT); }
        public int tickSeconds() { return 30; }

        public Set<Integer> watchedMaps(Character gm, String[] args) {
            return new java.util.HashSet<>(List.of(gm.getMapId(), HENESYS_HUNTING_GROUND));
        }

        /**
         * A hostile test (af-2/af-3) is about the staged bot's own KS sprees: no rival calls a KS war on the GM by
         * itself during the run (rc6, QA t-af-2: "two random bots just spawn and started going full ks mode" was a
         * war rolled on sight; rv-2 is the KS war test).
         */
        void holdKsWars(QaContext ctx) {
            QaWorld w = ctx.world();
            w.holdKsWars(ctx.gm(), true);
            ctx.log("no KS wars on you during this run (rv-2 tests those)");
            ctx.onStop("KS wars allowed again", () -> {
                Character now = ctx.gmNow();
                if (now != null) {
                    w.holdKsWars(now, false);
                }
            });
        }

        /** Stages the bot; hostile bots need mobs, so a GM on a map without any is warped to the Hunting Ground. */
        int stageBot(QaContext ctx, String state, boolean training) {
            QaWorld w = ctx.world();
            Character gm = ctx.gm();
            if (training && w.liveMobCount(gm) == 0) {
                if (!w.warp(gm, HENESYS_HUNTING_GROUND)) {
                    throw new QaRefusal("Cannot warp to " + HENESYS_HUNTING_GROUND + ".");
                }
                ctx.log("warped to " + HENESYS_HUNTING_GROUND + " (needs mobs)");
            }
            int id = w.stageAffinityBot(gm, state, training);
            if (id < 0) {
                throw new QaRefusal("No bot could be staged here.");
            }
            ctx.log("staged bot " + id + " as the GM's " + state + (training ? " (training, held on this map)" : " (social)"));
            ctx.onStop("unstage bot " + id + " and forget its ties", () -> w.unstageAffinityBot(id));
            botByGm.put(ctx.run().gmId(), id);
            ctx.onStop("final read-out", () -> {
                readOut(ctx, "FINAL");
                botByGm.remove(ctx.run().gmId());
            });
            readOut(ctx, "START");
            return id;
        }

        public void tick(QaContext ctx) {
            readOut(ctx, "STATE");
        }

        void readOut(QaContext ctx, String tag) {
            Integer id = botByGm.get(ctx.run().gmId());
            Character gm = ctx.gmNow();
            if (id != null && gm != null) {
                ctx.run().note(tag, "bot " + id + ": " + ctx.world().affinityOf(id, gm));
            }
        }

        Integer bot(QaContext ctx) {
            return botByGm.get(ctx.run().gmId());
        }

        static String word(String[] args, String fallback, String... allowed) {
            for (String a : args) {
                for (String ok : allowed) {
                    if (ok.equalsIgnoreCase(a)) {
                        return ok;
                    }
                }
            }
            return fallback;
        }
    }

    /** af-1: a friend bot. It greets, sends a buddy request or a party invite, buffs; party, fame, "ty" raise it. */
    static final class Af1Friend extends AffinityScenario {
        public String id() { return "af-1"; }
        public String title() { return "A bot friend: greetings, buddy request, party invite, buffs (ticket 58)"; }
        public String usage() { return "[social|training]"; }

        public List<String> stage(QaContext ctx) {
            boolean training = "training".equals(word(ctx.args(), "social", "social", "training"));
            int id = stageBot(ctx, "friend", training);
            return List.of(who(ctx, id) + " now counts you as a friend (GM read-out every 30 s in the log).",
                    "Stand near it: within a few minutes it greets you, and may send a buddy request or a party invite,",
                    "or buff you if it has a party buff. Each action is an ACT line. Say 'ty' or 'gg' to it: EVENT kind_chat.",
                    "Party it, fame it, trade it: EVENT party / fame / trade. No number is ever shown to you as a player.");
        }
    }

    /** af-2: a rival or nemesis bot on a mob map: taunts, KS sprees, refused trades, a nemesis may defame. */
    static final class Af2Rival extends AffinityScenario {
        public String id() { return "af-2"; }
        public String title() { return "A bot rival or nemesis: trash talk, KS sprees, refusals, defame (ticket 58)"; }
        public String usage() { return "[rival|nemesis]; then !qa do fight | grudge"; }

        public List<String> stage(QaContext ctx) {
            String state = word(ctx.args(), "rival", "rival", "nemesis");
            holdKsWars(ctx);
            int id = stageBot(ctx, state, true);
            return List.of(who(ctx, id) + ", a training bot held on this map, now counts you as its " + state + ".",
                    "Fight mobs near it: it taunts you now and then and goes on KS sprees (KS lines, at most 3 mobs per spree).",
                    "Hit a mob it is already fighting: EVENT ks. Try to trade it: REFUSE. A nemesis may defame you once a day.",
                    "It never follows you to another map. !qa do grudge forces a fresh grudge and a KS spree now.");
        }

        /** GMs whose run "fights": every tick they hit the mobs around them again (as rv-2's fight). */
        final Set<Integer> fighting = ConcurrentHashMap.newKeySet();
        static final int FIGHT_X = 500, FIGHT_Y = 120, FIGHT_MOBS = 8;

        @Override
        public int tickSeconds() {
            return 5;
        }

        @Override
        public void tick(QaContext ctx) {
            if (fighting.contains(ctx.run().gmId()) && ctx.gmNow() != null) {
                ctx.world().playerHitsMobs(ctx.gmNow(), FIGHT_X, FIGHT_Y, FIGHT_MOBS);
            }
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if ("fight".equals(name)) {
                // rc6 auto-QA triage (t-af-2: no KS line in any run): a rival's KS spree takes only a mob the player
                // has hit (AffinityReactions.ksPick: Monster.isAttackedBy), and the pretend player never attacks.
                Character gm = ctx.gmNow() != null ? ctx.gmNow() : ctx.gm();
                int n = ctx.world().playerHitsMobs(gm, FIGHT_X, FIGHT_Y, FIGHT_MOBS);
                int gmId = ctx.run().gmId();
                if (fighting.add(gmId)) {
                    ctx.onStop("stop fighting", () -> fighting.remove(gmId));
                }
                String line = "fighting: hit " + n + " mob(s) within " + FIGHT_X + " px (again every 5 s)";
                ctx.run().note("STEP", line);
                return List.of(line);
            }
            Integer id = bot(ctx);
            if (!"grudge".equals(name) || id == null) {
                throw new QaRefusal("af-2 steps: grudge, fight");
            }
            String result = ctx.world().forceGrudge(id, ctx.gm());
            ctx.run().note("STEP", result);
            readOut(ctx, "STATE");
            return List.of(result);
        }
    }

    /** af-3: @botdrama off. A nemesis bot next to the GM with drama off must stay neutral; !qa do on compares. */
    static final class Af3DramaOff extends AffinityScenario {
        public String id() { return "af-3"; }
        public String title() { return "@botdrama off: a nemesis bot stays neutral to you (ticket 58)"; }
        public String usage() { return "; then !qa do on | off"; }

        public List<String> stage(QaContext ctx) {
            QaWorld w = ctx.world();
            Character gm = ctx.gm();
            boolean was = w.setBotDramaOff(gm, true);
            ctx.log("@botdrama off (was " + (was ? "off" : "on") + ")");
            ctx.onStop("@botdrama back " + (was ? "off" : "on"), () -> {
                Character now = ctx.gmNow();
                if (now != null) {
                    w.setBotDramaOff(now, was);
                }
            });
            holdKsWars(ctx);
            int id = stageBot(ctx, "nemesis", true);
            return List.of("@botdrama is OFF for you and a nemesis bot, " + who(ctx, id) + ", is next to you.",
                    "Expect: the read-out says neutral, no taunt / ks / defame ACT lines, and its trades are not refused.",
                    "KS its mobs: no EVENT ks lines while off. !qa do on turns drama on to see the difference; stop restores it.");
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if (!"on".equals(name) && !"off".equals(name)) {
                throw new QaRefusal("af-3 steps: on, off");
            }
            ctx.world().setBotDramaOff(ctx.gm(), "off".equals(name));
            ctx.run().note("STEP", "@botdrama " + name);
            readOut(ctx, "STATE");
            return List.of("@botdrama " + name + ".");
        }
    }

    // ---- cp-1 / we-1 (1.5.0, ticket 58: bot couples and weddings) ----

    /** cp-1: a staged bot couple next to the GM. Party, chat about each other, a couple chair, rings, a love note. */
    static final class Cp1Couple implements QaScenario {
        private final Map<Integer, int[]> couples = new ConcurrentHashMap<>();

        public String id() { return "cp-1"; }
        public String title() { return "A bot couple or best friends: party, mention each other, couple chair, rings (ticket 58)"; }
        public String usage() { return "[married|friends] [same|mixed]; then !qa do step"; }
        public Set<QaLog.Topic> topics() { return EnumSet.of(COUPLE, BOT_CHAT); }
        /** rc6 (QA t-cu-1: "not really seeing any behavior"): a step every 20 s, so they act within the first minute. */
        public int tickSeconds() { return 20; }

        public List<String> stage(QaContext ctx) {
            String kind = AffinityScenario.word(ctx.args(), "dating", "married", "friends");
            Boolean same = sameArg(ctx.args());
            boolean married = "married".equals(kind);
            int[] ids = ctx.world().stageCouple(ctx.gm(), kind, same);
            if (ids == null) {
                throw new QaRefusal("No couple could be staged here.");
            }
            ctx.log("staged " + kind + " pair " + ids[0] + " + " + ids[1] + (same == null ? "" : same ? " (same gender)" : " (mixed)"));
            ctx.onStop("unstage couple " + ids[0] + " + " + ids[1], () -> ctx.world().unstageCouple(ids));
            couples.put(ctx.run().gmId(), ids);
            ctx.onStop("forget", () -> couples.remove(ctx.run().gmId()));
            ctx.world().coupleStep();
            if ("friends".equals(kind)) {
                return List.of(who(ctx, ids[0]) + " and " + who(ctx, ids[1]) + " are best friends: friendship rings.",
                        "Every 20 s (or !qa do step): they party up (PARTY), one walks over to the other (TOGETHER),",
                        "they smile at each other (SMILE) and talk to each other.");
            }
            return List.of(who(ctx, ids[0]) + " and " + who(ctx, ids[1]) + " are a couple"
                            + (married ? " (married: rings and the Amorian Loveseat)." : "."),
                    "Every 20 s (or !qa do step): they party up (PARTY), one walks over to the other (TOGETHER), they",
                    "smile (SIT for married ones: the loveseat, side by side), talk to each other, wear rings.",
                    "!couple note sends a love-note megaphone (through the megaphone gate).");
        }

        public void tick(QaContext ctx) {
            ctx.world().coupleStep();
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if (!"step".equals(name)) {
                throw new QaRefusal("cp-1 steps: step");
            }
            ctx.world().coupleStep();
            ctx.run().note("STEP", "couple step");
            return List.of("Ran the couples' step.");
        }
    }

    /** "same" / "mixed" in the arguments: both one gender / one of each; null = whatever comes. */
    static Boolean sameArg(String[] args) {
        for (String a : args) {
            if ("same".equalsIgnoreCase(a)) {
                return true;
            }
            if ("mixed".equalsIgnoreCase(a)) {
                return false;
            }
        }
        return null;
    }

    /**
     * we-1: a staged couple marries in Amoria's chapel now: notice, ceremony, rings, guests, then everyone goes home.
     * rc6 (QA t-we-1: "we-1 mixed sent me just to amoria not the chapel"): the run is held in the chapel (the White
     * Wedding Altar) and warps the GM there; live bot weddings stay in Amoria town.
     */
    static final class We1Wedding implements QaScenario {
        public static final int AMORIA = 680000000;
        public static final int CHAPEL = 680000110;

        public String id() { return "we-1"; }
        public String title() { return "A bot wedding in Amoria: notice, ceremony, rings, guests, bless EXP (ticket 58)"; }
        public String usage() { return "[same|mixed]"; }
        public Set<QaLog.Topic> topics() { return EnumSet.of(COUPLE, BOT_CHAT); }
        public Set<Integer> watchedMaps(Character gm, String[] args) { return Set.of(CHAPEL, AMORIA); }
        public Set<String> claims(Character gm, String[] args) { return Set.of("map:" + CHAPEL); }
        public int minutes(String[] args) { return 10; }

        public List<String> stage(QaContext ctx) {
            QaWorld w = ctx.world();
            Character gm = ctx.gm();
            if (!w.onEventChannel(gm)) {
                throw new QaRefusal("World events run on channel 1: change to channel 1 first.");
            }
            if (!w.warp(gm, CHAPEL)) {
                throw new QaRefusal("Cannot warp to the chapel (" + CHAPEL + ").");
            }
            ctx.log("warped to the chapel " + CHAPEL);
            int[] ids = w.stageCouple(ctx.gm(), "dating", sameArg(ctx.args()));
            if (ids == null) {
                throw new QaRefusal("No couple could be staged here.");
            }
            ctx.log("staged couple " + ids[0] + " + " + ids[1]);
            ctx.onStop("unstage couple " + ids[0] + " + " + ids[1], () -> w.unstageCouple(ids));
            ctx.onStop("stop the wedding if it still runs", () -> ctx.run().note("CLEANUP", "stopped "
                    + w.stopWeddings("!qa stop") + " wedding(s)"));
            String problem = w.startWedding(ids[0], "!qa we-1", CHAPEL);
            if (problem != null) {
                throw new QaRefusal("The wedding did not start: " + problem);
            }
            ctx.log("wedding started");
            return List.of("You are in Amoria's chapel. A blue notice says they are getting married; guests gather below the altar.",
                    "In about two minutes: the officiant speaks, both say i do (you get a small EXP blessing, BLESS, once),",
                    "rings (RINGS), \"married\" (MARRIED). Any two genders can marry; same/mixed picks the pair.",
                    "Guests cheer until the event ends (6 min); then guests leave and the couple goes home. !qa stop ends it early.");
        }
    }
}
