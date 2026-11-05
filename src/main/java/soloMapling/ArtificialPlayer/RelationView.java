package soloMapling.ArtificialPlayer;

import client.Character;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotDramaSystem.BotRomance;
import soloMapling.ArtificialPlayer.BotDramaSystem.Romance;
import soloMapling.companions.OwnedAltService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 1.6.4 {@code @bonds} (stage 1): a read-only view of the asking player's own relationships, built from existing public
 * accessors. Owner rules 2026-11-04: friend words only (no hostile states, no numbers), one list with the same wording for
 * a bot and a real player (no "bot" label), exes and {@code @romance off} dating/engaged partners hidden, stock facts only
 * for real players (buddy, guild, family, marriage). Switch: {@code -Dslums.bonds}, default OFF.
 */
public final class RelationView {
    public static final String OFF_TEXT = "Bonds are not available yet.";
    static final int MAX_LINES = 12;
    static final int MAX_CHARS = 110;

    private RelationView() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean("slums.bonds");
    }

    /** Everything the view may read about one player. Hostile states and exes are offered here and filtered by the view. */
    public interface Source {
        List<String> buddyNames();
        /** Name to affinity state, every tie the player has (the view keeps FRIEND only). */
        Map<String, AffinityState> affinity();
        /** Name to romance stage, every row (the view drops EX). */
        Map<String, Romance.Stage> romances();
        boolean romanceOn();
        /** Stock marriage partner, or null. */
        String spouseName();
        String guildName();
        String seniorName();
        List<String> juniorNames();
        List<String> altNames();
    }

    public static List<String> build(Source s) {
        List<String> lines = new ArrayList<>();
        Set<String> partners = new LinkedHashSet<>();
        String spouse = s.spouseName();
        if (spouse != null) {
            lines.add("Married to " + spouse + ".");
            partners.add(spouse);
        }
        s.romances().forEach((name, stage) -> {
            if (stage == Romance.Stage.EX || partners.contains(name)) return;
            if (stage != Romance.Stage.MARRIED && !s.romanceOn()) return;
            partners.add(name);
            lines.add(switch (stage) {
                case DATING -> "Dating " + name + ".";
                case ENGAGED -> "Engaged to " + name + ".";
                default -> "Married to " + name + ".";
            });
        });
        Set<String> close = new LinkedHashSet<>();
        s.affinity().forEach((name, st) -> {
            if (st == AffinityState.FRIEND && !partners.contains(name)) close.add(name);
        });
        list(lines, "Good friends", close);
        Set<String> friends = new LinkedHashSet<>(s.buddyNames());
        friends.removeAll(close);
        friends.removeAll(partners);
        list(lines, "Friends", friends);
        if (s.guildName() != null) lines.add("Guild: " + s.guildName() + ".");
        if (s.seniorName() != null) lines.add("Family senior: " + s.seniorName() + ".");
        list(lines, "Family juniors", new LinkedHashSet<>(s.juniorNames()));
        list(lines, "Your alt" + (s.altNames().size() == 1 ? "" : "s"), new LinkedHashSet<>(s.altNames()));
        if (lines.isEmpty()) lines.add("No bonds yet. Make a friend, join a guild or start a family.");
        if (lines.size() > MAX_LINES) {
            int more = lines.size() - (MAX_LINES - 1);
            List<String> cut = new ArrayList<>(lines.subList(0, MAX_LINES - 1));
            cut.add("...and " + more + " more.");
            return cut;
        }
        return lines;
    }

    private static void list(List<String> out, String title, Set<String> names) {
        if (names.isEmpty()) return;
        StringBuilder sb = new StringBuilder(title).append(": ");
        int shown = 0;
        for (String n : names) {
            String tail = ", ..." + (names.size() - shown) + " more";
            if (shown > 0 && sb.length() + n.length() + 2 + tail.length() > MAX_CHARS) {
                sb.setLength(sb.length() - 2);
                out.add(sb.append(", and ").append(names.size() - shown).append(" more.").toString());
                return;
            }
            sb.append(n).append(", ");
            shown++;
        }
        sb.setLength(sb.length() - 2);
        out.add(sb.append('.').toString());
    }

    /** Reads the live accessors for a player. Touches the database (romance, alts): call off the packet thread. */
    public static Source of(Character p) {
        return new Source() {
            public List<String> buddyNames() {
                return p.getBuddylist().getBuddies().stream().filter(e -> e.isVisible()).map(e -> e.getName()).toList();
            }
            public Map<String, AffinityState> affinity() {
                Map<String, AffinityState> m = new java.util.LinkedHashMap<>();
                for (AffinityState st : AffinityState.values()) {
                    if (st == AffinityState.NEUTRAL) continue;
                    for (int id : BotAffinity.botsFeeling(p.getId(), st)) {
                        String n = BotBuddies.nameOf(id);
                        if (n != null) m.put(n, st);
                    }
                }
                return m;
            }
            public Map<String, Romance.Stage> romances() {
                Map<String, Romance.Stage> m = new java.util.LinkedHashMap<>();
                for (Romance r : BotRomance.all(p.getId())) {
                    String n = BotBuddies.nameOf(BotStore.BOT_ID_BASE + r.botId());
                    if (n != null) m.put(n, r.stage());
                }
                return m;
            }
            public boolean romanceOn() {
                return BotRomance.romanceOn(p.getId());
            }
            public String spouseName() {
                return p.getPartnerId() > 0 ? Character.getNameById(p.getPartnerId()) : null;
            }
            public String guildName() {
                return p.getGuild() == null ? null : p.getGuild().getName();
            }
            public String seniorName() {
                var fe = p.getFamilyEntry();
                return fe == null || fe.getSenior() == null ? null : fe.getSenior().getName();
            }
            public List<String> juniorNames() {
                var fe = p.getFamilyEntry();
                return fe == null ? List.of() : fe.getJuniors().stream().filter(j -> j != null).map(j -> j.getName()).toList();
            }
            public List<String> altNames() {
                try {
                    return OwnedAltService.getInstance().owned(p).stream().map(OwnedAltService.Candidate::name).toList();
                } catch (RuntimeException e) {
                    return List.of();
                }
            }
        };
    }
}
