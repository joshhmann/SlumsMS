package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.skills.Cleric;
import net.opcodes.SendOpcode;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import server.maps.MapleMap;
import soloMapling.qa.QaLog;
import soloMapling.qa.QaLogTestAccess;

import java.awt.Point;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/*
 * b140: a viewer's client draws the Heal light column from the MAGIC_ATTACK packet a player's Heal produces, not from
 * the buff cast effect. A bot's party Heal broadcasts that packet (skill 2301002, its Skill.wz pose alert2 = 41, the
 * stock echo's speed 7, no mobs) and writes a COMBAT line.
 */
class BotPartyHealCastTest {

    private static final int BOT = 1_000_000_000 + 11;
    private static final int STAFF = 1382000;

    @TempDir
    Path dir;

    @BeforeEach
    void clientData() {
        SkillPoseFixture.install();
    }

    @AfterEach
    void clean() {
        QaLogTestAccess.reset();
    }

    private static Character cleric(MapleMap map, int weaponId) {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(BOT);
        when(bot.getName()).thenReturn("HealBot");
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(101000000);
        when(bot.getPosition()).thenReturn(new Point(500, 100));
        Inventory equipped = mock(Inventory.class);
        Item weapon = weaponId > 0 ? new Item(weaponId, (short) -11, (short) 1) : null;
        when(equipped.getItem((short) -11)).thenReturn(weapon);
        when(bot.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
        return bot;
    }

    private static Character player(int x) {
        Character p = mock(Character.class);
        when(p.getId()).thenReturn(77);
        when(p.getName()).thenReturn("Owner");
        when(p.getPosition()).thenReturn(new Point(x, 100));
        return p;
    }

    private static ByteBuffer bytes(Packet p) {
        return ByteBuffer.wrap(p.getBytes()).order(ByteOrder.LITTLE_ENDIAN);
    }

    @Test
    void theHealCastBroadcastsThePlayersHealMagicAttack() {
        MapleMap map = mock(MapleMap.class);
        Character bot = cleric(map, STAFF);

        BotSwingPlan plan = BotPartySupport.showHealCast(bot, player(200), 30);

        assertNotNull(plan, "a Heal cast plan");
        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(map).broadcastMessage(eq(bot), sent.capture(), eq(false));
        ByteBuffer b = bytes(sent.getValue());
        assertEquals(SendOpcode.MAGIC_ATTACK.getValue(), b.getShort(0), "sent as a magic attack");
        assertEquals(BOT, b.getInt(2), "by the bot");
        assertEquals(0x02, b.get(6), "no mobs (high nibble), Heal's 2 lines (low nibble)");
        assertEquals(30, b.get(8), "skill level");
        assertEquals(Cleric.HEAL, b.getInt(9), "skill 2301002");
        assertEquals(0, b.get(13), "display");
        assertEquals(41, b.get(14), "action: Heal's Skill.wz pose alert2 (client code 41)");
        assertEquals(BotSkillActions.codeOf("alert2"), b.get(14));
        assertEquals((byte) BotAttackData.FACING_LEFT_MASK, b.get(15), "stance: facing the player on its left");
        assertEquals(BotAttackEffects.HEAL_ECHO_SPEED, b.get(16), "the speed the stock echo of a Heal carries");
        assertEquals(sent.getValue().getBytes().length, 17 + 1 + 4, "no target entries, no charge int");
    }

    @Test
    void aBotWithoutAWeaponStillCastsHealsOwnPose() {
        MapleMap map = mock(MapleMap.class);
        Character bot = cleric(map, 0);
        BotSwingPlan plan = BotPartySupport.showHealCast(bot, player(900), 1);
        assertNotNull(plan);
        assertEquals(Cleric.HEAL, plan.skillId());
        assertEquals(41, plan.action());
        assertEquals(BotAttackData.FACING_RIGHT_MASK, plan.facingMask(), "facing the player on its right");
        verify(map).broadcastMessage(eq(bot), org.mockito.ArgumentMatchers.any(Packet.class), eq(false));
    }

    @Test
    void eachHealCastIsLoggedUnderCombatToTheHealedPlayersRunOnAnyMap() throws Exception {
        List<String> lines = new ArrayList<>();
        // the healed player (id 77) runs a COMBAT scenario that watches the dojo; the heal happens in Ellinia
        QaLogTestAccess.listen(77, java.util.Set.of(925020100), EnumSet.of(QaLog.Topic.COMBAT), dir, lines);
        MapleMap map = mock(MapleMap.class);
        Character bot = cleric(map, STAFF);
        Character target = player(200);
        BotSwingPlan plan = BotPartySupport.showHealCast(bot, target, 30);

        BotPartySupport.logHeal(bot, target, 30, plan, 4_000, 14_700);
        BotPartySupport.logHeal(bot, target, 30, plan, 4_000, 14_700); // inside the 2 s throttle

        List<String> heals = lines.stream().filter(l -> l.contains("PARTY HEAL")).toList();
        assertEquals(List.of("COMBAT PARTY HEAL HealBot -> Owner skill 2301002 lv 30 action 41 stance 128 hp 4000->14700"),
                heals);
    }

    @Test
    void nothingIsLoggedWhenCombatIsOff() {
        MapleMap map = mock(MapleMap.class);
        Character bot = cleric(map, STAFF);
        BotPartySupport.logHeal(bot, player(200), 30, null, 1, 2); // no run: no error, nothing to hear
        assertTrue(!QaLog.on(QaLog.Topic.COMBAT));
    }
}
