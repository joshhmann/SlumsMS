package net.server.channel;

import client.Character;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import client.processor.npc.DueyProcessor;
import constants.id.ItemId;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gives the wedding booking ticket back to the partner who spent it when the couple never started the ceremony (ticket
 * 188, F3). One ticket was spent at the booking, so one comes back: straight into the booker's CASH inventory when they
 * are online and have room, otherwise as a Duey parcel, which also covers an offline booker and waits until claimed.
 */
final class WeddingTicketRefund {
    private static final Logger log = LoggerFactory.getLogger(WeddingTicketRefund.class);

    /** dueypackages.SenderName holds 13 characters. */
    static final String DUEY_SENDER = "Wedding Desk";

    private WeddingTicketRefund() {
    }

    /** The ticket the reservation NPCs take: 9201005 (cathedral) and 9201008 (chapel), regular or premium. */
    static int ticketId(boolean cathedral, boolean premium) {
        if (cathedral) {
            return premium ? ItemId.PREMIUM_WEDDING_TICKET_CATHEDRAL : ItemId.NORMAL_WEDDING_TICKET_CATHEDRAL;
        }
        return premium ? ItemId.PREMIUM_WEDDING_TICKET_CHAPEL : ItemId.NORMAL_WEDDING_TICKET_CHAPEL;
    }

    static void refund(World world, boolean cathedral, BookedWedding wedding) {
        int ticketId = ticketId(cathedral, wedding.premium());
        Character booker = world.getPlayerStorage().getCharacterById(wedding.bookerId());
        Character partner = world.getPlayerStorage().getCharacterById(wedding.partnerId());

        String venue = cathedral ? "Cathedral" : "Chapel";
        String cancelled = "[Wedding] Your " + venue + " wedding did not start in time, so the reservation was cancelled.";

        boolean returned = true;
        if (booker != null && InventoryManipulator.checkSpace(booker, ticketId, 1, "") && InventoryManipulator.addById(booker, ticketId, (short) 1)) {
            booker.dropMessage(6, cancelled + " Your wedding ticket is back in your CASH inventory, so you can arrange a new reservation.");
            log.info("Wedding {} ({}) timed out: ticket {} returned to chr {}", wedding.weddingId(), venue, ticketId, wedding.bookerId());
        } else if (DueyProcessor.dueyCreatePackage(new Item(ticketId, (short) 0, (short) 1), 0, DUEY_SENDER, wedding.bookerId())) {
            if (booker != null) {
                booker.dropMessage(6, cancelled + " Your CASH inventory is full, so your wedding ticket was sent to Duey.");
            }
            log.info("Wedding {} ({}) timed out: ticket {} sent to chr {} through Duey", wedding.weddingId(), venue, ticketId, wedding.bookerId());
        } else {
            returned = false;
            if (booker != null) {
                booker.dropMessage(6, cancelled + " Your wedding ticket could not be returned. Please tell a GM.");
            }
            log.error("Wedding {} ({}) timed out and ticket {} could not be returned to chr {}: re-grant it by hand", wedding.weddingId(), venue, ticketId, wedding.bookerId());
        }

        if (partner != null) {
            partner.dropMessage(6, cancelled + (returned ? " The wedding ticket was returned to your partner." : ""));
        }
    }
}
