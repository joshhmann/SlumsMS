package server.reports;

import client.Character;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Ticket 131 (@bug context): what one real player did lately, for the bug report's "last steps". Three small rings
 * per character: the last {@link #MAPS} maps entered, the last {@link #NPCS} NPC talks and the last {@link #EVENTS}
 * server-side events (skill casts and attacks, item use, pickups, trades, shop buys, deaths). Repeats of the same
 * event (an attack spam with one skill) fold into one entry with a count, so a fight does not push out what happened
 * before it.
 * <p>
 * Memory: about 70 tiny records per online real player, nothing for bots (population bots never get a trail, see
 * {@link #of}). The hooks pass ids, not strings, so a call costs a field read and a lock on this character only;
 * names are looked up at report time.
 */
public final class PlayerTrail {
    public static final int MAPS = 10;
    public static final int NPCS = 10;
    public static final int EVENTS = 50;

    /** One entry. {@code note} is a short code-made word (route, partner name, map name), never player chat. */
    public record Entry(long time, String kind, int id, String note, int count) {
        Entry bump(long now) {
            return new Entry(now, kind, id, note, count + 1);
        }
    }

    private final ArrayDeque<Entry> maps = new ArrayDeque<>(MAPS);
    private final ArrayDeque<Entry> npcs = new ArrayDeque<>(NPCS);
    private final ArrayDeque<Entry> events = new ArrayDeque<>(EVENTS);

    /** The character's trail, made on first use; null for population bots (and for null). */
    public static PlayerTrail of(Character chr) {
        if (chr == null || chr.isBot()) {
            return null;
        }
        return chr.bugTrail();
    }

    public static void map(Character chr, int mapId, String mapName) {
        PlayerTrail t = of(chr);
        if (t != null) {
            long now = System.currentTimeMillis();
            t.recordMap(now, mapId, mapName);
            t.record(now, "warp", mapId, null); // the event stream keeps the order of warps among the rest
        }
    }

    public static void npc(Character chr, int npcId, String npcName) {
        PlayerTrail t = of(chr);
        if (t != null) {
            t.recordNpc(System.currentTimeMillis(), npcId, npcName);
        }
    }

    /** A server-side event: kind is one of skill, attack, use, pickup, trade, shop, death, warp. */
    public static void event(Character chr, String kind, int id, String note) {
        PlayerTrail t = of(chr);
        if (t != null) {
            t.record(System.currentTimeMillis(), kind, id, note);
        }
    }

    /** For tests and the hooks above. */
    public void record(long now, String kind, int id, String note) {
        add(events, EVENTS, new Entry(now, kind, id, note, 1), true);
    }

    public void recordMap(long now, int mapId, String name) {
        add(maps, MAPS, new Entry(now, "map", mapId, name, 1), false);
    }

    public void recordNpc(long now, int npcId, String name) {
        add(npcs, NPCS, new Entry(now, "npc", npcId, name, 1), false);
    }

    private synchronized void add(ArrayDeque<Entry> ring, int cap, Entry e, boolean fold) {
        Entry last = ring.peekLast();
        if (fold && last != null && last.kind().equals(e.kind()) && last.id() == e.id()
                && java.util.Objects.equals(last.note(), e.note())) {
            ring.pollLast();
            ring.addLast(last.bump(e.time()));
            return;
        }
        while (ring.size() >= cap) {
            ring.pollFirst();
        }
        ring.addLast(e);
    }

    public synchronized List<Entry> maps() {
        return new ArrayList<>(maps);
    }

    public synchronized List<Entry> npcs() {
        return new ArrayList<>(npcs);
    }

    public synchronized List<Entry> events() {
        return new ArrayList<>(events);
    }
}
