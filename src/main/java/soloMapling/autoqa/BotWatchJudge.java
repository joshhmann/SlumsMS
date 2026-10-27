package soloMapling.autoqa;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * 1.6.0 (owner 2026-10-26): "observe player bot behaviours and ensure they are acting". The pure half of the bot
 * watch ({@link BotWatch}): it turns one bot's track (a {@link Sample} every 30 s) into a verdict with a reason, picks
 * the bots to watch stratified by what they are doing, and sums a run up. No game classes: everything it knows is in
 * the samples, so every rule has a unit test.
 *
 * <p>A track is cut into segments of one activity (what the brain or the bot's phase says it is doing). Each segment
 * is judged with the first sample after it (an activity often ends the moment it succeeds: a RESTOCK trip ends when
 * the bot is back grinding, a MOVE_MAP when it arrives), and the bot's verdict is its worst segment's.
 */
public final class BotWatchJudge {

    public enum Verdict {
        /** The activity turned into something that looks like it. */
        OK,
        /** It said X but nothing X-like happened. */
        MISMATCH,
        /** Logged out in the middle of a commitment (a trip, a party, a channel move). */
        LOGGED_OUT,
        /** Nothing changed at all for {@link Limits#idleMs} while it was not resting. */
        IDLE,
        /** A position jump on one map that a walk cannot explain, where a real player could see it. */
        TELEPORTY;

        /** Worst first: a visible teleport, then a frozen bot, then a wrong one, then a dropped commitment. */
        int rank() {
            return switch (this) {
                case OK -> 0;
                case LOGGED_OUT -> 1;
                case MISMATCH -> 2;
                case IDLE -> 3;
                case TELEPORTY -> 4;
            };
        }
    }

    /** The Free Market entrance and its rooms. */
    static final int FM_FIRST = 910000000, FM_LAST = 910000022;

    /** Resting: idle is the point. */
    static final Set<String> REST = Set.of("BREAK", "FISH", "LOG_OUT");
    /** Town stays: off-screen the movement driver parks a town bot (no fidget, no chat), so still is fine there. */
    static final Set<String> TOWN_STAY = Set.of("HANG_OUT", "SHOW_OFF", "FREE_MARKET", "SELL", "PARTY_QUEST",
            "INVITE_PQ", "NONE");
    /** Logging out during these drops something the bot set out to do. */
    static final Set<String> COMMITMENT = Set.of("RESTOCK", "SELL", "FREE_MARKET", "MOVE_MAP", "PARTY_QUEST", "EVENT",
            "HELP_FRIEND", "FISH", "CHANGE_CHANNEL", "INVITE_PQ");

    /**
     * The judge's numbers.
     *
     * @param idleMs        nothing changed this long (and not resting) = IDLE; 10 min, or 80% of a shorter watch
     * @param moveTolPx     a position change under this is a fidget in place, not a move
     * @param minJudgeMs    a segment shorter than this is "too short to judge" unless it already shows its proof
     * @param progressMs    a GRIND this long with no EXP and no kill is a MISMATCH (unobserved grinders accrue EXP
     *                      every macro tick, observed ones kill within a minute or two)
     * @param travelMs      an unfinished trip that keeps moving is still "on the way" up to this long
     */
    public record Limits(long idleMs, int moveTolPx, long minJudgeMs, long progressMs, long travelMs) {
        public static Limits defaults() {
            return new Limits(10 * 60_000L, 8, 2 * 60_000L, 3 * 60_000L, 10 * 60_000L);
        }

        /** 10 min idle, or 80% of a watch shorter than 12.5 min (else a 10-minute watch could never call IDLE). */
        public static Limits forWatch(long watchMs) {
            Limits d = defaults();
            long idle = Math.max(2 * 60_000L, Math.min(d.idleMs(), watchMs * 8 / 10));
            return new Limits(idle, d.moveTolPx(), d.minJudgeMs(), d.progressMs(), d.travelMs());
        }
    }

    /**
     * One look at one bot (every 30 s). Read from memory only.
     *
     * @param t          when (epoch ms)
     * @param online     still logged in; the other fields are meaningless when false
     * @param activity   GRIND, HANG_OUT ... (LifeDecision.Activity names), or NONE for a bot without a brain
     * @param target     the map the activity heads for, -1 when none
     * @param kills      kills counted since the watch began (BotAttackStats hook); EXP is the proxy off-screen
     * @param pots       HP + MP potions held
     * @param etcSlots   ETC slots in use
     * @param equipSlots EQUIP slots in use
     * @param chat       the last line it said (null: nothing since the watch began)
     * @param chatAt     when it said it (0: never)
     * @param sitting    on a chair
     * @param town       the map is a town (map.isTown())
     * @param mobMap     the map has mob spawns (MapMobIndex)
     * @param watched    a real player is on the map (the movement driver's FULL tier)
     * @param jumpPx     how far past a walk the largest same-map position jump since the last sample went (0: none)
     * @param jumpWatched that jump happened while the map was watched
     */
    public record Sample(long t, boolean online, int channel, int map, int x, int y, String activity, int target,
                         String phase, int level, long exp, long meso, int kills, int pots, int etcSlots,
                         int equipSlots, String chat, long chatAt, boolean party, boolean sitting, boolean town,
                         boolean mobMap, boolean watched, int jumpPx, boolean jumpWatched, boolean shop) {

        public static Sample offline(long t) {
            return new Sample(t, false, 0, 0, 0, 0, "OFFLINE", -1, null, 0, 0, 0, 0, 0, 0, 0, null, 0, false, false,
                    false, false, false, 0, false, false);
        }

        boolean fm() {
            return map >= FM_FIRST && map <= FM_LAST;
        }

        /** For the JSON track: short keys, defaults left out. */
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("t", t);
            m.put("on", online);
            if (!online) {
                return m;
            }
            m.put("ch", channel);
            m.put("map", map);
            m.put("x", x);
            m.put("y", y);
            m.put("act", activity);
            if (target > 0) {
                m.put("target", target);
            }
            if (phase != null) {
                m.put("phase", phase);
            }
            m.put("lv", level);
            m.put("exp", exp);
            m.put("meso", meso);
            m.put("kills", kills);
            m.put("pots", pots);
            m.put("etc", etcSlots);
            m.put("equip", equipSlots);
            if (chat != null) {
                m.put("chat", chat);
                m.put("chatAt", chatAt);
            }
            m.put("party", party);
            m.put("sit", sitting);
            m.put("town", town);
            m.put("mobs", mobMap);
            m.put("watched", watched);
            if (shop) {
                m.put("shop", true);
            }
            if (jumpPx > 0) {
                m.put("jumpPx", jumpPx);
                m.put("jumpWatched", jumpWatched);
            }
            return m;
        }
    }

    /** One activity stretch and what the judge made of it. */
    public record Segment(String activity, long from, long to, int samples, Verdict verdict, String reason) {
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("activity", activity);
            m.put("from", from);
            m.put("to", to);
            m.put("samples", samples);
            m.put("verdict", verdict.name());
            m.put("reason", reason);
            return m;
        }
    }

    public record Judgement(Verdict verdict, String reason, List<Segment> segments, int jumpsOffscreen) {
    }

    private BotWatchJudge() {
    }

    // ── Teleport math (the sampler's 2-second position ticks) ─────────────────────────────────────────────

    /**
     * How far (px) a same-map move over {@code gapMs} goes past what a bot can cover, 0 when a move explains it.
     * A walk with Haste tops out near 175 px/s (v83 speed 140); Flash Jump and a mage's Teleport near 400 px/s, so
     * sideways and upward moves get 400 px/s plus 300 px of slack (a knock-back, a jump arc caught mid-air). Falls
     * are fast (terminal speed ~670 px/s), so downward gets 800 px/s. An in-map portal is a legal jump: see
     * {@link #excessJump(int, int, int, int, long, List)}.
     */
    public static int excessJump(int x0, int y0, int x1, int y1, long gapMs) {
        long gap = Math.max(1, gapMs);
        double side = 300 + 0.4 * gap, down = 300 + 0.8 * gap;
        int dx = Math.abs(x1 - x0), dy = y1 - y0;
        double over = Math.max(dx - side, dy < 0 ? -dy - side : dy - down);
        return over > 0 ? (int) Math.round(over) : 0;
    }

    /**
     * As {@link #excessJump(int, int, int, int, long)}, but a move a walk into one of the map's in-map portals, the
     * portal ride and a walk on from its exit explains is no jump. TEST bot watch 2026-10-27 (JellyHyper 4844 px,
     * datoy 5312 px on Henesys, Maple3 1964 px on Ludibrium, all "with a player there"): Henesys' hidden portals
     * hp00 (1302,93 to 5169,92) and hp01 (-377,273 to 5801,454) and Ludibrium's ph01 (-2430,-18 to 544,-257) carry a
     * player across the town in one step, and the bots' nav graph walks them like a player does. {@code links} holds
     * each in-map portal as {inX, inY, outX, outY}; the walk allowance is the plain one, shared by both legs.
     */
    public static int excessJump(int x0, int y0, int x1, int y1, long gapMs, List<int[]> links) {
        int best = excessJump(x0, y0, x1, y1, gapMs);
        if (best == 0 || links == null) {
            return best;
        }
        long gap = Math.max(1, gapMs);
        double side = 300 + 0.4 * gap, down = 300 + 0.8 * gap;
        for (int[] l : links) {
            int dx = Math.abs(l[0] - x0) + Math.abs(x1 - l[2]);
            int dy = Math.abs(l[1] - y0) + Math.abs(y1 - l[3]);
            double over = Math.max(dx - side, dy - down);
            best = Math.min(best, over > 0 ? (int) Math.round(over) : 0);
        }
        return best;
    }

    // ── Picking ───────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Picks up to {@code n} bot ids so every activity in use gets some: round-robin over the activities (rarest
     * first, so a lone PARTY_QUEST bot is never crowded out by 1,500 grinders), each activity's bots in a shuffled
     * order. Returns id -> the activity it was picked for.
     */
    public static Map<Integer, String> stratify(Map<String, List<Integer>> byActivity, int n, Random rng) {
        List<Map.Entry<String, List<Integer>>> groups = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : new TreeMap<>(byActivity).entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) {
                continue;
            }
            List<Integer> ids = new ArrayList<>(e.getValue());
            Collections.shuffle(ids, rng);
            groups.add(Map.entry(e.getKey(), ids));
        }
        groups.sort((a, b) -> Integer.compare(a.getValue().size(), b.getValue().size()));
        Map<Integer, String> out = new LinkedHashMap<>();
        int[] next = new int[groups.size()];
        boolean any = true;
        while (out.size() < n && any) {
            any = false;
            for (int g = 0; g < groups.size() && out.size() < n; g++) {
                List<Integer> ids = groups.get(g).getValue();
                if (next[g] < ids.size()) {
                    out.put(ids.get(next[g]++), groups.get(g).getKey());
                    any = true;
                }
            }
        }
        return out;
    }

    // ── Judging ───────────────────────────────────────────────────────────────────────────────────────────

    /** One bot's track, oldest first. */
    public static Judgement judge(List<Sample> track, Limits lim) {
        List<Segment> segs = new ArrayList<>();
        int offscreenJumps = 0;
        String loggedOut = null;
        Sample lastOnline = null;
        int i = 0;
        while (i < track.size()) {
            Sample s = track.get(i);
            if (!s.online()) {
                if (lastOnline != null && loggedOut == null) {
                    loggedOut = logoutReason(lastOnline);
                    long gone = s.t();
                    segs.add(new Segment("OFFLINE", gone, gone, 1,
                            loggedOut.startsWith("!") ? Verdict.LOGGED_OUT : Verdict.OK,
                            loggedOut.startsWith("!") ? loggedOut.substring(1) : loggedOut));
                }
                lastOnline = null;
                i++;
                continue;
            }
            int j = i;
            while (j < track.size() && track.get(j).online() && track.get(j).activity().equals(s.activity())) {
                j++;
            }
            List<Sample> seg = track.subList(i, j);
            Sample after = j < track.size() && track.get(j).online() ? track.get(j) : null;
            for (Sample x : seg) {
                if (x.jumpPx() > 0 && !x.jumpWatched()) {
                    offscreenJumps++;
                }
            }
            segs.add(judgeSegment(seg, after, lim, i == 0));
            lastOnline = seg.get(seg.size() - 1);
            i = j;
        }
        Segment worst = null;
        for (Segment sg : segs) {
            if (worst == null || sg.verdict().rank() > worst.verdict().rank()) {
                worst = sg;
            }
        }
        if (worst == null) {
            return new Judgement(Verdict.OK, "no samples", segs, offscreenJumps);
        }
        String reason = worst.verdict() == Verdict.OK && segs.size() > 1
                ? segs.size() + " stretches, all fine; last: " + segs.get(segs.size() - 1).reason()
                : (segs.size() > 1 ? worst.activity() + ": " : "") + worst.reason();
        return new Judgement(worst.verdict(), reason, segs, offscreenJumps);
    }

    /** "!" + why when the logout dropped a commitment, else a plain note. */
    static String logoutReason(Sample last) {
        if (COMMITMENT.contains(last.activity()) && !restockDone(last) && !pqStopDone(last)) {
            return "!logged out in the middle of " + last.activity() + " on map " + last.map();
        }
        if (last.party()) {
            return "!logged out while in a party (" + last.activity() + " on map " + last.map() + ")";
        }
        return "logged out after " + last.activity() + " (session over)";
    }

    /**
     * TEST bot watch 2026-10-27 (hermitorbis): a PARTY_QUEST is the trip to the lobby and the stay there looking for a
     * party (TrainingBot.onErrand holds the logout through both). Its label stays on while it walks back out to its
     * grind; from there a logout ends the session like any other. A party with a player is judged on its own below.
     */
    static boolean pqStopDone(Sample last) {
        return "PARTY_QUEST".equals(last.activity()) && last.phase() != null
                && !"LIFE_TRAVEL".equals(last.phase()) && !"LIFE_DWELL".equals(last.phase());
    }

    /**
     * TEST t-bw-1 2026-10-26 (roguez): a restock's life activity stays RESTOCK until the bot is back on its grind, so
     * the town stay after the shopping read as "logged out in the middle of RESTOCK". In IN_TOWN (the store visit is
     * over, or the walk to the shopkeeper, which the director now waits for: TrainingBot.onErrand) or on the way back
     * out from a store the errand is done; a logout there ends a session in town like a player's.
     */
    static boolean restockDone(Sample last) {
        return "RESTOCK".equals(last.activity()) && ("IN_TOWN".equals(last.phase()) || "SHOP_RETURN".equals(last.phase()));
    }

    static Segment judgeSegment(List<Sample> seg, Sample after, Limits lim, boolean firstOfTrack) {
        Sample first = seg.get(0), lastSeg = seg.get(seg.size() - 1);
        List<Sample> view = new ArrayList<>(seg);
        if (after != null) {
            view.add(after);
        }
        Sample last = view.get(view.size() - 1);
        String act = first.activity();
        long dur = last.t() - first.t();
        Segment base = new Segment(act, first.t(), lastSeg.t(), seg.size(), Verdict.OK, "");

        // TELEPORTY: a jump in this stretch while a player could see it (a segment's first sample's jump happened
        // before it began, under the activity before; it still happened on screen, so it counts once, here)
        for (int k = firstOfTrack ? 1 : 0; k < seg.size(); k++) {
            Sample s = seg.get(k);
            if (s.jumpPx() > 0 && s.jumpWatched()) {
                return with(base, Verdict.TELEPORTY, "jumped " + s.jumpPx() + " px past a walk on map " + s.map()
                        + " with a player there");
            }
        }

        int target = targetOf(seg);
        Facts f = facts(view, lim, target);

        // TEST bot watch 2026-10-27 (NoodleDagger on a chair 22 min, CloudPet 13 min parked in El Nath off-screen): the
        // excuses read the still stretch itself, not the samples around it (the walk in before it, the next
        // activity's first sample after it), which are moving by definition and broke "all sat" / "all in town"
        int[] run = longestStillRun(view, lim);
        long still = view.get(run[1]).t() - view.get(run[0]).t();
        if (still >= lim.idleMs() && !restingWhileStill(view.subList(run[0], run[1] + 1), lim, act)) {
            return with(base, Verdict.IDLE, "nothing changed for " + min(still) + " (no move, EXP, kill, chat, meso "
                    + "or item change) during " + act + " on map " + view.get(run[0]).map() // where it stood still
                    + (view.get(run[0]).watched() ? ", on screen" : ", off-screen"));
        }

        String ok = null, bad = null;
        boolean shortSeg = dur < lim.minJudgeMs();
        boolean onTheWay = target > 0 && !f.reached && (f.moved || f.mapChanged) && dur <= lim.travelMs();
        switch (act) {
            case "GRIND" -> {
                if (f.expUp || f.killsUp) {
                    ok = "gained " + (f.killsUp ? (last.kills() - first.kills()) + " kills" : "")
                            + (f.killsUp && f.expUp ? " and " : "") + (f.expUp ? "EXP" : "") + " on map " + last.map();
                } else if (onTheWay && !f.mostlyMob) {
                    ok = "on the way to its grind map " + target;
                } else if (dur < lim.progressMs()) {
                    ok = "too short to judge (" + sec(dur) + ")";
                } else if (first.level() >= 200 && f.mostlyMob && f.moved) {
                    ok = "level 200 (no EXP to gain), moving on a mob map";
                } else {
                    bad = f.mostlyMob ? "on mob map " + last.map() + " but no EXP or kill in " + min(dur)
                            : "said GRIND but spent " + min(dur) + " off any mob map (last " + last.map() + ")";
                }
            }
            case "RESTOCK" -> {
                // a trip to town also sells junk (the 1.6.0 bag retreat goes the same way): fewer items is a shop visit
                boolean bought = f.potsUp || f.mesoDown || f.itemsDown;
                if (bought) {
                    ok = (f.potsUp ? "potions " + first.pots() + " -> " + f.maxPots
                            : f.mesoDown ? "spent mesos" : "sold items")
                            + (f.inTownOrShop ? " after a town visit" : "");
                } else if (shortSeg || (f.moved || f.mapChanged) && dur <= lim.travelMs()) {
                    ok = "on its restock trip (" + sec(dur) + ")";
                } else {
                    bad = f.inTownOrShop ? "went to town but bought nothing in " + min(dur)
                            : "never reached a town or shop in " + min(dur);
                }
            }
            case "SELL" -> {
                if (f.mesoUp || f.itemsDown) {
                    ok = (f.mesoUp ? "mesos " + first.meso() + " -> " + last.meso() : "")
                            + (f.mesoUp && f.itemsDown ? ", " : "")
                            + (f.itemsDown ? "items " + (first.etcSlots() + first.equipSlots()) + " -> "
                            + (last.etcSlots() + last.equipSlots()) + " slots" : "");
                } else if (f.shop) {
                    // TEST 2026-10-26 (t-bw-1): a merchant bot's shop can sit 10 min without a buyer; it is selling
                    ok = "has its shop open" + (f.inFm ? " in the Free Market (map " + f.fmMap + ")" : "");
                } else if (shortSeg || onTheWay) {
                    ok = "on its way to sell (" + sec(dur) + ")";
                } else if (firstOfTrack && f.inFm && "LIFE_DWELL".equals(first.phase())) {
                    // TEST bot watch 2026-10-27 (aranmystic94): a SELL sells its junk the moment it arrives
                    // (TrainingBot.doLifeDwell); this one sold 37 items at 07:45, four minutes before the watch began
                    ok = "already in the Free Market when the watch began (it sells on arrival)";
                } else {
                    bad = (f.inFm ? "in the Free Market " : "") + "no meso gain and no item gone in " + min(dur);
                }
            }
            case "FREE_MARKET" -> {
                if (f.inFm) {
                    ok = "in the Free Market (map " + f.fmMap + ")";
                } else if (shortSeg || onTheWay) {
                    ok = "on its way to the Free Market (" + sec(dur) + ")";
                } else {
                    bad = "said FREE_MARKET but never entered it in " + min(dur) + " (last map " + last.map() + ")";
                }
            }
            case "HANG_OUT", "SHOW_OFF" -> {
                if (f.inTown && (f.moved || f.chatted || f.sat)) {
                    ok = "in town and " + (f.chatted ? "chatted" : f.sat ? "sat" : "moved");
                } else if (f.inTown && !f.anyWatched) {
                    ok = "in town off-screen (parked by the movement driver until a player comes)";
                } else if (shortSeg || onTheWay) {
                    ok = "on its way to town (" + sec(dur) + ")";
                } else if (f.inTown) {
                    bad = "in town on screen but never moved, chatted or sat in " + min(dur);
                } else {
                    bad = "said " + act + " but was not in a town for " + min(dur) + " (last map " + last.map() + ")";
                }
            }
            case "MOVE_MAP", "HELP_FRIEND", "PARTY_QUEST", "EVENT" -> {
                boolean party = ("PARTY_QUEST".equals(act) || "HELP_FRIEND".equals(act)) && f.party;
                if (f.reached || party) {
                    ok = f.reached ? "reached map " + target : "in a party";
                } else if (target <= 0 && "MOVE_MAP".equals(act) && f.mapChanged) {
                    ok = "moved to map " + last.map();
                } else if (shortSeg || onTheWay) {
                    ok = "on its way" + (target > 0 ? " to map " + target : "") + " (" + sec(dur) + ")";
                } else {
                    bad = target > 0 ? "never reached map " + target + " in " + min(dur)
                            + " (last " + last.map() + ")" : "no target map and no move in " + min(dur);
                }
            }
            case "FISH" -> {
                if (f.sat || f.reached) {
                    ok = f.sat ? "seated to fish" : "at the fishing spot";
                } else if (shortSeg || onTheWay) {
                    ok = "on its way to fish (" + sec(dur) + ")";
                } else {
                    bad = "said FISH but never sat or reached the water in " + min(dur);
                }
            }
            case "CHANGE_CHANNEL" -> {
                if (f.channelChanged) {
                    ok = "moved to channel " + last.channel();
                } else if (shortSeg) {
                    ok = "too short to judge (" + sec(dur) + ")";
                } else {
                    bad = "said CHANGE_CHANNEL but stayed on channel " + first.channel() + " for " + min(dur);
                }
            }
            case "INVITE_PQ", "INVITE_GUILD" -> {
                if (f.chatted || f.party) {
                    ok = f.party ? "in a party" : "talked";
                } else if (shortSeg) {
                    ok = "too short to judge (" + sec(dur) + ")";
                } else {
                    bad = "said " + act + " but neither talked nor partied in " + min(dur);
                }
            }
            case "BREAK", "LOG_OUT" -> ok = "BREAK".equals(act) ? "resting" + (f.sat ? " on a chair" : "")
                    : "on its way out";
            default -> ok = "no rule for " + act + (f.moved ? "; moved" : "");
        }
        return bad != null ? with(base, Verdict.MISMATCH, bad) : with(base, Verdict.OK, ok);
    }

    private static Segment with(Segment s, Verdict v, String reason) {
        return new Segment(s.activity(), s.from(), s.to(), s.samples(), v, reason);
    }

    /** What happened over a stretch (and the sample after it). */
    private record Facts(boolean moved, boolean mapChanged, boolean channelChanged, boolean expUp, boolean killsUp,
                         boolean chatted, boolean sat, boolean party, boolean inTown, boolean inTownOrShop,
                         boolean inFm, int fmMap, boolean mostlyMob, boolean reached, boolean potsUp, int maxPots,
                         boolean mesoDown, boolean mesoUp, boolean itemsDown, boolean anyWatched, boolean shop) {
    }

    /** The stretch's destination: the latest one it named (a MOVE_MAP picks its map a tick after it starts). */
    static int targetOf(List<Sample> seg) {
        int t = -1;
        for (Sample s : seg) {
            if (s.target() > 0) {
                t = s.target();
            }
        }
        return t;
    }

    private static Facts facts(List<Sample> v, Limits lim, int target) {
        Sample a = v.get(0), z = v.get(v.size() - 1);
        boolean moved = false, mapChanged = false, ch = false, chatted = false, sat = false, party = false;
        boolean town = false, townOrShop = false, fm = false, reached = false, mesoDown = false, watched = false;
        boolean shop = false;
        int fmMap = -1, mob = 0, maxPots = a.pots();
        for (int k = 0; k < v.size(); k++) {
            Sample s = v.get(k);
            if (k > 0) {
                Sample p = v.get(k - 1);
                if (s.map() != p.map() || Math.abs(s.x() - p.x()) > lim.moveTolPx()
                        || Math.abs(s.y() - p.y()) > lim.moveTolPx()) {
                    moved = true;
                }
                if (s.chatAt() > p.chatAt()) {
                    chatted = true;
                }
            }
            mapChanged |= s.map() != a.map();
            ch |= s.channel() != a.channel();
            sat |= s.sitting();
            party |= s.party();
            town |= s.town();
            townOrShop |= s.town() || !s.mobMap();
            if (s.fm()) {
                fm = true;
                fmMap = s.map();
            }
            if (s.mobMap()) {
                mob++;
            }
            reached |= target > 0 && s.map() == target;
            maxPots = Math.max(maxPots, s.pots());
            mesoDown |= s.meso() < a.meso();
            watched |= s.watched();
            shop |= s.shop();
        }
        boolean expUp = z.level() > a.level() || z.level() == a.level() && z.exp() > a.exp();
        return new Facts(moved, mapChanged, ch, expUp, z.kills() > a.kills(), chatted, sat, party, town, townOrShop,
                fm, fmMap, mob * 2 >= v.size(), reached, maxPots > a.pots(), maxPots, mesoDown, z.meso() > a.meso(),
                z.etcSlots() + z.equipSlots() < a.etcSlots() + a.equipSlots(), watched, shop);
    }

    /** Two samples in a row with nothing different: same place, map and channel, EXP, kills, chat, mesos and bags. */
    static boolean same(Sample p, Sample s, int tol) {
        return s.map() == p.map() && s.channel() == p.channel() && Math.abs(s.x() - p.x()) <= tol
                && Math.abs(s.y() - p.y()) <= tol && s.level() == p.level() && s.exp() == p.exp()
                && s.kills() == p.kills() && s.chatAt() == p.chatAt() && s.meso() == p.meso() && s.pots() == p.pots()
                && s.etcSlots() == p.etcSlots() && s.equipSlots() == p.equipSlots() && s.sitting() == p.sitting();
    }

    /** The longest stretch (ms) over which nothing changed. */
    static long longestStill(List<Sample> v, Limits lim) {
        int[] run = longestStillRun(v, lim);
        return v.isEmpty() ? 0 : v.get(run[1]).t() - v.get(run[0]).t();
    }

    /** First and last index (inclusive) of the longest stretch of {@code v} in which nothing changed. */
    static int[] longestStillRun(List<Sample> v, Limits lim) {
        long best = 0;
        int start = 0;
        int[] run = {0, 0};
        for (int k = 1; k < v.size(); k++) {
            if (!same(v.get(k - 1), v.get(k), lim.moveTolPx())) {
                start = k;
            }
            if (v.get(k).t() - v.get(start).t() > best) {
                best = v.get(k).t() - v.get(start).t();
                run = new int[] {start, k};
            }
        }
        return run;
    }

    /**
     * Still is fine: resting (BREAK, FISH, on its way out), on a chair (an AFK camper), or a town stay off-screen
     * (the movement driver parks a town bot nobody is watching: no fidget, no chat until a player arrives).
     */
    static boolean restingWhileStill(List<Sample> v, Limits lim, String act) {
        if (REST.contains(act)) {
            return true;
        }
        boolean allSat = true, allOffscreenTown = true;
        for (Sample s : v) {
            allSat &= s.sitting();
            allOffscreenTown &= !s.watched() && (s.town() || !s.mobMap());
        }
        return allSat || TOWN_STAY.contains(act) && allOffscreenTown;
    }

    // ── A run ─────────────────────────────────────────────────────────────────────────────────────────────

    /** The run's totals: per activity (the one each bot was picked for) and over all. */
    public record Summary(int bots, Map<Verdict, Integer> totals, Map<String, Map<String, Integer>> byActivity) {
        public int count(Verdict v) {
            return totals.getOrDefault(v, 0);
        }

        public int okPercent() {
            return bots == 0 ? 0 : Math.round(100f * count(Verdict.OK) / bots);
        }

        public int idlePercent() {
            return bots == 0 ? 0 : Math.round(100f * count(Verdict.IDLE) / bots);
        }

        public String line(long minutes) {
            StringBuilder worst = new StringBuilder();
            byActivity.forEach((act, m) -> {
                int badN = m.get("bots") - m.get("ok");
                if (badN > 0) {
                    worst.append(worst.length() == 0 ? "" : ", ").append(act).append(' ').append(badN).append('/')
                            .append(m.get("bots"));
                }
            });
            return String.format(Locale.ROOT, "%d/%d bots OK (%d%%), idle %d, mismatch %d, teleporty %d, logged out "
                            + "%d over %d min%s", count(Verdict.OK), bots, okPercent(), count(Verdict.IDLE),
                    count(Verdict.MISMATCH), count(Verdict.TELEPORTY), count(Verdict.LOGGED_OUT), minutes,
                    worst.length() == 0 ? "" : "; not OK: " + worst);
        }
    }

    /** {@code pickedFor}: each judged bot's activity at pick time, with its verdict. */
    public static Summary summarize(List<Map.Entry<String, Verdict>> pickedFor) {
        Map<Verdict, Integer> totals = new LinkedHashMap<>();
        for (Verdict v : Verdict.values()) {
            totals.put(v, 0);
        }
        Map<String, Map<String, Integer>> by = new TreeMap<>();
        for (Map.Entry<String, Verdict> e : pickedFor) {
            totals.merge(e.getValue(), 1, Integer::sum);
            Map<String, Integer> m = by.computeIfAbsent(e.getKey(), k -> {
                Map<String, Integer> fresh = new LinkedHashMap<>();
                fresh.put("bots", 0);
                for (Verdict v : Verdict.values()) {
                    fresh.put(key(v), 0);
                }
                return fresh;
            });
            m.merge("bots", 1, Integer::sum);
            m.merge(key(e.getValue()), 1, Integer::sum);
        }
        return new Summary(pickedFor.size(), totals, by);
    }

    /** JSON key of a verdict: ok, idle, mismatch, teleporty, loggedOut. */
    static String key(Verdict v) {
        return v == Verdict.LOGGED_OUT ? "loggedOut" : v.name().toLowerCase(Locale.ROOT);
    }

    /** Fewest bots the auto-QA check judges before it calls a result (fewer = the world was too empty). */
    static final int CHECK_MIN_BOTS = 5;
    /**
     * t-bw-1's bar. 80% OK: a 10-minute watch catches some bots mid-change whose proof lands after it ends, and a few
     * honest MISMATCHes (a SELL in a quiet FM) are expected; below 80% something is broken. IDLE at most 10%: a bot
     * frozen for 8 of 10 minutes while working is the bug this watch exists for. TELEPORTY 0: it only counts jumps on
     * a map a player watches, which a player would see.
     */
    static final int CHECK_MIN_OK_PERCENT = 80, CHECK_MAX_IDLE_PERCENT = 10;

    /** Null when a run meets t-bw-1's bar, else what is wrong. */
    public static String checkProblem(Summary s) {
        if (s.bots() < CHECK_MIN_BOTS) {
            return "only " + s.bots() + " bots judged (need " + CHECK_MIN_BOTS + ")";
        }
        List<String> out = new ArrayList<>();
        if (s.okPercent() < CHECK_MIN_OK_PERCENT) {
            out.add(s.okPercent() + "% OK (need " + CHECK_MIN_OK_PERCENT + "%)");
        }
        if (s.count(Verdict.TELEPORTY) > 0) {
            out.add(s.count(Verdict.TELEPORTY) + " teleported in front of a player");
        }
        if (s.idlePercent() > CHECK_MAX_IDLE_PERCENT) {
            out.add(s.idlePercent() + "% idle (at most " + CHECK_MAX_IDLE_PERCENT + "%)");
        }
        return out.isEmpty() ? null : String.join("; ", out);
    }

    static String min(long ms) {
        return ms >= 60_000 ? String.format(Locale.ROOT, "%.1f min", ms / 60_000.0) : sec(ms);
    }

    static String sec(long ms) {
        return (ms / 1000) + " s";
    }
}
