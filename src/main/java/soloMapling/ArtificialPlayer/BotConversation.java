package soloMapling.ArtificialPlayer;

import client.Character;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrain;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrains;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatDecide;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatStats;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage;
import soloMapling.ArtificialPlayer.BotMessagingSystem.BotChatRouting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.server.BotShutdown;
import soloMapling.server.BotTiming;
import soloMapling.server.ExecutorServiceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.botFaceTowardsPoint;
import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotEmote;
import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak;

/**
 * A free-form chat between one bot and one player through the bot's brain (bot-persistence ticket 21): the
 * same engine for every bot type. It holds the last turns, builds the prompt from the bot's persona, live state and
 * what it remembers about the player, types the reply at a human pace, and when the chat ends - by goodbye or by
 * a minute of silence - hands the transcript to {@link BotMemory} for notes.
 *
 * <p>SocialBot runs its conversations through here; bots with a numbered menu send it the lines that are not a
 * menu choice; bots with no menu at all get it straight from the dispatcher.
 */
public final class BotConversation {

    private static final Logger log = LoggerFactory.getLogger(BotConversation.class);

    static final int HISTORY_TURNS = 8;
    static final long IDLE_MS = TimeUnit.SECONDS.toMillis(60);

    /**
     * Owner report 2026-10-24: a bot in a conversation walked off mid-sentence. {@link #IDLE_MS} kept the chat but
     * nothing kept the bot in place, so the town wander, the fidget and a social bot's relocate all moved it away.
     * This is how long after the last exchange (either side, counting the time its reply is still typing) the bot
     * keeps its spot: 45 s, between the owner's 30 s floor and the 60 s session. Movement paths ask
     * {@link #isHoldingChat(Character)}; nothing holds a bot that has a real reason to go.
     */
    static final long HOLD_MS = TimeUnit.SECONDS.toMillis(45);

    private record Key(int botId, int playerId) {
    }

    /** History and idle expiry are shared across channels; pending replies keep their own destination. */
    private static final class ConversationState {
        final List<BotBrain.Turn> history = new ArrayList<>();
        volatile long lastAt;

        ConversationState(long now) {
            lastAt = now;
        }
    }

    private static final class Session {
        final BotSM bot;
        final Character player;
        final ConversationState state;
        final List<BotBrain.Turn> history;
        /** Immutable for this reply, even if the player switches channels while it is being generated. */
        final Consumer<String> deliver;
        final soloMapling.ArtificialPlayer.BotBrainSystem.Priority priority;

        Session(BotSM bot, Character player, long now) {
            this.bot = bot;
            this.player = player;
            this.state = new ConversationState(now);
            this.history = state.history;
            this.deliver = null;
            this.priority = soloMapling.ArtificialPlayer.BotBrainSystem.Priority.PLAYER;
        }

        Session(Session conversation, Consumer<String> deliver,
                soloMapling.ArtificialPlayer.BotBrainSystem.Priority priority) {
            this.bot = conversation.bot;
            this.player = conversation.player;
            this.state = conversation.state;
            this.history = state.history;
            this.deliver = deliver;
            this.priority = priority;
        }
    }

    private static final Map<Key, Session> sessions = new ConcurrentHashMap<>();
    /** Bot id -> the moment its conversation hold ends. One entry per bot that is talking or just talked. */
    private static final Map<Integer, Long> holds = new ConcurrentHashMap<>();
    /** Test seam: the hold length, {@link #HOLD_MS} in production. */
    private static volatile long holdLengthMs = HOLD_MS;
    /**
     * Test seam: the clock the hold reads, real time in production. A test that waits for a hold to lapse would
     * otherwise race the reply's own virtual thread, which arms the hold again when a parked line finally goes out.
     */
    private static volatile java.util.function.LongSupplier clock = System::currentTimeMillis;
    private static volatile boolean sweeperStarted;

    private BotConversation() {
    }

    /**
     * The player said {@code line} to the bot. The reply arrives later (or not: {@code fallback} runs instead when
     * the brain does not answer - the caller's scripted line, or nothing). Returns false when the brain is not
     * live, so the caller can answer from its script straight away.
     */
    public static boolean say(BotSM bot, Character player, String line, Runnable fallback) {
        return say(bot, player, line, fallback, null);
    }

    /**
     * As {@link #say(BotSM, Character, String, Runnable)}, answered through {@code deliver} (a whisper back, say)
     * instead of map chat; null means map chat. Both ways are one conversation with one history and memory.
     */
    public static boolean say(BotSM bot, Character player, String line, Runnable fallback, Consumer<String> deliver) {
        if (!BotBrains.get().isLive()) {
            // no LLM: the caller answers from its script, but the player is still talking to this bot, so it stays put
            if (bot != null && bot.getChr() != null && player != null) {
                holdNear(bot.getChr(), player, System.currentTimeMillis());
            }
            return false;
        }
        return start(bot, player, line, fallback, deliver);
    }

    /**
     * Ticket 37's natural chat: the player named the bot or is still talking to it. Answered in map chat through
     * the same tiers as {@link #say}, and with no LLM configured the scripted tier and the actions still answer -
     * a named bot never falls back to a menu. False only when bot or player is missing.
     */
    public static boolean talk(BotSM bot, Character player, String line) {
        return start(bot, player, line, null, null);
    }

    /**
     * As {@link #talk(BotSM, Character, String)}, answered through {@code deliver} instead of map chat: a bot buddy
     * reading buddy chat (ticket 49). With no LLM the scripted tier still answers.
     */
    public static boolean talk(BotSM bot, Character player, String line, Consumer<String> deliver) {
        return start(bot, player, line, null, deliver, soloMapling.ArtificialPlayer.BotBrainSystem.Priority.PARTNER);
    }

    /** How many lines the player has said in their current chat with this bot (0 when they are not talking). */
    public static int playerLines(int botCharId, int playerId) {
        Session session = sessions.get(new Key(botCharId, playerId));
        if (session == null || System.currentTimeMillis() - session.state.lastAt > IDLE_MS) {
            return 0;
        }
        synchronized (session.history) {
            return (int) session.history.stream().filter(t -> t.role() == BotBrain.Role.USER).count();
        }
    }

    private static boolean start(BotSM bot, Character player, String line, Runnable fallback, Consumer<String> deliver) {
        return start(bot, player, line, fallback, deliver, soloMapling.ArtificialPlayer.BotBrainSystem.Priority.PLAYER);
    }

    private static boolean start(BotSM bot, Character player, String line, Runnable fallback, Consumer<String> deliver,
                                 soloMapling.ArtificialPlayer.BotBrainSystem.Priority priority) {
        if (bot == null || bot.getChr() == null || player == null) {
            return false;
        }
        startSweeper();
        long now = System.currentTimeMillis();
        Session conversation = sessions.computeIfAbsent(new Key(bot.getChr().getId(), player.getId()),
                k -> new Session(bot, player, now));
        Session session = new Session(conversation, deliver, priority);
        session.state.lastAt = now;
        holdNear(bot.getChr(), player, now); // owner report 2026-10-24: the busy bot stays with the player it answers
        // Owner report 2026-10-22: what was said in public on the map, and the bot's own line the player answers
        Heard heard = deliver == null ? heard(bot.getChr(), player, now) : Heard.NONE;
        List<BotBrain.Turn> history;
        synchronized (session.history) {
            if (heard.justSaid() != null && !alreadyInHistory(session.history, heard.justSaid())) {
                // the bot's public line (scripted or not) is a real turn of this chat: the player is answering it
                session.history.add(BotBrain.Turn.assistant(heard.justSaid()));
            }
            session.history.add(BotBrain.Turn.user(line));
            while (session.history.size() > HISTORY_TURNS) {
                session.history.remove(0);
            }
            history = new ArrayList<>(session.history);
        }
        // Off the caller's thread: the first recall of a pair reads the database.
        ExecutorServiceManager.runAsync(() -> {
            try {
                ask(session, history, line, now, fallback, heard);
            } catch (RuntimeException | LinkageError e) {
                log.warn("Bot chat failed for {}", bot.getChr().getName(), e);
            }
        });
        return true;
    }

    /** The map's public chat for this bot's prompt, and its own line the player's line answers (or null). */
    record Heard(String transcript, String justSaid) {
        static final Heard NONE = new Heard("", null);
    }

    /** How long after a bot's public line the player's next line counts as answering it. */
    static final long JUST_SAID_MS = soloMapling.ArtificialPlayer.BotMessagingSystem.MapChatLog.QUESTION_MS;

    static Heard heard(Character bot, Character player, long now) {
        if (bot == null || player == null) {
            return Heard.NONE;
        }
        List<soloMapling.ArtificialPlayer.BotMessagingSystem.MapChatLog.Line> lines =
                soloMapling.ArtificialPlayer.BotMessagingSystem.MapChatLog.recent(
                        soloMapling.ArtificialPlayer.BotMessagingSystem.MapChatLog.channelOf(bot.getMap()),
                        bot.getMapId(), now);
        soloMapling.ArtificialPlayer.BotMessagingSystem.MapChatLog.Line own =
                soloMapling.ArtificialPlayer.BotMessagingSystem.MapChatLog.ownLineBefore(lines, bot.getId(),
                        player.getId(), JUST_SAID_MS, now);
        return new Heard(soloMapling.ArtificialPlayer.BotMessagingSystem.MapChatLog.transcript(lines, bot.getId()),
                own == null ? null : own.text());
    }

    /** Whether one of the last two bot turns already holds this line (a reply said in parts is one turn). */
    static boolean alreadyInHistory(List<BotBrain.Turn> history, String said) {
        int seen = 0;
        for (int i = history.size() - 1; i >= 0 && seen < 2; i--) {
            BotBrain.Turn t = history.get(i);
            if (t.role() == BotBrain.Role.ASSISTANT) {
                seen++;
                if (t.content() != null && t.content().contains(said)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Opens a chat without a line yet (the player named the bot): their next lines go to it. */
    public static void open(BotSM bot, Character player) {
        if (bot == null || bot.getChr() == null || player == null) {
            return;
        }
        // 1.5.1 (owner QA: bots "just zoomed away"): with no LLM there is no chat session, but the player is still
        // talking to this bot (its hint or menu is up), so it keeps its spot like any other exchange.
        holdNear(bot.getChr(), player, System.currentTimeMillis());
        if (!BotBrains.get().isLive()) {
            return;
        }
        startSweeper();
        long now = System.currentTimeMillis();
        sessions.computeIfAbsent(new Key(bot.getChr().getId(), player.getId()), k -> new Session(bot, player, now)).state.lastAt = now;
        holdNear(bot.getChr(), player, now); // a player who just named the bot is having a conversation with it
    }

    /**
     * 1.5.1 (owner QA: "other bots I tried to talk to just zoomed away"): the player addressed this bot through a
     * route that answers without a chat line - a natural action ("party", "trade", "follow"), the "u talking to me?"
     * check, a named menu bot's menu, the double-click window. That is an exchange too: the bot keeps its spot for
     * {@link #HOLD_MS}, on the player's map only (as every hold).
     */
    public static void engaged(Character bot, Character player) {
        holdNear(bot, player, System.currentTimeMillis());
    }

    /** Ticket 59: whether this bot is mid-chat with any player (a bot thread leaves it alone). */
    public static boolean hasAnyChat(int botCharId) {
        long now = System.currentTimeMillis();
        for (Map.Entry<Key, Session> e : sessions.entrySet()) {
            if (e.getKey().botId() == botCharId && now - e.getValue().state.lastAt <= IDLE_MS) {
                return true;
            }
        }
        return false;
    }

    /** An explicit friend request may keep its own conversation open; other partners still hold the bot. */
    public static boolean hasOtherChat(int botCharId, int exceptPlayerId) {
        long now = System.currentTimeMillis();
        return sessions.entrySet().stream().anyMatch(e -> e.getKey().botId() == botCharId
                && e.getKey().playerId() != exceptPlayerId && now - e.getValue().state.lastAt <= IDLE_MS);
    }

    /** Whether this player is mid-chat with this bot. */
    public static boolean isTalking(int botCharId, int playerId) {
        Session session = sessions.get(new Key(botCharId, playerId));
        return session != null && System.currentTimeMillis() - session.state.lastAt <= IDLE_MS;
    }

    /** The bot this player is mid-chat with, or -1 (the most recent one, if several). */
    public static int partnerOf(int playerId) {
        long now = System.currentTimeMillis();
        Session latest = null;
        for (Map.Entry<Key, Session> entry : sessions.entrySet()) {
            Session s = entry.getValue();
            if (entry.getKey().playerId() == playerId && now - s.state.lastAt <= IDLE_MS
                    && (latest == null || s.state.lastAt > latest.state.lastAt)) {
                latest = s;
            }
        }
        return latest == null ? -1 : latest.bot.getChr().getId();
    }

    /**
     * How much longer this bot keeps its spot for the conversation it is in, in ms; 0 when it is free to wander.
     * The callers are the movement paths (town wander, fidget, relocate, town loiter): a held bot answers, emotes
     * and faces the player, it just does not stroll off. Cheap: one map read, no allocation.
     */
    public static long holdMs(int botCharId) {
        Long until = holds.get(botCharId);
        if (until == null) {
            return 0;
        }
        long left = until - clock.getAsLong();
        if (left <= 0) {
            holds.remove(botCharId, until);
            return 0;
        }
        return left;
    }

    /** Whether this bot is mid-conversation and so must keep near the player it is talking to. */
    public static boolean isHoldingChat(int botCharId) {
        return holdMs(botCharId) > 0;
    }

    /**
     * As {@link #isHoldingChat(int)}, for a bot with a real reason to leave: the server is quiescing, the bot is
     * logging out, or a drama scene / party quest owns its movement. Null when none of that applies.
     */
    public static Character heldByChat(Character bot) {
        if (bot == null || BotShutdown.isQuiesced() || !isHoldingChat(bot.getId())) {
            return null;
        }
        try {
            // 1.5.1: a bot only waiting to log out (the director's logout waits for this chat) is still held; one
            // that said its goodbye and is about to disappear is not
            if (bot.getEventInstance() != null || BotLogout.isDeparting(bot.getId())
                    || soloMapling.ArtificialPlayer.BotDramaSystem.DramaKit.inScene(bot.getId())) {
                return null;
            }
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
        return bot;
    }

    /**
     * 1.5.1: whether a player talking to this bot keeps it here - logged in, on this channel: the hold is on and
     * nothing outranks it - not a shutdown, an event or a drama scene. Unlike {@link #heldByChat(Character)} it
     * ignores a queued logout (that is what it decides for the logout). Cheap: one map read for a bot nobody talks to.
     */
    public static boolean keptByChat(Character bot) {
        if (bot == null || !isHoldingChat(bot.getId()) || BotShutdown.isQuiesced()) {
            return false;
        }
        try {
            return bot.getEventInstance() == null
                    && !soloMapling.ArtificialPlayer.BotDramaSystem.DramaKit.inScene(bot.getId());
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** As {@link #heldByChat(Character)}: whether this bot keeps its spot for the chat right now. */
    public static boolean isHoldingChat(Character bot) {
        return heldByChat(bot) != null;
    }

    /**
     * 1.5.1 review: holds the bot only while the player is on its map and channel. A whisper or buddy line from
     * elsewhere is still a conversation, but nobody is there to stand with, so it must not pin the bot in town.
     */
    private static void holdNear(Character bot, Character player, long at) {
        if (bot == null || player == null || bot.getMapId() != player.getMapId()) {
            return;
        }
        try {
            if (bot.getClient().getChannel() != player.getClient().getChannel()) {
                return;
            }
        } catch (RuntimeException e) {
            return; // no client (a test double, a bot mid-logout): nobody to stand with
        }
        hold(bot.getId(), at);
    }

    /** Records an exchange at {@code at}: the bot keeps its spot until {@link #HOLD_MS} after it. */
    private static void hold(int botCharId, long at) {
        long until = Math.max(clock.getAsLong(), at) + holdLengthMs;
        holds.merge(botCharId, until, Math::max);
    }

    /** Test seam: shortens or restores the hold; also clears it so one test's chat cannot hold the next one's bot. */
    static void holdMsForTest(long ms) {
        holdLengthMs = ms;
        holds.clear();
    }

    /** Test seam: the clock the hold reads ({@code null} restores real time). */
    static void clockForTest(java.util.function.LongSupplier testClock) {
        clock = testClock == null ? System::currentTimeMillis : testClock;
    }

    /** The chat is over: forget it, and let the bot write down what it wants to remember. */
    public static void end(int botCharId, int playerId) {
        Session session = sessions.remove(new Key(botCharId, playerId));
        if (session == null) {
            return;
        }
        List<BotBrain.Turn> transcript;
        synchronized (session.history) {
            transcript = new ArrayList<>(session.history);
        }
        if (!transcript.isEmpty()) {
            BotMemory.writeConversationNotes(session.bot.getChr(), session.player, transcript);
            // Ticket 49: after a real chat with a player it already knew, a bot may (rarely) ask to be buddies.
            BotBuddies.afterChat(session.bot.getChr(), session.player,
                    (int) transcript.stream().filter(t -> t.role() == BotBrain.Role.USER).count());
        }
    }

    /** Ends every chat of a bot that is leaving the world. */
    public static void endAllOf(int botCharId) {
        holds.remove(botCharId); // a bot leaving the world keeps no hold for the next one that gets its id
        for (Key key : new ArrayList<>(sessions.keySet())) {
            if (key.botId() == botCharId) {
                end(key.botId(), key.playerId());
            }
        }
    }

    /**
     * Ticket 36/37's fast/slow split for one player line: the fast path sorts it (rules, then the decision model),
     * a flagged con gets a brush-off and a memory note, an asked-for action is decided by rules and done by the
     * server, filler gets an emote or nothing, small talk a scripted line, and only real talk (or an action to
     * narrate) takes an LLM slot. Every line's tier and latency go to {@link ChatStats}.
     */
    private static void ask(Session session, List<BotBrain.Turn> history, String line, long asked, Runnable fallback,
                            Heard heard) {
        Character bot = session.bot.getChr();
        Character player = session.player;
        if (session.bot instanceof soloMapling.ArtificialPlayer.BotTypes.TrainingBot training
                && soloMapling.ArtificialPlayer.BotLifeSystem.LifeRuntime.enabled()) training.lifeRuntime().memory().talked(asked);
        int slot = bot.getId() - BotStore.BOT_ID_BASE;
        boolean whisper = session.deliver != null;
        // Ticket 59: one fast read - rules, then one Decide call for intent, tone and con together
        soloMapling.ArtificialPlayer.BotConvo.ConvoSense.Read read =
                soloMapling.ArtificialPlayer.BotConvo.ConvoSense.read(line, ChatDecide.get());
        ChatTriage.Result triage = read.triage();
        // Ticket 58: a kind line warms the bot to the player, a rude one cools it (the tone read above, no extra call).
        soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityReactions.judgeTone(bot, player, read.affinityTone(),
                read.tone() == soloMapling.ArtificialPlayer.BotConvo.ConvoSense.Tone.FLIRTY, triage);

        // 1.5.0 bot drama: asking the bot out, the apology a rival demanded - answered by the drama rules
        String drama = soloMapling.ArtificialPlayer.BotDramaSystem.BotDrama.onPlayerLine(bot, player, line);
        if (drama != null) {
            if (!drama.isEmpty()) {
                deliver(session, drama, asked, ChatStats.Tier.SCRIPTED, "drama", new Cue(triage,
                        BotChatActions.Outcome.NONE, line));
            }
            return;
        }
        if (triage.flagged()) {
            onFlagged(session, slot, triage, line, asked);
            return;
        }
        // 1.5.0 bot guilds: "can i join ur guild?" - the leader decides, and a real invite follows on a yes
        String guildAnswer = soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.answerApplication(bot, player, line);
        if (guildAnswer != null) {
            deliver(session, guildAnswer, asked, ChatStats.Tier.SCRIPTED, "guild application",
                    new Cue(triage, BotChatActions.Outcome.NONE, line));
            return;
        }
        boolean deferredParty = soloMapling.ArtificialPlayer.BotLifeSystem.LifeRuntime.enabled()
                && triage.intent() == ChatTriage.Intent.PARTY
                && java.util.Set.of(103000800, 221023200, 200080101, 930000000, 261000021).contains(player.getMapId())
                && session.bot instanceof soloMapling.ArtificialPlayer.BotTypes.TrainingBot training
                && training.lifeRuntime().friendAsk(player, true, System.currentTimeMillis());
        BotChatActions.Result action = deferredParty ? new BotChatActions.Result(BotChatActions.Outcome.PARTY_DECLINED,
                "PENDING_LIFE: no invite or join has happened yet.",
                "lemme check a sec", null) : triage.intent() != ChatTriage.Intent.NONE
                ? BotChatActions.act(session.bot, player, triage.intent(), line)
                // ticket 59: come, help, guide, buff, buddy and the moods - the server decides and does it
                : soloMapling.ArtificialPlayer.BotConvo.ConvoActions.onIntent(session.bot, player, read, line);
        Cue cue = new Cue(triage, action.outcome(), line, action.mustMention());
        if (action.awaitingLife()) {
            deliver(session, action.scripted(), asked, ChatStats.Tier.SCRIPTED, "life request pending", cue);
            return;
        }
        BotRoster.Activity doing = session.bot.activitySnapshot();
        String activity = BotChatPrompt.activityPhrase(doing == null ? null : doing.name());
        if (action.outcome() == BotChatActions.Outcome.NONE) {
            switch (triage.kind()) {
                case IGNORE -> {
                    ChatStats.reply(ChatStats.Tier.IGNORED, System.currentTimeMillis() - asked, bot.getName(), "filler");
                    return;
                }
                case EMOTE -> {
                    if (whisper) {
                        deliver(session, bank(session, "talk", read, ScriptedReplies.pick(ScriptedReplies.TALK)), asked,
                                ChatStats.Tier.SCRIPTED, "filler whisper", cue);
                    } else {
                        int face = ReplyEmote.forFiller(line);
                        BotTiming.chain().pause(400 + ThreadLocalRandom.current().nextInt(900))
                                .run(() -> BotEmote(bot, face)).start();
                        ChatStats.reply(ChatStats.Tier.EMOTE, System.currentTimeMillis() - asked, bot.getName(), "filler");
                    }
                    return;
                }
                case THANKS, BYE -> {
                    deliver(session, bank(session, soloMapling.ArtificialPlayer.BotConvo.ConvoLines.key(triage.kind(), triage.intent(), line),
                                    read, ScriptedReplies.forKind(triage.kind())), asked, ChatStats.Tier.SCRIPTED,
                            triage.kind().name().toLowerCase(), cue);
                    return;
                }
                case GREETING -> {
                    if (botHasSpoken(session)) { // a greeting mid-chat is small talk; the first one gets a real reply
                        deliver(session, bank(session, "greeting", read, ScriptedReplies.forKind(triage.kind())), asked,
                                ChatStats.Tier.SCRIPTED, "greeting", cue);
                        return;
                    }
                }
                default -> {
                    // ticket 59: with human timing on, the fighting bot's skipped line is HumanTiming's call
                    if (!soloMapling.ArtificialPlayer.BotConvo.ConvoSwitches.on("timing")
                            && !whisper && "grinding".equals(activity) && botHasSpoken(session)
                            && ThreadLocalRandom.current().nextDouble() < busyIgnoreChance()) {
                        // Mid-fight a real player sometimes just does not answer.
                        ChatStats.reply(ChatStats.Tier.IGNORED, System.currentTimeMillis() - asked, bot.getName(),
                                "busy fighting");
                        return;
                    }
                }
            }
        }

        boolean wary = BotChatActions.wary(slot, player.getId());
        // Ticket 26: the chat's first reply to a player the bot knows greets them like an acquaintance.
        boolean recognise = !botHasSpoken(session) && !wary && BotRecognition.knows(slot, player.getId());
        String system = systemPrompt(session, slot, activity, wary, recognise, action.fact(), asked, whisper,
                BotChatPrompt.asksAboutTheWorld(line));
        if (action.outcome() == BotChatActions.Outcome.NONE && soloMapling.ArtificialPlayer.BotConvo.ConvoSwitches.on("contract")) {
            system += " " + soloMapling.ArtificialPlayer.BotConvo.ConvoContract.instructions();
        }
        String detail = triage.describe() + " " + read.describe() + (action.outcome() == BotChatActions.Outcome.NONE ? ""
                : " -> " + action.outcome().name().toLowerCase());
        List<BotBrain.Turn> prompt = BotChatPrompt.conversation(system, history, heard.transcript(), player.getName(),
                heard.justSaid());
        qaPrompt(bot, player, session.priority, prompt);
        BotBrains.get().reply(session.priority, player.getId(), prompt)
                .thenAccept(contract -> {
            if (!sessions.containsKey(new Key(bot.getId(), player.getId()))) {
                return; // the chat ended while the brain was thinking
            }
            java.util.Optional<String> reply = contract.map(soloMapling.ArtificialPlayer.BotConvo.ConvoContract.Reply::text);
            if (contract.isPresent() && action.outcome() == BotChatActions.Outcome.NONE
                    && contract.get().action() != soloMapling.ArtificialPlayer.BotConvo.ConvoAction.NONE
                    && soloMapling.ArtificialPlayer.BotDramaSystem.BotDrama.replyAllowed(bot, player, contract.get().text())) {
                // Ticket 59: the model proposed an action; the gates decide, and the words must match what happened.
                soloMapling.ArtificialPlayer.BotConvo.ConvoActions.Proposal p =
                        soloMapling.ArtificialPlayer.BotConvo.ConvoActions.onProposal(session.bot, player, contract.get(),
                                read, line);
                if (p.result().outcome() != BotChatActions.Outcome.NONE) {
                    if (p.result().awaitingLife()) {
                        deliver(session, p.result().scripted(), asked, ChatStats.Tier.SCRIPTED,
                                "life request pending", new Cue(triage, p.result().outcome(), line));
                        return;
                    }
                    String said = contract.get().text();
                    boolean fits = p.result().fits(said);
                    deliver(session, fits ? new soloMapling.ArtificialPlayer.BotConvo.ConvoLines.Line(said, -1, "llm")
                            : bank(session, soloMapling.ArtificialPlayer.BotConvo.ConvoLines.key(p.result().outcome()), read,
                            p.result().scripted()), asked, fits ? ChatStats.Tier.LLM
                            : ChatStats.Tier.SCRIPTED, detail + " tag=" + p.action().tag() + ":"
                            + p.decision().verdict().name().toLowerCase() + (fits ? "" : ", words did not fit"),
                            new Cue(triage, p.result().outcome(), line));
                    return;
                }
            }
            if (reply.isPresent() && !soloMapling.ArtificialPlayer.BotDramaSystem.BotDrama.replyAllowed(bot, player,
                    reply.get())) {
                reply = java.util.Optional.empty(); // 1.5.0: a flirt without @romance on, or off-limits: the script answers
            }
            if (reply.isPresent() && action.fits(reply.get())) {
                deliver(session, reply.get(), asked, ChatStats.Tier.LLM, detail, cue);
                return;
            }
            if (reply.isPresent()) {
                // The model ignored what the game did ("sure" to a declined party): the scripted line says it right.
                deliver(session, bank(session, soloMapling.ArtificialPlayer.BotConvo.ConvoLines.key(action.outcome()),
                        read, action.scripted()), asked, ChatStats.Tier.SCRIPTED, detail + ", llm reply did not fit: "
                        + reply.get(), cue);
                return;
            }
            if (action.scripted() != null) {
                deliver(session, bank(session, soloMapling.ArtificialPlayer.BotConvo.ConvoLines.key(action.outcome()),
                        read, action.scripted()), asked, ChatStats.Tier.SCRIPTED, detail + ", llm fell back", cue);
            } else if (fallback != null) {
                fallback.run();
                ChatStats.reply(ChatStats.Tier.SCRIPTED, System.currentTimeMillis() - asked, bot.getName(),
                        detail + ", llm fell back to the caller's script");
            } else {
                String activityAnswer = ScriptedReplies.activityQuestion(line);
                if (activityAnswer != null) {
                    // A generic acknowledgement cannot answer "what u doing". This is true while replying;
                    // the saved roster activity is a role, not proof of current combat, travel or shopping.
                    deliver(session, activityAnswer, asked, ChatStats.Tier.SCRIPTED,
                            detail + ", factual activity reply", cue);
                    return;
                }
                soloMapling.ArtificialPlayer.BotConvo.ConvoLines.Line scripted = recognise
                        && triage.kind() == ChatTriage.Kind.GREETING
                        ? bank(session, "recognise", read, BotRecognition.scriptedHello(player.getName(),
                        ThreadLocalRandom.current()))
                        : bank(session, soloMapling.ArtificialPlayer.BotConvo.ConvoLines.key(triage.kind(), triage.intent(), line), read,
                        ScriptedReplies.forKind(triage.kind()));
                deliver(session, scripted, asked, ChatStats.Tier.SCRIPTED, detail + ", llm fell back", cue);
            }
        });
    }

    /**
     * The system prompt for a reply: persona, live state, memories, recent map chat, the action's outcome, the
     * merchant's stock (ticket 26), and whether to greet the player as someone the bot knows.
     */
    private static String systemPrompt(Session session, int slot, String activity, boolean wary, boolean recognise,
                                       String outcome, long now, boolean whisper, boolean worldFacts) {
        Character bot = session.bot.getChr();
        Character player = session.player;
        BotRoster.Member member = BotRoster.member(slot);
        BotPersona.Persona persona = member == null ? null : member.personaOrNull();
        if (persona == null) {
            persona = BotPersona.generate(slot); // staff and on-demand bots get a stable persona too
        }
        BotPersonality.Traits traits = member == null ? null : member.traits();
        MerchantStock.Stock stock = MerchantStock.of(session.bot);
        BotChatPrompt.Context context = BotChatPrompt.context(bot, player, activity,
                BotChatPrompt.mood(slot, activity, wary, now), BotMemory.recall(slot, player.getId(), now),
                List.of(), outcome) // the map's chat goes in the final turn (BotChatPrompt.finalTurn)
                .with(stock == null ? null : stock.fact(), recognise);
        String card = null;
        String voiceLine = null;
        if (soloMapling.ArtificialPlayer.BotConvo.ConvoSwitches.on("card")) {
            // ticket 59: the relationship memory card (feelings in words, dated memories, romance, guild, situation)
            card = soloMapling.ArtificialPlayer.BotConvo.RelationshipCard.of(bot, player, activity,
                    soloMapling.ArtificialPlayer.BotConvo.BotThreads.contextFor(bot.getId(), player.getId()), now);
        }
        if (soloMapling.ArtificialPlayer.BotConvo.ConvoSwitches.on("persona")) {
            soloMapling.ArtificialPlayer.BotConvo.ConvoPersona voice = soloMapling.ArtificialPlayer.BotConvo.ConvoPersona.of(
                    slot, traits == null ? -1 : traits.chattiness());
            voiceLine = voice.prompt(context.job(), bot.getGuildId() > 0)
                    + soloMapling.ArtificialPlayer.BotConvo.ConvoTune.promptLines();
        }
        return BotChatPrompt.system(context, persona, traits == null ? 0.4 : traits.chattiness(), card, voiceLine,
                worldFacts)
                + soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityReactions.promptLine(bot.getId(), player) // ticket 58: friend or beef
                + soloMapling.ArtificialPlayer.BotCoupleSystem.BotCouples.promptLine(bot.getId()) // ticket 58: a partner
                + soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.promptLine(bot.getId()) // 1.5.0: its guild
                + soloMapling.ArtificialPlayer.BotDramaSystem.BotDrama.promptLine(bot.getId(), player) // 1.5.0: romance, beef
                + soloMapling.ArtificialPlayer.BotConvo.LevelTalk.promptLine(session.bot, now)
                + (soloMapling.ArtificialPlayer.BotLifeSystem.LifeRuntime.enabled()
                    && session.bot instanceof soloMapling.ArtificialPlayer.BotTypes.TrainingBot training
                    ? training.lifeRuntime().prompt() : "");
    }

    /**
     * Ticket 26: a bot noticed a player it knows and says hi first, in map chat, through the brain when it is live
     * (one light remembered thing at most) or a scripted "oh hey again". It opens a chat, so the player's answer
     * reaches the bot, and the bot listens unless the player is already talking to another bot. False when the
     * pair is already mid-chat or a character is missing.
     */
    public static boolean greet(BotSM bot, Character player) {
        if (bot == null || bot.getChr() == null || player == null) {
            return false;
        }
        Character chr = bot.getChr();
        Key key = new Key(chr.getId(), player.getId());
        long now = System.currentTimeMillis();
        if (isTalking(chr.getId(), player.getId())) {
            return false;
        }
        startSweeper();
        Session session = new Session(bot, player, now);
        sessions.put(key, session);
        BotChatRouting.Attention attention = BotChatRouting.attention(player.getId());
        if (attention == null || now - attention.lastAt() > BotChatRouting.attentionMs()) {
            BotChatRouting.attend(player.getId(), chr.getId(), now);
        }
        int slot = chr.getId() - BotStore.BOT_ID_BASE;
        ChatTriage.Result hello = new ChatTriage.Result(ChatTriage.Kind.GREETING, ChatTriage.Intent.NONE,
                ChatTriage.Source.DEFAULT, ChatTriage.Con.NORMAL, ChatTriage.Source.DEFAULT);
        Cue cue = new Cue(hello, BotChatActions.Outcome.NONE, null);
        ExecutorServiceManager.runAsync(() -> {
            try {
                // 1.6.0 S5: the script first when the gate's unprompted-LLM budget is spent; never a repeated hello
                if (!BotBrains.get().isLive() || !SocialGate.llmOk()) {
                    String scripted = freshHello(chr, player);
                    if (scripted == null) {
                        end(chr.getId(), player.getId());
                        return;
                    }
                    deliver(session, scripted, now, ChatStats.Tier.SCRIPTED, "recognised on sight", cue);
                    return;
                }
                BotRoster.Activity doing = bot.activitySnapshot();
                String activity = BotChatPrompt.activityPhrase(doing == null ? null : doing.name());
                String system = systemPrompt(session, slot, activity, false, true, null, now, false, false)
                        + " Nobody has said anything yet: you just noticed " + player.getName()
                        + " near you. Write your first line to them.";
                String transcript = heard(chr, player, now).transcript();
                List<BotBrain.Turn> prompt = BotChatPrompt.conversation(system,
                        List.of(BotBrain.Turn.user((transcript.isEmpty() ? ""
                                : "Public chat on this map just now, oldest first:\n" + transcript + "\n\n")
                                + "(" + player.getName() + " is standing near you)")));
                qaPrompt(chr, player, soloMapling.ArtificialPlayer.BotBrainSystem.Priority.AMBIENT, prompt);
                BotBrains.get().reply(soloMapling.ArtificialPlayer.BotBrainSystem.Priority.AMBIENT, player.getId(), prompt)
                        .thenAccept(contract -> {
                    java.util.Optional<String> raw = contract.map(soloMapling.ArtificialPlayer.BotConvo.ConvoContract.Reply::text);
                    java.util.Optional<String> reply = raw.filter(l ->  // 1.5.0: no flirt without @romance on
                            soloMapling.ArtificialPlayer.BotDramaSystem.BotDrama.replyAllowed(chr, player, l))
                            .filter(l -> SocialGate.fresh(chr.getId(), player.getId(), l)); // 1.6.0 S5: no repeats
                    if (sessions.get(key) != session) {
                        return; // the chat moved on or ended while the brain was thinking
                    }
                    String line = reply.orElseGet(() -> freshHello(chr, player));
                    if (line == null) {
                        end(chr.getId(), player.getId());
                        return;
                    }
                    deliver(session, line, now, reply.isPresent() ? ChatStats.Tier.LLM : ChatStats.Tier.SCRIPTED,
                            "recognised on sight" + (reply.isPresent() ? "" : ", llm fell back"), cue);
                });
            } catch (RuntimeException | LinkageError e) {
                log.warn("Bot greeting failed for {}", chr.getName(), e);
            }
        });
        return true;
    }

    /** A scripted hello this bot has not said to this player lately (1.6.0 S5), or null when all of them were. */
    static String freshHello(Character bot, Character player) {
        for (int i = 0; i < 8; i++) {
            String line = BotRecognition.scriptedHello(player.getName(), ThreadLocalRandom.current());
            if (SocialGate.fresh(bot.getId(), player.getId(), line)) {
                return line;
            }
        }
        return null;
    }

    /** !qa cv-8: the exact prompt a reply is asked with, one log line (newlines shown as \n). */
    static void qaPrompt(Character bot, Character player, soloMapling.ArtificialPlayer.BotBrainSystem.Priority priority,
                         List<BotBrain.Turn> prompt) {
        if (!soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.CONVO)) {
            return;
        }
        soloMapling.qa.QaLog.map(soloMapling.qa.QaLog.Topic.CONVO, bot.getMapId(), "PROMPT " + bot.getName() + " -> "
                + player.getName() + " [" + priority.name().toLowerCase(java.util.Locale.ROOT) + "] "
                + renderPrompt(prompt));
    }

    /** A prompt as one line: each turn as [role] text, newlines as \n. */
    public static String renderPrompt(List<BotBrain.Turn> prompt) {
        StringBuilder s = new StringBuilder();
        for (BotBrain.Turn t : prompt) {
            s.append(s.length() == 0 ? "" : " || ").append('[').append(t.role().name().toLowerCase(java.util.Locale.ROOT))
                    .append("] ").append(t.content() == null ? "" : t.content().replace("\n", "\\n"));
        }
        return s.toString();
    }

    /** A flagged con: no LLM, an in-character brush-off (or silence), and a note so the bot is warier next time. */
    private static void onFlagged(Session session, int slot, ChatTriage.Result triage, String line, long asked) {
        Character bot = session.bot.getChr();
        Character player = session.player;
        String kind = triage.con().name().toLowerCase();
        ChatStats.flagged(kind);
        if (triage.conConfidence() >= flagNoteMin()) {
            String note = BotChatActions.flagNote(triage.con());
            boolean known = BotMemory.about(slot, player.getId()).stream().anyMatch(n -> n.text().equals(note));
            if (!known) {
                BotMemory.remember(slot, player.getId(), List.of(note), BotMemory.Source.DIRECT, 0, 0, asked);
            }
        }
        if (triage.con() == ChatTriage.Con.HARASSMENT && ThreadLocalRandom.current().nextBoolean()) {
            ChatStats.reply(ChatStats.Tier.FLAGGED, System.currentTimeMillis() - asked, bot.getName(),
                    kind + "/" + triage.conSource().name().toLowerCase() + ", ignored");
            return;
        }
        // review 2026-10-22: with @botdrama off a rude player gets a neutral brush-off, never trash talk back
        boolean neutral = triage.con() == ChatTriage.Con.HARASSMENT
                && soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity.optedOut(player.getId());
        deliver(session, neutral ? new soloMapling.ArtificialPlayer.BotConvo.ConvoLines.Line(ScriptedReplies.shrug(), -1,
                        "scripted") : bank(session, soloMapling.ArtificialPlayer.BotConvo.ConvoLines.key(triage.con()), null,
                        ScriptedReplies.brushOff(triage.con())), asked, ChatStats.Tier.FLAGGED,
                kind + "/" + triage.conSource().name().toLowerCase(), new Cue(triage, BotChatActions.Outcome.NONE, line));
    }

    private static boolean botHasSpoken(Session session) {
        synchronized (session.history) {
            return session.history.stream().anyMatch(t -> t.role() == BotBrain.Role.ASSISTANT);
        }
    }

    /** What a reply answers, for the face that goes with it. */
    private record Cue(ChatTriage.Result triage, BotChatActions.Outcome outcome, String asked, String protect) {
        Cue(ChatTriage.Result triage, BotChatActions.Outcome outcome, String asked) {
            this(triage, outcome, asked, null);
        }
    }

    /** Ticket 59: a line from the bank for this key (the bot's voice, feelings and situation), else the script's. */
    private static soloMapling.ArtificialPlayer.BotConvo.ConvoLines.Line bank(Session session, String key,
            soloMapling.ArtificialPlayer.BotConvo.ConvoSense.Read read, String fallback) {
        String tone = read == null ? null : read.tone().name().toLowerCase(java.util.Locale.ROOT);
        soloMapling.ArtificialPlayer.BotConvo.ConvoLines.Line l = soloMapling.ArtificialPlayer.BotConvo.ConvoLines.line(
                key, session.bot.getChr(), session.player, tone, null, fallback, session.deliver != null);
        return l == null ? new soloMapling.ArtificialPlayer.BotConvo.ConvoLines.Line(fallback, -1, "scripted") : l;
    }

    private static void deliver(Session session, String line, long asked, ChatStats.Tier tier, String detail, Cue cue) {
        deliver(session, new soloMapling.ArtificialPlayer.BotConvo.ConvoLines.Line(line, -1,
                tier == ChatStats.Tier.LLM ? "llm" : "scripted"), asked, tier, detail, cue);
    }

    /** Short claim-free answers for a reply whose every clause promised something the game did not decide. */
    private static final java.util.List<String> NEUTRAL = java.util.List.of("hm", "ya", "lol", "maybe", "eh", "true");

    /** rc6 say-do: the reply with any claim the decided outcome does not back taken out. */
    static String guardClaims(Character bot, Character player, String line, Cue cue) {
        if (line == null) {
            return null;
        }
        java.util.Set<soloMapling.ArtificialPlayer.BotConvo.ClaimRules.Kind> allowed =
                soloMapling.ArtificialPlayer.BotConvo.ClaimActions.allowedFor(cue == null ? null : cue.outcome());
        String guarded = soloMapling.ArtificialPlayer.BotConvo.ClaimActions.guardReply(line, allowed);
        if (guarded == null) {
            guarded = NEUTRAL.get(ThreadLocalRandom.current().nextInt(NEUTRAL.size()));
        }
        if (!guarded.equals(line) && soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.CONVO)) {
            soloMapling.qa.QaLog.map(soloMapling.qa.QaLog.Topic.CONVO, bot.getMapId(), "SAYDO GUARD " + bot.getName()
                    + " (" + (cue == null ? "no action" : cue.outcome()) + "): \""
                    + soloMapling.ArtificialPlayer.BotConvo.ConvoQuality.anonymise(line, player.getName()) + "\" -> \""
                    + soloMapling.ArtificialPlayer.BotConvo.ConvoQuality.anonymise(guarded, player.getName()) + "\"");
        }
        return guarded;
    }

    /**
     * Types a line at a human pace, then says it (map chat or whisper) and records its tier. In map chat the bot
     * sometimes makes a fitting face as the bubble appears ({@link ReplyEmote}). Ticket 59: the line is said in the
     * bot's own voice ({@code VoiceStyler}) and timed like a person ({@code HumanTiming}: typing, a split, a late
     * answer or a "brb", or no answer mid-fight), and the quality loop remembers it for {@code !rate}.
     */
    private static void deliver(Session session, soloMapling.ArtificialPlayer.BotConvo.ConvoLines.Line said, long asked,
                                ChatStats.Tier tier, String detail, Cue cue) {
        if (said == null || said.text() == null) {
            return;
        }
        Character bot = session.bot.getChr();
        Character player = session.player;
        int slot = bot.getId() - BotStore.BOT_ID_BASE;
        BotRoster.Member member = BotRoster.member(slot);
        BotPersonality.Traits traits = member == null ? null : member.traits();
        soloMapling.ArtificialPlayer.BotConvo.ConvoPersona persona = soloMapling.ArtificialPlayer.BotConvo.ConvoPersona.of(
                slot, traits == null ? -1 : traits.chattiness());
        String styledLine = soloMapling.ArtificialPlayer.BotConvo.ConvoSwitches.on("persona")
                ? soloMapling.ArtificialPlayer.BotConvo.VoiceStyler.style(said.text(), persona, slot,
                java.util.List.of(player.getName().toLowerCase(java.util.Locale.ROOT),
                        bot.getName().toLowerCase(java.util.Locale.ROOT), cue == null || cue.protect() == null ? ""
                                : cue.protect()))
                : said.text();
        // rc6 say-do: a reply never promises what the decided action does not do ("omw" with the walk refused,
        // "inv me" with no party decided): the clause with that claim is taken out (ClaimActions.guardReply)
        String line = guardClaims(bot, player, styledLine, cue);
        String claimSource = "reply-" + said.source();
        synchronized (session.history) {
            session.history.add(BotBrain.Turn.assistant(line));
        }
        Consumer<String> deliver = session.deliver;
        boolean firstLine;
        synchronized (session.history) {
            firstLine = session.history.stream().filter(t -> t.role() == BotBrain.Role.ASSISTANT).count() <= 1;
        }
        BotRoster.Activity doing = session.bot.activitySnapshot();
        boolean fighting = "grinding".equals(BotChatPrompt.activityPhrase(doing == null ? null : doing.name()))
                && !soloMapling.ArtificialPlayer.BotConvo.RelationshipCard.mobs(bot).isEmpty();
        boolean isAction = cue != null && cue.outcome() != null && cue.outcome() != BotChatActions.Outcome.NONE;
        soloMapling.ArtificialPlayer.BotConvo.HumanTiming.Plan plan = soloMapling.ArtificialPlayer.BotConvo.HumanTiming.plan(
                line, System.currentTimeMillis() - asked, persona,
                new soloMapling.ArtificialPlayer.BotConvo.HumanTiming.Context(isAction, deliver != null, fighting, firstLine),
                ThreadLocalRandom.current());
        if ("brb".equals(plan.kind()) && !soloMapling.ArtificialPlayer.BotConvo.ClaimActions.canBack(bot,
                soloMapling.ArtificialPlayer.BotConvo.ClaimRules.classify("brb"))) {
            // rc6 say-do: no "brb" from a bot that would not go anywhere (a grinder mid-fight): just answer
            plan = new soloMapling.ArtificialPlayer.BotConvo.HumanTiming.Plan(java.util.List.of(
                    new soloMapling.ArtificialPlayer.BotConvo.HumanTiming.Part(plan.parts().get(0).delayMs(), line)),
                    "plain");
        }
        if (plan.ignored()) {
            ChatStats.reply(ChatStats.Tier.IGNORED, System.currentTimeMillis() - asked, bot.getName(), "busy fighting");
            soloMapling.ArtificialPlayer.BotConvo.ConvoQuality.timing(bot, player, plan,
                    System.currentTimeMillis() - asked);
            return;
        }
        soloMapling.ArtificialPlayer.BotConvo.ConvoQuality.timing(bot, player, plan, System.currentTimeMillis() - asked);
        // a late answer or a "brb" keeps the chat open until it is said (the sweeper ends chats idle for a minute)
        session.state.lastAt = Math.max(session.state.lastAt, System.currentTimeMillis() + plan.totalMs());
        holdNear(bot, player, session.state.lastAt); // its reply is an exchange too, typing time included
        int face = deliver != null || cue == null ? ReplyEmote.NONE
                : ReplyEmote.pick(cue.triage(), cue.outcome(), cue.asked(), line, ThreadLocalRandom.current());
        String fullDetail = detail + (said.fromBank() ? " bank#" + said.bankId() : "") + " plan=" + plan.kind();
        BotTiming.Chain chain = BotTiming.chain()
                .stopUnless(() -> sessions.containsKey(new Key(bot.getId(), player.getId())));
        java.util.List<soloMapling.ArtificialPlayer.BotConvo.HumanTiming.Part> parts = plan.parts();
        for (int i = 0; i < parts.size(); i++) {
            soloMapling.ArtificialPlayer.BotConvo.HumanTiming.Part part = parts.get(i);
            boolean last = i == parts.size() - 1;
            chain = chain.pause(part.delayMs())
                    .run(() -> {
                        if (deliver == null && bot.getMapId() == player.getMapId()) {
                            botFaceTowardsPoint(bot, player.getPosition());
                        }
                    })
                    .run(() -> {
                        soloMapling.ArtificialPlayer.BotConvo.SayDo.said(bot, player, part.text(), claimSource);
                        if (deliver != null) {
                            if (PlayerBusy.isBusy(player)) {
                                return;
                            }
                            deliver.accept(part.text());
                        } else {
                            soloMapling.ArtificialPlayer.BotConvo.SayDo.withSource(claimSource, () -> BotSpeak(bot, part.text()));
                            if (last && face != ReplyEmote.NONE) {
                                BotEmote(bot, face);
                            }
                        }
                        if ("brb".equals(part.text())) { // the timing's "brb": it really steps away until it answers
                            soloMapling.ArtificialPlayer.BotConvo.ClaimActions.start(bot,
                                    soloMapling.ArtificialPlayer.BotConvo.ClaimRules.classify("brb"));
                        }
                        session.state.lastAt = System.currentTimeMillis();
                        holdNear(bot, player, session.state.lastAt); // the line just went out: the hold runs from it
                        if (deliver == null) { // ticket 37: having answered, the bot keeps listening a while longer
                            BotChatRouting.touchAttention(player.getId(), bot.getId(), session.state.lastAt);
                        }
                        if (last) {
                            ChatStats.reply(tier, System.currentTimeMillis() - asked, bot.getName(), fullDetail);
                            soloMapling.ArtificialPlayer.BotConvo.ConvoQuality.said(bot, player, line, said, tier.name(),
                                    persona, cue == null || cue.triage() == null ? null : cue.asked(),
                                    System.currentTimeMillis() - asked);
                        }
                    });
        }
        chain.start();
    }

    /**
     * How long the bot types before a line appears: a beat to read, about 55 ms a character (with some jitter), and
     * now and then a pause as if it looked away - minus the time the brain already took. Between 300 ms and 6 s,
     * times {@code -Dsolomapling.chat.typingScale} (1.0).
     */
    static long typingDelay(String line, long alreadyMs, java.util.Random rng) {
        double perChar = 45 + rng.nextInt(25);
        long ms = 900 + (long) (perChar * line.length());
        if (rng.nextDouble() < 0.1) {
            ms += 1500 + rng.nextInt(1500);
        }
        double scale = doubleProperty("solomapling.chat.typingScale", 1.0);
        return (long) (scale * Math.max(300, Math.min(6000, ms - alreadyMs)));
    }

    private static double busyIgnoreChance() {
        return doubleProperty("solomapling.chat.busyIgnoreChance", 0.1);
    }

    private static double flagNoteMin() {
        return doubleProperty("solomapling.chat.flagNoteMin", 0.6);
    }

    private static double doubleProperty(String name, double fallback) {
        try {
            return Double.parseDouble(System.getProperty(name, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void startSweeper() {
        if (sweeperStarted) {
            return;
        }
        synchronized (BotConversation.class) {
            if (sweeperStarted) {
                return;
            }
            sweeperStarted = true;
            ExecutorServiceManager.getScheduledExecutorService().scheduleAtFixedRate(() -> {
                long now = System.currentTimeMillis();
                ChatStats.maybeSummarize(now);
                if (BotBrains.get() instanceof soloMapling.ArtificialPlayer.BotBrainSystem.ChatRouter router) {
                    router.maybeReport(); // the hourly cloud usage line
                }
                for (Map.Entry<Key, Session> entry : sessions.entrySet()) {
                    if (now - entry.getValue().state.lastAt > IDLE_MS) {
                        end(entry.getKey().botId(), entry.getKey().playerId());
                    }
                }
                if (!holds.isEmpty()) {
                    holds.entrySet().removeIf(e -> e.getValue() <= now); // a lapsed hold keeps nobody in place
                }
            }, 10, 10, TimeUnit.SECONDS);
        }
    }
}
