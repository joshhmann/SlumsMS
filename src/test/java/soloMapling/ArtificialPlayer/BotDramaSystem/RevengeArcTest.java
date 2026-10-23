package soloMapling.ArtificialPlayer.BotDramaSystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityStore;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
 * 1.5.0 rc6 (QA t-rv-4): "bot followed me once, but didnt really ask for apology till way later, then tp'd back to
 * where he spawned". The player left the map seconds after the rival arrived (its demand was said once, at arrival);
 * the rival stayed behind with the arc open, and when it gave up it warped onto its old grind spot - the map the
 * player was on by then.
 */
class RevengeArcTest {

    static final int PLAYER = 6161;
    static final int BOT = BotStore.BOT_ID_BASE + 93;
    final AtomicLong clock = new AtomicLong(3000 * TimeUnit.DAYS.toMillis(1));

    @BeforeEach
    void setUp() {
        BotAffinity.useForTest(new AffinityStore.Memory(), Runnable::run, clock::get, id -> true);
        BotRivalry.resetForTest(clock::get);
        DramaKit.resetForTest();
    }

    @AfterEach
    void tearDown() {
        BotAffinity.useForTest(null, null, null, null);
        BotRivalry.resetForTest(null);
        DramaKit.resetForTest();
    }

    @Test
    void aBotNeverWarpsBackOntoThePlayersMapAndATestBotStaysPut() {
        assertTrue(BotRivalry.staysPut(false, 100000000, 104040000, 104040000),
                "the player stands on its home map: it would pop up in front of them");
        assertFalse(BotRivalry.staysPut(false, 100000000, 104040000, 100000000), "else it goes home");
        assertFalse(BotRivalry.staysPut(false, 104040000, 104040000, 104040000), "already home: nothing to warp");
        assertTrue(BotRivalry.staysPut(true, 100000000, 104040000, 100000000), "a staged test bot stays");
    }

    @Test
    void anOpenArcIsNotAFreshFollow() {
        Character player = mock(Character.class);
        when(player.getId()).thenReturn(PLAYER);
        when(player.getMapId()).thenReturn(100000000);
        BotRivalry.Arc arc = BotRivalry.arcForTest(BOT, PLAYER, clock.get() + 90_000);
        BotRivalry.onMapChanged(player, 104040000); // the bot is not live here: no chase, nothing armed
        assertEquals(0, arc.chases);
        assertTrue(BotRivalry.arcOpen(PLAYER));
    }

    @Test
    void theDemandIsAskedAgainAndAChaseHasItsOwnLines() throws Exception {
        String yaml = Files.readString(Path.of("src/main/java/soloMapling/ArtificialPlayer/BotDialoguePack/DramaDialogue.yaml"));
        assertTrue(yaml.contains("  RevengeRemind:") && yaml.contains("  RevengeChase:"));
        String src = Files.readString(Path.of("src/main/java/soloMapling/ArtificialPlayer/BotDramaSystem/BotRivalry.java"));
        assertTrue(src.contains("new long[]{30_000, 60_000}"), "asked again at 30 s and 60 s");
        assertTrue(src.contains("DramaKit.reply(b, player, line)"), "whispered too");
    }
}
