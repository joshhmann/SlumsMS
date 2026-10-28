package soloMapling.fishing;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.manipulator.InventoryManipulator;
import constants.id.MapId;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.qa.QaLog;
import tools.PacketCreator;

import java.util.concurrent.ThreadLocalRandom;

/** Shared player/bot inventory adapter. Uses the existing world fishing task, no extra scheduler or model calls. */
public final class GameFishing {
    private static final FishingSessions SESSIONS = new FishingSessions(256, FishingPolicy.REWARDS,
            bound -> ThreadLocalRandom.current().nextInt(bound));
    /** 1.6.0 t-fh-2: since boot, fishing sessions bots started and catches paid to bots and to players. */
    private static final java.util.concurrent.atomic.LongAdder BOT_STARTS = new java.util.concurrent.atomic.LongAdder(),
            BOT_CATCHES = new java.util.concurrent.atomic.LongAdder(), PLAYER_CATCHES = new java.util.concurrent.atomic.LongAdder();
    private GameFishing() { }
    /** 1.6.0 t-fh-2: bots fishing right now. */
    public static int botsFishingNow() {
        return SESSIONS.count(id -> id >= soloMapling.server.SoloMaplingConstants.GameConstants.BOT_BASE_ID);
    }
    /** 1.6.0 t-fh-2: {bot sessions started, bot catches, player catches} since boot. */
    public static long[] counters() {
        return new long[]{BOT_STARTS.sum(), BOT_CATCHES.sum(), PLAYER_CATCHES.sum()};
    }
    /** 1.6.0 t-fh-2: one line for the QA log: bots fishing now and since boot. */
    public static String botsLine() {
        long[] c = counters();
        return "BOTS active=" + botsFishingNow() + " started=" + c[0] + " caught=" + c[1] + " playerCaught=" + c[2];
    }
    private static long now() { return System.nanoTime() / 1_000_000; }
    public static int destination(Character chr) {
        return chr != null && area(chr.getMapId()) && chr.getPosition() != null && chr.getPosition().y > 0
                ? chr.getMapId() : 120010000;
    }
    public static boolean area(int map) { return MapId.isFishingArea(map); }
    /**
     * The chair a character fishes from: the Fishing Chair if owned; for a player, else any other chair it owns; 0 if
     * none. 1.6.0 (owner, 2026-10-27): bots fish only from the Fishing Chair (3011000), so a bot without one never
     * fishes; players still fish from any chair ("sit on any chair").
     */
    public static int chairToUse(Character chr) {
        if (chr == null) return 0;
        var setup = chr.getInventory(InventoryType.SETUP);
        if (setup == null) return 0;
        if (setup.findById(FishingPolicy.CHAIR) != null) return FishingPolicy.CHAIR;
        if (BotHelpers.isBot(chr)) return 0;
        for (var item : setup.list()) if (constants.inventory.ItemConstants.canFishFromChair(item.getItemId())) return item.getItemId();
        return 0;
    }
    public static boolean hasChair(Character chr) { return chairToUse(chr) != 0; }
    /** Whether the chair this character sits on fishes: any fishing chair for a player, only the Fishing Chair for a bot. */
    static boolean seatFishes(Character chr) {
        int chair = chr.getChair();
        return BotHelpers.isBot(chr) ? chair == FishingPolicy.CHAIR : constants.inventory.ItemConstants.canFishFromChair(chair);
    }
    /**
     * Whether a bot may pick FISH as a life activity: the fishing flags are on, it owns a Fishing Chair (1.6.0:
     * {@link BotFishingChairs}) and its fishing map can take it.
     */
    public static boolean choosable(Character chr, java.util.function.IntPredicate reachable) {
        return FishingPolicy.rewardsEnabled() && hasChair(chr) && reachable.test(destination(chr));
    }
    public static boolean active(Character chr) { return chr != null && SESSIONS.active(chr.getId(), chr); }
    public static boolean start(Character chr) {
        if (!FishingPolicy.rewardsEnabled() || chr == null) return false;
        var result = SESSIONS.start(new GameActor(chr), now());
        if (result == FishingSessions.Start.STARTED) {
            if (BotHelpers.isBot(chr)) BOT_STARTS.increment();
            chr.dropMessage(5, "Fishing... stay seated for a bite.");
            note(chr, "START");
        }
        return result == FishingSessions.Start.STARTED || result == FishingSessions.Start.ALREADY_FISHING;
    }
    public static void cancel(Character chr) {
        if (chr != null && active(chr)) { SESSIONS.cancel(chr.getId(), chr); note(chr, "STOP"); }
    }
    public static void tick(int world) { SESSIONS.tickWorld(world, now()); }
    public static void stopWorld(int world) { SESSIONS.cancelWorld(world); }
    private static void note(Character chr, String text) {
        if (QaLog.on(QaLog.Topic.HABITS)) QaLog.map(QaLog.Topic.HABITS, chr.getMapId(),
                "FISHING " + text + " actor=" + chr.getId());
    }
    private record GameActor(Character chr) implements FishingSessions.Actor {
        public int id() { return chr.getId(); }
        public Object identity() { return chr; }
        public FishingSessions.Position position() {
            var map = chr.getMap(); var point = chr.getPosition();
            boolean online;
            if (BotHelpers.isBot(chr)) {
                var bot = CharacterStorage.getBotById(chr.getId());
                online = bot != null && bot.getChr() == chr && bot.getRunning();
            } else online = chr.isLoggedinWorld();
            boolean safe = FishingPolicy.rewardsEnabled() && online && chr.isAlive() && map != null && point != null
                    && area(map.getId()) && point.y > 0 && seatFishes(chr) && hasChair(chr)
                    && chr.getEventInstance() == null && chr.getTrade() == null && chr.getShop() == null
                    && chr.getPlayerShop() == null && chr.getHiredMerchant() == null;
            return new FishingSessions.Position(chr.getWorld(), map == null ? -1 : map.getChannelId(), map,
                    point == null ? 0 : point.x, point == null ? 0 : point.y, chr.getChair(), safe);
        }
        public boolean give(FishingSessions.Reward reward) {
            // Direct Character overload: BotClient.getPlayer() is null.
            return FishingPolicy.rewardsEnabled() && InventoryManipulator.addById(chr, reward.itemId(), (short) 1,
                    null, -1, -1);
        }
        public void fullBag() { chr.dropMessage(5, "Make room in your inventory to keep your next catch."); }
        public void caught(FishingSessions.Reward reward, long sequence) {
            (BotHelpers.isBot(chr) ? BOT_CATCHES : PLAYER_CATCHES).increment();
            soloMapling.achievements.Achievements.onFish(chr); // 1.6.1 ticket 93
            String effect = "Effect/BasicEff.img/Catch/Success";
            chr.sendPacket(PacketCreator.showInfo(effect));
            chr.getMap().broadcastMessage(chr, PacketCreator.showForeignInfo(chr.getId(), effect), false);
            chr.dropMessage(5, "Caught " + reward.name() + ".");
            note(chr, "CAUGHT item=" + reward.itemId() + " quantity=1 sequence=" + sequence);
        }
    }
}
