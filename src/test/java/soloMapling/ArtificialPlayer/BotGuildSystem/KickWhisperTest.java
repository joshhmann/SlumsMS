package soloMapling.ArtificialPlayer.BotGuildSystem;

import client.Character;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotWhispers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** rc6 auto-QA triage (t-gu-8): the kick whisper was swallowed by an earlier recruiter's whisper budget. */
class KickWhisperTest {

    @Test
    void theKickWhisperGetsThroughAfterAnUnsolicitedOne() {
        Character player = mock(Character.class);
        when(player.getId()).thenReturn(987_654);
        when(player.getName()).thenReturn("Kicked");
        assertTrue(BotWhispers.send(player, "Recruiter", 0, "join us", false), "the recruiter's whisper");
        assertFalse(BotWhispers.send(player, "Other", 0, "another pitch", false), "the budget is spent");
        assertTrue(BotGuilds.kickWhisper(player, "Leader", 0, "gotta let u go"), "the kick reason always arrives");
        verify(player, times(2)).sendPacket(any());
    }
}
