package net.server.coordinator.login;

import config.YamlConfig;
import net.server.coordinator.session.Hwid;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-6 (HOLES #5): {@code !enableauth} calls {@code unregisterLoginBypassEntry}, which built a
 * {@code Pair<String, Integer>} while {@code registerLoginBypassEntry} stored a {@code Pair<Hwid, Integer>}. Pair.equals
 * compares the parts by their own equals, so the removal never matched and the command did nothing: the account kept
 * skipping the PIN/PIC check until the entry expired on its own.
 */
class LoginBypassCoordinatorTest {

    private static final Hwid HWID = new Hwid("01234567");
    private static final Hwid OTHER_HWID = new Hwid("89ABCDEF");

    private int pinExpiration;
    private int picExpiration;

    @BeforeEach
    void registerableBypasses() throws Exception {
        pinExpiration = YamlConfig.config.server.BYPASS_PIN_EXPIRATION;
        picExpiration = YamlConfig.config.server.BYPASS_PIC_EXPIRATION;
        YamlConfig.config.server.BYPASS_PIN_EXPIRATION = 30;
        YamlConfig.config.server.BYPASS_PIC_EXPIRATION = 30;
        forgetEverything();
    }

    @AfterEach
    void restore() throws Exception {
        YamlConfig.config.server.BYPASS_PIN_EXPIRATION = pinExpiration;
        YamlConfig.config.server.BYPASS_PIC_EXPIRATION = picExpiration;
        forgetEverything();
    }

    /** The coordinator is a singleton, so each test has to start with no bypass entry at all. */
    private static void forgetEverything() throws Exception {
        Field map = LoginBypassCoordinator.class.getDeclaredField("loginBypass");
        map.setAccessible(true);
        ((Map<?, ?>) map.get(LoginBypassCoordinator.getInstance())).clear();
    }

    @Test
    void enableauthRemovesTheEntryRegisterMadeForThatHwidAndAccount() {
        LoginBypassCoordinator coordinator = LoginBypassCoordinator.getInstance();
        coordinator.registerLoginBypassEntry(HWID, 1, true);
        assertTrue(coordinator.canLoginBypass(HWID, 1, true), "register made the bypass");

        coordinator.unregisterLoginBypassEntry(HWID, 1);

        assertFalse(coordinator.canLoginBypass(HWID, 1, true),
                "!enableauth must take the bypass away, so the next login asks for the PIC again");
    }

    @Test
    void anotherAccountsBypassStays() {
        LoginBypassCoordinator coordinator = LoginBypassCoordinator.getInstance();
        coordinator.registerLoginBypassEntry(HWID, 1, true);
        coordinator.registerLoginBypassEntry(HWID, 2, true);
        coordinator.registerLoginBypassEntry(OTHER_HWID, 1, true);

        coordinator.unregisterLoginBypassEntry(HWID, 1);

        assertFalse(coordinator.canLoginBypass(HWID, 1, true), "the removed account's bypass is gone");
        assertTrue(coordinator.canLoginBypass(HWID, 2, true), "the same machine's other account keeps its bypass");
        assertTrue(coordinator.canLoginBypass(OTHER_HWID, 1, true), "another machine's account keeps its bypass");
    }

    @Test
    void unregisteringSomethingNeverRegisteredChangesNothing() {
        LoginBypassCoordinator coordinator = LoginBypassCoordinator.getInstance();
        coordinator.registerLoginBypassEntry(HWID, 1, true);

        coordinator.unregisterLoginBypassEntry(OTHER_HWID, 1);
        coordinator.unregisterLoginBypassEntry(null, 1);

        assertTrue(coordinator.canLoginBypass(HWID, 1, true), "an unrelated removal must not clear this entry");
    }
}
