package soloMapling.mechanics;

import scripting.event.EventInstanceManager;
import server.life.Monster;
import server.life.OverrideMonsterStats;
import server.maps.MapleMap;
import soloMapling.mechanics.MechanicsModel.Tier;
import soloMapling.mechanics.MechanicsModel.TierRules;

import java.util.List;

/**
 * 1.7.0 "PQ difficulties", round 11 item C: the tier's numbers reach the room. Once per tick, when the switch is on, the
 * mobs the current stage names get the tier's HP multiplier through {@code Monster.setOverrideStats} - the same call the
 * party quests' own scripts already use ({@code scripts/event/KerningPQ.js} scales its stage mobs that way), so nothing
 * new is invented for the mob side.
 *
 * <p>Switch {@code -Dslums.mechanics.tiers} (default <b>off</b>: off means the WZ numbers, byte for byte). Only the ids
 * the stage lists are touched, and only the ones standing on the stage's own map: a mob the row does not name - a
 * summon, a neighbour the map happens to spawn - keeps its WZ stats. The HP base is always the pre-override stat, so a
 * mob is scaled once and a later tick leaves it alone; a respawned or newly spawned mob has no override yet and is
 * scaled when its tick comes.
 *
 * <p><b>What does not scale, and why.</b> {@code mobDamageMultiplier} is carried by the row and read by nothing: touch
 * and body damage come from the mob's WZ stats ({@code Monster.getPADamage()} answers {@code stats.getPADamage()},
 * {@code Monster.java:1845-1846}), so honouring it would mean deciding what a scaled touch hit means for knockback,
 * death counts and the bots' KITE bands - an owner decision, not a plumbing one. {@code mobLevelBonus} is likewise
 * unread: a level is not a number the HP curve gives back without inventing the curve (WZ HP is not a function of level
 * alone), so the row may declare it and the executor does not pretend to honour it.
 */
public final class TierScaling {

    public static final String FLAG = "slums.mechanics.tiers";

    private TierScaling() {
    }

    /** Off unless a server turns it on; with it off every room keeps its WZ numbers. */
    public static boolean on() {
        return Boolean.getBoolean(FLAG);
    }

    /**
     * The tier's numbers for the run's current stage, or null when there is nothing to do: the switch is off, the tier
     * is stock, the row does not name the tier, or the multiplier is 1.
     */
    static TierRules rulesFor(RunState run) {
        if (!on() || run == null || run.tier() == Tier.NORMAL || run.content() == null) {
            return null;
        }
        TierRules rules = run.content().rules(run.tier()).orElse(null);
        return rules == null || rules.mobHpMultiplier() == 1.0 ? null : rules;
    }

    /** Scales the current stage's listed mobs on the instance map; returns how many it changed this tick. */
    public static int applyTo(RunState run, EventInstanceManager eim) {
        TierRules rules = rulesFor(run);
        if (rules == null || eim == null) {
            return 0;
        }
        MechanicsModel.Stage stage = run.stageData();
        if (stage == null) {
            return 0;
        }
        MapleMap map = eim.getMapInstance(stage.mapId());
        if (map == null) {
            return 0;
        }
        return scale(map.getAllMonsters(), stage.mobs(), rules);
    }

    /**
     * The pure half: every living mob of {@code mobs} whose id the stage lists, that has no override yet, gets its HP
     * times the tier's multiplier. Never scales an id the row does not name, never touches a dead mob, never scales a mob
     * twice - whatever the caller's tick rhythm is.
     */
    static int scale(List<Monster> mobs, List<Integer> listedIds, TierRules rules) {
        if (mobs == null || listedIds == null || listedIds.isEmpty() || rules == null
                || rules.mobHpMultiplier() == 1.0) {
            return 0; // a stock tier is not a scale of 1: it is no scaling at all, so the mobs stay as WZ made them
        }
        int changed = 0;
        for (Monster mob : mobs) {
            if (mob == null || !mob.isAlive() || !listedIds.contains(mob.getId()) || mob.getChangedStats() != null) {
                continue; // not this row's mob, dead, or already wearing this tier's stats
            }
            long hp = Math.max(1L, Math.round(mob.getStats().getHp() * rules.mobHpMultiplier()));
            if (hp > Integer.MAX_VALUE) {
                hp = Integer.MAX_VALUE; // the packet carries an int; a bigger number would overflow a health bar
            }
            mob.setOverrideStats(new OverrideMonsterStats((int) hp, mob.getStats().getMp(), mob.getStats().getExp()));
            changed++;
        }
        return changed;
    }
}
