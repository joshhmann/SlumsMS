package net.server.channel.handlers;

import client.Character;
import client.Client;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import server.monsterbook.DropSearchService;
import server.monsterbook.MonsterBookPackets;
import soloMapling.ArtificialPlayer.BotHelpers;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CP 0x3734 MONSTER_BOOK_QUERY from the slums.dll Monster Book (ticket 19). Read-only: it never changes the
 * character, so it can answer at any time. Bots have no book and are ignored.
 *
 * <pre>
 * 0  int mobId    the mob's drop table, for the % under each Dropping icon
 * 1  str query    item-name search (at least 3 characters)
 * 2  int itemId   the carded mobs that drop this item
 * </pre>
 *
 * <p>Each answer comes from {@link DropSearchService}'s in-memory indexes, never a database query. A client
 * is still held to {@link #MAX_QUERIES_PER_SECOND} so a stuck key cannot flood the channel with replies.
 */
public final class MonsterBookQueryHandler extends AbstractPacketHandler {
    static final int MAX_QUERIES_PER_SECOND = 20;

    private final DropSearchService service;
    /** Per character: {window start in ms, queries in that window}. One small entry per player who opened the book. */
    private final Map<Integer, long[]> windows = new ConcurrentHashMap<>();

    public MonsterBookQueryHandler() {
        this(DropSearchService.getInstance());
    }

    MonsterBookQueryHandler(DropSearchService service) {
        this.service = service;
    }

    @Override
    public void handlePacket(InPacket p, Client c) {
        Character chr = c.getPlayer();
        if (chr == null || BotHelpers.isBot(chr) || !allow(chr.getId(), System.currentTimeMillis())) {
            return;
        }
        DropSearchService.Rates rates = DropSearchService.Rates.of(chr);
        int type = p.readByte();
        switch (type) {
            case MonsterBookPackets.TYPE_DROP_TABLE -> {
                int mobId = p.readInt();
                c.sendPacket(MonsterBookPackets.dropTable(mobId, service.mobDropChances(rates, mobId)));
            }
            case MonsterBookPackets.TYPE_ITEM_HITS -> {
                String query = p.readString();
                String trimmed = query == null ? "" : query.trim();
                c.sendPacket(MonsterBookPackets.itemHits(trimmed, service.findBookItems(trimmed)));
            }
            case MonsterBookPackets.TYPE_ITEM_DROPPERS -> {
                int itemId = p.readInt();
                LinkedHashMap<Integer, Integer> droppers = service.itemDroppers(rates, itemId);
                c.sendPacket(MonsterBookPackets.itemDroppers(itemId, droppers));
            }
            default -> {
                // unknown type: no reply rather than a malformed one
            }
        }
    }

    boolean allow(int characterId, long now) {
        long[] w = windows.computeIfAbsent(characterId, k -> new long[]{now, 0});
        synchronized (w) {
            if (now - w[0] >= 1000) {
                w[0] = now;
                w[1] = 0;
            }
            return ++w[1] <= MAX_QUERIES_PER_SECOND;
        }
    }
}
