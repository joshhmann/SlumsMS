package soloMapling.worldevents;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** One running world event: which template, where, until when, and what it has spawned (ticket 21). */
public final class ActiveEvent {

    public final int id;
    public final EventTemplate template;
    public final EventTemplate.Spot spot;
    public final int channel;
    public final long startedAt;
    public final long endsAt;
    /** "scheduler" or the GM who started it. */
    public final String startedBy;
    /** Which bot levels react (resolved when it starts). */
    public final int minLevel;
    public final int maxLevel;

    volatile String mapName = "";
    volatile String regionName = "";
    /** Boss name (sighting, invasion) or wanted item name (wandering NPC). */
    volatile String subject = "";
    volatile String npcName = "";

    /** Object ids of the mobs this event spawned and that are still up. */
    final Set<Integer> mobOids = ConcurrentHashMap.newKeySet();
    volatile int waveIndex = -1;
    volatile int bossOid = -1;
    volatile int npcOid = -1;
    volatile int npcId = 0;
    volatile int hintsGiven = 0;
    volatile long lastChatterAt;
    volatile long lastRecruitAt;

    /** The first real player to win it (boss kill, treasure found, first quest turned in), or 0. */
    volatile int winnerId;
    volatile String winnerName;
    /** Players who turned in the wandering NPC's quest (one turn-in each). */
    final Set<Integer> completed = ConcurrentHashMap.newKeySet();
    /** Players who accepted the wandering NPC's quest. */
    final Set<Integer> accepted = ConcurrentHashMap.newKeySet();
    /** Bots that answered the call. */
    final Set<Integer> joinedBots = ConcurrentHashMap.newKeySet();

    /** 1.6.0: the evening headline (the director's), not a small or GM one. */
    volatile boolean headline;
    /** 1.6.0: bot deaths on the event map while it ran, per bot character id (invasions: "died 6 times lol"). */
    final java.util.Map<Integer, Integer> deaths = new ConcurrentHashMap<>();
    /** 1.6.0: real players who took part in a won boss fight (rewarded once each). */
    final Set<Integer> participants = ConcurrentHashMap.newKeySet();
    /** 1.6.0: the top real damage on the boss, for notices and memory. */
    volatile long winnerDamage;
    /** 1.6.0 Track D: whoever did the most damage to the boss, bot or player (the site's feed); null when none. */
    volatile String topDamageName;
    /** 1.6.0 Track D: the top 3 damage dealers on the boss (public characters only), for the site's feed. */
    volatile java.util.List<soloMapling.site.SiteFeed.Top> topDamage = java.util.List.of();
    volatile long lastGrumbleAt;
    /** 1.6.0: the next wave of a giant cake's little cakes (they keep coming while the cake stands). */
    volatile int cakeWave;

    volatile boolean finished;
    volatile String endReason = "";
    volatile long endedAt;

    ActiveEvent(int id, EventTemplate template, EventTemplate.Spot spot, int channel, long startedAt, String startedBy,
                int minLevel, int maxLevel) {
        this.id = id;
        this.template = template;
        this.spot = spot;
        this.channel = channel;
        this.startedAt = startedAt;
        this.endsAt = startedAt + template.durationMs();
        this.startedBy = startedBy;
        this.minLevel = minLevel;
        this.maxLevel = maxLevel;
    }

    /** What the event is about, in its notices ({BOSS}); a wedding sets the couple's names (ticket 58). */
    public void setSubject(String subject) {
        this.subject = subject == null ? "" : subject;
    }

    public int mapId() {
        return spot.mapId();
    }

    public String mapName() {
        return mapName;
    }

    public String winnerName() {
        return winnerName;
    }

    /** The region a treasure hunt names instead of its map. */
    public String regionName() {
        return regionName;
    }

    /** 1.6.0 Track D: the top damage on the event's boss, bot or player; null when there was no boss fight. */
    public String topDamageName() {
        return topDamageName;
    }

    public boolean finished() {
        return finished;
    }

    public String endReason() {
        return endReason;
    }

    public int botsJoined() {
        return joinedBots.size();
    }

    public boolean headline() {
        return headline;
    }

    /** Bot deaths on the event map so far. */
    public int botDeaths() {
        int n = 0;
        for (int d : deaths.values()) {
            n += d;
        }
        return n;
    }

    EventScheduler.Running running() {
        return new EventScheduler.Running(id, template.id(), endsAt);
    }

    public String describe(long now) {
        String left = finished ? "ended (" + endReason + ")" : Math.max(0, (endsAt - now) / 60_000) + " min left";
        return "#" + id + " " + template.id() + " (" + template.kind().label() + (headline ? ", headline" : "")
                + ") on " + mapName + " [" + mapId() + "] ch" + channel + ", " + left + ", " + joinedBots.size()
                + " bots" + (deaths.isEmpty() ? "" : ", " + botDeaths() + " bot deaths")
                + (winnerName != null ? ", won by " + winnerName : "") + ", by " + startedBy;
    }
}
