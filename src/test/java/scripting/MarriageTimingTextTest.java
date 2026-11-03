package scripting;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.script.Invocable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 188 F3: the wedding NPCs printed "at the null" whenever the reservation was not in this channel's queue (the
 * ceremony can only start on the booking channel) or its time had passed. They now name the venue and the channel and
 * never print a missing time.
 */
class MarriageTimingTextTest {

    private static final String VENUE_CATHEDRAL = "Cathedral - 12 minutes, 3 seconds from now";

    /** Door NPC (John / Bebop): choose "We're ready to get married" while the couple stands in Amoria. */
    private static MarriageNpcMocks.Couple knockOnTheDoor(String script, boolean cathedral, MarriageNpcMocks.Ch booking) throws Exception {
        MarriageNpcMocks.Couple c = new MarriageNpcMocks.Couple();
        if (booking != null) {
            c.world.channels.add(booking);
        }
        return c;
    }

    private static MarriageNpcMocks.Ch otherChannel(boolean cathedral, int slot, String timeLeft) {
        MarriageNpcMocks.Ch ch = new MarriageNpcMocks.Ch();
        ch.id = 2;
        ch.status.put(cathedral, slot);
        ch.timeLeft = timeLeft;
        return ch;
    }

    @ParameterizedTest
    @CsvSource({"9201002.js,true,Cathedral", "9201012.js,false,Chapel"})
    void bookedOnAnotherChannelNamesTheChannelNotNull(String script, boolean cathedral, String venue) throws Exception {
        MarriageNpcMocks.Couple c = knockOnTheDoor(script, cathedral, otherChannel(cathedral, 2, venue + " - 12 minutes, 3 seconds from now"));
        c.here.reserved = true;      // the world queue knows the couple...
        c.here.timeLeft = null;      // ...but this channel's queue does not, which used to print "null"

        Invocable npc = MarriageNpcMocks.load(script, c.cm);
        npc.invokeFunction("start");
        npc.invokeFunction("action", 1, 0, 0);

        assertFalse(c.log.lastText.contains("null"), c.log.lastText);
        assertTrue(c.log.lastText.contains("Channel 2"), c.log.lastText);
        assertTrue(c.log.lastText.contains("12 minutes"), c.log.lastText);
        assertTrue(c.log.lastText.contains(venue), c.log.lastText);
    }

    @ParameterizedTest
    @CsvSource({"9201002.js,true", "9201012.js,false"})
    void aSlotWhoseTimePassedNeverPrintsNull(String script, boolean cathedral) throws Exception {
        MarriageNpcMocks.Couple c = knockOnTheDoor(script, cathedral, null);
        c.here.reserved = true;
        c.here.status.put(cathedral, 2);
        c.here.timeLeft = (cathedral ? "Cathedral" : "Chapel") + " - null from now"; // Channel.getTimeLeft gave null

        Invocable npc = MarriageNpcMocks.load(script, c.cm);
        npc.invokeFunction("start");
        npc.invokeFunction("action", 1, 0, 0);

        assertFalse(c.log.lastText.contains("null"), c.log.lastText);
        assertTrue(c.log.lastText.contains("Channel 1"), c.log.lastText);
    }

    @ParameterizedTest
    @CsvSource({"9201002.js,true", "9201012.js,false"})
    void reservedButInNoQueueNeverPrintsNull(String script, boolean cathedral) throws Exception {
        MarriageNpcMocks.Couple c = knockOnTheDoor(script, cathedral, null);
        c.here.reserved = true;
        c.here.timeLeft = null;

        Invocable npc = MarriageNpcMocks.load(script, c.cm);
        npc.invokeFunction("start");
        npc.invokeFunction("action", 1, 0, 0);

        assertFalse(c.log.lastText.contains("null"), c.log.lastText);
    }

    @ParameterizedTest
    @CsvSource({"9201002.js,true", "9201012.js,false"})
    void aWeddingRunningOnAnotherChannelSendsThemThere(String script, boolean cathedral) throws Exception {
        MarriageNpcMocks.Couple c = knockOnTheDoor(script, cathedral, otherChannel(cathedral, 0, "X - RIGHT NOW"));
        c.here.reserved = false; // nothing queued, nothing running here

        Invocable npc = MarriageNpcMocks.load(script, c.cm);
        npc.invokeFunction("start");
        npc.invokeFunction("action", 1, 0, 0);

        assertTrue(c.log.lastText.contains("Channel 2"), c.log.lastText);
        assertFalse(c.log.lastText.contains("null"), c.log.lastText);
    }

    @ParameterizedTest
    @CsvSource({"9201005.js,true", "9201008.js,false"})
    void theBookingNpcOnTheWrongChannelNamesTheBookingChannel(String script, boolean cathedral) throws Exception {
        MarriageNpcMocks.Couple c = knockOnTheDoor(script, cathedral, otherChannel(cathedral, 2, (cathedral ? "Cathedral" : "Chapel") + " - 12 minutes, 3 seconds from now"));
        c.here.reserved = true;
        c.here.timeLeft = null;

        Invocable npc = MarriageNpcMocks.load(script, c.cm);
        npc.invokeFunction("start");
        npc.invokeFunction("action", 1, 1, 1);

        assertFalse(c.log.lastText.contains("null"), c.log.lastText);
        assertTrue(c.log.lastText.contains("Channel 2"), c.log.lastText);
    }

    @ParameterizedTest
    @CsvSource({"9201005.js,5251000,true", "9201008.js,5251001,false"})
    void aFreshBookingAnnouncesTheChannel(String script, int ticket, boolean cathedral) throws Exception {
        MarriageNpcMocks.Couple c = knockOnTheDoor(script, cathedral, null);
        c.player.items.put(ticket, 1);
        c.here.pushResult = 3;
        c.here.status.put(cathedral, 3);
        c.here.timeLeft = (cathedral ? "Cathedral" : "Chapel") + " - null from now";

        Invocable npc = MarriageNpcMocks.load(script, c.cm);
        npc.invokeFunction("start");
        npc.invokeFunction("action", 1, 1, 1);

        assertFalse(c.log.lastText.contains("null"), c.log.lastText);
        assertTrue(c.log.lastText.contains("Channel 1"), c.log.lastText);
    }
}
