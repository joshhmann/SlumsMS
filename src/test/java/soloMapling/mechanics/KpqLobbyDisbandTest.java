package soloMapling.mechanics;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import net.server.Server;
import net.server.channel.Channel;
import net.server.world.Party;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.maps.MapManager;
import server.maps.MapleMap;
import scripting.event.EventManager;
import scripting.event.EventScriptManager;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * b156 (K7): a throw while the lobby is forming its party must not escape the lobby tick, and the party it already
 * made must be disbanded, leaving the bots free again. Driven through {@link KpqLobby#tick()}, the entry point that
 * exists with and without the fix, so the assertions are on what the lobby does, not on {@code start}'s signature.
 *
 * <p>The throw comes from {@link Party#joinParty}: Lakelis' talk and {@code MechanicsRuns.start} are reached only
 * after every member joined, so a party that fails to fill is the "throw after the party is made" of K7. The party is
 * modelled by {@link Character#getParty()}, which is the one thing {@code KpqLobby.free} looks at.
 */
class KpqLobbyDisbandTest {

    private String mechanicsFlag, lobbyFlag;
    private final List<Integer> registered = new ArrayList<>();

    @BeforeEach
    void switchesOn() {
        mechanicsFlag = System.getProperty(Mechanics.FLAG);
        lobbyFlag = System.getProperty(KpqLobby.FLAG);
        System.setProperty(Mechanics.FLAG, "true");
        System.setProperty(KpqLobby.FLAG, "true");
    }

    @AfterEach
    void restore() {
        if (mechanicsFlag == null) System.clearProperty(Mechanics.FLAG);
        else System.setProperty(Mechanics.FLAG, mechanicsFlag);
        if (lobbyFlag == null) System.clearProperty(KpqLobby.FLAG);
        else System.setProperty(KpqLobby.FLAG, lobbyFlag);
        for (int id : registered) {
            CharacterStorage.getAllBots().remove(id);
        }
        registered.clear();
    }

    /** A bot as the lobby sees it: in the recruit map, in the band, with room for coupons and a running brain. */
    private Character bot(int id, int level) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn("m" + id);
        when(c.isBot()).thenReturn(true);
        when(c.getLevel()).thenReturn(level);
        when(c.getHp()).thenReturn(500);
        Inventory etc = mock(Inventory.class);
        when(etc.getNumFreeSlot()).thenReturn((short) 5); // KpqLobby.bagRoom wants room for the stage's coupons
        when(c.getInventory(InventoryType.ETC)).thenReturn(etc);
        BotSM sm = mock(BotSM.class);
        when(sm.getRunning()).thenReturn(true);
        CharacterStorage.getAllBots().put(id, sm);
        registered.add(id);
        return c;
    }

    @Test
    void aThrowWhileThePartyIsMadeDisbandsItAndLeavesTheBotsFree() {
        Character leader = bot(9_801, 30);
        Character joined = bot(9_802, 25);
        Character throwing = bot(9_803, 22);

        // the lobby: one channel whose recruit map holds the three bots, its Kerning PQ free
        MapManager maps = mock(MapManager.class);
        MapleMap recruit = mock(MapleMap.class);
        when(maps.isMapLoaded(KpqStaging.RECRUIT_MAP)).thenReturn(true);
        when(maps.getMap(KpqStaging.RECRUIT_MAP)).thenReturn(recruit);
        when(recruit.getAllPlayers()).thenReturn(new ArrayList<>(List.of(leader, joined, throwing)));
        EventManager kpq = mock(EventManager.class);
        when(kpq.getInstances()).thenReturn(List.of());
        EventScriptManager scripts = mock(EventScriptManager.class);
        when(scripts.getEventManager(KpqStaging.EVENT)).thenReturn(kpq);
        Channel channel = mock(Channel.class);
        when(channel.getId()).thenReturn(1);
        when(channel.getWorld()).thenReturn(1);
        when(channel.getMapFactory()).thenReturn(maps);
        when(channel.getEventSM()).thenReturn(scripts);
        World world = mock(World.class);
        when(world.getChannels()).thenReturn(List.of(channel));
        Server server = mock(Server.class);
        when(server.getWorld(anyInt())).thenReturn(world);

        // the party the lobby makes, as Character.getParty() reports it
        Map<Integer, Party> partyOf = new HashMap<>();
        List<Integer> left = new ArrayList<>();
        Party party = mock(Party.class);
        when(party.getId()).thenReturn(7);
        for (Character c : List.of(leader, joined, throwing)) {
            when(c.getParty()).thenAnswer(inv -> partyOf.get(c.getId()));
        }

        try (MockedStatic<Server> servers = mockStatic(Server.class);
             MockedStatic<Party> parties = mockStatic(Party.class)) {
            servers.when(Server::getInstance).thenReturn(server);
            parties.when(() -> Party.createParty(leader, true)).thenAnswer(inv -> {
                assertNull(partyOf.put(leader.getId(), party), "the leader had no party yet");
                return true;
            });
            parties.when(() -> Party.joinParty(any(Character.class), anyInt(), anyBoolean())).thenAnswer(inv -> {
                Character c = inv.getArgument(0);
                if (c == throwing) {
                    throw new IllegalStateException("b156: joining the lobby party threw");
                }
                partyOf.put(c.getId(), party);
                return true;
            });
            parties.when(() -> Party.leaveParty(any(Party.class), any(Character.class))).thenAnswer(inv -> {
                int id = inv.getArgument(1, Character.class).getId();
                partyOf.remove(id);
                left.add(id);
                return null;
            });

            assertDoesNotThrow(KpqLobby::tick,
                    "a throw while the party is made must not escape the lobby tick");

            parties.verify(() -> Party.createParty(leader, true)); // the tick reached a start: not a vacuous pass
            assertEquals(List.of(leader.getId(), joined.getId()), left,
                    "both bots still in the made party leave it, in the picker's order");
            assertTrue(partyOf.isEmpty(), "the lobby party must be disbanded, still held: " + partyOf.keySet());
            assertNull(leader.getParty(), "the leader is free again");
            assertNull(joined.getParty(), "the bot that joined is free again");
            assertEquals(0, KpqLobby.running(), "no run slot is left behind by a start that threw");
        }
    }
}
