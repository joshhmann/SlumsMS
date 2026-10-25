package soloMapling.ArtificialPlayer.BotMessagingSystem;

import soloMapling.ArtificialPlayer.BotConvo.BotThreads;
import soloMapling.ArtificialPlayer.BotConvo.ConvoEntities;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/**
 * 1.5.0 rc6, owner's routing order for a player's public line that names no bot exactly. Pure: the dispatcher hands
 * it the map's chat log, where everyone stands and the live thread, and gets back which bot answers and why.
 *
 * <ol>
 *   <li><b>A name wins anywhere</b> (the dispatcher checks it first: {@link BotAddressee}).</li>
 *   <li><b>Facing:</b> the player faces a close bot ({@link #FACE_PX} px or less, about the same height) inside the
 *   hearing bubble, and it is the nearest bot that way.</li>
 *   <li><b>A clear reply</b> to a bot line said in the bubble: the line shares a word or an entity (map, mob, item,
 *   a name) with it, or answers a question asked in the last 60 s. When the line fits a live thread with a
 *   participant in the bubble, the thread answers (its last speaker, with the thread in mind). Sub-steps, in order:
 *   the thread's lines share the most words; a bot line shares words; a recent question (newer than the chat the
 *   player was in); a short acceptance of the last speaker's ask; the bot the player is already talking to; the
 *   thread's last line was under 45 s ago.</li>
 *   <li><b>Otherwise the closest bot in the bubble</b> answers.</li>
 *   <li><b>Nobody in the bubble:</b> no bot answers - it is just public chat.</li>
 * </ol>
 *
 * <p>The hearing bubble is {@code -Dsolomapling.convo.hearPx} (400) px either side and {@code hearPy} (150) px up or
 * down. Bots outside it are never chosen for an unnamed line. Every decision carries its candidates and their signals
 * for {@link RouteLog}.
 */
public final class ChatRoute {

    /** Bot speakers this recent are candidates in the log. */
    public static final long SPEAKER_MS = TimeUnit.SECONDS.toMillis(90);
    /** A bot line can be clearly replied to this long after it was said. */
    public static final long REPLY_MS = TimeUnit.SECONDS.toMillis(60);
    /** A thread takes a line that fits it only by time this soon after its last line. */
    public static final long THREAD_JOIN_MS = TimeUnit.SECONDS.toMillis(45);
    /** Facing: at most this far in front of the player... */
    public static final int FACE_PX = 200;
    /** ...and at most this far above or below (the same platform, roughly). */
    public static final int FACE_PY = 50;

    public static int hearPx() {
        return Integer.getInteger("solomapling.convo.hearPx", 400);
    }

    public static int hearPy() {
        return Integer.getInteger("solomapling.convo.hearPy", 150);
    }

    /** Which step chose the bot (the label goes in the log). */
    public enum Step {
        NAME("1-name"), NAME_ASK("1-name-ask"), FACING("2-facing"), THREAD_WORDS("3-reply-thread"),
        REPLY_WORDS("3-reply-words"), REPLY_QUESTION("3-reply-question"), REPLY_ACCEPT("3-reply-accept"),
        ONGOING("3-ongoing-chat"), THREAD_RECENT("3-thread-recent"), CLOSEST("4-closest"), NONE("5-none"),
        /** Paths outside the owner's order that stay ahead of it (a yes to "u talking to me?", a menu chat...). */
        OTHER("other");

        public final String label;

        Step(String label) {
            this.label = label;
        }

        public boolean thread() {
            return this == THREAD_WORDS || this == THREAD_RECENT;
        }
    }

    /** A bot on the player's map. {@code talks}: it chats like a player (an unnamed line can go to it). */
    public record Bot(int id, String name, int x, int y, boolean talks) {
    }

    /**
     * The player and their line. {@code facingLeft} null when facing cannot be read. {@code talkingTo} is the bot
     * still listening to this player (-1 none), {@code talkingSince} when that chat last moved.
     */
    public record Player(int id, String name, String line, int x, int y, Boolean facingLeft, int talkingTo,
                         long talkingSince) {
    }

    /** One candidate and its signals. {@code key} is the bot's name, or "thread:<id>". */
    public record Candidate(String key, int botId, double nameScore, long secondsAgo, int sharedWords,
                            int sharedEntities, boolean question, boolean addressedPlayer, boolean inThread, int dx,
                            int dy, int distance, boolean inBubble, boolean facing, String tier, String line) {
    }

    /** The answer: the step, the bot that answers (-1 none), the thread (when a thread step), the line it answers. */
    public record Decision(Step step, int botId, BotThreads.View thread, MapChatLog.Line answers,
                           List<Candidate> candidates, boolean question, int length, List<String> entities) {

        public Decision as(Step other, int bot) {
            return new Decision(other, bot, null, null, candidates, question, length, entities);
        }

        public String chosenKey() {
            if (botId < 0) {
                return null;
            }
            if (thread != null) {
                return "thread:" + thread.id();
            }
            for (Candidate c : candidates) {
                if (c.botId() == botId && !c.key().startsWith("thread:")) {
                    return c.key();
                }
            }
            return String.valueOf(botId);
        }
    }

    private ChatRoute() {
    }

    static boolean inBubble(int dx, int dy) {
        return Math.abs(dx) <= hearPx() && Math.abs(dy) <= hearPy();
    }

    /**
     * The one hearing rule: whether a bot standing here would hear a player standing there. Routing applies it to a
     * bot's own lines and thread selection applies it to a thread's two speakers, so the two can never disagree about
     * who is in earshot.
     */
    public static boolean inBubble(Bot bot, int px, int py) {
        return bot != null && inBubble(bot.x() - px, bot.y() - py);
    }

    /** Per-bot working state. */
    private static final class Seen {
        final Bot bot;
        final int dx;
        final int dy;
        final boolean bubble;
        MapChatLog.Line newest;
        MapChatLog.Line best;
        int bestWords;
        int bestEntities;

        Seen(Bot bot, Player p) {
            this.bot = bot;
            this.dx = bot.x() - p.x();
            this.dy = bot.y() - p.y();
            this.bubble = inBubble(dx, dy);
        }

        int distance() {
            return (int) Math.round(Math.hypot(dx, dy));
        }

        boolean hears() {
            return bubble && bot.talks();
        }
    }

    /**
     * The routing for an unnamed line (steps 2 to 5). {@code lines}: the map's chat log ({@link MapChatLog#recent}),
     * {@code bots}: every visible bot on the map by id, {@code thread}: the map's live thread or null, {@code tier}:
     * a bot's feeling about the player (only asked for logged candidates), {@code named}: the name match of the line
     * against every bot on the map ({@link BotAddressee#resolve}, null none) - step 1.
     */
    public static Decision decide(List<MapChatLog.Line> lines, Player p, Map<Integer, Bot> bots, BotThreads.View thread,
                                  BotAddressee.Result named, IntFunction<String> tier, long now) {
        String said = p.line() == null ? "" : p.line().trim();
        int end = lines.size();
        for (int i = lines.size() - 1; i >= 0; i--) {
            MapChatLog.Line l = lines.get(i);
            if (l.speakerId() == p.id() && l.text().equals(said)) {
                end = i; // the player's own line was logged before routing: look before it
                break;
            }
        }
        Set<String> words = MapChatLog.words(said);
        ConvoEntities.Found found = ConvoEntities.find(said);
        List<String> botNames = new ArrayList<>();
        for (Bot b : bots.values()) {
            botNames.add(b.name());
        }
        Set<String> entities = keys(found.with(ConvoEntities.players(said, botNames)));

        Map<Integer, Seen> seen = new LinkedHashMap<>();
        for (int i = end - 1; i >= 0; i--) {
            MapChatLog.Line l = lines.get(i);
            Bot b = l.bot() ? bots.get(l.speakerId()) : null;
            if (b == null || b.id() == p.id() || now - l.at() > SPEAKER_MS) {
                continue;
            }
            Seen s = seen.computeIfAbsent(b.id(), k -> new Seen(b, p));
            if (s.newest == null) {
                s.newest = l;
            }
            if (now - l.at() <= REPLY_MS) {
                int w = shared(words, MapChatLog.words(l.text()));
                int e = shared(entities, keys(ConvoEntities.find(l.text())));
                if (w + e > s.bestWords + s.bestEntities) {
                    s.best = l;
                    s.bestWords = w;
                    s.bestEntities = e;
                }
            }
        }

        // facing: the nearest bot in front, about the same height, close
        Seen faced = null;
        if (p.facingLeft() != null) {
            Seen nearest = null;
            for (Bot b : bots.values()) {
                if (b.id() == p.id()) {
                    continue;
                }
                Seen s = seen.containsKey(b.id()) ? seen.get(b.id()) : new Seen(b, p);
                boolean ahead = p.facingLeft() ? s.dx <= 0 : s.dx >= 0;
                if (ahead && Math.abs(s.dy) <= FACE_PY && (nearest == null || Math.abs(s.dx) < Math.abs(nearest.dx))) {
                    nearest = s;
                }
            }
            if (nearest != null && Math.abs(nearest.dx) <= FACE_PX && nearest.hears()) {
                faced = nearest;
                seen.putIfAbsent(faced.bot.id(), faced);
            }
        }

        // closest in the bubble
        Seen closest = null;
        for (Bot b : bots.values()) {
            if (b.id() == p.id()) {
                continue;
            }
            Seen s = seen.containsKey(b.id()) ? seen.get(b.id()) : new Seen(b, p);
            if (s.hears() && (closest == null || s.distance() < closest.distance())) {
                closest = s;
            }
        }
        if (closest != null) {
            seen.putIfAbsent(closest.bot.id(), closest);
        }
        if (p.talkingTo() >= 0 && bots.containsKey(p.talkingTo())) {
            seen.putIfAbsent(p.talkingTo(), new Seen(bots.get(p.talkingTo()), p));
        }

        // the thread
        Seen threadBot = null;
        int threadWords = 0;
        int threadEntities = 0;
        boolean threadHeard = false;
        boolean threadSubjectHit = false;
        if (thread != null) {
            Seen last = null;
            Seen any = null;
            for (int id : thread.botIds()) {
                Bot b = bots.get(id);
                if (b == null) {
                    continue;
                }
                Seen s = seen.containsKey(id) ? seen.get(id) : new Seen(b, p);
                threadHeard |= s.bubble;
                if (s.hears()) {
                    if (id == thread.lastSpeakerId()) {
                        last = s;
                    } else if (any == null) {
                        any = s;
                    }
                }
            }
            threadBot = threadHeard ? (last != null ? last : any) : null;
            if (threadBot != null) {
                Set<String> tw = new HashSet<>();
                Set<String> te = new HashSet<>();
                for (int i = 0; i < end; i++) {
                    MapChatLog.Line l = lines.get(i);
                    if (l.bot() && thread.botIds().contains(l.speakerId()) && l.at() >= thread.startedAt()) {
                        tw.addAll(MapChatLog.words(l.text()));
                        te.addAll(keys(ConvoEntities.find(l.text())));
                    }
                }
                if (thread.topic() != null) { // the thread's subject ("looking for a party (Ludi PQ)") counts too
                    Set<String> sw = MapChatLog.words(thread.topic());
                    Set<String> se = keys(ConvoEntities.find(thread.topic()));
                    // A line that names what the thread is about is a reply to the thread, even when a nearby bot's
                    // line echoes more of its words (cv-5: the QA thread's lines never said "Orbis PQ", axeperion's
                    // "come orbis" did). Without the subject a bare "who wiped?" cannot reach this.
                    threadSubjectHit = shared(words, sw) > 0 || shared(entities, se) > 0;
                    tw.addAll(sw);
                    te.addAll(se);
                }
                for (String n : thread.names()) {
                    te.add("player:" + ConvoEntities.norm(n));
                    tw.remove(n.toLowerCase(Locale.ROOT));
                }
                threadWords = shared(words, tw);
                threadEntities = shared(entities, te);
            }
        }

        // the steps
        Step step = Step.NONE;
        Seen chosen = null;
        MapChatLog.Line answers = null;
        boolean viaThread = false;
        Seen bestLine = null;
        for (Seen s : seen.values()) {
            if (s.hears() && s.best != null && s.bestWords + s.bestEntities > 0
                    && (bestLine == null || s.bestWords + s.bestEntities > bestLine.bestWords + bestLine.bestEntities
                    || (s.bestWords + s.bestEntities == bestLine.bestWords + bestLine.bestEntities
                    && s.best.at() > bestLine.best.at()))) {
                bestLine = s;
            }
        }
        Seen byName = named == null || named.best() == null || !bots.containsKey(named.best().id()) ? null
                : seen.computeIfAbsent(named.best().id(), k -> new Seen(bots.get(k), p));
        if (byName != null && named.sure()) {
            step = Step.NAME; // anywhere on the map, in the bubble or not
            chosen = byName;
        } else if (byName != null && (named.worthAsking() || named.ambiguous())) {
            step = Step.NAME_ASK;
            chosen = byName;
        } else if (faced != null) {
            step = Step.FACING;
            chosen = faced;
        } else if (threadBot != null && threadWords + threadEntities > 0 && (bestLine == null || threadSubjectHit
                || threadWords + threadEntities >= bestLine.bestWords + bestLine.bestEntities)) {
            // cv-5: a line that names the live thread's subject joins the thread even when a nearby bot's line
            // echoes more of its words. A name, facing, the player's own partner and a menu request still come first.
            step = Step.THREAD_WORDS;
            chosen = threadBot;
            viaThread = true;
        } else if (bestLine != null) {
            step = Step.REPLY_WORDS;
            chosen = bestLine;
            answers = bestLine.best;
            if (threadBot != null && thread.botIds().contains(bestLine.bot.id())) {
                chosen = threadBot; // a reply to a thread line joins the thread
                viaThread = true;
            }
        } else {
            Seen asked = null;
            Seen lastSpoke = null;
            for (Seen s : seen.values()) {
                if (!s.hears() || s.newest == null || now - s.newest.at() > REPLY_MS) {
                    continue;
                }
                if (MapChatLog.isQuestion(s.newest.text()) && (asked == null || s.newest.at() > asked.newest.at())) {
                    asked = s;
                }
                if (lastSpoke == null || s.newest.at() > lastSpoke.newest.at()) {
                    lastSpoke = s;
                }
            }
            Seen talking = p.talkingTo() < 0 ? null : seen.get(p.talkingTo());
            if (talking != null && !talking.hears()) {
                talking = null;
            }
            if (asked != null && (talking == null || asked.bot.id() == talking.bot.id()
                    || asked.newest.at() > p.talkingSince())) {
                step = Step.REPLY_QUESTION;
                chosen = asked;
                answers = asked.newest;
            } else if (lastSpoke != null && BotChatRouting.replyToLastSpeaker(said, new BotChatRouting.LastSpeaker(
                    lastSpoke.bot.id(), lastSpoke.bot.name(), lastSpoke.newest.text(), lastSpoke.newest.at())).isPresent()) {
                step = Step.REPLY_ACCEPT;
                chosen = lastSpoke;
                answers = lastSpoke.newest;
            } else if (talking != null) {
                step = Step.ONGOING;
                chosen = talking;
            } else if (threadBot != null && thread.lastLineAt() > 0 && now - thread.lastLineAt() <= THREAD_JOIN_MS) {
                step = Step.THREAD_RECENT;
                chosen = threadBot;
                viaThread = true;
            } else if (closest != null) {
                step = Step.CLOSEST;
                chosen = closest;
            }
            if (chosen != null && !viaThread && threadBot != null && answers != null
                    && thread.botIds().contains(chosen.bot.id())) {
                chosen = threadBot;
                viaThread = true;
            }
        }

        // the candidates, for the log
        List<Candidate> out = new ArrayList<>();
        for (Seen s : seen.values()) {
            MapChatLog.Line l = s.newest;
            out.add(new Candidate(s.bot.name(), s.bot.id(), nameScore(said, s.bot), l == null ? -1 : (now - l.at()) / 1000,
                    s.bestWords, s.bestEntities, l != null && MapChatLog.isQuestion(l.text()),
                    l != null && p.name() != null && !ConvoEntities.players(l.text(), List.of(p.name())).isEmpty(),
                    thread != null && thread.botIds().contains(s.bot.id()), s.dx, s.dy, s.distance(), s.bubble,
                    s == faced, tier == null ? null : tier.apply(s.bot.id()), l == null ? null : l.text()));
        }
        if (thread != null) {
            Seen t = threadBot;
            out.add(new Candidate("thread:" + thread.id(), t == null ? thread.lastSpeakerId() : t.bot.id(), 0,
                    thread.lastLineAt() <= 0 ? -1 : (now - thread.lastLineAt()) / 1000, threadWords, threadEntities,
                    false, false, true, t == null ? 0 : t.dx, t == null ? 0 : t.dy, t == null ? -1 : t.distance(),
                    threadHeard, false, null, thread.topic()));
        }
        List<String> ents = new ArrayList<>(entities);
        java.util.Collections.sort(ents);
        return new Decision(step, chosen == null ? -1 : chosen.bot.id(), viaThread ? thread : null, answers,
                List.copyOf(out), MapChatLog.isQuestion(said), said.length(), List.copyOf(ents));
    }

    static double nameScore(String line, Bot b) {
        BotAddressee.Result r = BotAddressee.resolve(line, List.of(new BotAddressee.Candidate(b.id(), b.name(), 0)));
        return r == null || r.best() == null ? 0 : Math.round(r.best().score() * 100) / 100.0;
    }

    static Set<String> keys(ConvoEntities.Found found) {
        Set<String> out = new HashSet<>();
        for (ConvoEntities.Entity e : found.all()) {
            out.add(e.kind() == ConvoEntities.Kind.PLAYER ? "player:" + ConvoEntities.norm(e.name())
                    : e.kind().name().toLowerCase(Locale.ROOT) + ":" + e.id());
        }
        return out;
    }

    private static int shared(Set<String> a, Set<String> b) {
        int n = 0;
        for (String x : a) {
            if (b.contains(x)) {
                n++;
            }
        }
        return n;
    }
}
