package net;

import client.processor.npc.FredrickProcessor;
import database.note.NoteDao;
import net.opcodes.RecvOpcode;
import org.junit.jupiter.api.Test;
import service.NoteService;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Ticket 164: the empty InnerPortalHandler was deleted. Nothing in the tree sends 0x65; every scripted portal
 * (including the pt=5 ones on the KPQ/LPQ maps) arrives on CHANGE_MAP_SPECIAL and runs through
 * ChangeMapSpecialHandler/ChangeMapHandler. The opcode stays declared in RecvOpcode as wire-format inventory,
 * but it must resolve to no handler -- a future re-registration has to delete this test on purpose.
 */
class InnerPortalRegistrationTest {

    @Test
    void theInnerPortalOpcodeResolvesToNoHandler() {
        NoteService noteService = new NoteService(new NoteDao());
        PacketProcessor.registerGameHandlerDependencies(new ChannelDependencies(noteService, new FredrickProcessor(noteService)));
        PacketProcessor channelProcessor = PacketProcessor.getProcessor(0, 0);

        assertNotNull(channelProcessor.getHandler((short) RecvOpcode.USE_DOOR.getValue()),
                "the neighbouring door opcode is still served (the table really is populated)");
        assertNull(channelProcessor.getHandler((short) RecvOpcode.USE_INNER_PORTAL.getValue()),
                "USE_INNER_PORTAL (0x65) must have no handler");
    }
}
