package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Character;
import client.Job;
import client.inventory.WeaponType;
import constants.skills.Aran;
import constants.skills.DawnWarrior;
import net.packet.Packet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import soloMapling.ArtificialPlayer.BotDecoratorSystem.BotWeapons;
import soloMapling.server.BotAttackPackets;
import soloMapling.server.BotAttackPackets.AttackTarget;
import tools.PacketCreator;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
 * 1.5.6 hotfix: the two melee attacks on 1.5.5 (pirates bare-handed, so no gun case) that a viewer's client finds no
 * range for and closes on (BotMeleeRange, CActionMan::GetMeleeAttackRange 0x414440). Neither was seen on live.
 *
 * (a) A Dawn Warrior 2nd job's idle show-off swing picking Soul Blade: 1.5.5 sent it as MELEE in its own pose (blade,
 *     115) with a sword; a sword's afterimage has no range for blade.
 * (b) An Aran holding pole-arm 1442119 or 1442120 (drawn with the spear's afterimage): Full Swing / Overswing combo
 *     hits (the hidden 2111000x / 2112000x ids) have their own afterimage for a pole-arm only.
 */
class LiveMeleeCloseCasesTest {

    private static final ClientAttackRules RULES = new ClientAttackRules(ClientAttackData.INSTANCE);
    private static final int SWORD = 1302000, POLE_ARM = 1442000;
    private static final int[] SPEAR_DRAWN_POLE_ARMS = {1442119, 1442120};
    private static final int DW2 = 1110, ARAN3 = 2111, ARAN4 = 2112;

    private Character bot;

    @BeforeEach
    void clientData() {
        SkillPoseFixture.install();
        bot = mock(Character.class);
        when(bot.getId()).thenReturn(1_000_000_009);
    }

    private static Map<Integer, AttackTarget> oneMob() {
        return Map.of(5000, new AttackTarget((short) 300, List.of(1000)));
    }

    private static boolean closes(List<String> problems) {
        return problems.stream().anyMatch(s -> s.startsWith("crash:"));
    }

    // ---- (a) Dawn Warrior 2nd job, Soul Blade ----

    @Test
    void soulBladeAsMeleeWithASwordWouldCloseTheViewer() {
        int blade = BotSkillActions.codeOf("blade");
        assertEquals(115, blade);
        byte[] old = PacketCreator.closeRangeAttack(bot, DawnWarrior.SOUL_BLADE, 20, 0, 0x11,
                Map.of(), 4, blade, 0).getBytes(); // what 1.5.5's skillSwing sent
        assertTrue(closes(RULES.check(old, new ClientAttackRules.Attacker(SWORD, DW2, 0), null)),
                "the repro: this packet closes the viewer");
        assertFalse(BotMeleeRange.hasRange(DawnWarrior.SOUL_BLADE, 20, SWORD, blade));
    }

    @Test
    void theGuardNeverSendsSoulBladeAsMelee() {
        long before = BotAttackPackets.rewrites();
        Packet p = BotAttackPackets.closeRangeAttack(bot, SWORD, DawnWarrior.SOUL_BLADE, 20, 0, 0x11, oneMob(), 4,
                115, 0);
        assertEquals(before + 1, BotAttackPackets.rewrites());
        assertNotNull(p, "a sword has a plain swing to send instead");
        ClientAttackRules.Decoded[] d = new ClientAttackRules.Decoded[1];
        List<String> problems = RULES.check(p.getBytes(), new ClientAttackRules.Attacker(SWORD, DW2, 0), d);
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(0, d[0].skillId());
        assertNotEquals(115, d[0].action());
    }

    @Test
    void aDawnWarriorsShowOffSoulBladeIsAShotTheViewerDraws() {
        BotAttackProfile soulBlade = BotAttackConfig.resolve(Job.DAWNWARRIOR2, WeaponType.SWORD1H).aoe();
        assertEquals(DawnWarrior.SOUL_BLADE, soulBlade.skillId, "DW2's show-off picks Soul Blade from this slot");
        long before = BotAttackPackets.rewrites();
        Packet p = BotAttack.showOffPacket(bot, soulBlade, 20, SWORD, WeaponType.SWORD1H, false);
        assertNotNull(p);
        assertEquals(before, BotAttackPackets.rewrites(), "the show-off path itself picks a pose with a range");
        ClientAttackRules.Decoded[] d = new ClientAttackRules.Decoded[1];
        List<String> problems = RULES.check(p.getBytes(), new ClientAttackRules.Attacker(SWORD, DW2, 0), d);
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(ClientAttackRules.RANGED, d[0].type(), "Soul Blade goes out on the shoot route, as a player's");
        assertEquals(DawnWarrior.SOUL_BLADE, d[0].skillId());
    }

    // ---- (b) Aran with a spear-drawn pole-arm ----

    @Test
    void fullSwingAndOverswingHitsHaveNoRangeWithTheSpearDrawnPoleArms() {
        for (int skill : new int[]{Aran.HIDDEN_FULL_DOUBLE, Aran.HIDDEN_FULL_TRIPLE, Aran.HIDDEN_OVER_DOUBLE,
                Aran.HIDDEN_OVER_TRIPLE}) {
            BotSwingPlan normal = BotSwingPlan.of(meleeOf(skill), 20, POLE_ARM, false, 3);
            assertTrue(BotMeleeRange.hasRange(skill, 20, POLE_ARM, normal.action()), skill + " with a pole-arm");
            for (int weapon : SPEAR_DRAWN_POLE_ARMS) {
                BotSwingPlan plan = BotSwingPlan.of(meleeOf(skill), 20, weapon, false, 3);
                int job = skill / 10000;
                byte[] raw = PacketCreator.closeRangeAttack(bot, skill, 20, 0, 0x13, Map.of(5000, List.of(1, 2, 3)),
                        plan.speed(), plan.action(), 0).getBytes();
                assertTrue(closes(RULES.check(raw, new ClientAttackRules.Attacker(weapon, job, 0), null)),
                        "the repro: skill " + skill + " with " + weapon + " closes the viewer");
                assertFalse(BotMeleeRange.hasRange(skill, 20, weapon, plan.action()), skill + " with " + weapon);

                long before = BotAttackPackets.rewrites();
                Packet guarded = BotAttackEffects.attackPacket(bot, plan, oneMob(), 0x11, 0);
                assertEquals(before + 1, BotAttackPackets.rewrites(), "the guard rewrites it");
                if (guarded != null) {
                    List<String> problems = RULES.check(guarded.getBytes(),
                            new ClientAttackRules.Attacker(weapon, job, 0), null);
                    assertFalse(closes(problems), problems.toString());
                }
            }
        }
    }

    private static BotAttackProfile meleeOf(int hiddenSkill) {
        for (Job job : new Job[]{Job.ARAN3, Job.ARAN4}) {
            BotAttackConfig.JobAttacks atk = BotAttackConfig.resolve(job, WeaponType.POLE_ARM_SWING);
            for (BotAttackProfile p : List.of(atk.single(), atk.aoe())) {
                if (p != null && p.skillId == hiddenSkill) {
                    return p;
                }
            }
            for (BotAttackConfig.Extra e : atk.extras()) {
                if (e.profile().skillId == hiddenSkill) {
                    return e.profile();
                }
            }
        }
        throw new AssertionError("no Aran attack sends " + hiddenSkill);
    }

    @Test
    void aransAreNeverHandedTheSpearDrawnPoleArms() {
        for (Job job : new Job[]{Job.ARAN1, Job.ARAN2, Job.ARAN3, Job.ARAN4}) {
            assertTrue(BotWeapons.fits(job, POLE_ARM), job + " fights with a pole-arm");
            for (int weapon : SPEAR_DRAWN_POLE_ARMS) {
                assertTrue(BotWeapons.UNDRAWABLE.contains(weapon));
                assertFalse(BotWeapons.fits(job, weapon), job + " must not hold " + weapon);
            }
        }
    }
}
