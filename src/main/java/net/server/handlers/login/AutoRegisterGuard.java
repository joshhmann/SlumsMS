package net.server.handlers.login;

import client.ReservedNames;
import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Limits on automatic registration ("log in with a new name to create it") for a public server: a minimum password
 * length, a cap on accounts per IP and a cooldown between new accounts from one IP. Addresses on the local network
 * (and the router a LAN player reaches the public address through) are exempt from the per-IP limits.
 */
public final class AutoRegisterGuard {

    private static final Logger log = LoggerFactory.getLogger(AutoRegisterGuard.class);

    /** Why a registration was refused; each maps to the login-failed reason the client shows. */
    public enum Refusal {
        RESERVED_NAME(5),        // "Not a registered ID": staff-looking names are never created (ticket 43)
        PASSWORD_TOO_SHORT(4),   // "Incorrect password"
        TOO_MANY_ACCOUNTS(10),   // "Cannot process so many connections"
        TOO_SOON(10);

        public final int loginFailedReason;

        Refusal(int loginFailedReason) {
            this.loginFailedReason = loginFailedReason;
        }
    }

    interface AccountCounter {
        int accountsLastSeenFrom(String ip) throws SQLException;
    }

    private static final Map<String, Long> lastRegistrationByIp = new ConcurrentHashMap<>();
    private static volatile AccountCounter counter = AutoRegisterGuard::countFromDatabase;

    private AutoRegisterGuard() {
    }

    /** Null when the account may be created; otherwise why not. Does not record anything. */
    public static Refusal check(String ip, String login, String password, long now) {
        if (ReservedNames.isReservedAccountName(login)) {
            return Refusal.RESERVED_NAME;
        }
        return check(ip, password, now);
    }

    /** The checks that do not depend on the account name. */
    public static Refusal check(String ip, String password, long now) {
        var server = YamlConfig.config.server;
        if (password == null || password.length() < server.AUTO_REGISTER_MIN_PASSWORD_LENGTH) {
            return Refusal.PASSWORD_TOO_SHORT;
        }
        if (isLocal(ip)) {
            return null;
        }
        Long last = lastRegistrationByIp.get(ip);
        if (last != null && now - last < TimeUnit.MINUTES.toMillis(server.AUTO_REGISTER_IP_COOLDOWN_MINUTES)) {
            return Refusal.TOO_SOON;
        }
        if (server.AUTO_REGISTER_MAX_ACCOUNTS_PER_IP > 0) {
            try {
                if (counter.accountsLastSeenFrom(ip) >= server.AUTO_REGISTER_MAX_ACCOUNTS_PER_IP) {
                    return Refusal.TOO_MANY_ACCOUNTS;
                }
            } catch (SQLException e) {
                log.warn("Could not count accounts for {}; allowing the registration", ip, e);
            }
        }
        return null;
    }

    /** Called once an account was created from this IP. */
    public static void recordRegistration(String ip, String login, long now) {
        if (!isLocal(ip)) {
            lastRegistrationByIp.put(ip, now);
        }
        log.info("New account '{}' registered from {}", login, ip);
    }

    /** Loopback and private (RFC 1918 / link-local) addresses: the owner's LAN, including hairpin through the router. */
    static boolean isLocal(String ip) {
        try {
            InetAddress address = InetAddress.getByName(ip);
            return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress();
        } catch (UnknownHostException | SecurityException e) {
            return false;
        }
    }

    // accounts.ip is the address of each account's last login, so this counts accounts used from this address.
    private static int countFromDatabase(String ip) throws SQLException {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM accounts WHERE ip = ?")) {
            ps.setString(1, ip);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Test seam: a fake account counter (null restores the database) and a clean cooldown table. */
    static void useForTest(AccountCounter testCounter) {
        counter = testCounter == null ? AutoRegisterGuard::countFromDatabase : testCounter;
        lastRegistrationByIp.clear();
    }
}
