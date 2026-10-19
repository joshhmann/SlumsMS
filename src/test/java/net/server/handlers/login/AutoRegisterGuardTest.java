package net.server.handlers.login;

import config.YamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AutoRegisterGuardTest {

    private static final String PUBLIC_IP = "203.0.113.7";
    private static final long NOW = 1_800_000_000_000L;

    private int accounts;

    @BeforeEach
    void setUp() {
        var server = YamlConfig.config.server;
        server.AUTO_REGISTER_MIN_PASSWORD_LENGTH = 6;
        server.AUTO_REGISTER_MAX_ACCOUNTS_PER_IP = 3;
        server.AUTO_REGISTER_IP_COOLDOWN_MINUTES = 10;
        accounts = 0;
        AutoRegisterGuard.useForTest(ip -> accounts);
    }

    @AfterEach
    void tearDown() {
        AutoRegisterGuard.useForTest(null);
    }

    @Test
    void aShortPasswordIsRefusedEvenOnTheLan() {
        assertEquals(AutoRegisterGuard.Refusal.PASSWORD_TOO_SHORT, AutoRegisterGuard.check(PUBLIC_IP, "abc", NOW));
        assertEquals(AutoRegisterGuard.Refusal.PASSWORD_TOO_SHORT, AutoRegisterGuard.check("192.168.0.1", "abc", NOW));
    }

    @Test
    void anIpAtTheAccountCapIsRefused() {
        accounts = 2;
        assertNull(AutoRegisterGuard.check(PUBLIC_IP, "secret1", NOW));
        accounts = 3;
        assertEquals(AutoRegisterGuard.Refusal.TOO_MANY_ACCOUNTS, AutoRegisterGuard.check(PUBLIC_IP, "secret1", NOW));
    }

    @Test
    void oneIpWaitsOutTheCooldownBetweenNewAccounts() {
        AutoRegisterGuard.recordRegistration(PUBLIC_IP, "first", NOW);

        assertEquals(AutoRegisterGuard.Refusal.TOO_SOON,
                AutoRegisterGuard.check(PUBLIC_IP, "secret1", NOW + TimeUnit.MINUTES.toMillis(9)));
        assertNull(AutoRegisterGuard.check(PUBLIC_IP, "secret1", NOW + TimeUnit.MINUTES.toMillis(10)));
        assertNull(AutoRegisterGuard.check("198.51.100.9", "secret1", NOW), "another address is not held back");
    }

    @Test
    void lanAndHairpinAddressesSkipThePerIpLimits() {
        accounts = 50;
        AutoRegisterGuard.recordRegistration("192.168.0.1", "owner", NOW);

        assertNull(AutoRegisterGuard.check("192.168.0.1", "secret1", NOW + 1), "the router a LAN player comes through");
        assertNull(AutoRegisterGuard.check("10.0.0.5", "secret1", NOW));
        assertNull(AutoRegisterGuard.check("127.0.0.1", "secret1", NOW));
    }

    @Test
    void aZeroCapMeansNoAccountCap() {
        YamlConfig.config.server.AUTO_REGISTER_MAX_ACCOUNTS_PER_IP = 0;
        accounts = 1_000;
        assertNull(AutoRegisterGuard.check(PUBLIC_IP, "secret1", NOW));
    }
}
