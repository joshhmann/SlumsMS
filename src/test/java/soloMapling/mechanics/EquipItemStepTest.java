package soloMapling.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGear;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGear.Wear;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGear.WearResult;

/**
 * {@link SolverStep.EquipItem} is an intent the bot's own gear machinery carries out: the executor asks
 * {@link BotGear#wear} and tells the log what happened - it never moves an item itself, and it never does more than one
 * attempt in a few seconds.
 */
class EquipItemStepTest {

    private static final long NOW = 7_000_000L;
    private static final int ROCK = 1032033;

    @AfterEach
    void putTheRealGearBack() {
        StepExecutor.wearer = BotGear::wear;
    }

    private static Character bot() {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(9);
        when(bot.getName()).thenReturn("Rocky");
        return bot;
    }

    private static void run(StepExecutor executor, Character bot, long now, List<String> log) {
        executor.execute(null, null, null, bot, new SolverStep.EquipItem(ROCK, "the maps from 990000300 on hurt without it"),
                now, log::add);
    }

    @Test
    void theStepAsksTheGearSystemToWearTheNamedItemAndSaysItDid() {
        List<Integer> asked = new ArrayList<>();
        StepExecutor.wearer = (b, id) -> {
            asked.add(id);
            return new WearResult(Wear.WORN, "");
        };
        List<String> log = new ArrayList<>();

        run(new StepExecutor(), bot(), NOW, log);

        assertEquals(List.of(ROCK), asked, "one attempt, for exactly this item");
        assertTrue(log.stream().anyMatch(l -> l.contains("EQUIP") && l.contains("Rocky") && l.contains(String.valueOf(ROCK))),
                log.toString());
    }

    @Test
    void aRefusalIsSaidWithItsReasonAndNothingIsClaimed() {
        StepExecutor.wearer = (b, id) -> new WearResult(Wear.REFUSED, "1032033 needs level 10, the bot is level 8");
        List<String> log = new ArrayList<>();

        run(new StepExecutor(), bot(), NOW, log);

        assertTrue(log.stream().anyMatch(l -> l.contains("cannot put on") && l.contains("level 10")), log.toString());
        assertTrue(log.stream().noneMatch(l -> l.startsWith("EQUIP")), "no 'puts on' line for a refusal: " + log);
    }

    @Test
    void aBotThatDoesNotHoldItSaysSoByName() {
        StepExecutor.wearer = (b, id) -> new WearResult(Wear.NOT_CARRIED, "the bot does not carry " + id);
        List<String> log = new ArrayList<>();

        run(new StepExecutor(), bot(), NOW, log);

        assertTrue(log.stream().anyMatch(l -> l.contains("cannot put on") && l.contains(String.valueOf(ROCK))),
                log.toString());
    }

    @Test
    void anAttemptIsPacedAndARepeatedRefusalIsSaidOnce() {
        AtomicInteger tries = new AtomicInteger();
        StepExecutor.wearer = (b, id) -> {
            tries.incrementAndGet();
            return new WearResult(Wear.REFUSED, "nope");
        };
        StepExecutor executor = new StepExecutor();
        Character bot = bot();
        List<String> log = new ArrayList<>();

        run(executor, bot, NOW, log);
        run(executor, bot, NOW + 500, log);
        run(executor, bot, NOW + 1_000, log);
        assertEquals(1, tries.get(), "paced: not one wear attempt per tick");

        run(executor, bot, NOW + 5_000, log);
        assertEquals(2, tries.get());
        assertEquals(1, log.size(), "the same refusal is not said again: " + log);
    }

    @Test
    void anAlreadyWornItemIsQuiet() {
        StepExecutor.wearer = (b, id) -> new WearResult(Wear.ALREADY_WORN, "");
        List<String> log = new ArrayList<>();

        run(new StepExecutor(), bot(), NOW, log);

        assertTrue(log.isEmpty(), log.toString());
    }
}
