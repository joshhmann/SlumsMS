package soloMapling.dashboard;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 157: live.json's "switches", the game's effective on/off settings, with nothing secret in them. */
class EffectiveSwitchesTest {

    @Test
    void gameSwitchesAndConfigTogglesAreListedAndSecretsAndPathsAreNot() {
        Properties p = new Properties();
        p.setProperty("slums.qa", "true");
        p.setProperty("solomapling.enabled", "true");
        p.setProperty("cashshop.window.enabled", "true");
        p.setProperty("weather.enabled", "false");
        p.setProperty("mapleroot.login-port", "8489");
        p.setProperty("slums.auth.secretFile", "/etc/x");      // a secret's name
        p.setProperty("slums.patch.AccessKey", "abc");         // key, any case
        p.setProperty("slums.db.password", "hunter2");
        p.setProperty("slums.discord.token", "t");
        p.setProperty("slums.autoqa.dir", "data/autoqa");      // a path
        p.setProperty("slums.llm.url", "http://192.168.0.17:8095/v1");
        p.setProperty("slums.win.dir", "C:\\games");
        p.setProperty("java.home", "/usr/lib/jvm");             // not a game setting
        p.setProperty("bot.nav.cacheDir", "cache");

        Map<String, String> s = EffectiveSwitches.of(p, Map.of("OWNED_ALTS_ENABLED", true, "USE_FAMILY_SYSTEM", false,
                "ENABLE_PIC", false));

        assertEquals("true", s.get("slums.qa"));
        assertEquals("true", s.get("solomapling.enabled"));
        assertEquals("true", s.get("cashshop.window.enabled"));
        assertEquals("false", s.get("weather.enabled"));
        assertEquals("8489", s.get("mapleroot.login-port"));
        assertEquals("true", s.get("config.OWNED_ALTS_ENABLED"));
        assertEquals("false", s.get("config.USE_FAMILY_SYSTEM"));
        for (String gone : new String[]{"slums.auth.secretFile", "slums.patch.AccessKey", "slums.db.password",
                "slums.discord.token", "slums.autoqa.dir", "slums.llm.url", "slums.win.dir", "java.home",
                "bot.nav.cacheDir"}) {
            assertFalse(s.containsKey(gone), gone);
        }
        assertEquals(8, s.size(), s.toString());
    }

    @Test
    void everyOnOffFieldOfTheServerConfigIsAToggle() {
        assertTrue(EffectiveSwitches.configToggleNames().containsAll(java.util.List.of("OWNED_ALTS_ENABLED",
                "OWNED_ALTS_SUMMON_ENABLED", "BLOCK_GENERATE_CASH_ITEM", "USE_FAMILY_SYSTEM")));
        assertTrue(EffectiveSwitches.configToggleNames().stream().noneMatch(EffectiveSwitches::hidden));
    }
}
