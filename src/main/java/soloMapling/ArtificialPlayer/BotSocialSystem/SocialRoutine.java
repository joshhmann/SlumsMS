package soloMapling.ArtificialPlayer.BotSocialSystem;

import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemory;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemoryStore;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Emotion;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Need;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Trait;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Ticket 181 stage 7: the daily schedule as SOFT STRUCTURE. There is no script and no timetable: for each bot the
 * traits fix four smooth hour-of-day weights (work or training, a lunchtime gathering in the town square or market, an
 * evening hangout, rest), and those weights are only ever inputs to decisions that already exist:
 *
 * <ul>
 *   <li>{@link #modeBias}: two multipliers on the pull scores of {@code ModeSwitch.decide} (training to town or social,
 *       and back). Bounded to {@code [0.5, 1.8]}; a strong need pulls them toward 1, so a lonely or exhausted bot is not
 *       held to its schedule.</li>
 *   <li>{@link #spotFor}: which of the pilot's gathering maps (Henesys: town square, market, park, pet park) a bot's town
 *       stay goes to at this hour. The weights are the bot's own taste (traits) times what each place is for at that
 *       hour, plus its friend group's recurring gathering ({@link SocialGroups}). Bots with similar traits like the same
 *       place, so they meet there again and again, which is what forms the bonds. The pick is stable over 30 minutes.</li>
 *   <li>{@link #townPull}: how much the hours ask a bot to be in town, for the hang-out bias.</li>
 * </ul>
 *
 * <p>Everything is a pure function of (traits, needs, emotions, hour) plus the bot's id for its own phase; the one read
 * of the world is the mind in memory. No random state, so the same inputs always give the same answer.
 * {@code -Dslums.socialroutine=false}, or the pilot off, makes every bias neutral (1, no preferred map).
 */
public final class SocialRoutine {

    public static final String SWITCH = "slums.socialroutine";

    /** No bias may leave this band: a schedule nudges a decision, never takes it over. */
    public static final double BIAS_MIN = 0.5, BIAS_MAX = 1.8;
    /** A spot pick is stable over this long. */
    static final long BUCKET_MS = 30 * 60_000L;

    private SocialRoutine() {
    }

    /** On unless {@code -Dslums.socialroutine=false}; also needs the pilot. */
    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty(SWITCH)) && SocialPilot.enabled();
    }

    // ── the clock ──

    private static volatile ZoneId zone;

    /** The time zone the hours are read in ({@code -Dslums.socialroutine.zone}, else the server's own). */
    static ZoneId zone() {
        ZoneId z = zone;
        if (z != null) {
            return z;
        }
        String name = System.getProperty("slums.socialroutine.zone");
        try {
            return name == null || name.isBlank() ? ZoneId.systemDefault() : ZoneId.of(name.trim());
        } catch (RuntimeException e) {
            return ZoneId.systemDefault();
        }
    }

    /** Test seam: the zone the hours are read in (null restores the default). */
    static void useZoneForTest(ZoneId testZone) {
        zone = testZone;
        PROFILES.clear();
    }

    /** The hour of the day, 0 up to 24, with the minutes as a fraction. */
    public static double hourOf(long now) {
        ZonedDateTime t = Instant.ofEpochMilli(now).atZone(zone());
        return t.getHour() + t.getMinute() / 60.0;
    }

    // ── the shape of a day ──

    /** One bot's four weights at one hour, each 0..1. */
    public record Day(double work, double gather, double evening, double rest) {
        /** How much the hour asks to be in company in town: the gathering and the evening hangout. */
        public double company() {
            return Math.max(gather, evening);
        }
    }

    /** What a bot is mostly doing at an hour (the largest weight), or what its needs made it do instead. */
    public enum Kind { WORK, GATHER, EVENING, REST }

    public record Doing(Kind kind, String why) {
    }

    /**
     * What makes one bot's day its own: the phase (a chronotype from its id and ambition), and its taste for each
     * gathering place, all from its traits. {@code spotTaste[i]} is for the i-th pilot map.
     */
    public record Profile(double shift, double sociability, double ambition, double curiosity, double confidence,
                          double agreeableness, double[] spotTaste) {

        /** The profile of {@code id} with this mind (traits only; the needs and emotions are read separately). */
        public static Profile of(int id, SocialContext.Self self, int spots) {
            double soc = self.t(Trait.SOCIABILITY), amb = self.t(Trait.AMBITION), cur = self.t(Trait.CURIOSITY),
                    conf = self.t(Trait.CONFIDENCE), agr = self.t(Trait.AGREEABLENESS);
            double shift = 3 * (unit(id, 0xC0FFEE) - 0.5) - 1.5 * (amb - 0.5); // -2.25 .. 2.25 hours
            double[] taste = new double[Math.max(1, spots)];
            for (int i = 0; i < taste.length; i++) {
                taste[i] = switch (i % 4) {
                    case 0 -> 0.20 + 0.70 * soc;                                  // the square: the sociable, who like a crowd
                    case 1 -> 0.20 + 0.60 * amb + 0.20 * conf;                    // the market: the ambitious and confident
                    case 2 -> 0.20 + 0.50 * agr + 0.30 * (1 - soc);               // the park: the agreeable and the quiet
                    default -> 0.20 + 0.70 * cur;                                 // the pet park: the curious
                };
            }
            return new Profile(shift, soc, amb, cur, conf, agr, taste);
        }
    }

    /** A smooth bump on the 24-hour circle: 1 at {@code center}, falling to 0 at {@code halfWidth} hours away. */
    static double bump(double hour, double center, double halfWidth) {
        double d = Math.abs(((hour - center) % 24 + 36) % 24 - 12); // distance on the circle, 0..12
        return d >= halfWidth ? 0 : 0.5 * (1 + Math.cos(Math.PI * d / halfWidth));
    }

    /** The four weights of this profile at {@code hour}. */
    public static Day at(Profile p, double hour) {
        double work = (0.55 + 0.45 * p.ambition()) * bump(hour, 11.0 + p.shift(), 4.0 + 2.5 * p.ambition());
        double gather = (0.35 + 0.65 * p.sociability()) * bump(hour, 13.0 + 0.5 * p.shift(), 2.0 + 2.0 * p.sociability());
        double evening = (0.35 + 0.65 * p.sociability()) * bump(hour, 19.5 + p.shift(), 3.0 + 1.0 * p.sociability());
        double rest = (1 - 0.3 * p.ambition()) * bump(hour, 3.5 + p.shift(), 4.5);
        return new Day(work, gather, evening, rest);
    }

    /** The hour the profile's evening hangout peaks at (the group event's hour is the mean of its members'). */
    public static double eveningHour(Profile p) {
        return ((19.5 + p.shift()) % 24 + 24) % 24;
    }

    /**
     * What the bot is mostly doing at this hour: the largest weight; but a bot that is worn out rests and a bot that is
     * starved for company gathers, whatever the hour says (autonomy: needs and emotions override the schedule).
     */
    public static Doing doing(Profile p, SocialContext.Self self, double hour) {
        if (self.n(Need.REST) >= 0.85) {
            return new Doing(Kind.REST, "worn out");
        }
        if (self.n(Need.COMPANIONSHIP) >= 0.85 || self.e(Emotion.LONELINESS) >= 0.6) {
            return new Doing(Kind.GATHER, "starved for company");
        }
        Day d = at(p, hour);
        Kind best = Kind.REST;
        double top = d.rest();
        if (d.work() > top) {
            best = Kind.WORK;
            top = d.work();
        }
        if (d.gather() > top) {
            best = Kind.GATHER;
            top = d.gather();
        }
        if (d.evening() > top) {
            best = Kind.EVENING;
        }
        return new Doing(best, "the hour");
    }

    // ── bias into ModeSwitch ──

    /**
     * Two multipliers for {@code ModeSwitch.decide}: {@code towardTown} scales the pull from training to a social or town
     * mode, {@code towardTraining} the pull back. 1 and 1 changes nothing.
     */
    public record ModeBias(double towardTown, double towardTraining) {
        public static final ModeBias NONE = new ModeBias(1, 1);

        public ModeBias {
            towardTown = clampBias(towardTown);
            towardTraining = clampBias(towardTraining);
        }

        public boolean neutral() {
            return towardTown == 1 && towardTraining == 1;
        }
    }

    static double clampBias(double v) {
        return Double.isFinite(v) ? Math.max(BIAS_MIN, Math.min(BIAS_MAX, v)) : 1;
    }

    /** The urgency of the bot's own state: a need or a feeling this strong loosens the schedule's hold (0..1). */
    static double urgency(SocialContext.Self self) {
        double u = Math.max(self.n(Need.COMPANIONSHIP), Math.max(self.n(Need.REST), 2 * self.e(Emotion.LONELINESS)));
        return Math.max(0, Math.min(1, (u - 0.5) / 0.4));
    }

    /** The multipliers of this profile and state at {@code hour}. Pure. */
    public static ModeBias modeBias(Profile p, SocialContext.Self self, double hour) {
        Day d = at(p, hour);
        double company = d.gather() + d.evening();
        double town = 1 + 0.9 * company + 0.3 * d.rest() - 0.6 * d.work();
        double train = 1 + 1.0 * d.work() - 0.5 * company - 0.3 * d.rest();
        double loose = 1 - 0.75 * urgency(self);
        return new ModeBias(1 + (clampBias(town) - 1) * loose, 1 + (clampBias(train) - 1) * loose);
    }

    /** {@link ModeBias#NONE} unless this bot is in the pilot, the routine is on and its mind is in memory. */
    public static ModeBias modeBias(int botId, long now) {
        if (!enabled() || !SocialPilot.isPilot(botId)) {
            return ModeBias.NONE;
        }
        ModeBias b = read(botId, now, (p, self) -> modeBias(p, self, hourOf(now)));
        if (b == null) {
            return ModeBias.NONE;
        }
        if (!b.neutral()) {
            SocialPilot.onRoutineBias();
        }
        return b;
    }

    /** How much the hours ask this bot to be in town for company, 0..1 (the hang-out pull's weight). */
    public static double townPull(int botId, long now) {
        if (!enabled()) {
            return 0;
        }
        Double v = read(botId, now, (p, self) -> {
            Day d = at(p, hourOf(now));
            return Math.min(1, d.company()) * (1 - 0.75 * urgency(self));
        });
        return v == null ? 0 : v;
    }

    // ── the gathering spot ──

    /** What a friend group adds to a member's spot pick: its place, and the hour it gathers. */
    public record GroupPlan(int spotIndex, double eventHour) {
    }

    /** One pick: the index into the pilot's maps, the map and how it was chosen. */
    public record Spot(int index, int mapId, String why) {
    }

    /** How much a group's event pulls at {@code hour}: 1 at its hour, 0 more than two hours away. */
    public static double eventPull(GroupPlan plan, double hour) {
        return plan == null ? 0 : bump(hour, plan.eventHour(), 2.0);
    }

    /**
     * The bot's gathering spot at {@code now}: weights are its taste times what each place is for at this hour (the
     * market at midday, the park and pet park in the evening), squared so the taste shows, then its group's place counts
     * for more all day and a great deal at its event hour; a starved bot leans toward the busy square and its group.
     * One stable draw per 30 minutes picks among them. Pure.
     */
    public static Spot spotFor(int botId, Profile p, SocialContext.Self self, long now, int[] maps, GroupPlan group) {
        int n = Math.min(maps.length, p.spotTaste().length);
        double hour = hourOf(now);
        Day d = at(p, hour);
        double[] w = new double[n];
        double sum = 0;
        boolean starved = self.n(Need.COMPANIONSHIP) >= 0.7 || self.e(Emotion.LONELINESS) >= 0.35;
        double pull = eventPull(group, hour);
        double[] base = placeWeights(p, d, n);
        for (int i = 0; i < n; i++) {
            double x = base[i];
            if (group != null && group.spotIndex() == i) {
                x *= 1.4 + 3.0 * pull;
                if (starved) {
                    x *= 1.5;
                }
            }
            if (starved && i == 0) {
                x *= 1.3;
            }
            w[i] = x;
            sum += x;
        }
        if (sum <= 0) {
            return new Spot(0, maps[0], "no taste");
        }
        double roll = unit(botId * 31L + now / BUCKET_MS, 0x5107) * sum;
        int pick = n - 1;
        for (int i = 0; i < n; i++) {
            roll -= w[i];
            if (roll < 0) {
                pick = i;
                break;
            }
        }
        String why = group != null && group.spotIndex() == pick && pull > 0.3 ? "its group's gathering"
                : starved ? "company" : "its taste at this hour";
        return new Spot(pick, maps[pick], why);
    }

    /**
     * Taste x what each place is for at this hour, squared so a taste shows: the weight of each of the first {@code n}
     * gathering places for a bot with this profile. The square is for company all day, the market for the lunchtime
     * gathering, the park and the pet park for the evening. Pure.
     */
    public static double[] placeWeights(Profile p, Day d, int n) {
        double[] w = new double[n];
        for (int i = 0; i < n; i++) {
            double time = switch (i % 4) {
                case 0 -> 0.6 + 0.4 * d.company();
                case 1 -> 0.35 + 0.65 * d.gather();
                case 2 -> 0.4 + 0.6 * d.evening();
                default -> 0.4 + 0.6 * Math.max(d.evening(), 0.5 * d.gather());
            };
            double x = p.spotTaste()[i] * time;
            w[i] = x * x;
        }
        return w;
    }

    /** The map the bot's town stay goes to now, or 0 (not in the pilot, routine off, mind not in memory). */
    public static int preferredMap(int botId, long now) {
        if (!enabled() || !SocialPilot.isPilot(botId)) {
            return 0;
        }
        int[] maps = SocialPilot.maps();
        GroupPlan g = SocialGroups.planFor(botId, now);
        Spot s = read(botId, now, maps.length, (p, self) -> spotFor(botId, p, self, now, maps, g));
        return s == null ? 0 : s.mapId();
    }

    // ── reading the mind ──

    @FunctionalInterface
    interface Use<T> {
        T apply(Profile p, SocialContext.Self self);
    }

    private static final Map<Integer, Profile> PROFILES = new ConcurrentHashMap<>();

    private static <T> T read(int botId, long now, Use<T> use) {
        return read(botId, now, SocialPilot.maps().length, use);
    }

    /** Runs {@code use} with the bot's profile and mind now; null when its memory is not loaded (memory only). */
    private static <T> T read(int botId, long now, int spots, Use<T> use) {
        LifeMemory mem = LifeMemoryStore.peek(botId);
        if (mem == null) {
            return null;
        }
        SocialContext.Self self = mem.mind(m -> SocialContext.Self.of(m, now));
        Profile p = PROFILES.computeIfAbsent(botId, id -> Profile.of(id, self, spots));
        if (p.spotTaste().length != Math.max(1, spots)) { // the pilot's map changed
            p = Profile.of(botId, self, spots);
            PROFILES.put(botId, p);
        }
        return use.apply(p, self);
    }

    /** The profile of a bot with this mind (cached: the traits never change). Null when the mind is not loaded. */
    static Profile profileOf(int botId, long now) {
        return read(botId, now, (p, self) -> p);
    }

    /** Test seam: forgets the cached profiles. */
    static void resetForTest() {
        PROFILES.clear();
        zone = null;
    }

    /** A stable value in [0, 1) from two longs (no random state: the same inputs give the same answer). */
    static double unit(long a, long b) {
        long z = a * 0x9E3779B97F4A7C15L + b;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11) / (double) (1L << 53);
    }
}
