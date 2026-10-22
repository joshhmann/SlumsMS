package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds;
import soloMapling.ArtificialPlayer.BotGuildSystem.GuildBossRuns;
import soloMapling.ArtificialPlayer.BotGuildSystem.GuildRules;
import soloMapling.ArtificialPlayer.BotGuildSystem.GuildStaging;
import soloMapling.ArtificialPlayer.BotGuildSystem.GuildStore.GuildRow;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.server.ExecutorServiceManager;
import soloMapling.server.SoloMaplingUtilities;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 1.5.0 bot guilds, GM tools (GM 4).
 * <pre>
 * !botguild [list]                       every bot guild (vibe, members online, players, leader, rival), player guilds
 *                                        with bot members, and the boss run on now
 * !botguild info &lt;guild&gt;                 a guild's bot members
 * !botguild form &lt;name&gt; [vibe] [size]    found one more bot guild now from guildless roster bots (stored)
 * !botguild stage [n] [join] [neutral|rival] [guild]   n (2) bots next to you that count you as a friend (or
 *                                        neutral/rival); with join they are members of your guild (or the named bot
 *                                        guild) at once, else invite them yourself (/ginvite) and watch them answer
 * !botguild unstage                      remove the bots you staged
 * !botguild recruit &lt;guild&gt;              a recruit round now: megaphone (gate), out loud in Henesys/FM, whispers + invites to friends
 * !botguild jab &lt;guild&gt;                  a rivalry jab now: on a shared map, by megaphone, and a grind-map claim
 * !botguild bossrun &lt;guild&gt; [boss] [fast]  a staged boss run now (gpq|zakum|pap|horntail); fast = 60 s gather, 2 min inside
 * !botguild bossstop                     end the boss run now
 * !botguild quit &lt;bot&gt; [rivals|ignored]  the bot quits its guild now with that reason's line
 * !botguild join &lt;bot&gt; &lt;guild&gt;           an online bot joins a guild now
 * !botguild step                         run the guild step now (chatter, parties, recruiting, rivalry, quits)
 * </pre>
 */
public class BotGuildCommand extends Command {
    {
        setDescription("Bot guilds (GM): !botguild [list] | info <guild> | form <name> [vibe] [size] | stage [n] [join] [neutral|rival] [guild] | unstage | recruit <guild> | jab <guild> | bossrun <guild> [boss] [fast] | bossstop | quit <bot> [rivals|ignored] | join <bot> <guild> | step");
    }

    private static final Map<Integer, List<Integer>> staged = new ConcurrentHashMap<>();

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        ExecutorServiceManager.getExecutorService().execute(() -> {
            try {
                run(gm, params);
            } catch (RuntimeException e) {
                gm.yellowMessage("!botguild failed: " + e.getMessage());
            }
        });
    }

    private void run(Character gm, String[] params) {
        String sub = params.length == 0 ? "list" : params[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> BotGuilds.describe().forEach(gm::yellowMessage);
            case "info" -> {
                GuildRow g = guild(gm, params, 1);
                if (g != null) {
                    gm.yellowMessage(g.name() + " (" + g.vibe().label() + "):");
                    BotGuilds.describeGuild(g.guildId()).forEach(gm::yellowMessage);
                }
            }
            case "form" -> {
                if (params.length < 2) {
                    gm.yellowMessage("Usage: !botguild form <name> [casual|hardcore|merchant|social|newbie|pvm] [size]");
                    return;
                }
                GuildRules.Vibe vibe = params.length > 2 ? GuildRules.Vibe.parse(params[2]) : GuildRules.Vibe.NEWBIE;
                int size = params.length > 3 ? parse(params[3], 8) : 8;
                gm.yellowMessage(BotGuilds.formNow(params[1], vibe, size, System.currentTimeMillis()));
            }
            case "stage" -> stage(gm, params);
            case "unstage" -> {
                List<Integer> ids = staged.remove(gm.getId());
                if (ids == null) {
                    gm.yellowMessage("You staged no bots.");
                    return;
                }
                ids.forEach(GuildStaging::unstage);
                gm.yellowMessage("Removed " + ids.size() + " staged bot(s).");
            }
            case "recruit" -> {
                GuildRow g = guild(gm, params, 1);
                if (g != null) {
                    BotGuilds.recruitNow(g).forEach(gm::yellowMessage);
                }
            }
            case "jab" -> {
                GuildRow g = guild(gm, params, 1);
                if (g != null) {
                    BotGuilds.jabNow(g).forEach(gm::yellowMessage);
                }
            }
            case "bossrun" -> {
                GuildRow g = guild(gm, params, 1);
                if (g == null) {
                    return;
                }
                GuildRules.Boss boss = null;
                boolean fast = false;
                for (int i = 2; i < params.length; i++) {
                    if ("fast".equalsIgnoreCase(params[i])) {
                        fast = true;
                    } else if (GuildRules.boss(params[i]) != null) {
                        boss = GuildRules.boss(params[i]);
                    }
                }
                String problem = GuildBossRuns.start(g, boss, fast, gm.getName(), gm.getClient().getChannel());
                gm.yellowMessage(problem == null ? "Boss run on: " + GuildBossRuns.running() + " (see !botguild list)"
                        : "No boss run: " + problem);
            }
            case "bossstop" -> gm.yellowMessage("Stopped " + GuildBossRuns.stop(gm.getName()) + " run(s).");
            case "quit" -> {
                if (params.length < 2) {
                    gm.yellowMessage("Usage: !botguild quit <bot> [rivals|ignored]");
                    return;
                }
                Character bot = bot(params[1]);
                if (bot == null) {
                    gm.yellowMessage("No online bot called " + params[1] + ".");
                    return;
                }
                GuildRules.QuitReason why = params.length > 2 && params[2].toLowerCase(Locale.ROOT).startsWith("ign")
                        ? GuildRules.QuitReason.IGNORED : GuildRules.QuitReason.RIVALS;
                gm.yellowMessage(BotGuilds.quit(bot.getId(), why, gm.getName()));
            }
            case "join" -> {
                if (params.length < 3) {
                    gm.yellowMessage("Usage: !botguild join <bot> <guild>");
                    return;
                }
                Character bot = bot(params[1]);
                GuildRow g = guild(gm, params, 2);
                if (bot == null || g == null) {
                    gm.yellowMessage("Need an online bot and a bot guild.");
                    return;
                }
                gm.yellowMessage(BotGuilds.join(bot, g.guildId(), 5, System.currentTimeMillis())
                        ? bot.getName() + " joined " + g.name() + "." : bot.getName() + " could not join (in a guild already, or full).");
            }
            case "step" -> {
                BotGuilds.stepNow();
                gm.yellowMessage("Ran the guild step.");
            }
            default -> gm.yellowMessage("Usage: " + getDescription());
        }
    }

    private static void stage(Character gm, String[] params) {
        int n = 2;
        boolean join = false;
        AffinityState state = AffinityState.FRIEND;
        GuildRow target = null;
        for (int i = 1; i < params.length; i++) {
            String p = params[i].toLowerCase(Locale.ROOT);
            if (p.matches("\\d+")) {
                n = Math.max(1, Math.min(6, Integer.parseInt(p)));
            } else if ("join".equals(p)) {
                join = true;
            } else if (AffinityState.parse(p) != null) {
                state = AffinityState.parse(p);
            } else if (BotGuilds.byName(p) != null) {
                target = BotGuilds.byName(p);
                join = true;
            }
        }
        int guildId = target != null ? target.guildId() : join ? gm.getGuildId() : 0;
        if (join && guildId <= 0) {
            gm.yellowMessage("You are in no guild: create one at the Guild HQ, or name a bot guild.");
            return;
        }
        List<Integer> mine = staged.computeIfAbsent(gm.getId(), k -> new CopyOnWriteArrayList<>());
        int made = 0;
        for (int i = 0; i < n; i++) {
            int id = GuildStaging.stage(gm, state, guildId, 5);
            if (id > 0) {
                mine.add(id);
                made++;
            }
        }
        gm.yellowMessage("Staged " + made + " " + state.label() + " bot(s)" + (guildId > 0 ? " in guild " + guildId
                : "; invite them with /ginvite <name> (friends say yes after a few seconds)") + ". !botguild unstage removes them.");
    }

    private static GuildRow guild(Character gm, String[] params, int index) {
        if (params.length <= index) {
            gm.yellowMessage("Name a bot guild (!botguild list).");
            return null;
        }
        GuildRow g = BotGuilds.byName(params[index]);
        if (g == null) {
            gm.yellowMessage("No bot guild called " + params[index] + ".");
        }
        return g;
    }

    private static Character bot(String name) {
        Character c = SoloMaplingUtilities.world().getPlayerStorage().getCharacterByName(name);
        return c != null && BotHelpers.isBot(c) ? c : null;
    }

    private static int parse(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
