package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Character;
import client.Job;
import client.inventory.WeaponType;
import constants.inventory.EquipType;
import constants.skills.Cleric;
import net.packet.Packet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotDecoratorSystem.BotWeapons;
import soloMapling.server.BotAttackPackets.AttackTarget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
 * Ticket 32: every attack every bot class can make is one a v83 viewer's client draws, and is the packet a player's
 * own client would have sent. For each job, each kind of weapon it is handed (one weapon per client attack type /
 * afterimage / speed group of that kind), each attack it resolves (single, AoE, ultimate, rotation extras, the
 * cleric's heal, the crusader's orb charger), each level the skill has and both facings, this builds the exact bytes
 * the bot broadcasts (BotSwingPlan -> BotAttackEffects) for every pose the plan may pick, and runs them through
 * ClientAttackRules - the client's own checks, fed by the client's own Skill.wz / Character.wz / exe tables.
 */
class BotAttackClientValidityTest {

    private static final ClientAttackData DATA = ClientAttackData.INSTANCE;
    private static final ClientAttackRules RULES = new ClientAttackRules(DATA);

    @BeforeEach
    void useClientData() {
        SkillPoseFixture.install();
    }

    @Test
    void everyBotAttackIsDrawnAndPlayerLike() {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(424242);
        Set<String> failures = new TreeSet<>();
        int packets = 0;
        for (Job job : Job.values()) {
            List<EquipType> kinds = BotWeapons.weaponsFor(job);
            if (kinds.isEmpty()) {
                kinds = List.of(EquipType.SWORD); // beginners and GMs: the decorator's sword
            }
            for (EquipType kind : kinds) {
                for (int weaponId : representativeWeapons(kind)) {
                    WeaponType weaponType = weaponType(weaponId);
                    for (BotAttackProfile p : profiles(job, weaponType)) {
                        packets += checkProfile(bot, job, weaponId, weaponType, p, failures);
                    }
                }
            }
        }
        System.out.println("BotAttackClientValidityTest: checked " + packets + " packets");
        assertTrue(packets > 10_000, "checked " + packets + " packets");
        assertTrue(failures.isEmpty(), failures.size() + " problems:\n" + String.join("\n", failures));
    }

    @Test
    void everyWeaponABotIsHandedIsOneTheClientDraws() {
        Set<Integer> undrawable = new TreeSet<>();
        for (ClientAttackData.Weapon w : DATA.weapons.values()) {
            EquipType kind = BotWeapons.kindOf(w.id());
            if (kind == null || !botKinds().contains(kind)) {
                continue;
            }
            if (w.afterImage().isEmpty() || w.attackType() != BotClientPoses.defaultAttackType(w.id())) {
                undrawable.add(w.id());
            }
        }
        assertEquals(undrawable, new TreeSet<>(BotWeapons.UNDRAWABLE),
                "weapons whose client data draws no attack or the wrong poses must be kept from bots");
        for (int id : undrawable) {
            assertTrue(!BotWeapons.fits(Job.HERO, id) && !BotWeapons.fits(Job.BEGINNER, id), id + " still fits");
        }
    }

    @Test
    void theExeTablesInBotClientPosesMatchTheExe() {
        for (BotAttackProfile.Route route : BotAttackProfile.Route.values()) {
            String table = switch (route) {
                case CLOSE -> "T1";
                case RANGED -> "T2";
                case MAGIC -> "T3";
            };
            for (int type = 1; type <= 9; type++) {
                for (boolean type3 : new boolean[]{false, true}) {
                    List<Integer> exe = new ArrayList<>();
                    for (int c : DATA.table(table, type, type3)) {
                        exe.add(c);
                    }
                    assertEquals(exe, BotClientPoses.plainPoses(route, type, type3), table + " " + type + " " + type3);
                }
            }
        }
        assertEquals(DATA.actions, BotSkillActions.CLIENT_ACTIONS, "the exe's action table");
        for (ClientAttackData.ExeSkill k : DATA.exe.values()) {
            int id = k.id();
            assertEquals(k.flags().contains("keydown"), BotClientPoses.isKeydown(id), "keydown " + id);
            assertEquals(k.flags().contains("keepPose"), BotClientPoses.keepsKeydownPose(id), "keepPose " + id);
            assertEquals(k.flags().contains("noActionCheck"), BotClientPoses.skipsActionCheck(id), "noActionCheck " + id);
            assertEquals(k.flags().contains("shootNoBullet"), BotClientPoses.shootsWithoutBullet(id), "noBullet " + id);
            assertEquals(k.flags().contains("shootWithoutWeapon"), BotClientPoses.shootsWithoutShootingWeapon(id),
                    "shootWithoutWeapon " + id);
            for (int wt = 30; wt <= 49; wt++) {
                assertEquals(k.weaponTypes().contains(wt), BotSkillActions.weaponFits(id, wt * 10000 + 1000000),
                        "skill " + id + " with weapon type " + wt);
            }
        }
    }

    private static Set<EquipType> botKinds() {
        Set<EquipType> kinds = new LinkedHashSet<>();
        for (Job job : Job.values()) {
            kinds.addAll(BotWeapons.weaponsFor(job));
        }
        return kinds;
    }

    /* One weapon of the kind per (attack type, afterimage or not, attack speed): the fields a viewer reads. */
    private static List<Integer> representativeWeapons(EquipType kind) {
        Map<String, Integer> reps = new LinkedHashMap<>();
        List<Integer> ids = new ArrayList<>();
        for (ClientAttackData.Weapon w : DATA.weapons.values()) {
            if (BotWeapons.kindOf(w.id()) == kind && !BotWeapons.UNDRAWABLE.contains(w.id())) {
                ids.add(w.id());
            }
        }
        Collections.sort(ids);
        for (int id : ids) {
            ClientAttackData.Weapon w = DATA.weapons.get(id);
            reps.putIfAbsent(w.attackType() + "/" + w.afterImage().isEmpty() + "/" + w.attackSpeed(), id);
        }
        return new ArrayList<>(reps.values());
    }

    private static List<BotAttackProfile> profiles(Job job, WeaponType weapon) {
        BotAttackConfig.JobAttacks a = BotAttackConfig.resolve(job, weapon);
        List<BotAttackProfile> out = new ArrayList<>();
        for (BotAttackProfile p : new BotAttackProfile[]{a.single(), a.aoe(), a.ultimate()}) {
            if (p != null) {
                out.add(p);
            }
        }
        for (BotAttackConfig.Extra e : a.extras()) {
            out.add(e.profile());
        }
        if (job.isA(Job.CLERIC)) {
            out.add(BotAttackProfile.magicAoe(Cleric.HEAL, 1));
        }
        if (BotComboOrbs.usesCombo(job)) {
            out.add(BotAttackDriver.comboCharger(a.single(), true, weapon));
            out.add(BotAttackDriver.comboCharger(a.single(), false, weapon));
        }
        out.add(BotAttackProfile.basicSwing()); // what a bot out of MP / ammo / reach falls back to
        out.add(BotAttackProfile.ranged(0, 1)); // ... and a shooter out of MP (SwingSupply)
        return out;
    }

    private static int checkProfile(Character bot, Job job, int weaponId, WeaponType weaponType, BotAttackProfile p,
                                    Set<String> failures) {
        int packets = 0;
        ClientAttackData.Skill skill = DATA.skills.get(p.skillId);
        if (p.skillId != 0 && skill == null) {
            failures.add(job + " " + p.skillId + ": not in the client's Skill.wz");
            return 0;
        }
        int maxLevel = skill != null ? Math.max(1, skill.maxLevel()) : 0;
        for (int level = skill != null ? 1 : 0; level <= maxLevel; level++) {
            for (boolean left : new boolean[]{false, true}) {
                BotSwingPlan plan = BotSwingPlan.of(p, level, weaponId, left, p.numDamage);
                String who = job + " " + weaponId + " " + p.skillId + " lv" + level + (left ? " L" : " R")
                        + (plan.skillId() != p.skillId ? " (sent as a plain attack)" : "") + ": ";
                if (plan.skillId() != p.skillId && p.skillId != 0) {
                    failures.add(job + " " + weaponId + " " + p.skillId + ": the bot cannot send it with this weapon");
                    break;
                }
                List<Integer> poses = BotAttackData.actionCandidates(plan.skillId(), plan.skillLevel(), weaponId,
                        plan.route());
                if (poses.isEmpty()) {
                    failures.add(who + "no pose at all");
                    continue;
                }
                if (plan.prepare()) {
                    packets++;
                    for (String f : RULES.check(BotAttackEffects.preparePacket(bot, plan).getBytes(),
                            new ClientAttackRules.Attacker(weaponId, job.getId(), 0), null)) {
                        failures.add(who + "keydown packet: " + f);
                    }
                }
                for (int pose : poses) {
                    BotSwingPlan sent = plan.withAction(pose);
                    if (BotAttackTiming.poseMs(pose, sent.speed()) <= 0) {
                        failures.add(who + "pose " + pose + " has no length in bot-action-delays.tsv");
                    }
                    Packet packet = BotAttackEffects.attackPacket(bot, sent, targets(sent),
                            (sent.maxMobs() << 4) | sent.lines(),
                            sent.route() == BotAttackProfile.Route.RANGED && BotClientPoses.isShootingWeapon(weaponId)
                                    ? BotAttackData.projectileFor(weaponType) : 0);
                    packets++;
                    ClientAttackRules.Attacker attacker = new ClientAttackRules.Attacker(weaponId, job.getId(),
                            sent.prepare() ? sent.skillId() : 0);
                    ClientAttackRules.Decoded[] decoded = new ClientAttackRules.Decoded[1];
                    for (String f : RULES.check(packet.getBytes(), attacker, decoded)) {
                        failures.add(who + f);
                    }
                    if (decoded[0] != null) {
                        for (String f : RULES.playerLike(decoded[0], attacker)) {
                            failures.add(who + f);
                        }
                    }
                }
                if (plan.shots() > 1) {
                    packets++;
                    for (String f : RULES.check(BotAttackEffects.releasePacket(bot, plan).getBytes(),
                            new ClientAttackRules.Attacker(weaponId, job.getId(), plan.skillId()), null)) {
                        failures.add(who + "release packet: " + f);
                    }
                }
            }
        }
        return packets;
    }

    private static Map<Integer, AttackTarget> targets(BotSwingPlan plan) {
        Map<Integer, AttackTarget> targets = new LinkedHashMap<>();
        for (int m = 0; m < plan.maxMobs(); m++) {
            List<Integer> lines = new ArrayList<>();
            for (int i = 0; i < plan.lines(); i++) {
                lines.add(1000 + i);
            }
            targets.put(5000 + m, new AttackTarget((short) 300, lines));
        }
        return targets;
    }

    static WeaponType weaponType(int itemId) {
        return switch (itemId / 10000 % 100) {
            case 30 -> WeaponType.SWORD1H;
            case 31, 32 -> WeaponType.GENERAL1H_SWING;
            case 33 -> WeaponType.DAGGER_OTHER;
            case 37 -> WeaponType.WAND;
            case 38 -> WeaponType.STAFF;
            case 40 -> WeaponType.SWORD2H;
            case 41, 42 -> WeaponType.GENERAL2H_SWING;
            case 43 -> WeaponType.SPEAR_STAB;
            case 44 -> WeaponType.POLE_ARM_SWING;
            case 45 -> WeaponType.BOW;
            case 46 -> WeaponType.CROSSBOW;
            case 47 -> WeaponType.CLAW;
            case 48 -> WeaponType.KNUCKLE;
            case 49 -> WeaponType.GUN;
            default -> WeaponType.NOT_A_WEAPON;
        };
    }
}
