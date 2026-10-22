package soloMapling.ArtificialPlayer.BotGuildSystem;

import soloMapling.ArtificialPlayer.BotGuildSystem.GuildRules.Vibe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The bot guilds the world starts with (owner, 2026-10-21): about ten of 15-40 members - a few big, mostly mid-size,
 * a couple of tiny new ones - each with a name, an emblem, a leader and a vibe, drawn from the roster. Pure: the
 * founding itself is {@link BotGuilds#seed}.
 */
public final class GuildPlan {

    private GuildPlan() {
    }

    /**
     * One guild to found. Names follow the client's rule (3-12 letters). Emblem ids are stock v83 GuildMark ids
     * (BackGround 1000-1030, marks 2000-9000 ranges, colours 1-13). {@code rival} is another spec's name.
     */
    public record Spec(String name, Vibe vibe, int size, int minLevel, int maxLevel, int logoBG, int logoBGColor,
                       int logo, int logoColor, String rival, String notice) {
    }

    public static final List<Spec> SPECS = List.of(
            new Spec("Lionheart", Vibe.HARDCORE, 38, 60, 200, 1003, 1, 2000, 3, "Nightfall",
                    "boss runs every night. lv60+ whisper an officer"),
            new Spec("Moonlight", Vibe.CASUAL, 34, 20, 120, 1010, 8, 3004, 13, "SlumLords",
                    "chill guild, all levels, be nice"),
            new Spec("GoldenBazaar", Vibe.MERCHANT, 26, 25, 200, 1006, 4, 9002, 5, null,
                    "merchants and traders. pc your stuff in guild chat"),
            new Spec("SlumLords", Vibe.SOCIAL, 24, 15, 150, 1001, 12, 5018, 1, "Moonlight",
                    "hangout guild. fm ch1 most nights"),
            new Spec("Nightfall", Vibe.PVM, 24, 55, 200, 1014, 11, 2011, 9, "Lionheart",
                    "zak pap ht. bring pots"),
            new Spec("MapleLeaf", Vibe.SOCIAL, 22, 10, 90, 1008, 6, 3001, 6, null,
                    "friendly guild, we pq a lot"),
            new Spec("RedSnails", Vibe.CASUAL, 20, 10, 60, 1004, 2, 2005, 2, null,
                    "slow and steady. no drama"),
            new Spec("Ironclad", Vibe.HARDCORE, 18, 40, 150, 1017, 9, 4005, 10, null,
                    "grind grind grind"),
            new Spec("Sprouts", Vibe.NEWBIE, 8, 1, 35, 1012, 7, 3006, 7, null,
                    "new guild! everyone welcome"),
            new Spec("DawnRunners", Vibe.NEWBIE, 6, 10, 50, 1020, 5, 9008, 4, null,
                    "just started, help us grow"));

    /** A roster bot that can be put in a guild. */
    public record Candidate(int botId, int level) {
    }

    /** A founded guild's members: the leader first, then the two officers, then the rest. */
    public record Founding(Spec spec, List<Candidate> members) {
        public Candidate leader() {
            return members.get(0);
        }

        /** Guild rank of the member at this index: 1 leader, 2 two officers, 3 the next fifth, 5 everyone else. */
        public int rankAt(int index) {
            if (index == 0) {
                return 1;
            }
            if (index <= 2) {
                return 2;
            }
            return index <= 2 + members.size() / 5 ? 3 : 5;
        }
    }

    /**
     * Assigns roster bots to the guilds. Sizes shrink in proportion when the roster is too small to fill them all
     * with half the roster (the other half stays guildless: plenty of solo bots). Each guild takes bots in its level
     * band first, then the nearest levels. A guild that would get fewer than 3 is skipped.
     */
    public static List<Founding> assign(List<Candidate> roster, List<Spec> specs, Random rng) {
        int wanted = specs.stream().mapToInt(Spec::size).sum();
        double scale = Math.min(1.0, roster.size() * 0.5 / Math.max(1, wanted));
        List<Candidate> pool = new ArrayList<>(roster);
        java.util.Collections.shuffle(pool, rng);
        Set<Integer> taken = new HashSet<>();
        List<Founding> out = new ArrayList<>();
        for (Spec spec : specs) {
            int size = Math.max(spec.size() >= 10 ? 4 : 3, (int) Math.round(spec.size() * scale));
            List<Candidate> members = new ArrayList<>();
            for (Candidate c : pool) {
                if (members.size() >= size) {
                    break;
                }
                if (!taken.contains(c.botId()) && c.level() >= spec.minLevel() && c.level() <= spec.maxLevel()) {
                    members.add(c);
                }
            }
            if (members.size() < size) {
                int mid = (spec.minLevel() + Math.min(spec.maxLevel(), 200)) / 2;
                List<Candidate> rest = new ArrayList<>();
                for (Candidate c : pool) {
                    if (!taken.contains(c.botId()) && !members.contains(c)) {
                        rest.add(c);
                    }
                }
                rest.sort(Comparator.comparingInt(c -> Math.abs(c.level() - mid)));
                for (Candidate c : rest) {
                    if (members.size() >= size) {
                        break;
                    }
                    members.add(c);
                }
            }
            if (members.size() < 3) {
                continue;
            }
            members.sort(Comparator.comparingInt(Candidate::level).reversed()); // the strongest leads
            for (Candidate c : members) {
                taken.add(c.botId());
            }
            out.add(new Founding(spec, members));
        }
        return out;
    }

    /** Guild capacity: room for the members and some players (stock steps of 5, at most 100). */
    public static int capacityFor(int members) {
        int cap = (int) Math.ceil((members + 10) / 5.0) * 5;
        return Math.max(15, Math.min(100, cap));
    }
}
