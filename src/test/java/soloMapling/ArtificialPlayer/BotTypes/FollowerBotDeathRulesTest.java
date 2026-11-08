package soloMapling.ArtificialPlayer.BotTypes;

import client.BotClient;
import client.Character;
import client.Job;
import client.inventory.InventoryType;
import net.server.world.Party;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import scripting.event.EventInstanceManager;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotDeaths;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotPartySystem.ExpeditionBotsFixture;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.server.SoloMaplingUtilities;

import java.awt.Point;
import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.7 alt-revive-respawn, party regulars: a regular in a boss run dies where it stands and is not moved by any
 * follow path. With the switches the server ships (regulars off) its tick is today's tick; with
 * {@code -Dslums.companion.deathrules.regulars=true} it waits for its revive beside its leader instead.
 */
class FollowerBotDeathRulesTest {

    private static final int LEADER = 4_242;
    private static final int BOT = 1_000_000_344;

    private MockedStatic<GCMovement> movement;
    private MockedStatic<SoloMaplingUtilities> world;
    private Character leader;
    private Character botChr;
    private FollowerBot follower;
    private ExpeditionBotsFixture.Joined joined;

    @BeforeEach
    void stage() {
        System.clearProperty("slums.companion.deathrules.regulars");
        movement = Mockito.mockStatic(GCMovement.class);
        world = Mockito.mockStatic(SoloMaplingUtilities.class);
        MapleMap altar = mock(MapleMap.class);
        when(altar.getId()).thenReturn(280_030_000);
        when(altar.getAllMonsters()).thenReturn(new java.util.ArrayList<>());
        when(altar.getCharacters()).thenReturn(java.util.List.of());
        leader = mock(Character.class);
        when(leader.getId()).thenReturn(LEADER);
        when(leader.getName()).thenReturn("Josh");
        when(leader.getMap()).thenReturn(altar);
        when(leader.getPosition()).thenReturn(new Point(0, 0));
        botChr = mock(Character.class, RETURNS_DEEP_STUBS);
        when(botChr.getId()).thenReturn(BOT);
        when(botChr.getName()).thenReturn("Clock4");
        when(botChr.getMap()).thenReturn(altar);
        when(botChr.getMapId()).thenReturn(280_030_000);
        when(botChr.getPosition()).thenReturn(new Point(-30, 0));
        when(botChr.getPartyId()).thenReturn(77);
        when(botChr.getLevel()).thenReturn(70);
        when(botChr.getJob()).thenReturn(Job.BISHOP);
        when(botChr.getSkillLevel(anyInt())).thenReturn(0);
        when(botChr.getInventory(InventoryType.EQUIPPED).getItem(anyShort())).thenReturn(null);
        when(botChr.getParty()).thenReturn(mock(Party.class));
        when(botChr.getTrade()).thenReturn(null);
        when(botChr.getClient()).thenReturn(mock(BotClient.class, RETURNS_DEEP_STUBS));
        when(botChr.getClient().getChannelServer().getPlayerStorage().getCharacterById(LEADER)).thenReturn(leader);
        when(botChr.getClient().getChannelServer().getPlayerStorage().getCharacterById(BOT)).thenReturn(null);
        net.server.PlayerStorage storage = mock(net.server.PlayerStorage.class);
        when(storage.getCharacterById(LEADER)).thenReturn(leader);
        net.server.world.World w = mock(net.server.world.World.class);
        when(w.getPlayerStorage()).thenReturn(storage);
        world.when(SoloMaplingUtilities::world).thenReturn(w);
        joined = ExpeditionBotsFixture.join(altar, LEADER, BOT);
        when(botChr.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        follower = new FollowerBot(botChr);
        follower.followForTest(LEADER);
        follower.setRunning(true);
        CharacterStorage.addActiveBot(BOT, follower);
    }

    @AfterEach
    void unstage() throws Exception {
        joined.release();
        CharacterStorage.removeActiveBot(BOT);
        BotDeaths.clearBot(BOT);
        lying().remove(BOT);
        soloMapling.ArtificialPlayer.BotPartySystem.CompanionBossRevive.runEnded(BOT);
        System.clearProperty("slums.companion.deathrules.regulars");
        world.close();
        movement.close();
    }

    @SuppressWarnings("unchecked")
    static Map<Integer, Long> lying() throws Exception {
        Field f = BotDeaths.class.getDeclaredField("lyingUntil");
        f.setAccessible(true);
        return (Map<Integer, Long>) f.get(null);
    }

    @Test
    void withTheSwitchesTheServerShipsItsTickIsTodaysTick() throws Exception {
        lying().put(BOT, 0L); // dead in the boss run
        follower.updateState();
        follower.updateState(); // IDLE -> RUNNING -> the follow tick
        // today: nothing in a follower's tick knows it is dead; it follows, and only GCMovement's own hold keeps it put
        movement.verify(() -> GCMovement.follow(botChr, leader), atLeastOnce());
        verify(botChr, never()).applyHpMpChange(anyInt(), anyInt(), anyInt());
        assertTrue(BotDeaths.isDead(BOT), "left exactly as it was: the caller's tick decides everything");
    }

    @Test
    void withTheRegularsSwitchOnItWaitsInsteadOfFollowing() throws Exception {
        System.setProperty("slums.companion.deathrules.regulars", "true");
        lying().put(BOT, 0L);
        follower.updateState();
        follower.updateState();
        assertTrue(soloMapling.ArtificialPlayer.BotPartySystem.CompanionBossRevive.waiting(botChr),
                "the death rules own the tick now: it is waiting for its own revive");
        verify(botChr, never()).applyHpMpChange(anyInt(), anyInt(), anyInt()); // not yet: its ten seconds are not up
        verify(leader).yellowMessage(contains("Clock4 is down, back in 10s"));
        movement.verify(() -> GCMovement.follow(botChr, leader), never()); // no follow session while it waits
        assertFalse(follower.isFighting());
        assertTrue(BotDeaths.isDead(BOT));
    }
}
