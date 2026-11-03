package scripting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 188 (with the Java side's giveMarriageRings returning false on a failed ring insert): the vows NPCs must stop
 * cleanly on an explicit false: no tokens taken, no marriage item ids, no stage change, no blessings. A void or true
 * return (the Java side not merged yet, or success) keeps the old behaviour.
 */
class MarriageRingFailureTest {

    public static final class Handler {
        final Object answer;
        boolean voidReturn;
        int calls;

        Handler(Object answer) {
            this.answer = answer;
        }

        public Object giveMarriageRings(Object player, Object partner, int ringId) {
            calls++;
            return answer;
        }
    }

    private static final class Altar {
        final MarriageNpcMocks.Couple c = new MarriageNpcMocks.Couple();
        final MarriageNpcMocks.Eim eim = new MarriageNpcMocks.Eim();

        Altar(String garmentBase) {
            c.cm.mapId = 680000210;
            c.cm.eim = eim;
            c.map.partner = c.partner;
            eim.props.put("groomId", 11);
            eim.props.put("brideId", 22);
            eim.props.put("weddingStage", 2);
            eim.props.put("confirmedVows", 22); // the partner already confirmed
            for (MarriageNpcMocks.Pl p : new MarriageNpcMocks.Pl[]{c.player, c.partner}) {
                p.items.put(4000313, 1);
                p.equipped.put(1050131, 1);
            }
            c.player.items.put(4031357, 1);
            c.partner.items.put(4031358, 1);
        }

        /** Confirms the vows; returns the exception the script ended with, or null. */
        Exception vows(String script, Handler handler) throws Exception {
            Invocable npc = MarriageNpcMocks.load(script, c.cm);
            ((ScriptEngine) npc).put("fakeHandler", handler);
            ((ScriptEngine) npc).eval("RingActionHandler = fakeHandler");
            npc.invokeFunction("start");           // asks "should I make you husband and wife"
            try {
                npc.invokeFunction("action", 1, 0, 0);  // yes
            } catch (ScriptException e) {
                return e; // the bot-gossip hook that follows the ceremony cannot run on test doubles
            }
            return null;
        }
    }

    @ParameterizedTest
    @CsvSource({"9201002.js", "9201011.js"})
    void aFailedRingInsertStopsTheCeremonyCleanly(String script) throws Exception {
        Altar a = new Altar("x");
        Handler handler = new Handler(false);
        Exception e = a.vows(script, handler);

        assertEquals(null, e, "no script error");
        assertEquals(1, handler.calls);
        assertEquals(2, a.eim.getIntProperty("weddingStage"), "ceremony did not progress");
        assertEquals(-1, a.c.player.marriageItemId);
        assertEquals(-1, a.c.partner.marriageItemId);
        assertTrue(a.c.player.items.containsKey(4031357) && a.c.player.items.get(4031357) == 1, "token kept: " + a.c.log.events);
        assertFalse(a.c.log.events.stream().anyMatch(x -> x.contains("gain")), "nothing consumed or granted: " + a.c.log.events);
        assertEquals(0, a.c.map.messages.size(), "no wedding announcement");
        assertEquals(0, a.c.player.exp + a.c.partner.exp);
        assertTrue(a.c.log.lastText.contains("rings could not be made"), a.c.log.lastText);
    }

    @ParameterizedTest
    @CsvSource({"9201002.js", "9201011.js"})
    void aSuccessfulOrVoidReturnStillMarries(String script) throws Exception {
        for (Object answer : new Object[]{true, null}) {
            Altar a = new Altar("x");
            Handler handler = new Handler(answer);
            a.vows(script, handler); // may end in the gossip hook's type error; everything before it is asserted

            assertEquals(1, handler.calls);
            assertEquals(3, a.eim.getIntProperty("weddingStage"), "ceremony progressed for " + answer);
            assertEquals(1112803, a.c.player.marriageItemId);
            assertEquals(1112803, a.c.partner.marriageItemId);
            assertTrue(a.c.log.has("A gain 4031357 -1"), a.c.log.events.toString());
            assertTrue(a.c.log.has("B gain 4031358 -1"), a.c.log.events.toString());
        }
    }
}
