package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
import soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Ask;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 1.6.1 say-do for bot talk (ticket 114: bots ask each other to party, come along, help, trade, "then they really do
 * it"): every yes a bot gives in a talk is a promise, judged by what the bot then does within its window
 * ({@link #windowMs}): a party ask is backed when the two are in one party, a come-along when the bot reached the town
 * (or is on its trip there when the window ends), help when the helper partied with the asker, a deal or a story when
 * the act went through. The line claims ({@link SayDo}) judge words; this judges the answers. Memory only, a few
 * dozen open at a time, resolved on the talk tick ({@link #resolveDue}); the counts go to {@code bot-life.json}
 * (botTalk.promises) and auto-QA t-bt-3 wants at least {@link #MIN_RATE} of them backed.
 */
public final class TalkPromises {

    /** The share of yes answers that must be carried out (auto-QA t-bt-3). */
    public static final double MIN_RATE = 0.8;
    static final int MAX_OPEN = 2_000;
    static final int EXAMPLES = 20;

    /** How long the act may take after the yes (the talk's own lines take up to a minute of it). */
    public static long windowMs(Ask ask) {
        return switch (ask) {
            case COME_ALONG -> TimeUnit.MINUTES.toMillis(5);
            default -> TimeUnit.MINUTES.toMillis(2);
        };
    }

    /** Where a bot is now: its map, its party (0: none), the map its life trip heads for (-1: none); null offline. */
    public record Where(int mapId, int partyId, int heading) {
    }

    /** The game as the judge reads it (tests pass a fake). */
    @FunctionalInterface
    public interface World {
        Where where(int charId);
    }

    /** One yes: who promised what to whom, and (for a deal or a story) whether the act went through. */
    static final class Promise {
        final int talkId;
        final Ask ask;
        final int botId;
        final String botName;
        final int askerId;
        final String askerName;
        final int town;
        final long at;
        final long due;
        volatile Boolean settled;
        volatile String settledWhy;

        Promise(int talkId, Ask ask, int botId, String botName, int askerId, String askerName, int town, long at) {
            this.talkId = talkId;
            this.ask = ask;
            this.botId = botId;
            this.botName = botName;
            this.askerId = askerId;
            this.askerName = askerName;
            this.town = town;
            this.at = at;
            this.due = at + windowMs(ask);
        }
    }

    /** A verdict: backed, and why. */
    public record Verdict(boolean backed, String why) {
    }

    private static final List<Promise> open = new ArrayList<>();
    private static final Map<Ask, long[]> byAsk = new EnumMap<>(Ask.class);
    private static final Deque<String> unbacked = new ArrayDeque<>();
    private static volatile World world = TalkPromises::gameWhere;

    private TalkPromises() {
    }

    /** Test seam: the world the judge reads (null: the game). */
    static void useForTest(World w) {
        world = w == null ? TalkPromises::gameWhere : w;
        resetForTest();
    }

    static synchronized void resetForTest() {
        open.clear();
        byAsk.clear();
        unbacked.clear();
    }

    /** A bot said yes to {@code ask} from {@code asker} in talk {@code talkId}; {@code town} for a come-along. */
    static synchronized void promised(int talkId, Ask ask, Character bot, Character asker, int town, long now) {
        if (ask == null || bot == null || asker == null || open.size() >= MAX_OPEN) {
            return;
        }
        open.add(new Promise(talkId, ask, bot.getId(), bot.getName(), asker.getId(), asker.getName(), town, now));
    }

    /** The act of talk {@code talkId} went through or not (a deal, a story: nothing to see later). */
    static synchronized void acted(int talkId, Ask ask, boolean ok, String why) {
        if (ask != Ask.TRADE && ask != Ask.GOSSIP) {
            return; // a party or a trip is judged by what the bots then do
        }
        for (Promise p : open) {
            if (p.talkId == talkId && p.ask == ask) {
                p.settled = ok;
                p.settledWhy = why;
            }
        }
    }

    /** Judges the open promises that are settled or due; returns the verdicts given (for tests and the trace). */
    static List<Verdict> resolveDue(long now) {
        List<Promise> mine;
        synchronized (TalkPromises.class) {
            if (open.isEmpty()) {
                return List.of();
            }
            mine = new ArrayList<>(open);
        }
        List<Verdict> out = new ArrayList<>();
        List<Promise> done = new ArrayList<>();
        for (Promise p : mine) {
            Verdict v;
            try {
                v = judge(p, now);
            } catch (RuntimeException | LinkageError e) {
                v = now >= p.due ? new Verdict(false, "could not be read") : null;
            }
            if (v != null) {
                out.add(v);
                done.add(p);
                record(p, v);
            }
        }
        if (!done.isEmpty()) {
            synchronized (TalkPromises.class) {
                open.removeAll(done);
            }
        }
        return out;
    }

    /** A promise's verdict now, or null to wait. */
    static Verdict judge(Promise p, long now) {
        boolean over = now >= p.due;
        if (p.settled != null) {
            return new Verdict(p.settled, p.settledWhy == null ? (p.settled ? "done" : "not done") : p.settledWhy);
        }
        if (p.ask == Ask.TRADE || p.ask == Ask.GOSSIP) {
            return over ? new Verdict(false, "the act never ran") : null;
        }
        Where me = world.where(p.botId);
        if (me == null) {
            return new Verdict(false, "went offline");
        }
        switch (p.ask) {
            case PARTY, HELP -> {
                Where asker = world.where(p.askerId);
                if (asker != null && me.partyId() != 0 && me.partyId() == asker.partyId()) {
                    return new Verdict(true, "in one party with " + p.askerName);
                }
                return over ? new Verdict(false, "no party with " + p.askerName) : null;
            }
            case COME_ALONG -> {
                if (me.mapId() == p.town) {
                    return new Verdict(true, "reached " + p.town);
                }
                if (!over) {
                    return null;
                }
                return me.heading() == p.town ? new Verdict(true, "on its way to " + p.town)
                        : new Verdict(false, "never went to " + p.town + " (on " + me.mapId() + ")");
            }
            default -> {
                return new Verdict(true, "not judged");
            }
        }
    }

    private static synchronized void record(Promise p, Verdict v) {
        long[] c = byAsk.computeIfAbsent(p.ask, k -> new long[2]);
        c[0]++;
        if (v.backed()) {
            c[1]++;
        } else {
            unbacked.addLast(p.botName + " said yes to " + p.askerName + "'s " + p.ask.name().toLowerCase(Locale.ROOT)
                    + ": " + v.why());
            while (unbacked.size() > EXAMPLES) {
                unbacked.pollFirst();
            }
        }
    }

    /** yes answers judged, backed, the rate, per ask, open, and the latest unbacked ones. */
    public static synchronized Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        long yes = 0, backed = 0;
        Map<String, Object> per = new LinkedHashMap<>();
        for (Map.Entry<Ask, long[]> e : byAsk.entrySet()) {
            yes += e.getValue()[0];
            backed += e.getValue()[1];
            per.put(e.getKey().name().toLowerCase(Locale.ROOT), rate(e.getValue()[0], e.getValue()[1]));
        }
        out.put("yes", yes);
        out.put("backed", backed);
        out.put("rate", yes == 0 ? null : Math.round(1000.0 * backed / yes) / 1000.0);
        out.put("byAsk", per);
        out.put("open", open.size());
        out.put("unbacked", List.copyOf(unbacked));
        return out;
    }

    private static Map<String, Object> rate(long yes, long backed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("yes", yes);
        m.put("backed", backed);
        m.put("rate", yes == 0 ? null : Math.round(1000.0 * backed / yes) / 1000.0);
        return m;
    }

    private static Where gameWhere(int id) {
        BotSM sm = CharacterStorage.getBotById(id);
        Character c = sm == null ? null : sm.getChr();
        if (c == null || c.getMap() == null) {
            return null;
        }
        int heading = sm instanceof TrainingBot tb ? tb.watchTarget() : -1;
        return new Where(c.getMapId(), c.getParty() == null ? 0 : c.getParty().getId(), heading);
    }
}
