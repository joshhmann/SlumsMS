package net.server.handlers.login;

import client.Client;
import config.ServerConfig;
import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;
import tools.PacketCreator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.IntUnaryOperator;

/**
 * Refuses a real player's login when their client is older than MIN_CLIENT_BUILD (0 = off). The build comes from
 * ClientBuildStampHandler; a client without the stamp (slums.dll 1.4.1 and older) counts as 0. Bots never send a
 * password packet, so they never reach this. Checked as soon as the password is verified: before an already-logged-in
 * session is kicked, and before AUTOMATIC_REGISTER creates an account.
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
    /**
     * Ticket 170: the QA client build's caps bit in the format-2 stamp (client kCapQaBuild, bit 31). A QA build also
     * stamps its release "<version>-qa". It never connects to live: refused unless {@link #ALLOW_QA} is on.
     */
    public static final int CAP_QA_BUILD = 0x80000000;
    /** {@code -Dslums.client.allowQa=true}: TEST and RC accept the QA client build. Off (refused) by default. */
    public static final String ALLOW_QA = "slums.client.allowQa";
    static final String QA_BUILD_MESSAGE =
            "This is a QA test client: it only connects to the test server. Open the SlumsMS launcher to play.";

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

    /**
     * Auto-QA (t-vg-1, soloMapling.autoqa): the gate's verdict for a client build against a minimum, with no GM bypass,
     * and the line such a client is shown. Uses a scratch config; the live one is not touched.
     */
    public static String verdictFor(int clientBuild, int minimumBuild) {
        ServerConfig scratch = new ServerConfig();
        scratch.MIN_CLIENT_BUILD = minimumBuild;
        scratch.MIN_CLIENT_BUILD_GM_BYPASS = false;
        scratch.CLIENT_UPDATE_MESSAGE = config.YamlConfig.config.server.CLIENT_UPDATE_MESSAGE;
        return outdated(clientBuild, 0, scratch) ? "REFUSED: " + message(scratch) : "ALLOWED";
    }

    static String message(ServerConfig config) {
        String text = config.CLIENT_UPDATE_MESSAGE;
        return text == null || text.isBlank() ? "Your game is out of date: open the SlumsMS launcher to update." : text;
    }

    /**
     * True for the login results that mean the password checked out: 0 ok, 7 already logged in (Client.login decides
     * 7 only after the password), 23 / -23 terms not accepted, -10 bcrypt migration due.
     */
    static boolean passwordVerified(int loginok) {
        return loginok == 0 || loginok == 7 || loginok == 23 || loginok == -23 || loginok == -10;
    }

    /** True when the client is a QA build and this server does not take one (ticket 170). No GM bypass. */
    static boolean qaBuildRefused(int clientCaps, boolean allowQa) {
        return (clientCaps & CAP_QA_BUILD) != 0 && !allowQa;
    }

    /** Refuse (popup + silent login failure) and return true, or return false to let the login continue. */
    static boolean refuseIfOutdated(Client c, String login) {
        return refuseIfOutdated(c, login, YamlConfig.config.server, ClientVersionGate::accountGmLevel);
    }

    /**
     * {@code gmLevelOfAccount} reads the account's real GM level. Client.getGMLevel() can't be used: Client.login sets
     * it to 0 and only the character list loads the real one, which is after this gate, so the bypass never applied.
     * It is asked only when the client is outdated and the bypass is on.
     */
    static boolean refuseIfOutdated(Client c, String login, ServerConfig config, IntUnaryOperator gmLevelOfAccount) {
        return refuseIfOutdated(c, login, config, gmLevelOfAccount, Boolean.getBoolean(ALLOW_QA));
    }

    /** As above; {@code allowQa} is {@link #ALLOW_QA} (tests hand it in). The QA build check runs first. */
    static boolean refuseIfOutdated(Client c, String login, ServerConfig config, IntUnaryOperator gmLevelOfAccount,
                                    boolean allowQa) {
        if (qaBuildRefused(c.getClientCaps(), allowQa)) {
            log.info("Client version gate: refused '{}' from {}: QA client build {} ({}); -D{} is off", login,
                    c.getRemoteAddress(), c.getClientBuild(), c.getClientRelease(), ALLOW_QA);
            c.sendPacket(PacketCreator.serverNotice(1, QA_BUILD_MESSAGE));
            c.sendPacket(PacketCreator.getLoginFailed(SILENT_LOGIN_FAILED));
            return true;
        }
        int build = c.getClientBuild();
        if (config.MIN_CLIENT_BUILD <= 0 || build >= config.MIN_CLIENT_BUILD) {
            return false;
        }
        int gmLevel = config.MIN_CLIENT_BUILD_GM_BYPASS ? gmLevelOfAccount.applyAsInt(c.getAccID()) : 0;
        if (!outdated(build, gmLevel, config)) {
            log.info("Client version gate: GM account '{}' (level {}) allowed with old client build {} (minimum {})", login, gmLevel, build, config.MIN_CLIENT_BUILD);
            return false;
        }
        log.info("Client version gate: refused '{}' from {}: client build {}{} < minimum {}", login, c.getRemoteAddress(), build,
                c.getClientRelease().isEmpty() ? " (no stamp)" : " (" + c.getClientRelease() + ")", config.MIN_CLIENT_BUILD);
        c.sendPacket(PacketCreator.serverNotice(1, message(config)));
        c.sendPacket(PacketCreator.getLoginFailed(SILENT_LOGIN_FAILED));
        return true;
    }

    /** The account's GM level: the highest characters.gm on it (the level lives on each character). 0 if none. */
    static int accountGmLevel(int accountId) {
        if (accountId <= 0) {
            return 0;
        }
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT MAX(gm) FROM characters WHERE accountid = ?")) {
            ps.setInt(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            log.warn("Client version gate: couldn't read the GM level of account {}", accountId, e);
            return 0;
        }
    }
}
