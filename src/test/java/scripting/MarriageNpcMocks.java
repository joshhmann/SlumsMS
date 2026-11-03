package scripting;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Stand-ins for the parts of NPCConversationManager, Character, Channel and World that the Amoria wedding NPC scripts
 * touch. Every item grant, meso change and message goes into one ordered {@link #log} so a test can assert order.
 */
final class MarriageNpcMocks {

    private MarriageNpcMocks() {
    }

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    static Invocable load(String npcScript, Cm cm) {
        ScriptEngine engine = new Loader().load("npc/" + npcScript);
        assertNotNull(engine, npcScript + " evaluates");
        engine.put("cm", cm);
        return (Invocable) engine;
    }

    public static final class Map0 {
        public Pl partner;
        public final List<String> messages = new ArrayList<>();

        public Pl getCharacterById(int id) {
            return partner;
        }

        public void dropMessage(int type, String text) {
            messages.add(text);
        }
    }

    /** Ordered record of what the script did. */
    static final class Log {
        final List<String> events = new ArrayList<>();
        String lastText;
        boolean disposed;

        boolean has(String event) {
            return events.contains(event);
        }

        int indexOf(String event) {
            return events.indexOf(event);
        }
    }

    public static class Pl {
        final Log log;
        final String name;
        public int id;
        public int partnerId;
        public int gender;
        public final Map0 map;
        public boolean hasSpace = true;
        public final Map<Integer, Integer> items = new HashMap<>();
        public final Map<Integer, Integer> equipped = new HashMap<>();
        public final List<Object> gifts = new ArrayList<>();

        Pl(Log log, String name, int id, int partnerId, Map0 map) {
            this.log = log;
            this.name = name;
            this.id = id;
            this.partnerId = partnerId;
            this.map = map;
        }

        public int getId() {
            return id;
        }

        public int getPartnerId() {
            return partnerId;
        }

        public int getGender() {
            return gender;
        }

        public Map0 getMap() {
            return map;
        }

        public boolean haveItemWithId(int item, boolean checkEquipped) {
            return items.getOrDefault(item, 0) > 0 || (checkEquipped && equipped.getOrDefault(item, 0) > 0);
        }

        public boolean haveItemEquipped(int item) {
            return equipped.getOrDefault(item, 0) > 0;
        }

        public boolean haveItem(int item) {
            return items.getOrDefault(item, 0) > 0;
        }

        public boolean canHold(int item, int qty) {
            return hasSpace;
        }

        public boolean canHold(int item) {
            return hasSpace;
        }

        public void dropMessage(int type, String text) {
            log.events.add(name + " msg");
        }

        public Pl getAbstractPlayerInteraction() {
            return this;
        }

        public List<Object> getUnclaimedMarriageGifts() {
            return gifts;
        }

        public void gainItem(int item, int qty) {
            log.events.add(name + " gain " + item + " " + qty);
            items.merge(item, qty, Integer::sum);
        }

        public void gainItem(int item, int qty, boolean randomStats, boolean showMessage, long expires) {
            log.events.add(name + " gain " + item + " " + qty);
            items.merge(item, qty, Integer::sum);
        }

        public boolean isMarried() {
            return false;
        }

        public Object getMarriageRing() {
            return null;
        }

        public int marriageItemId = -1;
        public int exp;

        public void setMarriageItemId(int id) {
            marriageItemId = id;
        }

        public void gainExp(int amount) {
            exp += amount;
        }
    }

    /** Event instance of a running wedding. */
    public static final class Eim {
        public final Map<String, Integer> props = new HashMap<>();

        public int getIntProperty(String key) {
            return props.getOrDefault(key, -1);
        }

        public void setIntProperty(String key, Integer value) {
            props.put(key, value);
        }

        public int gridSize() {
            return 0;
        }

        public void schedule(String method, long delay) {
        }
    }

    /** Channel. */
    public static final class Ch {
        public int id;
        public int pushResult = 3;
        public boolean reserved;
        public Integer ongoing;
        public String timeLeft;
        public final Map<Boolean, Integer> status = new HashMap<>();
        final List<String> pushes = new ArrayList<>();

        public int getId() {
            return id;
        }

        public boolean isWeddingReserved(Integer weddingId) {
            return reserved;
        }

        public Integer getOngoingWedding(boolean cathedral) {
            return ongoing;
        }

        public int getWeddingReservationStatus(Integer weddingId, boolean cathedral) {
            return status.getOrDefault(cathedral, -1);
        }

        public String getWeddingReservationTimeLeft(Integer weddingId) {
            return timeLeft;
        }

        public int pushWeddingReservation(Integer weddingId, boolean cathedral, boolean premium, Integer groomId, Integer brideId) {
            pushes.add("push " + weddingId + " " + cathedral);
            return pushResult;
        }

        public Ch getPlayerStorage() {
            return this;
        }

        public Pl partner;

        public Pl getCharacterById(int id) {
            return partner;
        }
    }

    public static final class World0 {
        public int relationshipId = 77;
        public final List<Ch> channels = new ArrayList<>();
        public Ch current;

        public int getRelationshipId(int playerId) {
            return relationshipId;
        }

        public Ch getPlayerStorage() {
            return current;
        }

        public List<Ch> getChannels() {
            return channels;
        }
    }

    public static final class Client0 {
        final World0 world;
        final Ch channel;

        Client0(World0 world, Ch channel) {
            this.world = world;
            this.channel = channel;
        }

        public World0 getWorldServer() {
            return world;
        }

        public Ch getChannelServer() {
            return channel;
        }
    }

    public static final class Cm {
        final Log log;
        final Pl player;
        final Client0 client;
        public int mapId = 680000000;
        public Eim eim;
        public int meso = 10_000_000;

        Cm(Log log, Pl player, Client0 client) {
            this.log = log;
            this.player = player;
            this.client = client;
        }

        public int getMapId() {
            return mapId;
        }

        public Eim getEventInstance() {
            return eim;
        }

        public Client0 getClient() {
            return client;
        }

        public Pl getPlayer() {
            return player;
        }

        public Map0 getMap() {
            return player.map;
        }

        public boolean haveItem(int item) {
            return player.items.getOrDefault(item, 0) > 0;
        }

        public boolean haveItem(int item, int qty) {
            return player.items.getOrDefault(item, 0) >= qty;
        }

        public boolean canHold(int item, int qty) {
            return player.hasSpace;
        }

        public boolean canHold(int item) {
            return player.hasSpace;
        }

        public List<Object> getUnclaimedMarriageGifts() {
            return player.gifts;
        }

        public void gainItem(int item, int qty) {
            player.gainItem(item, qty);
        }

        public void gainItem(int item, int qty, boolean randomStats, boolean showMessage, long expires) {
            player.gainItem(item, qty, randomStats, showMessage, expires);
        }

        public int getMeso() {
            return meso;
        }

        public void gainMeso(int amount) {
            meso += amount;
            log.events.add("meso " + amount);
        }

        public void sendOk(String text) {
            log.lastText = text;
            log.events.add("ok");
        }

        public void sendSimple(String text) {
            log.lastText = text;
        }

        public void sendYesNo(String text) {
            log.lastText = text;
        }

        public void warp(int map, int portal) {
            log.events.add("warp " + map);
        }

        public void dispose() {
            log.disposed = true;
        }
    }

    /** A couple standing together in Amoria on channel 1, holding an engagement token. */
    static final class Couple {
        final Log log = new Log();
        final Map0 map = new Map0();
        final Pl player = new Pl(log, "A", 11, 22, map);
        final Pl partner = new Pl(log, "B", 22, 11, map);
        final Ch here = new Ch();
        final World0 world = new World0();
        final Cm cm;

        Couple() {
            here.id = 1;
            here.partner = partner;
            world.current = here;
            world.channels.add(here);
            cm = new Cm(log, player, new Client0(world, here));
            player.items.put(4031357, 1); // engagement token
        }
    }
}
