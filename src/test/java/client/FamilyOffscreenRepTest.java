package client;

import constants.game.ExpTable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * b035: a bot junior grinding off screen levels up through {@link Character#applyOffscreenExp}, which never called the
 * family rep hook a real level-up ({@code Character.levelUp}) and a real kill ({@code Monster}) use, so its senior got
 * nothing while it trained. Its off-screen levels now give the senior FAMILY_REP_PER_LEVELUP each, under the bot cap.
 */
class FamilyOffscreenRepTest {

    @Test
    void aBotJuniorsOffscreenLevelUpGivesItsSeniorRep() {
        Character bot = Character.getDefault(new BotClient(0, 0));
        bot.setID(1_000_000_555);
        bot.setName("Grinder");
        bot.setJob(Job.WARRIOR);
        bot.setLevel(30);
        Family fam = mock(Family.class);
        FamilyEntry senior = new FamilyEntry(fam, 777, "Senior", 50, null);
        FamilyEntry junior = new FamilyEntry(fam, bot.getId(), "Grinder", 30, Job.WARRIOR);
        junior.setSenior(senior, false);
        junior.setCharacter(bot);

        var cfg = config.YamlConfig.config.server;
        int levelWas = cfg.FAMILY_REP_PER_LEVELUP;
        cfg.FAMILY_REP_PER_LEVELUP = 200; // live's tuning (the test config has none)
        try {
            int levels = bot.applyOffscreenExp(ExpTable.getExpNeededForLevel(30) + 1, 200);
            assertEquals(1, levels);
            assertEquals(200, senior.getReputation(), "the senior gets the level-up's rep");
        } finally {
            cfg.FAMILY_REP_PER_LEVELUP = levelWas;
        }
    }
}
