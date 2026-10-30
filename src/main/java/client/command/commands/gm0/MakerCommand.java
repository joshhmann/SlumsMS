package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import client.inventory.MakerService;
import soloMapling.ArtificialPlayer.BotMakerSystem.BotMaker;
import soloMapling.ArtificialPlayer.BotMakerSystem.MakerPlan;
import soloMapling.companions.OwnedAltService;

import java.util.List;

/*
 * Ticket 112: @maker plan [alt] - the read-only maker plan (equips you, or one of your alts that is out with you,
 * could make right now that would be upgrades, best first); @maker crystals <alt> / @maker scrap <alt> - that alt
 * turns its leftovers into monster crystals / disassembles its junk through the Maker path, within the daily caps
 * (economy.yaml maker.*, off unless maker.botBatch is on). You craft for yourself in the Maker window.
 */
public class MakerCommand extends Command {
    { setDescription("Maker: @maker plan [alt] | @maker crystals <alt> | @maker scrap <alt>."); }

    static final int PLAN_LINES = 5;

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length == 0 || params.length > 2) {
            player.yellowMessage("Usage: @maker plan [alt] | @maker crystals <alt> | @maker scrap <alt>");
            return;
        }
        String what = params[0].toLowerCase(java.util.Locale.ROOT);
        Character who = params.length == 2 ? alt(player, params[1]) : player;
        if (who == null) {
            player.yellowMessage(params[1] + " is not one of your alts out with you (@botme " + params[1] + ").");
            return;
        }
        String name = who == player ? "You" : who.getName();
        switch (what) {
            case "plan" -> {
                if (MakerService.getMakerSkillLevel(who) < 1) {
                    player.yellowMessage(name + (who == player ? " don't" : " doesn't")
                            + " have the Maker skill yet: Moren in Magatia teaches it from level 45.");
                    return;
                }
                List<String> lines = MakerPlan.lines(MakerPlan.plan(who, PLAN_LINES));
                player.yellowMessage(lines.isEmpty() ? name + ": nothing you can make right now would be an upgrade."
                        : name + " could make: " + String.join("; ", lines));
            }
            case "crystals", "scrap" -> {
                if (who == player) {
                    player.yellowMessage("Make your own in the Maker window; @maker " + what + " <alt> is for your alts.");
                    return;
                }
                if (!BotMaker.enabled()) {
                    player.yellowMessage("Alts and bots are not crafting with Maker yet.");
                    return;
                }
                BotMaker.Result r = what.equals("crystals") ? BotMaker.makeCrystals(who, Integer.MAX_VALUE)
                        : BotMaker.disassembleJunk(who, Integer.MAX_VALUE);
                player.yellowMessage(who.getName() + (what.equals("crystals") ? " made " + r.made() + " monster crystal(s)"
                        : " disassembled " + r.made() + " equip(s)") + " (" + r.stoppedBy() + ").");
            }
            default -> player.yellowMessage("Usage: @maker plan [alt] | @maker crystals <alt> | @maker scrap <alt>");
        }
    }

    /* The owner's alt with this name that is out with them, or null. */
    static Character alt(Character owner, String name) {
        for (OwnedAltService.Active a : OwnedAltService.getInstance().active()) {
            if (a.owner() == owner && a.alt().getName().equalsIgnoreCase(name)) {
                return a.alt();
            }
        }
        return null;
    }
}
