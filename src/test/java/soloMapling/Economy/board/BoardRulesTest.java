package soloMapling.Economy.board;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class BoardRulesTest {
    static final int GLOVE = 2040804, ORE = 4004000, SWORD = 1302000, QUEST = 4031013;
    final FakeBoardWorld w = new FakeBoardWorld().account(7, "Momo", "MomoAlt").account(8, "Pip")
            .item(GLOVE, "Gloves for ATT 60%").item(ORE, "Power Crystal Ore").item(SWORD, "Sword").item(QUEST, "Dark Marble");

    Board.Post post(String id, int account, String character, int item, int qty, long price, long created) {
        return new Board.Post(id, account, character, 0, Board.Kind.WTB, item, qty, price, created, 0, false);
    }

    Map<String, String> reasons(List<Board.Post> posts, Set<String> removed) {
        return BoardRules.validate(posts, w, removed, w.now).rejected().stream()
                .collect(Collectors.toMap(Board.Rejection::id, Board.Rejection::reason, (a, b) -> a + "|" + b));
    }

    @Test
    void eachRuleRefusesWithItsReason() {
        w.untradeable.add(QUEST);
        w.owners.put("botty", new Board.Owner(1_000_000_123, 7, "Botty"));
        long t = w.now - 1000;
        Map<String, String> r = reasons(List.of(
                post("noacct", 99, "Momo", GLOVE, 1, 100, t),
                post("nochar", 7, "Nobody", GLOVE, 1, 100, t),
                post("other", 7, "Pip", GLOVE, 1, 100, t),
                post("bot", 7, "Botty", GLOVE, 1, 100, t),
                post("noitem", 7, "Momo", 2999999, 1, 100, t),
                post("quest", 7, "Momo", QUEST, 1, 100, t),
                post("qty0", 7, "Momo", ORE, 0, 100, t),
                post("qtybig", 7, "Momo", ORE, 40_000, 100, t),
                post("dear", 7, "Momo", SWORD, 1, 3_000_000_000L, t),
                post("free", 7, "Momo", ORE, 1, 0, t),
                post("huge", 7, "Momo", ORE, 100, 30_000_000, t),
                post("future", 7, "Momo", ORE, 1, 100, w.now + TimeUnit.HOURS.toMillis(1)),
                post("old", 7, "Momo", ORE, 1, 100, w.now - TimeUnit.DAYS.toMillis(3) - 1),
                post("gone", 7, "Momo", ORE, 1, 100, t)), Set.of("gone"));
        assertEquals("no such account", r.get("noacct"));
        assertEquals("no such character", r.get("nochar"));
        assertEquals("character is not on this account", r.get("other"));
        assertEquals("no such character", r.get("bot"));
        assertEquals("no such item", r.get("noitem"));
        assertEquals("item can't be traded", r.get("quest"));
        assertEquals("qty must be 1 to 32767", r.get("qty0"));
        assertEquals("qty must be 1 to 32767", r.get("qtybig"));
        assertEquals("price must be at most 2147483647", r.get("dear"));
        assertEquals("price must be above 0", r.get("free"));
        assertTrue(r.get("huge").startsWith("qty x price over "), r.get("huge"));
        assertEquals("created in the future", r.get("future"));
        assertEquals("expired", r.get("old"));
        assertEquals("removed by a GM", r.get("gone"));
    }

    @Test
    void aGoodPostIsShownUnderTheGamesSpellingWithItsCharacterAndAtMostThreeDays() {
        long created = w.now - 1000;
        Board.Post p = new Board.Post("p1", 7, "momoalt", 0, Board.Kind.WTS, ORE, 5, 2000, created,
                created + TimeUnit.DAYS.toMillis(10), false);
        BoardRules.Result r = BoardRules.validate(List.of(p), w, Set.of(), w.now);
        assertEquals(List.of(), r.rejected());
        Board.Post open = r.open().get(0);
        assertEquals("MomoAlt", open.character());
        assertEquals(71, open.characterId());
        assertEquals(created + BoardRules.LIFE_MS, open.expiresAt(), "the site's 10 days are cut to 3");
        Board.Post shorter = p.withExpiry(created + 1000 + TimeUnit.HOURS.toMillis(1));
        assertEquals(shorter.expiresAt(), BoardRules.expiry(shorter), "an earlier expiry from the site stands");
    }

    @Test
    void anAccountKeepsItsFiveOldestPostsAndDuplicatesAreRefused() {
        List<Board.Post> posts = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            posts.add(post("a" + i, 7, i % 2 == 0 ? "Momo" : "MomoAlt", ORE, 1, 100, w.now - 10_000 + i));
        }
        posts.add(post("b0", 8, "Pip", ORE, 1, 100, w.now - 50));
        posts.add(post("a0", 8, "Pip", ORE, 1, 100, w.now - 50));
        BoardRules.Result r = BoardRules.validate(posts, w, Set.of(), w.now);
        assertEquals(List.of("a0", "a1", "a2", "a3", "a4", "b0"), r.open().stream().map(Board.Post::id).sorted().toList());
        Map<String, String> why = r.rejected().stream().collect(Collectors.toMap(Board.Rejection::id,
                Board.Rejection::reason, (a, b) -> a + "|" + b));
        assertEquals("over 5 open posts", why.get("a5"));
        assertEquals("over 5 open posts", why.get("a6"));
        assertEquals("duplicate id", why.get("a0"));
    }

    @Test
    void aDatabaseFailureIsNotTakenAsARejection() {
        FakeBoardWorld broken = new FakeBoardWorld() {
            @Override
            public boolean accountExists(int account) {
                throw new IllegalStateException("db down");
            }
        };
        assertThrows(IllegalStateException.class, () -> BoardRules.validate(
                List.of(post("x", 7, "Momo", ORE, 1, 1, w.now)), broken, Set.of(), w.now));
    }
}
