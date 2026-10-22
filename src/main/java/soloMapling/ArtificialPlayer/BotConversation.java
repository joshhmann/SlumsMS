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

    private record Key(int botId, int playerId) {
    }

    private static final class Session {
        final BotSM bot;
        final Character player;
        final List<BotBrain.Turn> history = new ArrayList<>();
        volatile long lastAt;
        /** How the bot answers: in map chat, or back as a whisper when that is how the player spoke (ticket 22). */
        volatile Consumer<String> deliver;

        Session(BotSM bot, Character player, long now) {
            this.bot = bot;
            this.player = player;
            this.lastAt = now;
        }
    }

    private static final Map<Key, Session> sessions = new ConcurrentHashMap<>();
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
        return start(bot, player, line, null, deliver);
    }

    /** How many lines the player has said in their current chat with this bot (0 when they are not talking). */
    public static int playerLines(int botCharId, int playerId) {
        Session session = sessions.get(new Key(botCharId, playerId));
        if (session == null || System.currentTimeMillis() - session.lastAt > IDLE_MS) {
            return 0;
        }
        synchronized (session.history) {
            return (int) session.history.stream().filter(t -> t.role() == BotBrain.Role.USER).count();
        }
    }

    private static boolean start(BotSM bot, Character player, String line, Runnable fallback, Consumer<String> deliver) {
        if (bot == null || bot.getChr() == null || player == null) {
            return false;
        }
        startSweeper();
        long now = System.currentTimeMillis();
        Session session = sessions.computeIfAbsent(new Key(bot.getChr().getId(), player.getId()),
                k -> new Session(bot, player, now));
        session.lastAt = now;
        session.deliver = deliver;
        List<BotBrain.Turn> history;
        synchronized (session.history) {
            session.history.add(BotBrain.Turn.user(line));
            while (session.history.size() > HISTORY_TURNS) {
                session.history.remove(0);
            }
            history = new ArrayList<>(session.history);
        }
        // Off the caller's thread: the first recall of a pair reads the database.
        ExecutorServiceManager.runAsync(() -> {
            try {
                ask(session, history, line, now, fallback);
            } catch (RuntimeException | LinkageError e) {
                log.warn("Bot chat failed for {}", bot.getChr().getName(), e);
            }
        });
        return true;
    }

    /** Opens a chat without a line yet (the player named the bot): their next lines go to it. */
    public static void open(BotSM bot, Character player) {
        if (bot == null || bot.getChr() == null || player == null || !BotBrains.get().isLive()) {
            return;
        }
        startSweeper();
        long now = System.currentTimeMillis();
        sessions.computeIfAbsent(new Key(bot.getChr().getId(), player.getId()), k -> new Session(bot, player, now)).lastAt = now;
    }

    /** Whether this player is mid-chat with this bot. */
    public static boolean isTalking(int botCharId, int playerId) {
        Session session = sessions.get(new Key(botCharId, playerId));
        return session != null && System.currentTimeMillis() - session.lastAt <= IDLE_MS;
    }

    /** The bot this player is mid-chat with, or -1 (the most recent one, if several). */
    public static int partnerOf(int playerId) {
        long now = System.currentTimeMillis();
        Session latest = null;
        for (Map.Entry<Key, Session> entry : sessions.entrySet()) {
            Session s = entry.getValue();
            if (entry.getKey().playerId() == playerId && now - s.lastAt <= IDLE_MS
                    && (latest == null || s.lastAt > latest.lastAt)) {
                latest = s;
            }
        }
        return latest == null ? -1 : latest.bot.getChr().getId();
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
    private static void ask(Session session, List<BotBrain.Turn> history, String line, long asked, Runnable fallback) {
        Character bot = session.bot.getChr();
        Character player = session.player;
        int slot = bot.getId() - BotStore.BOT_ID_BASE;
        boolean whisper = session.deliver != null;
        ChatTriage.Result triage = ChatTriage.classify(line, ChatDecide.get());
        // Ticket 58: a kind line warms the bot to the player, a rude one cools it (rules, then the cheap Decide model).
        soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityReactions.judgeLine(bot, player, line, triage);

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
        BotChatActions.Result action = triage.intent() == ChatTriage.Intent.NONE ? BotChatActions.Result.NONE
                : BotChatActions.act(session.bot, player, triage.intent());
        Cue cue = new Cue(triage, action.outcome(), line);
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
                        deliver(session, ScriptedReplies.pick(ScriptedReplies.TALK), asked, ChatStats.Tier.SCRIPTED,
                                "filler whisper", cue);
                    } else {
                        int face = ReplyEmote.forFiller(line);
                        BotTiming.chain().pause(400 + ThreadLocalRandom.current().nextInt(900))
                                .run(() -> BotEmote(bot, face)).start();
                        ChatStats.reply(ChatStats.Tier.EMOTE, System.currentTimeMillis() - asked, bot.getName(), "filler");
                    }
                    return;
                }
                case THANKS, BYE -> {
                    deliver(session, ScriptedReplies.forKind(triage.kind()), asked, ChatStats.Tier.SCRIPTED,
                            triage.kind().name().toLowerCase(), cue);
                    return;
                }
                case GREETING -> {
                    if (botHasSpoken(session)) { // a greeting mid-chat is small talk; the first one gets a real reply
                        deliver(session, ScriptedReplies.forKind(triage.kind()), asked, ChatStats.Tier.SCRIPTED,
                                "greeting", cue);
                        return;
                    }
                }
                default -> {
                    if (!whisper && "grinding".equals(activity) && botHasSpoken(session)
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
        String system = systemPrompt(session, slot, activity, wary, recognise, action.fact(), asked, whisper);
        String detail = triage.describe() + (action.outcome() == BotChatActions.Outcome.NONE ? ""
                : " -> " + action.outcome().name().toLowerCase());
        BotBrains.get().complete(player.getId(), BotChatPrompt.conversation(system, history)).thenAccept(reply -> {
            if (!sessions.containsKey(new Key(bot.getId(), player.getId()))) {
                return; // the chat ended while the brain was thinking
            }
            if (reply.isPresent() && action.fits(reply.get())) {
                deliver(session, reply.get(), asked, ChatStats.Tier.LLM, detail, cue);
                return;
            }
            if (reply.isPresent()) {
                // The model ignored what the game did ("sure" to a declined party): the scripted line says it right.
                deliver(session, action.scripted(), asked, ChatStats.Tier.SCRIPTED, detail + ", llm reply did not fit: "
                        + reply.get(), cue);
                return;
            }
            if (action.scripted() != null) {
                deliver(session, action.scripted(), asked, ChatStats.Tier.SCRIPTED, detail + ", llm fell back", cue);
            } else if (fallback != null) {
                fallback.run();
                ChatStats.reply(ChatStats.Tier.SCRIPTED, System.currentTimeMillis() - asked, bot.getName(),
                        detail + ", llm fell back to the caller's script");
            } else {
                String scripted = recognise && triage.kind() == ChatTriage.Kind.GREETING
                        ? BotRecognition.scriptedHello(player.getName(), ThreadLocalRandom.current())
                        : ScriptedReplies.forKind(triage.kind());
                deliver(session, scripted, asked, ChatStats.Tier.SCRIPTED, detail + ", llm fell back", cue);
            }
        });
    }

    /**
     * The system prompt for a reply: persona, live state, memories, recent map chat, the action's outcome, the
     * merchant's stock (ticket 26), and whether to greet the player as someone the bot knows.
     */
    private static String systemPrompt(Session session, int slot, String activity, boolean wary, boolean recognise,
                                       String outcome, long now, boolean whisper) {
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
                whisper ? List.of() : BotChatRouting.recentOnMap(bot.getMapId(), now), outcome)
                .with(stock == null ? null : stock.fact(), recognise);
        return BotChatPrompt.system(context, persona, traits == null ? 0.4 : traits.chattiness())
                + soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityReactions.promptLine(bot.getId(), player) // ticket 58: friend or beef
                + soloMapling.ArtificialPlayer.BotCoupleSystem.BotCouples.promptLine(bot.getId()) // ticket 58: a partner
                + soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.promptLine(bot.getId()); // 1.5.0: its guild
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
                if (!BotBrains.get().isLive()) {
                    deliver(session, BotRecognition.scriptedHello(player.getName(), ThreadLocalRandom.current()), now,
                            ChatStats.Tier.SCRIPTED, "recognised on sight", cue);
                    return;
                }
                BotRoster.Activity doing = bot.activitySnapshot();
                String activity = BotChatPrompt.activityPhrase(doing == null ? null : doing.name());
                String system = systemPrompt(session, slot, activity, false, true, null, now, false)
                        + " Nobody has said anything yet: you just noticed " + player.getName()
                        + " near you. Write your first line to them.";
                List<BotBrain.Turn> prompt = BotChatPrompt.conversation(system,
                        List.of(BotBrain.Turn.user("(" + player.getName() + " is standing near you)")));
                BotBrains.get().complete(player.getId(), prompt).thenAccept(reply -> {
                    if (sessions.get(key) != session) {
                        return; // the chat moved on or ended while the brain was thinking
                    }
                    deliver(session, reply.orElseGet(() -> BotRecognition.scriptedHello(player.getName(),
                                    ThreadLocalRandom.current())), now,
                            reply.isPresent() ? ChatStats.Tier.LLM : ChatStats.Tier.SCRIPTED,
                            "recognised on sight" + (reply.isPresent() ? "" : ", llm fell back"), cue);
                });
            } catch (RuntimeException | LinkageError e) {
                log.warn("Bot greeting failed for {}", chr.getName(), e);
            }
        });
        return true;
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
        deliver(session, ScriptedReplies.brushOff(triage.con()), asked, ChatStats.Tier.FLAGGED,
                kind + "/" + triage.conSource().name().toLowerCase(), new Cue(triage, BotChatActions.Outcome.NONE, line));
    }

    private static boolean botHasSpoken(Session session) {
        synchronized (session.history) {
            return session.history.stream().anyMatch(t -> t.role() == BotBrain.Role.ASSISTANT);
        }
    }

    /** What a reply answers, for the face that goes with it. */
    private record Cue(ChatTriage.Result triage, BotChatActions.Outcome outcome, String asked) {
    }

    /**
     * Types a line at a human pace, then says it (map chat or whisper) and records its tier. In map chat the bot
     * sometimes makes a fitting face as the bubble appears ({@link ReplyEmote}).
     */
    private static void deliver(Session session, String line, long asked, ChatStats.Tier tier, String detail, Cue cue) {
        Character bot = session.bot.getChr();
        Character player = session.player;
        synchronized (session.history) {
            session.history.add(BotBrain.Turn.assistant(line));
        }
        long typing = typingDelay(line, System.currentTimeMillis() - asked, ThreadLocalRandom.current());
        Consumer<String> deliver = session.deliver;
        int face = deliver != null || cue == null ? ReplyEmote.NONE
                : ReplyEmote.pick(cue.triage(), cue.outcome(), cue.asked(), line, ThreadLocalRandom.current());
        BotTiming.chain()
                .stopUnless(() -> sessions.containsKey(new Key(bot.getId(), player.getId())))
                .pause(typing)
                .run(() -> {
                    if (deliver == null && bot.getMapId() == player.getMapId()) {
                        botFaceTowardsPoint(bot, player.getPosition());
                    }
                })
                .run(() -> {
                    if (deliver != null) {
                        deliver.accept(line);
                    } else {
                        BotSpeak(bot, line);
                        if (face != ReplyEmote.NONE) {
                            BotEmote(bot, face);
                        }
                    }
                    session.lastAt = System.currentTimeMillis();
                    if (deliver == null) { // ticket 37: having answered, the bot keeps listening a while longer
                        BotChatRouting.touchAttention(player.getId(), bot.getId(), session.lastAt);
                    }
                    ChatStats.reply(tier, System.currentTimeMillis() - asked, bot.getName(), detail);
                })
                .start();
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
                for (Map.Entry<Key, Session> entry : sessions.entrySet()) {
                    if (now - entry.getValue().lastAt > IDLE_MS) {
                        end(entry.getKey().botId(), entry.getKey().playerId());
                    }
                }
            }, 10, 10, TimeUnit.SECONDS);
        }
    }
}
