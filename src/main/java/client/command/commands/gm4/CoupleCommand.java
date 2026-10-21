package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import net.server.world.World;
import soloMapling.ArtificialPlayer.BotBuddies;
import soloMapling.ArtificialPlayer.BotCoupleSystem.BotCouples;
import soloMapling.ArtificialPlayer.BotCoupleSystem.BotWeddings;
import soloMapling.ArtificialPlayer.BotCoupleSystem.CoupleStaging;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.server.ExecutorServiceManager;
import soloMapling.server.SoloMaplingUtilities;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ticket 58 GM tools for bot couples (GM 4).
 * <pre>
 * !couple [list]                   every couple, who is online, who is ready to marry
 * !couple spawn [married]          two new bots next to you that are a couple (staged, memory only)
 * !couple form &lt;botA&gt; &lt;botB&gt;       make two online bots a couple (stored for roster bots)
 * !couple wedding [id]             the Amoria wedding now (default: your staged couple); watch it in Amoria ch 1
 * !couple note [id]                a love-note megaphone now (through the megaphone gate)
 * !couple step                     run the couples' minute step now (party, hop, chat, chair, rings)
 * !couple break &lt;id&gt;               break a couple up
 * !couple unstage                  remove the couple you staged
 * </pre>
 */
public class CoupleCommand extends Command {
    {
        setDescription("Bot couples (GM): !couple [list] | spawn [married] | form <botA> <botB> | wedding [id] | note [id] | step | break <id> | unstage");
    }

    private static final Map<Integer, int[]> staged = new ConcurrentHashMap<>();

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        ExecutorServiceManager.getExecutorService().execute(() -> {
            try {
                run(gm, params);
            } catch (RuntimeException e) {
                gm.yellowMessage("!couple failed: " + e.getMessage());
            }
        });
    }

    private void run(Character gm, String[] params) {
        String sub = params.length == 0 ? "list" : params[0].toLowerCase();
        long now = System.currentTimeMillis();
        switch (sub) {
            case "list" -> BotCouples.describe(now).forEach(gm::yellowMessage);
            case "spawn" -> {
                if (staged.containsKey(gm.getId())) {
                    gm.yellowMessage("You already staged a couple: !couple unstage first.");
                    return;
                }
                int[] ids = CoupleStaging.stage(gm, params.length > 1 && params[1].toLowerCase().startsWith("mar"));
                if (ids == null) {
                    gm.yellowMessage("No couple could be made here.");
                    return;
                }
                staged.put(gm.getId(), ids);
                BotCouples.Couple couple = BotCouples.coupleOf(ids[0]);
                gm.yellowMessage("Staged couple #" + couple.id() + ": " + BotBuddies.nameOf(ids[0]) + " + "
                        + BotBuddies.nameOf(ids[1]) + " (" + couple.stage().name().toLowerCase() + ").");
            }
            case "form" -> {
                if (params.length < 3) {
                    gm.yellowMessage("Usage: !couple form <botA> <botB>");
                    return;
                }
                Character a = bot(params[1]);
                Character b = bot(params[2]);
                if (a == null || b == null) {
                    gm.yellowMessage("Both must be online bots.");
                    return;
                }
                boolean dynamic = soloMapling.ArtificialPlayer.BotRoster.member(a.getId() - soloMapling.ArtificialPlayer.BotStore.BOT_ID_BASE) == null
                        || soloMapling.ArtificialPlayer.BotRoster.member(b.getId() - soloMapling.ArtificialPlayer.BotStore.BOT_ID_BASE) == null;
                BotCouples.Couple couple = BotCouples.form(a.getId(), b.getId(), dynamic, now);
                gm.yellowMessage(couple == null ? "One of them is already in a couple."
                        : "Couple #" + couple.id() + " formed" + (dynamic ? " (staged: on-demand bots are not stored)" : "") + ".");
            }
            case "wedding" -> {
                BotCouples.Couple couple = pick(gm, params);
                if (couple == null) {
                    gm.yellowMessage("No couple: give an id (!couple list) or !couple spawn first.");
                    return;
                }
                String problem = BotWeddings.start(couple, gm.getName());
                gm.yellowMessage(problem == null ? "The wedding is on in Amoria (map " + BotWeddings.AMORIA
                        + ", channel 1). The ceremony takes about two minutes." : "No wedding: " + problem);
            }
            case "note" -> {
                BotCouples.Couple couple = pick(gm, params);
                gm.yellowMessage(couple == null ? "No couple." : BotCouples.loveNoteNow(couple));
            }
            case "step" -> {
                BotCouples.stepNow();
                gm.yellowMessage("Ran the couples' step.");
            }
            case "break" -> {
                if (params.length < 2) {
                    gm.yellowMessage("Usage: !couple break <id>");
                    return;
                }
                gm.yellowMessage(BotCouples.breakUp(Long.parseLong(params[1])) ? "Broken up." : "No such couple.");
            }
            case "unstage" -> {
                int[] ids = staged.remove(gm.getId());
                if (ids == null) {
                    gm.yellowMessage("You have no staged couple.");
                    return;
                }
                CoupleStaging.unstage(ids);
                gm.yellowMessage("Removed your staged couple.");
            }
            default -> gm.yellowMessage("Usage: " + getDescription());
        }
    }

    private static BotCouples.Couple pick(Character gm, String[] params) {
        if (params.length > 1) {
            try {
                return BotCouples.byId(Long.parseLong(params[1]));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        int[] ids = staged.get(gm.getId());
        return ids == null ? null : BotCouples.coupleOf(ids[0]);
    }

    private static Character bot(String name) {
        World world = SoloMaplingUtilities.world();
        Character chr = world == null ? null : world.getPlayerStorage().getCharacterByName(name);
        return chr != null && BotHelpers.isBot(chr) ? chr : null;
    }
}
