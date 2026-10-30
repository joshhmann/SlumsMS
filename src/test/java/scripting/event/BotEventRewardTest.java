package scripting.event;

import client.Character;
import client.Client;
import client.inventory.Item;
import net.server.channel.Channel;
import net.server.world.World;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.ArtificialPlayer.BotHoldings;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.1 KPQ automation: bots now claim PQ prizes themselves at the reward NPC (Cloto after stage 5).
 * <ul>
 *   <li>A population bot shares a headless client that has no player: the prize must go into the bot's own
 *   inventory through its holdings, never through the client (which would give it to nobody, or throw).</li>
 *   <li>An owned alt claims its own prize and also gets one through its owner's claim (owner 2026-10-26): whichever
 *   comes first gives it, never both.</li>
 * </ul>
 */
class BotEventRewardTest {

    /** setEventRewards ignores levels above MAX_EVENT_LEVELS (0 when the test config does not set it). */
    private static void withEventLevels(Runnable r) {
        int was = config.YamlConfig.config.server.MAX_EVENT_LEVELS;
        config.YamlConfig.config.server.MAX_EVENT_LEVELS = Math.max(was, 8);
        try {
            r.run();
        } finally {
            config.YamlConfig.config.server.MAX_EVENT_LEVELS = was;
        }
    }

    private static EventInstanceManager realInstance() {
        EventManager em = mock(EventManager.class);
        World world = mock(World.class);
        Channel ch = mock(Channel.class);
        when(world.getId()).thenReturn(1);
        when(ch.getId()).thenReturn(1);
        when(em.getWorldServer()).thenReturn(world);
        when(em.getChannelServer()).thenReturn(ch);
        return new EventInstanceManager(em, "Kerning1");
    }

    @Test
    void aBotOnTheSharedClientGetsItsPrizeThroughItsHoldings() {
        EventInstanceManager eim = realInstance();
        withEventLevels(() -> eim.setEventRewards(1, List.of(2000001), List.of(80))); // one prize: 80 Orange Potions
        Character bot = mock(Character.class);
        Client shared = mock(Client.class);
        when(shared.getPlayer()).thenReturn(null); // the shared bot client has no player
        when(bot.getClient()).thenReturn(shared);
        when(bot.hasEmptySlot(org.mockito.ArgumentMatchers.anyByte())).thenReturn(true);
        try (MockedStatic<BotHoldings> holdings = mockStatic(BotHoldings.class)) {
            holdings.when(() -> BotHoldings.newItem(anyInt(), anyInt())).thenCallRealMethod();
            holdings.when(() -> BotHoldings.grantItem(eq(bot), any(Item.class))).thenReturn(true);
            assertTrue(eim.giveEventReward(bot));
            holdings.verify(() -> BotHoldings.grantItem(eq(bot),
                    argThat(i -> i.getItemId() == 2000001 && i.getQuantity() == 80)));
        }
        verify(bot, never()).getAbstractPlayerInteraction();
    }

    @Test
    void aBotWithAFullBagIsRefusedLikeAPlayer() {
        EventInstanceManager eim = realInstance();
        withEventLevels(() -> eim.setEventRewards(1, List.of(2000001), List.of(80)));
        Character bot = mock(Character.class);
        Client shared = mock(Client.class);
        when(bot.getClient()).thenReturn(shared);
        when(bot.hasEmptySlot(org.mockito.ArgumentMatchers.anyByte())).thenReturn(true);
        try (MockedStatic<BotHoldings> holdings = mockStatic(BotHoldings.class)) {
            holdings.when(() -> BotHoldings.newItem(anyInt(), anyInt())).thenCallRealMethod();
            holdings.when(() -> BotHoldings.grantItem(eq(bot), any(Item.class))).thenReturn(false);
            assertFalse(eim.giveEventReward(bot), "Cloto says: make room first");
        }
    }

    private static Character player(int id) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        return chr;
    }

    @Test
    void anAltsPrizeComesOnceWhetherItOrItsOwnerClaimsFirst() throws Exception {
        Character owner = player(1);
        Character alt = player(10);
        when(alt.isOwnedAlt()).thenReturn(true);
        when(alt.getOwnedAltOwner()).thenReturn(owner);

        EventInstanceManager eim = mock(EventInstanceManager.class, CALLS_REAL_METHODS);
        Field f = EventInstanceManager.class.getDeclaredField("altsRewarded");
        f.setAccessible(true);
        f.set(eim, ConcurrentHashMap.<String>newKeySet());
        doReturn(List.of(owner, alt)).when(eim).getPlayers();
        doReturn(true).when(eim).giveOwnEventRewardOnce(any(), anyInt());

        assertTrue(eim.giveEventReward(alt), "the alt claims at the NPC itself");
        assertTrue(eim.giveEventReward(owner), "then the owner claims");
        verify(eim, times(1)).giveOwnEventRewardOnce(alt, 1);
        verify(eim, times(1)).giveOwnEventRewardOnce(owner, 1);

        // the other order: the owner's claim gives the alt its prize, the alt's own claim then gives nothing more
        EventInstanceManager eim2 = mock(EventInstanceManager.class, CALLS_REAL_METHODS);
        f.set(eim2, ConcurrentHashMap.<String>newKeySet());
        doReturn(List.of(owner, alt)).when(eim2).getPlayers();
        doReturn(true).when(eim2).giveOwnEventRewardOnce(any(), anyInt());
        assertTrue(eim2.giveEventReward(owner));
        assertTrue(eim2.giveEventReward(alt), "still true: the script then sends the alt on to the bonus");
        verify(eim2, times(1)).giveOwnEventRewardOnce(alt, 1);

        @SuppressWarnings("unchecked")
        Set<String> seen = (Set<String>) f.get(eim2);
        assertEquals(Set.of("10:1"), seen, "players are not tracked, only alts");
    }
}
