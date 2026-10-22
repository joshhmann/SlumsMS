package client.command.commands.gm3;

import client.Job;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BotShowcaseCommandTest {

    @Test
    void noArgumentsShowsEveryFourthJobForFiveMinutes() {
        BotShowcaseCommand.Request r = BotShowcaseCommand.parse(new String[0]);
        assertEquals(18, r.jobs().size());
        assertEquals(5, r.minutes());
    }

    @Test
    void jobsByNameOrIdAndAMinutesCount() {
        BotShowcaseCommand.Request r = BotShowcaseCommand.parse(new String[]{"aran4", "512", "10"});
        assertEquals(List.of(Job.ARAN4, Job.BUCCANEER), r.jobs());
        assertEquals(10, r.minutes());
        assertEquals(30, BotShowcaseCommand.parse(new String[]{"pirates", "99"}).minutes());
        assertEquals(4, BotShowcaseCommand.parse(new String[]{"pirates"}).jobs().size());
    }

    @Test
    void thePacingSetIsTheSpotChecksJobs() {
        BotShowcaseCommand.Request r = BotShowcaseCommand.parse(new String[]{"pacing", "12"});
        assertEquals(List.of(Job.BOWMASTER, Job.CORSAIR, Job.FP_ARCHMAGE, Job.BLAZEWIZARD4, Job.BISHOP, Job.HERO,
                Job.ARAN4, Job.THUNDERBREAKER4), r.jobs());
        assertEquals(12, r.minutes());
    }

    @Test
    void anUnknownWordIsRefused() {
        assertNull(BotShowcaseCommand.parse(new String[]{"pirate-king"}));
    }
}
