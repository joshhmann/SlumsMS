package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Job;
import client.inventory.WeaponType;
import constants.inventory.EquipType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotDecoratorSystem.BotWeapons;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Ticket 32: bots swing at a player's cadence and send a player's attack speed (BotAttackTiming). The pose lengths come
 * from the client's Character.wz (tools/bot-attack-rules/action_delays.py -> soloMapling/bot-action-delays.tsv); the
 * numbers checked here against a real player are from the test server's attack-capture.tsv (a GM beginner spamming
 * basic attacks with a 1302000 sword: speed 3, 633-677 ms apart).
 */
class BotAttackTimingTest {

    private static final ClientAttackData DATA = ClientAttackData.INSTANCE;

    @BeforeEach
    void useClientData() {
        SkillPoseFixture.install();
        BotAttackTiming.setEnabledForTest(true);
    }

    @AfterEach
    void flagBack() {
        BotAttackTiming.setEnabledForTest(null);
    }

    @Test
    void theDelayTableNamesTheExesActions() throws IOException {
        int rows = 0;
        try (BufferedReader r = resource()) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("D\t")) {
                    String[] f = line.split("\t");
                    int code = Integer.parseInt(f[1]);
                    assertEquals(DATA.actions.get(code), f[2], "action " + code);
                    rows++;
                }
            }
        }
        assertTrue(rows > 150, rows + " actions");
        // every plain pose of every weapon has a length
        for (String t : new String[]{"T1", "T2", "T3"}) {
            for (int type = 1; type <= 9; type++) {
                for (boolean type3 : new boolean[]{false, true}) {
                    for (int code : DATA.table(t, type, type3)) {
                        assertTrue(BotAttackTiming.actionMs(code) > 0, t + " " + type + " pose " + code);
                    }
                }
            }
        }
    }

    @Test
    void aBeginnersSwordSwingTakesWhatAPlayersClientTakes() {
        // capture: 1302000 sent at speed 3; swings 633-677 ms apart (min 633, most 655-662)
        assertEquals(3, BotClientPoses.actionSpeed(1302000));
        for (int pose : new int[]{5, 6, 7, 16, 17}) { // swingO1-3, stabO1-2: 800 ms of frames each
            assertEquals(800, BotAttackTiming.actionMs(pose));
            assertEquals(650, BotAttackTiming.poseMs(pose, 3));
        }
        BotSwingPlan plan = BotSwingPlan.of(BotAttackProfile.basicSwing(), 0, 1302000, false, 1);
        assertEquals(650, plan.busyMs(720));
    }

    @Test
    void theSpeedFollowsMapleRootsHookNotTheItem() {
        assertEquals(3, BotAttackTiming.speedDegree(1302000, 0)); // 1H sword (item attackSpeed 4)
        assertEquals(2, BotAttackTiming.speedDegree(1332000, 0)); // dagger
        assertEquals(4, BotAttackTiming.speedDegree(1402000, 0)); // 2H sword
        assertEquals(4, BotAttackTiming.speedDegree(1452000, 0)); // bow
        assertEquals(3, BotAttackTiming.speedDegree(1472000, 0)); // claw
        assertEquals(4, BotAttackTiming.speedDegree(1482000, 0)); // knuckle
        assertEquals(3, BotAttackTiming.speedDegree(1492000, 0)); // gun
        assertEquals(2, BotAttackTiming.speedDegree(1482000, -2)); // knuckle under Speed Infusion
        assertEquals(0, BotAttackTiming.speedDegree(1332000, -2)); // clamped at 0, no upper clamp in the hook
        for (int wt = 30; wt <= 49; wt++) {
            int item = 1000000 + wt * 10000;
            assertEquals(ClientAttackRules.playerSpeed(item), BotAttackTiming.speedDegree(item, 0), "class " + wt);
        }
    }

    @Test
    void theFlagOffKeepsTheOldSpeedAndCooldowns() {
        BotAttackTiming.setEnabledForTest(false);
        BotSwingPlan plan = BotSwingPlan.of(BotAttackProfile.basicSwing(), 0, 1302000, false, 1);
        assertEquals(DATA.weapons.get(1302000).attackSpeed(), plan.speed());
        assertEquals(720, plan.busyMs(720));
    }

    @Test
    void anUnknownPoseFallsBackToTheProfileCooldown() {
        BotSwingPlan plan = BotSwingPlan.of(BotAttackProfile.basicSwing(), 0, 1302000, false, 1).withAction(40);
        assertEquals(-1, BotAttackTiming.poseMs(40, 3)); // "tired": no frames in the client
        assertEquals(720, plan.busyMs(720));
    }

    @Test
    void theSkillTimingTableMatchesTheRuntime() throws IOException {
        int rows = 0;
        try (InputStream in = getClass().getResourceAsStream("/soloMapling/bot-skill-timing.tsv")) {
            assertNotNull(in, "bot-skill-timing.tsv");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("K\t")) {
                    continue;
                }
                String[] f = line.split("\t");
                int item = 1000000 + Integer.parseInt(f[3]) * 10000;
                int degree = Integer.parseInt(f[4]);
                assertEquals(BotAttackTiming.speedDegree(item, 0), degree, line);
                int min = Integer.MAX_VALUE, max = 0;
                for (String p : f[5].split(",")) {
                    int ms = BotAttackTiming.poseMs(Integer.parseInt(p), degree);
                    min = Math.min(min, ms);
                    max = Math.max(max, ms);
                }
                assertEquals(Integer.parseInt(f[6]), min, line);
                assertEquals(Integer.parseInt(f[7]), max, line);
                rows++;
            }
        }
        assertTrue(rows > 1000, rows + " rows");
    }

    /*
     * Every bot class's attacks, old cooldown against the pose's length: printed for the ticket (ticket 32 "Attack
     * timing"), and no attack may be shorter than 0.2 s or longer than 4 s (MapleRoot reuses alert2, 270 ms, for some customs).
     */
    @Test
    void everyBotAttackHasAPlayersCadence() {
        List<String> report = new ArrayList<>();
        TreeSet<String> bad = new TreeSet<>();
        for (Job job : Job.values()) {
            List<EquipType> kinds = BotWeapons.weaponsFor(job);
            if (kinds.isEmpty()) {
                kinds = List.of(EquipType.SWORD);
            }
            for (EquipType kind : kinds) {
                int weaponId = firstWeapon(kind);
                if (weaponId == 0) {
                    continue;
                }
                WeaponType wt = BotAttackClientValidityTest.weaponType(weaponId);
                BotAttackConfig.JobAttacks a = BotAttackConfig.resolve(job, wt);
                List<BotAttackProfile> ps = new ArrayList<>(Arrays.asList(a.single(), a.aoe(), a.ultimate()));
                a.extras().forEach(e -> ps.add(e.profile()));
                for (BotAttackProfile p : ps) {
                    if (p == null) {
                        continue;
                    }
                    BotSwingPlan plan = BotSwingPlan.of(p, Math.max(1, p.skillLevel), weaponId, false, p.numDamage);
                    List<Integer> poses = BotAttackData.actionCandidates(plan.skillId(), plan.skillLevel(), weaponId,
                            plan.route());
                    int lo = Integer.MAX_VALUE, hi = 0;
                    for (int pose : poses) {
                        int ms = BotAttackTiming.poseMs(pose, plan.speed());
                        lo = Math.min(lo, ms);
                        hi = Math.max(hi, ms);
                    }
                    if (lo < 200 || hi > 4000) {
                        bad.add(job + " " + weaponId + " " + plan.skillId() + " poses " + poses + ": " + lo + "-" + hi);
                    }
                    report.add(job + "\t" + weaponId + "\t" + plan.skillId() + "\t" + poses + "\tspeed "
                            + plan.speed() + "\told " + p.cooldownMs + "\tnew " + (lo == hi ? lo : lo + "-" + hi));
                }
            }
        }
        if (Boolean.getBoolean("bot.attack.timing.print")) {
            report.forEach(System.out::println);
        }
        assertTrue(report.size() > 100, report.size() + " attacks");
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }

    private static int firstWeapon(EquipType kind) {
        return DATA.weapons.keySet().stream().sorted()
                .filter(id -> BotWeapons.kindOf(id) == kind && !BotWeapons.UNDRAWABLE.contains(id))
                .findFirst().orElse(0);
    }

    private static BufferedReader resource() {
        InputStream in = BotAttackTiming.class.getResourceAsStream(BotAttackTiming.RESOURCE);
        assertNotNull(in, BotAttackTiming.RESOURCE);
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
}
