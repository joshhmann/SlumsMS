package soloMapling.ArtificialPlayer.BotAffinitySystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.maps.MapleMap;
import server.maps.MapObjectType;
import soloMapling.ArtificialPlayer.BotStore;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
 * Auto-QA rv-2 read "0 of 3 called bots took mobs" for a whole KS war: ksPick only credited a take when the mob it
 * chose was NOT the target the grind brain had already picked, and a helper asked to KS the player's mobs usually has
 * the player's nearest mob as its pick already - so every real take was silent. These drive the decision directly
 * with a real character and a mocked map, so the credit (and the once-per-mob rule) stays pinned.
 */
class KsSpreeCreditTest {

    static final int PLAYER = 42;
    static final int MAP = 104040000;

    final AtomicLong clock = new AtomicLong(1_000_000_000_000L);

    Character bot;
    Character player;
    Monster mob;

    @BeforeEach
    void setUp() {
        System.setProperty("solomapling.affinity", "true");
        System.setProperty("solomapling.affinity.hostility", "true");
        BotAffinity.useForTest(new AffinityStore.Memory(), Runnable::run, clock::get, id -> true);
        AffinityReactions.resetForTest();

        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(MAP);
        player = player();
        player.setMap(map); // the spree is started with the player's map id
        mob = mob();
        when(map.getCharacterById(PLAYER)).thenReturn(player);
        when(map.getMapObjectsInRange(any(), anyDouble(), eq(List.of(MapObjectType.MONSTER))))
                .thenReturn(List.of(mob));

        bot = Character.getDefault(new BotClient(0, 0));
        bot.setID(BotStore.BOT_ID_BASE + 7);
        bot.setName("helper");
        bot.setMap(map);
        bot.setPosition(new Point(10, 10));
    }

    @AfterEach
    void tearDown() {
        AffinityReactions.resetForTest();
        System.clearProperty("solomapling.affinity");
        System.clearProperty("solomapling.affinity.hostility");
    }

    private static Character player() {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(PLAYER);
        chr.setName("AutoQaOne");
        return chr;
    }

    private static Monster mob() {
        Monster m = mock(Monster.class);
        when(m.getObjectId()).thenReturn(9001);
        when(m.getName()).thenReturn("Stump");
        when(m.getPosition()).thenReturn(new Point(20, 10));
        when(m.isAlive()).thenReturn(true);
        when(m.isAttackedBy(any(Character.class))).thenAnswer(call -> ((Character) call.getArgument(0)).getId() == PLAYER);
        return m;
    }

    /** The bug: the brain already picked the player's mob, so the take was never counted. */
    @Test
    void aMobTheHelperHasPickedAlreadyCountsAsATake() {
        AffinityReactions.startWarSpree(bot, player, 90_000, 5);

        Monster chosen = AffinityReactions.ksPick(bot, mob);

        assertEquals(mob, chosen, "it fights the player's mob");
        assertTrue(AffinityReactions.spreeOf(bot.getId()).contains("4 mobs left"), "the take is credited");
    }

    /** One mob is one take: asking again for the same mob must not spend another of the spree's mobs. */
    @Test
    void aSecondPickOfTheSameMobIsNotCreditedTwice() {
        AffinityReactions.startWarSpree(bot, player, 90_000, 5);

        AffinityReactions.ksPick(bot, mob);
        AffinityReactions.ksPick(bot, mob);

        assertTrue(AffinityReactions.spreeOf(bot.getId()).contains("4 mobs left"));
    }

    /**
     * A helper with nothing to take at hand heads for the closest mob around its rival instead of drifting off to
     * its own ledge, which is how a whole war could pass with the helpers 500 px below the player.
     */
    @Test
    void aHelperWithNothingToTakeWalksBackToItsRival() {
        when(mob.isAttackedBy(any(Character.class))).thenReturn(false); // the player has not touched it
        player.setPosition(new Point(400, 10));
        when(mob.getPosition()).thenReturn(new Point(430, 10)); // but it stands next to the player
        AffinityReactions.startWarSpree(bot, player, 90_000, 5);
        Monster own = mock(Monster.class); // the bot's own distant pick
        when(own.getObjectId()).thenReturn(9002);

        Monster chosen = AffinityReactions.ksPick(bot, own);

        assertEquals(mob, chosen, "it walks toward its rival instead of its own pick");
        assertTrue(AffinityReactions.spreeOf(bot.getId()).contains("5 mobs left"), "no take: nothing to credit");
    }

    /** A mob the player has not touched is not a take: the spree is untouched. */
    @Test
    void anotherPlayersMobIsNotATake() {
        when(mob.isAttackedBy(any(Character.class))).thenReturn(false);
        AffinityReactions.startWarSpree(bot, player, 90_000, 5);

        Monster chosen = AffinityReactions.ksPick(bot, mob);

        assertEquals(mob, chosen);
        assertTrue(AffinityReactions.spreeOf(bot.getId()).contains("5 mobs left"));
    }
}
