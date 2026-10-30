package soloMapling.ArtificialPlayer;

import client.Character;
import client.inventory.Pet;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapItem;
import server.maps.MapleMap;
import server.movement.AbsoluteLifeMovement;
import server.movement.LifeMovementFragment;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotHabits;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import tools.PacketCreator;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;

/**
 * 1.5.0 rc4: some bots walk around with a real pet (owner decision, moved up from 1.5.1).
 *
 * <p><b>Who.</b> About a quarter of the bots at level {@value #MIN_LEVEL}+ have one common v83 pet (kitty, puppy,
 * bunny, husky, panda, baby dragon ...). Every bot whose loot habit is {@link BotHabits.Loot#PET} has one with a
 * Meso Magnet and an Item Pouch, so that habit finally happens; a few other pet owners wear a Meso Magnet or an Item
 * Pouch too (it changes nothing for them: their habit is not to let the pet loot). The choice is drawn from the
 * bot's id with its own seed, so the same bot always has the same pet, name and pet equips.
 *
 * <p><b>Persistence: none, on purpose.</b> The pet is rebuilt from the bot's id at every login ({@link #attach}),
 * so there is nothing to store: no pets rows, no cash inventory item, no BotStore column, no migration. A bot's
 * level never goes down, so a bot that had a pet keeps it. The pet is a {@link Pet#botPet memory-only pet}: its
 * {@code saveToDb} is a no-op, so its id (the bot's own id) can never touch a real pet's row. The pet equips are
 * flags on the character ({@code setBotPetEquips}), not items, so BotStore's holdings never see them either.
 *
 * <p><b>Seen.</b> The pet is in the character's pet slots, so every map entry and every spawn packet a client gets
 * carries it (MapleMap.addPlayer also puts it at the bot's feet on each map and channel change), and the bot's
 * removal from the map removes the pet with it. While a real player watches the map, the bot's movement broadcast
 * moves the pet behind its bot ({@link #follow}, throttled); an unwatched map gets no pet packet at all. Bot pets
 * never get hungry (no hunger registration; World and Character also refuse it for bots).
 *
 * <p><b>Loot.</b> {@link #lootingPetSlot} and {@link #petMayTake} drive GrindLoot's pet pickups: mesos with a Meso
 * Magnet, items with an Item Pouch, never a player's dropped item (real pets skip those too), and never anybody
 * else's drop while its ownership window runs, whatever its drop type.
 */
public final class BotPets {

    private static final Logger log = LoggerFactory.getLogger(BotPets.class);

    /** The level from which a bot may have a pet. */
    public static final int MIN_LEVEL = 30;
    /** The share of the other (not pet-looting) bots at {@link #MIN_LEVEL}+ with a pet: ~10% + 90% x 17% = ~25%. */
    static final double OTHER_SHARE = 0.17;
    /** The share of the other pet owners that wear a Meso Magnet / an Item Pouch anyway. */
    static final double MAGNET_SHARE = 0.25;
    static final double POUCH_SHARE = 0.20;
    /** How long a drop belongs to its owner (DropCommands.FFA_OWNER_PROTECT_MS, vanilla). */
    static final long OWNERSHIP_MS = 15_000;

    /** Where the pet walks: this far behind its bot. */
    static final int BEHIND_PX = 38;
    /** At most one pet move packet per bot this often; a stop is sent at once. */
    static final long MOVE_GAP_MS = 150;

    /** Common v83 pets (all in Item.wz/Pet and String.wz/Pet.img): id and the species name. */
    static final int[] POOL = {
            5000000, // Brown Kitty
            5000001, // Brown Puppy
            5000002, // Pink Bunny
            5000004, // Black Kitty
            5000005, // White Bunny
            5000006, // Husky
            5000007, // Black Pig
            5000008, // Panda
            5000011, // Monkey
            5000012, // White Tiger
            5000020, // Mini Yeti
            5000023, // Penguin
            5000029, // Baby Dragon
            5000034, // Black Bunny
    };
    static final String[] SPECIES = {
            "Brown Kitty", "Brown Puppy", "Pink Bunny", "Black Kitty", "White Bunny", "Husky", "Black Pig", "Panda",
            "Monkey", "White Tiger", "Mini Yeti", "Penguin", "Baby Dragon", "Black Bunny",
    };
    /** Names players give their pets (half the bots keep the species name). Max 13 characters. */
    static final String[] NAMES = {
            "Mochi", "Boba", "Peanut", "Nugget", "Biscuit", "Pudding", "Kiwi", "Oreo", "Coco", "Muffin", "Sushi",
            "Tofu", "Pepper", "Waffles", "Noodle", "Bean", "Pickles", "Taco", "Snowball", "Ziggy", "Pumpkin", "Lulu",
            "Bubbles", "Dumpling",
    };

    /** A bot's pet: which, its name, and its pet equips. */
    public record Choice(int itemId, String name, boolean magnet, boolean pouch) {
        public boolean loots() {
            return magnet || pouch;
        }
    }

    // What a pet item must be to spawn; tests swap it to play a missing id.
    private static volatile IntPredicate itemExists = BotPets::petItemExists;
    private static final provider.DataProvider ITEM_DATA =
            provider.DataProviderFactory.getDataProvider(provider.wz.WZFiles.ITEM);
    private static final Map<Integer, Boolean> existsCache = new ConcurrentHashMap<>();
    private static final Set<Integer> warnedMissing = ConcurrentHashMap.newKeySet();
    // bot id -> when its pet last moved (follow throttle); dropped with the bot
    private static final Map<Integer, long[]> lastMove = new ConcurrentHashMap<>();
    // !qa pt-1 / !botcombat pets: staged bots whose pet loots whatever their own habit is
    private static final Set<Integer> stagedLooters = ConcurrentHashMap.newKeySet();

    private BotPets() {
    }

    /** The pet a bot of that id and level has, or null. Stable for the id (the level only gates it). */
    public static Choice choiceFor(int botId, int level) {
        if (level < MIN_LEVEL) {
            return null;
        }
        SplittableRandom r = new SplittableRandom(botId * 0xC2B2AE3D27D4EB4FL ^ 0x7E7E_9E75L);
        // every draw is taken in the same order whatever the outcome, so one choice never shifts another
        double has = r.nextDouble();
        int species = r.nextInt(POOL.length);
        boolean ownName = r.nextBoolean();
        int name = r.nextInt(NAMES.length);
        double magnet = r.nextDouble();
        double pouch = r.nextDouble();
        boolean petLooter = BotHabits.of(botId).loot() == BotHabits.Loot.PET;
        if (!petLooter && has >= OTHER_SHARE) {
            return null;
        }
        return new Choice(POOL[species], ownName ? NAMES[name] : SPECIES[species],
                petLooter || magnet < MAGNET_SHARE, petLooter || pouch < POUCH_SHARE);
    }

    /**
     * Gives a bot its pet, if it has one (bot generation calls this once the bot's level is known). True when
     * a pet was attached. Safe to call again: a bot that already has its pet is left alone.
     */
    public static boolean attach(Character bot) {
        if (bot == null || !BotHelpers.isBot(bot)) {
            return false;
        }
        try {
            return attach(bot, choiceFor(bot.getId(), bot.getLevel()));
        } catch (RuntimeException | LinkageError e) {
            log.warn("Bot {} gets no pet: {}", bot.getId(), e.toString());
            return false;
        }
    }

    /** Gives the bot this pet (null: none). False when it already has one or the pet item does not exist. */
    static boolean attach(Character bot, Choice choice) {
        if (choice == null || hasBotPet(bot)) {
            return false;
        }
        if (!itemExists.test(choice.itemId())) {
            if (warnedMissing.add(choice.itemId())) {
                log.warn("Pet item {} is not in the item data: bots drawing it go without a pet", choice.itemId());
            }
            return false;
        }
        Pet pet = Pet.botPet(choice.itemId(), bot.getId(), choice.name());
        Point at = bot.getPosition() != null ? new Point(bot.getPosition()) : new Point(0, 0);
        pet.setPos(at);
        pet.setStance(bot.getStance());
        bot.addPet(pet);
        bot.setBotPetEquips(choice.magnet(), choice.pouch());
        MapleMap map = bot.getMap();
        if (map != null && GCMovement.isMapObserved(map.getId())) {
            // the bot is already on a watched map: its spawn packet went out without the pet
            map.broadcastMessage(bot, PacketCreator.showPet(bot, pet, false, false), false);
        }
        return true;
    }

    /** Takes the bot's pet away (staging undo); despawns it for watchers. */
    public static void detach(Character bot) {
        if (bot == null) {
            return;
        }
        for (Pet pet : bot.getPets()) {
            if (pet != null && pet.isBotPet()) {
                MapleMap map = bot.getMap();
                if (map != null && GCMovement.isMapObserved(map.getId())) {
                    map.broadcastMessage(bot, PacketCreator.showPet(bot, pet, true, false), false);
                }
                bot.removePet(pet, true);
            }
        }
        bot.setBotPetEquips(false, false);
        forget(bot.getId());
    }

    /** The bot left the world: drop its follow state. */
    public static void forget(int botId) {
        lastMove.remove(botId);
        stagedLooters.remove(botId);
    }

    public static boolean hasBotPet(Character bot) {
        if (bot == null) {
            return false;
        }
        for (Pet p : bot.getPets()) {
            if (p != null && p.isBotPet()) {
                return true;
            }
        }
        return false;
    }

    // ---- following ----

    /**
     * The bot's movement just went out to watchers (BotMovementManager, observed maps only): its pet trots
     * along behind it. Throttled to {@link #MOVE_GAP_MS}; a stop goes out at once so the pet settles.
     */
    public static void follow(Character bot, int x, int y, int velX, int velY, int stance, int fh) {
        Pet pet = bot.getPet(0);
        if (pet == null || !pet.isBotPet()) {
            return;
        }
        long now = System.currentTimeMillis();
        long[] last = lastMove.computeIfAbsent(bot.getId(), k -> new long[]{0L, 0L});
        boolean stopped = velX == 0 && velY == 0;
        boolean wasMoving = last[1] != 0;
        if (now - last[0] < MOVE_GAP_MS && !(stopped && wasMoving)) {
            return;
        }
        long elapsed = last[0] == 0 ? MOVE_GAP_MS : Math.min(400, Math.max(50, now - last[0]));
        last[0] = now;
        last[1] = stopped ? 0 : 1;

        MapleMap map = bot.getMap();
        Point spot = petSpot(x, y, stance);
        if (spot.x != x && map != null && !onGround(map, spot.x, y)) {
            spot = new Point(x, y); // no floor behind the bot (a ledge end): the pet stays at its feet
        }
        int petStance = petStance(stance, stopped);
        AbsoluteLifeMovement move = new AbsoluteLifeMovement(0, spot, (int) elapsed, petStance);
        move.setPixelsPerSecond(new Point(velX, velY));
        move.setFh(fh);
        pet.setPos(spot);
        pet.setStance(petStance);
        pet.setFh(fh);
        if (map != null) {
            List<LifeMovementFragment> moves = List.of(move);
            map.broadcastMessage(bot, PacketCreator.movePet(bot.getId(), pet.getUniqueId(), (byte) 0, moves), false);
        }
    }

    /** Where the pet stands for its bot at (x, y) with this stance: {@link #BEHIND_PX} behind it. */
    static Point petSpot(int x, int y, int stance) {
        boolean facingLeft = (stance & 1) == 1;
        return new Point(facingLeft ? x + BEHIND_PX : x - BEHIND_PX, y);
    }

    /** The pet's stance for its bot's: walk / stand / jump as the bot, anything else (attack, rope) stands. */
    static int petStance(int botStance, boolean stopped) {
        int facing = botStance & 1;
        int kind = botStance >> 1; // 1 walk, 2 stand, 3 jump
        if (kind == 3) {
            return 6 | facing;
        }
        return (stopped ? 4 : 2) | facing;
    }

    private static boolean onGround(MapleMap map, int x, int y) {
        try {
            Point g = GCMovement.groundPointBelow(map, x, y - 10);
            return g != null && Math.abs(g.y - y) <= 12;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---- looting ----

    /** The slot of the bot's pet that loots (Meso Magnet or Item Pouch on), or -1. */
    public static int lootingPetSlot(Character bot) {
        if (bot == null || !(bot.isEquippedMesoMagnet() || bot.isEquippedItemPouch())) {
            return -1;
        }
        Pet[] pets = bot.getPets();
        for (int i = 0; i < pets.length; i++) {
            if (pets[i] != null && pets[i].isSummoned()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * May the bot's pet pick this drop up? Mesos need the Meso Magnet, items the Item Pouch; a player's own dropped
     * item is never a pet's (as for players' pets); and anybody else's drop waits out its whole ownership window,
     * whatever its drop type, so a bot's pet never takes a player's kill.
     */
    public static boolean petMayTake(Character bot, MapItem mi, long now) {
        if (bot == null || mi == null || mi.isPlayerDrop()) {
            return false;
        }
        if (mi.getMeso() > 0 ? !bot.isEquippedMesoMagnet() : !bot.isEquippedItemPouch()) {
            return false;
        }
        // the shared ownership rule (LootPicker): its bot's own drop, or anybody's once the window has run out
        return soloMapling.ArtificialPlayer.BotGrindSystem.LootPicker.claimable(mi, id -> id == bot.getId(),
                OWNERSHIP_MS, now);
    }

    /** Staged bots (pt-1) whose pet loots for them whatever their own loot habit is. */
    public static boolean isStagedLooter(int botId) {
        return stagedLooters.contains(botId);
    }

    /**
     * !qa pt-1 / !botcombat pets: gives a staged bot a pet now (its own draw if it has one, else one from the pool
     * by its id), with the Meso Magnet and Item Pouch when {@code loots}, and lets that pet loot for it.
     */
    public static Choice stage(Character bot, boolean loots) {
        Choice own = choiceFor(bot.getId(), MIN_LEVEL);
        int pick = Math.floorMod(bot.getId(), POOL.length);
        Choice c = own != null ? new Choice(own.itemId(), own.name(), loots, loots)
                : new Choice(POOL[pick], SPECIES[pick], loots, loots);
        if (!attach(bot, c) && !hasBotPet(bot)) {
            return null;
        }
        if (loots) {
            bot.setBotPetEquips(true, true);
            stagedLooters.add(bot.getId());
        }
        return c;
    }

    /** One line about a bot's pet, for the logs and GM read-outs. */
    public static String describe(Character bot) {
        for (Pet p : bot.getPets()) {
            if (p != null && p.isBotPet()) {
                return bot.getName() + ": pet " + p.getItemId() + " \"" + p.getName() + "\""
                        + (bot.isEquippedMesoMagnet() ? " +magnet" : "") + (bot.isEquippedItemPouch() ? " +pouch" : "")
                        + ", loot " + BotHabits.lootNow(bot);
            }
        }
        return bot.getName() + ": no pet";
    }

    // ---- staging (!qa pt-1, !botcombat pets) ----

    /** Gives each staged bot a pet ({@link #stage}); one line per bot. */
    public static List<String> stageAll(List<Integer> ids, boolean loots) {
        List<String> out = new java.util.ArrayList<>();
        for (int id : ids) {
            Character bot = BotHelpers.getCharFromChannelStorage(id);
            if (bot == null) {
                continue;
            }
            out.add(stage(bot, loots) == null ? bot.getName() + ": no pet (pet item missing?)" : describe(bot));
        }
        return out;
    }

    /** One {@link #describe} line per staged bot. */
    public static List<String> report(List<Integer> ids) {
        List<String> out = new java.util.ArrayList<>();
        for (int id : ids) {
            Character bot = BotHelpers.getCharFromChannelStorage(id);
            out.add(bot == null ? id + ": gone" : describe(bot));
        }
        return out;
    }

    /**
     * pt-1 {@code !qa do drop}: next to each staged bot, a display meso pile of its own (its pet takes it once it
     * settles) and one owned by the GM (the pet must leave it for {@value #OWNERSHIP_MS} ms). Display drops are
     * removed on pickup but credit nobody, so the step creates no mesos.
     */
    public static List<String> dropsFor(Character gm, List<Integer> ids) {
        List<String> out = new java.util.ArrayList<>();
        for (int id : ids) {
            Character bot = BotHelpers.getCharFromChannelStorage(id);
            MapleMap map = bot == null ? null : bot.getMap();
            if (map == null || bot.getPosition() == null) {
                continue;
            }
            Point p = bot.getPosition();
            map.spawnMesoDrop(77, new Point(p.x + 25, p.y), bot, bot, false, (byte) 0, (short) 0, true);
            map.spawnMesoDrop(66, new Point(p.x - 25, p.y), gm, gm, false, (byte) 0, (short) 0, true);
            out.add(bot.getName() + ": staged 77 mesos of its own and 66 of yours (the pet is not guaranteed to take the 77; real mob drops are what it loots)");
        }
        return out;
    }

    // ---- item data ----

    private static boolean petItemExists(int itemId) {
        return existsCache.computeIfAbsent(itemId, id -> {
            try {
                // the client draws a pet from its Item.wz/Pet image: no image, no pet
                return ItemConstants.isPet(id) && ITEM_DATA.getData("Pet/" + id + ".img") != null;
            } catch (RuntimeException | LinkageError e) {
                return false;
            }
        });
    }

    static void setItemCheckForTest(IntPredicate check) {
        itemExists = check != null ? check : BotPets::petItemExists;
        warnedMissing.clear();
    }

    static void resetForTest() {
        setItemCheckForTest(null);
        lastMove.clear();
        stagedLooters.clear();
    }
}
