package server.checkin;

import net.opcodes.SendOpcode;
import net.packet.OutPacket;
import net.packet.Packet;

/** LP 0x3733: the Daily Check-In window's state, sent after every request and at login when a reward waits. */
public final class DailyCheckinPackets {

    private static final int RESP_SNAPSHOT = 1;

    private DailyCheckinPackets() {
    }

    /**
     * @param currentDay  the claimable day, or during the cooldown the last day claimed (0..28)
     * @param claimedMask bit {@code d - 1} set when day {@code d} was claimed this cycle
     * @param justClaimed 0 for a plain open; else the day just claimed (the client confirms it)
     */
    public static Packet snapshot(int currentDay, int claimedMask, int justClaimed) {
        OutPacket p = OutPacket.create(SendOpcode.DAILY_CHECKIN_SYNC);
        p.writeByte(RESP_SNAPSHOT);
        p.writeByte(currentDay);
        p.writeInt(claimedMask);
        p.writeByte(justClaimed);
        int days = DailyCheckinStreak.CYCLE_DAYS;
        p.writeByte(days);
        for (int d = 1; d <= days; d++) {
            p.writeInt(DailyCheckinRewards.iconItemId(d));
        }
        for (int d = 1; d <= days; d++) {
            p.writeString(DailyCheckinRewards.tooltip(d));
        }
        return p;
    }
}
