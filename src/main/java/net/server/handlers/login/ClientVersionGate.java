package net.server.handlers.login;

import client.Client;
import config.ServerConfig;
import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.PacketCreator;

/**
 * Refuses a real player's login when their client is older than MIN_CLIENT_BUILD (0 = off). The build comes from
 * ClientBuildStampHandler; a client without the stamp (slums.dll 1.4.1 and older) counts as 0. Bots never send a
 * password packet, so they never reach this. Checked after the password is verified and before the character list.
 *
 * What the player sees: a server notice popup (BROADCAST_MSG type 1; opcodes 0x1D..0x7C go to CWvsContext in every
 * stage, so the v83 login screen shows it) with CLIENT_UPDATE_MESSAGE, followed by login-failed reason 1, which the
 * v83 CLogin::OnCheckPasswordResult (0x5F9891) handles silently: it clears the "request sent" flag and shows no dialog
 * of its own, so the only text is ours and the login box works again. The stock reasons with text (3 blocked, 4 wrong
 * password, 5 not registered, 6/8/9 system error, 7 logged in, 10 busy, ...) would all mislead.
 */
public final class ClientVersionGate {
    private static final Logger log = LoggerFactory.getLogger(ClientVersionGate.class);
    /** Login-failed reason with no client dialog (see above). */
    static final int SILENT_LOGIN_FAILED = 1;

    private ClientVersionGate() {
    }

    /** True when this login must be refused. */
    static boolean outdated(int clientBuild, int gmLevel, ServerConfig config) {
        int minimum = config.MIN_CLIENT_BUILD;
        if (minimum <= 0 || clientBuild >= minimum) {
            return false;
        }
        return !(config.MIN_CLIENT_BUILD_GM_BYPASS && gmLevel > 0);
    }

    static String message(ServerConfig config) {
        String text = config.CLIENT_UPDATE_MESSAGE;
        return text == null || text.isBlank() ? "Your game is out of date: open the SlumsMS launcher to update." : text;
    }

    /** Refuse (popup + silent login failure) and return true, or return false to let the login continue. */
    static boolean refuseIfOutdated(Client c, String login) {
        ServerConfig config = YamlConfig.config.server;
        if (!outdated(c.getClientBuild(), c.getGMLevel(), config)) {
            if (config.MIN_CLIENT_BUILD > 0 && c.getClientBuild() < config.MIN_CLIENT_BUILD) {
                log.info("Client version gate: GM account '{}' allowed with old client build {} (minimum {})", login, c.getClientBuild(), config.MIN_CLIENT_BUILD);
            }
            return false;
        }
        log.info("Client version gate: refused '{}' from {}: client build {}{} < minimum {}", login, c.getRemoteAddress(), c.getClientBuild(),
                c.getClientRelease().isEmpty() ? " (no stamp)" : " (" + c.getClientRelease() + ")", config.MIN_CLIENT_BUILD);
        c.sendPacket(PacketCreator.serverNotice(1, message(config)));
        c.sendPacket(PacketCreator.getLoginFailed(SILENT_LOGIN_FAILED));
        return true;
    }
}
