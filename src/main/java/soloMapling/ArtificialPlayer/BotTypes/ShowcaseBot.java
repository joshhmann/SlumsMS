package soloMapling.ArtificialPlayer.BotTypes;

import client.Character;
import client.inventory.WeaponType;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackConfig;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackTiming;
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
 * (the next swing when the pose has played out), so its rhythm can be checked too, then a gap before the next one.
 */
public class ShowcaseBot extends BotSM {

    private static final long GAP_MS = 1_500;
    static final int REPEATS = 3;

    private final long endsAt;
    private final List<BotAttackProfile> rotation = new ArrayList<>();
    private int next;
    private int repeat;
    private long nextSwingAt;
    private boolean leaving;

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

    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused() || leaving) {
            return;
        }
        Character chr = getChr();
        long now = System.currentTimeMillis();
        if (now >= endsAt) {
            leaving = true;
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
            return;
        }
        BotAttackProfile p = rotation.get(next % rotation.size());
        BotAttackDriver.AttackResult r = BotAttackDriver.forceProfile(chr, p);
        if (!r.hit() && soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.ATTACK)) { // !qa bk-1
            soloMapling.qa.QaLog.map(soloMapling.qa.QaLog.Topic.ATTACK, chr.getMapId(), chr.getName() + " "
                    + chr.getJob() + " skill " + p.skillId + " not sent: " + r.reason());
        }
        if (r.hit() && BotAttackTiming.enabled() && ++repeat < REPEATS) {
            nextSwingAt = BotAttackDriver.swingEndsAt(chr.getId()); // again as soon as a player could
            return;
        }
        repeat = 0;
        next++; // a skill with nothing in its reach is skipped this round, not retried forever
        long burst = BotSwingPlan.KEYDOWN_SHOTS * BotSwingPlan.KEYDOWN_SHOT_MS;
        nextSwingAt = now + (r.hit() ? GAP_MS + burst : 400);
    }
}
