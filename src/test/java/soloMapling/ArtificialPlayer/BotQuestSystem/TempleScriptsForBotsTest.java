package soloMapling.ArtificialPlayer.BotQuestSystem;

import org.junit.jupiter.api.Test;
import scripting.AbstractScriptManager;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 158 item 5: the Temple's scripted steps run for a bot through {@link BotQuestScript}, a {@code qm} whose
 * player is the bot. That is only safe if those scripts call nothing but what {@code BotQuestScript.BotQm} answers
 * for a bot. The fake below has exactly that surface, so a script change that reaches for anything else fails here.
 * It also pins the conversations {@link TempleOfTime} has with them (how many "next"s each takes): a sub-quest's start
 * (3523-3539), 3507's end, and 3514's start and end.
 */
class TempleScriptsForBotsTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    public static final class Bot {
        long meso;
        int buffSource = -1;

        public int getMeso() {
            return (int) meso;
        }

        public Integer getBuffSource(Object stat) {
            return buffSource;
        }
    }

    /** BotQm's bot-safe surface. */
    public static final class Qm {
        final int quest;
        final Bot bot = new Bot();
        final Set<Integer> completed = new HashSet<>();
        final Set<Integer> started = new HashSet<>();
        final Map<Integer, Integer> items = new HashMap<>();
        final Map<String, String> progress = new HashMap<>();
        final List<String> said = new ArrayList<>();
        long exp;
        boolean disposed;

        Qm(int quest) {
            this.quest = quest;
        }

        public Bot getPlayer() {
            return bot;
        }

        public void startQuest() {
            started.add(quest);
        }

        public void completeQuest() {
            completeQuest(quest);
        }

        /** QuestActionManager.forceCompleteQuest(): Quest.forceComplete on getPlayer(), the bot (3529). */
        public boolean forceCompleteQuest() {
            completeQuest(quest);
            return true;
        }

        public void startQuest(int id) {
            started.add(id);
        }

        public void completeQuest(int id) {
            started.remove(id);
            completed.add(id);
        }

        public boolean isQuestCompleted(int id) {
            return completed.contains(id);
        }

        public void setQuestProgress(int id, int info, int value) {
            progress.put(id + ":" + info, String.valueOf(value));
        }

        public boolean canHold(int id) {
            return true;
        }

        public boolean canHold(int id, int qty) {
            return true;
        }

        public boolean haveItem(int id) {
            return items.getOrDefault(id, 0) > 0;
        }

        public void gainItem(int id, int qty) {
            items.merge(id, qty, Integer::sum);
        }

        public void gainMeso(int gain) {
            bot.meso += gain;
        }

        public void gainExp(int gain) {
            exp += gain;
        }

        public void sendOk(String text) {
            said.add(text);
        }

        public void sendNext(String text) {
            said.add(text);
        }

        public void dispose() {
            disposed = true;
        }
    }

    /** One conversation as BotQuestScript.run has it: the first call, then "next" while not disposed. */
    private static void run(Qm qm, boolean start, int nexts) throws Exception {
        ScriptEngine engine = new Loader().load("quest/" + qm.quest + ".js");
        assertNotNull(engine, "quest/" + qm.quest + ".js");
        engine.put("qm", qm);
        String fn = start ? "start" : "end";
        ((Invocable) engine).invokeFunction(fn, (byte) 1, (byte) 0, 0);
        for (int i = 0; i < nexts && !qm.disposed; i++) {
            ((Invocable) engine).invokeFunction(fn, (byte) 1, (byte) 0, 0);
        }
    }

    @Test
    void everySubQuestCompletesItselfAndUnlocks3507() throws Exception {
        for (TempleRoute.SubQuest sub : TempleRoute.SUB_QUESTS) {
            Qm qm = new Qm(sub.questId());
            qm.started.add(TempleRoute.Q_MEMORY_KEEPER);
            run(qm, true, 1);
            assertTrue(qm.completed.contains(sub.questId()), sub.questId() + " completes itself");
            assertTrue(qm.disposed);
        }
        Qm warrior = new Qm(3523);
        run(warrior, true, 1);
        assertEquals("1", warrior.progress.get("3507:7081"), "3523 sets 3507's progress");
    }

    @Test
    void memoryKeeperCompletes3507OnlyAfterASubQuest() throws Exception {
        Qm early = new Qm(TempleRoute.Q_MEMORY_KEEPER);
        run(early, false, 1);
        assertFalse(early.completed.contains(TempleRoute.Q_MEMORY_KEEPER), "not before the first instructor");
        Qm qm = new Qm(TempleRoute.Q_MEMORY_KEEPER);
        qm.completed.add(3526);
        run(qm, false, 1);
        assertTrue(qm.completed.contains(TempleRoute.Q_MEMORY_KEEPER));
        assertTrue(qm.disposed);
    }

    @Test
    void theSorcererSellsThePotionAndEndsTheQuestOnceItIsDrunk() throws Exception {
        Qm poor = new Qm(TempleRoute.Q_SORCERER);
        poor.bot.meso = 999_999;
        run(poor, true, 0);
        assertFalse(poor.started.contains(TempleRoute.Q_SORCERER), "no money, no deal");
        Qm qm = new Qm(TempleRoute.Q_SORCERER);
        qm.bot.meso = 1_500_000;
        run(qm, true, 0);
        assertTrue(qm.started.contains(TempleRoute.Q_SORCERER));
        assertEquals(500_000, qm.bot.meso, "the fee");
        assertEquals(1, qm.items.get(TempleRoute.SORCERER_POTION));

        Qm notDrunk = new Qm(TempleRoute.Q_SORCERER);
        notDrunk.items.put(TempleRoute.SORCERER_POTION, 1);
        run(notDrunk, false, 1);
        assertFalse(notDrunk.completed.contains(TempleRoute.Q_SORCERER), "drink it first");
        Qm drunk = new Qm(TempleRoute.Q_SORCERER);
        drunk.bot.buffSource = TempleRoute.SORCERER_POTION;
        run(drunk, false, 1);
        assertTrue(drunk.completed.contains(TempleRoute.Q_SORCERER));
        assertEquals(891_500, drunk.exp, "the script's own EXP");
    }
}
