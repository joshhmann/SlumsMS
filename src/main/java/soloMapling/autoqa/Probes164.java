package soloMapling.autoqa;

import client.Character;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrains;
import soloMapling.ArtificialPlayer.BotConversation;
import soloMapling.ArtificialPlayer.BotConvo.StateOfMind;
import soloMapling.ArtificialPlayer.BotConvo.TalkQa;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotAttackSystem.KillCalibration;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.ArtificialPlayer.GCMoveSystem.NavTrace;
import soloMapling.ArtificialPlayer.GCMoveSystem.NavTrapQa;
import soloMapling.inspect.BotInspectApi;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ticket 164 b224 (owner 2026-10-30): the two auto-QA checks two tickets promised and never got.
 * <ul>
 *   <li>Ticket 156, chat grounding: t-gr-1 proves the snapshot a reply is held to tells the truth about a fresh bot
 *   (its level, job, map; the player's level and name; never met), that true claims pass, that untrue ones (a level it
 *   is not, a town it is not in, "again" to a stranger) are rejected and counted, and that the delivery path swaps an
 *   untrue scripted line for a safe one - no chat model needed. t-gr-2 does it through the real chat model: a bot
 *   asked its level answers with its real level, and a forced untrue model draft is rejected, retried with the model
 *   and never said.</li>
 *   <li>Ticket 157, the tools it adopted: t-nv-2 checks the bots' world graph for one-way-in traps that are not in the
 *   committed baseline ({@link NavTrapQa}, the "no trap maps" check the adoption matrix promised), and t-nv-3 that
 *   the debug tools answer on the server: the bot inspect API (refuses a request without the secret; its view of a
 *   fresh bot is the bot), kill calibration and the nav path trace (a watched bot has a ring of its own moves).</li>
 * </ul>
 * Each probe stages fresh throwaway bots next to the pretend player and despawns them (and undoes any pin) itself;
 * the first line is PASS / FAIL / SKIP (a SKIP names the switch that is off). The verdict rules are static, for tests.
 */
final class Probes164 {

    static final List<String> NAMES = List.of("groundingTruth", "groundingLive", "trapMaps", "debugTools");

    private Probes164() {
    }

    static List<String> probe(String what, SyntheticPlayer p, String... a) {
        try {
            return switch (what) {
                case "groundingTruth" -> groundingTruth(p);
                case "groundingLive" -> groundingLive(p);
                case "trapMaps" -> trapMaps();
                case "debugTools" -> debugTools(p);
                default -> null;
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of("SKIP interrupted");
        } catch (Exception e) {
            return List.of("FAIL probe " + what + " threw " + e);
        }
    }

    // ── t-gr-1: the snapshot tells the truth and the checker holds a reply to it (no chat model) ──────────

    /** The level the fresh bots of the grounding checks are made at. */
    static final int GR_LEVEL = 35;

    /** A claim the probe holds to the snapshot: its text and whether the checker must let it through. */
    record Claim(String text, boolean grounded) {
    }

    /** Towns far from most staging spots, with the word players use: one the bot is not in is the untrue place. */
    static final List<Map.Entry<Integer, String>> FAR_TOWNS = List.of(Map.entry(240000000, "leafre"),
            Map.entry(250000000, "mu lung"), Map.entry(220000000, "ludi"));

    /**
     * The claims for a bot of {@code level} whose map's region is {@code region}: two true ones (its level, a plain
     * greeting) and three untrue ones (a level 37 above, a far town it is not in, "again" to a stranger, the last only
     * when the pair has never met).
     */
    static List<Claim> claims(int level, int region, boolean met) {
        List<Claim> out = new ArrayList<>();
        out.add(new Claim("im lvl " + level + " rn", true));
        out.add(new Claim("hey whats up", true));
        out.add(new Claim("im lvl " + (level + 37) + " rn", false));
        for (Map.Entry<Integer, String> t : FAR_TOWNS) {
            if (t.getKey() != region) {
                out.add(new Claim("im grinding in " + t.getValue() + " rn", false));
                break;
            }
        }
        if (!met) {
            out.add(new Claim("hey again! good to see u", false));
        }
        return out;
    }

    /** What the snapshot gets wrong about the truth (empty: it holds the truth). */
    static List<String> truthProblems(StateOfMind.Snapshot s, String botName, int botLevel, int botMap, String playerName,
                                      int playerLevel) {
        List<String> bad = new ArrayList<>();
        if (s == null) {
            bad.add("no snapshot for the bot");
            return bad;
        }
        if (!botName.equals(s.botName())) {
            bad.add("snapshot bot " + s.botName() + ", is " + botName);
        }
        if (s.botLevel() != botLevel) {
            bad.add("snapshot level " + s.botLevel() + ", bot is " + botLevel);
        }
        if (s.here() == null || s.here().id() != botMap) {
            bad.add("snapshot map " + (s.here() == null ? "none" : s.here().id()) + ", bot is on " + botMap);
        }
        if (!playerName.equals(s.playerName()) || s.playerLevel() != playerLevel) {
            bad.add("snapshot player " + s.playerName() + " lv " + s.playerLevel() + ", is " + playerName + " lv "
                    + playerLevel);
        }
        if (s.playerIsBot()) {
            bad.add("the snapshot takes the pretend player for a bot");
        }
        return bad;
    }

    /** What the checker got wrong about the claims: a true one rejected or an untrue one let through. */
    static List<String> claimProblems(List<Claim> claims, List<StateOfMind.Verdict> verdicts) {
        List<String> bad = new ArrayList<>();
        for (int i = 0; i < claims.size(); i++) {
            Claim c = claims.get(i);
            StateOfMind.Verdict v = verdicts.get(i);
            if (c.grounded() && !v.ok()) {
                bad.add("rejected a true line \"" + c.text() + "\" (" + v + ")");
            } else if (!c.grounded() && v.ok()) {
                bad.add("let an untrue line through: \"" + c.text() + "\"");
            }
        }
        return bad;
    }

    /** A counter from {@link StateOfMind#stats()} (0 when missing). */
    static long stat(Map<String, Object> stats, String key) {
        return stats != null && stats.get(key) instanceof Number n ? n.longValue() : 0;
    }

    static List<String> groundingTruth(SyntheticPlayer p) throws InterruptedException {
        if (!StateOfMind.checkOn()) {
            return List.of("SKIP the grounding checker is off (-Dsolomapling.convo.grounding=false)");
        }
        List<String> ev = new ArrayList<>();
        List<Character> spawned = new ArrayList<>();
        try {
            spawned.addAll(TalkQa.spawn(p.character(), 1, true, GR_LEVEL));
            if (spawned.isEmpty()) {
                return List.of("FAIL no fresh bot could be spawned next to the pretend player");
            }
            Character bot = spawned.get(0);
            Probes162.freeze(bot);
            Character me = p.character();
            StateOfMind.Snapshot s = BotConversation.qaSnapshot(bot, me);
            List<String> bad = new ArrayList<>(truthProblems(s, bot.getName(), bot.getLevel(), bot.getMapId(),
                    me.getName(), me.getLevel()));
            if (s == null) {
                return Probes160.verdict(bad, "", ev);
            }
            ev.add("bot " + bot.getName() + " lv " + bot.getLevel() + " " + s.botJob() + " on " + bot.getMapId()
                    + " (" + (s.here() == null ? "?" : s.here().name()) + "), player " + me.getName() + " lv "
                    + me.getLevel() + ", met " + s.met());
            Map<String, Object> before = StateOfMind.stats();
            List<Claim> claims = claims(bot.getLevel(), s.here() == null ? -1 : s.here().region(), s.met());
            List<StateOfMind.Verdict> verdicts = new ArrayList<>();
            int untrue = 0;
            for (Claim c : claims) {
                StateOfMind.Verdict v = StateOfMind.judge(c.text(), s, bot.getName() + " -> " + me.getName(), "qa");
                verdicts.add(v);
                untrue += c.grounded() ? 0 : 1;
                ev.add((c.grounded() ? "true " : "untrue ") + "\"" + c.text() + "\": " + v);
            }
            bad.addAll(claimProblems(claims, verdicts));
            // the delivery path: an untrue scripted answer is swapped for a safe line that passes
            String asked = "what lvl are u and where r u";
            String untrueLine = "im lvl " + (bot.getLevel() + 37) + " grinding in "
                    + claims.stream().filter(c -> c.text().startsWith("im grinding in ")).findFirst()
                    .map(c -> c.text().substring("im grinding in ".length(), c.text().length() - 3)).orElse("leafre")
                    + " lol";
            BotSM sm = CharacterStorage.getBotById(bot.getId());
            String said = BotConversation.qaGroundScripted(sm, me, asked, untrueLine);
            ev.add("scripted \"" + untrueLine + "\" -> delivered \"" + said + "\"");
            if (said == null) {
                bad.add("the delivery path built no snapshot for the bot");
            } else if (said.equals(untrueLine)) {
                bad.add("the delivery path said the untrue scripted line");
            } else if (!StateOfMind.check(said, s).ok()) {
                bad.add("the safe line it fell back to is not grounded either: " + StateOfMind.check(said, s));
            }
            Map<String, Object> after = StateOfMind.stats();
            long checked = stat(after, "checked") - stat(before, "checked");
            long rejected = stat(after, "rejected") - stat(before, "rejected");
            long fellBack = stat(after, "fellBack") - stat(before, "fellBack");
            ev.add("counters: checked +" + checked + ", rejected +" + rejected + ", fell back +" + fellBack
                    + " (since boot " + after + ")");
            if (checked < claims.size() + 1) {
                bad.add("checked +" + checked + ", want at least " + (claims.size() + 1));
            }
            if (rejected < untrue + 1) {
                bad.add("rejected +" + rejected + ", want at least " + (untrue + 1));
            }
            if (fellBack < 1) {
                bad.add("no fallback counted for the swapped scripted line");
            }
            return Probes160.verdict(bad, "the snapshot holds the bot's real level, map and the player; " + untrue
                    + " untrue claims rejected, the true ones kept, an untrue scripted line swapped for a safe one", ev);
        } finally {
            ev.add(TalkQa.teardown(spawned));
        }
    }

    // ── t-gr-2: the same through the real chat model ───────────────────────────────────────────────────

    /** How long the live check waits for a model reply (human timing can hold one up to ~55 s; a retry asks twice). */
    static final long GR2_REPLY_MS = 90_000;

    private static final Pattern NUMBER = Pattern.compile("\\b(\\d{1,3})\\b");

    /**
     * Whether a reply states {@code level} as a number of its own (null: it does). Other numbers are left to the
     * grounding check, which rejects a different level the bot claims for itself ("im 70").
     */
    static String levelProblem(String reply, int level) {
        if (reply == null) {
            return "no reply";
        }
        Matcher m = NUMBER.matcher(reply);
        while (m.find()) {
            if (Integer.parseInt(m.group(1)) == level) {
                return null;
            }
        }
        return "the reply did not state its level " + level + ": \"" + reply + "\"";
    }

    /** The first line the pretend player heard {@code bot} say in map chat since {@code since}, or null. */
    static String heardFrom(SyntheticPlayer p, String bot, long since) {
        for (AutoQaLine l : p.heard(since)) {
            if ("CHAT".equals(l.kind()) && l.message().startsWith(bot + ": ")) {
                return l.message().substring(bot.length() + 2);
            }
        }
        return null;
    }

    static String awaitReply(SyntheticPlayer p, String bot, long since, long capMs) throws InterruptedException {
        long until = System.currentTimeMillis() + capMs;
        while (System.currentTimeMillis() < until) {
            String r = heardFrom(p, bot, since);
            if (r != null) {
                return r;
            }
            Thread.sleep(500);
        }
        return null;
    }

    static List<String> groundingLive(SyntheticPlayer p) throws InterruptedException {
        if (!StateOfMind.checkOn()) {
            return List.of("SKIP the grounding checker is off (-Dsolomapling.convo.grounding=false)");
        }
        if (!BotBrains.get().isLive()) {
            return List.of("SKIP the chat model is not live on this server (BotBrains): the model's reply and its "
                    + "grounding retry need it; t-gr-1 covers the checker without it");
        }
        List<String> ev = new ArrayList<>();
        List<Character> spawned = new ArrayList<>();
        Character me = p.character();
        Character bot = null;
        try {
            spawned.addAll(TalkQa.spawn(me, 1, false, GR_LEVEL));
            if (spawned.isEmpty()) {
                return List.of("FAIL no fresh bot could be spawned next to the pretend player");
            }
            bot = spawned.get(0);
            BotSM sm = CharacterStorage.getBotById(bot.getId());
            List<String> bad = new ArrayList<>();
            // A: asked its level, it answers with its real level
            long t0 = System.currentTimeMillis();
            BotConversation.talk(sm, me, "what level are you? just the number pls");
            String a = awaitReply(p, bot.getName(), t0, GR2_REPLY_MS);
            ev.add("asked its level (lv " + bot.getLevel() + "): " + (a == null ? "no reply in "
                    + GR2_REPLY_MS / 1000 + " s" : "\"" + a + "\""));
            String lp = levelProblem(a, bot.getLevel());
            if (lp != null) {
                bad.add(lp);
            }
            StateOfMind.Snapshot s = BotConversation.qaSnapshot(bot, me);
            if (a != null && s != null && !StateOfMind.check(a, s).ok()) {
                bad.add("its answer is not grounded: " + StateOfMind.check(a, s));
            }
            // B: a forced untrue model draft is rejected, the model is asked again, and the untrue line is never said
            Thread.sleep(3000);
            String untrue = "lol im lvl " + (bot.getLevel() + 40) + " grinding in leafre, good to see u again";
            Map<String, Object> before = StateOfMind.stats();
            BotConversation.qaNextDraft(bot.getId(), me.getId(), untrue);
            long t1 = System.currentTimeMillis();
            BotConversation.talk(sm, me, "hows ur day going");
            String b = awaitReply(p, bot.getName(), t1, GR2_REPLY_MS + 30_000);
            boolean used = !BotConversation.qaDraftPending(bot.getId(), me.getId());
            Map<String, Object> after = StateOfMind.stats();
            long rejected = stat(after, "rejected") - stat(before, "rejected");
            long retried = stat(after, "retried") - stat(before, "retried");
            long resolved = stat(after, "retryPassed") - stat(before, "retryPassed") + stat(after, "fellBack")
                    - stat(before, "fellBack");
            ev.add("forced draft \"" + untrue + "\": reached the model path " + used + "; said "
                    + (b == null ? "nothing" : "\"" + b + "\"") + "; rejected +" + rejected + ", retried +" + retried
                    + ", retry passed or fell back +" + resolved);
            bad.addAll(retryProblems(used, b, untrue, rejected, retried, resolved));
            if (b != null && s != null && !StateOfMind.check(b, s).ok() && !StateOfMind.check(b, s).reason()
                    .equals(StateOfMind.Reason.FAMILIAR)) {
                bad.add("what it said instead is not grounded: " + StateOfMind.check(b, s));
            }
            return Probes160.verdict(bad, "asked its level it said " + bot.getLevel() + "; a forced untrue draft was "
                    + "rejected, retried with the model and never said", ev);
        } finally {
            if (bot != null) {
                BotConversation.qaNextDraft(bot.getId(), me.getId(), null);
                BotConversation.end(bot.getId(), me.getId());
            }
            ev.add(TalkQa.teardown(spawned));
        }
    }

    /** What went wrong with the forced draft (empty: rejected, retried, resolved, and something else was said). */
    static List<String> retryProblems(boolean used, String said, String untrue, long rejected, long retried,
                                      long resolved) {
        List<String> bad = new ArrayList<>();
        if (!used) {
            bad.add("the line never reached the chat model (answered by a script or the bank), so the forced draft "
                    + "was not judged");
            return bad;
        }
        if (said == null) {
            bad.add("no reply was said after the forced draft");
        } else if (said.contains("leafre") || said.equalsIgnoreCase(untrue)) {
            bad.add("the untrue draft was said: \"" + said + "\"");
        }
        if (rejected < 1) {
            bad.add("the checker counted no reject");
        }
        if (retried < 1) {
            bad.add("no retry with the model was counted");
        }
        if (resolved < 1) {
            bad.add("the retry neither passed nor fell back");
        }
        return bad;
    }

    // ── t-nv-2: no new one-way-in traps in the bots' world graph ────────────────────────────────────────

    /** The verdict on a trap search against the baseline (pure, for the tests). */
    static List<String> trapVerdict(NavTrapQa.Traps t, Map<Integer, String> baseline) {
        List<String> ev = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        if (baseline.isEmpty()) {
            bad.add("the trap baseline (soloMapling/nav-trap-maps.txt) is missing from the jar");
        }
        if (t.reachable() < 500) {
            bad.add("only " + t.reachable() + " maps reachable from " + t.hub() + ": the world graph did not load");
        }
        NavTrapQa.Diff d = NavTrapQa.diff(t, baseline.keySet());
        if (!d.unknown().isEmpty()) {
            List<Integer> first = new ArrayList<>(d.unknown()).subList(0, Math.min(20, d.unknown().size()));
            bad.add(d.unknown().size() + " new one-way trap(s), not in the baseline: " + first
                    + (d.unknown().size() > 20 ? " ..." : ""));
        }
        ev.add(t.reachable() + " maps reachable from " + t.hub() + ", " + t.canReturn() + " with a way back, "
                + t.traps().size() + " traps (" + baseline.size() + " known in the baseline)");
        if (!d.fixed().isEmpty()) {
            List<Integer> first = new ArrayList<>(d.fixed()).subList(0, Math.min(20, d.fixed().size()));
            ev.add(d.fixed().size() + " baseline trap(s) now have a way back (drop them from the baseline): " + first);
        }
        return Probes160.verdict(bad, "no new one-way-in trap: every trap reachable from Lith Harbor is a known one", ev);
    }

    static List<String> trapMaps() {
        boolean ready = NavTrapQa.graphReady();
        long t0 = System.currentTimeMillis();
        NavTrapQa.Traps t = NavTrapQa.worldTraps();
        List<String> out = new ArrayList<>(trapVerdict(t, NavTrapQa.baseline()));
        out.add("graph " + (ready ? "already built" : "built for the check") + ", search " + (System.currentTimeMillis()
                - t0) + " ms");
        return out;
    }

    // ── t-nv-3: the ticket-157 debug tools answer on this server ─────────────────────────────────────────

    /** The quiet field the debug-tools bot stands on, pinned to full simulation for the check (HHG1). */
    static final int NV3_FIELD = AutoQaCatalog.HHG1;

    /** What is wrong with the inspect API's view of a bot (empty: it is the bot). */
    static List<String> inspectProblems(Map<String, Object> view, int cid, String name, int level, int map) {
        List<String> bad = new ArrayList<>();
        if (view == null) {
            bad.add("/bots/" + cid + " has no document for an online bot");
            return bad;
        }
        if (!Boolean.TRUE.equals(view.get("ok")) || !Integer.valueOf(cid).equals(view.get("cid"))
                || !name.equals(view.get("name")) || !Integer.valueOf(level).equals(view.get("level"))
                || !Integer.valueOf(map).equals(view.get("map"))) {
            bad.add("/bots/" + cid + " says " + view.get("name") + " lv " + view.get("level") + " on " + view.get("map")
                    + ", the bot is " + name + " lv " + level + " on " + map);
        }
        return bad;
    }

    static List<String> debugTools(SyntheticPlayer p) throws Exception {
        List<String> off = new ArrayList<>();
        if (!KillCalibration.enabled()) {
            off.add("kill calibration (-Dslums.killcalib=true)");
        }
        if (!NavTrace.enabled()) {
            off.add("nav path trace (-Dslums.navTrace=true)");
        }
        int port = BotInspectApi.qaPort();
        if (port < 0) {
            off.add("bot inspect API (-Dslums.inspect=true with -Dslums.inspect.port and .secretFile)");
        }
        if (off.size() == 3) {
            return List.of("SKIP every ticket-157 debug tool is off: " + String.join(", ", off));
        }
        List<String> ev = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        List<Character> spawned = new ArrayList<>();
        List<Integer> pinned = Probes162.pin(ev, NV3_FIELD);
        try {
            spawned.addAll(TalkQa.spawn(p.character(), 1, true, 30));
            if (spawned.isEmpty()) {
                return List.of("FAIL no fresh bot could be spawned");
            }
            Character bot = spawned.get(0);
            Probes162.place(bot, NV3_FIELD, null);
            Thread.sleep(8_000); // a few seconds of full-physics ticks on the pinned field
            if (port >= 0) {
                int noSecret = get(port, "/bots/" + bot.getId(), null);
                int wrong = get(port, "/bots/" + bot.getId(), "not-the-secret-" + new Random().nextInt(1 << 20));
                ev.add("inspect on 127.0.0.1:" + port + ": no secret -> " + noSecret + ", a wrong one -> " + wrong);
                if (noSecret != 403 || wrong != 403) {
                    bad.add("the inspect API answered " + noSecret + "/" + wrong + " without the secret, want 403");
                }
                Map<String, Object> view = BotInspectApi.qaBot(bot.getId());
                bad.addAll(inspectProblems(view, bot.getId(), bot.getName(), bot.getLevel(), bot.getMapId()));
                if (BotInspectApi.qaBot(p.character().getId()) != null) {
                    bad.add("the inspect API serves a document for the pretend (real) player");
                }
                Map<String, Object> pos = BotInspectApi.qaPositions();
                boolean listed = pos != null && pos.get("bots") instanceof List<?> rows && rows.stream()
                        .anyMatch(r -> r instanceof List<?> l && !l.isEmpty() && Integer.valueOf(bot.getId()).equals(l.get(0)));
                ev.add("/positions: " + (pos == null ? "none" : pos.get("count") + " bots") + ", the fresh bot listed "
                        + listed);
                if (!listed) {
                    bad.add("/positions does not list the fresh bot");
                }
            }
            if (KillCalibration.enabled()) {
                Map<String, Object> doc = KillCalibration.snapshotJson(System.currentTimeMillis());
                List<String> lines = KillCalibration.summaryLines(2);
                ev.add("killcalib: " + (lines.isEmpty() ? "no lines" : lines.get(0)));
                if (!Integer.valueOf(1).equals(doc.get("version")) || !Boolean.TRUE.equals(doc.get("reportOnly"))
                        || !(doc.get("totals") instanceof Map<?, ?>) || !(doc.get("buckets") instanceof List<?>)) {
                    bad.add("calibration.json is not version 1, report-only, with totals and buckets: " + doc.keySet());
                }
                if (lines.isEmpty() || !lines.get(0).startsWith("Kill calibration (report only, on)")) {
                    bad.add("!killcalib's header is not \"Kill calibration (report only, on)...\"");
                }
            }
            if (NavTrace.enabled()) {
                boolean observed = GCMovement.isMapObserved(bot);
                List<String> ring = NavTrace.qaRing(bot);
                ev.add("nav trace: " + NavTrace.describe() + "; bot on " + bot.getMapId() + " (full simulation "
                        + observed + "), ring " + (ring == null ? "none" : ring.size() + " ticks, last: "
                        + ring.get(ring.size() - 1)));
                if (!observed) {
                    bad.add("the pinned field " + NV3_FIELD + " is not on full simulation: the trace cannot be judged");
                } else if (ring == null || ring.isEmpty()) {
                    bad.add("a bot on a full-simulation map has no path trace ring after 8 s");
                } else if (!ring.get(ring.size() - 1).contains(" m" + bot.getMapId() + " ")) {
                    bad.add("the ring's last tick is not on the bot's map: " + ring.get(ring.size() - 1));
                }
            }
            if (!bad.isEmpty()) {
                return Probes160.verdict(bad, "", ev);
            }
            if (!off.isEmpty()) {
                ev.add(0, "SKIP the rest answer, but off here: " + String.join(", ", off));
                return ev;
            }
            return Probes160.verdict(bad, "the inspect API refuses no secret and shows the bot as it is, kill "
                    + "calibration reports, and a watched bot keeps a path trace", ev);
        } finally {
            ev.add(TalkQa.teardown(spawned));
            Probes162.unpin(pinned);
        }
    }

    /** A GET to the local inspect endpoint with the given header value (null: none); the HTTP status. */
    private static int get(int port, String path, String header) throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).GET();
        if (header != null) {
            req.header(BotInspectApi.HEADER, header);
        }
        return http.send(req.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
