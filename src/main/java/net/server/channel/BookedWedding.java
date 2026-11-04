package net.server.channel;

import java.util.Set;

/**
 * A wedding reservation that reached the front of its venue's queue. {@code bookerId} is the partner who talked to the
 * reservation NPC and paid the booking ticket, {@code partnerId} the other half of the couple.
 */
public record BookedWedding(boolean premium, int weddingId, Set<Integer> guests, int bookerId, int partnerId) {
}
