package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import server.bounce.BounceZoneService;
import server.bounce.BounceZones;

import java.util.List;

/*
 * !bounce list | here | reload | bypass
 * Where MapleRoot's universal bounces (Ninja Bounce 1050 family, Vertical Bounce 1054 family) are blocked:
 * src/main/java/soloMapling/BounceZones/bounce-zones.yaml. reload re-reads it and pushes it to every online player.
 */
public class BounceCommand extends Command {
    {
        setDescription("Bounce block zones: !bounce list | here | reload | bypass");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        String sub = params.length == 0 ? "help" : params[0].toLowerCase();
        switch (sub) {
            case "list" -> {
                BounceZones z = BounceZones.getInstance();
                for (BounceZones.Category cat : z.categories()) {
                    gm.yellowMessage(cat.name() + (cat.enabled() ? " [ON]" : " [off]")
                            + (cat.blockFlashJump() ? " +Flash Jump" : "") + ": " + cat.ranges().size()
                            + " entries, " + cat.idCount() + " map ids. " + cat.description());
                }
                gm.yellowMessage("Blocked now: " + z.bounceRanges().size() + " bounce ranges, "
                        + z.flashJumpRanges().size() + " Flash Jump ranges.");
            }
            case "here" -> gm.yellowMessage(here(BounceZones.getInstance(), gm.getMapId()));
            case "reload" -> {
                int players = BounceZoneService.reloadAndPushAll();
                BounceZones z = BounceZones.getInstance();
                gm.yellowMessage("Bounce zones reloaded: " + z.categories().size() + " categories, "
                        + z.bounceRanges().size() + " bounce ranges blocked; sent to " + players + " player(s).");
            }
            case "bypass" -> gm.yellowMessage(BounceZoneService.toggleBypass(gm.getId())
                    ? "Bounce backstop bypassed for you (the server lets your bounces through; your client still blocks them)."
                    : "Bounce backstop applies to you again.");
            default -> gm.yellowMessage("!bounce list | here | reload | bypass");
        }
    }

    public static String here(BounceZones z, int mapId) {
        List<BounceZones.Category> listed = z.listing(mapId);
        String by = z.blockedBy(mapId).map(BounceZones.Category::name).orElse(null);
        StringBuilder s = new StringBuilder("Map " + mapId + ": ");
        s.append(by == null ? "bounces allowed" : "bounces BLOCKED by " + by);
        s.append(z.blocksFlashJump(mapId) ? ", Flash Jump blocked" : ", Flash Jump allowed");
        if (!listed.isEmpty()) {
            s.append(". Listed in ").append(listed.stream()
                    .map(c -> c.name() + (c.enabled() ? "" : " (off)")).toList());
        }
        return s.append('.').toString();
    }
}
