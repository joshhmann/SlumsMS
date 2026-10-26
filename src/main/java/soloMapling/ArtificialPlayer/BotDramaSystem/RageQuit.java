package soloMapling.ArtificialPlayer.BotDramaSystem;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotChannels;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotLogout;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotPopulationDirector;
import soloMapling.ArtificialPlayer.BotRoster;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.server.BotTiming;
import soloMapling.server.ExecutorServiceManager;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 1.6.0 slice S7 "Beef": rage-quit and return. A bot that booms an expensive scroll (the {@code LifeMemory.BOOM} life
 * event, {@code BotScrolling.rollOnce}) may type a rage line ("im done with this game", "f this"), log out, and come
 * back {@link #MIN_AWAY_MS}-{@link #MAX_AWAY_MS} (2-6 hours) later with a sheepish line ("im back lol"). Its friends
 * get it as news for their threads ({@link DramaNews}: "{OTHER} rage quit after a boom lol").
 *
 * <p><b>Rare and bounded.</b> Only an item worth at least {@link #MIN_VALUE} mesos (or of level {@link #MIN_ITEM_LEVEL}+
 * when the market has no price), a chance of {@link #chance} (0.12 x (0.5 + Killer pull), at most 0.3), at most once per
 * bot per {@link #PER_BOT_GAP_MS} (3 days), {@link #PER_HOUR} (2) an hour and {@link #PER_DAY} (8) a day world-wide.
 * Only a roster bot that may log out now (no trade, no party with a real player, no conversation: the same
 * {@link BotPopulationDirector#mayLogOut} the director uses) - the leave itself is {@link BotLogout#requestOwn}, so a
 * commitment that comes up in the beat before still holds it.
 *
 * <p><b>The return.</b> While it is away, the director does not log it in ({@link #away}); when it is due, it is logged
 * in once ({@link BotPopulationDirector#logInBounded}) and says its line a few seconds after it lands
 * ({@link #onBotLogin}). A return that cannot log in is tried every minute for {@link #RETURN_TRY_MS} (6 h), then
 * forgotten (the director's normal logins take over). Memory only: a restart forgets who is away, and the bot simply
 * comes back with the director.
 */
public final class RageQuit {

    private static final Logger log = LoggerFactory.getLogger(RageQuit.class);

    static final int MIN_VALUE = 300_000;
    static final int MIN_ITEM_LEVEL = 50;
    static final long PER_BOT_GAP_MS = TimeUnit.DAYS.toMillis(3);
    static final int PER_HOUR = 2;
    static final int PER_DAY = 8;
    static final long MIN_AWAY_MS = TimeUnit.HOURS.toMillis(2);
    static final long MAX_AWAY_MS = TimeUnit.HOURS.toMillis(6);
    static final long RETURN_TRY_MS = TimeUnit.HOURS.toMillis(6);
    /** The beat between the rage line and the logout, so it is read. */
    static final long LEAVE_BEAT_MS = 3_500;
    /** After the comeback login, the sheepish line comes this long after the bot lands. */
    static final long RETURN_LINE_MS = 8_000;

    /** How a rage-quitter leaves and comes back: the real logout/login, or a staged bot's channel hop (QA). */
    interface Exit {
        void leave(Character bot);

        boolean comeBack(Away a);
    }

    /** One bot away after a rage quit. {@code staged}: a QA bot (it hops channels instead of logging out). */
    record Away(int charId, String name, String item, long quitAt, long returnAt, boolean staged, int channel,
                int mapId, int x, int y) {
    }

    private static final Map<Integer, Away> away = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> lastQuitAt = new ConcurrentHashMap<>();
    /** Bots logged back in after a rage quit, whose line is still to come: char id -> the away it ends. */
    private static final Map<Integer, Away> returning = new ConcurrentHashMap<>();
    private static final Map<Long, Integer> perDay = new ConcurrentHashMap<>();
    private static volatile long lastStepAt;
    private static volatile Exit exit = new RealExit();
    /** A staged bot's channel hop (a seam for tests). */
    private static volatile java.util.function.BiPredicate<Character, Integer> hop = BotChannels::moveToChannel;
    /** Runs something a little later (a seam for tests: at once). */
    private static volatile java.util.function.BiConsumer<Long, Runnable> later = BotTiming::after;

    private RageQuit() {
    }

    // ── the rules (pure) ──

    /** Whether a boomed item is expensive enough to rage over: its market value, else its level. */
    static boolean expensive(Integer value, int itemLevel) {
        return value != null ? value >= MIN_VALUE : itemLevel >= MIN_ITEM_LEVEL;
    }

    /** The chance a bot rage quits over an expensive boom: 0.12 x (0.5 + Killer pull), at most 0.3. */
    static double chance(double killer) {
        return Math.min(0.3, 0.12 * (0.5 + Math.max(0, killer)));
    }

    /** How long a rage-quitter stays away: 2-6 hours. */
    static long awayMs(Random rng) {
        return MIN_AWAY_MS + (long) (rng.nextDouble() * (MAX_AWAY_MS - MIN_AWAY_MS));
    }

    /** Whether the world-wide and per-bot caps allow one more rage quit now (nothing is counted). */
    static boolean capsAllow(int charId, long now) {
        Long last = lastQuitAt.get(charId);
        long day = Math.floorDiv(now, TimeUnit.DAYS.toMillis(1));
        return (last == null || now - last >= PER_BOT_GAP_MS) && perDay.getOrDefault(day, 0) < PER_DAY;
    }

    // ── the trigger ──

    /**
     * A bot's scroll boomed its item ({@code BotScrolling.rollOnce}, after the life event). Its friends standing by may
     * tease it; and it may rage quit. Memory work only on the caller's thread.
     */
    public static void onBotBoom(Character bot, int itemId, Integer valueBefore) {
        if (!BeefKit.enabled() || bot == null || !BotHelpers.isBot(bot)) {
            return;
        }
        Teasing.onBotBoom(bot);
        long now = BeefKit.now();
        Random rng = ThreadLocalRandom.current();
        if (!expensive(valueBefore, itemLevel(itemId)) || rng.nextDouble() >= chance(BeefKit.rivalPull(bot.getId()))) {
            return;
        }
        BotSM sm = CharacterStorage.getBotById(bot.getId());
        if (sm == null || !BotHelpers.isRosterBot(bot) || BotRoster.loadedMember(bot.getId() - BotStore.BOT_ID_BASE) == null
                || !BotPopulationDirector.mayLogOut(sm) || DramaKit.inScene(bot.getId()) || BotLogout.isLeaving(bot.getId())) {
            return;
        }
        String item = Teasing.itemName(itemId);
        long awayMs = awayMs(rng);
        ExecutorServiceManager.runAsync(() -> quit(bot, item, awayMs, false, now)); // its line may type: off this thread
    }

    /**
     * The bot rage quits now: the line, then (after {@link #LEAVE_BEAT_MS}) the leave; back after {@code awayMs}.
     * {@code staged} (QA): a channel hop out and back instead of a logout, and no caps. What happened.
     */
    static String quit(Character bot, String item, long awayMs, boolean staged, long now) {
        if (!staged) {
            synchronized (RageQuit.class) {
                if (!capsAllow(bot.getId(), now) || !DramaKit.hourly("rq", PER_HOUR, now)) {
                    return "rage quit capped";
                }
                long day = Math.floorDiv(now, TimeUnit.DAYS.toMillis(1));
                perDay.merge(day, 1, Integer::sum);
                perDay.keySet().removeIf(d -> d < day - 1);
            }
        }
        lastQuitAt.put(bot.getId(), now);
        Point p = bot.getPosition();
        Away a = new Away(bot.getId(), bot.getName(), item, now, now + awayMs, staged, DramaKit.channelOf(bot),
                bot.getMapId(), p == null ? 0 : p.x, p == null ? 0 : p.y);
        away.put(bot.getId(), a);
        String line = DramaKit.say(bot, "RageQuit", Map.of("ITEM", item == null ? "my item" : item));
        DramaNews.add(DramaNews.Kind.RAGE_QUIT, bot.getId(), bot.getName(), item, now);
        BeefKit.qa(bot.getMapId(), "RAGEQUIT " + bot.getName() + " (" + item + "): \"" + line + "\"; back in "
                + TimeUnit.MILLISECONDS.toMinutes(awayMs) + " min" + (staged ? " (staged)" : ""));
        log.info("Bot {} rage quits after booming {} (back in {} min)", bot.getName(), item,
                TimeUnit.MILLISECONDS.toMinutes(awayMs));
        later.accept(LEAVE_BEAT_MS, () -> {
            try {
                exit.leave(bot);
            } catch (RuntimeException e) {
                log.debug("Rage quit of {} could not leave", bot.getName(), e);
            }
        });
        return bot.getName() + " rage quits: \"" + line + "\"";
    }

    /** Whether this bot is away after a rage quit now (the director leaves it offline). */
    public static boolean away(int charId, long now) {
        Away a = away.get(charId);
        return a != null && now < a.returnAt();
    }

    // ── the return ──

    /** From the drama tick: at most once a minute, due rage-quitters come back. */
    static void maybeStep(long now) {
        if (away.isEmpty() || now - lastStepAt < TimeUnit.MINUTES.toMillis(1)) {
            return;
        }
        lastStepAt = now;
        ExecutorServiceManager.runAsync(() -> step(now));
    }

    /** Due returns now (also the QA door). The names that came back. */
    static List<String> step(long now) {
        List<String> back = new ArrayList<>();
        for (Away a : List.copyOf(away.values())) {
            if (now < a.returnAt()) {
                continue;
            }
            if (now - a.returnAt() > RETURN_TRY_MS) {
                away.remove(a.charId());
                continue;
            }
            returning.put(a.charId(), a);
            boolean ok;
            try {
                ok = exit.comeBack(a);
            } catch (RuntimeException e) {
                log.debug("Rage-quit return of {} failed", a.name(), e);
                ok = false;
            }
            if (ok) {
                away.remove(a.charId());
                back.add(a.name());
            } else {
                returning.remove(a.charId());
            }
        }
        return back;
    }

    /** A bot joined the world: a rage-quitter coming back says its line once it stands on the map. */
    static void onBotLogin(Character bot) {
        Away a = bot == null ? null : returning.get(bot.getId());
        if (a == null) {
            return;
        }
        later.accept(RETURN_LINE_MS + ThreadLocalRandom.current().nextInt(4_000), () -> sayBack(bot, a));
    }

    static void sayBack(Character bot, Away a) {
        if (returning.remove(bot.getId()) == null) {
            return;
        }
        String line = bot.getMap() == null ? null : DramaKit.say(bot, "RageReturn", Map.of());
        long now = BeefKit.now();
        DramaNews.add(DramaNews.Kind.BACK, bot.getId(), bot.getName(), a.item(), now);
        BeefKit.qa(bot.getMapId(), "RAGEQUIT BACK " + bot.getName() + " after "
                + TimeUnit.MILLISECONDS.toMinutes(now - a.quitAt()) + " min: \"" + line + "\"");
    }

    /**
     * {@code !qa dr-6}: the bot "booms" an item now (the BOOM life event) and rage quits at once, staged: it hops to
     * another channel for {@code awayMs} instead of logging out, then comes back to its spot. What happened.
     */
    public static String forced(Character bot, int itemId, long awayMs) {
        if (bot == null) {
            return "no bot";
        }
        soloMapling.ArtificialPlayer.BotLifeSystem.LifeRuntime.event(bot,
                soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemory.Event.BOOM);
        lastQuitAt.remove(bot.getId());
        String out = quit(bot, Teasing.itemName(itemId), awayMs, true, BeefKit.now());
        return out == null ? "no rage quit" : out;
    }

    /** {@code !qa dr-6}: due returns now (the minute step, at once). The names that came back. */
    public static List<String> returnsNow() {
        return step(BeefKit.now());
    }

    /** {@code !qa dr-6} cleanup: forget a staged bot's rage quit. */
    public static void forget(int charId) {
        away.remove(charId);
        returning.remove(charId);
        lastQuitAt.remove(charId);
    }

    /** Who is away now, for GMs. */
    public static List<String> describe(long now) {
        List<String> out = new ArrayList<>();
        for (Away a : away.values()) {
            out.add(a.name() + " (" + a.item() + ") back in " + Math.max(0, TimeUnit.MILLISECONDS.toMinutes(a.returnAt() - now))
                    + " min" + (a.staged() ? ", staged" : ""));
        }
        return out;
    }

    static int itemLevel(int itemId) {
        try {
            Integer req = server.ItemInformationProvider.getInstance().getEquipLevelReq(itemId);
            return req == null ? 0 : req;
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }

    // ── how it leaves and comes back ──

    /** The roster bot logs out for real and the director logs it back in; a staged QA bot hops channels. */
    static final class RealExit implements Exit {
        @Override
        public void leave(Character bot) {
            Away a = away.get(bot.getId());
            if (a != null && a.staged()) {
                int other = otherChannel(a.channel());
                hop.test(bot, other);
                return;
            }
            BotLogout.requestOwn(bot.getId(), System.currentTimeMillis());
        }

        @Override
        public boolean comeBack(Away a) {
            if (a.staged()) {
                Character bot = BeefKit.online(a.charId()); // a staged idle bot is only in the world's storage
                if (bot == null) {
                    return false;
                }
                // a channel hop keeps the bot's map and spot, so it reappears where it stood (never a warp)
                boolean moved = DramaKit.channelOf(bot) == a.channel() || hop.test(bot, a.channel());
                if (moved) {
                    onBotLogin(bot);
                }
                return moved;
            }
            if (CharacterStorage.botLoggedIn(a.charId())) {
                returning.remove(a.charId()); // logged in some other way meanwhile (a GM): no comeback line
                return true;
            }
            return BotPopulationDirector.logInBounded(a.charId() - BotStore.BOT_ID_BASE);
        }

        private static int otherChannel(int ch) {
            return ch == 1 ? 2 : 1;
        }
    }

    // ── test seams ──

    static void useForTest(Exit testExit) {
        exit = testExit != null ? testExit : new RealExit();
        later = testExit != null ? (ms, r) -> r.run() : BotTiming::after;
        away.clear();
        lastQuitAt.clear();
        returning.clear();
        perDay.clear();
        lastStepAt = 0;
    }

    static void useHopForTest(java.util.function.BiPredicate<Character, Integer> testHop) {
        hop = testHop != null ? testHop : BotChannels::moveToChannel;
    }

    static boolean returningNow(int charId) {
        return returning.containsKey(charId);
    }
}
