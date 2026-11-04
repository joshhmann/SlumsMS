package soloMapling.ArtificialPlayer.BotMessagingSystem;

import client.Character;
import server.maps.MapleMap;

import java.util.concurrent.atomic.AtomicInteger;

public class ChatMessage {
    private final Character sender;
    private final String content;
    private MapleMap map;
    private final long timestamp;
    private final AtomicInteger dispatchFailures = new AtomicInteger();

    public ChatMessage(Character sender, String content) {
        this.sender = sender;
        this.content = content;
        this.map = this.sender.getMap();
        this.timestamp = System.currentTimeMillis();
    }

    public Character getSender() {
        return sender;
    }

    public String getContent() {
        return content;
    }

    protected MapleMap getMap() {
        return map;
    }

    protected long getTimestamp() {
        return timestamp;
    }

    /** Counts a delivery of this line that threw, and returns how many have now failed ({@link Dispatcher#drain}). */
    int noteDispatchFailure() {
        return dispatchFailures.incrementAndGet();
    }
}