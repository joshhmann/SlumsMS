package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/*
 * How long a player's attack keeps them busy in MapleRoot's v83 client, so a bot swings at a player's cadence and sends
 * a player's attack speed (ticket 32; docs/real-bots/client-remote-attack-rules.md, "Attack timing").
 *
 * - The pose's length: the frame delays of the client's Character.wz 00002000.img summed per body action (a negative
 *   delay counts by its size). tools/bot-attack-rules/action_delays.py writes them to the classpath resource
 *   soloMapling/bot-action-delays.tsv, so a jar-only install has them too.
 * - The attack speed: MapleRoot's GetAttackSpeedDegree hook (client repo mapleroot/ijl15/SkillEdits/ActiveSkill.h,
 *   over 0x765066) sets it from the weapon class alone - 3 for 1H swords/axes/blunts, wands, staves, pole-arms, claws
 *   and guns, 2 for daggers, 4 for everything else - plus Speed Infusion's x; boosters are ignored. A real player's
 *   1H sword (item attackSpeed 4) is sent at 3 (attack-capture.tsv on the test server).
 * - Speed stretches the pose: length * (speed + 10) / 16 (0x9525ed). A beginner's 800 ms swing at speed 3 is 650 ms,
 *   which is the gap a real player's client spams basic attacks at (633-677 ms in the capture).
 *
 * On by default; -Dslums.botattacktiming=false sends the weapon's item attackSpeed and the profiles' fixed cooldowns
 * again (the behaviour before this class).
 */
public final class BotAttackTiming {

    private static final Logger log = LoggerFactory.getLogger(BotAttackTiming.class);

    public static final String PROPERTY = "slums.botattacktiming";
    static final String RESOURCE = "/soloMapling/bot-action-delays.tsv";
    /* Body-action codes run 0..161 in the client's action table (0x4a38e7). */
    static final int ACTION_COUNT = 162;

    private static volatile Boolean enabledOverride; // tests
    private static volatile int[] delays;            // code -> total ms at speed 6, -1 = no frames

    private BotAttackTiming() {}

    public static boolean enabled() {
        Boolean o = enabledOverride;
        return o != null ? o : Boolean.parseBoolean(System.getProperty(PROPERTY, "true"));
    }

    /* Tests: force the flag on or off (null = the system property again). */
    static void setEnabledForTest(Boolean on) {
        enabledOverride = on;
    }

    /* The attack speed MapleRoot's client sends for a weapon class (item / 10000 % 100), Speed Infusion's x added. */
    public static int speedDegree(int weaponItemId, int partyBooster) {
        int degree = switch (BotSkillActions.weaponClass(weaponItemId)) {
            case 30, 31, 32, 37, 38, 44, 47, 49 -> 3;
            case 33 -> 2;
            default -> 4;
        };
        return Math.max(0, degree + partyBooster);
    }

    /* The pose's frame delays summed, before the attack speed stretches it; -1 when the client has no such pose. */
    public static int actionMs(int actionCode) {
        int[] d = table();
        return actionCode >= 0 && actionCode < d.length ? d[actionCode] : -1;
    }

    /* How long the pose plays at this attack speed (0x9525ed: ms * (speed + 10) / 16); -1 for an unknown pose. */
    public static int poseMs(int actionCode, int speed) {
        int ms = actionMs(actionCode);
        return ms <= 0 ? -1 : ms * (Math.max(0, speed) + 10) / 16;
    }

    /*
     * How long a swing of this plan keeps the bot busy before it may swing again: the pose's length at the plan's speed
     * when the flag is on and the pose is known, else the profile's fixed cooldown.
     */
    public static long swingMs(BotSwingPlan plan, int profileCooldownMs) {
        if (!enabled() || plan == null) {
            return profileCooldownMs;
        }
        int ms = poseMs(plan.action(), plan.speed());
        return ms > 0 ? ms : profileCooldownMs;
    }

    static int[] table() {
        int[] d = delays;
        if (d == null) {
            synchronized (BotAttackTiming.class) {
                d = delays;
                if (d == null) {
                    d = load();
                    delays = d;
                }
            }
        }
        return d;
    }

    private static int[] load() {
        int[] d = new int[ACTION_COUNT];
        Arrays.fill(d, -1);
        try (InputStream in = BotAttackTiming.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                log.warn("Bot attack timing: {} is missing, bots keep their fixed attack cooldowns", RESOURCE);
                return d;
            }
            parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)), d);
        } catch (IOException | RuntimeException e) {
            log.warn("Bot attack timing: could not read {}, bots keep their fixed attack cooldowns", RESOURCE, e);
            Arrays.fill(d, -1);
        }
        return d;
    }

    /* "D code name totalMs preMs frames" rows into code -> totalMs. */
    static void parse(BufferedReader reader, int[] into) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t");
            if (f.length >= 4 && f[0].equals("D")) {
                int code = Integer.parseInt(f[1]);
                if (code >= 0 && code < into.length) {
                    into[code] = Integer.parseInt(f[3]);
                }
            }
        }
    }
}
