package soloMapling.ArtificialPlayer.BotFlavorSystem;

import client.Character;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.SocialBot;
import soloMapling.ArtificialPlayer.BotTypes.TownWandererBot;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.server.EventMessageSystem.EventBus;
import soloMapling.server.EventMessageSystem.EventSubscriber;
import soloMapling.server.EventMessageSystem.EventType;
import soloMapling.server.EventMessageSystem.GameEvent;
import soloMapling.server.ExecutorServiceManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak;

/**
 * Ticket 85 (player-likeness gap 5): "gz" on any map, not just in town. {@link LevelUpCongrats} only reaches
 * SocialBot and TownWandererBot, so a player levelling on a hunting map heard nothing. This listens for every level-up
 * and lets 0 to 2 other nearby bots (never those two types, which already react) say a short gz after a human delay.
 *
 * <p>Rates: a real player's level-up gets one gz with chance {@link #PLAYER_CHANCE} and a second with
 * {@link #SECOND_CHANCE} (about 0.48 gz a level when two bots are near; fewer when none is). A bot's level-up
 * only gets one when a real player is on the map to hear it, at {@link #BOT_CHANCE}. Never more than one burst per map
 * every {@value #MAP_GAP_MS} ms and {@value #MAX_PER_MINUTE} bursts a minute server-wide.
 */
public final class LevelUpGz implements EventSubscriber {

    public static volatile double PLAYER_CHANCE = 0.42;
    public static volatile double SECOND_CHANCE = 0.15;
    public static volatile double BOT_CHANCE = 0.08;
    public static final int RADIUS_X = 700;
    public static final int RADIUS_Y = 350;
    static final long MAP_GAP_MS = 20_000;
    static final int MAX_PER_MINUTE = 8;

    private static final LevelUpGz INSTANCE = new LevelUpGz();
    private static volatile boolean registered;

    private static final String[] PLAIN = {"gz", "gz!", "gz gz", "gratz", "grats", "ggs", "gzz", "nice", "gj", "gz :)",
            "congrats", "lets go", "wtg", "gz lol", "nicee"};
    private static final String[] NAMED = {"gz {N}", "grats {N}", "gratz {N}!", "gz {N} :D", "nice {N}", "ayy {N} gz",
            "congrats {N}", "gj {N}"};
    private static final String[] LEVEL = {"lv{L} gz", "gz on {L}", "{L} nice", "gz lv {L}", "gratz lv{L}"};

    private static final Map<Integer, Long> lastOnMap = new ConcurrentHashMap<>();
    private static final Deque<Long> bursts = new ArrayDeque<>();

    private LevelUpGz() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        EventBus.getInstance().subscribe(EventType.LEVEL_UP, INSTANCE);
        registered = true;
    }

    @Override
    public boolean matchesFilter(GameEvent event) {
        return enabled();
    }

    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("solomapling.levelGz", "true"));
    }

    @Override
    public void onEvent(GameEvent event) {
        try {
            react(event);
        } catch (RuntimeException e) {
            // ambient flavor: never let it reach the level-up
        }
    }

    private void react(GameEvent event) {
        if (event == null || event.getType() != EventType.LEVEL_UP) {
            return;
        }
        Character leveler = event.getMapleCharacter();
        if (leveler == null || leveler.getMap() == null || !GCMovement.isMapObserved(leveler)) {
            return;
        }
        boolean real = !BotHelpers.isBot(leveler);
        boolean audience = real;
        List<Character> eligible = new ArrayList<>();
        for (Character c : leveler.getMap().getAllPlayers()) {
            if (c == leveler) {
                continue;
            }
            if (!BotHelpers.isBot(c)) {
                audience = true;
                continue;
            }
            if (eligibleBot(c, leveler)) {
                eligible.add(c);
            }
        }
        int speakers = plan(real, audience, eligible.size(), ThreadLocalRandom.current());
        if (speakers == 0 || !claim(leveler.getMapId(), System.currentTimeMillis())) {
            return;
        }
        java.util.Collections.shuffle(eligible);
        long delay = 0;
        for (int i = 0; i < speakers; i++) {
            Character bot = eligible.get(i);
            delay += i == 0 ? 3_000 + ThreadLocalRandom.current().nextInt(5_000) : 2_000 + ThreadLocalRandom.current().nextInt(5_000);
            final String line = line(leveler.getName(), leveler.getLevel(), ThreadLocalRandom.current());
            ExecutorServiceManager.getScheduledExecutorService().schedule(() -> fire(bot, leveler, line), delay,
                    TimeUnit.MILLISECONDS);
        }
    }

    /** How many bots say gz: 0 to 2. Pure. */
    public static int plan(boolean levelerIsReal, boolean audience, int eligible, Random rng) {
        if (eligible <= 0 || !audience) {
            return 0;
        }
        if (rng.nextDouble() >= (levelerIsReal ? PLAYER_CHANCE : BOT_CHANCE)) {
            return 0;
        }
        return eligible >= 2 && levelerIsReal && rng.nextDouble() < SECOND_CHANCE ? 2 : 1;
    }

    /** One burst per map per {@link #MAP_GAP_MS}, {@link #MAX_PER_MINUTE} a minute everywhere. Books it when it lets one. */
    static boolean claim(int mapId, long now) {
        synchronized (bursts) {
            while (!bursts.isEmpty() && now - bursts.peekFirst() > 60_000) {
                bursts.pollFirst();
            }
            Long last = lastOnMap.get(mapId);
            if (bursts.size() >= MAX_PER_MINUTE || last != null && now - last < MAP_GAP_MS) {
                return false;
            }
            bursts.addLast(now);
            lastOnMap.put(mapId, now);
            return true;
        }
    }

    private static boolean eligibleBot(Character c, Character leveler) {
        BotSM brain = CharacterStorage.getBotById(c.getId());
        if (brain == null || brain instanceof SocialBot || brain instanceof TownWandererBot) {
            return false; // those two congratulate through LevelUpCongrats
        }
        return c.isAlive() && Math.abs(c.getPosition().x - leveler.getPosition().x) <= RADIUS_X
                && Math.abs(c.getPosition().y - leveler.getPosition().y) <= RADIUS_Y;
    }

    private static void fire(Character bot, Character leveler, String line) {
        if (bot.getMap() == null || bot.getMap() != leveler.getMap() || !bot.isAlive()
                || !GCMovement.isMapObserved(bot)) {
            return;
        }
        BotSpeak(bot, line);
    }

    /** A short gz in a human mix: bare, with the name, or with the level. Pure. */
    public static String line(String name, int level, Random rng) {
        double roll = rng.nextDouble();
        if (roll < 0.55) {
            return PLAIN[rng.nextInt(PLAIN.length)];
        }
        if (roll < 0.85) {
            return NAMED[rng.nextInt(NAMED.length)].replace("{N}", name);
        }
        return LEVEL[rng.nextInt(LEVEL.length)].replace("{L}", Integer.toString(level));
    }

    /** Test seam. */
    public static void resetForTest() {
        lastOnMap.clear();
        synchronized (bursts) {
            bursts.clear();
        }
    }
}
