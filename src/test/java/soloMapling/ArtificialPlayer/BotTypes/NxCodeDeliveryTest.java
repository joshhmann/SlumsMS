package soloMapling.ArtificialPlayer.BotTypes;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.0 nx-1: after the buyer's 50m has moved, the NX merchant's hand-over always leaves them with the NX. Before,
 * a declined or ignored messenger invite (or a failed insert) left them 50m down with nothing.
 */
class NxCodeDeliveryTest {

    static final class FakePort implements NxCodeDelivery.Port {
        String mintAs = "ABCDE-23456-FGHJK";
        boolean noteOk = true;
        boolean online = true;
        boolean accepts;
        long now;
        int credited;
        final List<String> did = new ArrayList<>();

        public String mint(int nx) { did.add("mint " + nx); return mintAs; }
        public boolean saveNote(String buyerName, String text) { did.add("note " + buyerName + ": " + text); return noteOk; }
        public void showNotes(int buyerId) { did.add("show notes"); }
        public boolean online(int buyerId) { return online; }
        public void invite(int buyerId) { did.add("invite"); }
        public boolean accepted(int buyerId) { return accepts; }
        public void typeCode(String code) { did.add("type " + code); }
        public void leaveMessenger() { did.add("leave"); }
        public boolean creditDirect(int buyerId, int nx) {
            if (!online) {
                return false;
            }
            credited += nx;
            did.add("credit " + nx);
            return true;
        }
        public void whisper(int buyerId, String line) { did.add("whisper " + line); }
        public void say(String line) { did.add("say " + line); }
        public void qa(String line, boolean problem) { did.add("qa " + line); }
        public long now() { return now; }

        boolean did(String prefix) {
            return did.stream().anyMatch(d -> d.startsWith(prefix));
        }
    }

    @Test
    void anAcceptedMessengerGetsTheCodeTypedAndANoteCopy() {
        FakePort p = new FakePort();
        NxCodeDelivery d = new NxCodeDelivery(p, 7, "Buyer");
        assertTrue(d.start());
        assertTrue(p.did("note Buyer: ur 10k nx code: ABCDE-23456-FGHJK"));
        assertTrue(p.did("invite"));
        assertFalse(d.poll());
        p.accepts = true;
        assertTrue(d.poll());
        assertTrue(p.did("type ABCDE-23456-FGHJK"));
        assertFalse(p.did("show notes"));
    }

    @Test
    void aDeclinedOrIgnoredInviteShowsTheNoteWithTheCode() {
        FakePort p = new FakePort();
        NxCodeDelivery d = new NxCodeDelivery(p, 7, "Buyer");
        d.start();
        p.now = NxCodeDelivery.MESSENGER_WINDOW_MS - 1;
        assertFalse(d.poll(), "still inside the window");
        p.now = NxCodeDelivery.MESSENGER_WINDOW_MS;
        assertTrue(d.poll());
        assertTrue(p.did("leave"));
        assertTrue(p.did("show notes"));
        assertTrue(p.did("qa NXCODE messenger not accepted by Buyer: note shown"));
        assertFalse(p.did("type "));
    }

    @Test
    void noNoteAndNoMessengerWhispersTheCode() {
        FakePort p = new FakePort();
        p.noteOk = false;
        NxCodeDelivery d = new NxCodeDelivery(p, 7, "Buyer");
        d.start();
        p.now = NxCodeDelivery.MESSENGER_WINDOW_MS;
        assertTrue(d.poll());
        assertTrue(p.did("whisper ur nx code: ABCDE-23456-FGHJK"));
    }

    @Test
    void aBuyerWhoLogsOffKeepsTheCodeInTheirNotes() {
        FakePort p = new FakePort();
        NxCodeDelivery d = new NxCodeDelivery(p, 7, "Buyer");
        d.start();
        p.online = false;
        assertTrue(d.poll(), "no 30 s wait for someone who left");
        assertTrue(p.did("qa NXCODE buyer Buyer went offline: code waits in their notes"));
    }

    @Test
    void aCodeThatCannotBeStoredIsPaidAsNxCreditInstead() {
        FakePort p = new FakePort();
        p.mintAs = null;
        NxCodeDelivery d = new NxCodeDelivery(p, 7, "Buyer");
        assertFalse(d.start());
        assertEquals(NxCodeDelivery.NX, p.credited);
        assertFalse(p.did("invite"), "no code to type");
        assertFalse(p.did("note "));
        assertTrue(d.poll());
    }

    @Test
    void theNoteFitsTheInbox() {
        assertTrue(NxCodeDelivery.noteText("ABCDE-23456-FGHJK").length() <= 200);
    }
}
