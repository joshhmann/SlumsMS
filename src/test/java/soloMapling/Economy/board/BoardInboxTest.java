package soloMapling.Economy.board;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BoardInboxTest {

    @Test
    void postsReadCamelCaseOrSnakeCaseAndIsoOrEpochTimes() {
        BoardInbox.Posts p = BoardInbox.posts("""
                {"schema":1,"posts":[
                 {"id":"p1","account":7,"character":"Momo","kind":"wtb","itemId":2040804,"qty":1,"price":3000000,
                  "createdAt":"2026-10-26T07:00:00Z","expiresAt":"2026-10-29T07:00:00Z"},
                 {"id":42,"account":"8","character":"Pip","kind":"WTS","item_id":4004000,"qty":10,"price":2000,
                  "created_at":1793000000000},
                 {"id":"bad","account":7,"character":"Momo","kind":"BUY","itemId":1,"qty":1,"price":1,"createdAt":"x"},
                 {"account":7},
                 "junk"]}""");
        assertEquals(2, p.posts().size());
        Board.Post a = p.posts().get(0);
        assertEquals(Board.Kind.WTB, a.kind());
        assertEquals(Instant.parse("2026-10-26T07:00:00Z").toEpochMilli(), a.createdAt());
        assertEquals(Instant.parse("2026-10-29T07:00:00Z").toEpochMilli(), a.expiresAt());
        Board.Post b = p.posts().get(1);
        assertEquals("42", b.id());
        assertEquals(8, b.account());
        assertEquals(4004000, b.itemId());
        assertEquals(0, b.expiresAt(), "no expiry from the site: the rules give 3 days");
        assertEquals(List.of(new Board.Rejection("bad", "malformed: kind"), new Board.Rejection("", "malformed: id"),
                new Board.Rejection("", "malformed: not an object")), p.malformed());
    }

    @Test
    void aBareArrayOfPostsAndAnEmptyFileWork() {
        assertEquals(0, BoardInbox.posts("[]").posts().size());
        assertEquals(0, BoardInbox.posts("{\"schema\":1}").posts().size());
        assertThrows(IllegalArgumentException.class, () -> BoardInbox.posts("{not json"));
    }

    @Test
    void alertsReadTheSitesShapeKeyedByAccountId() {
        List<Board.AccountAlerts> a = BoardInbox.alerts("""
                {"schema":1,"generated_at":"2026-10-26T07:00:00Z","accounts":{
                 "7":{"watch":[2040804],"alerts":[{"id":"a1","itemId":2040804,"dir":"below","price":2500000,"armed":true}]},
                 "8":{"watch":[],"alerts":[{"id":"a2","itemId":4004000,"dir":"above","price":5000,"armed":false}]},
                 "nope":{"alerts":[]}}}""");
        assertEquals(2, a.size());
        assertEquals(7, a.get(0).account());
        assertEquals(List.of(2040804), a.get(0).watchlist());
        assertEquals(Board.Dir.BELOW, a.get(0).alerts().get(0).dir());
        assertEquals(8, a.get(1).alerts().get(0).account());
        assertEquals(Board.Dir.ABOVE, a.get(1).alerts().get(0).dir());
        assertFalse(a.get(1).alerts().get(0).armed());
    }

    @Test
    void alertsReadDirectionEitherWayAndSkipBrokenRows() {
        List<Board.AccountAlerts> a = BoardInbox.alerts("""
                {"schema":1,"accounts":[
                 {"account":7,"watchlist":[2040804,4004000],"alerts":[
                   {"id":"a1","itemId":2040804,"direction":"below","price":2500000,"armed":true},
                   {"id":"a2","itemId":4004000,"above":5000,"armed":false,"armedAt":"2026-10-26T07:00:00Z"},
                   {"id":"a3","itemId":4004000,"direction":"sideways","price":5},
                   {"id":"a4","itemId":4004000,"direction":"above","price":0}]},
                 {"account":0,"alerts":[]}]}""");
        assertEquals(1, a.size());
        assertEquals(List.of(2040804, 4004000), a.get(0).watchlist());
        assertEquals(2, a.get(0).alerts().size());
        Board.Alert a1 = a.get(0).alerts().get(0), a2 = a.get(0).alerts().get(1);
        assertEquals(Board.Dir.BELOW, a1.dir());
        assertTrue(a1.armed());
        assertEquals(Board.Dir.ABOVE, a2.dir());
        assertEquals(5000, a2.price());
        assertFalse(a2.armed());
        assertEquals(Instant.parse("2026-10-26T07:00:00Z").toEpochMilli(), a2.armedAt());
    }
}
