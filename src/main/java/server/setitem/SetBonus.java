package server.setitem;

import java.util.Map;

/**
 * The stats every worn set adds together, already folded: {@code incAllStat} is in each of str/dex/int/luk.
 * {@link client.Character#recalcEquipStats} adds these to the equip totals; the client gets str..maxMp in
 * {@link SetItemPackets#pieceCounts} so its Ability window can show the same numbers.
 */
public record SetBonus(int str, int dex, int int_, int luk, int maxHp, int maxMp, int watk, int matk) {

    public static final SetBonus NONE = new SetBonus(0, 0, 0, 0, 0, 0, 0, 0);

    static SetBonus of(Map<String, Integer> stats) {
        int all = stats.getOrDefault("incAllStat", 0);
        return new SetBonus(
                stats.getOrDefault("incSTR", 0) + all,
                stats.getOrDefault("incDEX", 0) + all,
                stats.getOrDefault("incINT", 0) + all,
                stats.getOrDefault("incLUK", 0) + all,
                stats.getOrDefault("incMHP", 0),
                stats.getOrDefault("incMMP", 0),
                stats.getOrDefault("incPAD", 0),
                stats.getOrDefault("incMAD", 0));
    }

    public boolean isEmpty() {
        return equals(NONE);
    }
}
