package soloMapling.ArtificialPlayer;

import client.Character;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BondEvent;
import soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.function.IntFunction;

/**
 * Bots talk about players behind their backs (bot-persistence ticket 15), and since 1.6.0 S4 about each other, and
 * only about what really happened.
 *
 * <p><b>Spreading.</b> Every minute a few online bots each pass something on to a bot they are bonded to - a FRIEND
 * or a crew mate ({@code BotBonds}), wherever it is; a bot with no bond falls back to one on the same map, as before.
 * Two things travel: one chat note about a player, kept as hearsay ({@link BotMemory#hear}, at most
 * {@link BotMemory#MAX_HOPS} mouths deep, online listeners only), and one subject's {@link Reputation} counters, which
 * move from the teller's circle to the listener's. Socializers pass things on more often, Killers prefer bad news
 * (Bartle, S1). A listener whose circle now knows a real player as a KSer or scammer takes the existing small hearsay
 * nudge against them ({@code BotAffinity.onHearsay}, capped): it is a little warier.
 *
 * <p><b>Grounded talk.</b> {@link #factFor} picks what a bot thread's gossip is about from real facts - a KS, scam,
 * gift or PQ its circle knows of ({@link Reputation}), a fresh rivalry or friendship between bots (bond drama), a boss
 * kill, rare drop or wedding in the news ({@link BotWorldGossip}) - weighted by the teller's Bartle type (Killers bad
 * news and beef, Socializers weddings and friendships, Achievers boss kills and drops, Explorers finds), with a
 * non-person topic (prices, maps, the patch) when there is nothing true to tell. Gossip about a real player who stands
 * right there is aimed at them: it goes through {@link SocialGate} ({@code GOSSIP}: only when they started it with the
 * teller or its crew, never with {@code @quiet bots} or {@code @botdrama off}).
 */
public final class BotGossip {

    static final long PERIOD_MS = TimeUnit.MINUTES.toMillis(1);
    /** Bots that may gossip in one step. */
    static final int TELLERS_PER_STEP = 20;
    /** The chance a chosen teller passes something on, at a Socializer share of 0.25 (scaled by it, see {@link #passChance}). */
    static final double PASS_CHANCE = 0.3;

    private static volatile long lastStepAt;
    private static volatile long firstStepAt;
    /** The boot read of reputations waits this long after the first step (bonds load at their first minute step). */
    static final long LOAD_AFTER_MS = TimeUnit.MINUTES.toMillis(3);

    /** A bot's point on Bartle's chart (a seam for tests). */
    private static volatile IntFunction<SocialProfile> profiles = BotGossip::profileOf;
    /** Bots this bot is bonded to (friends, then crew; a seam for tests). */
    private static volatile IntFunction<List<Integer>> bonded = BotGossip::bondsOf;
    /** A character's name by id (a seam for tests). */
    private static volatile IntFunction<String> names = BotGossip::nameOf;
    /** Whether the bot may gossip about this player in front of them ({@link SocialGate}; a seam for tests). */
    private static volatile BiPredicate<Character, Character> gossipInFront = BotGossip::gate;

    private BotGossip() {
    }

    // ── spreading ──

    /** Runs at most once a minute, from the director's tick. */
    static void maybeStep(long now) {
        if (now - lastStepAt < PERIOD_MS) {
            return;
        }
        lastStepAt = now;
        if (firstStepAt == 0) {
            firstStepAt = now;
        } else if (now - firstStepAt >= LOAD_AFTER_MS) {
            Reputation.loadAsync(); // once; after bonds and guilds are loaded, so the rows land in the right circles
        }
        Map<Integer, List<Character>> byMap = new java.util.HashMap<>();
        for (BotSM bot : CharacterStorage.getAllBots().values()) {
            Character chr = bot.getChr();
            if (chr != null && chr.getMap() != null) {
                byMap.computeIfAbsent(chr.getMapId(), k -> new ArrayList<>()).add(chr);
            }
        }
        step(byMap, now, ThreadLocalRandom.current());
    }

    /** One round over the online bots grouped by map; returns how many notes and reputations changed hands. */
    static int step(Map<Integer, List<Character>> botsByMap, long now, Random rng) {
        Map<Integer, Character> online = new java.util.HashMap<>();
        for (List<Character> here : botsByMap.values()) {
            for (Character c : here) {
                online.put(c.getId(), c);
            }
        }
        List<Character> tellers = new ArrayList<>();
        List<Integer> listeners = new ArrayList<>();
        for (List<Character> here : botsByMap.values()) {
            for (Character teller : here) {
                Integer friend = bondedListener(teller.getId(), rng);
                if (friend != null) {
                    tellers.add(teller);
                    listeners.add(friend);
                    continue;
                }
                if (here.size() < 2) {
                    continue;
                }
                Character listener;
                do {
                    listener = here.get(rng.nextInt(here.size()));
                } while (listener == teller);
                tellers.add(teller);
                listeners.add(listener.getId());
            }
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < tellers.size(); i++) {
            order.add(i);
        }
        java.util.Collections.shuffle(order, rng);
        int passed = 0;
        for (int i : order.subList(0, Math.min(TELLERS_PER_STEP, order.size()))) {
            Character tellerChr = tellers.get(i);
            int listenerId = listeners.get(i);
            Character listenerChr = online.get(listenerId);
            SocialProfile p = profiles.apply(tellerChr.getId());
            if (rng.nextDouble() >= passChance(p.socialPull())) {
                continue;
            }
            // 1.6.0 S4: what the teller's circle thinks of someone travels along the bond (memory only, any listener)
            int subject = Reputation.spread(tellerChr.getId(), listenerId, 1 + 2 * p.rivalPull(), now, rng);
            if (subject != 0) {
                passed++;
                warn(listenerId, subject);
            }
            if (listenerChr == null) {
                continue; // an offline friend hears the reputation, not a note (notes go to bots with memory loaded)
            }
            int teller = tellerChr.getId() - BotStore.BOT_ID_BASE;
            List<BotMemory.Note> notes = BotMemory.recentPassable(teller, 5);
            if (notes.isEmpty()) {
                continue;
            }
            BotMemory.Note note = notes.get(rng.nextInt(notes.size()));
            if (soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity.optedOut(note.aboutCharId())) {
                continue; // 1.5.0: nobody gossips about a player with @botdrama off
            }
            if (BotMemory.hear(listenerChr.getId() - BotStore.BOT_ID_BASE, note, now)) {
                passed++;
                // Ticket 58: hearing that a player KSes (or is a good friend) nudges the listener a little.
                soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity.onHearsay(listenerChr.getId() - BotStore.BOT_ID_BASE, note.aboutCharId(), note.text());
                soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityReactions.maybeMentionGossip(tellerChr, listenerChr,
                        note.aboutCharId(), note.text(), rng);
            }
        }
        return passed;
    }

    /** How likely a teller passes something on this step: 0.3 at a Socializer share of 0.25, from 0.15 up to 0.6. */
    static double passChance(double socializerShare) {
        double share = Math.max(0, Math.min(1, socializerShare));
        return Math.min(0.6, PASS_CHANCE * (0.5 + 2 * share));
    }

    /** A FRIEND or crew mate to tell (one of the closest four), or null when the bot has no bond. */
    static Integer bondedListener(int tellerId, Random rng) {
        List<Integer> close = bonded.apply(tellerId);
        if (close == null || close.isEmpty()) {
            return null;
        }
        return close.get(rng.nextInt(Math.min(4, close.size())));
    }

    /**
     * The listener's circle now knows the subject KSes or scams: the listener (a bot) takes the existing small hearsay
     * nudge against that real player (capped by {@code AffinityRules.HEARSAY_CAP}, only while they are online).
     */
    static void warn(int listenerCharId, int subjectId) {
        if (BotHelpers.isArtificial(subjectId) || !Reputation.wary(listenerCharId, subjectId)) {
            return;
        }
        try {
            soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity.onHearsay(listenerCharId - BotStore.BOT_ID_BASE,
                    subjectId, soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity.NOTE_KS);
        } catch (RuntimeException e) {
            // affinity is best effort
        }
    }

    private static List<Integer> bondsOf(int botId) {
        try {
            LinkedHashSet<Integer> out = new LinkedHashSet<>(
                    soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds.friendsOf(botId));
            out.addAll(soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds.crewOf(botId));
            out.remove(botId);
            return new ArrayList<>(out);
        } catch (RuntimeException | LinkageError e) {
            return List.of();
        }
    }

    private static SocialProfile profileOf(int charId) {
        try {
            return soloMapling.ArtificialPlayer.BotLifeSystem.SocialSelf.of(charId).social();
        } catch (RuntimeException | LinkageError e) {
            return SocialProfile.forCharacter(charId);
        }
    }

    private static String nameOf(int charId) {
        if (BotHelpers.isArtificial(charId)) {
            return BotBuddies.nameOf(charId);
        }
        try {
            Character c = soloMapling.server.SoloMaplingUtilities.world().getPlayerStorage().getCharacterById(charId);
            if (c != null) {
                return c.getName();
            }
            String n = Character.getNameById(charId);
            return n == null || n.isBlank() ? null : n;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static boolean gate(Character bot, Character player) {
        return SocialGate.allow(bot, player, SocialGate.Act.GOSSIP);
    }

    // ── what gossip is about ──

    /**
     * One real thing to talk about. {@code about} is the subject in a few words for the prompt and chat routing ("Gm
     * ksed Nayrb"); {@code opener} the teller's first line when there is no model (null: the line bank's);
     * {@code names} the people it may name besides the two speakers; {@code subjectId} who it is about (0: nobody).
     */
    public record Fact(String kind, String about, String opener, List<String> names, int subjectId, boolean negative) {

        /** Whether it is about people (else a topic like prices or the patch). */
        public boolean person() {
            return !names.isEmpty();
        }
    }

    /**
     * What {@code teller} gossips about with {@code other} (both bots) on a map where {@code present} stand: one of the
     * true things its circle knows, drawn by its Bartle type, or a non-person topic. Never null. Memory only.
     */
    public static Fact factFor(Character teller, Character other, Collection<Character> present, long now, Random rng) {
        SocialProfile p = profiles.apply(teller.getId());
        List<Fact> facts = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        reputationFacts(teller, other, present, p, facts, weights);
        dramaFacts(teller, other, p, now, facts, weights);
        newsFacts(teller, other, p, now, rng, facts, weights);
        heardFacts(teller, other, present, p, now, facts, weights); // a story it heard in an earlier thread
        Fact pick = pick(facts, weights, rng);
        return pick != null ? pick : smallTalk(p, rng);
    }

    /**
     * {@code !qa gs-1}: the reputation fact {@code teller}'s circle holds about one subject, or null when it knows
     * nothing about them or may not say it here (the subject stands there and did not start it with its circle).
     */
    public static Fact factAbout(Character teller, Character other, Collection<Character> present, int subjectId) {
        List<Fact> facts = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        reputationFacts(teller, other, present, profiles.apply(teller.getId()), facts, weights);
        for (Fact f : facts) {
            if (f.subjectId() == subjectId) {
                return f;
            }
        }
        return null;
    }

    /**
     * What two bots talk about in a TRAINING thread: where a crew mate really trains (more likely for Explorers), the
     * teller's own level and next goal (Achievers), else spots for their level. Never null; names only real people.
     */
    public static Fact trainingFor(Character teller, Character other, Random rng) {
        SocialProfile p = profiles.apply(teller.getId());
        if (rng.nextDouble() < 0.3 + p.share(SocialProfile.Type.EXPLORER)) {
            for (int mate : bonded.apply(teller.getId())) {
                BotSM sm = mate == other.getId() ? null : CharacterStorage.getBotById(mate);
                Character c = sm == null ? null : sm.getChr();
                if (c == null || c.getMap() == null || c.getMap().isTown() || c.getName() == null) {
                    continue;
                }
                String place = BotEpisodes.placeName(c.getMapId());
                if (place != null) {
                    return new Fact("spot", c.getName() + " trains at " + place, null, List.of(c.getName()),
                            c.getId(), false);
                }
            }
        }
        if (rng.nextDouble() < 0.3 + p.share(SocialProfile.Type.ACHIEVER)) {
            int level = teller.getLevel();
            int next = level < 10 ? 10 : level < 30 ? 30 : level < 70 ? 70 : level < 120 ? 120 : 200;
            return new Fact("level", teller.getName() + " is level " + level + ", aiming for " + next, null,
                    List.of(), 0, false);
        }
        return new Fact("spot", "good training spots around level " + Math.min(teller.getLevel(), other.getLevel()),
                null, List.of(), 0, false);
    }

    static void reputationFacts(Character teller, Character other, Collection<Character> present, SocialProfile p,
                                List<Fact> facts, List<Double> weights) {
        int tellerId = teller.getId();
        int n = 0;
        for (int s : Reputation.subjectsKnownBy(tellerId)) {
            if (s == tellerId || s == other.getId()) {
                continue;
            }
            if (n++ >= 12) {
                break;
            }
            Reputation.Known k = Reputation.known(tellerId, s);
            Reputation.Mark m = k.top();
            String subject = m == null ? null : names.apply(s);
            if (subject == null || subject.isBlank()) {
                continue;
            }
            if (m.negative && !BotHelpers.isArtificial(s)) {
                Character there = presentChr(present, s);
                if (there != null && !gossipInFront.test(teller, there)) {
                    continue; // saying it to their face is beef: only when they started it with this bot or its crew
                }
            }
            int victimId = k.victim(m);
            String victim = victimId == tellerId ? "me" : victimId == 0 ? null : names.apply(victimId);
            List<String> named = new ArrayList<>(List.of(subject));
            if (victim != null && !"me".equals(victim)) {
                named.add(victim);
            }
            double w = Math.min(3, k.count(m)) * (m.negative ? 1 + 2 * p.rivalPull() : 0.6 + p.socialPull())
                    * (k.hops() == 0 ? 1.3 : 1);
            facts.add(new Fact(m.key(), aboutOf(m, subject, victim == null ? null : "me".equals(victim)
                    ? teller.getName() : victim), opener(m, subject, victim, s ^ tellerId), List.copyOf(named), s,
                    m.negative));
            weights.add(w);
        }
    }

    static void dramaFacts(Character teller, Character other, SocialProfile p, long now, List<Fact> facts,
                           List<Double> weights) {
        List<Integer> circle = new ArrayList<>(bonded.apply(teller.getId()));
        circle.add(teller.getId());
        int n = 0;
        for (Reputation.Drama d : Reputation.dramaAbout(circle, now)) {
            if (n++ >= 6) {
                break;
            }
            boolean mine = d.botId() == teller.getId();
            String a = mine ? teller.getName() : names.apply(d.botId());
            String o = names.apply(d.otherId());
            if (a == null || o == null || d.otherId() == teller.getId() || d.otherId() == other.getId()
                    || d.botId() == other.getId()) {
                continue; // drama between the two speakers is a spat for S7, not gossip
            }
            boolean beef = d.state().hostile();
            if (!beef && d.state() != AffinityState.FRIEND) {
                continue;
            }
            List<String> named = mine ? List.of(o) : List.of(a, o);
            String about = beef ? a + " has beef with " + o : a + " and " + o + " are friends now";
            String opener = beef
                    ? (mine ? (d.cause() == BondEvent.KS ? o + " keeps ksing me smh" : "me and " + o + " have beef now")
                    : a + " and " + o + " have beef lol")
                    : (mine ? "me and " + o + " r tight now" : a + " and " + o + " r always together now lol");
            facts.add(new Fact(beef ? "beef" : "friends", about, opener, named, d.otherId(), beef));
            weights.add(beef ? 1 + 2 * p.rivalPull() : 0.5 + p.socialPull());
        }
    }

    static void newsFacts(Character teller, Character other, SocialProfile p, long now, Random rng, List<Fact> facts,
                          List<Double> weights) {
        double achiever = p.share(SocialProfile.Type.ACHIEVER);
        double explorer = p.share(SocialProfile.Type.EXPLORER);
        double social = p.socialPull();
        for (BotWorldGossip.News n : BotWorldGossip.recent(now)) {
            if (n.whoId == teller.getId() || n.whoId == other.getId()) {
                continue;
            }
            double bias = switch (n.kind) {
                case BOSS -> 0.6 + 2 * achiever;
                case DROP -> 0.6 + 1.5 * achiever + 1.5 * explorer;
                case WEDDING -> 0.6 + 2 * social;
                case EVENT -> 0.8 + social + achiever; // 1.6.0 world events: "remember the cake lol"
            };
            String line = BotWorldGossip.line(n, n.witnessedBy(teller.getId()), BotWorldGossip.ageText(now - n.at), rng);
            List<String> named = n.kind == BotWorldGossip.Kind.WEDDING ? List.of(n.who, n.what) : List.of(n.who);
            facts.add(new Fact("news", BotWorldGossip.about(n), line, named, n.whoId, false));
            weights.add(BotWorldGossip.freshness(now - n.at) * bias);
        }
    }

    /** No true story: a topic without people, by the teller's type (Explorers maps, Achievers levels and drops). */
    static Fact smallTalk(SocialProfile p, Random rng) {
        double explorer = p.share(SocialProfile.Type.EXPLORER);
        double achiever = p.share(SocialProfile.Type.ACHIEVER);
        double r = rng.nextDouble() * 1.5;
        String about;
        if (r < explorer) {
            about = "maps and hidden spots worth exploring";
        } else if (r < explorer + achiever) {
            about = "leveling and the best drops";
        } else if (r < explorer + achiever + 0.4) {
            about = "prices in the FM";
        } else {
            about = "the new patch";
        }
        return new Fact("talk", about, null, List.of(), 0, false);
    }

    static Fact pick(List<Fact> facts, List<Double> weights, Random rng) {
        double sum = 0;
        for (double w : weights) {
            sum += Math.max(0, w);
        }
        if (facts.isEmpty() || sum <= 0) {
            return null;
        }
        double roll = rng.nextDouble() * sum;
        for (int i = 0; i < facts.size(); i++) {
            roll -= Math.max(0, weights.get(i));
            if (roll < 0) {
                return facts.get(i);
            }
        }
        return facts.get(facts.size() - 1);
    }

    private static Character presentChr(Collection<Character> present, int id) {
        if (present == null) {
            return null;
        }
        for (Character c : present) {
            if (c != null && c.getId() == id) {
                return c;
            }
        }
        return null;
    }

    /** The subject in a few words ("Gm ksed Nayrb", "Gm scammed people on a trade"). */
    static String aboutOf(Reputation.Mark m, String subject, String victim) {
        String v = victim == null ? "people" : victim;
        return switch (m) {
            case KS -> subject + " ksed " + v;
            case SCAM -> subject + " scammed " + v + " on a trade";
            case GIFT -> subject + " gave " + v + " free stuff";
            case FAVOR -> subject + " gave " + v + " a good deal";
            case PQ -> subject + " did pqs with " + v;
        };
    }

    /** The teller's first line about it, before its voice styles it. {@code seed} picks the wording. */
    static String opener(Reputation.Mark m, String subject, String victim, int seed) {
        String[] pool;
        if ("me".equals(victim)) {
            pool = switch (m) {
                case KS -> new String[]{"{S} ksed me earlier smh", "{S} keeps ksing me lol", "ugh {S} ksed me again"};
                case SCAM -> new String[]{"{S} scammed me on a trade smh", "never trade {S}, scammed me"};
                case GIFT -> new String[]{"{S} gave me free stuff earlier, so nice", "{S} just gave me stuff lol nice"};
                case FAVOR -> new String[]{"{S} sold me stuff cheap, legit", "{S} gave me a sweet deal"};
                case PQ -> new String[]{"did pq with {S}, good party", "{S} is fun to pq with"};
            };
        } else if (victim == null) {
            pool = switch (m) {
                case KS -> new String[]{"heard {S} ks's ppl", "watch out for {S}, ksing again"};
                case SCAM -> new String[]{"heard {S} scams ppl on trades", "dont trade {S} lol"};
                case GIFT -> new String[]{"heard {S} gives free stuff", "{S} is so generous lol"};
                case FAVOR -> new String[]{"{S} sells stuff cheap", "{S} gives good deals"};
                case PQ -> new String[]{"{S} does pqs a lot", "{S} is always in pqs"};
            };
        } else {
            pool = switch (m) {
                case KS -> new String[]{"did u hear {S} ksed {V}", "{S} ksed {V} earlier lol", "watch out for {S}, ksed {V}"};
                case SCAM -> new String[]{"heard {S} scammed {V} on a trade", "dont trade {S}, scammed {V}"};
                case GIFT -> new String[]{"{S} gave {V} free stuff, so nice", "heard {S} gave {V} free stuff"};
                case FAVOR -> new String[]{"{S} gave {V} a sweet deal", "heard {S} sold {V} stuff cheap"};
                case PQ -> new String[]{"{S} pqs with {V} a lot", "{S} and {V} did pq together"};
            };
        }
        return pool[Math.floorMod(seed, pool.length)].replace("{S}", subject)
                .replace("{V}", victim == null ? "" : victim).replaceAll("\\s+", " ").trim();
    }

    /** The names a thread about this fact may use: the two speakers and the fact's people. */
    public static Set<String> allowedNames(Fact fact, String a, String b) {
        Set<String> out = new LinkedHashSet<>(List.of(a, b));
        if (fact != null) {
            out.addAll(fact.names());
        }
        return out;
    }

    // ── stories a listener keeps (1.6.0 social trace: GOSSIP DECIDE / SPREAD) ──

    /** Stories a bot keeps at most (the newest); the oldest goes first. */
    static final int STORIES_PER_BOT = 4;
    /** Bots that hold stories at most (least recently told first out): 4,096 x 4 small records, memory only. */
    static final int STORY_HOLDERS = 4096;
    /** How long a heard story stays worth retelling (as the world news, {@code BotWorldGossip}). */
    static final long STORY_TTL_MS = TimeUnit.HOURS.toMillis(8);
    /**
     * The fact kinds a listener keeps: public acts ({@link Reputation} marks from the game's own KS / trade / gift / PQ
     * events), bond drama between bots and the world news (boss kills, drops, weddings, events). Never a chat note:
     * nothing a real player said in private travels this way. Small talk, spots and levels are not stories.
     */
    static final Set<String> KEEPABLE = Set.of("ks", "scam", "gift", "favor", "pq", "beef", "friends", "news");

    /**
     * A story a bot heard in a thread: the teller's exact {@link Fact}, who told it, and how many mouths deep it is
     * (1: heard from the one who knew it, at most {@link BotMemory#MAX_HOPS}).
     */
    public record Heard(Fact fact, int fromId, String fromName, int hops, long at) {
    }

    private static final java.util.LinkedHashMap<Integer, java.util.ArrayDeque<Heard>> stories =
            new java.util.LinkedHashMap<>(256, .75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, java.util.ArrayDeque<Heard>> eldest) {
                    return size() > STORY_HOLDERS;
                }
            };

    /** Whether a listener may keep this story: a public fact about people, of a kind worth retelling. */
    static boolean keepable(Fact fact) {
        if (fact == null || !fact.person() || !KEEPABLE.contains(fact.kind())) {
            return false;
        }
        int s = fact.subjectId();
        return s == 0 || BotHelpers.isArtificial(s) || !optedOut(s); // @botdrama off: nobody passes it on
    }

    /** Whether a real player has {@code @botdrama off} (a seam for tests). */
    private static volatile java.util.function.IntPredicate optedOut = BotGossip::optedOutNow;

    private static boolean optedOut(int charId) {
        return optedOut.test(charId);
    }

    private static boolean optedOutNow(int charId) {
        try {
            return soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity.optedOut(charId);
        } catch (RuntimeException | LinkageError e) {
            return true; // unknown: keep nothing about a real player
        }
    }

    /**
     * The GOSSIP thread is over: {@code listener} keeps {@code teller}'s story, word for word (same about, opener and
     * names), one hop deeper than the teller held it. Memory only, bounded per bot and in all. The kept story, or null
     * when it is not one to keep (small talk, a private matter, too many hops, the listener's own, already known).
     */
    public static Heard keep(int listenerId, int tellerId, String tellerName, Fact fact, long now) {
        if (listenerId == tellerId || !keepable(fact) || fact.subjectId() == listenerId) {
            return null;
        }
        synchronized (stories) {
            Heard known = heardOf(tellerId, fact.about(), now);
            int hops = known == null ? 1 : known.hops() + 1;
            if (hops > BotMemory.MAX_HOPS) {
                return null;
            }
            java.util.ArrayDeque<Heard> mine = stories.computeIfAbsent(listenerId, k -> new java.util.ArrayDeque<>());
            for (Heard h : mine) {
                if (h.fact().about().equals(fact.about())) {
                    return null; // it knew this one already
                }
            }
            Heard kept = new Heard(fact, tellerId, tellerName, hops, now);
            mine.addLast(kept);
            while (mine.size() > STORIES_PER_BOT) {
                mine.pollFirst();
            }
            return kept;
        }
    }

    /** The fresh stories this bot heard, oldest first (a copy). */
    public static List<Heard> heard(int botId, long now) {
        synchronized (stories) {
            java.util.ArrayDeque<Heard> mine = stories.get(botId);
            if (mine == null) {
                return List.of();
            }
            mine.removeIf(h -> now - h.at() > STORY_TTL_MS);
            return List.copyOf(mine);
        }
    }

    /** The fresh story this bot heard with this subject line, or null. */
    public static Heard heardOf(int botId, String about, long now) {
        for (Heard h : heard(botId, now)) {
            if (h.fact().about().equals(about)) {
                return h;
            }
        }
        return null;
    }

    /**
     * Stories {@code teller} heard and may pass on to {@code other}: never back to the bot that told it, never a story
     * about {@code other} itself, never in front of a real player it is about unless the in-front gate allows it. The
     * facts stay exact; the opener says it is hearsay ("heard Gm ksed Nayrb") and may name only the story's people.
     */
    static void heardFacts(Character teller, Character other, Collection<Character> present, SocialProfile p, long now,
                           List<Fact> facts, List<Double> weights) {
        for (Heard h : heard(teller.getId(), now)) {
            Fact f = h.fact();
            if (h.fromId() == other.getId() || f.subjectId() == other.getId() || f.subjectId() == teller.getId()
                    || f.names().contains(other.getName()) || f.names().contains(teller.getName()) || !keepable(f)) {
                continue;
            }
            if (f.negative() && f.subjectId() != 0 && !BotHelpers.isArtificial(f.subjectId())) {
                Character there = presentChr(present, f.subjectId());
                if (there != null && !gossipInFront.test(teller, there)) {
                    continue;
                }
            }
            facts.add(new Fact(f.kind(), f.about(), retellOpener(f.about(), f.about().hashCode() ^ teller.getId()),
                    f.names(), f.subjectId(), f.negative()));
            double fresh = Math.max(0.2, 1 - (double) (now - h.at()) / STORY_TTL_MS);
            weights.add((1 + p.socialPull() + (f.negative() ? p.rivalPull() : 0)) * 1.5 * fresh);
        }
    }

    /** The hearsay opener for a retold story: its subject line, as heard ("heard Gm ksed Nayrb"). */
    static String retellOpener(String about, int seed) {
        String[] pool = {"heard {A}", "did u hear {A}", "apparently {A} lol", "ppl say {A}"};
        return pool[Math.floorMod(seed, pool.length)].replace("{A}", about);
    }

    /** Test seam: forget every kept story; {@code testOptedOut} who has {@code @botdrama off} (null: the real check). */
    public static void clearStoriesForTest(java.util.function.IntPredicate testOptedOut) {
        synchronized (stories) {
            stories.clear();
        }
        optedOut = testOptedOut != null ? testOptedOut : BotGossip::optedOutNow;
    }

    // ── test seams ──

    /** Test seam: Bartle points, bonds, names and the in-front gate (nulls restore the real ones). */
    public static void useForTest(IntFunction<SocialProfile> testProfiles, IntFunction<List<Integer>> testBonded,
                                  IntFunction<String> testNames, BiPredicate<Character, Character> testGate) {
        profiles = testProfiles != null ? testProfiles : BotGossip::profileOf;
        bonded = testBonded != null ? testBonded : BotGossip::bondsOf;
        names = testNames != null ? testNames : BotGossip::nameOf;
        gossipInFront = testGate != null ? testGate : BotGossip::gate;
        lastStepAt = 0;
    }
}
