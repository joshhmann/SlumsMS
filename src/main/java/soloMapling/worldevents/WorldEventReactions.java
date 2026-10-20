package soloMapling.worldevents;

import client.Character;
import soloMapling.ArtificialPlayer.BotDialogueHandler;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMegaphoneSystem.WorldEventMegaphones;
import soloMapling.ArtificialPlayer.BotMemory;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotPersonality;
import soloMapling.ArtificialPlayer.BotPopulationDirector;
import soloMapling.ArtificialPlayer.BotRoster;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotGrindSystem.TrainingRegions;
import soloMapling.ArtificialPlayer.BotTravelSystem.BotMapGates;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.ArtificialPlayer.ConversationManager;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.server.ExecutorServiceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static soloMapling.BotLogger.log;

/**
 * How the bot population reacts to a world event (ticket 21, spec "Bot reactions").
 * <ul>
 *   <li><b>Gather:</b> training bots in the event's region whose level fits and whose personality leans to grinding
 *   drop what they are doing and head to the map (up to the event's crowd cap). Committed bots stay put: the same
 *   test the population director uses before a logout (open trade or invite, a conversation, a party with a real
 *   player, following someone). Bots never win anything and never get event rewards.</li>
 *   <li><b>Chatter:</b> while it runs, now and then a bot on a map a real player can see says a line about it; after
 *   it ends, a few more lines for a while. All of it is bot map chat, so {@code @quiet bots} hides it.</li>
 *   <li><b>Megaphones:</b> sometimes one bot shouts about the event when it starts, and congratulates a real player
 *   who won it - through {@link WorldEventMegaphones}, so through {@code BotMegaphoneGate}, its cooldowns and
 *   {@code @quiet megaphones}.</li>
 *   <li><b>Memory:</b> a real player who wins becomes a note for the bots that were there; {@code BotGossip} spreads it.</li>
 * </ul>
 * When the event ends the bots are released and carry on with their session.
 */
public final class WorldEventReactions {

    static final String PACK = "WorldEventDialogue.yaml";
    static final String ROOT = "WorldEvent";
    /** How long bots keep talking about an event after it ends. */
    static final long AFTERMATH_MS = 20 * 60_000L;
    static final double START_MEGAPHONE_CHANCE = 0.6;
    static final int MEMORY_WITNESSES = 8;

    private WorldEventReactions() {
    }

    // ── who goes ─────────────────────────────────────────────────────────────────────────────────

    /**
     * The chance a free, fitting bot answers the call, from its personality: grinders mostly go, social players
     * sometimes, town loiterers rarely. A bot without stored traits goes half the time.
     */
    static double joinChance(BotPersonality.Traits traits) {
        if (traits == null) {
            return 0.5;
        }
        double total = traits.grind() + traits.town() + traits.social();
        if (total <= 0) {
            return 0.5;
        }
        double lean = (traits.grind() + 0.5 * traits.social()) / total;
        return Math.max(0.1, Math.min(0.9, 0.15 + 0.75 * lean));
    }

    static boolean levelFits(int level, int min, int max) {
        return level >= min && level <= max;
    }

    /** A region key for a map: its TrainingRegions region, else its continent-sized id block. */
    static String regionKey(int mapId) {
        TrainingRegions.Region r = TrainingRegions.regionOf(mapId);
        return r != null ? r.name() : "block" + (mapId / 10_000_000);
    }

    static String regionName(int mapId) {
        TrainingRegions.Region r = TrainingRegions.regionOf(mapId);
        return r != null ? r.name() : "the world";
    }

    /** Calls fitting bots to the event, up to its cap. Returns how many new bots answered. */
    static int recruit(ActiveEvent e, Random rng) {
        if (!e.template.kind().botsGather() || e.finished) {
            return 0;
        }
        int cap = Math.min(e.template.botCap(), Math.max(0, Integer.getInteger("solomapling.worldEvents.botCap", 10)));
        int room = cap - e.joinedBots.size();
        if (room <= 0) {
            return 0;
        }
        String region = regionKey(e.mapId());
        List<TrainingBot> pool = new ArrayList<>();
        for (BotSM bot : CharacterStorage.getAllBots().values()) {
            if (!(bot instanceof TrainingBot tb)) {
                continue;
            }
            Character chr = bot.getChr();
            if (chr == null || chr.getMap() == null || e.joinedBots.contains(chr.getId())
                    || WorldEventSteer.isSteered(chr.getId()) || CharacterStorage.checkIfInvisibleBot(chr.getId())) {
                continue;
            }
            if (!levelFits(chr.getLevel(), e.minLevel, e.maxLevel) || !region.equals(regionKey(chr.getMapId()))
                    || !BotMapGates.mayEnter(e.mapId(), BotMapGates.rebirthsOf(chr))) {
                continue;
            }
            if (!BotPopulationDirector.mayLogOut(bot)) {
                continue; // committed to a player: stays put
            }
            pool.add(tb);
        }
        Collections.shuffle(pool, rng);
        int joined = 0;
        for (TrainingBot tb : pool) {
            if (joined >= room) {
                break;
            }
            Character chr = tb.getChr();
            BotRoster.Member member = BotRoster.member(chr.getId() - BotStore.BOT_ID_BASE);
            if (rng.nextDouble() >= joinChance(member == null ? null : member.traits())) {
                continue;
            }
            WorldEventSteer.steer(chr.getId(), e.id, e.mapId());
            e.joinedBots.add(chr.getId());
            tb.callToEvent();
            joined++;
        }
        if (joined > 0) {
            log("[WorldEvents] #" + e.id + " " + joined + " bots head to " + e.mapName + " (" + e.joinedBots.size()
                    + "/" + cap + ")");
        }
        return joined;
    }

    static void release(ActiveEvent e) {
        WorldEventSteer.release(e.id);
    }

    // ── what they say ────────────────────────────────────────────────────────────────────────────

    static String nodePrefix(EventTemplate.Kind kind) {
        return switch (kind) {
            case INVASION -> "Invasion";
            case BOSS_SIGHTING -> "Boss";
            case TREASURE_HUNT -> "Treasure";
            case HOT_SPOT -> "HotSpot";
            case WANDERING_NPC -> "Wanderer";
        };
    }

    static Map<String, String> tokens(ActiveEvent e) {
        Map<String, String> t = new HashMap<>();
        t.put("MAP", e.mapName);
        t.put("EVENT", e.template.name());
        t.put("CH", String.valueOf(e.channel));
        t.put("REGION", e.regionName);
        if (e.subject != null && !e.subject.isBlank()) {
            t.put("BOSS", e.subject);
            t.put("ITEM", e.subject);
        }
        if (e.npcName != null && !e.npcName.isBlank()) {
            t.put("NPC", e.npcName);
        }
        if (e.winnerName != null) {
            t.put("PLAYER", e.winnerName);
        }
        return t;
    }

    /** One line about a running event from a bot a real player can see; at most one per event per chatter gap. */
    static void chatter(ActiveEvent e, long now, Random rng, boolean aftermath) {
        long gap = Math.max(20, Long.getLong("solomapling.worldEvents.chatterSeconds", 90)) * 1000L;
        if (aftermath) {
            gap *= 3;
        }
        if (now - e.lastChatterAt < gap) {
            return;
        }
        e.lastChatterAt = now - (long) (rng.nextDouble() * gap * 0.3); // a little jitter
        Character speaker = pickSpeaker(e, rng, !aftermath);
        if (speaker == null) {
            return;
        }
        String node = nodePrefix(e.template.kind()) + (aftermath ? "After" : "During");
        String line = pickLine(node, tokens(e), rng);
        if (line == null) {
            return;
        }
        ExecutorServiceManager.runAsync(() -> SocialCommands.BotSpeak(speaker, line));
    }

    /** A free bot on a map a real player is on: on the event map first, else anywhere in the region. */
    private static Character pickSpeaker(ActiveEvent e, Random rng, boolean preferEventMap) {
        String region = regionKey(e.mapId());
        List<Character> onMap = new ArrayList<>();
        List<Character> inRegion = new ArrayList<>();
        for (BotSM bot : CharacterStorage.getAllBots().values()) {
            Character chr = bot.getChr();
            if (chr == null || chr.getMap() == null || !free(chr) || !GCMovement.isMapObserved(chr.getMapId())) {
                continue;
            }
            if (chr.getMapId() == e.mapId()) {
                onMap.add(chr);
            } else if (region.equals(regionKey(chr.getMapId()))) {
                inRegion.add(chr);
            }
        }
        List<Character> from = preferEventMap && !onMap.isEmpty() ? onMap : inRegion.isEmpty() ? onMap : inRegion;
        return from.isEmpty() ? null : from.get(rng.nextInt(from.size()));
    }

    private static boolean free(Character chr) {
        return BotHelpers.isBot(chr) && !CharacterStorage.checkIfInvisibleBot(chr.getId())
                && !ConversationManager.getInstance().isInConversation(chr.getId());
    }

    static String pickLine(String node, Map<String, String> tokens, Random rng) {
        BotDialogueHandler.DialogueConstructor dialog = BotDialogueHandler.getDialogueCon(PACK, ROOT, node);
        if (dialog == null || dialog.getDialogue() == null || dialog.getDialogue().isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>(dialog.getDialogue());
        Collections.shuffle(lines, rng);
        for (String line : lines) {
            String filled = fillAll(line, tokens);
            if (filled != null) {
                return filled;
            }
        }
        return null;
    }

    /** Fills every {TOKEN}; null when a token has no value (that line is skipped). */
    static String fillAll(String line, Map<String, String> tokens) {
        String out = line;
        for (Map.Entry<String, String> t : tokens.entrySet()) {
            if (t.getValue() != null && !t.getValue().isBlank()) {
                out = out.replace("{" + t.getKey() + "}", t.getValue());
            }
        }
        return out.matches(".*\\{[A-Z_]+}.*") ? null : out;
    }

    // ── megaphones ───────────────────────────────────────────────────────────────────────────────

    static void megaphoneStart(ActiveEvent e, Random rng) {
        if (rng.nextDouble() >= START_MEGAPHONE_CHANCE) {
            return;
        }
        Character speaker = anyFreeBot(e, rng);
        if (speaker != null) {
            WorldEventMegaphones.getInstance().offerEvent(speaker, "WorldEvent" + nodePrefix(e.template.kind()),
                    tokens(e), "wevent:start:" + e.id);
        }
    }

    static void megaphoneWon(ActiveEvent e, Random rng) {
        if (e.winnerName == null) {
            return;
        }
        Character speaker = null;
        List<Character> here = new ArrayList<>();
        for (int id : e.joinedBots) {
            BotSM bot = CharacterStorage.getBotById(id);
            if (bot != null && bot.getChr() != null && bot.getChr().getMapId() == e.mapId() && free(bot.getChr())) {
                here.add(bot.getChr());
            }
        }
        if (!here.isEmpty()) {
            speaker = here.get(rng.nextInt(here.size()));
        }
        if (speaker == null) {
            speaker = anyFreeBot(e, rng);
        }
        if (speaker != null) {
            WorldEventMegaphones.getInstance().offerEvent(speaker, "WorldEventWon", tokens(e), "wevent:won:" + e.id);
        }
    }

    private static Character anyFreeBot(ActiveEvent e, Random rng) {
        String region = regionKey(e.mapId());
        List<Character> near = new ArrayList<>();
        List<Character> any = new ArrayList<>();
        for (BotSM bot : CharacterStorage.getAllBots().values()) {
            Character chr = bot.getChr();
            if (chr == null || chr.getMap() == null || !free(chr) || CharacterStorage.getBotById(chr.getId()) == null) {
                continue;
            }
            any.add(chr);
            if (region.equals(regionKey(chr.getMapId()))) {
                near.add(chr);
            }
        }
        List<Character> from = near.isEmpty() ? any : near;
        return from.isEmpty() ? null : from.get(rng.nextInt(from.size()));
    }

    // ── memory ───────────────────────────────────────────────────────────────────────────────────

    /** The bots that were there remember the real player who won; gossip carries it further. */
    static void rememberWinner(ActiveEvent e, server.maps.MapleMap map, long now) {
        if (e.winnerId <= 0 || e.winnerName == null) {
            return;
        }
        List<Integer> witnesses = new ArrayList<>();
        for (int id : e.joinedBots) {
            BotSM bot = CharacterStorage.getBotById(id);
            if (bot != null && bot.getChr() != null && bot.getChr().getMapId() == e.mapId()) {
                witnesses.add(id);
            }
        }
        if (map != null) {
            for (Character chr : map.getAllPlayers()) {
                if (BotHelpers.isBot(chr) && !witnesses.contains(chr.getId())) {
                    witnesses.add(chr.getId());
                }
            }
        }
        if (witnesses.isEmpty()) {
            return;
        }
        String note = noteText(e);
        List<Integer> chosen = witnesses.subList(0, Math.min(MEMORY_WITNESSES, witnesses.size()));
        List<Integer> copy = List.copyOf(chosen);
        int winner = e.winnerId;
        ExecutorServiceManager.runAsync(() -> {
            for (int charId : copy) {
                try {
                    BotMemory.remember(charId - BotStore.BOT_ID_BASE, winner, List.of(note), BotMemory.Source.DIRECT,
                            0, 0, now);
                } catch (RuntimeException ex) {
                    log("[WorldEvents] memory note failed: " + ex);
                }
            }
        });
    }

    static String noteText(ActiveEvent e) {
        return switch (e.template.kind()) {
            case INVASION -> "beat " + e.subject + " in the " + e.template.name().toLowerCase() + " at " + e.mapName;
            case BOSS_SIGHTING -> "killed " + e.subject + " at " + e.mapName + " when it showed up";
            case TREASURE_HUNT -> "found the hidden treasure chest at " + e.mapName + " first";
            case WANDERING_NPC -> "helped " + e.npcName + " in " + e.mapName + " first";
            case HOT_SPOT -> "trained at the hot spot on " + e.mapName;
        };
    }
}
