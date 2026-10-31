package soloMapling.qa;

import client.Character;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * TEST 10-31 (t-mx-5): the check's cleanup set the alts' levels back before sending them home, and an owned alt that
 * died had already gone home and been detached - {@code PartyCharacter}'s constructor read
 * {@code getClient().getChannel()} through a null client and the whole cleanup threw, so nothing after it ran
 * (levels not put back, second alt never dismissed, the QA log's last line a NullPointerException).
 */
class SetLevelGoneTest {

    @Test
    void anAltThatWentHomeGetsItsLevelBackWithoutTouchingAParty() {
        Character gone = mock(Character.class); // getClient() is null: it was saved and detached

        MechanicsScenarios.setLevel(gone, 30);

        verify(gone).setLevel(30);
        verify(gone, never()).setMPC(any());
        verify(gone, never()).silentPartyUpdate();
    }
}
