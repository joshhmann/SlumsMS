package soloMapling.server.EventMessageSystem;

import client.Character;
import client.inventory.Item;
import server.maps.MapleMap;

import static soloMapling.DebugUtilities.debugprint;

public class GameEvent {
    private static final java.util.concurrent.atomic.AtomicInteger nextId = new java.util.concurrent.atomic.AtomicInteger(1);

    private final int id;
    private final long timestamp;
    private final Character mapleCharacter;
    private final int world;
    private final int channel;
    private final MapleMap map;
    private final String playerName;
    private final int playerId;
    private final EventType type;
    private final String message;
    private final Item item;
    private final Boolean pass;
    private final int detail;

    public GameEvent(Character mapleCharacter,
                     EventType type, String message, Item item, Boolean pass) {
        this(mapleCharacter, type, message, item, pass, 0);
    }

    /** {@code detail}: a number the event type defines (a boss's level, a rare drop's base chance). */
    public GameEvent(Character mapleCharacter,
                     EventType type, String message, Item item, Boolean pass, int detail) {
        this.id = nextId.getAndIncrement();
        this.timestamp = System.currentTimeMillis();
        this.mapleCharacter = mapleCharacter;
        this.world = mapleCharacter.getWorld();
        this.channel = mapleCharacter.getMap().getChannelServer().getId();
        this.map = mapleCharacter.getMap();
        this.playerName = mapleCharacter.getName();
        this.playerId = mapleCharacter.getId();

        this.type = type;
        this.message = message;
        this.item = item;
        this.pass = pass;
        this.detail = detail;
    }

    public int getId() { return id; }
    public long getTimestamp() { return timestamp; }
    public Character getMapleCharacter() { return mapleCharacter; }
    public int getWorld() { return world; }
    public int getChannel() { return channel; }
    public MapleMap getMap() { return map; }
    public String getPlayerName() { return playerName; }
    public int getPlayerId() { return playerId; }
    public EventType getType() { return type; }
    public String getMessage() { return message; }
    public Item getItem() { return item; }
    public Boolean getPass() { return pass; }
    public int getDetail() { return detail; }
    public void printDescription() {
        debugprint("GameEvent: ", id, mapleCharacter, type, message, item, pass);
    }
}
