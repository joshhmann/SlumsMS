package soloMapling.mechanics;

import client.Character;
import client.inventory.Item;
import client.inventory.Pet;
import scripting.AbstractScriptManager;
import scripting.npc.NPCConversationManager;
import soloMapling.ArtificialPlayer.BotHoldings;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.List;

/**
 * A bot talks to a stage NPC through <b>our own NPC script</b> (ticket 130 phase 1: "positions and answers from our
 * scripts"). A bot has no client of its own (the shared {@code BotClient} has no player), so the normal
 * {@code NPCScriptManager} path cannot run a script for it: this runs the script file in a fresh engine with a
 * conversation manager whose player is the bot. Dialogue packets go to the bot's client, which drops them; the lines
 * are kept for the run log. Items the script gives or takes go through {@link BotHoldings} (a bot's inventory path).
 *
 * <p>Only scripts whose branch for the bot uses player-side calls that resolve through {@code getPlayer()} are safe
 * (KPQ's Cloto, stage 1: grid, item counts, gainItem, event properties). A call that still reaches the shared client
 * throws; the talk then reports the error and the run logs it instead of the bot getting stuck.
 */
final class BotNpcTalk extends AbstractScriptManager {

    static final BotNpcTalk INSTANCE = new BotNpcTalk();

    /** What one talk did: whether the script ran, the lines it showed, an error if it failed. */
    record Result(boolean ran, List<String> said, String error) {
        String lastLine() {
            return said.isEmpty() ? "" : said.get(said.size() - 1);
        }
    }

    private BotNpcTalk() {
    }

    /** One click on the NPC: the script's {@code start()} (which for KPQ's Cloto plays its first status). */
    Result talk(Character bot, int npcId) {
        return converse(bot, npcId, List.of());
    }

    /**
     * A whole conversation: the click ({@code start()}), then one {@code action(1, 0, selection)} per answer, as a
     * player presses "next"/"yes" or picks a menu line, until the script disposes. KPQ: Cloto's prize after the last
     * stage ([0]: next), Nella's "leave?" ([0]: yes), Lakelis' "I want to participate" ([0]).
     */
    Result converse(Character bot, int npcId, List<Integer> answers) {
        ScriptEngine engine;
        try {
            engine = getInvocableScriptEngine("npc/" + npcId + ".js");
        } catch (RuntimeException e) {
            return new Result(false, List.of(), "script npc/" + npcId + ".js did not load: " + e.getMessage());
        }
        if (engine == null) {
            return new Result(false, List.of(), "no script npc/" + npcId + ".js");
        }
        BotCm cm = new BotCm(bot, npcId);
        engine.put("cm", cm);
        try {
            ((Invocable) engine).invokeFunction("start");
            for (int selection : answers) {
                if (cm.disposed) {
                    break;
                }
                ((Invocable) engine).invokeFunction("action", (byte) 1, (byte) 0, selection);
            }
            return new Result(true, List.copyOf(cm.said), null);
        } catch (Exception e) {
            return new Result(false, List.copyOf(cm.said), "npc/" + npcId + ".js for a bot: " + e);
        }
    }

    /** The script's {@code cm} for a bot: the bot is the player; dialogue is recorded, never sent anywhere. */
    static final class BotCm extends NPCConversationManager {
        private final Character bot;
        final List<String> said = new ArrayList<>();
        boolean disposed;

        BotCm(Character bot, int npcId) {
            super(bot.getClient(), npcId, null);
            this.bot = bot;
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
            disposed = true; // NPCScriptManager never held this conversation: nothing to release there
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
        public void sendOk(String text) {
            said.add(text);
        }

        @Override
        public void sendOk(String text, byte speaker) {
            said.add(text);
        }

        @Override
        public void sendPrev(String text) {
            said.add(text);
        }

        @Override
        public void sendNextPrev(String text) {
            said.add(text);
        }

        @Override
        public void sendYesNo(String text) {
            said.add(text);
        }

        @Override
        public void sendSimple(String text) {
            said.add(text);
        }

        @Override
        public void sendAcceptDecline(String text) {
            said.add(text);
        }

        /** The event manager of the bot's own channel (the shared bot client may sit on another one). */
        @Override
        public scripting.event.EventManager getEventManager(String event) {
            net.server.channel.Channel ch = bot.getClient().getChannelServer();
            return ch == null ? super.getEventManager(event) : ch.getEventSM().getEventManager(event);
        }

        /**
         * Every {@code gainItem} overload ends here: a population bot's items go through its holdings, never the
         * shared client. An owned alt has its own client whose player is the alt: the normal path is right for it.
         */
        @Override
        public Item gainItem(int id, short quantity, boolean randomStats, boolean showMessage, long expires, Pet from) {
            if (bot.getClient().getPlayer() == bot) {
                return super.gainItem(id, quantity, randomStats, showMessage, expires, from);
            }
            if (quantity > 0) {
                Item item = BotHoldings.newItem(id, quantity);
                return item != null && BotHoldings.grantItem(bot, item) ? item : null;
            }
            if (quantity < 0) {
                takeItems(bot, id, -quantity);
            }
            return null;
        }
    }

    /** Takes up to {@code count} of an item out of a bot's inventory (its holdings ledger follows); how many went. */
    static int takeItems(Character bot, int itemId, int count) {
        return BotHoldings.takeHeld(bot, itemId, count);
    }
}
