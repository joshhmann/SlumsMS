package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Character;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.server.BotAttackPackets;
import soloMapling.server.BotAttackPackets.AttackTarget;
import tools.PacketCreator;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
 * The validator BotAttackClientValidityTest relies on catches what the v83 client drops: each packet here is one the
 * client is known (from its code) to drop or choke on, and one it draws.
 */
class ClientAttackRulesTest {

    private static final ClientAttackRules RULES = new ClientAttackRules(ClientAttackData.INSTANCE);
    private static final int SWORD = 1302000, KNUCKLE = 1482000, GUN = 1492000, BOW = 1452000, POLEARM = 1442000;
    private Character bot;

    @BeforeEach
    void setUp() {
        SkillPoseFixture.install();
        bot = mock(Character.class);
        when(bot.getId()).thenReturn(7);
    }

    private List<String> close(int skill, int level, int pose, int weapon, int job) {
        // the raw packet (PacketCreator), without BotAttackPackets' range guard: this tests the model of the client
        return RULES.check(PacketCreator.closeRangeAttack(bot, skill, level, 0, 0x11,
                Map.of(1, List.of(5)), 4, pose, 0).getBytes(),
                new ClientAttackRules.Attacker(weapon, job, 0), null);
    }

    private static boolean drops(List<String> problems) {
        return problems.stream().anyMatch(p -> p.startsWith("drop:") || p.startsWith("crash:"));
    }

    @Test
    void aSkillSentInAPoseItDoesNotAllowIsDropped() {
        assertTrue(drops(close(1121012, 5, 5, SWORD, 112)), "Rising Rage in swingO1");
        assertTrue(close(1121012, 5, 151, SWORD, 112).isEmpty(), "Rising Rage in dragonAura");
    }

    @Test
    void aSkillTheWeaponCannotUseIsDropped() {
        List<String> p = close(5121011, 5, BotSkillActions.codeOf("dragonBreathe"), SWORD, 512);
        assertTrue(p.stream().anyMatch(s -> s.contains("cannot use skill")), p.toString());
    }

    @Test
    void aKnuckleOrGunSwingNeedsItsOwnPlainPoses() {
        assertTrue(drops(close(0, 0, 5, KNUCKLE, 510)), "a knuckle in swingO1");
        assertTrue(close(0, 0, 16, KNUCKLE, 510).isEmpty(), "a knuckle in stabO1");
        List<String> gun = RULES.check(BotAttackPackets.rangedAttack(bot, 0, 0, 0, 0x11, 2330000,
                Map.of(1, new AttackTarget((short) 0, List.of(5))), 4, 16, 0).getBytes(),
                new ClientAttackRules.Attacker(GUN, 520, 0), null);
        assertTrue(drops(gun), "a gun shot in stabO1");
    }

    @Test
    void aShotFromANonShootingWeaponIsDroppedUnlessTheSkillIsOneOfTheExceptions() {
        List<String> sword = RULES.check(BotAttackPackets.rangedAttack(bot, 0, 0, 0, 0x11, 0,
                Map.of(1, new AttackTarget((short) 0, List.of(5))), 4, 5, 0).getBytes(),
                new ClientAttackRules.Attacker(SWORD, 100, 0), null);
        assertTrue(drops(sword));
        List<String> smash = RULES.check(BotAttackPackets.rangedAttack(bot, 21100004, 10, 0, 0x12, 0,
                Map.of(1, new AttackTarget((short) 0, List.of(5, 6))), 4, BotSkillActions.codeOf("comboSmash"), 0)
                .getBytes(), new ClientAttackRules.Attacker(POLEARM, 2110, 0), null);
        assertTrue(smash.isEmpty(), smash.toString());
    }

    @Test
    void hurricaneWithoutAKeydownIsDrawnWithoutItsPose() {
        byte[] shot = BotAttackPackets.rangedAttack(bot, 3121004, 20, 0, 0x13, 2060000,
                Map.of(1, new AttackTarget((short) 0, List.of(5, 6, 7))), 4, 22, 0).getBytes();
        assertTrue(drops(RULES.check(shot, new ClientAttackRules.Attacker(BOW, 312, 0), null)));
        assertTrue(RULES.check(shot, new ClientAttackRules.Attacker(BOW, 312, 3121004), null).isEmpty());
    }

    @Test
    void aSpellWithoutASkillAndAShortPacketAreCaught() {
        assertTrue(drops(RULES.check(BotAttackPackets.magicAttack(bot, 0, 0, 0, 0x11,
                Map.of(1, new AttackTarget((short) 0, List.of(5))), -1, 4, 28, 0).getBytes(),
                new ClientAttackRules.Attacker(1372000, 200, 0), null)));
        byte[] full = PacketCreator.closeRangeAttack(bot, 0, 0, 0, 0x12, Map.of(1, List.of(5, 6)), 4, 5, 0).getBytes();
        byte[] cut = java.util.Arrays.copyOf(full, full.length - 2);
        assertTrue(RULES.check(cut, new ClientAttackRules.Attacker(SWORD, 100, 0), null).get(0).startsWith("crash:"));
    }

    @Test
    void aKeydownPacketForASkillTheClientLacksWouldThrow() {
        List<String> p = RULES.check(PacketCreator.skillEffect(bot, 2121001, 1, (byte) 0, 0, (byte) 4).getBytes(),
                new ClientAttackRules.Attacker(1372000, 212, 0), null);
        assertEquals(1, p.size());
        assertTrue(p.get(0).startsWith("crash:"), p.toString()); // MapleRoot has no Big Bang
    }

    @Test
    void aWeaponWithoutAnAfterimageDrawsNoMeleeHits() {
        assertTrue(drops(close(0, 0, 5, 1302083, 100)), "1302083 has no afterImage / attack type 0");
    }

    @Test
    void aMeleePoseTheWeaponsAfterimageHasNoRangeForClosesTheViewer() {
        // 1.6.0, seen twice on TEST: a gunslinger bot's Double Shot (doublefire, 86) sent as melee with a gun
        List<String> p = close(5001003, 20, 86, GUN, 520);
        assertTrue(p.stream().anyMatch(s -> s.startsWith("crash:") && s.contains("afterimage gun")), p.toString());
        assertTrue(close(0, 0, 9, GUN, 520).isEmpty(), "a gun's plain swingT1 has a range");
        // a skill with an lt/rb range never reads the afterimage: Somersault Kick with a gun
        assertTrue(close(5001002, 20, BotSkillActions.codeOf("somersault"), GUN, 520).isEmpty());
        // a skill with its own afterimage reads that: Double Uppercut with a knuckle has one for doubleupper
        assertTrue(close(5101003, 20, BotSkillActions.codeOf("doubleupper"), KNUCKLE, 510).isEmpty());
        // a bow's melee swings: swingT1 has a range, Arrow Blow's and Double Shot's poses do not
        assertTrue(close(0, 0, 9, BOW, 300).isEmpty(), "a bow's swingT1");
    }
}
