package net.server.channel.handlers;

import client.Character;
import client.Client;
import client.MonsterBook;
import net.packet.InPacket;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The cover a player picks has to be a card the player found. Accepting any {@code id / 10000 == 238} let a client
 * put a card on the book it did not own, and an id with no {@code monstercarddata} row then threw while the next
 * character-info packet was built - which kept the character out of its map.
 */
class MonsterBookCoverHandlerTest {

    private static final int OWNED_COVER = 2380005;

    /** The book's cards are only ever filled by a pickup, so a test puts one there directly. */
    @SuppressWarnings("unchecked")
    private static void own(Character chr, int cardid) {
        try {
            Field cards = MonsterBook.class.getDeclaredField("cards");
            cards.setAccessible(true);
            ((Map<Integer, Integer>) cards.get(chr.getMonsterBook())).put(cardid, 1);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("MonsterBook.cards is not reachable by reflection", e);
        }
    }

    private static Character characterWith(Client c) {
        Character chr = Character.getDefault(c);
        try {
            // The book is built while a character is loaded from the database; getDefault has none.
            Field monsterbook = Character.class.getDeclaredField("monsterbook");
            monsterbook.setAccessible(true);
            monsterbook.set(chr, new MonsterBook());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Character.monsterbook is not reachable by reflection", e);
        }
        when(c.getPlayer()).thenReturn(chr);
        return chr;
    }

    private static InPacket choosing(int id) {
        InPacket p = mock(InPacket.class);
        when(p.readInt()).thenReturn(id);
        return p;
    }

    @Test
    void aCoverThePlayerDoesNotOwnIsRefused() {
        Client c = mock(Client.class);
        Character chr = characterWith(c);

        new MonsterBookCoverHandler().handlePacket(choosing(OWNED_COVER), c);

        assertEquals(0, chr.getMonsterBookCover(), "a card the book does not hold cannot become the cover");
        verify(c, never()).sendPacket(any());
    }

    @Test
    void aCoverThePlayerOwnsIsAccepted() {
        Client c = mock(Client.class);
        Character chr = characterWith(c);
        own(chr, OWNED_COVER);

        new MonsterBookCoverHandler().handlePacket(choosing(OWNED_COVER), c);

        assertEquals(OWNED_COVER, chr.getMonsterBookCover(), "a card the book holds is a valid cover");
        verify(c, times(1)).sendPacket(any());
    }

    @Test
    void theBareBookNeedsNoCard() {
        Client c = mock(Client.class);
        Character chr = characterWith(c);
        chr.setMonsterBookCover(OWNED_COVER);

        new MonsterBookCoverHandler().handlePacket(choosing(0), c);

        assertEquals(0, chr.getMonsterBookCover(), "choosing no cover is always allowed");
        verify(c, times(1)).sendPacket(any());
    }
}
