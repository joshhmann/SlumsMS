package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.menus.QaMenu;
import soloMapling.qa.QaRefusal;
import soloMapling.qa.QaSuite;

import java.util.Arrays;
import java.util.List;

/*
 * Test server only (registered when QaSuite.isEnabled(): -Dslums.qa=true or -Dslums.attackcapture=true):
 *   !qa (a menu) | !qa list | !qa run <id> [args] | !qa do <step> [args] | !qa where | !qa stop
 * Runs a release-notes test scenario for the GM with its debug log in logs/qa/ (docs/ops/qa-suite.md).
 */
public class QaCommand extends Command {
    {
        setDescription("Test server: run a release test scenario with its debug log (!qa: menu, !qa list).");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        if (params.length == 0) {
            // 1.5.0: the scenarios as a menu in the NPC window (each click runs the typed !qa command)
            if (!QaMenu.open(gm)) {
                gm.yellowMessage("Close the NPC window first. !qa list | !qa run <id> [args] | !qa do <step> | !qa where | !qa stop");
            }
            return;
        }
        String sub = params[0];
        String[] rest = params.length > 1 ? Arrays.copyOfRange(params, 1, params.length) : new String[0];
        QaSuite suite = QaSuite.get();
        try {
            switch (sub) {
                case "list" -> show(gm, suite.list());
                case "run" -> {
                    if (rest.length == 0) {
                        gm.yellowMessage("Syntax: !qa run <id> [args]   (!qa list for the ids)");
                        return;
                    }
                    show(gm, suite.start(gm, rest[0], Arrays.copyOfRange(rest, 1, rest.length)));
                }
                case "do" -> {
                    if (rest.length == 0) {
                        gm.yellowMessage("Syntax: !qa do <step> [args]");
                        return;
                    }
                    show(gm, suite.action(gm, rest[0], Arrays.copyOfRange(rest, 1, rest.length)));
                }
                case "where" -> show(gm, suite.where(gm.getId()));
                case "stop" -> show(gm, suite.stop(gm.getId(), "!qa stop"));
                default -> gm.yellowMessage("!qa (menu) | !qa list | !qa run <id> [args] | !qa do <step> | !qa where | !qa stop");
            }
        } catch (QaRefusal r) {
            gm.yellowMessage(r.getMessage());
        }
    }

    private static void show(Character gm, List<String> lines) {
        for (String l : lines) {
            gm.yellowMessage(l);
        }
    }
}
