package scripting;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.mechanics.Mechanics;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Amoria PQ counts a bot as a party member only when {@code -Dslums.mechanics.amoriapq.botcount} is on.
 *
 * <p>Both halves are pinned here, because the switch is easy to over-apply: a bot has to satisfy the <em>party-size</em>
 * arithmetic (otherwise three humans and three bots cannot start the quest at all - {@code getEligibleParty} returns an
 * empty list and the start fails), and it must still not satisfy the <em>puzzle</em> counts. The puzzle half is checked by
 * asking the script's own helpers: with three bots and one human, {@code comboSlots} must still be 1 (the humans) and
 * {@code stageItemRequirement} must still be the one-human figure, because a bot cannot stand on a crate nor hold a trial
 * item. Counting bots there would demand work no member can perform.
 */
class AmoriaBotCountTest {

    private static final String EVENT = "event/AmoriaPQ.js";
    private static final String HOST = "npc/9201044.js";
    private static final String LAST = "npc/9201045.js";

    /** Loads a script file through the server's own Graal setup (the same one that runs it live). */
    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    /** The minimum a {@code PartyCharacter} has to answer for the eligibility walk. */
    public static final class FakePartyChar {
        private final FakePlayer player;
        private final int mapId;
        private final int level;
        private final boolean leader;
        private final String name;

        FakePartyChar(String name, boolean bot, int mapId, int level, boolean leader) {
            this.name = name;
            this.player = new FakePlayer(bot);
            this.mapId = mapId;
            this.level = level;
            this.leader = leader;
        }

        public FakePlayer getPlayer() {
            return player;
        }

        public int getMapId() {
            return mapId;
        }

        public int getLevel() {
            return level;
        }

        public boolean isLeader() {
            return leader;
        }

        public String getName() {
            return name;
        }

        /** {@code 9201044.js:76} asks the party slot, not the player, whether it is a bot. */
        public boolean isBot() {
            return player.isBot();
        }
    }

    public static final class FakePlayer {
        private final boolean bot;

        FakePlayer(boolean bot) {
            this.bot = bot;
        }

        public boolean isBot() {
            return bot;
        }

        public boolean isMarried() {
            return false;
        }
    }

    /** A party slot whose player has just logged out: {@code getPlayer()} answers null. */
    public static final class NoPlayerChar {
        public FakePlayer getPlayer() {
            return null;
        }
    }

    /** The two puzzle counters read {@code eim.getPlayers()}, a list of characters. */
    public static final class FakeEim {
        private final java.util.List<FakePartyChar> players;

        FakeEim(java.util.List<FakePartyChar> players) {
            this.players = players;
        }

        public java.util.List<FakePartyChar> getPlayers() {
            return players;
        }
    }

    @AfterEach
    void clearSwitch() {
        System.clearProperty(Mechanics.AMORIA_BOTCOUNT_FLAG);
    }

    private static Object call(String path, String function, Object... args) throws ScriptException, NoSuchMethodException {
        ScriptEngine engine = new Loader().load(path);
        assertTrue(engine instanceof Invocable, "the script engine is invocable");
        return ((Invocable) engine).invokeFunction(function, args);
    }

    @Test
    void theSwitchIsOffByDefaultAndOnlyThePropertyTurnsItOn() {
        assertFalse(Mechanics.amoriaBotCount(), "off unless the server operator asks");
        System.setProperty(Mechanics.AMORIA_BOTCOUNT_FLAG, "true");
        assertTrue(Mechanics.amoriaBotCount());
        System.setProperty(Mechanics.AMORIA_BOTCOUNT_FLAG, "false");
        assertFalse(Mechanics.amoriaBotCount());
    }

    @Test
    void aHumanAlwaysCountsAndABotCountsOnlyUnderTheSwitch() throws ScriptException, NoSuchMethodException {
        FakePartyChar human = new FakePartyChar("human", false, 680000000, 60, true);
        FakePartyChar bot = new FakePartyChar("Zak (1)", true, 680000000, 60, false);

        assertEquals(Boolean.TRUE, call(EVENT, "countsAsMember", human), "a human is a member, switch or not");
        assertEquals(Boolean.FALSE, call(EVENT, "countsAsMember", bot),
                "fail-before: with the switch off a bot is filtered out exactly as it always was");
        assertEquals(Boolean.FALSE, call(EVENT, "countsAsMember", new NoPlayerChar()),
                "a character with no player attached is never a member");

        System.setProperty(Mechanics.AMORIA_BOTCOUNT_FLAG, "true");
        assertEquals(Boolean.TRUE, call(EVENT, "countsAsMember", bot), "switch on: the bot's presence counts");
        assertEquals(Boolean.TRUE, call(EVENT, "countsAsMember", human), "and a human is unaffected");
    }

    /**
     * The gate itself, in the arithmetic {@code getEligibleParty} performs ({@code eligible.length >= minPlayers}), with
     * {@code minPlayers} read from the script rather than assumed. Three humans and three bots is the case the owner asked
     * about: before, the list comes back with three members against a three-player minimum only if the party is all
     * humans, so a party that is half bots is "too small" however many people are standing there.
     */
    @Test
    void aPartyHalfBotsIsTooSmallBeforeTheSwitchAndBigEnoughAfter() throws ScriptException, NoSuchMethodException {
        ScriptEngine engine = new Loader().load(EVENT);
        Object minPlayers = engine.eval("minPlayers");
        assertTrue(minPlayers instanceof Number, "the script states its own minimum");
        int minimum = ((Number) minPlayers).intValue();

        FakePartyChar[] party = {
                new FakePartyChar("human1", false, 680000000, 60, true),
                new FakePartyChar("human2", false, 680000000, 60, false),
                new FakePartyChar("Zak (1)", true, 680000000, 60, false),
                new FakePartyChar("Zak (2)", true, 680000000, 60, false),
                new FakePartyChar("Zak (3)", true, 680000000, 60, false)};

        assertEquals(minimum, members(engine, party), "fail-before: five standing there, two counted, so still short");

        System.setProperty(Mechanics.AMORIA_BOTCOUNT_FLAG, "true");
        ScriptEngine on = new Loader().load(EVENT);
        assertEquals(5, members(on, party), "switch on: all five count and the party clears the minimum");
    }

    /** How many of these characters {@code countsAsMember} accepts, which is the size of the eligible list. */
    private static int members(ScriptEngine engine, FakePartyChar[] party) throws ScriptException, NoSuchMethodException {
        int counted = 0;
        for (FakePartyChar ch : party) {
            if (Boolean.TRUE.equals(((Invocable) engine).invokeFunction("countsAsMember", ch))) {
                counted++;
            }
        }
        return counted;
    }

    @Test
    void thePuzzleCountsStillIgnoreBotsWhateverTheSwitchSays() throws ScriptException, NoSuchMethodException {
        java.util.List<FakePartyChar> party = java.util.List.of(
                new FakePartyChar("human", false, 680000000, 60, true),
                new FakePartyChar("Zak (1)", true, 680000000, 60, false),
                new FakePartyChar("Zak (2)", true, 680000000, 60, false),
                new FakePartyChar("Zak (3)", true, 680000000, 60, false));
        FakeEim eim = new FakeEim(party);

        System.setProperty(Mechanics.AMORIA_BOTCOUNT_FLAG, "true");
        assertEquals(1, call(HOST, "comboSlots", eim),
                "three bots and one human need one pair of hands on the crates, not four");
        assertEquals(1, call(HOST, "partySize", eim), "the combo's own count is the humans'");
        assertEquals(9, call(LAST, "stageItemRequirement", eim),
                "50 trial items were the chore of six humans; one human still gathers ceil(50/6) = 9");
    }
}
