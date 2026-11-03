package client;

import io.netty.handler.timeout.IdleStateEvent;
import net.packet.Packet;

/*
 * A single shared headless Client for SoloMapling bots.
 * Credits to NutNNut for creating this headless System (c) 2026.
 */
public class BotClient extends Client {

    public BotClient(int world, int channel) {
        // (Type, sessionId, remoteAddress, PacketProcessor, world, channel)
        // No type/session/processor/socket — only world+channel are load-bearing
        // (routing). A dummy "bot" remote address keeps any logging path safe.
        super(null, -1, "bot", null, world, channel);
    }

    /*
     * No-op: a bot has no network socket. The base sendPacket does
     * ioChannel.writeAndFlush(packet) with no null guard, so this is the
     * core NPE fix. It also covers every announce* helper, which all
     * funnel through sendPacket.
     */
    @Override
    public void sendPacket(Packet packet) {
        // packets to a bot fizzle out — no socket, no recipients
    }

    /*
     * Bots must always read as logged-in: shared player-code paths gate on this.
     * (This is independent of Character.isLoggedin(), which stays false
     * for bots, so autosave/disconnect loops still skip them.)
     */
    /**
     * A bot has no boss HP bar to show, and the shared client has no player for the base method to track it on:
     * it threw when a boss died on a map with bots (map dispose, and any boss kill near bots).
     */
    @Override
    public void announceBossHpBar(server.life.Monster mm, int mobHash, net.packet.Packet packet) {
    }

    @Override
    public boolean isLoggedIn() {
        return true;
    }

    /**
     * The client a script runs with when a <b>population bot</b> acts: its {@code getPlayer()} is the bot.
     *
     * <p>Every population bot shares its channel's one headless client ({@code BotClientHandler}), and that client has no
     * player. A reactor hit, a reactor an item is dropped on and an NPC conversation all hand their script that client
     * ({@code Reactor.hitReactor(c)}, {@code MapleMap.activateItemReactors}, {@code NPCConversationManager.getClient()}),
     * and the scripts reach the character through it: {@code ReactorActionManager.dropItems} returns without a drop when
     * {@code c.getPlayer()} is null ({@code ReactorActionManager.java:157-159}), and {@code rm.getPlayer().getEventInstance()}
     * - the first line of Guild Quest's statues ({@code scripts/reactor/9208000.js:36}), spear altars and gate - throws.
     * A client bound to the bot gives them the player they ask for and is otherwise the headless client (same world and
     * channel, no socket, every packet dropped). It is made per action: nothing is written to the shared client, so two
     * bots acting at once never see each other's player.
     *
     * <p>Only {@code getPlayer()} differs from the shared client. In particular the script-engine cache ({@code
     * AbstractScriptManager.getInvocableScriptEngine(path, c)} keeps one engine per script path <em>on the client</em>) is the
     * shared client's own, delegated, so a bound client neither builds a new Graal engine per hit nor splits the cache the
     * shared client has always had. The bound client is for a script to read its player through; it is never sent a packet
     * (the headless client drops them anyway), never stored on the character and never the character's own client.
     *
     * <p>Anyone who has a client of their own - a real player, an owned alt - gets it back unchanged.
     */
    public static Client forScripts(Character bot) {
        Client own = bot == null ? null : bot.getClient();
        if (!(own instanceof BotClient) || own.getPlayer() != null) {
            return own;
        }
        return new BotClient(own.getWorld(), own.getChannel()) {
            @Override
            public Character getPlayer() {
                return bot;
            }

            @Override
            public void setScriptEngine(String name, javax.script.ScriptEngine e) {
                own.setScriptEngine(name, e);
            }

            @Override
            public javax.script.ScriptEngine getScriptEngine(String name) {
                return own.getScriptEngine(name);
            }

            @Override
            public void removeScriptEngine(String name) {
                own.removeScriptEngine(name);
            }
        };
    }

    /*
     * Skip the DB write and SessionCoordinator registration a real login state
     * change performs. A headless client must never write login-state rows
     * (the base method UPDATEs accounts keyed on getAccID()) or
     * register/deregister online sessions.
     */
    @Override
    public void updateLoginState(int newState) {
        // headless: no account row, no session to register
    }

    /* No-op: base does ioChannel.disconnect(). Bots never disconnect. */
    @Override
    public void disconnectSession() {
        // no socket to disconnect
    }

    /* No-op: base does ioChannel.close(). Bots have no session to close. */
    @Override
    public void closeSession() {
        // no socket to close
    }

    /*
     * No-op: the base idle checker pings, then dereferences
     * ioChannel.isActive() and may disconnect. A bot is never part of a
     * netty pipeline so this is unreachable in practice, but neutralizing it
     * guarantees the inactivity reaper can never touch the shared bot client.
     */
    @Override
    public void checkIfIdle(final IdleStateEvent event) {
        // never reap the headless bot client
    }

    /*
     * Always report "just now" so any timeout task sees the client as freshly
     * active and never considers the shared bot client idle.
     */
    @Override
    public long getLastPacket() {
        return System.currentTimeMillis();
    }
}
