package client.command.commands.gm0;

import client.Client;
import client.command.Command;
import config.YamlConfig;
import constants.id.MapId;
import scripting.npc.NPCScriptManager;

/**
 * 1.6.6.1 (owner 8 Nov 2026): @rebirth opens Wyatt the Reborn's rebirth talk (scripts/npc/rebirth.js), the same one a
 * click on him runs through NPCTalkHandler. The released client never sends the talk for a click on Wyatt (1300009 has
 * no client script), so the Essence Stone and the rebirth were out of reach. It works only in Base of Roots, where he
 * stands, the way @craft opens his crafting talk.
 */
public class RebirthCommand extends Command {
    {
        setDescription("Talk to Wyatt the Reborn about your rebirth (in Base of Roots).");
    }

    static final String NOT_HERE = "Wyatt the Reborn is in Base of Roots. Go there and type @rebirth again.";
    static final String OFF = "The rebirth system is not on.";

    /** Why @rebirth is refused on this map, or null when it opens Wyatt's talk (it then runs {@code open}). */
    static String handle(boolean rebirthOn, int mapId, Runnable open) {
        if (!rebirthOn) {
            return OFF;
        }
        if (mapId != MapId.MAPLEROOT_HUB) {
            return NOT_HERE;
        }
        open.run();
        return null;
    }

    @Override
    public void execute(Client c, String[] params) {
        String refusal = handle(YamlConfig.config.server.USE_REBIRTH_SYSTEM, c.getPlayer().getMapId(),
                () -> NPCScriptManager.getInstance().start(c, YamlConfig.config.server.REBIRTH_NPC_ID, "rebirth", null));
        if (refusal != null) {
            c.getPlayer().dropMessage(5, refusal);
        }
    }
}
