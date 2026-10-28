package net.server.coordinator.session;

import client.Client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * slums.dll sends its build stamp (CP CLIENT_BUILD_STAMP) only on the login connection, before the password. The
 * channel session that follows is a new {@link Client}, so the stamp is carried across here, keyed by the character
 * in transition: remembered when a session hands a character over (login -> channel, channel change, cash shop) and
 * restored by the session that takes it. Without it a channel session reads as build 0.
 */
public final class ClientStampCarry {

    private record Stamp(int format, int build, String release, int caps) {
    }

    private static final Map<Integer, Stamp> pending = new ConcurrentHashMap<>();

    private ClientStampCarry() {
    }

    public static void remember(int characterId, Client from) {
        if (from == null) {
            return;
        }
        if (from.getClientStampFormat() <= 0 && from.getClientBuild() <= 0) {
            pending.remove(characterId);
            return;
        }
        pending.put(characterId, new Stamp(from.getClientStampFormat(), from.getClientBuild(), from.getClientRelease(),
                from.getClientCaps()));
    }

    /** Puts the remembered stamp on the new session; a session that already has one (it sent its own) keeps it. */
    public static void restore(int characterId, Client to) {
        Stamp s = pending.remove(characterId);
        if (s == null || to == null || to.getClientStampFormat() > 0) {
            return;
        }
        to.setClientStamp(s.format(), s.build(), s.release(), s.caps());
    }

    static int pendingCount() {
        return pending.size();
    }
}
