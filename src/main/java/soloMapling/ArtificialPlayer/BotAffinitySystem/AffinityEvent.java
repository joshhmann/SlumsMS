package soloMapling.ArtificialPlayer.BotAffinitySystem;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Something a real player did to a bot that moves how the bot feels about them (ticket 58). Each kind has a default
 * weight and a per-pair cooldown, so one long fight or a spammed buff counts once, not a hundred times. Both can be
 * tuned with {@code -Dsolomapling.affinity.<name>=<delta>} and {@code -Dsolomapling.affinity.<name>.cooldownSec=<s>}
 * (name in lower case, e.g. {@code ks}).
 */
public enum AffinityEvent {
    // Raised by
    PARTY(6, 30 * 60),
    BUFF(3, 5 * 60),
    HEAL(3, 5 * 60),
    RESCUE(8, 10 * 60),
    TRADE(5, 10 * 60),
    GIFT(9, 10 * 60),
    FAME(12, 0),
    KIND_CHAT(2, 3 * 60),
    // Lowered by
    KS(-6, 2 * 60),
    DROP_STEAL(-5, 2 * 60),
    DEFAME(-18, 0),
    RUDE_CHAT(-7, 3 * 60),
    // Second-hand (gossip): small, and never enough on its own to make a friend or a rival
    HEARSAY_GOOD(3, 60 * 60),
    HEARSAY_BAD(-4, 60 * 60);

    private final int defaultDelta;
    private final int defaultCooldownSec;

    AffinityEvent(int defaultDelta, int defaultCooldownSec) {
        this.defaultDelta = defaultDelta;
        this.defaultCooldownSec = defaultCooldownSec;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean hostile() {
        return defaultDelta < 0;
    }

    public boolean hearsay() {
        return this == HEARSAY_GOOD || this == HEARSAY_BAD;
    }

    public int delta() {
        return Integer.getInteger("solomapling.affinity." + key(), defaultDelta);
    }

    public long cooldownMs() {
        return TimeUnit.SECONDS.toMillis(Integer.getInteger("solomapling.affinity." + key() + ".cooldownSec",
                defaultCooldownSec));
    }

    public static AffinityEvent parse(String raw) {
        if (raw == null) {
            return null;
        }
        String k = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (AffinityEvent e : values()) {
            if (e.name().equals(k)) {
                return e;
            }
        }
        return null;
    }
}
