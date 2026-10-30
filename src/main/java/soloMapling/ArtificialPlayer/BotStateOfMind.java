package soloMapling.ArtificialPlayer;

import client.Character;
import constants.game.GameConstants;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrain;
import soloMapling.ArtificialPlayer.BotConvo.ConvoEntities;
import soloMapling.ArtificialPlayer.BotConvo.StateOfMind;
import soloMapling.ArtificialPlayer.BotConvo.TrainingTips;
import soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Ticket 156: builds a {@link StateOfMind.Snapshot} from the live game for one reply - the bot's map, region and the
 * monsters on it, its plan step and destination ({@code !botwhy}), its training spots, the player (met or not, the
 * notes, their real level and job), the chat so far and an open quote. Built only when a reply is made (no tick
 * work); every part is guarded, so a missing piece leaves it out instead of failing the reply.
 */
final class BotStateOfMind {

    private static final int MOBS = 6;

    static {
        StateOfMind.useRegions(MapMobIndex::returnMapId);
    }

    private BotStateOfMind() {
    }

    /**
     * The snapshot for {@code bot} answering {@code player}. {@code history}: the chat's turns (oldest first);
     * {@code spoken}: the bot already said something in this chat; {@code offer}: an open trade offer or null;
     * {@code fact}: the game's decided answer (its places count as known); {@code worldTalk}: the line asks about the
     * world, so the player's level's training spots are known too.
     */
    static StateOfMind.Snapshot capture(BotSM sm, Character player, List<BotBrain.Turn> history, boolean spoken,
                                        StateOfMind.Offer offer, String fact, boolean worldTalk) {
        long t0 = System.nanoTime();
        Character bot = sm.getChr();
        StateOfMind.Builder b = StateOfMind.builder();
        b.bot(bot.getName(), bot.getLevel(), job(bot));
        int mapId = bot.getMapId();
        if (safe(bot::getMap, null) != null) { // a bot always stands on a map; a staged one without it knows no place
            b.here(new StateOfMind.Place(mapId, mapName(bot), region(mapId)), mobsOn(bot));
        }
        BotRoster.Activity doing = safe(sm::activitySnapshot, null);
        String activity = BotChatPrompt.activityPhrase(doing == null ? null : doing.name());
        String step = null;
        if (sm instanceof soloMapling.ArtificialPlayer.BotTypes.TrainingBot training
                && soloMapling.ArtificialPlayer.BotLifeSystem.LifeRuntime.enabled()) {
            try {
                var life = training.lifeRuntime();
                var next = life == null ? null : life.planNext();
                if (next != null && next.step() != null) {
                    step = next.step().describe(next.plan().target());
                }
                int dest = life == null ? 0 : life.planDestination();
                if (dest > 0 && dest != mapId) {
                    b.heading(new StateOfMind.Place(dest, nameOf(dest), region(dest)), spawnNames(dest));
                }
                if (life != null) {
                    for (var g : life.memory().goals()) {
                        b.goal(g.description());
                    }
                }
            } catch (RuntimeException | LinkageError e) {
                // no plan: the snapshot goes without it
            }
        }
        int train = doing == null ? -1 : safe(() -> doing.intState(BotRoster.TRAIN_STATE, -1), -1);
        b.doing(activity, step);
        if (train > 0 && train != mapId) {
            b.knows(new StateOfMind.Place(train, nameOf(train), region(train)), spawnNames(train));
        }
        int jobId = safe(() -> bot.getJob().getId(), 0);
        for (TrainingTips.Spot spot : safe(() -> TrainingTips.spotsFor(bot.getLevel(), jobId, 2),
                List.<TrainingTips.Spot>of())) {
            b.knows(new StateOfMind.Place(spot.mapId(), spot.name(), region(spot.mapId())), spawnNames(spot.mapId()));
        }
        if (worldTalk && player != null) {
            int pJob = safe(() -> player.getJob().getId(), 0);
            for (TrainingTips.Spot spot : safe(() -> TrainingTips.spotsFor(player.getLevel(), pJob, 2),
                    List.<TrainingTips.Spot>of())) {
                b.knows(new StateOfMind.Place(spot.mapId(), spot.name(), region(spot.mapId())),
                        spawnNames(spot.mapId()));
            }
        }
        for (ConvoEntities.Entity e : safe(() -> ConvoEntities.find(fact).all(), List.<ConvoEntities.Entity>of())) {
            if (e.kind() == ConvoEntities.Kind.MAP) {
                b.knows(new StateOfMind.Place(e.id(), e.name(), region(e.id())), spawnNames(e.id()));
            } else if (e.kind() == ConvoEntities.Kind.MOB) {
                b.knows(null, List.of(e.name()));
            }
        }
        if (player != null) {
            boolean isBot = BotHelpers.isBot(player);
            b.player(player.getName(), player.getLevel(), job(player), isBot);
            int slot = bot.getId() - BotStore.BOT_ID_BASE;
            List<String> notes = new ArrayList<>();
            boolean known = spoken;
            try {
                for (BotMemory.Note n : BotMemory.about(slot, player.getId())) {
                    if (n.source() == BotMemory.Source.DIRECT) {
                        known = true;
                        notes.add(n.text());
                    }
                }
            } catch (RuntimeException | LinkageError e) {
                // memory unreadable: a stranger, unless the chat says otherwise
            }
            try {
                known |= soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity.friend(bot.getId(), player.getId())
                        || soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity.hostile(bot.getId(),
                        player.getId());
            } catch (RuntimeException | LinkageError e) {
                // no affinity
            }
            if (history != null) {
                int playerTurns = 0;
                for (BotBrain.Turn t : history) {
                    if (t.role() == BotBrain.Role.USER) {
                        playerTurns++;
                    }
                }
                known |= playerTurns > 1; // they have been talking in this chat already
            }
            b.met(known, notes.size() > 3 ? notes.subList(notes.size() - 3, notes.size()) : notes);
        }
        if (history != null) {
            for (BotBrain.Turn t : history) {
                if (t.role() != BotBrain.Role.SYSTEM) {
                    b.line(t.content(), t.role() == BotBrain.Role.USER);
                }
            }
        }
        b.offer(offer);
        StateOfMind.Snapshot s = b.build();
        StateOfMind.snapshotBuilt(System.nanoTime() - t0);
        return s;
    }

    /** An open offer: the player's line answers a quote this bot gave them (an amount or a "no"). */
    static StateOfMind.Offer offer(Character bot, Character player, String line) {
        if (bot == null || player == null || line == null) {
            return null;
        }
        try {
            soloMapling.Economy.BotDirectTrade.OpenQuote q = soloMapling.Economy.BotDirectTrade.openQuote(bot.getId(),
                    player.getId());
            if (q == null) {
                return null;
            }
            soloMapling.Economy.HaggleBrain.Read read = soloMapling.Economy.HaggleBrain.rules(line);
            return switch (read.move()) {
                case OFFER -> haggleWords(line) ? new StateOfMind.Offer(q.item(), q.price(), read.price()) : null;
                case REFUSE -> new StateOfMind.Offer(q.item(), q.price(), -1);
                default -> null;
            };
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static final java.util.regex.Pattern HAGGLE_WORDS = java.util.regex.Pattern.compile(
            "\\b(do|for|how about|hbu|offer|take|give|pay|would|will|can|could|lower|deal|sell|buy|price|accept|ok|"
            + "c/o|max|best|cheaper|than|instead)\\b");

    /** "would u do 300k", "300k?", "9k for it": a price offered, not "lol im 300k exp away". */
    static boolean haggleWords(String line) {
        String text = line.toLowerCase(Locale.ROOT).trim();
        return text.split("\\s+").length <= 3 || HAGGLE_WORDS.matcher(text).find();
    }

    private static String job(Character c) {
        try {
            String name = GameConstants.getJobName(c.getJob().getId());
            return name == null || name.isBlank() ? "beginner" : name.toLowerCase(Locale.ROOT);
        } catch (RuntimeException | LinkageError e) {
            return "beginner";
        }
    }

    private static String mapName(Character bot) {
        try {
            return bot.getMap() == null ? nameOf(bot.getMapId()) : bot.getMap().getMapName();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static String nameOf(int mapId) {
        try {
            String place = server.maps.MapFactory.loadPlaceName(mapId);
            String street = server.maps.MapFactory.loadStreetName(mapId);
            String name = place == null || place.isBlank() ? street : place;
            return name == null || name.isBlank() ? null : name;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static int region(int mapId) {
        try {
            int r = MapMobIndex.returnMapId(mapId);
            return r == 999_999_999 ? mapId : r;
        } catch (RuntimeException | LinkageError e) {
            return -1;
        }
    }

    /** The monsters on the bot's map: the ones alive now, then the map's spawn list. */
    private static List<String> mobsOn(Character bot) {
        Set<String> names = new LinkedHashSet<>();
        try {
            if (bot.getMap() != null) {
                for (server.life.Monster m : bot.getMap().getAllMonsters()) {
                    if (m != null && m.getName() != null && !m.getName().isBlank()) {
                        names.add(m.getName());
                    }
                    if (names.size() >= MOBS) {
                        break;
                    }
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // no live monsters
        }
        names.addAll(spawnNames(bot.getMapId()));
        return new ArrayList<>(names).subList(0, Math.min(MOBS, names.size()));
    }

    /** The names of the monsters a map spawns (String.wz, cached by the providers). */
    private static List<String> spawnNames(int mapId) {
        List<String> out = new ArrayList<>();
        try {
            for (int id : new LinkedHashSet<>(MapMobIndex.mobIds(mapId))) {
                String n = server.life.MonsterInformationProvider.getInstance().getMobNameFromId(id);
                if (n != null && !n.isBlank() && !out.contains(n)) {
                    out.add(n);
                }
                if (out.size() >= MOBS) {
                    break;
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // no WZ: nothing
        }
        return out;
    }

    private static <T> T safe(java.util.function.Supplier<T> f, T fallback) {
        try {
            T v = f.get();
            return v == null ? fallback : v;
        } catch (RuntimeException | LinkageError e) {
            return fallback;
        }
    }
}
