package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Character;
import constants.skills.Pirate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.server.BotAttackPackets;
import soloMapling.server.BotAttackPackets.AttackTarget;
import tools.PacketCreator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
 * 1.6.0: a bot's melee attack in a pose the viewer's client finds no range for closes that client
 * (CActionMan::GetMeleeAttackRange 0x414440). Seen twice on TEST: a gunslinger bot's Double Shot (doublefire, 86) sent
 * as melee with a gun. BotMeleeRange is the server's copy of the client's range data; BotAttackPackets never sends a
 * melee packet it says has no range.
 */
class BotMeleeRangeTest {

    private static final ClientAttackData DATA = ClientAttackData.INSTANCE;
    private static final ClientAttackRules RULES = new ClientAttackRules(DATA);
    private static final int GUN = 1492000, KNUCKLE = 1482000, CLAW = 1472000, SWORD = 1302000;

    private Character bot;

    @BeforeEach
    void clientData() {
        SkillPoseFixture.install();
        bot = mock(Character.class);
        when(bot.getId()).thenReturn(1_000_000_007);
    }

    private static Map<Integer, AttackTarget> oneMob() {
        return Map.of(5000, new AttackTarget((short) 300, List.of(1000, 1001)));
    }

    @Test
    void theRuntimeTableIsTheClientsFixture() throws IOException {
        assertEquals(rows("/soloMapling/client-attack-data.tsv"), rows(BotMeleeRange.RESOURCE),
                "src/main/resources/soloMapling/bot-melee-ranges.tsv must be the fixture's R, J and I rows");
    }

    private static List<String> rows(String resource) throws IOException {
        try (InputStream in = BotMeleeRangeTest.class.getResourceAsStream(resource)) {
            List<String> out = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.startsWith("R\t") || line.startsWith("J\t") || line.startsWith("I\t")
                        || line.startsWith("# sha256 Character/Afterimage") || line.startsWith("# sha256 Skill")) {
                    out.add(line);
                }
            }
            return out;
        }
    }

    @Test
    void aGunslingersDoubleShotSentAsMeleeWouldCloseTheViewer() {
        byte[] raw = PacketCreator.closeRangeAttack(bot, Pirate.DOUBLE_SHOT, 20, 0, 0x12,
                Map.of(5000, List.of(1000, 1001)), 3, 86, 0).getBytes();
        List<String> p = RULES.check(raw, new ClientAttackRules.Attacker(GUN, 520, 0), null);
        assertTrue(p.stream().anyMatch(s -> s.startsWith("crash:")), p.toString());
        assertFalse(BotMeleeRange.hasRange(Pirate.DOUBLE_SHOT, 20, GUN, 86));
    }

    @Test
    void thePacketGuardNeverSendsIt() {
        long before = BotAttackPackets.rewrites();
        byte[] sent = BotAttackPackets.closeRangeAttack(bot, GUN, Pirate.DOUBLE_SHOT, 20, 0, 0x12, oneMob(), 3, 86, 0)
                .getBytes();
        assertEquals(before + 1, BotAttackPackets.rewrites());
        ClientAttackRules.Decoded[] d = new ClientAttackRules.Decoded[1];
        List<String> p = RULES.check(sent, new ClientAttackRules.Attacker(GUN, 520, 0), d);
        assertTrue(p.isEmpty(), "the gun's plain swing instead: " + p);
        assertEquals(0, d[0].skillId());
        assertNotEquals(86, d[0].action());
        // an attack the client has a range for goes out untouched
        byte[] fine = BotAttackPackets.closeRangeAttack(bot, GUN, 0, 0, 0, 0x12, oneMob(), 3, 9, 0).getBytes();
        assertEquals(before + 1, BotAttackPackets.rewrites());
        assertTrue(java.util.Arrays.equals(fine, PacketCreator.closeRangeAttack(bot, 0, 0, 0, 0x12,
                Map.of(5000, List.of(1000, 1001)), 3, 9, 0).getBytes()));
    }

    @Test
    void theRangeRulesOfTheClient() {
        assertTrue(BotMeleeRange.hasRange(0, 0, GUN, 9), "a gun's swingT1");
        assertFalse(BotMeleeRange.hasRange(0, 0, GUN, 93), "a gun's shot pose has no melee range");
        assertTrue(BotMeleeRange.hasRange(5101003, 20, KNUCKLE, BotSkillActions.codeOf("doubleupper")),
                "Double Uppercut's own knuckle afterimage");
        assertTrue(BotMeleeRange.hasRange(5001002, 20, GUN, BotSkillActions.codeOf("somersault")),
                "Somersault Kick has an lt/rb range");
        assertTrue(BotMeleeRange.hasRange(0, 0, SWORD, 55), "savage has a hard-coded range");
        assertFalse(BotMeleeRange.hasRange(0, 0, CLAW, 24), "the claw's repeated swingO1 code is never filled");
        assertTrue(BotMeleeRange.hasRange(3001005, 20, 0, 86), "no weapon: dropped before any range is read");
        assertTrue(BotMeleeRange.safePlainPose(GUN) == 9 || BotMeleeRange.safePlainPose(GUN) == 10);
    }

    /*
     * Every weapon kind a bot holds (one weapon per afterimage) x every pose in the client's table x no skill and every
     * skill a bot attacks with, at level 1 and its top level: BotMeleeRange agrees with the model of the client.
     */
    @Test
    void theGuardAgreesWithTheClientForEveryWeaponPoseAndSkill() {
        Map<String, Integer> weaponByAfterImage = new TreeMap<>();
        for (ClientAttackData.Weapon w : DATA.weapons.values()) {
            int wt = w.id() / 10000 % 100;
            if (w.id() / 1000000 == 1 && wt >= 30 && wt <= 49 && !w.afterImage().isEmpty()) {
                weaponByAfterImage.merge(w.afterImage(), w.id(), Math::min);
            }
        }
        Set<Integer> skills = new TreeSet<>();
        skills.add(0);
        for (BotAttackProfile p : BotAttackConfig.allProfiles()) {
            skills.add(p.skillId);
        }
        Set<String> disagree = new TreeSet<>();
        int checked = 0;
        for (int weaponId : weaponByAfterImage.values()) {
            ClientAttackData.Weapon weapon = DATA.weapons.get(weaponId);
            for (int skillId : skills) {
                ClientAttackData.Skill skill = skillId == 0 ? null : DATA.skills.get(skillId);
                if (skillId != 0 && skill == null) {
                    continue;
                }
                int top = skill == null ? 0 : Math.max(1, skill.maxLevel());
                for (int level : skill == null ? new int[]{0} : new int[]{1, top}) {
                    for (int action = 0; action < DATA.actions.size(); action++) {
                        ClientAttackRules.Decoded d = new ClientAttackRules.Decoded(ClientAttackRules.CLOSE, 1, 1,
                                skillId, level, 0, action, false, 3, 0);
                        boolean model = RULES.meleeRange(d, skill, level, weapon) == null;
                        checked++;
                        if (model != BotMeleeRange.hasRange(skillId, level, weaponId, action)) {
                            disagree.add(weapon.afterImage() + " skill " + skillId + " lv" + level + " pose " + action
                                    + ": client " + model);
                        }
                    }
                }
            }
        }
        assertTrue(checked > 10_000, "checked " + checked);
        assertTrue(disagree.isEmpty(), disagree.size() + " disagree:\n" + String.join("\n", disagree));
    }
}
