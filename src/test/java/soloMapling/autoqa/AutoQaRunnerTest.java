package soloMapling.autoqa;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static soloMapling.autoqa.Check.Status.FAIL;
import static soloMapling.autoqa.Check.Status.PASS;
import static soloMapling.autoqa.Check.Status.SKIP;

class AutoQaRunnerTest {

    /** A scripted server: a fake clock, one or two actors, and a reaction to each line player 1 says. */
    static final class FakeWorld implements AutoQaWorld {
        long now = 1_000_000;
        final List<AutoQaLine> qa = new ArrayList<>();
        final List<FakeActor> actors = new ArrayList<>();
        final List<String> said = new ArrayList<>();
        String unavailable;
        int logouts;
        BiConsumer<FakeWorld, String> onSay = (w, line) -> {
        };
        LlmJudge judge = LlmJudge.of(null, 0);

        void qa(String kind, String msg) {
            qa.add(new AutoQaLine(now, "qa", kind, msg));
        }

        @Override
        public String unavailableReason() {
            return unavailable;
        }

        @Override
        public Actor actor(int slot) {
            while (actors.size() < slot) {
                actors.add(new FakeActor(this, actors.size() + 1));
            }
            return actors.get(slot - 1);
        }

        @Override
        public void logoutAll() {
            logouts++;
        }

        @Override
        public List<AutoQaLine> qaLines(int gmId, long sinceMs) {
            return qa.stream().filter(l -> l.timeMs() >= sinceMs).toList();
        }

        @Override
        public String nameOf(int characterId) {
            return "Bot" + (characterId % 1000);
        }

        @Override
        public long now() {
            return now;
        }

        @Override
        public void sleep(long ms) {
            now += ms;
        }

        @Override
        public List<String> probe(String what, Actor actor, String... args) {
            return switch (what) {
                case "ok" -> List.of("PASS fine", "VAR near=Bot77");
                case "nope" -> List.of("FAIL not fine");
                case "later" -> List.of("SKIP not here");
                default -> List.of("FAIL unknown");
            };
        }

        @Override
        public LlmJudge judge() {
            return judge;
        }

        @Override
        public Map<String, Object> lastHealth() {
            return null;
        }
    }

    static final class FakeActor implements AutoQaWorld.Actor {
        final FakeWorld w;
        final int slot;
        final List<AutoQaLine> heard = new ArrayList<>();

        FakeActor(FakeWorld w, int slot) {
            this.w = w;
            this.slot = slot;
        }

        void hear(String kind, String msg) {
            heard.add(new AutoQaLine(w.now, "heard:" + slot, kind, msg));
        }

        public int id() {
            return 100 + slot;
        }

        public String name() {
            return slot == 1 ? "AutoQaOne" : "AutoQaTwo";
        }

        public int slot() {
            return slot;
        }

        public void say(String text) {
            w.said.add(slot + ":" + text);
            hear("CHAT", name() + ": " + text);
            w.onSay.accept(w, text);
        }

        public void whisper(String target, String text) {
            w.said.add(slot + ":whisper " + target + " " + text);
        }

        public void multiChat(int type, String text) {
            w.said.add(slot + ":multi" + type + " " + text);
        }

        public void partyInvite(String name) {
            w.said.add(slot + ":party " + name);
        }

        public boolean acceptPartyInvite() {
            return false;
        }

        public void guildInvite(String name) {
            w.said.add(slot + ":ginvite " + name);
        }

        public boolean acceptGuildInvite() {
            return false;
        }

        public void buddyAdd(String name) {
            w.said.add(slot + ":buddy " + name);
        }

        public List<AutoQaLine> heard(long sinceMs) {
            return heard.stream().filter(l -> l.timeMs() >= sinceMs).toList();
        }

        public int mapId() {
            return 100000000;
        }

        public boolean isOnline() {
            return true;
        }
    }

    /** The suite's answers: "!qa run x" stages bot 1000000042; "!qa run bad" is refused; "!qa run guild" needs a guild. */
    static void suite(FakeWorld w, String line) {
        FakeActor gm = w.actors.get(0);
        if (line.startsWith("!qa run ")) {
            String id = line.substring(8).split(" ")[0];
            switch (id) {
                case "bad" -> gm.hear("YELLOW", "Staging bad failed (boom)");
                case "guild" -> gm.hear("YELLOW", "You must be in a guild first.");
                default -> {
                    gm.hear("YELLOW", "QA " + id + " running: test");
                    w.qa("STAGE", "staged bot 1000000042 next to the GM");
                }
            }
        } else if (line.equals("!qa do roast")) {
            w.qa("RIVALRY", "ROAST facts for AutoQaOne: level 70, 0 deaths today");
            w.qa("RIVALRY", "ROAST mock Bot42 -> AutoQaOne: lv 70 still here lol");
        } else if (line.startsWith("hi Bot42")) {
            w.qa("AFFINITY", "ACT greet Bot42 (friend) -> AutoQaOne: hey");
        }
    }

    static FakeWorld world() {
        FakeWorld w = new FakeWorld();
        w.onSay = AutoQaRunnerTest::suite;
        return w;
    }

    @Test
    void aCheckWhoseExpectationsHoldPassesAndWalksTheStates() {
        FakeWorld w = world();
        Check c = Check.auto("t-af-1", "friend").step(Steps.run("af-1"), Steps.say("hi {bot}"), Steps.waitSeconds(5))
                .expect(Expects.has("AFFINITY ACT greet {bot} "), Expects.has("CHAT AutoQaOne: hi Bot42")).build();
        AutoQaRunner r = new AutoQaRunner(w);
        List<AutoQaRunner.CheckResult> out = r.run(List.of(c), false, null);
        assertEquals(PASS, out.get(0).status(), out.get(0).toString());
        assertEquals(List.of("PREPARING", "STEPPING t-af-1", "ASSERTING t-af-1", "CLEANING t-af-1", "FINISHED", "IDLE"),
                r.transitions());
        assertEquals(AutoQaRunner.State.IDLE, r.state());
        assertTrue(w.said.contains("1:!qa stop"), "cleanup stops the scenario");
        assertEquals(1, w.logouts);
    }

    @Test
    void aFailedExpectationFailsTheCheckWithEvidence() {
        FakeWorld w = world();
        Check c = Check.auto("t-rv-1", "roast").step(Steps.run("rv-1"), Steps.qaDo("roast"))
                .expect(Expects.atLeast(3, "RIVALRY ROAST facts for {me}"), Expects.has("RIVALRY ROAST .*-> {me}: ")).build();
        AutoQaRunner.CheckResult res = new AutoQaRunner(w).run(List.of(c), false, null).get(0);
        assertEquals(FAIL, res.status());
        assertTrue(res.reason().contains("1 expectation(s) failed"), res.reason());
        assertTrue(res.evidence().stream().anyMatch(e -> e.contains("saw 1")), res.evidence().toString());
        assertTrue(res.evidence().stream().anyMatch(e -> e.contains("lv 70 still here lol")), "passing evidence kept");
    }

    @Test
    void aFailKeepsTheSuitesAnswersToTheGm() {
        FakeWorld w = world();
        w.onSay = (fw, line) -> {
            suite(fw, line);
            if (line.equals("!qa do taunt")) {
                fw.actors.get(0).hear("YELLOW", "no free grinder of SlumLords fits map 104040000");
            }
        };
        Check c = Check.auto("t-gw-1", "war").step(Steps.run("gw-1"), Steps.qaDo("taunt"))
                .expect(Expects.has("GUILD_WAR TURF .* goes to contest map")).build();
        AutoQaRunner.CheckResult res = new AutoQaRunner(w).run(List.of(c), false, null).get(0);
        assertEquals(FAIL, res.status());
        assertTrue(res.evidence().stream().anyMatch(e -> e.contains("no free grinder of SlumLords")), res.evidence().toString());
        Check ok = Check.auto("t-af-1", "friend").step(Steps.run("af-1")).expect(Expects.has("STAGE staged")).build();
        AutoQaRunner.CheckResult pass = new AutoQaRunner(w).run(List.of(ok), false, null).get(0);
        assertEquals(PASS, pass.status(), pass.toString());
        assertFalse(pass.evidence().stream().anyMatch(e -> e.startsWith("gm heard")), "only a FAIL carries them");
    }

    @Test
    void aPreconditionRefusalSkipsAndAnyOtherRefusalFails() {
        FakeWorld w = world();
        Check needsGuild = Check.auto("t-gu-2", "g").step(Steps.run("guild")).expect(Expects.has("NEVER_SEEN")).build();
        Check broken = Check.auto("t-gu-3", "b").step(Steps.run("bad")).expect(Expects.has("NEVER_SEEN")).build();
        List<AutoQaRunner.CheckResult> out = new AutoQaRunner(w).run(List.of(needsGuild, broken), false, null);
        assertEquals(SKIP, out.get(0).status());
        assertTrue(out.get(0).reason().contains("must be in a guild"));
        assertEquals(FAIL, out.get(1).status());
        assertTrue(out.get(1).reason().contains("Staging bad failed"));
    }

    @Test
    void manualAndNotNightlyChecksAreSkippedWithTheirReason() {
        FakeWorld w = world();
        Check manual = Check.manual("t-cl-1", "ropes", "client art: eyes only");
        Check lasting = Check.auto("t-gw-3", "poach").notNightly().step(Steps.run("gw-3")).expect(Expects.has("NEVER_SEEN")).build();
        List<AutoQaRunner.CheckResult> nightly = new AutoQaRunner(w).run(List.of(manual, lasting), true, null);
        assertEquals(SKIP, nightly.get(0).status());
        assertEquals("client art: eyes only", nightly.get(0).reason());
        assertEquals(SKIP, nightly.get(1).status());
        assertTrue(nightly.get(1).reason().contains("not in the nightly run"));
        assertTrue(w.said.isEmpty(), "nothing was typed");
        // asked by id, the lasting one runs
        assertEquals(FAIL, new AutoQaRunner(w).run(List.of(lasting), false, null).get(0).status());
    }

    @Test
    void fixturesSetVariablesOrEndTheCheck() {
        FakeWorld w = world();
        Check ok = Check.auto("t-bb-1", "b").step(Steps.fixture("ok"), Steps.say("hi {near}"))
                .expect(Expects.has("CHAT AutoQaOne: hi Bot77")).build();
        Check bad = Check.auto("t-bb-2", "b").step(Steps.fixture("nope")).expect(Expects.has("NEVER_SEEN")).build();
        Check later = Check.auto("t-ch-1", "c").step(Steps.fixture("later")).expect(Expects.has("NEVER_SEEN")).build();
        List<AutoQaRunner.CheckResult> out = new AutoQaRunner(w).run(List.of(ok, bad, later), false, null);
        assertEquals(PASS, out.get(0).status());
        assertEquals(FAIL, out.get(1).status());
        assertEquals(SKIP, out.get(2).status());
    }

    @Test
    void anUnknownPlaceholderFailsTheStepInsteadOfSayingIt() {
        FakeWorld w = world();
        Check c = Check.auto("t-af-2", "x").step(Steps.say("hi {bot}")).expect(Expects.has("NEVER_SEEN")).build();
        AutoQaRunner.CheckResult res = new AutoQaRunner(w).run(List.of(c), false, null).get(0);
        assertEquals(FAIL, res.status());
        assertFalse(w.said.stream().anyMatch(s -> s.contains("{bot}")));
    }

    @Test
    void marksSplitTheLinesBeforeAndAfter() {
        FakeWorld w = world();
        Check c = Check.auto("t-af-3", "drama").step(Steps.run("af-3"), Steps.waitSeconds(10), Steps.mark("on"),
                        Steps.qaDo("roast"))
                .expect(Expects.lacksBefore("on", "RIVALRY ROAST"), Expects.hasAfter("on", "RIVALRY ROAST")).build();
        assertEquals(PASS, new AutoQaRunner(w).run(List.of(c), false, null).get(0).status());
    }

    @Test
    void waitForReturnsAsSoonAsTheLineShowsUp() {
        FakeWorld w = world();
        w.onSay = (fw, line) -> {
            suite(fw, line);
            if (line.equals("ping")) {
                fw.qa("AFFINITY", "pong");
            }
        };
        Check c = Check.auto("t-af-1", "w").step(Steps.run("af-1"), Steps.say("ping"), Steps.waitFor("AFFINITY pong", 600))
                .expect(Expects.has("AFFINITY pong")).build();
        long t0 = w.now;
        assertEquals(PASS, new AutoQaRunner(w).run(List.of(c), false, null).get(0).status());
        assertTrue(w.now - t0 < 60_000, "did not wait out the 600 s");
    }

    @Test
    void anUnavailableServerSkipsEveryCheck() {
        FakeWorld w = world();
        w.unavailable = "the !qa suite is off";
        List<AutoQaRunner.CheckResult> out = new AutoQaRunner(w).run(AutoQaCatalog.all(), false, null);
        assertTrue(out.stream().allMatch(r -> r.status() == SKIP));
        assertTrue(w.said.isEmpty());
    }

    @Test
    void abortSkipsTheRestAndStillCleansUp() {
        FakeWorld w = world();
        AutoQaRunner r = new AutoQaRunner(w);
        Check first = Check.auto("t-af-1", "a").step(Steps.run("af-1"), Steps.of("abort", run -> {
            r.abort();
            return Check.StepResult.OK;
        }), Steps.waitSeconds(1)).expect(Expects.has("NEVER_SEEN")).build();
        Check second = Check.auto("t-af-2", "b").step(Steps.run("af-2")).expect(Expects.has("NEVER_SEEN")).build();
        List<AutoQaRunner.CheckResult> out = r.run(List.of(first, second), false, null);
        assertEquals(SKIP, out.get(0).status());
        assertEquals(SKIP, out.get(1).status());
        assertTrue(w.said.contains("1:!qa stop"));
        assertTrue(r.transitions().contains("ABORTED"));
    }

    @Test
    void theJudgeGradesBotLinesAndAMissingJudgeIsNotAFailure() {
        FakeWorld w = world();
        Check c = Check.auto("t-rv-1", "roast").step(Steps.run("rv-1"), Steps.qaDo("roast"))
                .expect(Expects.judged("RIVALRY ROAST .*-> {me}: (.+)", "a roast", 2)).build();
        assertEquals(SKIP, new AutoQaRunner(w).run(List.of(c), false, null).get(0).status());

        List<String> asked = new ArrayList<>();
        w.judge = LlmJudge.of((sys, user) -> {
            asked.add(user);
            return "{\"on_topic\":true,\"coherent\":true,\"in_character\":true,\"pg13\":true,\"reveals_ai\":false,\"reason\":\"fine\"}";
        }, 5);
        AutoQaRunner.CheckResult ok = new AutoQaRunner(w).run(List.of(c), false, null).get(0);
        assertEquals(PASS, ok.status(), ok.toString());
        assertTrue(asked.get(0).contains("lv 70 still here lol"));

        w.judge = LlmJudge.of((sys, user) -> "{\"on_topic\":true,\"coherent\":true,\"in_character\":false,\"pg13\":false,"
                + "\"reveals_ai\":false,\"reason\":\"slur\"}", 5);
        AutoQaRunner.CheckResult bad = new AutoQaRunner(w).run(List.of(c), false, null).get(0);
        assertEquals(FAIL, bad.status());
        assertTrue(bad.evidence().stream().anyMatch(e -> e.contains("not pg13")), bad.evidence().toString());
    }

    @Test
    void theBudgetStopsAStuckCheck() {
        FakeWorld w = world();
        Check c = Check.auto("t-we-1", "slow").minutes(1).step(Steps.waitSeconds(120), Steps.say("late"))
                .expect(Expects.has("NEVER_SEEN")).build();
        AutoQaRunner.CheckResult res = new AutoQaRunner(w).run(List.of(c), false, null).get(0);
        assertEquals(FAIL, res.status());
        assertTrue(res.reason().contains("budget"));
        assertFalse(w.said.contains("1:late"));
    }
}
