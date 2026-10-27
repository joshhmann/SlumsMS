package soloMapling.ArtificialPlayer.BotTypes;

import client.Character;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.life.Monster;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotPartySystem.ExpeditionBotsFixture;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.0: a party bot that went into its player's boss expedition fights the altar's monsters next to him; the same
 * follower outside an expedition (or before the run) only follows, as before.
 */
class FollowerFightExpeditionTest {

    private static final int ZAKUM_ALTAR = 280030000;

    @Test
    void aJoinedFollowerInsideTheRunTargetsTheBoss() {
        Monster arm = mock(Monster.class);
        when(arm.isAlive()).thenReturn(true);
        when(arm.getPosition()).thenReturn(new Point(100, 0));
        MapleMap altar = mock(MapleMap.class);
        when(altar.getId()).thenReturn(ZAKUM_ALTAR);
        when(altar.getAllMonsters()).thenReturn(new ArrayList<>(List.of(arm)));
        ExpeditionBotsFixture.Joined j = ExpeditionBotsFixture.join(altar, 1, 1_000_000_124);
        try {
            Character bot = j.bot();
            Character leader = j.leader();
            when(bot.getPosition()).thenReturn(new Point(0, 0));
            assertNull(FollowerFight.target(bot, leader), "still at the recruiter: no instance yet");

            when(bot.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
            assertSame(arm, FollowerFight.target(bot, leader));
        } finally {
            j.release();
        }
    }
}
