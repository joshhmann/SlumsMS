package soloMapling.ArtificialPlayer;

import client.Character;
import net.server.Server;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands;
import soloMapling.ArtificialPlayer.BotTownSystem.TownPresenceConfig;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.server.BotShutdown;
import soloMapling.server.ExecutorServiceManager;
import soloMapling.server.MethodScheduler;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotMegaphoneGate;
import soloMapling.ArtificialPlayer.BotMegaphoneSystem.WorldEventMegaphones;

import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static soloMapling.ArtificialPlayer.BotCommandsPack.MegaphoneCommands.BotAvatarMegaphone;
import static soloMapling.ArtificialPlayer.BotCommandsPack.MegaphoneCommands.BotSuperMegaphone;
import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.*;
import static soloMapling.ArtificialPlayer.BotCustomization.getRandomChairId;
import static soloMapling.ArtificialPlayer.BotHelpers.isBot;
import static soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.*;
import static soloMapling.BotLogger.log;

public class SocialHotPotatoManager {

    private static SocialHotPotatoManager instance;
    private ScheduledFuture<?> scheduledTask;
    private ScheduledFuture<?> megaScheduledTask;
    private final Random random = new Random();
    private boolean running = false;

    private static final int MIN_INTERVAL_MS = 8_000;
    private static final int MAX_INTERVAL_MS = 20_000;

    // Slower than the old 30-90 s: BotMegaphoneGate caps every bot megaphone server-wide on top of this.
    private static final int MEGA_MIN_INTERVAL_MS = 120_000;
    private static final int MEGA_MAX_INTERVAL_MS = 360_000;

    private static final int[] HENESYS_MAP_IDS = {
            100000000,  // Henesys
            100000100,  // Henesys Market
            100000200,  // Henesys Park
            100000102   // Henesys Potion Shop
    };

    // Bark map scope: the four Henesys maps plus every town map in TownPresence.yaml. Built once
    // (start / refreshMapScope), rebuilt live on !env townpresence reload. The megaphone ticker is
    // deliberately NOT scoped per town - megaphones are server-wide, so it stays a single ticker drawing
    // from this same pool (never fanned out) to keep the global megaphone rate unchanged.
    private volatile Set<Integer> ambientMapIds = buildAmbientMapIds();

    private static final String SOCIAL_DIALOGUE_PATH = "SocialHotPotatoDialogue.yaml";
    private static final String SOCIAL_BOT_TYPE = "SocialHotPotato";
    private static final String MEGA_DIALOGUE_PATH = "MegaphoneDialogue.yaml";
    private static final String MEGA_BOT_TYPE = "MegaphoneBroadcast";

    private static final String[] SOCIAL_CATEGORIES = {
            "RealLife", "MapleInUniverse", "SocialSim", "AFK",
            "Nostalgia", "RandomOneLiners", "Complaints", "Reactions", "FlexBrag"
    };


    static final String[] MEGA_CATEGORIES = {
            "BirthdayMessages", "ItemSales", "GuildRecruitment", "PQRecruitment",
            "RWTSpam", "Flex", "RandomAnnouncements", "SocialMessages"
    };

    // Weights for mega category selection (must match MEGA_CATEGORIES order). Each one can be changed without a
    // build: -Dsolomapling.megaWeight.<Category>=N (e.g. -Dsolomapling.megaWeight.RWTSpam=0 turns RWT spam off).
    // Ticket 47: RWTSpam was 19 of 100; it is now flavour at 3 - the owner's call, see
    // docs/chat/content-review-2026-10.md. Weights need not add up to 100.
    static final int[] MEGA_DEFAULT_WEIGHTS = {
            5,   // BirthdayMessages
            12,  // ItemSales
            10,  // GuildRecruitment
            7,   // PQRecruitment (real PQ calls come from WorldEventMegaphones)
            3,   // RWTSpam
            19,  // Flex
            22,  // RandomAnnouncements
            22   // SocialMessages
    };

    static int megaWeight(int index) {
        return Math.max(0, Integer.getInteger("solomapling.megaWeight." + MEGA_CATEGORIES[index],
                MEGA_DEFAULT_WEIGHTS[index]));
    }

    private SocialHotPotatoManager() {}

    public static synchronized SocialHotPotatoManager getInstance() {
        if (instance == null) {
            instance = new SocialHotPotatoManager();
        }
        return instance;
    }

    public void start() {
        if (running) return;
        running = true;
        WorldEventMegaphones.getInstance().register();
        DialoguePackCheck.logOnce();
        refreshMapScope();
        scheduleNextTick();
        scheduleNextMegaTick();
        log("[SocialHotPotato] Started.");
    }

    // Rebuild the bark map scope from config. Called on start and by !env townpresence reload so town
    // curation applies without a restart. Does not touch the (server-wide) megaphone ticker.
    public void refreshMapScope() {
        ambientMapIds = buildAmbientMapIds();
        log("[SocialHotPotato] Bark map scope: " + ambientMapIds.size() + " maps.");
    }

    private static Set<Integer> buildAmbientMapIds() {
        Set<Integer> ids = new LinkedHashSet<>();
        for (int id : HENESYS_MAP_IDS) {
            ids.add(id);
        }
        ids.addAll(TownPresenceConfig.allTownMapIds());
        ids.add(constants.id.MapId.FM_ENTRANCE);
        ids.add(constants.id.MapId.MAPLEROOT_HUB);
        for (int room = 1; room <= 22; room++) {
            ids.add(constants.id.MapId.FM_ENTRANCE + room);
            ids.add(constants.id.MapId.MAPLEROOT_HUB + room);
        }
        return ids;
    }

    public void stop() {
        running = false;
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }
        if (megaScheduledTask != null) {
            megaScheduledTask.cancel(false);
        }
        log("[SocialHotPotato] Stopped.");
    }

    private void scheduleNextTick() {
        if (!running) return;
        int delay = (int) ((MIN_INTERVAL_MS + random.nextInt(MAX_INTERVAL_MS - MIN_INTERVAL_MS))
                / Math.max(0.01, BotChatTuning.multiplier(BotChatTuning.MapType.TOWN)));
        scheduledTask = ExecutorServiceManager.getScheduledExecutorService().schedule(() -> {
            try {
                tick();
            } catch (Exception e) {
                log("[SocialHotPotato] Error during tick: " + e.getMessage());
                e.printStackTrace();
            }
            scheduleNextTick();
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void scheduleNextMegaTick() {
        if (!running) return;
        int delay = MEGA_MIN_INTERVAL_MS + random.nextInt(MEGA_MAX_INTERVAL_MS - MEGA_MIN_INTERVAL_MS);
        megaScheduledTask = ExecutorServiceManager.getScheduledExecutorService().schedule(() -> {
            try {
                megaTick();
            } catch (Exception e) {
                log("[SocialHotPotato] Error during mega tick: " + e.getMessage());
                e.printStackTrace();
            }
            scheduleNextMegaTick();
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void megaTick() {
        if (BotShutdown.isQuiesced()) {
            return; // Ticket 09: no server-wide megaphone while the server is going down
        }
        // Ticket 47: a real world event waiting for the gate (boss kill, rare drop, PQ forming) goes first.
        if (WorldEventMegaphones.getInstance().flushPending()) return;
        // Megaphones are server-wide (heard by every online player regardless of the sender's map), so the
        // pool is unfiltered by observation - draw a sender from any eligible bot in scope.
        Character bot = selectRandomFillerBot(false);
        if (bot == null) return;
        doMegaphone(bot);
    }

    private void tick() {
        if (BotShutdown.isQuiesced()) {
            return; // Ticket 09: no bark, chair or nudge once the gate has closed
        }
        // Barks are local chat/emote/chair packets, so gate on observation: only pick from maps a real
        // player is on. Unobserved towns emit no barks (gate packets on observation).
        Character bot = selectRandomFillerBot(true);
        if (bot == null) return;

        if (MovementCommands.nudgeAwayFromOverlap(bot)) return;

        executeRandomAction(bot);
    }

    private Character selectRandomFillerBot(boolean requireObserved) {
        List<Character> fillerBots = new ArrayList<>();

        for (int mapId : ambientMapIds) {
            if (requireObserved && !GCMovement.isMapObserved(mapId)) continue; // skip unwatched maps entirely
            try {
                // Ticket 57: the same town map on every channel it is loaded on; an observed bark goes where a real
                // player is (observation is kept per map id, across channels).
                for (MapleMap map : BotChannels.loadedMaps(mapId)) {
                    if (requireObserved && map.getAllPlayers().stream().allMatch(BotHelpers::isBot)) continue;
                    for (Character chr : map.getAllPlayers()) {
                        if (!isBot(chr)) continue;
                        BotSM bot = CharacterStorage.getBotById(chr.getId());
                        if (bot == null || !bot.isAvailableForAmbientActions()) continue;
                        if (ConversationManager.getInstance().isInConversation(chr.getId())) continue;
                        fillerBots.add(chr);
                    }
                }
            } catch (Exception e) {
                // Map not loaded yet, skip
            }
        }

        if (fillerBots.isEmpty()) return null;
        return fillerBots.get(random.nextInt(fillerBots.size()));
    }

    private void executeRandomAction(Character bot) {
        int roll = random.nextInt(100);

        if (roll < 45) {
            doChat(bot);
        } else if (roll < 62) {
            doEmote(bot);
        } else if (roll < 77) {
            doPositionChange(bot);
        } else if (roll < 92) {
            doEmoteAndChat(bot);
        } else {
            doChalkboard(bot);
        }
    }

    // --- Action handlers ---

    private void doChat(Character bot) {
        String category = SOCIAL_CATEGORIES[random.nextInt(SOCIAL_CATEGORIES.length)];
        String line = backableLine(bot, category);
        if (line != null) {
            soloMapling.ArtificialPlayer.BotConvo.SayDo.withSource("pack:SocialHotPotato/" + category,
                    () -> BotAmbientSpeak(bot, line));
        }
    }

    /**
     * rc6 say-do: a random line of the category this bot can stand behind: a claim line ("brb dinner", "brb pots")
     * only when it will then do it (ClaimActions), else another pick (up to 6), else a line with no claim, else none.
     */
    private String backableLine(Character bot, String category) {
        return backableLine(bot, SOCIAL_DIALOGUE_PATH, SOCIAL_BOT_TYPE, category);
    }

    private String backableLine(Character bot, String path, String type, String category) {
        String fallback = null;
        for (int i = 0; i < 6; i++) {
            String line = getRandomLine(path, type, category);
            if (line == null) {
                return null;
            }
            var claims = soloMapling.ArtificialPlayer.BotConvo.ClaimRules.classify(line);
            if (claims.isEmpty()) {
                if (fallback == null) {
                    fallback = line;
                }
                if (random.nextInt(4) != 0) {
                    return line; // most of the time any claim-free line will do
                }
                continue;
            }
            if (soloMapling.ArtificialPlayer.BotConvo.ClaimActions.canBack(bot, claims)) {
                return line;
            }
        }
        return fallback;
    }

    private void doEmote(Character bot) {
        int emoteId = 1 + random.nextInt(22);
        BotEmote(bot, emoteId);
    }

    private void doPositionChange(Character bot) {
        int pick = random.nextInt(4);
        switch (pick) {
            case 0:
                if (bot.getChair() > 0) {
                    botCancelChair(bot);
                } else {
                    botSitChair(bot, getRandomChairId());
                }
                break;
            case 1:
                microTurnAround(bot);
                break;
            case 2:
                BotIdleStandingUpdate(bot);
                break;
            case 3:
                MovementCommands.nudgeSmall(bot);
                break;
        }
    }

    private void doEmoteAndChat(Character bot) {
        int emoteId = 1 + random.nextInt(22);
        BotEmote(bot, emoteId);
        String category = SOCIAL_CATEGORIES[random.nextInt(SOCIAL_CATEGORIES.length)];
        String line = backableLine(bot, category);
        if (line != null) {
            soloMapling.ArtificialPlayer.BotConvo.SayDo.withSource("pack:SocialHotPotato/" + category,
                    () -> BotAmbientSpeak(bot, line));
        }
    }

    private void doChalkboard(Character bot) {
        String line = getRandomLine(SOCIAL_DIALOGUE_PATH, SOCIAL_BOT_TYPE, "Chalkboard");
        if (line == null) return;
        botSetChalkboard(bot, line);
        MethodScheduler.runAfterDelay(() -> botClearChalkboard(bot), 5 * 60 * 1000);
    }

    private void doMegaphone(Character bot) {
        String category = selectWeightedMegaCategory();
        if (category == null) return;
        String line;
        if ("GuildRecruitment".equals(category)) {
            // 1.5.0: recruit for a real bot guild, by one of its members; no bot guild = no guild megaphone
            Object[] recruit = soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.ambientRecruit(bot, random);
            if (recruit == null) return;
            bot = (Character) recruit[0];
            line = (String) recruit[1];
        } else {
            // rc6 say-do: "S> clean reverse weapon 500m trade me" only from a bot that holds one (else another line)
            line = backableLine(bot, MEGA_DIALOGUE_PATH, MEGA_BOT_TYPE, category);
        }
        if (line == null || !BotMegaphoneGate.tryAcquire()) return;

        // An avatar megaphone shows 4 lines of 13 characters; longer lines would be cut off, so they go super.
        if (line.length() > 52 || random.nextInt(100) < 75) {
            BotSuperMegaphone(bot, line);
        } else {
            BotAvatarMegaphone(bot, line);
        }
    }

    private String selectWeightedMegaCategory() {
        return pickWeightedMegaCategory(random.nextDouble());
    }

    /** The category a uniform roll in [0,1) lands on under the current weights; null when every weight is 0. */
    static String pickWeightedMegaCategory(double roll) {
        int total = 0;
        for (int i = 0; i < MEGA_CATEGORIES.length; i++) {
            total += megaWeight(i);
        }
        if (total <= 0) {
            return null;
        }
        int target = (int) (roll * total);
        int cumulative = 0;
        for (int i = 0; i < MEGA_CATEGORIES.length; i++) {
            cumulative += megaWeight(i);
            if (target < cumulative) {
                return MEGA_CATEGORIES[i];
            }
        }
        return MEGA_CATEGORIES[MEGA_CATEGORIES.length - 1];
    }

    public void testNudge(Character bot) {
        MovementCommands.nudgeSmall(bot);
    }

    public boolean testNudgeOverlap(Character bot) {
        return MovementCommands.nudgeAwayFromOverlap(bot);
    }

    // --- Dialogue loading ---

    private String getRandomLine(String dialoguePath, String botType, String category) {
        try {
            BotDialogueHandler.DialogueConstructor dialog =
                    BotDialogueHandler.getDialogueCon(dialoguePath, botType, category);
            if (dialog == null || dialog.getDialogue().isEmpty()) return null;
            List<String> lines = dialog.getDialogue();
            return lines.get(random.nextInt(lines.size()));
        } catch (Exception e) {
            return null;
        }
    }
}
