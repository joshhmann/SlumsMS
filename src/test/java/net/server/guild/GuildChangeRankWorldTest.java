package net.server.guild;

import client.Character;
import net.server.world.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 1.5.2: Guild.changeRank sent the rank change through Server.getInstance().getWorld(this.world). A guild whose cached
 * world id is not a running world (the test server's log: "Cannot invoke World.setGuildAndRank(int, int, int) because
 * the return value of Server.getWorld(int) is null" out of MatchCheckerGuildCreation) threw, the catch returned early,
 * and the new rank was never recorded - how a freshly created guild's master can be left at rank 5, which the rc6
 * mendLeaderRank workaround repairs on login.
 */
class GuildChangeRankWorldTest {

    @Test
    void rankChangeGoesThroughTheMembersWorldWhenTheGuildWorldIsNotRunning() throws Exception {
        Guild guild = mock(Guild.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        Field lock = Guild.class.getDeclaredField("membersLock");
        lock.setAccessible(true);
        lock.set(guild, new ReentrantLock());
        doNothing().when(guild).broadcast(any());

        World world = mock(World.class);
        Character chr = mock(Character.class);
        when(chr.getWorldServer()).thenReturn(world);

        GuildCharacter mgc = mock(GuildCharacter.class);
        when(mgc.isOnline()).thenReturn(true);
        when(mgc.getId()).thenReturn(77);
        when(mgc.getCharacter()).thenReturn(chr);

        assertDoesNotThrow(() -> guild.changeRank(mgc, 1));
        verify(world).setGuildAndRank(77, 0, 1);
        verify(mgc).setGuildRank(1);
    }
}
