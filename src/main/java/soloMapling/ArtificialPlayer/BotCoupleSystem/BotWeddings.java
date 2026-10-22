package soloMapling.ArtificialPlayer.BotCoupleSystem;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import server.life.NPC;
import server.maps.MapObject;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotChannels;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.worldevents.ActiveEvent;
import soloMapling.worldevents.WorldEvents;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak;

/**
 * Ticket 58: a bot couple's wedding, as a world event (ticket 21, kind {@code wedding}, template
 * {@code amoria_wedding}). The stock v83 wedding (reservations, the Cathedral/Chapel event instances, the
 * {@code marriages} rows) is built for two real players and real reservations must always win, so bots do not use it:
 * the ceremony is staged in Amoria town, in front of the chapel, where any player can walk up and watch.
 *
 * <ul>
 *   <li>Start: the world's blue notice "[Wedding] A &amp; B are getting married in Amoria!" (and the event's usual
 *   megaphone chance). The couple comes to Amoria on channel 1 (a channel hop, then a warp - as a player would use a
 *   return scroll) dressed for it: a tuxedo and a wedding dress over their own clothes for the day. Guests appear.</li>
 *   <li>Ceremony (about two minutes): a guest officiates, both say "i do", rings are given (they wear them from then
 *   on), they are pronounced married; the guests cheer until the event ends.</li>
 *   <li>End: guests leave, the couple gets its own clothes back and returns to where it was, doing what it did.</li>
 * </ul>
 * Only a couple of free bots marries: neither may be partied, following or grouped with a real player, in an event
 * or PQ, trading, or gathering for a guild boss run ({@link BotCouples#freeForWedding}). The wedding clothes are a loan
 * ({@link OutfitLoans}): the bots' stored rows keep their own clothes throughout, and a bot never leaves the world
 * mid-ceremony. Watchers are blessed once the couple is married, so a ceremony stopped early blesses nobody.
 * Nothing is paid out, no {@code characters} or {@code marriages} row changes, and no reservation is touched.
 */
public final class BotWeddings {

    public static final String TEMPLATE = "amoria_wedding";
    public static final int AMORIA = 680000000;
    /** Amoria NPCs the ceremony gathers by (the wedding staff in front of the chapel), first found wins. */
    static final int[] CHAPEL_NPCS = {9201002, 9201005, 9201003, 9201004, 9201000};
    static final int TUXEDO = 1050113, WEDDING_DRESS = 1051114;
    static final short OVERALL_SLOT = -105;
    static final int GUESTS = 5;

    /** The ceremony beats, in seconds after the start. */
    static final int OFFICIANT_1 = 40, OFFICIANT_2 = 55, GROOM_VOWS = 70, BRIDE_VOWS = 80, RINGS = 95, PRONOUNCE = 110;

    /** One running ceremony. */
    static final class Ceremony {
        final BotCouples.Couple couple;
        final int groom, bride;
        final Map<Integer, int[]> home = new ConcurrentHashMap<>();      // charId -> {mapId, x, y}
        final Map<Integer, String> type = new ConcurrentHashMap<>();     // charId -> TRAINING / SOCIAL
        final List<Integer> guests = new ArrayList<>();
        final Set<Integer> fired = new HashSet<>();
        /** Real players already blessed at this wedding. */
        final Set<Integer> blessed = ConcurrentHashMap.newKeySet();
        final long startedAt;
        volatile boolean married;
        long nextCheer;

        Ceremony(BotCouples.Couple couple, int groom, int bride, long startedAt) {
            this.couple = couple;
            this.groom = groom;
            this.bride = bride;
            this.startedAt = startedAt;
        }
    }

    private static volatile BotCouples.Couple requested;
    private static final Map<Integer, Ceremony> byEvent = new ConcurrentHashMap<>();
    private static final Set<Integer> inCeremony = ConcurrentHashMap.newKeySet();

    private BotWeddings() {
    }

    public static boolean running() {
        return !byEvent.isEmpty();
    }

    public static boolean inCeremony(int charId) {
        return inCeremony.contains(charId);
    }

    /** Starts the wedding event for this couple now; the problem, or null when it started. */
    public static synchronized String start(BotCouples.Couple couple, String by) {
        if (couple == null) {
            return "no couple";
        }
        if (couple.stage() == BotCouples.Stage.MARRIED) {
            return "they are married already";
        }
        if (couple.stage() == BotCouples.Stage.FRIENDS) {
            return "best friends do not marry";
        }
        if (running()) {
            return "a wedding is already on";
        }
        requested = couple;
        try {
            WorldEvents.StartResult r = WorldEvents.getInstance().startById(TEMPLATE, 0, by);
            return r.error();
        } finally {
            requested = null;
        }
    }

    // ── called by WorldEvents for kind WEDDING ──

    /** Sets the ceremony up on the event map: a problem, or null. */
    public static String begin(ActiveEvent e, MapleMap map) {
        BotCouples.Couple couple = requested;
        if (couple == null) {
            return "bot weddings start from a couple (!couple wedding), not on their own";
        }
        Character a = live(couple.charA());
        Character b = live(couple.charB());
        if (a == null || b == null) {
            return "the couple is not online";
        }
        for (Character chr : List.of(a, b)) {
            if (!BotCouples.freeForWedding(chr)) {
                return chr.getName() + " is busy (with a player, in an event, trading or on a guild run)";
            }
        }
        // Any pair may marry (owner 2026-10-21). "Groom" and "bride" are only the two halves' places at the altar.
        Character groom = a.getGender() == b.getGender() || a.getGender() == 0 ? a : b;
        Character bride = groom == a ? b : a;
        Ceremony c = new Ceremony(couple, groom.getId(), bride.getId(), System.currentTimeMillis());
        Point altar = altar(map);
        for (Character chr : List.of(groom, bride)) {
            BotSM sm = CharacterStorage.getBotById(chr.getId());
            c.home.put(chr.getId(), new int[]{chr.getMapId(), chr.getPosition().x, chr.getPosition().y});
            c.type.put(chr.getId(), sm instanceof TrainingBot ? "TRAINING" : "SOCIAL");
            if (BotCouples.channelOf(chr) != e.channel && !BotChannels.moveToChannel(chr, e.channel)) {
                return chr.getName() + " could not come to channel " + e.channel;
            }
        }
        inCeremony.add(groom.getId());
        inCeremony.add(bride.getId());
        place(groom, map, new Point(altar.x - 30, altar.y));
        place(bride, map, new Point(altar.x + 30, altar.y));
        dress(c, groom, outfitFor(groom.getGender()));
        dress(c, bride, outfitFor(bride.getGender()));
        int base = BotStore.nextDynamicSlots(GUESTS);
        for (int i = 0; i < GUESTS; i++) {
            Point at = new Point(altar.x + (i % 2 == 0 ? -1 : 1) * (110 + 45 * (i / 2)), altar.y);
            int id = BotGeneration.createBot(base + i, at, map);
            Character guest = id > 0 ? BotHelpers.getCharFromChannelStorage(id) : null;
            if (guest != null) {
                BotTypeManager.convertBotType(guest, BotTypeManager.BotType.SOCIAL_BOT);
                c.guests.add(id);
            }
        }
        e.setSubject(groom.getName() + " & " + bride.getName());
        byEvent.put(e.id, c);
        java.util.concurrent.ScheduledFuture<?>[] beat = new java.util.concurrent.ScheduledFuture<?>[1];
        beat[0] = soloMapling.server.ExecutorServiceManager.getScheduledExecutorService().scheduleAtFixedRate(() -> {
            if (!byEvent.containsKey(e.id)) {
                beat[0].cancel(false);
                return;
            }
            try {
                step(e, System.currentTimeMillis());
            } catch (RuntimeException ex) {
                BotCouples.qa("ceremony step failed: " + ex);
            }
        }, 3, 3, java.util.concurrent.TimeUnit.SECONDS);
        BotCouples.qa("WEDDING #" + e.id + " begins: " + groom.getName() + " & " + bride.getName() + ", " + c.guests.size() + " guests at ("
                + altar.x + "," + altar.y + ")");
        return null;
    }

    /** One step of the ceremony (the event ticks every few seconds). */
    public static void step(ActiveEvent e, long now) {
        Ceremony c = byEvent.get(e.id);
        if (c == null) {
            return;
        }
        Character groom = live(c.groom);
        Character bride = live(c.bride);
        Character officiant = c.guests.isEmpty() ? null : live(c.guests.get(0));
        long s = (now - c.startedAt) / 1000;
        Map<String, String> names = Map.of("GROOM", groom == null ? "" : groom.getName(), "BRIDE",
                bride == null ? "" : bride.getName());
        if (beat(c, OFFICIANT_1, s)) {
            line(officiant, "Officiant", names, 0);
        }
        if (beat(c, OFFICIANT_2, s)) {
            line(officiant, "Officiant", names, 1);
        }
        if (beat(c, GROOM_VOWS, s)) {
            line(groom, "Vows", names, -1);
        }
        if (beat(c, BRIDE_VOWS, s)) {
            line(bride, "Vows", names, -1);
        }
        if (beat(c, RINGS, s)) {
            line(officiant, "RingsGiven", names, -1);
            int ring = BotCouples.ringOf(c.couple);
            if (groom != null) {
                BotCouples.wearRing(groom, ring);
            }
            if (bride != null) {
                BotCouples.wearRing(bride, ring);
            }
            BotCouples.qa("RINGS " + ring + " on " + names.get("GROOM") + " and " + names.get("BRIDE"));
        }
        if (beat(c, PRONOUNCE, s)) {
            line(officiant, "Pronounce", names, -1);
            BotCouples.marry(c.couple, now); // recorded first: a couple blessed once is married, so never blessed again
            c.married = true;
            bless(c, groom);
            bless(c, bride);
            c.nextCheer = now + 4_000;
            BotCouples.qa("MARRIED couple #" + c.couple.id() + ": " + names.get("GROOM") + " & " + names.get("BRIDE"));
        }
        if (c.married && now >= c.nextCheer && !c.guests.isEmpty()) {
            Character guest = live(c.guests.get(ThreadLocalRandom.current().nextInt(c.guests.size())));
            line(guest, "Guest", names, -1);
            c.nextCheer = now + 12_000 + ThreadLocalRandom.current().nextInt(12_000);
        }
    }

    /** The event ended (or was stopped): guests leave, the couple goes home in its own clothes. */
    public static void end(ActiveEvent e) {
        Ceremony c = byEvent.remove(e.id);
        if (c == null) {
            return;
        }
        for (int id : c.guests) {
            Character guest = BotHelpers.getCharFromChannelStorage(id);
            if (guest != null) {
                BotSM sm = CharacterStorage.getBotById(id);
                if (sm != null) {
                    sm.setRunning(false);
                    sm.stopScheduledTask();
                }
                BotGeneration.removeBotFromServer(guest);
            }
        }
        for (int id : List.of(c.groom, c.bride)) {
            inCeremony.remove(id);
            Character chr = live(id);
            if (chr == null) {
                continue;
            }
            undress(c, chr);
            int[] home = c.home.get(id);
            if (home != null && chr.getClient() != null) {
                MapleMap back = chr.getClient().getChannelServer().getMapFactory().getMap(home[0]);
                if (back != null) {
                    place(chr, back, new Point(home[1], home[2]));
                }
            }
            if ("TRAINING".equals(c.type.get(id))
                    && CharacterStorage.getBotById(id) instanceof soloMapling.ArtificialPlayer.BotTypes.SocialBot) {
                BotTypeManager.convertBotType(chr, BotTypeManager.BotType.TRAINING_BOT); // only the type it was given
            }
        }
        BotCouples.qa("WEDDING #" + e.id + " ends: " + (c.married ? "married" : "not married (stopped early)"));
    }

    // ── pieces ──

    /** Each wears what fits them: a tuxedo for a man, a wedding dress for a woman. */
    static int outfitFor(int gender) {
        return gender == 1 ? WEDDING_DRESS : TUXEDO;
    }

    /**
     * "I now pronounce": every real player on the map who is watching gets a small blessing - once per player per
     * wedding, and only once the marriage is recorded: about 1% of their level's EXP, at most
     * {@code -Dsolomapling.couples.blessExpCap} (20000).
     */
    static void bless(Ceremony c, Character speaker) {
        if (speaker == null || speaker.getMap() == null || !c.married) {
            return;
        }
        for (Character chr : speaker.getMap().getAllPlayers()) {
            if (BotHelpers.isBot(chr) || !BotHelpers.isVisibleRealPlayer(chr) || !c.blessed.add(chr.getId())) {
                continue;
            }
            int exp = blessExp(chr.getLevel());
            if (exp > 0) {
                chr.gainExp(exp, true, true);
                chr.dropMessage(5, "You feel blessed by the newlyweds' happiness.");
                BotCouples.qa("BLESS " + chr.getName() + " +" + exp + " EXP");
            }
        }
    }

    static int blessExp(int level) {
        long cap = (long) BotCouples.prop("blessExpCap", 20000);
        if (level <= 0 || level >= 200 || cap <= 0) {
            return 0;
        }
        long needed = constants.game.ExpTable.getExpNeededForLevel(level);
        return (int) Math.max(1, Math.min(cap, needed / 100));
    }

    private static boolean beat(Ceremony c, int at, long seconds) {
        return seconds >= at && c.fired.add(at);
    }

    /** A line from the wedding pack; index picks a fixed line (the officiant's two openers), -1 a random one. */
    private static void line(Character bot, String node, Map<String, String> tokens, int index) {
        if (bot == null) {
            return;
        }
        String text;
        if (index >= 0) {
            soloMapling.ArtificialPlayer.BotDialogueHandler.DialogueConstructor d =
                    soloMapling.ArtificialPlayer.BotDialogueHandler.getDialogueCon("CoupleDialogue.yaml", "Wedding", node);
            List<String> lines = d == null ? null : d.getDialogue();
            text = lines == null || lines.size() <= index ? null : lines.get(index);
            if (text != null) {
                for (Map.Entry<String, String> t : tokens.entrySet()) {
                    text = text.replace("{" + t.getKey() + "}", t.getValue());
                }
            }
        } else {
            text = BotCouples.pick("Wedding", node, tokens);
        }
        if (text != null) {
            BotSpeak(bot, text);
            BotCouples.qa("SAY " + bot.getName() + ": " + text);
        }
    }

    /** Where the couple stands: by a wedding NPC on the map, else at the map's first portal. */
    static Point altar(MapleMap map) {
        for (int npcId : CHAPEL_NPCS) {
            for (MapObject mo : map.getMapObjectsInRange(new Point(0, 0), Double.POSITIVE_INFINITY,
                    List.of(MapObjectType.NPC))) {
                if (mo instanceof NPC npc && npc.getId() == npcId) {
                    return new Point(npc.getPosition().x, npc.getPosition().y);
                }
            }
        }
        return map.getPortal(0) != null ? new Point(map.getPortal(0).getPosition()) : new Point(0, 0);
    }

    private static void place(Character chr, MapleMap map, Point at) {
        soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.stop(chr);
        BotSM sm = CharacterStorage.getBotById(chr.getId());
        if (sm instanceof TrainingBot) {
            BotTypeManager.convertBotType(chr, BotTypeManager.BotType.SOCIAL_BOT); // stand and mingle, not grind
        }
        chr.changeMap(map, at);
    }

    /** The wedding outfit goes on as a loan: the bot's own overall is kept (never a tux or dress) and goes back. */
    static void dress(Ceremony c, Character chr, int outfit) {
        try {
            OutfitLoans.lend(chr, OVERALL_SLOT, outfit, TUXEDO, WEDDING_DRESS);
        } catch (RuntimeException ex) {
            BotCouples.qa("could not dress " + chr.getName() + ": " + ex);
        }
    }

    static void undress(Ceremony c, Character chr) {
        try {
            if (!OutfitLoans.giveBack(chr, OVERALL_SLOT)) {
                takeOffWeddingClothes(chr);
            }
        } catch (RuntimeException ex) {
            BotCouples.qa("could not undress " + chr.getName() + ": " + ex);
        }
    }

    /** Whether this item is wedding clothes (the tuxedo or the dress). */
    static boolean weddingOutfit(int itemId) {
        return itemId == TUXEDO || itemId == WEDDING_DRESS;
    }

    /**
     * A bot wearing wedding clothes outside a ceremony (an older build stored them as its own) takes them off; true
     * when it did. Its own clothes from before are not known any more, so the slot is left empty.
     */
    static boolean takeOffWeddingClothes(Character chr) {
        if (inCeremony(chr.getId()) || OutfitLoans.lent(chr, OVERALL_SLOT)) {
            return false;
        }
        Item worn = chr.getInventory(InventoryType.EQUIPPED).getItem(OVERALL_SLOT);
        if (worn == null || !weddingOutfit(worn.getItemId())) {
            return false;
        }
        chr.getInventory(InventoryType.EQUIPPED).removeSlot(OVERALL_SLOT);
        chr.equipChanged();
        BotCouples.qa("UNDRESSED " + chr.getName() + ": wedding clothes outside a wedding");
        return true;
    }

    private static Character live(int charId) {
        BotSM sm = CharacterStorage.getBotById(charId);
        return sm == null ? null : sm.getChr();
    }

    /** Tests: the bot is in a ceremony now. */
    static void markInCeremonyForTest(int charId) {
        inCeremony.add(charId);
    }

    /** Tests. */
    static void resetForTest() {
        byEvent.clear();
        inCeremony.clear();
        requested = null;
    }
}
