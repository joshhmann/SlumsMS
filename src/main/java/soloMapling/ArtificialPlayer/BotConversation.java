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
        /** Ticket 59: who the LLM call is for (a player's own line, or buddy/party channel chat). */
        volatile soloMapling.ArtificialPlayer.BotBrainSystem.Priority priority =
                soloMapling.ArtificialPlayer.BotBrainSystem.Priority.PLAYER;

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
        return start(bot, player, line, null, deliver, soloMapling.ArtificialPlayer.BotBrainSystem.Priority.PARTNER);
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
        return start(bot, player, line, fallback, deliver, soloMapling.ArtificialPlayer.BotBrainSystem.Priority.PLAYER);
    }

    private static boolean start(BotSM bot, Character player, String line, Runnable fallback, Consumer<String> deliver,
                                 soloMapling.ArtificialPlayer.BotBrainSystem.Priority priority) {
        if (bot == null || bot.getChr() == null || player == null) {
            return false;
        }
        startSweeper();
        long now = System.currentTimeMillis();
        Session session = sessions.computeIfAbsent(new Key(bot.getChr().getId(), player.getId()),
                k -> new Session(bot, player, now));
        session.lastAt = now;
        session.deliver = deliver;
        session.priority = priority;
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

    /** Ticket 59: whether this bot is mid-chat with any player (a bot thread leaves it alone). */
    public static boolean hasAnyChat(int botCharId) {
        long now = System.currentTimeMillis();
        for (Map.Entry<Key, Session> e : sessions.entrySet()) {
            if (e.getKey().botId() == botCharId && now - e.getValue().lastAt <= IDLE_MS) {
                return true;
            }
        }
        return false;
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
        BotChatActions.Result action = triage.intent() != ChatTriage.Intent.NONE
                ? BotChatActions.act(session.bot, player, triage.intent(), line)
                // ticket 59: come, help, guide, buff, buddy and the moods - the server decides and does it
                : soloMapling.ArtificialPlayer.BotConvo.ConvoActions.onIntent(session.bot, player, read, line);
        Cue cue = new Cue(triage, action.outcome(), line, action.mustMention());
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
                    deliver(session, bank(session, soloMapling.ArtificialPlayer.BotConvo.ConvoLines.key(triage.kind(), line),
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
        String system = systemPrompt(session, slot, activity, wary, recognise, action.fact(), asked, whisper);
        if (action.outcome() == BotChatActions.Outcome.NONE && soloMapling.ArtificialPlayer.BotConvo.ConvoSwitches.on("contract")) {
            system += " " + soloMapling.ArtificialPlayer.BotConvo.ConvoContract.instructions();
        }
        String detail = triage.describe() + " " + read.describe() + (action.outcome() == BotChatActions.Outcome.NONE ? ""
                : " -> " + action.outcome().name().toLowerCase());
        BotBrains.get().reply(session.priority, player.getId(), BotChatPrompt.conversation(system, history))
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
                soloMapling.ArtificialPlayer.BotConvo.ConvoLines.Line scripted = recognise
                        && triage.kind() == ChatTriage.Kind.GREETING
                        ? bank(session, "recognise", read, BotRecognition.scriptedHello(player.getName(),
                        ThreadLocalRandom.current()))
                        : bank(session, soloMapling.ArtificialPlayer.BotConvo.ConvoLines.key(triage.kind(), line), read,
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
        return BotChatPrompt.system(context, persona, traits == null ? 0.4 : traits.chattiness(), card, voiceLine)
                + soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityReactions.promptLine(bot.getId(), player) // ticket 58: friend or beef
                + soloMapling.ArtificialPlayer.BotCoupleSystem.BotCouples.promptLine(bot.getId()) // ticket 58: a partner
                + soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.promptLine(bot.getId()) // 1.5.0: its guild
                + soloMapling.ArtificialPlayer.BotDramaSystem.BotDrama.promptLine(bot.getId(), player); // 1.5.0: romance, beef
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
                BotBrains.get().reply(soloMapling.ArtificialPlayer.BotBrainSystem.Priority.AMBIENT, player.getId(), prompt)
                        .thenAccept(contract -> {
                    java.util.Optional<String> raw = contract.map(soloMapling.ArtificialPlayer.BotConvo.ConvoContract.Reply::text);
                    java.util.Optional<String> reply = raw.filter(l ->  // 1.5.0: no flirt without @romance on
                            soloMapling.ArtificialPlayer.BotDramaSystem.BotDrama.replyAllowed(chr, player, l));
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
        String line = soloMapling.ArtificialPlayer.BotConvo.ConvoSwitches.on("persona")
                ? soloMapling.ArtificialPlayer.BotConvo.VoiceStyler.style(said.text(), persona, slot,
                java.util.List.of(player.getName().toLowerCase(java.util.Locale.ROOT),
                        bot.getName().toLowerCase(java.util.Locale.ROOT), cue == null || cue.protect() == null ? ""
                                : cue.protect()))
                : said.text();
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
        if (plan.ignored()) {
            ChatStats.reply(ChatStats.Tier.IGNORED, System.currentTimeMillis() - asked, bot.getName(), "busy fighting");
            soloMapling.ArtificialPlayer.BotConvo.ConvoQuality.timing(bot, player, plan);
            return;
        }
        soloMapling.ArtificialPlayer.BotConvo.ConvoQuality.timing(bot, player, plan);
        // a late answer or a "brb" keeps the chat open until it is said (the sweeper ends chats idle for a minute)
        session.lastAt = Math.max(session.lastAt, System.currentTimeMillis() + plan.totalMs());
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
                        if (deliver != null) {
                            if (PlayerBusy.isBusy(player)) {
                                return;
                            }
                            deliver.accept(part.text());
                        } else {
                            BotSpeak(bot, part.text());
                            if (last && face != ReplyEmote.NONE) {
                                BotEmote(bot, face);
                            }
                        }
                        session.lastAt = System.currentTimeMillis();
                        if (deliver == null) { // ticket 37: having answered, the bot keeps listening a while longer
                            BotChatRouting.touchAttention(player.getId(), bot.getId(), session.lastAt);
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
                for (Map.Entry<Key, Session> entry : sessions.entrySet()) {
                    if (now - entry.getValue().lastAt > IDLE_MS) {
                        end(entry.getKey().botId(), entry.getKey().playerId());
                    }
                }
            }, 10, 10, TimeUnit.SECONDS);
        }
    }
}
