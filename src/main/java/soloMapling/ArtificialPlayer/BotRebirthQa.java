package soloMapling.ArtificialPlayer;

import client.Character;
import client.Job;
import client.inventory.InventoryType;
import client.inventory.Item;
import soloMapling.ArtificialPlayer.BotProgressionSystem.BotBuild;
import soloMapling.ArtificialPlayer.BotProgressionSystem.BotProgression;
import soloMapling.ArtificialPlayer.BotProgressionSystem.BotRealStats;
import soloMapling.ArtificialPlayer.BotProgressionSystem.BotRebirth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 1.6.1 ticket 30: staging for {@code !qa rb-1} and auto-QA t-rb-1. An online roster bot is raised to level 200 on its
 * own build (4th job), then walks the earned path with the pace forced: two boss runs, the key crafted, the trip
 * granted, and the rebirth at Wyatt's rules. {@link #check} judges the result; {@link #restore} puts the bot back
 * exactly as it was (level, job, build, rebirths, its characters_rebirth row, EXP rate, the QA's items gone, the
 * pace slot given back).
 */
public final class BotRebirthQa {

    /** What the bot was before the run. */
    public record Before(Character bot, int level, long exp, int job, int reborns, BotRealStats build, int expRate,
                         long meso, int cashItems, Map<Short, Integer> worn, Map<Integer, Integer> skills) {
    }

    private BotRebirthQa() {
    }

    /** Picks an online bot (a build job, level 30+, under 3 rebirths) near {@code player}'s level; throws if none. */
    public static Before pick(Character player) {
        if (!BotRebirth.enabled()) {
            throw new IllegalStateException("bot rebirths are off (BOT_REBIRTH_ENABLED and USE_REBIRTH_SYSTEM)");
        }
        List<Character> bots = new ArrayList<>();
        for (BotSM sm : soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getAllBots().values()) {
            Character c = sm == null ? null : sm.getChr();
            if (c != null && c.isBot() && c.getMap() != null && c.getWorld() == player.getWorld() && c.getLevel() >= 30
                    && c.getLevel() < 200 && c.getParty() == null && BotBuild.isBuildJob(c.getJob().getId())
                    && BotProgression.isReal(c.getId()) && c.getReborns() < BotRebirth.MAX_REBIRTHS) {
                bots.add(c);
            }
        }
        if (bots.isEmpty()) throw new IllegalStateException("no online bot (level 30-199, a build job, no party)");
        bots.sort(Comparator.comparingInt((Character c) -> c.getMapId() == player.getMapId() ? 0 : 1)
                .thenComparingInt(Character::getReborns));
        Character bot = bots.getFirst();
        synchronized (bot) {
            Map<Short, Integer> worn = new LinkedHashMap<>();
            for (Item i : bot.getInventory(InventoryType.EQUIPPED).list()) worn.put(i.getPosition(), i.getItemId());
            Map<Integer, Integer> skills = new LinkedHashMap<>();
            bot.getSkills().forEach((s, e) -> { if (s != null && e != null && e.skillevel > 0) skills.put(s.getId(),
                    (int) e.skillevel); });
            return new Before(bot, bot.getLevel(), bot.getExp(), bot.getJob().getId(), bot.getReborns(),
                    BotProgression.snapshot(bot), bot.getExpRate(), bot.getMeso(),
                    bot.getInventory(InventoryType.CASH).list().size(), worn, skills);
        }
    }

    /** Raises the bot to 200 on its build (it advances to 4th job as any bot does); false if it did not get there. */
    public static boolean raise(Before b) {
        Character bot = b.bot();
        synchronized (bot) {
            bot.setLevel(BotRebirth.REBIRTH_LEVEL);
            BotProgression.afterOffscreenLevels(bot, b.level());
            return bot.getLevel() == BotRebirth.REBIRTH_LEVEL && BotRebirth.fullyAdvanced(bot.getJob().getId());
        }
    }

    /**
     * Walks the earned path with the pace forced: up to five decisions (boss runs, the craft) until the trip to Wyatt
     * is granted, then the rebirth there. Returns the evidence lines; the first says what happened.
     */
    public static List<String> walk(Before b) {
        Character bot = b.bot();
        List<String> ev = new ArrayList<>();
        long now = System.currentTimeMillis();
        if (BotRebirth.paceLeft(now) <= 0) BotRebirth.givePaceBack(now); // force: one slot today for the QA run
        BotRebirth.certainFor(120_000);
        try {
            int trip = -1;
            for (int i = 0; i < 5 && trip < 0 && bot.getReborns() == b.reborns(); i++) {
                trip = BotRebirth.tripMap(bot, now);
                ev.add("decision " + (i + 1) + ": stage " + BotRebirth.stageOf(bot, now) + ", drops "
                        + held(bot, BotRebirth.ingredientsFor(b.reborns())) + ", key "
                        + count(bot, BotRebirth.keyItemFor(b.reborns())) + ", trip " + trip);
            }
            BotRebirth.Result r = BotRebirth.rebirthHere(bot, now, false);
            if (!r.done() && bot.getReborns() == b.reborns() + 1) {
                r = new BotRebirth.Result(true, "its own decision got there first: " + bot.getName() + " rebirth "
                        + bot.getReborns());
            }
            ev.addFirst((r.done() ? "REBORN " : "NOT REBORN ") + r.text());
        } finally {
            BotRebirth.certainFor(0);
        }
        return ev;
    }

    /** What is wrong with the rebirth the QA run just made (empty: it is right). */
    public static List<String> check(Before b) {
        Character bot = b.bot();
        List<String> bad = new ArrayList<>();
        int r = b.reborns() + 1;
        if (bot.getReborns() != r) bad.add("rebirths " + bot.getReborns() + ", expected " + r);
        if (bot.getLevel() != 1 || bot.getExp() != 0) bad.add("level " + bot.getLevel() + " exp " + bot.getExp());
        int job = bot.getJob().getId();
        if (job != 0 && job != 1000 && job != 2000) bad.add("job " + job + " is not a beginner");
        if (count(bot, BotRebirth.keyItemFor(b.reborns())) != 0) bad.add("the key item was not taken");
        if (!held(bot, BotRebirth.ingredientsFor(b.reborns())).equals("0/0")) bad.add("boss drops left over");
        int rate = BotRebirth.rateFor(r, 1);
        if (rate > 0 && bot.getExpRate() != rate) bad.add("EXP rate " + bot.getExpRate() + ", expected " + rate);
        Map<Short, Integer> worn = new LinkedHashMap<>();
        for (Item i : bot.getInventory(InventoryType.EQUIPPED).list()) worn.put(i.getPosition(), i.getItemId());
        if (!worn.equals(b.worn())) bad.add("worn gear changed: " + b.worn() + " -> " + worn);
        if (bot.getMeso() != b.meso()) bad.add("mesos " + b.meso() + " -> " + bot.getMeso());
        if (bot.getInventory(InventoryType.CASH).list().size() != b.cashItems()) bad.add("cash items changed");
        long lost = b.skills().keySet().stream().filter(id -> bot.getSkills().keySet().stream()
                .noneMatch(s -> s != null && s.getId() == id)).count();
        if (lost > 0) bad.add(lost + " past skills lost");
        if (BotRebirth.rebirthHere(bot, System.currentTimeMillis(), false).done()) bad.add("reborn again at level 1");
        return bad;
    }

    /** One line on the bot now. */
    public static String describe(Before b) {
        Character bot = b.bot();
        return bot.getName() + " lv " + bot.getLevel() + " job " + bot.getJob().getId() + " rebirths "
                + bot.getReborns() + " EXP rate " + bot.getExpRate() + " key " + count(bot,
                BotRebirth.keyItemFor(b.reborns())) + " worn " + bot.getInventory(InventoryType.EQUIPPED).list().size();
    }

    /** Puts the bot back exactly as it was before the run; returns what it did. */
    public static String restore(Before b) {
        Character bot = b.bot();
        boolean reborn = bot.getReborns() > b.reborns();
        synchronized (bot) {
            for (int r = b.reborns(); r < BotRebirth.MAX_REBIRTHS; r++) {
                takeAll(bot, BotRebirth.keyItemFor(r));
                for (int id : BotRebirth.ingredientsFor(r)) takeAll(bot, id);
            }
            bot.setBotReborns(b.reborns());
            if (reborn) BotRebirth.unrecord(bot, b.reborns());
            bot.setJob(Job.getById(b.job()));
            bot.setLevel(b.level());
            bot.setExp(b.exp());
            BotProgression.forget(bot.getId());
            if (b.build() != null) BotProgression.restore(bot, b.build());
            else BotProgression.buildNew(bot);
            bot.setPlayerExpRatesCerezeth(b.expRate());
            bot.refreshStatsAfterProgression();
        }
        if (reborn) BotRebirth.givePaceBack(System.currentTimeMillis());
        return "restored " + describe(b);
    }

    private static void takeAll(Character bot, int itemId) {
        if (itemId < 0) return;
        for (Item held; (held = bot.getInventory(InventoryType.ETC).findById(itemId)) != null; ) {
            if (BotHoldings.consumeHeld(bot, held, held.getQuantity()) <= 0) return;
        }
    }

    private static int count(Character bot, int itemId) {
        return itemId < 0 ? 0 : bot.getInventory(InventoryType.ETC).countById(itemId);
    }

    private static String held(Character bot, int[] ids) {
        return ids.length < 2 ? "-" : count(bot, ids[0]) + "/" + count(bot, ids[1]);
    }
}
