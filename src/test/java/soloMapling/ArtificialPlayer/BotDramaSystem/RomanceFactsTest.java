package soloMapling.ArtificialPlayer.BotDramaSystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotConvo.RelationshipCard;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.SocialGate;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Stage B (owner's step 5): a romance is only ever spoken of from a real relationship and with {@code @romance} on.
 * Before this, a dating bot told every stranger "You're dating them" and a married bot told everyone "You're taken".
 */
class RomanceFactsTest {

    static final int PLAYER = 4243;
    static final int BOT = BotStore.BOT_ID_BASE + 79;
    static final long DAY = TimeUnit.DAYS.toMillis(1);

    final RomanceStore.Memory store = new RomanceStore.Memory();
    final AtomicLong clock = new AtomicLong(1000 * DAY);

    @BeforeEach
    void setUp() {
        BotRomance.useForTest(store, id -> null, id -> null, clock::get);
        DramaKit.resetForTest();
    }

    @AfterEach
    void tearDown() {
        BotRomance.useForTest(null, null, null, null);
        SocialGate.setRomanceOff(PLAYER, false);
        DramaKit.resetForTest();
    }

    static Character chr(int id, String name) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn(name);
        return c;
    }

    private String card() {
        return RelationshipCard.of(chr(BOT, "Amy"), chr(PLAYER, "Bob"), "grinding", null, clock.get());
    }

    @Test
    void aDatingPairIsOnlyInThePromptWithRomanceOn() {
        Character p = chr(PLAYER, "Bob");
        Character b = chr(BOT, "Amy");
        BotRomance.startDating(b, p, true);
        assertFalse(card().toLowerCase().contains("dating"),
                "@romance off: the prompt never mentions the dating: " + card());
        assertFalse(SocialGate.romanceSpeakable(PLAYER));
        BotRomance.setRomance(p, true);
        assertTrue(SocialGate.romanceSpeakable(PLAYER));
        assertTrue(card().toLowerCase().contains("dating"),
                "@romance on: the prompt may say they are dating: " + card());
    }

    @Test
    void romanceOffSilencesARealRelationshipAgain() {
        Character p = chr(PLAYER, "Bob");
        Character b = chr(BOT, "Amy");
        BotRomance.setRomance(p, true);
        BotRomance.startDating(b, p, true);
        assertTrue(card().toLowerCase().contains("dating"));
        SocialGate.setRomanceOff(PLAYER, true);
        assertFalse(SocialGate.romanceSpeakable(PLAYER), "@romance off wins over @romance on");
        assertFalse(card().toLowerCase().contains("dating"),
                "@romance off: the dating is out of the prompt again: " + card());
    }

    @Test
    void aStrangerNeverGetsARomanceFact() {
        assertFalse(card().toLowerCase().contains("dating"), "no relationship, no romance fact: " + card());
        assertFalse(card().toLowerCase().contains("taken"), "a single bot is not 'taken': " + card());
    }
}
