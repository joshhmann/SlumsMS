package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import net.server.world.World;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityReactions;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityStaging;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotBuddies;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.server.ExecutorServiceManager;
import soloMapling.server.SoloMaplingUtilities;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ticket 58 GM tools (GM 4). Affinity is never shown to players; this is the only place it is visible.
 * <pre>
 * !affinity [bot] [player]                 how a bot feels about a player (defaults: the nearest bot, you)
 * !affinity list [player]                  every bot with feelings about a player, strongest first
 * !affinity set &lt;bot&gt; &lt;friend|rival|nemesis|neutral&gt; [player]   put a pair in a state now
 * !affinity grudge &lt;bot&gt; [player]          a fresh grudge (at least rival) and a KS spree if it is fighting
 * !affinity stage &lt;friend|rival|nemesis&gt; [social|training]   a new bot next to you that feels that way about you
 * !affinity unstage                        removes the bots you staged and forgets their ties
 * </pre>
 */
public class AffinityCommand extends Command {
    {
        setDescription("Bot affinity (GM): !affinity [bot] [player] | list [player] | set <bot> <state> [player] | grudge <bot> [player] | stage <state> [social|training] | unstage");
    }

    private static final Map<Integer, List<Integer>> staged = new ConcurrentHashMap<>();

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        ExecutorServiceManager.getExecutorService().execute(() -> {
            try {
                run(gm, params);
            } catch (RuntimeException e) {
                gm.yellowMessage("!affinity failed: " + e.getMessage());
            }
        });
    }

    private void run(Character gm, String[] params) {
        String sub = params.length == 0 ? "" : params[0].toLowerCase();
        switch (sub) {
            case "list" -> {
                Character player = params.length > 1 ? player(params[1]) : gm;
                if (player == null) {
                    gm.yellowMessage("No online player " + params[1] + ".");
                    return;
                }
                gm.yellowMessage("Bots about " + player.getName() + ":");
                BotAffinity.describeAll(player.getId(), id -> {
                    String n = BotBuddies.nameOf(id);
                    return n == null ? "#" + id : n;
                }).forEach(gm::yellowMessage);
            }
            case "set" -> {
                if (params.length < 3) {
                    gm.yellowMessage("Usage: !affinity set <bot> <friend|rival|nemesis|neutral> [player]");
                    return;
                }
                Character bot = bot(gm, params[1]);
                AffinityState state = AffinityState.parse(params[2]);
                Character player = params.length > 3 ? player(params[3]) : gm;
                if (bot == null || state == null || player == null) {
                    gm.yellowMessage("Need an online bot, a state (friend|rival|nemesis|neutral) and an online player.");
                    return;
                }
                boolean ok = BotAffinity.stage(bot.getId(), player.getId(), state);
                gm.yellowMessage(ok ? bot.getName() + " -> " + player.getName() + ": "
                        + BotAffinity.describe(bot.getId(), player.getId()) : "Could not set it (player not loaded).");
            }
            case "grudge" -> {
                Character bot = params.length > 1 ? bot(gm, params[1]) : nearestBot(gm);
                Character player = params.length > 2 ? player(params[2]) : gm;
                if (bot == null || player == null) {
                    gm.yellowMessage("Need an online bot and an online player.");
                    return;
                }
                if (!BotAffinity.forceGrudge(bot.getId(), player.getId())) {
                    gm.yellowMessage("No grudge: the player is not loaded or has @botdrama off.");
                    return;
                }
                if (bot.getMapId() == player.getMapId()) {
                    AffinityReactions.forceSpree(bot, player);
                }
                gm.yellowMessage(bot.getName() + " -> " + player.getName() + ": "
                        + BotAffinity.describe(bot.getId(), player.getId()));
            }
            case "stage" -> {
                AffinityState state = params.length > 1 ? AffinityState.parse(params[1]) : null;
                if (state == null || state == AffinityState.NEUTRAL) {
                    gm.yellowMessage("Usage: !affinity stage <friend|rival|nemesis> [social|training]");
                    return;
                }
                boolean training = params.length > 2 ? params[2].toLowerCase().startsWith("train") : state.hostile();
                int id = AffinityStaging.stage(gm, state, training);
                if (id < 0) {
                    gm.yellowMessage("No bot could be made here.");
                    return;
                }
                staged.computeIfAbsent(gm.getId(), k -> new ArrayList<>()).add(id);
                String name = BotBuddies.nameOf(id);
                gm.yellowMessage("Staged " + name + " (" + (training ? "training, held on this map" : "social")
                        + ") as your " + state.label() + ". !affinity unstage removes it.");
            }
            case "unstage" -> {
                List<Integer> ids = staged.remove(gm.getId());
                if (ids == null || ids.isEmpty()) {
                    gm.yellowMessage("You have no staged bots.");
                    return;
                }
                ids.forEach(AffinityStaging::unstage);
                gm.yellowMessage("Removed " + ids.size() + " staged bot(s).");
            }
            default -> {
                Character bot = params.length > 0 ? bot(gm, params[0]) : nearestBot(gm);
                Character player = params.length > 1 ? player(params[1]) : gm;
                if (bot == null || player == null) {
                    gm.yellowMessage("Usage: " + getDescription());
                    return;
                }
                gm.yellowMessage(bot.getName() + " -> " + player.getName() + ": "
                        + BotAffinity.describe(bot.getId(), player.getId()));
                String spree = AffinityReactions.spreeOf(bot.getId());
                if (spree != null) {
                    gm.yellowMessage(bot.getName() + " is " + spree + ".");
                }
            }
        }
    }

    private static Character online(String name) {
        World world = SoloMaplingUtilities.world();
        return world == null ? null : world.getPlayerStorage().getCharacterByName(name);
    }

    private static Character player(String name) {
        Character chr = online(name);
        return chr == null || BotHelpers.isBot(chr) ? null : chr;
    }

    private static Character bot(Character gm, String name) {
        Character chr = online(name);
        return chr != null && BotHelpers.isBot(chr) ? chr : null;
    }

    private static Character nearestBot(Character gm) {
        Character best = null;
        double bestSq = Double.MAX_VALUE;
        for (Character c : gm.getMap().getAllPlayers()) {
            if (BotHelpers.isBot(c)) {
                double d = c.getPosition().distanceSq(gm.getPosition());
                if (d < bestSq) {
                    bestSq = d;
                    best = c;
                }
            }
        }
        return best;
    }
}
