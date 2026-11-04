package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.ArtificialPlayer.RelationView;
import soloMapling.companions.OwnedAltService;

import java.util.List;

public class BondsCommand extends Command {
    { setDescription("Shows your friends, partner, guild, family and alts."); }

    /** The reply for a player; the switch decides. Separate so tests need no client. */
    public static List<String> reply(RelationView.Source source) {
        return RelationView.enabled() ? RelationView.build(source) : List.of(RelationView.OFF_TEXT);
    }

    @Override public void execute(Client c, String[] params) {
        Character p = c.getPlayer();
        if (!RelationView.enabled()) {
            p.yellowMessage(RelationView.OFF_TEXT);
            return;
        }
        OwnedAltService.getInstance().runAsync(() -> reply(RelationView.of(p)).forEach(p::yellowMessage));
    }
}
