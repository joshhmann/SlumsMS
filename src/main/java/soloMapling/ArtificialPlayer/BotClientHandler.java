package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Client;
import soloMapling.server.SoloMaplingConstants.GameConstants;

/**
 * Single source of truth for the shared headless bot {@link Client}.
 */
public class BotClientHandler {

    private static volatile Client botClient = null;
    /**
     * Ticket 57: one shared headless client per channel. A bot's channel is its client's: the channel's player
     * storage, map factory and every changeMap resolve through it. Channel 1's is {@link #botClient}.
     */
    private static final java.util.concurrent.ConcurrentHashMap<Integer, Client> byChannel =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Constructs the channel-1 headless bot client. Idempotent — safe to call
     * more than once (only the first call builds the instance). Must run after
     * the channels exist, since the client reports the home world /
     * {@code CHANNEL_1} for routing.
     */
    public static synchronized void initHeadlessBotClient() {
        if (botClient == null) {
            botClient = new BotClient(GameConstants.homeWorld(), GameConstants.CHANNEL_1);
            byChannel.put(GameConstants.CHANNEL_1, botClient);
        }
    }

    /** The channel-1 headless client. Null until {@link #initHeadlessBotClient()} runs. */
    public static Client getBotClient() {
        return botClient;
    }

    /** The shared headless client of one channel of the home world (ticket 57); channel 1's is {@link #getBotClient()}. */
    public static Client getBotClient(int channel) {
        if (channel == GameConstants.CHANNEL_1 && botClient != null) {
            return botClient;
        }
        return byChannel.computeIfAbsent(channel, ch -> new BotClient(GameConstants.homeWorld(), ch));
    }
}
