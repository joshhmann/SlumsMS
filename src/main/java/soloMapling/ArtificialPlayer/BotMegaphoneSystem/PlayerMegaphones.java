package soloMapling.ArtificialPlayer.BotMegaphoneSystem;

import client.Character;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotWhispers;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.Economy.BotDirectTrade;
import soloMapling.Economy.MegaBuyDesk;
import soloMapling.Economy.MegaBuyGame;
import soloMapling.server.ExecutorServiceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 1.6.0 ticket 86 + player-likeness gap 4: the one hook for a real player's megaphone
 * ({@code UseCashItemHandler}: megaphone, super megaphone, item megaphone, triple megaphone, avatar megaphone,
 * Megassenger). The packet handler only hands over the text; everything else runs on the executor, never the game
 * thread: a buy request goes to {@link MegaBuyDesk} (a bot that really has the item whispers an offer later), and
 * whatever it does not answer may get a short bot reaction ({@link MegaReactions}).
 * {@code -Dsolomapling.playerMegaphones=false} switches the hook off.
 */
public final class PlayerMegaphones {

    private static final MegaBuyDesk DESK = new MegaBuyDesk(new MegaBuyGame(), MegaBuyDesk.Limits.defaults());
    private static final MegaReactions REACTIONS = new MegaReactions(new GameEnv());

    private PlayerMegaphones() {
    }

    public static MegaBuyDesk desk() {
        return DESK;
    }

    public static MegaReactions reactions() {
        return REACTIONS;
    }

    /** A real player used a megaphone with this text (several lines joined by a space). Returns at once. */
    public static void onUse(Character player, String text) {
        try {
            if (player == null || text == null || BotHelpers.isBot(player)
                    || "false".equalsIgnoreCase(System.getProperty("solomapling.playerMegaphones", "true"))) {
                return;
            }
            MegaBuyDesk.Asker asker = new MegaBuyDesk.Asker(player.getId(), player.getName(),
                    MegaBuyGame.channelOfPlayer(player), player);
            ExecutorServiceManager.runAsync(() -> {
                try {
                    MegaBuyDesk.Outcome o = DESK.hear(asker, text);
                    int reacted = REACTIONS.react(asker.id(), asker.name(), text, o == MegaBuyDesk.Outcome.SCHEDULED);
                    soloMapling.BotLogger.log("[MegaBuy] smega by " + asker.name() + ": \"" + text + "\" -> " + o
                            + (reacted > 0 ? ", " + reacted + " reaction(s)" : ""));
                } catch (RuntimeException e) {
                    // a megaphone never fails because of a bot
                }
            });
        } catch (RuntimeException e) {
            // never reaches the packet handler
        }
    }

    private static final class GameEnv implements MegaReactions.Env {
        @Override
        public long now() {
            return System.currentTimeMillis();
        }

        @Override
        public double roll() {
            return ThreadLocalRandom.current().nextDouble();
        }

        @Override
        public int between(int lo, int hi) {
            return hi <= lo ? lo : ThreadLocalRandom.current().nextInt(lo, hi + 1);
        }

        @Override
        public void later(long delayMs, Runnable task) {
            ExecutorServiceManager.getScheduledExecutorService().schedule(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    // a bot that cannot speak just does not
                }
            }, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
        }

        private Character player(int id) {
            return soloMapling.server.SoloMaplingUtilities.world().getPlayerStorage().getCharacterById(id);
        }

        @Override
        public List<Integer> botsNear(int playerId, int n) {
            Character p = player(playerId);
            if (p == null || p.getMap() == null) {
                return List.of();
            }
            List<Integer> ids = new ArrayList<>();
            for (Character c : p.getMap().getAllPlayers()) {
                BotSM sm = BotHelpers.isBot(c) ? CharacterStorage.getBotById(c.getId()) : null;
                if (sm != null && sm.getRunning() && !BotDirectTrade.handlesOwnTrades(sm)
                        && !soloMapling.ArtificialPlayer.PlayerBusy.isBusy(p)) {
                    ids.add(c.getId());
                }
            }
            Collections.shuffle(ids);
            return ids.subList(0, Math.min(n, ids.size()));
        }

        @Override
        public int anyBot(int playerId) {
            List<Integer> all = new ArrayList<>();
            for (BotSM sm : CharacterStorage.getAllBots().values()) {
                if (sm.getRunning() && !BotDirectTrade.handlesOwnTrades(sm) && sm.getChr() != null) {
                    all.add(sm.getChr().getId());
                    if (all.size() >= 60) {
                        break;
                    }
                }
            }
            return all.isEmpty() ? 0 : all.get(ThreadLocalRandom.current().nextInt(all.size()));
        }

        @Override
        public void say(int botId, String line) {
            BotSM sm = CharacterStorage.getBotById(botId);
            if (sm != null && sm.getChr() != null && sm.getRunning()) {
                SocialCommands.BotFullChat(sm.getChr(), line);
            }
        }

        @Override
        public boolean whisper(int botId, int playerId, String line) {
            BotSM sm = CharacterStorage.getBotById(botId);
            Character p = player(playerId);
            return sm != null && sm.getChr() != null && p != null
                    && BotWhispers.send(p, sm.getChr().getName(), Math.max(0, MegaBuyGame.channelOfPlayer(sm.getChr()) - 1),
                    line, false);
        }
    }
}
