package soloMapling.menus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where each {@code !qa} scenario sits in the {@code !qa} menu: the owner's QA page's three evenings and their
 * sections (1.5.0 Notes: 1 bots social, 2 economy, 3 combat/client), what {@code !qa run} variants it offers, and
 * which {@code !qa do} steps are clickable. 1.4.x scenarios that are not on the 1.5.0 page sit in the closest
 * evening. {@code QaMenuTest} fails when a registered scenario is missing here, so a new scenario must be placed.
 */
public final class QaMenuCatalog {

    /** One evening of the QA page. */
    public record Evening(int number, String title) {
    }

    public static final List<Evening> EVENINGS = List.of(
            new Evening(1, "Evening 1: bots social"),
            new Evening(2, "Evening 2: economy"),
            new Evening(3, "Evening 3: combat, client"));

    /** A {@code !qa run <id> <args>} choice: its label in the menu and the arguments. */
    public record Variant(String label, String args) {
    }

    /**
     * One scenario's place: evening, section heading, run variants (the default run is always offered too), the
     * {@code !qa do} steps a click can run as they are, and steps that need typed text (shown as a hint).
     */
    public record Entry(String id, int evening, String section, List<Variant> variants, List<String> steps,
                        List<String> typed) {
    }

    private static final Map<String, Entry> BY_ID = new LinkedHashMap<>();

    private static String currentSection;  // builder state while the table below is read
    private static int eveningNo;

    static {
        // ── Evening 1: bots social ──
        eveningNo = 1;
        section("Bots remember you");
        add("af-1", v("social", "training"), steps());
        add("af-2", v("rival", "nemesis"), steps("grudge", "fight"));
        add("af-3", v(), steps("on", "off"));
        section("Spicy rivals");
        add("rv-1", v(), steps("roast", "die", "nemesis", "status"));
        add("rv-2", v("small"), steps("fight", "kswar 2", "kswar 3", "kswar 4", "endwar", "status"));
        add("rv-3", v(), steps("callout"));
        add("rv-4", v(), steps("revenge"));
        section("Bot guilds (guildless)");
        add("gu-1", v(), steps());
        add("gu-3", v(), steps("recruit"));
        add("gu-4", v(), steps("jab"));
        add("gu-5", v("gpq", "zakum", "pap", "horntail"), steps());
        add("gu-8", v(), steps("kick", "vote"));
        section("Bots in your guild");
        add("gd-1", v(), steps());
        add("gu-2", v(), steps("join"));
        add("gu-6", v(), steps("rival", "ignore"));
        section("Guild wars");
        add("gw-1", v(), steps("turf", "taunt", "score", "end"));
        add("gw-2", v(), steps("check", "turf", "score", "peace"));
        add("gw-3", v(), steps("poach", "player"));
        add("gw-4", v(), steps("chat", "bossrun", "betray"));
        section("Partying with bots");
        add("gu-7", v(), steps());
        section("Party quests");
        add("pq-1", v(), steps("check", "where"));
        add("pq-2", v(), steps());
        add("dj-1", v("1", "5"), steps("check"));
        add("ex-1", v("1", "5"), steps()); // 1.6.0: party bots join your boss expedition
        add("mx-1", v("perfect"), steps()); // 1.7.0 ticket 130: four bots clear Kerning PQ stage 1 on their own
        add("mx-2", v("perfect"), steps()); // 1.6.1 KPQ automation: four bots play all of Kerning PQ on their own
        add("mx-3", v(), steps()); // 1.6.2: a stopped KPQ run leaves nobody inside
        add("mx-4", v(), steps()); // 1.6.2 b113: two channels run KPQ at once
        add("mx-5", v(), steps()); // 1.6.2: a player leads his alts and a bot in KPQ
        add("mx-6", v(), steps()); // 1.6.2: a player party takes the KPQ channel from a bot lobby run
        section("Couples and weddings");
        add("cp-1", v("married", "friends", "married same", "married mixed"), steps("step"));
        add("we-1", v("same", "mixed"), steps());
        section("Romance (opt-in)");
        add("rm-1", v(), steps("ask", "date", "whisper", "follow", "jealous", "step", "off"));
        add("rm-2", v("same", "mixed"), steps("propose", "wedding", "divorce"));
        add("rm-3", v(), steps("neglect", "breakup", "second", "confront", "flame", "gossip"));
        add("rm-4", v(), steps("propose", "wedding", "runaway"));
        section("Natural conversations");
        add("cv-1", v("social", "training"), steps(), "sense <line>");
        add("cv-2", v(), steps());
        add("cv-3", v("rival", "nemesis", "friend"), steps());
        add("cv-4", v(), steps("persona"));
        add("cv-5", v(), steps("thread", "thread beef", "thread gossip", "thread lfg", "thread trade",
                "thread training"));
        add("cv-6", v(), steps("last"));
        add("cv-7", v(), steps("llm", "bank"));
        add("cv-8", v(), steps("ask"), "ask <question>");
        add("cv-9", v(), steps("hold", "sample"), "hold [line]");
        add("cv-10", v(), steps());
        add("fh-1", v(), steps());
        add("fh-2", v(), steps("stop"));
        add("cv-11", v(), steps(), "say <line>");
        add("cv-12", v(), steps());
        section("Player likeness (1.6.0)");
        add("lk-1", v(), steps("camp", "sample"));
        section("Social gate (1.6.0)");
        add("sg-1", v(), steps("try", "loud", "quiet"));
        add("sg-2", v(), steps("check", "taunt", "ks", "roast", "fresh"));
        section("Bot bonds (1.6.0)");
        add("bb-3", v(), steps("fast", "check"), "fast <minutes>");
        add("bt-1", v(), steps("pair", "rival", "group", "check")); // 1.6.1 ticket 114: bot-to-bot talk
        section("Memory kinds (1.6.0)");
        add("me-1", v(), steps("card", "clear"));
        add("me-2", v(), steps("check", "scam", "fresh"));
        section("Grounded gossip (1.6.0)");
        add("gs-1", v(), steps("tell", "llm", "ks", "check"));
        section("Initiative (1.6.0)");
        add("in-1", v(), steps("login"));
        add("in-2", v(), steps("ask", "check"));
        add("in-3", v(), steps("invite"));
        section("Beef (1.6.0)");
        add("rv-5", v(), steps("beef", "llm", "check"));
        add("dr-6", v(), steps("boom", "back", "gossip"));
        add("cr-1", v(), steps("signs", "jealous", "ripen", "check"));
        section("World events (1.6.0)");
        add("ev-1", v(), steps("gossip"));
        add("ev-2", v(), steps("die", "status"));
        section("Bot watch (1.6.0)");
        add("bw-1", v(), steps()); // follows 20 bots for 10 min; !botwatch for longer looks
        add("nv-1", v(), steps()); // ticket 157: town portals and NPCs reachable (nav probe), logs leads
        section("Social trace (1.6.0)");
        add("st-1", v(), steps()); // 1.6.0 social trace: a short trace records a bot thread

        // ── Evening 2: economy ──
        eveningNo = 2;
        section("Gachapon and machines");
        add("ga-1", v(), steps("roll", "roll rare", "roll jackpot", "sim"));
        section("Wealth ceiling");
        add("ga-2", v(), steps("step"));
        section("Bot shops");
        add("tr-1", v(), steps("collect", "close"));
        add("fm-1", v(), steps());
        section("Trading and haggling");
        add("tr-2", v(), steps(), "sim <player line>");
        add("tr-3", v(), steps(), "quote <item words>");
        add("tr-6", v(), steps(), "line <text>");
        add("tr-8", v(), steps(), "smega <text>", "react <text>");
        add("nx-1", v(), steps("wallet"), "run: <non-GM buyer name>"); // 1.6.0: buy a 10k NX code for 50m
        add("xc-1", v(), steps("check")); // 1.6.1: the NX exchange in game (@nx), against a test regular
        add("xc-2", v(), steps("check")); // 1.6.1 tickers: the LEAF book in game (@nx leaf), against a test regular
        add("xc-3", v(), steps("check")); // 1.6.1 volatility: a Leaf Rush changes the rate; a trader bot posts an order
        section("Gifts and prices");
        add("tr-4", v(), steps("gift", "gift potions", "gift scroll", "gift mesos"));
        add("tr-9", v(), steps()); // 1.6.2: ask the friend bot "got any pots?" in chat
        add("tr-5", v(), steps("price", "tax"), "price <item id>", "tax <price>");
        add("mk-1", v(), steps());
        add("sf-1", v(), steps());
        add("bd-1", v(), steps());
        section("Hanako and meso sinks");
        add("tr-7", v(), steps("meters"));
        section("Partner rings (2nd character)");
        add("pr-1", v(), steps("rings"), "run: <partner name> [ring id]");
        add("pr-2", v(), steps("rings"), "run: <partner name> [ring id]");
        section("Scrolling");
        add("sc-2", v("careful", "normal", "gambler"), steps("roll"));
        add("gn-2", v(), steps());
        section("NPCs and storage (1.4.x)");
        add("np-1", v(), steps());
        add("np-2", v(), steps());
        add("np-3", v(), steps("close"));

        // ── Evening 3: combat, client ──
        eveningNo = 3;
        section("Bots fight like players");
        add("cb-1", v(), steps());
        add("cb-2", v(), steps());
        add("cb-3", v(), steps("hurt", "lowpots", "die"));
        add("cb-4", v(), steps("stepon", "fight"));
        add("sp-1", v(), steps("rope"));
        add("pt-1", v("noloot"), steps("drop"));
        add("hc-1", v(), steps()); // 1.6.2 b140: a staged Bishop follower heals the GM, for the Heal-column script
        section("Ordinary v83 features (ticket 69)");
        add("vb-5", v(), steps("buy", "sell", "expcard"));
        add("vb-7", v(), steps("storage", "duey", "trade"));
        add("vb-8", v(), steps("chain", "craft")); // 1.6.2 ticket 112: Moren's Maker 1 chain and a monster crystal
        add("ti-1", v(), steps("ask", "back")); // 1.6.1 ticket 29: the Maple Island question
        add("fy-1", v(), steps("check")); // 1.6.1 ticket 53: Family, a bot junior and the entitlements
        add("rb-1", v(), steps("check")); // 1.6.1 ticket 30: a regular earns a rebirth
        add("rb-2", v(), steps("check")); // 1.6.1 ticket 30 promise audit: the key item from a real Von Leon kill
        add("ac-1", v(), steps("mano", "fish", "list")); // 1.6.1 ticket 93: achievements and medals
        section("Bot attack timing");
        add("bk-1", v("4th", "pirates", "pacing"), steps());
        section("Idle bots, slide, bounces");
        add("id-1", v("off"), steps());
        add("ps-1", v(), steps());
        add("sl-1", v(), steps());
        add("ps-2", v(), steps());
        add("bl-1", v(), steps());
        add("bl-2", v(), steps("restock", "repeat"));
        add("bl-3", v(), steps("need"));
        add("bl-4", v(), steps());
        add("bl-11", v(), steps());
        add("bc-1", v(), steps("cc"));
        add("lr-1", v(), steps());
        add("mv-1", v(), steps());
        add("jq-1", v(), steps("push"));
        section("Client and channels");
        add("cs-2", v(), steps());
        add("co-1", v(), steps());
        add("oa-1", v(), steps());
        add("oa-2", v(), steps("alts", "alts remove")); // 1.6.2 ticket 99: companion orders and sharing
        add("gl-1", v(), steps("drop"));
        add("wd-1", v(), steps());
        section("Ordinary v83 features (ticket 69)");
        add("vb-1", v(), steps("hair", "haircolor", "face", "lens", "skin"));
        add("vb-2", v(), steps("delete", "wrongpic"));
        add("vb-3", v(), steps("clear", "reward"));
        add("vb-4", v(), steps("room", "bottle-broke", "bottle", "perfume", "hog"));
        add("vb-6", v(), steps("advance", "spend"));
        section("Older checks (1.4.x)");
        add("gh-1", v(), steps());
        add("ld-1", List.of(new Variant("perion", "102000000"), new Variant("kerning", "103000000")), steps());
        add("wd-2", v(), steps());
        add("pe-1", v(), steps());
    }

    private QaMenuCatalog() {
    }

    private static void section(String name) {
        currentSection = name;
    }

    private static List<Variant> v(String... args) {
        List<Variant> out = new ArrayList<>();
        for (String a : args) {
            out.add(new Variant(a, a));
        }
        return out;
    }

    private static List<String> steps(String... steps) {
        return List.of(steps);
    }

    private static void add(String id, List<Variant> variants, List<String> steps, String... typed) {
        if (BY_ID.put(id, new Entry(id, eveningNo, currentSection, List.copyOf(variants), steps, List.of(typed))) != null) {
            throw new IllegalStateException("QA menu lists " + id + " twice");
        }
    }

    /** Every placed scenario, in page order. */
    public static List<Entry> all() {
        return List.copyOf(BY_ID.values());
    }

    /** The scenario's place, or null when it is not placed. */
    public static Entry find(String id) {
        return BY_ID.get(id);
    }

    /** The scenarios of one evening, in page order. */
    public static List<Entry> evening(int number) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : BY_ID.values()) {
            if (e.evening() == number) {
                out.add(e);
            }
        }
        return out;
    }
}
