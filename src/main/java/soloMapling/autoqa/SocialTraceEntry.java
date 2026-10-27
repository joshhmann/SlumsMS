package soloMapling.autoqa;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 1.6.0 (owner 2026-10-26): one bot<->bot social episode the {@link SocialTrace} follows - a bot thread, or a drama
 * event between two bots - from why it started to whether a third bot repeated it. The recorder fills the raw facts in
 * as they happen (all under the entry's own lock); {@link SocialTraceJudge} turns them into one verdict per
 * {@link Stage} when the entry closes. Nothing here acts on the game.
 */
public final class SocialTraceEntry {

    /** The pipeline stages every entry is judged on, in order. */
    public enum Stage { TRIGGER, COMPOSE, DELIVER, SENSE, DECIDE, ACT, REMEMBER, SPREAD }

    /** A stage's verdict: it worked, it broke, no code does this step, or the step does not apply. */
    public enum Status {
        OK("ok"), FAIL("fail"), NONE("none"), NA("n/a");

        public final String json;

        Status(String json) {
            this.json = json;
        }
    }

    public record Verdict(Status status, String reason, Map<String, Object> data) {
        public Verdict(Status status, String reason) {
            this(status, reason, Map.of());
        }
    }

    /** A line a bot said, with the bot's chat voice (the stiffness rule spares a "proper" voice). */
    public record Line(long at, int botId, String who, String text, String voice) {
    }

    /** Something one of the two bots did inside the follow-up window: a life decision, a party, a trade, a reply... */
    public record Event(long at, int botId, String kind, String text) {
    }

    /** Both bots' feelings about each other at one moment ({@code BotBonds}). */
    public record Bond(double ab, double ba, String stateAb, String stateBa) {
    }

    final String id;
    /** "thread" or "drama". */
    final String kind;
    /** A thread's topic (GOSSIP, LFG ...) or a drama event's kind (ROAST, BREAKUP ...). */
    final String topic;
    final long startedAt;
    final int mapId;
    final int channel;
    final int aId;
    final int bId;
    final String aName;
    final String bName;
    /** The real players on the map when it started (observers marked "(observer)"). */
    final List<String> watchers;

    // TRIGGER
    String trigger;
    String about;
    /** The lower of the two bots' levels. */
    int minLevel;
    /** The recruiter's (a's) level: LFG's PQ band rule (1.6.0: the PQ is the recruiter's own). */
    int aLevel;
    /** 1.6.0 LFG: the decision ("party up for Kerning PQ"), or why the other bot said no; and why a party failed. */
    String lfgDecision;
    String lfgDecline;
    String lfgFail;
    String factKind;
    String factAbout;
    List<String> factNames = List.of();
    boolean factPerson;

    // COMPOSE
    String source;
    long composeMs = -1;
    String fallback;
    int modelLines = -1;
    final List<String> nameGuard = new ArrayList<>();

    // DELIVER
    int planned = -1;
    long deliverBy;
    final List<Line> lines = new ArrayList<>();
    final List<String> dropped = new ArrayList<>();
    final List<String> repeats = new ArrayList<>();

    // REMEMBER
    Bond before;
    Bond after;
    final List<String> remembered = new ArrayList<>();

    // ACT
    final List<Event> events = new ArrayList<>();
    /** Read at close: where the two bots are and whether they share a party. */
    Map<String, Object> endState = Map.of();

    // SPREAD
    /** What a third bot would repeat (a gossip fact's story, a drama's subject); null: nothing to spread. */
    String spreadKey;
    /** The people the story is about (a later thread that names them all repeats it). */
    List<String> spreadNames = List.of();
    final List<String> spread = new ArrayList<>();

    long closesAt;
    EnumMap<Stage, Verdict> verdicts;

    SocialTraceEntry(String id, String kind, String topic, long startedAt, int mapId, int channel, int aId, String aName,
                     int bId, String bName, List<String> watchers) {
        this.id = id;
        this.kind = kind;
        this.topic = topic;
        this.startedAt = startedAt;
        this.mapId = mapId;
        this.channel = channel;
        this.aId = aId;
        this.aName = aName;
        this.bId = bId;
        this.bName = bName;
        this.watchers = watchers == null ? List.of() : List.copyOf(watchers);
    }

    boolean involves(int botId) {
        return botId == aId || botId == bId;
    }

    String nameOf(int botId) {
        return botId == aId ? aName : botId == bId ? bName : String.valueOf(botId);
    }

    /** The closed entry as one JSON object (UTC ISO times; the full text of every line). */
    synchronized Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("kind", kind);
        m.put("topic", topic);
        m.put("startedAt", SocialTraceJudge.iso(startedAt));
        m.put("closedAt", SocialTraceJudge.iso(closesAt));
        m.put("map", mapId);
        m.put("channel", channel);
        m.put("a", Map.of("id", aId, "name", aName));
        m.put("b", Map.of("id", bId, "name", bName));
        m.put("watchers", watchers);
        m.put("about", about);
        if (factKind != null) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("kind", factKind);
            f.put("about", factAbout);
            f.put("names", factNames);
            f.put("person", factPerson);
            m.put("fact", f);
        }
        List<Object> said = new ArrayList<>();
        for (Line l : lines) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("at", SocialTraceJudge.iso(l.at()));
            x.put("offsetMs", l.at() - startedAt);
            x.put("who", l.who());
            x.put("text", l.text());
            said.add(x);
        }
        m.put("lines", said);
        Map<String, Object> stages = new LinkedHashMap<>();
        if (verdicts != null) {
            for (Map.Entry<Stage, Verdict> e : verdicts.entrySet()) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("status", e.getValue().status().json);
                v.put("reason", e.getValue().reason());
                v.putAll(e.getValue().data());
                stages.put(e.getKey().name(), v);
            }
        }
        m.put("stages", stages);
        return m;
    }
}
