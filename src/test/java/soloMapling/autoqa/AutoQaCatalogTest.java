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
            "t-cv-1", "t-cv-2", "t-cv-3", "t-cv-4", "t-cv-5", "t-cv-6", "t-cv-7", "t-cv-8", "t-cv-9", "t-cv-10", "t-cq-1", "t-ga-1", "t-ga-2", "t-ga-3", "t-ga-4",
            "t-wc-1", "t-bs-1", "t-td-1", "t-td-2", "t-td-3", "t-gf-1", "t-pr-1", "t-sk-1", "t-sk-2", "t-rg-1", "t-rg-2",
            "t-sc-1", "t-sc-2", "t-cb-1", "t-cb-2", "t-cb-3", "t-cb-4", "t-sp-1", "t-sl-1", "t-ps-2", "t-pt-1", "t-bk-1", "t-at-1", "t-bk-3", "t-id-1",
            "t-mv-1", "t-jq-1", "t-cl-1", "t-cl-2", "t-cl-3", "t-cc-1", "t-mr-2", "t-vg-1", "t-ch-1", "t-la-1", "t-vb-1", "t-vb-2", "t-vb-3", "t-vb-4", "t-vb-5", "t-vb-6", "t-vb-7", "t-fh-1", "t-fh-2", "t-mb-1", "t-bl-1", "t-bl-2", "t-bl-3", "t-bl-4", "t-lk-1", "t-sg-1", "t-sg-2", "t-bb-3", "t-bl-11", "t-bc-1", "t-me-1", "t-me-2", "t-gs-1", "t-in-1", "t-in-2", "t-in-3", "t-lr-1", "t-rv-5", "t-dr-6", "t-cr-1", "t-ev-1", "t-ev-2", "t-ev-3", "t-mk-1", "t-sf-1", "t-bd-1", "t-bw-1", "t-st-1", "t-ex-1", "t-nx-1", "t-nx-2",
            "t-oa-1", "t-tw-1", "t-hp-1", "t-au-1", "t-ds-1", "t-np-4", "t-ck-1", "t-sc-3", "t-ss-1", "t-xc-1", "t-fy-1");

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
        assertEquals(s.checks().size(), s.count("PASS") + s.count("FAIL") + s.count("SKIP") + s.count("NOT_RUN"));
        assertEquals(null, AutoQaReport.snapshot(null));
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
