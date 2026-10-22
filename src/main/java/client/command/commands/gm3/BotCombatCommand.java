package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.Job;
import client.command.Command;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotCombatTuning;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotHabits;
import soloMapling.ArtificialPlayer.BotCombatSystem.CombatStaging;
import soloMapling.ArtificialPlayer.BotHelpers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 1.5.0 rc3 (test server only, registered with !qa): the human combat layer by hand.
 *   !botcombat                          the switches (BOT_COMBAT_*, BOT_KS_MANNERS, BOT_SPREAD) and this help
 *   !botcombat set <key> <value|clear>  override one until restart: human | aoe | sloppy | deaths | manners | spread
 *   !botcombat habits <bot name>        that bot's loot, potion, rebuff and manners habits
 *   !botcombat stage [job ...]          held grinders next to you (default crusader fp_mage ranger hermit)
 *   !botcombat clear                    takes your staged grinders away
 *   !botcombat spread                   how spread the bots on this map are
 *   !botcombat pets [n]                 1.5.0 rc4: n (default 3) held grinders with looting pets next to you
 *   !botcombat pet <bot name>           that bot's pet, pet equips and loot style
 * The !qa scenarios cb-1 .. cb-4, sp-1 and pt-1 stage the same things with a log (docs/ops/qa-suite.md).
 */
public class BotCombatCommand extends Command {
    {
        setDescription("Test server: bot combat habits, switches and staged grinders (1.5.0 rc3).");
    }

    private static final Map<Integer, List<Integer>> staged = new ConcurrentHashMap<>();

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        if (params.length == 0) {
            gm.yellowMessage("Bot combat: " + BotCombatTuning.describe());
            gm.yellowMessage("!botcombat set <human|aoe|sloppy|deaths|manners|spread> <value|clear> | habits <bot> |"
                    + " stage [job ...] | clear | spread | pets [n] | pet <bot>");
            return;
        }
        switch (params[0].toLowerCase(java.util.Locale.ROOT)) {
            case "set" -> {
                if (params.length < 3 || !BotCombatTuning.override(params[1],
                        "clear".equalsIgnoreCase(params[2]) ? null : params[2])) {
                    gm.yellowMessage("Syntax: !botcombat set <human|aoe|sloppy|deaths|manners|spread> <value|clear>");
                    return;
                }
                gm.yellowMessage("Bot combat: " + BotCombatTuning.describe());
            }
            case "habits" -> {
                Character bot = params.length > 1
                        ? c.getChannelServer().getPlayerStorage().getCharacterByName(params[1]) : null;
                if (bot == null || !BotHelpers.isBot(bot)) {
                    gm.yellowMessage("No bot called " + (params.length > 1 ? params[1] : "?") + " on this channel.");
                    return;
                }
                gm.yellowMessage(bot.getName() + ": " + BotHabits.of(bot.getId()).describe());
            }
            case "stage" -> {
                List<Job> jobs = new ArrayList<>();
                for (int i = 1; i < params.length; i++) {
                    Job j = CombatStaging.job(params[i]);
                    if (j == null) {
                        gm.yellowMessage("Not a job: " + params[i]);
                        return;
                    }
                    jobs.add(j);
                }
                if (jobs.isEmpty()) {
                    for (String j : List.of("CRUSADER", "FP_MAGE", "RANGER", "HERMIT")) {
                        jobs.add(CombatStaging.job(j));
                    }
                }
                List<Integer> ids = CombatStaging.stage(gm, jobs, 0, 0, null, true);
                staged.computeIfAbsent(gm.getId(), k -> new java.util.concurrent.CopyOnWriteArrayList<>()).addAll(ids);
                gm.yellowMessage(ids.size() + " grinder(s) held on this map: " + ids + ". !botcombat clear removes them.");
            }
            case "clear" -> {
                List<Integer> ids = staged.remove(gm.getId());
                if (ids != null) {
                    CombatStaging.unstage(ids);
                }
                gm.yellowMessage((ids == null ? 0 : ids.size()) + " staged grinder(s) removed.");
            }
            case "spread" -> {
                if (gm.getMap() != null) {
                    CombatStaging.spreadTally(gm.getMap()).forEach(gm::yellowMessage);
                }
            }
            case "pets" -> {
                int n = 3;
                if (params.length > 1 && params[1].matches("\\d")) {
                    n = Math.max(1, Math.min(6, Integer.parseInt(params[1])));
                }
                List<Integer> ids = CombatStaging.stage(gm, List.of(), n, 0, null, true);
                staged.computeIfAbsent(gm.getId(), k -> new java.util.concurrent.CopyOnWriteArrayList<>()).addAll(ids);
                soloMapling.ArtificialPlayer.BotPets.stageAll(ids, true).forEach(gm::yellowMessage);
                gm.yellowMessage(ids.size() + " grinder(s) with looting pets held here. !botcombat clear removes them.");
            }
            case "pet" -> {
                Character bot = params.length > 1
                        ? c.getChannelServer().getPlayerStorage().getCharacterByName(params[1]) : null;
                if (bot == null || !BotHelpers.isBot(bot)) {
                    gm.yellowMessage("No bot called " + (params.length > 1 ? params[1] : "?") + " on this channel.");
                    return;
                }
                gm.yellowMessage(soloMapling.ArtificialPlayer.BotPets.describe(bot));
            }
            default -> gm.yellowMessage("!botcombat [set|habits|stage|clear|spread|pets|pet]");
        }
    }
}
