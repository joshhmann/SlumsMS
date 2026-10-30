package soloMapling.ArtificialPlayer.BotQuestSystem;

import client.Character;
import client.QuestStatus;
import client.inventory.Item;
import client.inventory.Pet;
import scripting.AbstractScriptManager;
import scripting.quest.QuestActionManager;
import server.quest.Quest;
import soloMapling.ArtificialPlayer.BotHoldings;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.List;

/**
 * Ticket 158 item 5: a bot runs a quest's own script ({@code scripts/quest/<id>.js}, the {@code qm} conversation a
 * player has at the NPC) for the Temple of Time's scripted steps: 3507's end (the Memory Keeper), the first-instructor
 * sub-quests 3523-3539 (start), and 3514's start and end (the Sorcerer). Never {@code forceStart}/{@code forceComplete}
 * from outside the script: the script decides, as for a player.
 *
 * <p>A roster bot shares a headless client that has no player, so the normal {@code QuestScriptManager} cannot run a
 * script for it (the same reason as {@code soloMapling.mechanics.BotNpcTalk}). This runs the file in a fresh engine
 * with a {@code qm} whose player is the bot: quest state, quest progress and items go to the bot (items and mesos
 * through {@link BotHoldings}, the bot's inventory path); dialogue is recorded, never sent. An owned alt has a client
 * whose player is the alt, so the normal paths are right for it.
 */
public final class BotQuestScript extends AbstractScriptManager {

    public static final BotQuestScript INSTANCE = new BotQuestScript();

    /** What one conversation did. */
    public record Result(boolean ran, List<String> said, String error) {
    }

    private BotQuestScript() {
    }

    /**
     * One conversation with the quest's script: {@code start(1, 0, 0)} or {@code end(1, 0, 0)}, then one more
     * "next/yes" per {@code nexts} while the script has not disposed.
     */
    public Result run(Character bot, int questId, int npcId, boolean start, int nexts) {
        ScriptEngine engine;
        try {
            engine = getInvocableScriptEngine("quest/" + questId + ".js");
        } catch (RuntimeException e) {
            return new Result(false, List.of(), "quest/" + questId + ".js did not load: " + e.getMessage());
        }
        if (engine == null) {
            return new Result(false, List.of(), "no script quest/" + questId + ".js");
        }
        BotQm qm = new BotQm(bot, questId, npcId, start);
        engine.put("qm", qm);
        String fn = start ? "start" : "end";
        try {
            ((Invocable) engine).invokeFunction(fn, (byte) 1, (byte) 0, 0);
            for (int i = 0; i < nexts && !qm.disposed; i++) {
                ((Invocable) engine).invokeFunction(fn, (byte) 1, (byte) 0, 0);
            }
            return new Result(true, List.copyOf(qm.said), null);
        } catch (Exception e) {
            return new Result(false, List.copyOf(qm.said), "quest/" + questId + ".js " + fn + " for a bot: " + e);
        }
    }

    /** The script's {@code qm} for a bot: the bot is the player; dialogue recorded; items through its holdings. */
    static final class BotQm extends QuestActionManager {
        private final Character bot;
        final List<String> said = new ArrayList<>();
        boolean disposed;

        BotQm(Character bot, int questId, int npcId, boolean start) {
            super(bot.getClient(), questId, npcId, start);
            this.bot = bot;
        }

        private boolean shared() {
            return bot.getClient() == null || bot.getClient().getPlayer() != bot;
        }

        @Override
        public Character getPlayer() {
            return bot;
        }

        @Override
        public Character getChar() {
            return bot;
        }

        @Override
        public int getMapId() {
            return bot.getMapId();
        }

        @Override
        public void dispose() {
            disposed = true; // QuestScriptManager never held this conversation
        }

        @Override
        public int getQuestStatus(int id) {
            return bot.getQuest(Quest.getInstance(id)).getStatus().getId();
        }

        @Override
        public boolean isQuestCompleted(int id) {
            return bot.getQuest(Quest.getInstance(id)).getStatus() == QuestStatus.Status.COMPLETED;
        }

        @Override
        public boolean isQuestStarted(int id) {
            return bot.getQuest(Quest.getInstance(id)).getStatus() == QuestStatus.Status.STARTED;
        }

        @Override
        public void setQuestProgress(int id, int infoNumber, String progress) {
            bot.setQuestProgress(id, infoNumber, progress);
        }

        @Override
        public boolean canHold(int itemid) {
            return canHold(itemid, 1);
        }

        @Override
        public boolean canHold(int itemid, int quantity) {
            if (!shared()) {
                return super.canHold(itemid, quantity);
            }
            Item item = BotHoldings.newItem(itemid, quantity);
            return item != null && BotHoldings.canHold(bot, item);
        }

        @Override
        public void gainMeso(int gain) {
            if (!shared()) {
                super.gainMeso(gain);
            } else if (gain < 0) {
                BotHoldings.spendMeso(bot, -gain);
            } else if (gain > 0) {
                BotHoldings.grantMeso(bot, gain);
            }
        }

        @Override
        public Item gainItem(int id, short quantity, boolean randomStats, boolean showMessage, long expires, Pet from) {
            if (!shared()) {
                return super.gainItem(id, quantity, randomStats, showMessage, expires, from);
            }
            if (quantity > 0) {
                Item item = BotHoldings.newItem(id, quantity);
                return item != null && BotHoldings.grantItem(bot, item) ? item : null;
            }
            if (quantity < 0) {
                int left = -quantity;
                for (Item stack : new ArrayList<>(bot.getInventory(
                        constants.inventory.ItemConstants.getInventoryType(id)).listById(id))) {
                    if (left <= 0) {
                        break;
                    }
                    left -= BotHoldings.consumeHeld(bot, stack, left);
                }
            }
            return null;
        }

        @Override
        public void sendOk(String text) {
            said.add(text);
        }

        @Override
        public void sendOk(String text, byte speaker) {
            said.add(text);
        }

        @Override
        public void sendNext(String text) {
            said.add(text);
        }

        @Override
        public void sendNext(String text, byte speaker) {
            said.add(text);
        }

        @Override
        public void sendYesNo(String text) {
            said.add(text);
        }

        @Override
        public void sendAcceptDecline(String text) {
            said.add(text);
        }

        @Override
        public void sendSimple(String text) {
            said.add(text);
        }
    }
}
