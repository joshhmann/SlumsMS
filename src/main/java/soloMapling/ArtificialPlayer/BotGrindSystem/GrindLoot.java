package soloMapling.ArtificialPlayer.BotGrindSystem;

import client.Character;
import server.maps.MapItem;
import server.maps.MapObject;
import server.maps.MapObjectType;
import soloMapling.ArtificialPlayer.BotCommandsPack.DropCommands;
import soloMapling.ArtificialPlayer.BotHoldings;
import soloMapling.ArtificialPlayer.BotPets;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotHabits;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

// Organic loot collection shared by every grind style: the passive at-feet vacuum that never
// interrupts a swing, and the between-kills walk-and-loot sweep. The caller passes its own leash
// and search range, so camp sweeps its spot, roam sweeps a box around the bot, and later styles
// bring their own bounds. Extracted verbatim from the pre-split GrindBrain. Ours (SoloMapling).
final class GrindLoot {

    private static final int LOOT_PICKUP_PX = LootPicker.PICKUP_PX; // close enough to grab
    private static final long LOOT_GAP_MIN_MS = 100;         // stagger between at-feet pickups
    private static final long LOOT_GAP_MAX_MS = 350;
    private static final long LOOT_SWEEP_GAP_MIN_MS = 80;    // tighter pacing while drive-by sweeping (walk-over at ~125px/s clears ~60px spacing)
    private static final long LOOT_SWEEP_GAP_MAX_MS = 200;
    private static final int SWEEP_PICKUP_CAP = 2;           // pickups per combat tick while sweeping (keeps the anim readable)
    static final int LOOT_SAME_LEDGE_Y = LootPicker.SAME_LEDGE_DY; // sweep: only walk to drops near the bot's Y (its ledge)
    // Let a fresh drop finish its fly-out arc and settle on the floor before the bot grabs it — instant
    // pickup looks like the item teleports into the bot mid-air. Aged against MapItem.getDropTime() (the
    // server clock stamp set when the drop spawns), so it's "this drop has been on the ground ≥ this long".
    private static final long LOOT_SETTLE_MS = LootPicker.SETTLE_MS;
    private static final long LOOT_NARRATE_GAP_MS = 6_000;   // throttle loot lines (pickups are frequent)

    private final GrindBrain b;
    private long lootNextMs = 0L;
    private long lastLootNarrateMs = 0L;

    GrindLoot(GrindBrain brain) {
        this.b = brain;
    }

    void reset() {
        lootNextMs = 0L;
        lastLootNarrateMs = 0L;
    }

    // Passive at-feet loot: scoop a single drop sitting directly at the bot's feet (staggered), wherever the
    // bot is standing or walking. Runs every combat tick; never moves the bot or interrupts the swing, so the
    // bot only collects loot it is literally on top of (LOOT_PICKUP_PX). Distant drops are left for a
    // reposition to pass over, or the lull sweep (tryWalkAndLoot).
    void grabLootAtFeet(Character chr) {
        if (now() < lootNextMs) {
            return;
        }
        // 1.5.0 rc3: a bot whose pet loots picks up what lands around it, without walking for it
        // 1.5.0 rc4: and it is the pet that picks it up (BotPets: its loot equips, ownership, player drops)
        int petSlot = b.lootStyle == BotHabits.Loot.PET ? BotPets.lootingPetSlot(chr) : -1;
        int reach = petSlot >= 0 ? PET_LOOT_PX : LOOT_PICKUP_PX;
        MapItem drop = nearestCollectableDrop(chr, reach, Integer.MIN_VALUE, Integer.MAX_VALUE, reach, petSlot >= 0);
        if (drop != null) {
            if (petSlot >= 0) {
                DropCommands.botPetLootSingleDrop(chr, drop, petSlot);
                if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.PETS)) {
                    soloMapling.qa.QaLog.map(soloMapling.qa.QaLog.Topic.PETS, chr.getMapId(), "PET LOOT " + chr.getName()
                            + "'s pet picks up " + (drop.getMeso() > 0 ? drop.getMeso() + " mesos" : "item "
                            + drop.getItemId()) + (drop.getOwnerId() == chr.getId() ? " (its own drop)" : " (free drop)"));
                }
            } else {
                DropCommands.botLootSingleDrop(chr, drop);
            }
            lootNextMs = now() + lootGapMs();
            b.markProgress(); // collecting loot is productive, not wedged
            narrateLoot("scooping up loot at my feet");
        }
    }

    // Between kills, sweep the whole drop pile in one continuous motion instead of the old stop-and-go: gather
    // every eligible drop in [x0, x1] (the caller's leash), enter the chain at the near end and walk through to
    // the far end, vacuuming whatever the bot passes over — no hard stop, no re-approach per item. Advanced
    // classes blink/dash to the near end first. Returns true when there is loot to deal with this tick (so the
    // caller skips chasing a mob / holds its lull); false = nothing collectable, do your dry behaviour.
    boolean tryWalkAndLoot(Character chr, int x0, int x1, int searchRangePx) {
        List<MapItem> drops = collectableDrops(chr, searchRangePx, x0, x1, LOOT_SAME_LEDGE_Y);
        if (drops.isEmpty()) {
            return false;
        }
        Point pos = chr.getPosition();
        if (pos == null) {
            return true; // loot exists but we can't route this tick — still "there's loot to deal with"
        }
        // Route: near end = closest drop; far end = the farthest drop on the near end's side. Walking from the
        // bot through the near end to the far end passes over the whole chain in one direction; drops on the far
        // side of the bot are caught on a later sweep once the near ones are gone.
        MapItem near = drops.get(0);
        double nearSq = pos.distanceSq(near.getPosition());
        for (MapItem mi : drops) {
            double dsq = pos.distanceSq(mi.getPosition());
            if (dsq < nearSq) {
                nearSq = dsq;
                near = mi;
            }
        }
        boolean sweepRight = near.getPosition().x >= pos.x;
        MapItem far = near;
        for (MapItem mi : drops) {
            int mx = mi.getPosition().x;
            if (sweepRight ? mx > far.getPosition().x : mx < far.getPosition().x) {
                far = mi;
            }
        }
        // Class-skill approach: mages blink / hermits flash-jump to the near end of the chain, then the sweep
        // walk covers the rest. skillMoveToward carries every safety gate (style, cooldown, min distance,
        // onDifferentLedge refusal); WALK-style bots and any refusal fall through to the plain walk below.
        Point nearGp = GCMovement.groundPointBelow(chr.getMap(), near.getPosition().x, near.getPosition().y);
        int nearY = (nearGp != null) ? nearGp.y : near.getPosition().y;
        if (b.engage.skillMoveToward(chr, near.getPosition().x, nearY)) {
            b.engaged = false;
            narrateLoot("blinking over to a loot chain");
            return true; // blinked this tick; the sweep walk issues next tick once grounded
        }
        // Drive-by walk to the far end — no GCMovement.stop. Re-issue only when the far target shifts past the
        // epsilon (existing retarget pattern) so the walk runs uninterrupted across ticks.
        b.engaged = false;
        int tx = GrindBrain.clamp(far.getPosition().x, x0, x1);
        if (Math.abs(tx - b.lastMoveTargetX) >= GrindBrain.ROAM_RETARGET_EPS) {
            Point gp = GCMovement.groundPointBelow(chr.getMap(), far.getPosition().x, far.getPosition().y);
            int ty = (gp != null) ? gp.y : far.getPosition().y;
            GCMovement.move(chr, tx, ty);
            b.lastMoveTargetX = tx;
            narrateLoot("sweeping through a chain of drops");
        }
        vacuumWhileSweeping(chr);
        return true;
    }

    // ── 1.5.0 rc3: loot habits (BotHabits.Loot) ──
    static final int PET_LOOT_PX = 160;          // what a looting pet reaches around its owner
    static final int INSTANT_LOOT_PX = 180;      // an instant looter walks this far for a fresh drop
    static final long INSTANT_AFTER_KILL_MS = 3_000;

    // Etc. drops that are no quest's: what a junk-skipping bot leaves behind (mesos, equips and usables it takes).
    static boolean isJunk(MapItem mi) {
        if (mi.getMeso() > 0 || mi.getItem() == null || mi.getQuest() > 0) {
            return false;
        }
        return constants.inventory.ItemConstants.getInventoryType(mi.getItem().getItemId())
                == client.inventory.InventoryType.ETC;
    }

    private MapItem nearestCollectableDrop(Character chr, int rangePx, int x0, int x1, int yLimit) {
        return nearestCollectableDrop(chr, rangePx, x0, x1, yLimit, false);
    }

    // 1.6.2: the drop selection is the shared LootPicker (owned alts pick through it too); what a bot may take is
    // its own rule on top: the bot loot rules, room in its holdings, its junk habit, and its pet's rules.
    private MapItem nearestCollectableDrop(Character chr, int rangePx, int x0, int x1, int yLimit, boolean byPet) {
        long now = now();
        return LootPicker.nearest(chr, new LootPicker.Reach(rangePx, x0, x1, yLimit, LOOT_SETTLE_MS), now,
                mi -> botMayTake(chr, mi) && (!byPet || BotPets.petMayTake(chr, mi, now)));
    }

    // Every eligible drop in [x0, x1] within rangePx and the same-ledge Y band - the multi-drop counterpart to
    // nearestCollectableDrop (identical filters), used to plan a whole sweep.
    private List<MapItem> collectableDrops(Character chr, int rangePx, int x0, int x1, int yLimit) {
        return LootPicker.all(chr, new LootPicker.Reach(rangePx, x0, x1, yLimit, LOOT_SETTLE_MS), now(),
                mi -> botMayTake(chr, mi));
    }

    // A population bot's own loot rules: the bot loot rules (ownership), room in its holdings (never walk to loot it
    // cannot hold, and never delete it uncredited), and a junk-skipping bot leaves etc. junk (1.5.0 rc3).
    private boolean botMayTake(Character chr, MapItem mi) {
        return DropCommands.botCanLoot(chr, mi) && BotHoldings.canCreditDrop(chr, mi)
                && !(b.lootStyle == BotHabits.Loot.SKIP_JUNK && isJunk(mi));
    }

    // In-sweep vacuum: while the sweep walk carries the bot across the chain, grab up to SWEEP_PICKUP_CAP drops
    // sitting at its feet this tick — WITHOUT halting or cancelling the walk (the GCMovement.stop was what made
    // the old loot stop-and-go). Paced by the tightened sweep gap so a walk-over doesn't outrun the pacer, and
    // settle is still enforced (via nearestCollectableDrop) so mid-flight drops aren't yanked in.
    private void vacuumWhileSweeping(Character chr) {
        if (now() < lootNextMs) {
            return;
        }
        int picked = 0;
        while (picked < SWEEP_PICKUP_CAP) {
            MapItem drop = nearestCollectableDrop(chr, LOOT_PICKUP_PX, Integer.MIN_VALUE, Integer.MAX_VALUE, LOOT_PICKUP_PX);
            if (drop == null) {
                break;
            }
            DropCommands.botLootSingleDrop(chr, drop);
            picked++;
            b.markProgress(); // collecting loot is productive, not wedged
        }
        if (picked > 0) {
            lootNextMs = now() + sweepGapMs();
            narrateLoot("scooping loot on the move");
        }
    }

    private long sweepGapMs() {
        return LOOT_SWEEP_GAP_MIN_MS + (long) (b.rng.nextDouble() * (LOOT_SWEEP_GAP_MAX_MS - LOOT_SWEEP_GAP_MIN_MS));
    }

    private long lootGapMs() {
        return LOOT_GAP_MIN_MS + (long) (b.rng.nextDouble() * (LOOT_GAP_MAX_MS - LOOT_GAP_MIN_MS));
    }

    // Throttled loot narration — pickups are frequent, so cap one line per LOOT_NARRATE_GAP_MS. Its
    // ABSENCE is itself a signal: a stationed ranged/magic bot that never closes on a kill never loots.
    private void narrateLoot(String msg) {
        if (!GrindBrain.NARRATE) {
            return;
        }
        if (now() - lastLootNarrateMs >= LOOT_NARRATE_GAP_MS) {
            lastLootNarrateMs = now();
            b.debugLine(msg);
        }
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
