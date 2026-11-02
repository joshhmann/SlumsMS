package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Character;
import client.Job;
import client.SkillFactory;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.WeaponType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.maps.MapleMap;
import soloMapling.WzTestData;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.3 t-rb-2 (TEST 2026-11-02 00:15 UTC): a level 200 Dark Knight with 175 of 1495 MP and no MP potions swung
 * plain attacks for a whole Von Leon run - the attack stats read {@code 80 swings 0 kills {0=80}, ... HP 6730/6730
 * MP 175/1495}. The rotation's La Mancha Spear extra (200 MP) was picked on every single swing: its WZ cooltime only
 * starts when the skill really fires, and a cast the bot cannot pay for is sent as the weapon's plain swing
 * (SwingSupply), so the extra stayed "ready" forever and the bot never fell through to Dark Impale (80 MP, which it
 * could have paid). A rotation extra must be one the bot can pay for.
 */
class ExtraPayableTest {

    private static final int LA_MANCHA_SPEAR = 1321011;   // the Dark Knight's rotation extra, 200 MP
    private static final long NOW = 1_000_000L;

    private BotAttackDriver.Ledges ledgesBefore;
    private Monster mob;

    @BeforeEach
    void stubTheMobInReach() {
        WzTestData.assumePresent("Skill.wz");
        SkillFactory.loadAllSkills();
        ledgesBefore = BotAttackDriver.ledges;
        BotAttackDriver.ledges = (map, a, b) -> false; // a mocked map has no nav graph
        mob = mock(Monster.class);
        when(mob.isAlive()).thenReturn(true);
        when(mob.getPosition()).thenReturn(new Point(0, 0));
        when(mob.isBoss()).thenReturn(false);
        when(mob.getStats()).thenReturn(null);
    }

    @AfterEach
    void restoreTheLedges() {
        BotAttackDriver.ledges = ledgesBefore;
    }

    @Test
    void anExtraTheBotCannotPayForIsNotPicked() {
        Character bot = darkKnight(1, 175);

        assertNull(readyExtra(bot, NOW), "La Mancha Spear costs 200 MP and the bot holds 175 with no potion to drink");
    }

    @Test
    void anExtraTheBotCanPayForIsStillPicked() {
        Character bot = darkKnight(2, 1495);

        assertNotNull(readyExtra(bot, NOW), "1495 MP pays for La Mancha Spear's 200");
    }

    private BotAttackConfig.Extra readyExtra(Character bot, long now) {
        BotAttackConfig.JobAttacks atks = BotAttackConfig.resolve(Job.DARKKNIGHT, WeaponType.SPEAR_SWING,
                BotAttackDriver.ownedSkills(bot));
        assertEquals(LA_MANCHA_SPEAR, atks.extras().getFirst().profile().skillId, "the Dark Knight's extra");
        return BotAttackDriver.readyExtra(bot, atks.extras(), false, now);
    }

    /** A 4th-job Dark Knight 200 with the two spear skills and the MP the raise left it (t-rb-2's 175 of 1495). */
    private Character darkKnight(int id, int mp) {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(id);
        when(bot.getJob()).thenReturn(Job.DARKKNIGHT);
        when(bot.getLevel()).thenReturn(200);
        when(bot.getPosition()).thenReturn(new Point(0, 0));
        when(bot.getSkillLevel(LA_MANCHA_SPEAR)).thenReturn(5);
        when(bot.getSkillLevel(1321012)).thenReturn(5); // Dark Impale, 80 MP: affordable at 175, and never reached
        when(bot.getMp()).thenReturn(mp);
        when(bot.getCurrentMaxMp()).thenReturn(1495);
        Inventory use = mock(Inventory.class);
        when(use.list()).thenReturn(List.of()); // no MP potions, as the raise left it
        when(bot.getInventory(InventoryType.USE)).thenReturn(use);
        MapleMap map = mock(MapleMap.class);
        when(map.getMapObjectsInRange(any(Point.class), anyDouble(), anyList())).thenReturn(List.of(mob));
        when(bot.getMap()).thenReturn(map);
        BotAttackDriver.setTargetFilter(id, m -> true); // the KS manners would ask a mock questions it cannot answer
        return bot;
    }
}
