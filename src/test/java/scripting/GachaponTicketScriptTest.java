package scripting;

import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.5.0 rc3 review: the Singapore gachapon (9270043.js) took a Remote Gachapon Ticket and then called doGachapon(),
 * which plays a Gachapon Ticket; gachaponold.js took the ticket and doGachapon(ticket) took another. A machine script
 * now never takes a ticket itself: doGachapon(ticket) takes exactly one, after the prize fits.
 */
class GachaponTicketScriptTest {

    static final int TICKET = 5220000, REMOTE = 5451000;

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    public static final class FakeMap {
        public String getMapName() {
            return "Singapore";
        }
    }

    public static final class FakePlayer {
        public FakeMap getMap() {
            return new FakeMap();
        }
    }

    /** The parts of NPCConversationManager the machine scripts use. */
    public static final class GachaCm {
        final Set<Integer> held;
        public final List<String> taken = new ArrayList<>();
        public final List<Integer> rolls = new ArrayList<>();
        public boolean disposed;

        GachaCm(Set<Integer> held) {
            this.held = held;
        }

        public boolean haveItem(int item) {
            return held.contains(item);
        }

        public boolean canHold(int item) {
            return true;
        }

        public void gainItem(int item, int qty) {
            taken.add(item + " x" + qty);
        }

        public boolean doGachapon() {
            return doGachapon(TICKET);
        }

        /** As GachaMachine: no roll (and nothing taken) without the ticket. */
        public boolean doGachapon(int ticket) {
            if (!held.contains(ticket)) {
                return false;
            }
            rolls.add(ticket);
            return true;
        }

        public String gachaponTownName() {
            return "Singapore";
        }

        public String gachaponOdds() {
            return "";
        }

        public FakePlayer getPlayer() {
            return new FakePlayer();
        }

        public void sendSimple(String text) {
        }

        public void sendYesNo(String text) {
        }

        public void sendOk(String text) {
        }

        public void sendNext(String text) {
        }

        public void dispose() {
            disposed = true;
        }
    }

    private static GachaCm run(String script, Set<Integer> held) throws Exception {
        ScriptEngine engine = new Loader().load(script);
        assertNotNull(engine, script + " evaluates");
        GachaCm cm = new GachaCm(held);
        engine.put("cm", cm);
        Invocable npc = (Invocable) engine;
        npc.invokeFunction("start");
        npc.invokeFunction("action", 1, 0, 0);
        return cm;
    }

    @Test
    void singaporeTakesOneGachaponTicketThroughTheMachineOnly() throws Exception {
        GachaCm cm = run("npc/9270043.js", Set.of(TICKET, REMOTE));
        assertEquals(List.of(), cm.taken, "the script takes no ticket itself");
        assertEquals(List.of(TICKET), cm.rolls, "one roll on a Gachapon Ticket, taken by the machine after it fits");
        assertTrue(cm.disposed);
    }

    @Test
    void singaporeNeverEatsARemoteTicket() throws Exception {
        GachaCm cm = run("npc/9270043.js", Set.of(REMOTE));
        assertEquals(List.of(), cm.taken);
        assertEquals(List.of(), cm.rolls, "a remote ticket is used from the item, not at this machine");
    }

    @Test
    void theOldMachineScriptTakesOneTicketNotTwo() throws Exception {
        GachaCm cm = run("npc/gachaponold.js", Set.of(TICKET));
        assertEquals(List.of(), cm.taken, "no gainItem(ticket, -1) before the roll");
        assertEquals(List.of(TICKET), cm.rolls);
    }
}
