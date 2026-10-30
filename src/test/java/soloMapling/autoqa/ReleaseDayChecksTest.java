package soloMapling.autoqa;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.0 release-day triage of the full TEST run at d1410d0c7 (2026-10-27): three checks failed on staging and timing,
 * not on the feature. Each test below uses the lines that run logged.
 */
class ReleaseDayChecksTest {

    /** gu-4 at 17:11: the staged jab and the megaphone jab landed; the !qa do jab 2 s later found both bots cooling down. */
    private static final List<String> GU4_RUN = List.of(
            "BOT_GUILD JOIN StarBubble -> MoonLiteX (rank 5) [memory only]",
            "BOT_GUILD JOIN ImNeko -> SlumLords (rank 5) [memory only]",
            "BOT_CHAT StarBubble: SlumLords needs to chill",
            "BOT_GUILD JAB StarBubble [MoonLiteX] -> [SlumLords]: SlumLords needs to chill",
            "BOT_GUILD MEGAPHONE StarBubble ch1: shoutout to SlumLords for always being second",
            "BOT_GUILD MEGA JAB StarBubble [MoonLiteX] at SlumLords: sent",
            "STEP jab: no member of MoonLiteX and SlumLords share a map a player sees",
            "BOT_GUILD MEGAPHONE StarBubble ch1: MoonLiteX > SlumLords lol",
            "BOT_GUILD MEGA JAB StarBubble [MoonLiteX] at SlumLords: sent");

    @Test
    void guildJabPassesWhenTheSecondJabIsOnTheCooldown() throws Exception {
        List<String> failed = failures(AutoQaCatalog.find("t-gu-4"), GU4_RUN);
        assertTrue(failed.isEmpty(), () -> "t-gu-4 failed on the 17:11 lines: " + failed);
    }

    @Test
    void guildJabStillFailsWithoutAnyJab() throws Exception {
        List<String> noJab = GU4_RUN.stream().filter(l -> !l.startsWith("BOT_GUILD JAB ")).toList();
        assertFalse(failures(AutoQaCatalog.find("t-gu-4"), noJab).isEmpty(), "no map jab must fail");
        List<String> noMega = GU4_RUN.stream().filter(l -> !l.contains("MEGA JAB")).toList();
        assertFalse(failures(AutoQaCatalog.find("t-gu-4"), noMega).isEmpty(), "no megaphone jab must fail");
    }

    /** ev-2 at 18:17: 102 bots cleared the three waves in 92 s, so the natural-death wait must end well inside that. */
    @Test
    void invasionAsksForADeathWhileTheEventStillRuns() {
        int wait = waitSeconds(AutoQaCatalog.find("t-ev-2"), "WEVENT BOT DIED ");
        assertTrue(wait > 0 && 8 + wait <= 45, "t-ev-2 waits " + wait + " s before !qa do die; the invasion lasts ~90 s");
    }

    /** pt-1: in the passing runs the first PET LOOT came 16-19 s after the drop, at the edge of the old 20 s window. */
    @Test
    void petLootHasRoomPastTheFirstKill() {
        Check c = AutoQaCatalog.find("t-pt-1");
        int cap = -1;
        for (Check.Step s : c.steps()) {
            Matcher m = Pattern.compile("wait up to (\\d+) s for 1 x /PET").matcher(s.describe());
            if (m.find()) {
                cap = Integer.parseInt(m.group(1));
            }
        }
        assertTrue(cap >= 45, "t-pt-1 waits " + cap + " s for a PET LOOT");
    }

    private static int waitSeconds(Check c, String regex) {
        for (Check.Step s : c.steps()) {
            Matcher m = Pattern.compile("up to (\\d+) s for /" + Pattern.quote(regex) + "/").matcher(s.describe());
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        }
        return -1;
    }

    /** Expectations that FAIL on these lines (a SKIP, e.g. the LLM judge with no model here, is not a failure). */
    private static List<String> failures(Check check, List<String> lines) throws Exception {
        World w = new World();
        long t = 2_000_000;
        for (String line : lines) {
            int sp = line.indexOf(' ');
            w.lines.add(new AutoQaLine(t++, "qa", line.substring(0, sp), line.substring(sp + 1)));
        }
        CheckRun run = new CheckRun(check, w);
        run.actor(1);
        List<String> failed = new ArrayList<>();
        for (Check.Expect e : check.expects()) {
            Check.Verdict v = e.eval(run);
            if (v.status() == Check.Status.FAIL) {
                failed.add(e.describe() + ": " + v.evidence());
            }
        }
        return failed;
    }

    private static final class StubActor implements AutoQaWorld.Actor {
        @Override public int id() { return 1; }
        @Override public String name() { return "AutoQaOne"; }
        @Override public int slot() { return 1; }
        @Override public void say(String text) { }
        @Override public void whisper(String target, String text) { }
        @Override public void multiChat(int type, String text) { }
        @Override public void partyInvite(String name) { }
        @Override public boolean acceptPartyInvite() { return false; }
        @Override public void guildInvite(String name) { }
        @Override public boolean acceptGuildInvite() { return false; }
        @Override public void buddyAdd(String name) { }
        @Override public List<AutoQaLine> heard(long sinceMs) { return List.of(); }
        @Override public int mapId() { return 100000000; }
        @Override public boolean isOnline() { return true; }
    }

    private static final class World implements AutoQaWorld {
        final List<AutoQaLine> lines = new ArrayList<>();

        @Override public String unavailableReason() { return null; }
        @Override public Actor actor(int slot) { return slot == 1 ? new StubActor() : null; }
        @Override public void logoutAll() { }
        @Override public List<AutoQaLine> qaLines(int gmId, long sinceMs) { return lines; }
        @Override public String nameOf(int characterId) { return "AutoQaOne"; }
        @Override public long now() { return 2_000_000; }
        @Override public void sleep(long ms) { }
        @Override public List<String> probe(String what, Actor actor, String... args) { return List.of("FAIL no probe " + what); }
        @Override public LlmJudge judge() { return null; }
        @Override public Map<String, Object> lastHealth() { return Map.of(); }
    }
}
