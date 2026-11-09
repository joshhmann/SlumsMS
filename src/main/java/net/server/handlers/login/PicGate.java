package net.server.handlers.login;

import client.Client;
import config.YamlConfig;
import net.server.coordinator.session.SessionCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * pic-bypass-char-select (S1, 9 Nov 2026): the character list tells the client whether it must give a PIC
 * ({@code ENABLE_PIC && !canBypassPic()}: 0 register one, 1 enter it; otherwise 2, none needed; PacketCreator and
 * ViewAllCharHandler). The PIC-less selects (CHAR_SELECT, PICK_ALL_CHAR) started the channel migration even when the
 * list had asked for a PIC, so an edited client entered the game without it. They are now refused exactly where the
 * list asked for one, before any session is reserved; a client the server told "2" (PIC off, or a live bypass entry)
 * selects as before.
 */
final class PicGate {
    private static final Logger log = LoggerFactory.getLogger(PicGate.class);

    private PicGate() {
    }

    /** What the character list told this client: a PIC is needed to enter (register one or enter it). */
    static boolean required(Client c) {
        return YamlConfig.config.server.ENABLE_PIC && !c.canBypassPic();
    }

    /** Ends the login session when a PIC-less select arrives where the list asked for a PIC. True when refused. */
    static boolean refusePicLessSelect(Client c, String packet) {
        if (!required(c)) {
            return false;
        }
        log.warn("Refused a {} without a PIC from account {}: its character list asked for one", packet, c.getAccID());
        SessionCoordinator.getInstance().closeSession(c, true);
        return true;
    }

    /** Ends the login session when a register-PIC select arrives for an account that already has one. */
    static boolean refusePicOverwrite(Client c, String packet) {
        String pic = c.getPic();
        if (pic == null || pic.isEmpty()) {
            return false;
        }
        log.warn("Refused a {} from account {}: it already has a PIC", packet, c.getAccID());
        SessionCoordinator.getInstance().closeSession(c, true);
        return true;
    }
}
