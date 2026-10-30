package soloMapling.autoqa;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoQaCatalogTest {

    /** The 74 check ids of slumsms-1.5.0-notes.html, in page order, and rc6's t-cq-1 (daily chat quality) after cv. */
    static final List<String> PAGE = List.of("t-af-1", "t-af-2", "t-af-3", "t-rv-1", "t-rv-2", "t-rv-3", "t-rv-4", "t-bb-1",
            "t-bb-2", "t-bg-1", "t-gu-1", "t-gu-3", "t-gu-4", "t-gu-5", "t-gu-8", "t-gu-2", "t-gu-6", "t-gw-1", "t-gw-2",
            "t-gw-3", "t-gw-4", "t-pb-1", "t-cu-1", "t-cu-2", "t-we-1", "t-mr-1", "t-rm-1", "t-rm-2", "t-rm-3", "t-rm-4",
            "t-cv-1", "t-cv-2", "t-cv-3", "t-cv-4", "t-cv-5", "t-cv-6", "t-cv-7", "t-cv-8", "t-cv-9", "t-cv-10", "t-cv-11", "t-cv-12", "t-cq-1", "t-ga-1", "t-ga-2", "t-ga-3", "t-ga-4",
            "t-wc-1", "t-bs-1", "t-bs-2", "t-td-1", "t-td-2", "t-td-3", "t-gf-1", "t-gf-2", "t-pr-1", "t-sk-1", "t-sk-2", "t-rg-1", "t-rg-2",
            "t-sc-1", "t-sc-2", "t-cb-1", "t-cb-2", "t-cb-3", "t-cb-4", "t-sp-1", "t-sl-1", "t-ps-2", "t-pt-1", "t-bk-1", "t-at-1", "t-bk-3", "t-id-1",
            "t-mv-1", "t-jq-1", "t-cl-1", "t-cl-2", "t-cl-3", "t-cc-1", "t-mr-2", "t-vg-1", "t-ch-1", "t-la-1", "t-vb-1", "t-vb-2", "t-vb-3", "t-vb-4", "t-vb-5", "t-vb-6", "t-vb-7", "t-vb-8", "t-fh-1", "t-fh-2", "t-mb-1", "t-bl-1", "t-bl-2", "t-bl-3", "t-bl-4", "t-lk-1", "t-sg-1", "t-sg-2", "t-bb-3", "t-bl-11", "t-bc-1", "t-me-1", "t-me-2", "t-gs-1", "t-in-1", "t-in-2", "t-in-3", "t-lr-1", "t-rv-5", "t-dr-6", "t-cr-1", "t-ev-1", "t-ev-2", "t-ev-3", "t-mk-1", "t-sf-1", "t-bd-1", "t-bw-1", "t-st-1", "t-ex-1", "t-nx-1", "t-nx-2",
            "t-oa-1", "t-tw-1", "t-hp-1", "t-au-1", "t-ds-1", "t-np-4", "t-ck-1", "t-sc-3", "t-ss-1", "t-xc-1", "t-xc-2", "t-xc-3", "t-xc-4", "t-fy-1", "t-fy-2", "t-fy-3", "t-fy-4", "t-ti-1", "t-rb-1", "t-rb-2", "t-rb-3", "t-ac-1", "t-ac-2", "t-pg-1", "t-nc-1", "t-ld-1", "t-iv-1", "t-iv-2", "t-sn-1", "t-bt-1", "t-bt-2", "t-bt-3", "t-br-1", "t-nv-1", "t-mx-1", "t-mx-2",
            "t-bq-1", "t-tm-1", "t-gb-1", "t-gb-2", "t-gb-3", "t-gb-4", "t-gb-5", "t-gb-6", "t-su-1", "t-su-2", "t-su-3", "t-jb-1", "t-jb-2", "t-nn-1", "t-nn-2",
            "t-mx-3", "t-mx-4", "t-mx-5", "t-mx-6", "t-gr-1", "t-gr-2", "t-nv-2", "t-nv-3");

    @Test
    void everyPageCheckIsThereOnceInPageOrder() {
        assertEquals(PAGE, AutoQaCatalog.all().stream().map(Check::id).toList());
    }

    @Test
    void runnableChecksHaveExpectationsAndManualOnesAReason() {
        for (Check c : AutoQaCatalog.all()) {
            if (c.kind() == Check.Kind.MANUAL) {
                assertNotNull(c.why(), c.id());
                assertTrue(c.steps().isEmpty(), c.id());
            } else {
                assertFalse(c.expects().isEmpty(), c.id() + " has nothing to assert");
                assertTrue(c.minutes() > 0, c.id());
                if (c.kind() == Check.Kind.HALF) {
                    assertNotNull(c.why(), c.id() + " must say what still needs eyes");
                }
            }
        }
    }

    @Test
    void eyesOnlyChecksAreManual() {
        for (String id : List.of("t-cl-1", "t-cl-2", "t-cl-3", "t-cc-1", "t-mr-2", "t-jq-1", "t-la-1")) {
            assertEquals(Check.Kind.MANUAL, AutoQaCatalog.find(id).kind(), id);
        }
    }

    @Test
    void everyScenarioACheckRunsExistsInTheQaSuite() throws Exception {
        Pattern run = Pattern.compile("^!qa run ([a-z]{2}-\\d+)");
        Set<String> registered = new HashSet<>();
        for (soloMapling.qa.QaScenario s : soloMapling.qa.QaScenarios.all()) {
            registered.add(s.id());
            registered.addAll(s.aliases());
        }
        for (Check c : AutoQaCatalog.all()) {
            for (Check.Step s : c.steps()) {
                Matcher m = run.matcher(s.describe());
                if (m.find()) {
                    assertTrue(registered.contains(m.group(1)), c.id() + " runs unknown scenario " + m.group(1));
                }
            }
        }
    }

    @Test
    void everyPatternCompiles() {
        // the catalogue is built once; building it compiles nothing, so compile what the steps describe
        for (Check c : AutoQaCatalog.all()) {
            for (Check.Expect e : c.expects()) {
                Matcher m = Pattern.compile("/(.*)/").matcher(e.describe());
                if (m.find()) {
                    Pattern.compile(m.group(1).replace("{me}", "AutoQaOne").replaceAll("\\{\\w+}", "X"));
                }
            }
        }
    }

    @Test
    void thePartyChatCheckMatchesTheLineTestLogged() {
        // 1.6.0 TEST run: "\\{bot}" filled to "\GolemMega", read as the \G anchor, and the player's line is lowercased
        Check cv10 = AutoQaService.select(AutoQaCatalog.all(), "t-cv-10").get(0);
        String logged = "21:58:53.874 CONVO PARTY CHAT AutoQaOne: \"golemmega ready?\" -> GolemMega answers [1-name]";
        boolean matched = false;
        for (Check.Expect e : cv10.expects()) {
            Matcher m = Pattern.compile("/(.*)/").matcher(e.describe());
            if (m.find() && m.group(1).startsWith("CONVO PARTY CHAT")) {
                String filled = m.group(1).replace("{me}", "AutoQaOne").replace("{bot}", "GolemMega");
                matched = Pattern.compile(filled).matcher(logged).find();
            }
        }
        assertTrue(matched);
    }

    @Test
    void selectorsPickByPageIdShortIdOrGroup() {
        List<Check> all = AutoQaCatalog.all();
        assertEquals(1, AutoQaService.select(all, "t-af-1").size());
        assertEquals(1, AutoQaService.select(all, "af-1").size());
        assertEquals(3, AutoQaService.select(all, "af").size());
        assertEquals(all.size(), AutoQaService.select(all, "ALL").size());
        assertTrue(AutoQaService.select(all, "zz").isEmpty());
    }

    @Test
    void aCommaListAndTheSmokeSetPickEachCheck() {
        List<Check> all = AutoQaCatalog.all();
        List<String> smoke = AutoQaService.select(all, "smoke").stream().map(Check::id).toList();
        assertEquals(List.of("t-bg-1", "t-vg-1", "t-ch-1"), smoke);
        assertEquals(smoke, AutoQaService.select(all, "bg-1, vg-1,ch-1").stream().map(Check::id).toList());
        assertEquals(4, AutoQaService.select(all, "af,bg-1").size());
    }

    @Test
    void aReportSnapshotCountsEveryStatus() {
        java.util.Map<String, Object> r = AutoQaReport.empty("b", "2026-10-23", AutoQaCatalog.all());
        AutoQaReport.Snapshot s = AutoQaReport.snapshot(r);
        assertEquals(AutoQaCatalog.all().size(), s.checks().size());
        assertEquals(s.checks().size(), s.count("PASS") + s.count("FAIL") + s.count("SKIP") + s.count("NOT_RUN")
                + s.count("OLD"));
        assertEquals(null, AutoQaReport.snapshot(null));
    }

    /** TEST 2026-10-30: t-cu-1 staged its pair wherever the last check left the GM (a mob ledge) and never sat. */
    @Test
    void theCoupleChecksStageThePairInTown() {
        for (String id : List.of("t-cu-1", "t-cu-2")) {
            Check c = AutoQaCatalog.find(id);
            assertEquals("p1 says '!warp " + AutoQaCatalog.COUPLE_TOWN + "'", c.steps().get(0).describe(), id);
            assertTrue(c.steps().get(2).describe().startsWith("!qa run cp-1"), id);
        }
        assertEquals(100000000, AutoQaCatalog.COUPLE_TOWN, "Henesys: no mobs");
    }

    /**
     * TEST 2026-10-30: with KS manners at the swing, the full invasion's first wave never cleared around the visible
     * auto-QA GM. Event mobs are everyone's now; the invasion checks keep the GM visible to prove it.
     */
    @Test
    void theInvasionChecksKeepTheirGmVisible() {
        for (String id : List.of("t-ev-2", "t-ev-3")) {
            for (Check.Step st : AutoQaCatalog.find(id).steps()) {
                assertFalse(st.describe().contains("!hide"), id + ": " + st.describe());
            }
        }
    }

    /**
     * b222: a wait that runs out fails its check. The few windows that may legitimately stay empty are explicit
     * ({@code Steps.optionalWait}) and pinned here, and the checks whose waits timed out in every TEST run of
     * 10-23..30 while the check still passed were converted (they would otherwise fail on the new rule for the wrong
     * reason, or kept passing without testing what they waited for).
     */
    @Test
    void onlyPinnedChecksHaveOptionalWindowsAndTheTimedOutWaitsWereConverted() {
        Set<String> optional = new HashSet<>();
        for (Check c : AutoQaCatalog.all()) {
            for (Check.Step st : c.steps()) {
                if (st.describe().startsWith("optional: ")) {
                    optional.add(c.id());
                }
            }
        }
        assertEquals(Set.of("t-ev-2"), optional);
        // t-gu-8: a held kick is a branch (vote), not a wait that may run out
        assertTrue(steps("t-gu-8").contains("up to 15 s for the kick, else !qa do vote"), steps("t-gu-8"));
        assertFalse(steps("t-gu-8").contains("for /BOT_GUILD KICK /"), steps("t-gu-8"));
        // t-td-2: the chat ask is answered with the open quote and the 1k offer with a counter, each said in chat
        assertTrue(steps("t-td-2").contains("for /QUOTE asked again {bot} /"), steps("t-td-2"));
        assertTrue(steps("t-td-2").contains("for /QUOTE haggle 1000 -> /"), steps("t-td-2"));
        assertTrue(steps("t-td-2").contains("for /CHAT {bot}: .*\\d/"), steps("t-td-2"));
        assertFalse(steps("t-td-2").contains("quote sword"), "a staged re-ask would stand in for the chat one");
        // t-cb-4: no 120 s soak that saw 0 lines in every run
        assertFalse(steps("t-cb-4").contains("x /MANNERS /"), steps("t-cb-4"));
        // t-af-3: the wait ends on what the expectation accepts
        assertTrue(steps("t-af-3").contains("for /" + AutoQaCatalog.AF3_DRAMA_ON + "/"), steps("t-af-3"));
        // t-bl-3 and t-ev-2: the trip, death and respawn may come before the wait's own window
        assertTrue(steps("t-bl-3").contains("(since mark need)"), steps("t-bl-3"));
        assertTrue(steps("t-ev-2").contains("for /WEVENT BOT DIED / (since the start)"), steps("t-ev-2"));
        // the chat checks give a late (human-timed) reply the time it takes
        for (String id : List.of("t-cv-1", "t-cv-2", "t-cv-3", "t-cv-7")) {
            assertFalse(steps(id).matches("(?s).*wait up to 1[25] s for 1 x .*"), id + ": " + steps(id));
        }
    }

    private static String steps(String id) {
        StringBuilder b = new StringBuilder();
        for (Check.Step st : AutoQaCatalog.find(id).steps()) {
            b.append(st.describe()).append('\n');
        }
        return b.toString();
    }

    @Test
    void theNightlyRunIsAtTheNextFullHourUtc() {
        long t = java.time.Instant.parse("2026-10-23T09:30:00Z").toEpochMilli();
        assertEquals(30 * 60_000L, AutoQaService.msUntilNext(10, t));
        assertEquals(23 * 3600_000L + 30 * 60_000L, AutoQaService.msUntilNext(9, t));
    }

    @Test
    void autoQaIsOffUnlessSwitchedOn() {
        assertFalse(AutoQa.enabledFor(k -> null, false));
        assertTrue(AutoQa.enabledFor(k -> AutoQa.PROPERTY.equals(k) ? "true" : null, false));
        assertTrue(AutoQa.enabledFor(k -> null, true));
    }
}
