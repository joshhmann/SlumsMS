package soloMapling.ArtificialPlayer.BotPartySystem;

import client.Character;
import net.server.Server;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import net.server.world.World;
import soloMapling.ArtificialPlayer.BotConversation;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.qa.QaLog;
import soloMapling.server.ExecutorServiceManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Ticket 85: party chat reaches the bots. A player's line on the party channel goes to the bots in that party through
 * the same conversation pipeline whispers, buddy chat and guild chat use ({@link BotConversation#talk}: triage, sense,
 * memory, say-do, the no-AI-talk rules), routed by {@link PartyChatRoute} (name, clear reply, closest, nobody), and
 * the answer goes back on the party channel. One bot answers a line. Rate limits: a named line or a reply may be
 * answered every 1.5 s, other lines every 6 s, and a party hears at most {@value #MAX_PER_MINUTE} bot lines a minute.
 * Parties of bots alone sometimes talk among themselves ({@link #maybeStep}).
 */
public final class BotPartyChat {

    static final int MAX_PER_MINUTE = 8;
    static final long NAMED_GAP_MS = 1_500;
    static final long OPEN_GAP_MS = 6_000;
    static final long STEP_MS = TimeUnit.MINUTES.toMillis(1);
    /** Per bot-only party, per minute: the chance one member says something. */
    static final double SOLO_CHATTER_CHANCE = 0.04;

    /** What the party has heard from its bots lately. */
    static final class PartyState {
        PartyChatRoute.Last last = PartyChatRoute.Last.NONE;
        long lastAt;
        final Deque<Long> window = new ArrayDeque<>();
    }

    private static final Map<Integer, PartyState> states = new ConcurrentHashMap<>();
    private static volatile long lastStepAt;

    private BotPartyChat() {
    }

    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("solomapling.partyChat", "true"));
    }

    // ── a player speaks ──

    /** {@code MultiChatHandler}, party type: a real player said {@code line} to their party. */
    public static void onPartyChat(Character player, String line) {
        if (!enabled() || player == null || line == null || line.isBlank() || BotHelpers.isBot(player)
                || player.getParty() == null || isCommand(line)) {
            return;
        }
        Party party = player.getParty();
        List<PartyChatRoute.Member> bots = botsOf(party, player);
        if (bots.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        PartyState state = states.computeIfAbsent(party.getId(), k -> new PartyState());
        PartyChatRoute.Choice choice;
        synchronized (state) {
            choice = decide(state, line, bots, bots.size(), now, ThreadLocalRandom.current());
        }
        if (choice == null) {
            return;
        }
        BotSM brain = CharacterStorage.getBotById(choice.botId());
        if (brain == null || brain.getChr() == null) {
            return;
        }
        Character speaker = brain.getChr();
        qa(player.getMapId(), "PARTY CHAT " + player.getName() + ": \"" + line + "\" -> " + speaker.getName()
                + " answers [" + choice.step().label + "]");
        BotConversation.talk(brain, player, line, reply -> sayInParty(speaker, reply));
    }

    static boolean isCommand(String line) {
        char c = line.trim().charAt(0);
        return c == '!' || c == '@' || c == '/';
    }

    /**
     * Route and gate one line: the choice, or null when no bot answers (nobody fits, the dice said no, or a rate limit
     * held it). A line that gets an answer is booked against the party's limits here.
     */
    static PartyChatRoute.Choice decide(PartyState state, String line, List<PartyChatRoute.Member> bots, int botCount,
                                        long now, Random rng) {
        PartyChatRoute.Choice choice = PartyChatRoute.route(line, bots, state.last, now, rng);
        if (!choice.answers()) {
            return null;
        }
        boolean named = choice.step() == PartyChatRoute.Step.NAME || choice.step() == PartyChatRoute.Step.REPLY;
        if (!allow(state, named, now)) {
            return null;
        }
        if (rng.nextDouble() >= PartyChatRoute.answerChance(choice.step(), line, botCount)) {
            return null;
        }
        book(state, choice.botId(), null, now);
        return choice;
    }

    /** The party's rate limits: a minute window and a gap since the last bot line. */
    static boolean allow(PartyState state, boolean named, long now) {
        while (!state.window.isEmpty() && now - state.window.peekFirst() > 60_000) {
            state.window.pollFirst();
        }
        if (state.window.size() >= MAX_PER_MINUTE) {
            return false;
        }
        return now - state.lastAt >= (named ? NAMED_GAP_MS : OPEN_GAP_MS);
    }

    static void book(PartyState state, int botId, String text, long now) {
        state.lastAt = now;
        state.window.addLast(now);
        state.last = new PartyChatRoute.Last(botId, text == null ? "" : text, now);
    }

    static List<PartyChatRoute.Member> botsOf(Party party, Character player) {
        List<PartyChatRoute.Member> out = new ArrayList<>();
        for (PartyCharacter pc : party.getMembers()) {
            if (!BotHelpers.isArtificial(pc.getId())) {
                continue;
            }
            BotSM brain = CharacterStorage.getBotById(pc.getId());
            Character chr = brain == null ? null : brain.getChr();
            if (chr == null || !pc.isOnline()) {
                continue;
            }
            int dist = Integer.MAX_VALUE;
            if (player != null && chr.getMap() != null && chr.getMapId() == player.getMapId()
                    && chr.getClient().getChannel() == player.getClient().getChannel()) {
                dist = (int) Math.min(Integer.MAX_VALUE - 1, Math.round(chr.getPosition().distance(player.getPosition())));
            }
            out.add(new PartyChatRoute.Member(chr.getId(), chr.getName(), dist));
        }
        return out;
    }

    // ── a bot speaks ──

    /** A bot says a line on its party's channel (every real member sees it). */
    public static void sayInParty(Character bot, String line) {
        if (bot == null || line == null || line.isBlank() || bot.getParty() == null) {
            return;
        }
        Party party = bot.getParty();
        World world = Server.getInstance().getWorld(bot.getWorld());
        if (world == null) {
            return;
        }
        world.partyChat(party, line, bot.getName());
        PartyState state = states.computeIfAbsent(party.getId(), k -> new PartyState());
        synchronized (state) {
            state.last = new PartyChatRoute.Last(bot.getId(), line, System.currentTimeMillis());
        }
        qa(bot.getMapId(), "PARTY SAY " + bot.getName() + ": " + line);
    }

    // ── bots alone ──

    /** From the population director's tick, at most once a minute: a bot-only party now and then talks. */
    public static void maybeStep(long now) {
        if (!enabled() || now - lastStepAt < STEP_MS) {
            return;
        }
        lastStepAt = now;
        Map<Integer, List<Character>> byParty = new HashMap<>();
        Map<Integer, Boolean> hasReal = new HashMap<>();
        for (BotSM bot : CharacterStorage.getAllBots().values()) {
            Character chr = bot.getChr();
            Party p = chr == null ? null : chr.getParty();
            if (p == null) {
                continue;
            }
            byParty.computeIfAbsent(p.getId(), k -> new ArrayList<>()).add(chr);
            for (PartyCharacter pc : p.getMembers()) {
                if (!BotHelpers.isArtificial(pc.getId()) && pc.isOnline()) {
                    hasReal.put(p.getId(), true);
                }
            }
        }
        Random rng = ThreadLocalRandom.current();
        for (Map.Entry<Integer, List<Character>> e : byParty.entrySet()) {
            if (hasReal.containsKey(e.getKey()) || e.getValue().size() < 2) {
                continue;
            }
            soloChatter(e.getValue(), rng);
        }
    }

    /** One bot-only party's minute: maybe one line, maybe a second member answering a few seconds later. */
    static void soloChatter(List<Character> members, Random rng) {
        if (rng.nextDouble() >= SOLO_CHATTER_CHANCE) {
            return;
        }
        Character a = members.get(rng.nextInt(members.size()));
        String line = PartyLines.pick(PartyLines.IDLE, rng);
        ExecutorServiceManager.getScheduledExecutorService().schedule(() -> sayInParty(a, line),
                1 + rng.nextInt(5), TimeUnit.SECONDS);
        if (rng.nextDouble() < 0.4) {
            Character b = members.get(rng.nextInt(members.size()));
            if (b != a) {
                String back = PartyLines.pick(PartyLines.ANSWER, rng);
                ExecutorServiceManager.getScheduledExecutorService().schedule(() -> sayInParty(b, back),
                        6 + rng.nextInt(8), TimeUnit.SECONDS);
            }
        }
    }

    static void qa(int mapId, String line) {
        if (QaLog.on(QaLog.Topic.CONVO)) {
            QaLog.map(QaLog.Topic.CONVO, mapId, line);
        }
    }

    /** Test seam. */
    static void resetForTest() {
        states.clear();
        lastStepAt = 0;
    }
}
