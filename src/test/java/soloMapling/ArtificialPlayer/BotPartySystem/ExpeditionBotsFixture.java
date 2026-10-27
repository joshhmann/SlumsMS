package soloMapling.ArtificialPlayer.BotPartySystem;

import client.BotClient;
import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionType;
import server.maps.MapleMap;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Test support: a leader's alt that has joined his (registering) Zakum expedition through {@link ExpeditionBots}. */
public final class ExpeditionBotsFixture {

    public record Joined(Character leader, Character bot, Expedition expedition) {
        public void release() {
            ExpeditionBots.release(expedition);
        }
    }

    private ExpeditionBotsFixture() {
    }

    public static Joined join(MapleMap map, int leaderId, int botId) {
        server.TimerManager.getInstance().start();
        Character leader = mock(Character.class);
        when(leader.getId()).thenReturn(leaderId);
        when(leader.getMap()).thenReturn(map);
        when(leader.getName()).thenReturn("Josh");
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(botId);
        when(bot.getName()).thenReturn("Clock4");
        when(bot.getMap()).thenReturn(map);
        when(bot.getLevel()).thenReturn(70);
        when(bot.isAlive()).thenReturn(true);
        when(bot.getClient()).thenReturn(mock(BotClient.class));
        when(bot.isOwnedAlt()).thenReturn(true);
        when(bot.getOwnedAltOwner()).thenReturn(leader);
        Party party = mock(Party.class);
        PartyCharacter pl = mock(PartyCharacter.class);
        when(pl.getId()).thenReturn(leaderId);
        when(pl.getPlayer()).thenReturn(leader);
        PartyCharacter pb = mock(PartyCharacter.class);
        when(pb.getId()).thenReturn(botId);
        when(pb.getPlayer()).thenReturn(bot);
        when(party.getMembers()).thenReturn(List.of(pl, pb));
        when(leader.getParty()).thenReturn(party);
        when(bot.getParty()).thenReturn(party);

        ExpeditionBots.Quota saved = ExpeditionBots.quota;
        ExpeditionBots.quota = (b, e) -> true;
        try {
            Expedition exped = new Expedition(leader, ExpeditionType.ZAKUM, true, 0, 0);
            exped.beginRegistration();
            ExpeditionBots.autoJoin(leader, exped);
            return new Joined(leader, bot, exped);
        } finally {
            ExpeditionBots.quota = saved;
        }
    }
}
