package soloMapling.ArtificialPlayer.BotDramaSystem;

import client.Character;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.server.SoloMaplingUtilities;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * The GM staging commands of the drama package ({@code !botromance}, {@code !botrival}, {@code !botwar}) and the
 * {@code !qa} scenarios share this: every action takes the GM and its words and returns what happened as lines. Bots
 * it stages are on-demand bots (memory only) remembered per GM and taken away by {@code unstage} (or the scenario's
 * stop).
 */
public final class DramaGm {

    private static final Map<Integer, List<Integer>> staged = new ConcurrentHashMap<>();

    private DramaGm() {
    }

    /** Runs one action of an area (romance | rival | war). */
    public static List<String> run(Character gm, String area, String[] words) {
        String action = words.length == 0 ? "status" : words[0].toLowerCase(java.util.Locale.ROOT);
        String[] args = words.length <= 1 ? new String[0] : java.util.Arrays.copyOfRange(words, 1, words.length);
        return switch (area) {
            case "romance" -> romance(gm, action, args);
            case "rival" -> rival(gm, action, args);
            default -> List.of("unknown drama area " + area);
        };
    }

    // ── staging bookkeeping ──

    static int stage(Character gm, AffinityState state, boolean training, int level, int offset) {
        int id = DramaStaging.stage(gm, state, training, level, offset);
        if (id > 0) {
            staged.computeIfAbsent(gm.getId(), k -> new CopyOnWriteArrayList<>()).add(id);
        }
        return id;
    }

    /** The bots this GM staged, oldest first. */
    public static List<Integer> stagedBy(int gmId) {
        return staged.getOrDefault(gmId, List.of());
    }

    /** Takes away every bot the GM staged. How many. */
    public static int unstageAll(int gmId) {
        List<Integer> ids = staged.remove(gmId);
        if (ids == null) {
            return 0;
        }
        Character gm = DramaKit.onlinePlayer(gmId);
        if (gm == null) {
            gm = byId(gmId);
        }
        for (int id : ids) {
            if (gm != null) {
                BotRomance.undoStock(gm, id); // a staged bot never stays anyone's fiance or spouse
            }
            DramaStaging.unstage(id);
        }
        return ids.size();
    }

    /** The bot an action is about: a name given, else the GM's latest staged bot (index from the end). */
    static Character bot(Character gm, String[] args, int argIndex, int fromEnd) {
        if (args.length > argIndex && !args[argIndex].matches("\\d+")) {
            Character byName = byName(args[argIndex]);
            if (byName != null && BotHelpers.isBot(byName)) {
                return byName;
            }
        }
        List<Integer> mine = stagedBy(gm.getId());
        if (mine.size() > fromEnd) {
            return DramaKit.liveBot(mine.get(mine.size() - 1 - fromEnd));
        }
        return null;
    }

    static Character byId(int id) {
        net.server.world.World w = SoloMaplingUtilities.world();
        return w == null ? null : w.getPlayerStorage().getCharacterById(id);
    }

    static Character byName(String name) {
        net.server.world.World w = SoloMaplingUtilities.world();
        return w == null ? null : w.getPlayerStorage().getCharacterByName(name);
    }

    static int intArg(String[] args, int fallback) {
        for (String a : args) {
            if (a.matches("\\d{1,4}")) {
                return Integer.parseInt(a);
            }
        }
        return fallback;
    }

    static boolean has(String[] args, String word) {
        for (String a : args) {
            if (a.equalsIgnoreCase(word)) {
                return true;
            }
        }
        return false;
    }

    // ── romance ──

    static List<String> romance(Character gm, String action, String[] args) {
        List<String> out = new ArrayList<>();
        switch (action) {
            case "status" -> {
                Character who = args.length > 0 ? byName(args[0]) : gm;
                out.addAll(BotRomance.describe(who == null ? gm.getId() : who.getId()));
                out.add(RomanceWedding.describe());
            }
            case "on", "off" -> {
                out.addAll(BotRomance.setRomance(gm, "on".equals(action)));
                out.add("@romance " + action + " for " + gm.getName());
            }
            case "stage" -> {
                String kind = args.length > 0 ? args[0].toLowerCase() : "crush";
                int level = intArg(args, "crush".equals(kind) ? 0 : 60);
                if (!BotRomance.romanceOn(gm.getId())) {
                    BotRomance.setRomance(gm, true);
                    out.add("turned @romance on for you (staging needs it)");
                }
                int offset = 70 + 50 * stagedBy(gm.getId()).size();
                int id = stage(gm, AffinityState.FRIEND, false, level, offset);
                Character bot = id > 0 ? DramaKit.liveBot(id) : null;
                if (bot == null) {
                    out.add("no bot could be staged here");
                    return out;
                }
                switch (kind) {
                    case "dating", "partner", "second" -> {
                        BotRomance.startDating(bot, gm, true);
                        out.add(bot.getName() + " (lv " + bot.getLevel() + ") is dating you now (staged)");
                    }
                    case "ex" -> {
                        BotRomance.startDating(bot, gm, true);
                        Romance r = BotRomance.with(gm.getId(), bot.getId());
                        BotRomance.end(r, BotRomance.EndReason.GM, false);
                        BotAffinityBridge.friend(bot, gm);
                        out.add(bot.getName() + " is your ex now (staged, quietly)");
                    }
                    default -> out.add(bot.getName() + " (lv " + bot.getLevel() + ") has a crush on you: stand near it");
                }
            }
            case "ask" -> {
                Character bot = bot(gm, args, 0, 0);
                out.add(bot == null ? "no bot" : BotRomance.askOut(bot, gm));
            }
            case "propose" -> {
                Character bot = bot(gm, args, 0, 0);
                out.add(bot == null ? "no bot" : BotRomance.botProposes(bot, gm));
            }
            case "follow" -> {
                Character bot = bot(gm, args, 0, 0);
                DramaKit.clearCooldowns("rm:follow:" + gm.getId());
                out.add(bot == null ? "no bot" : "change map now: " + bot.getName() + " follows within a few seconds"
                        + " (a QA run makes it every time, live 35% at most every 15 min)");
            }
            case "whisper" -> out.add(BotRomance.whisperNow(gm));
            case "jealous" -> {
                int id = stage(gm, AffinityState.RIVAL, false, 0, -90);
                DramaKit.clearCooldowns("rm:jealous");
                BotRomance.stepNow();
                out.add(id > 0 ? "a rival of yours (" + BotRomance.nameOf(id) + ") stands here: your partner gets jealous"
                        : "no rival could be staged");
            }
            case "confront" -> out.add(BotRomance.confrontNow(gm));
            case "neglect" -> {
                Character bot = bot(gm, args, 0, 0);
                int days = intArg(args, 5);
                if (bot == null || !BotRomance.backdate(gm.getId(), bot.getId(), TimeUnit.DAYS.toMillis(days))) {
                    out.add("no romance with that bot");
                    return out;
                }
                BotRomance.stepNow();
                out.add("moved " + bot.getName() + "'s romance " + days + " days back: a warning whisper now; "
                        + "!botromance breakup ends it with the full drama (live: 30 min after the warning)");
            }
            case "breakup" -> {
                Character bot = bot(gm, args, 0, 0);
                out.add(bot == null ? "no bot" : BotRomance.breakUpNow(gm.getId(), bot.getId(),
                        BotRomance.EndReason.NEGLECT));
            }
            case "flame" -> {
                Character bot = bot(gm, args, 0, 0);
                if (bot == null) {
                    out.add("no bot");
                    return out;
                }
                BotRomance.backdate(gm.getId(), bot.getId(), RomanceRules.flameMs() + TimeUnit.DAYS.toMillis(1));
                out.add(BotRomance.flameNow(gm, bot));
            }
            case "wedding" -> {
                if (gm.getMapId() != RomanceWedding.AMORIA) {
                    gm.changeMap(RomanceWedding.AMORIA);
                    out.add("warped you to Amoria: run it again in a second");
                    return out;
                }
                String problem = RomanceWedding.start(gm);
                out.add(problem == null ? "the wedding is on (about a minute and a half)" : "no wedding: " + problem);
            }
            case "object" -> {
                RomanceWedding.force(RomanceWedding.Drama.OBJECTION);
                out.add("the next wedding gets an objection");
            }
            case "runaway" -> {
                RomanceWedding.force(RomanceWedding.Drama.RUNAWAY);
                out.add("the next wedding: the bot runs from the altar");
            }
            case "crash" -> {
                RomanceWedding.force(RomanceWedding.Drama.CRASH);
                out.add("the next wedding: an ex or rival crashes the reception");
            }
            case "stopwedding" -> out.add("stopped " + RomanceWedding.stop() + " wedding(s)");
            case "divorce" -> out.add(BotRomance.divorce(gm));
            case "gossip" -> {
                DramaKit.clearCooldowns("rm:gossip");
                BotRomance.stepNow();
                out.add("gossip step ran (needs your partner and another bot on your map)");
            }
            case "step" -> {
                BotRomance.stepNow();
                out.add("romance step ran");
            }
            case "unstage" -> out.add("removed " + unstageAll(gm.getId()) + " staged bot(s)");
            default -> out.add("!botromance status [player] | on | off | stage <crush|dating|ex> [level] | ask | propose"
                    + " | follow | whisper | jealous | confront | neglect [days] | breakup | flame | wedding | object |"
                    + " runaway | crash | stopwedding | divorce | gossip | step | unstage");
        }
        return out;
    }

    // ── rivals ──

    static List<String> rival(Character gm, String action, String[] args) {
        List<String> out = new ArrayList<>();
        switch (action) {
            case "status" -> {
                Character who = args.length > 0 ? byName(args[0]) : gm;
                out.addAll(BotRivalry.describe(who == null ? gm.getId() : who.getId()));
            }
            case "stage" -> {
                AffinityState state = has(args, "nemesis") ? AffinityState.NEMESIS : AffinityState.RIVAL;
                boolean training = !has(args, "social");
                int id = stage(gm, state, training, 0, 80 + 40 * stagedBy(gm.getId()).size());
                out.add(id > 0 ? BotRomance.nameOf(id) + " is your " + state.label() + (training
                        ? " (a training bot held on this map)" : " (a social bot)") : "no bot could be staged here");
            }
            case "roast" -> {
                Character bot = bot(gm, args, 0, 0);
                if (bot == null) {
                    out.add("no bot");
                    return out;
                }
                AffinityState st = soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity.state(bot.getId(), gm.getId());
                out.add("facts: " + RivalRoasts.facts(gm, soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity
                        .hostileCount(bot.getId(), gm.getId()), BotRivalry.deathsToday(gm.getId())).describe());
                out.add(BotRivalry.roast(bot, gm, st.hostile() ? st : AffinityState.RIVAL));
            }
            case "kswar" -> {
                Character bot = bot(gm, args, 0, 0);
                out.add(bot == null ? "no bot" : BotRivalry.startKsWar(bot, gm, intArg(args, 3), true));
            }
            case "endwar" -> out.add(BotRivalry.endWarNow(gm.getId()));
            case "callout" -> {
                Character bot = bot(gm, args, 0, 0);
                out.add(bot == null ? "no bot" : BotRivalry.callout(bot, gm, true));
            }
            case "revenge" -> {
                Character bot = bot(gm, args, 0, 0);
                out.add(bot == null ? "no bot" : BotRivalry.armNow(bot, gm));
            }
            case "die" -> {
                BotRivalry.onDeath(gm);
                out.add("counted a death: " + BotRivalry.deathsToday(gm.getId()) + " today");
            }
            case "unstage" -> out.add("removed " + unstageAll(gm.getId()) + " staged bot(s)");
            default -> out.add("!botrival status [player] | stage <rival|nemesis> [social] | roast | kswar [2-4] |"
                    + " endwar | callout | revenge | die | unstage");
        }
        return out;
    }

    /** Small bridge so staging can put feelings back after a quiet staged breakup. */
    static final class BotAffinityBridge {
        static void friend(Character bot, Character gm) {
            soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity.stage(bot.getId(), gm.getId(),
                    AffinityState.FRIEND);
        }
    }
}
