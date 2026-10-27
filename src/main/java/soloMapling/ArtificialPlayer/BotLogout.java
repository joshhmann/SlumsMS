package soloMapling.ArtificialPlayer;

import client.Character;
import com.esotericsoftware.yamlbeans.YamlReader;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyCommands;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.server.ExecutorServiceManager;

import java.io.FileReader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * How a roster bot logs off (bot-persistence ticket 09): never in the middle of something a player is part of.
 * A bot that is free says bye if a real player can see it and goes. A bot holding a commitment - an open trade,
 * an armed invite, a conversation, a party with a player, someone it follows - waits for it to end, up to a cap;
 * at the cap it says bye, leaves the party, closes any trade window (both sides are refunded) and goes, so
 * nothing is left half-open.
 */
public final class BotLogout {

    private static final Logger log = LoggerFactory.getLogger(BotLogout.class);

    static final long WAIT_CAP_MS = TimeUnit.MINUTES.toMillis(15);
    private static final String LINES_FILE = "src/main/java/soloMapling/ArtificialPlayer/BotDialoguePack/LogoutDialogue.yaml";

    /** Bots that want to leave but are holding a commitment: char id -> the time they leave regardless. */
    private static final Map<Integer, Long> leaving = new ConcurrentHashMap<>();
    /** Bots between their goodbye and their removal: a second request in that beat must not log them out twice. */
    private static final java.util.Set<Integer> departing = ConcurrentHashMap.newKeySet();
    private static volatile Map<String, List<String>> lines;

    private BotLogout() {
    }

    /** Bots whose own reason to leave outranks a conversation (its own goodbye, a staged boss run). */
    private static final java.util.Set<Integer> ownReason = ConcurrentHashMap.newKeySet();

    /**
     * A bot's session is over: it leaves now if it is free, otherwise as soon as it is (or at the cap). 1.5.1 (owner
     * QA: bots "just zoomed away"): a player talking to it on its map counts as a commitment too - the director's
     * logout waits for the conversation hold to end instead of the bot vanishing mid-chat.
     */
    public static void request(int charId, long now) {
        request(charId, now, false);
    }

    /**
     * As {@link #request(int, long)} for a leave the bot itself announced (its own "gtg" line, a staged boss run):
     * a conversation does not keep it; the other commitments still do.
     */
    public static void requestOwn(int charId, long now) {
        request(charId, now, true);
    }

    private static void request(int charId, long now, boolean own) {
        BotSM bot = CharacterStorage.getBotById(charId);
        if (bot == null || bot.getChr() == null) {
            leaving.remove(charId);
            ownReason.remove(charId);
            return;
        }
        if (departing.contains(charId)) {
            return;
        }
        if (mayLeaveNow(bot, own)) {
            leaving.remove(charId);
            ownReason.remove(charId);
            leave(bot, false);
        } else {
            leaving.putIfAbsent(charId, now + WAIT_CAP_MS);
            if (own) {
                ownReason.add(charId);
            }
        }
    }

    /**
     * Free to go now: no commitment or errand ({@link BotPopulationDirector#mayEndSession(BotSM)}) and, unless the bot
     * announced the leave itself, no player talking to it ({@link BotConversation#keptByChat(Character)}).
     */
    static boolean mayLeaveNow(BotSM bot, boolean own) {
        return BotPopulationDirector.mayEndSession(bot)
                && !soloMapling.ArtificialPlayer.BotConvo.LfgParty.holds(bot.getChr().getId()) // 1.6.0: LFG PQ trip
                && (own || !BotConversation.keptByChat(bot.getChr()));
    }

    /** Whether this bot is waiting to leave, or already on its way out. */
    public static boolean isLeaving(int charId) {
        return leaving.containsKey(charId) || departing.contains(charId);
    }

    /** Whether this bot said its goodbye and is about to disappear (not merely waiting for a commitment to end). */
    public static boolean isDeparting(int charId) {
        return departing.contains(charId);
    }

    public static int waiting() {
        return leaving.size();
    }

    /** One director tick: bots whose commitment ended leave; bots past the cap leave regardless. */
    public static void process(long now) {
        for (Map.Entry<Integer, Long> entry : leaving.entrySet()) {
            int charId = entry.getKey();
            BotSM bot = CharacterStorage.getBotById(charId);
            if (bot == null || bot.getChr() == null) {
                leaving.remove(charId);
                ownReason.remove(charId);
            } else if (mayLeaveNow(bot, ownReason.contains(charId))) {
                leaving.remove(charId);
                ownReason.remove(charId);
                leave(bot, false);
            } else if (now >= entry.getValue()) {
                leaving.remove(charId);
                ownReason.remove(charId);
                leave(bot, true);
            }
        }
    }

    /**
     * Says bye (to a player who can see it), leaves its party, then logs out after a beat so the line is read
     * before the character disappears.
     */
    private static void leave(BotSM bot, boolean forced) {
        Character chr = bot.getChr();
        if (!departing.add(chr.getId())) {
            return;
        }
        boolean partiedWithPlayer = partiedWithPlayer(chr);
        boolean watched = partiedWithPlayer || bot.checkMainPlayersOnMap();
        boolean intoBoss = soloMapling.ArtificialPlayer.BotGuildSystem.GuildBossRuns.away(chr.getId() - BotStore.BOT_ID_BASE);
        if (watched && !intoBoss) { // a bot going into a staged boss run says nothing (its guild chat did)
            String line = line(partiedWithPlayer ? "Party" : "Alone");
            if (line != null) {
                SocialCommands.BotSpeak(chr, line);
            }
        }
        long beat = watched ? 1500 + ThreadLocalRandom.current().nextLong(1500) : 0;
        ExecutorServiceManager.getScheduledExecutorService().schedule(() -> ExecutorServiceManager.runAsync(() -> {
            long started = System.nanoTime();
            try {
                if (chr.getParty() != null) {
                    BotPartyCommands.botLeaveParty(chr);
                }
                if (forced) {
                    BotStore.closeOpenWindow(chr); // refunds both sides; removeBotFromServer would too
                }
                BotRoster.markSessionEnded(chr.getId());
                BotTypeManager.manuallyStopBot(chr);
                BotGeneration.removeBotFromServer(chr);
                soloMapling.ArtificialPlayer.GCMoveSystem.BotPerformanceMonitor.recordRosterLogout(System.nanoTime() - started);
            } catch (Exception e) {
                log.warn("Roster bot {} could not log out", chr.getName(), e);
            } finally {
                departing.remove(chr.getId());
            }
        }), beat, TimeUnit.MILLISECONDS);
    }

    static boolean partiedWithPlayer(Character chr) {
        Party party = chr == null ? null : chr.getParty();
        if (party == null) {
            return false;
        }
        for (PartyCharacter member : party.getMembers()) {
            if (member != null && !BotHelpers.isArtificial(member.getId())) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    static String line(String kind) {
        Map<String, List<String>> loaded = lines;
        if (loaded == null) {
            try (FileReader file = new FileReader(soloMapling.DataFiles.file(LINES_FILE))) {
                loaded = (Map<String, List<String>>) new YamlReader(file).read();
            } catch (Exception e) {
                log.warn("Could not read {}; bots log off silently", LINES_FILE, e);
                loaded = Map.of();
            }
            lines = loaded;
        }
        List<String> pool = loaded.get(kind);
        return pool == null || pool.isEmpty() ? null : pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }
}
