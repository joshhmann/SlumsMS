package soloMapling.ArtificialPlayer.BotCombatSystem;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rc6 t-cb-3 (2026-10-23 full run, 2 of 2): {@code lowpots} runs over every staged bot, so all of them go to town to
 * restock, and a bot that left the grind is not death-eligible ({@link BotDeaths} rolls nothing for it) - the forced
 * lethal hit did nothing and the check failed "did not die (not grinding yet?)". The die step now puts its subject
 * back to work on the staging map first and only hits a bot that is actually grinding.
 */
class CombatStagingDieTest {

    /** A staged bot in the exact post-lowpots state: in town, not grinding, until its return trip runs. */
    private static final class Bot implements CombatStaging.DieSubject {

        private final int id;
        private final String name;
        private final boolean canWork;
        private final List<String> log;
        private boolean grinding;

        Bot(int id, boolean grinding, boolean canWork, List<String> log) {
            this.id = id;
            this.name = "bot" + id;
            this.grinding = grinding;
            this.canWork = canWork;
            this.log = log;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean grinding() {
            return grinding;
        }

        @Override
        public boolean backToGrind() {
            log.add(name + " sent back to work");
            if (!canWork) {
                return false;
            }
            grinding = true;
            return true;
        }

        @Override
        public boolean killNow() {
            log.add(name + (grinding ? " hit while grinding" : " HIT IN TOWN"));
            return grinding;
        }
    }

    private static Map<Integer, CombatStaging.DieSubject> stage(List<String> log, Bot... bots) {
        Map<Integer, CombatStaging.DieSubject> byId = new HashMap<>();
        for (Bot bot : bots) {
            byId.put(bot.id, bot);
        }
        return byId;
    }

    @Test
    void everyStagedBotDrainedAndInTownStillDiesOncePutBackToWork() {
        List<String> log = new ArrayList<>();
        Map<Integer, CombatStaging.DieSubject> bots = stage(log
                , new Bot(101, false, true, log)
                , new Bot(102, false, true, log)
                , new Bot(103, false, true, log)
                , new Bot(104, false, true, log));

        List<String> out = CombatStaging.die(List.of(101, 102, 103, 104), bots::get);

        assertEquals(List.of("bot101 died"), out);
        assertEquals(List.of("bot101 sent back to work", "bot101 hit while grinding"), log,
                "the subject is back in the grind before the lethal hit, and nothing else is hit");
    }

    @Test
    void aBotThatIsAlreadyFightingIsNotSentBack() {
        List<String> log = new ArrayList<>();
        Map<Integer, CombatStaging.DieSubject> bots = stage(log, new Bot(102, true, true, log));

        assertEquals(List.of("bot102 died"), CombatStaging.die(List.of(102), bots::get));
        assertEquals(List.of("bot102 hit while grinding"), log, "a bot at work is never moved for nothing");
    }

    @Test
    void noBotThatComesBackIsAStagingFailureNotAGrindMessage() {
        List<String> log = new ArrayList<>();
        Map<Integer, CombatStaging.DieSubject> bots = stage(log
                , new Bot(101, false, false, log)
                , new Bot(102, false, false, log));

        List<String> out = CombatStaging.die(List.of(101, 102), bots::get);

        assertEquals(List.of("staging: no staged bot could be returned to the grind and killed (bot101, bot102)"), out);
        for (String line : log) {
            assertTrue(line.endsWith("sent back to work"), "nothing is ever hit while out of the grind: " + log);
        }
    }

    @Test
    void theDeathStepReachesAGrinderBeforeTheBotThatWasJustSentToTown() {
        List<String> log = new ArrayList<>();
        Map<Integer, CombatStaging.DieSubject> bots = stage(log
                , new Bot(101, false, false, log) // the one lowpots bought out of pots: first staged, in town
                , new Bot(102, true, true, log));

        assertEquals(List.of("bot102 died"), CombatStaging.die(List.of(101, 102), bots::get));
        assertEquals(List.of("bot102 hit while grinding"), log);
    }

    @Test
    void theOrderKeepsEveryStagedBotExactlyOnce() {
        List<String> log = new ArrayList<>();
        Map<Integer, CombatStaging.DieSubject> bots = stage(log
                , new Bot(7, false, false, log)
                , new Bot(8, true, true, log)
                , new Bot(9, false, false, log)
                , new Bot(10, true, true, log));

        // 8 and 10 are fighting, but 8 is tried first (staging order among the grinders) and it dies
        assertEquals(List.of("bot8 died"), CombatStaging.die(List.of(7, 8, 9, 10), bots::get));
        assertEquals(List.of("bot8 hit while grinding"), log, "no staged bot is touched before the one that dies");
    }
}
