package net.server.channel.handlers;

import java.util.function.IntUnaryOperator;

/**
 * The Incubator (5060002) hatch decision, kept free of Client/inventory types so it can be tested.
 * <p>
 * Research (P0-6, policy B2/B5): the item data says the Incubator is "needed to open the eggs from pigmy"
 * (String.wz Cash.img 5060002; Item.wz Cash/0506 has no spec). The eggs are the "Pigmy Egg" items 4170000-4170007
 * and 4170009 (String.wz Etc.img). 4170012 is a "DS Egg" (Dragon Sleeper) and is not accepted. The reward table is
 * the HeavenMS/Cosmic one; no source gives weights, so the pick is uniform. Classification: V83_BASELINE
 * (accepted set and table are inherited, no SlumsMS-custom design exists).
 */
public final class IncubatorHatch {
    public static final int INCUBATOR_ID = 5060002;

    static final int[] IDS = {1012070, 1302049, 1302063, 1322027, 2000004, 2000005, 2020013, 2020015, 2040307,
            2040509, 2040519, 2040521, 2040533, 2040715, 2040717, 2040810, 2040811, 2070005, 2070006, 4020009};
    static final int[] QUANTITIES = {1, 1, 1, 1, 240, 200, 200, 200, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 3};

    /** The side effects the hatch needs; the handler backs them with the real inventories. */
    public interface Ops {
        /** Item id in the chosen target slot, or -1 when that slot is empty/invalid. */
        int targetItemId();

        boolean hasSpaceFor(int itemId, int quantity);

        boolean consumeEgg();

        boolean consumeIncubator();

        boolean grant(int itemId, int quantity);

        void restoreEgg();

        void restoreIncubator();
    }

    public record Result(boolean hatched, int rewardId, int quantity, String refusal) {
    }

    public static boolean isEgg(int itemId) {
        return (itemId >= 4170000 && itemId <= 4170007) || itemId == 4170009;
    }

    /** @param pick returns an index in [0, bound) */
    public static Result hatch(Ops ops, IntUnaryOperator pick) {
        int target = ops.targetItemId();
        if (target < 0) {
            return refuse("There is nothing in that slot to incubate.");
        }
        if (!isEgg(target)) {
            return refuse("The Incubator can only hatch a Pigmy Egg.");
        }
        int i = pick.applyAsInt(IDS.length);
        int id = IDS[i];
        int qty = QUANTITIES[i];
        if (!ops.hasSpaceFor(id, qty)) {
            return refuse("Your inventory is full. Make room before using the Incubator.");
        }
        if (!ops.consumeEgg()) {
            return refuse("The egg could not be found.");
        }
        if (!ops.consumeIncubator()) {
            ops.restoreEgg();
            return refuse("The Incubator could not be found.");
        }
        if (!ops.grant(id, qty)) {
            ops.restoreEgg();
            ops.restoreIncubator();
            return refuse("Your inventory is full. Make room before using the Incubator.");
        }
        return new Result(true, id, qty, null);
    }

    private static Result refuse(String why) {
        return new Result(false, 0, 0, why);
    }

    private IncubatorHatch() {
    }
}
