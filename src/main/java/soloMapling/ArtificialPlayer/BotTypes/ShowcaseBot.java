package soloMapling.ArtificialPlayer.BotTypes;

import client.Character;
import client.inventory.WeaponType;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackConfig;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackTiming;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotClientPoses;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotSwingClock;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotSwingPlan;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotSM;

import java.util.ArrayList;
import java.util.List;

import static soloMapling.server.ExecutorServiceManager.runAsync;

/*
 * !botshowcase (ticket 32, test server): stands where it was spawned and cycles every attack its job has - single,
 * AoE, ultimate and rotation extras, one after another - at the nearest mobs, so a player standing next to it (with
 * the client's attack diagnostic on) sees each one drawn or logged as dropped. Leaves the world when its time is up.
 * With bot attack timing on (BotAttackTiming), each attack goes out REPEATS times back to back at a player's cadence
 * (the next swing on the client's first update step after the pose has played out), so its rhythm can be checked too,
 * then a gap before the next one. A held keydown skill (Hurricane, Rapid Fire) goes out once, held for its full stream.
 * The bot's own swing clock (BotSwingClock) times each repeat to the millisecond: the macro tick (2-6 s) only starts
 * the cycle and notices when the time is up.
 */
public class ShowcaseBot extends BotSM {

    private static final long GAP_MS = 1_500;
    static final int REPEATS = 3;

    private final long endsAt;
    private final List<BotAttackProfile> rotation = new ArrayList<>();
    private int next;
    private int repeat;
    private long nextSwingAt;
    private volatile boolean leaving;
    private final BotSwingClock clock = new BotSwingClock();

    public ShowcaseBot(Character character, long durationMs) {
        super(character);
        botType = "ShowcaseBot";
        endsAt = System.currentTimeMillis() + durationMs;
    }

    /* The attacks the bot will cycle: its job's, as the table lists them (a showcase is not limited by SP). */
    public static List<BotAttackProfile> rotationFor(Character bot) {
        WeaponType weapon = BotAttack.resolveEquippedWeaponType(bot);
        BotAttackConfig.JobAttacks a = BotAttackConfig.resolve(bot.getJob(), weapon);
        List<BotAttackProfile> out = new ArrayList<>();
        for (BotAttackProfile p : new BotAttackProfile[]{a.single(), a.aoe(), a.ultimate()}) {
            if (p != null && !out.contains(p)) {
                out.add(p);
            }
        }
        for (BotAttackConfig.Extra e : a.extras()) {
            out.add(e.profile());
        }
        return out;
    }

    public List<BotAttackProfile> rotation() {
        return rotation;
    }

    /*
     * What a player's cadence for this attack is with the bot's weapon, for the owner's spot check: "every 660 ms" for a
     * swing (the pose rounded up to the client's 30 ms update step), "held, a shot every 120 ms" for a keydown stream.
     */
    public static String cadenceLabel(Character bot, BotAttackProfile p) {
        BotSwingPlan plan = BotSwingPlan.of(p, BotAttackDriver.packetLevel(bot, p),
                BotAttackDriver.equippedWeaponId(bot), false, p.numDamage);
        if (plan.shots() > 1) {
            return "held, a shot every " + BotSwingPlan.KEYDOWN_SHOT_MS + " ms";
        }
        int pose = BotAttackTiming.poseMs(plan.action(), plan.speed());
        long every = plan.busyMs(p.cooldownMs);
        return pose > 0 ? "every " + every + " ms (pose " + pose + " ms)" : "every " + every + " ms (fixed)";
    }

    @Override
    public void updateState() {
        super.updateState();
        clock.trySweep(this::step);
    }

    // One showcase beat: run from the macro tick and from the swing clock, never both at once (BotSwingClock).
    private void step() {
        if (checkIfNotRunningOrPaused() || leaving) {
            clock.disarm();
            return;
        }
        Character chr = getChr();
        long now = System.currentTimeMillis();
        if (now >= endsAt) {
            leaving = true;
            clock.disarm();
            setRunning(false);
            runAsync(() -> BotGeneration.removeBotFromServer(chr));
            return;
        }
        if (rotation.isEmpty()) {
            rotation.addAll(rotationFor(chr));
            if (rotation.isEmpty()) {
                return;
            }
        }
        if (now < nextSwingAt) {
            clock.armAt(nextSwingAt, now, this::step);
            return;
        }
        BotAttackProfile p = rotation.get(next % rotation.size());
        BotAttackDriver.AttackResult r = BotAttackDriver.forceProfile(chr, p);
        if (!r.hit() && soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.ATTACK)) { // !qa bk-1
            soloMapling.qa.QaLog.map(soloMapling.qa.QaLog.Topic.ATTACK, chr.getMapId(), chr.getName() + " "
                    + chr.getJob() + " skill " + p.skillId + " not sent: " + r.reason());
        }
        long swingEnds = Math.max(now, BotAttackDriver.swingEndsAt(chr.getId()));
        int repeats = isHeldStream(p) ? 1 : REPEATS;
        if (r.hit() && BotAttackTiming.enabled() && ++repeat < repeats) {
            nextSwingAt = swingEnds; // again as soon as a player could
        } else {
            repeat = 0;
            next++; // a skill with nothing in its reach is skipped this round, not retried forever
            nextSwingAt = r.hit() ? swingEnds + GAP_MS : now + 400;
        }
        clock.armAt(nextSwingAt, now, this::step);
    }

    private static boolean isHeldStream(BotAttackProfile p) {
        return BotClientPoses.isKeydown(p.skillId) && BotClientPoses.keepsKeydownPose(p.skillId);
    }
}
