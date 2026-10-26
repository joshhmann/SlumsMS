package soloMapling.ArtificialPlayer.BotDramaSystem;

import client.Character;
import net.server.Server;
import net.server.guild.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;
import soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds;
import soloMapling.ArtificialPlayer.BotGuildSystem.GuildRules;
import soloMapling.ArtificialPlayer.BotGuildSystem.GuildStore.GuildRow;
import soloMapling.ArtificialPlayer.BotGuildSystem.GuildStore.MemberRow;
import soloMapling.server.BotTiming;
import soloMapling.server.ExecutorServiceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 1.6.0 slice S7 "Beef": guild drama that grows out of bond drama. Two members of one bot guild who hold a grudge
 * (a RIVAL or NEMESIS bond, slice S2) fight in guild chat now and then; after {@link #STRIKES_TO_LEAVE} fights, or a
 * nemesis, the lower-ranked one may leave the guild - and, if the guild has a rival guild with room, join it (the
 * existing poach drama: its new guild welcomes it). Leaders (rank 1-2) never leave this way.
 *
 * <p>Rare and bounded: one roll every {@link #STEP_MS} (5 min); a fight at most once per guild per
 * {@link #SPAT_GAP_MS} (30 min) and {@link #SPATS_PER_HOUR} (6) an hour world-wide; a feud leave at most once per guild
 * per {@link #LEAVE_GAP_MS} (3 days) and {@link #LEAVES_PER_DAY} (2) a day world-wide. Only online members are read
 * (at most the guild's online bots x K bonds).
 */
public final class GuildFeuds {

    private static final Logger log = LoggerFactory.getLogger(GuildFeuds.class);

    static final long STEP_MS = TimeUnit.MINUTES.toMillis(5);
    static final long SPAT_GAP_MS = TimeUnit.MINUTES.toMillis(30);
    static final int SPATS_PER_HOUR = 6;
    static final double SPAT_CHANCE = 0.25;
    static final int STRIKES_TO_LEAVE = 3;
    static final double LEAVE_CHANCE = 0.3;
    static final long LEAVE_GAP_MS = TimeUnit.DAYS.toMillis(3);
    static final int LEAVES_PER_DAY = 2;
    static final double JOIN_RIVAL_CHANCE = 0.5;

    /** (lower id, higher id) -> fights so far. */
    private static final Map<Long, Integer> strikes = new ConcurrentHashMap<>();
    private static final Map<Long, Integer> leavesByDay = new ConcurrentHashMap<>();
    private static volatile long lastStepAt;

    private GuildFeuds() {
    }

    /** Two online members of one guild with a grudge between them. */
    record Feud(Character a, Character b, boolean nemesis) {
    }

    static long pair(int a, int b) {
        return ((long) Math.min(a, b) << 32) | (Math.max(a, b) & 0xffffffffL);
    }

    /** The feuds inside one guild among its online members: {@code a} holds the grudge. */
    static List<Feud> feuds(List<Character> online) {
        List<Feud> out = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        Map<Integer, Character> byId = new java.util.HashMap<>();
        for (Character c : online) {
            byId.put(c.getId(), c);
        }
        for (Character c : online) {
            for (BotBonds.View v : BotBonds.bondsOf(c.getId())) {
                Character other = byId.get(v.otherId());
                if (other != null && v.state().hostile() && seen.add(pair(c.getId(), other.getId()))) {
                    out.add(new Feud(c, other, v.state() == AffinityState.NEMESIS));
                }
            }
        }
        return out;
    }

    /** From the drama tick. */
    static void maybeStep(long now) {
        if (!BeefKit.enabled() || now - lastStepAt < STEP_MS) {
            return;
        }
        lastStepAt = now;
        ExecutorServiceManager.runAsync(() -> {
            try {
                step(now, ThreadLocalRandom.current(), false);
            } catch (RuntimeException e) {
                log.debug("Guild feud step failed", e);
            }
        });
    }

    /** One roll over every bot guild ({@code force}: GM / QA, every feud fights and the caps are skipped). What happened. */
    static List<String> step(long now, Random rng, boolean force) {
        List<String> out = new ArrayList<>();
        for (GuildRow g : BotGuilds.botGuilds()) {
            List<Feud> feuds = feuds(BotGuilds.onlineMembers(g.guildId()));
            if (feuds.isEmpty()) {
                continue;
            }
            Feud f = feuds.get(rng.nextInt(feuds.size()));
            if (!force && (rng.nextDouble() >= SPAT_CHANCE || !DramaKit.take("gf:spat:" + g.guildId(), now, SPAT_GAP_MS)
                    || !DramaKit.hourly("gf-spat", SPATS_PER_HOUR, now))) {
                continue;
            }
            out.add(fight(g, f, now, rng, force));
        }
        return out;
    }

    /** A fight in guild chat; after enough of them (or a nemesis) the lower-ranked one may leave. */
    static String fight(GuildRow g, Feud f, long now, Random rng, boolean force) {
        int n = strikes.merge(pair(f.a().getId(), f.b().getId()), 1, Integer::sum);
        BotGuilds.sayInGuild(f.a(), DramaKit.pick("GuildFeudSpat", Map.of("OTHER", f.b().getName())));
        BotTiming.after(2_500, () -> BotGuilds.sayInGuild(f.b(), DramaKit.pick("GuildFeudReply", Map.of("OTHER",
                f.a().getName()))));
        String line = "GUILD FEUD " + g.name() + ": " + f.a().getName() + " vs " + f.b().getName() + " (fight " + n
                + (f.nemesis() ? ", nemesis" : "") + ")";
        BeefKit.qaAny(line);
        if ((n >= STRIKES_TO_LEAVE || f.nemesis()) && (force || rng.nextDouble() < LEAVE_CHANCE)) {
            String left = maybeLeave(g, f, now, rng, force);
            if (left != null) {
                line += "; " + left;
            }
        }
        return line;
    }

    /** The lower-ranked of the two leaves (never a leader), maybe for the rival guild. What happened, or null. */
    static String maybeLeave(GuildRow g, Feud f, long now, Random rng, boolean force) {
        MemberRow ma = BotGuilds.membership(f.a().getId());
        MemberRow mb = BotGuilds.membership(f.b().getId());
        if (ma == null || mb == null) {
            return null;
        }
        Character leaver = ma.rank() >= mb.rank() ? f.a() : f.b();
        Character stayer = leaver == f.a() ? f.b() : f.a();
        MemberRow lm = leaver == f.a() ? ma : mb;
        if (lm.rank() <= 2) {
            return null; // a leader or jr. master keeps its guild
        }
        long day = Math.floorDiv(now, TimeUnit.DAYS.toMillis(1));
        if (!force && (leavesByDay.getOrDefault(day, 0) >= LEAVES_PER_DAY
                || !DramaKit.take("gf:leave:" + g.guildId(), now, LEAVE_GAP_MS))) {
            return null;
        }
        leavesByDay.merge(day, 1, Integer::sum);
        leavesByDay.keySet().removeIf(d -> d < day - 1);
        BotGuilds.sayInGuild(leaver, DramaKit.pick("GuildFeudLeave", Map.of("OTHER", stayer.getName())));
        BotGuilds.leave(leaver.getId(), "feud with " + stayer.getName());
        strikes.remove(pair(f.a().getId(), f.b().getId()));
        String out = leaver.getName() + " left " + g.name() + " over " + stayer.getName();
        GuildRow rival = g.rivalId() > 0 ? BotGuilds.guild(g.rivalId()) : null;
        if (rival != null && (force || rng.nextDouble() < JOIN_RIVAL_CHANCE) && roomIn(rival.guildId())
                && BotGuilds.join(leaver, rival.guildId(), 5, now)) {
            Map<String, String> t = Map.of("MEMBER", leaver.getName(), "OLD", g.name(), "GUILD", rival.name());
            Character welcome = GuildWars.speaker(rival.guildId());
            if (welcome != null && welcome.getId() != leaver.getId()) {
                BotTiming.after(4_000, () -> BotGuilds.sayInGuild(welcome, DramaKit.pick("PoachWelcome", t)));
            }
            out += " and joined the rival " + rival.name();
        }
        BeefKit.qaAny("GUILD FEUD LEAVE " + out);
        log.info("Guild feud: {}", out);
        return out;
    }

    private static boolean roomIn(int guildId) {
        try {
            Guild live = Server.getInstance().getGuild(guildId);
            return live == null || GuildRules.roomToPoach(live.getMembers().size(), live.getCapacity());
        } catch (RuntimeException e) {
            return false;
        }
    }

    static int strikesOf(int a, int b) {
        return strikes.getOrDefault(pair(a, b), 0);
    }

    static void resetForTest() {
        strikes.clear();
        leavesByDay.clear();
        lastStepAt = 0;
    }
}
