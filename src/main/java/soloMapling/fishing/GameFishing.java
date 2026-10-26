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
    private GameFishing() { }
    private static long now() { return System.nanoTime() / 1_000_000; }
    public static int destination(Character chr) {
        return chr != null && area(chr.getMapId()) && chr.getPosition() != null && chr.getPosition().y > 0
                ? chr.getMapId() : 120010000;
    }
    public static boolean area(int map) { return MapId.isFishingArea(map); }
    public static boolean hasChair(Character chr) {
        return chr != null && chr.getInventory(InventoryType.SETUP).findById(FishingPolicy.CHAIR) != null;
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
                    && area(map.getId()) && point.y > 0 && chr.getChair() == FishingPolicy.CHAIR && hasChair(chr)
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
            String effect = "Effect/BasicEff.img/Catch/Success";
            chr.sendPacket(PacketCreator.showInfo(effect));
            chr.getMap().broadcastMessage(chr, PacketCreator.showForeignInfo(chr.getId(), effect), false);
            chr.dropMessage(5, "Caught " + reward.name() + ".");
            note(chr, "CAUGHT item=" + reward.itemId() + " quantity=1 sequence=" + sequence);
        }
    }
}
