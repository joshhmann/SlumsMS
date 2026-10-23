package soloMapling.menus;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.autoqa.AutoQaCatalog;
import soloMapling.autoqa.AutoQaReport;
import soloMapling.autoqa.Check;
import soloMapling.menus.MenuWindow.Option;
import soloMapling.menus.MenuWindow.Page;
import soloMapling.qa.QaContext;
import soloMapling.qa.QaScenario;
import soloMapling.qa.QaScenarios;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QaMenuTest {

    private final List<String> ran = new ArrayList<>();
    private String running;
    private boolean autoQa = true;
    private AutoQaReport.Snapshot report;

    @BeforeEach
    void fake() {
        QaMenu.useForTest(new QaMenu.Source() {
            public List<QaScenario> scenarios() {
                return QaScenarios.all();
            }

            public String runningId(int gmId) {
                return running;
            }

            public boolean autoQaEnabled() {
                return autoQa;
            }

            public AutoQaReport.Snapshot autoQaReport() {
                return report;
            }

            public List<Check> autoQaChecks() {
                return AutoQaCatalog.all();
            }
        }, (p, line) -> {
            ran.add(line);
            return null;
        });
    }

    @AfterEach
    void real() {
        QaMenu.useForTest(null, null);
        MenuWindow.useForTest(null, null);
    }

    private static Character gm() {
        BotClient c = new BotClient(0, 0);
        Character chr = Character.getDefault(c);
        chr.setID(4001);
        chr.setName("Gm");
        c.setPlayer(chr);
        return chr;
    }

    // ── grouping ──

    @Test
    void everyRegisteredScenarioIsInAnEvening() {
        List<String> missing = new ArrayList<>();
        for (QaScenario s : QaScenarios.all()) {
            if (QaMenuCatalog.find(s.id()) == null) {
                missing.add(s.id());
            }
        }
        assertTrue(missing.isEmpty(), "place these in QaMenuCatalog (an evening and section): " + missing);
    }

    @Test
    void theMenuListsNoScenarioThatIsNotRegistered() {
        Set<String> ids = new HashSet<>();
        QaScenarios.all().forEach(s -> ids.add(s.id()));
        for (QaMenuCatalog.Entry e : QaMenuCatalog.all()) {
            assertTrue(ids.contains(e.id()), e.id() + " is in the menu but not registered (an alias? a removed one?)");
            assertTrue(e.evening() >= 1 && e.evening() <= QaMenuCatalog.EVENINGS.size(), e.id());
        }
    }

    @Test
    void everyScenarioWithStepsOffersThem() {
        for (QaScenario s : QaScenarios.all()) {
            if (declaresAction(s.getClass())) {
                QaMenuCatalog.Entry e = QaMenuCatalog.find(s.id());
                assertNotNull(e, s.id());
                assertFalse(e.steps().isEmpty() && e.typed().isEmpty(), s.id() + " has !qa do steps: list them");
            }
        }
    }

    private static boolean declaresAction(Class<?> c) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                k.getDeclaredMethod("action", QaContext.class, String.class, String[].class);
                return true;
            } catch (NoSuchMethodException e) {
                // keep looking up
            }
        }
        return false;
    }

    @Test
    void theEveningPagesShowEveryScenarioOnce() {
        List<String> seen = new ArrayList<>();
        for (QaMenuCatalog.Evening ev : QaMenuCatalog.EVENINGS) {
            for (int page = 0; page < 20; page++) {
                Page p = QaMenu.eveningPage(ev.number(), page);
                for (String label : p.labels()) {
                    if (label.startsWith("#b") && label.contains("-")) {
                        String id = label.substring(2, label.indexOf("#k"));
                        if (!seen.contains(id)) {
                            seen.add(id);
                        }
                    }
                }
                if (p.labels().stream().noneMatch(l -> l.contains("Next page"))) {
                    break;
                }
            }
        }
        List<String> want = new ArrayList<>();
        QaScenarios.all().forEach(s -> want.add(s.id()));
        assertEquals(new HashSet<>(want), new HashSet<>(seen));
        assertEquals(want.size(), seen.size());
    }

    @Test
    void theEveningsFollowTheQaPage() {
        assertEquals(1, QaMenuCatalog.find("af-1").evening());
        assertEquals(1, QaMenuCatalog.find("we-1").evening());
        assertEquals(2, QaMenuCatalog.find("tr-1").evening());
        assertEquals(2, QaMenuCatalog.find("ga-1").evening());
        assertEquals(3, QaMenuCatalog.find("cb-1").evening());
        assertEquals(3, QaMenuCatalog.find("bk-1").evening());
    }

    @Test
    void aLongEveningIsPagedWithItsSectionHeadings() {
        Page first = QaMenu.eveningPage(1, 0);
        assertTrue(first.render().contains("#dBots remember you#k"), first.render());
        assertTrue(first.labels().stream().anyMatch(l -> l.contains("Next page")));
        assertTrue(first.text().contains("(page 1/"), first.text());
        long clickable = first.labels().stream().filter(l -> l.startsWith("#b") && l.contains("-")).count();
        assertEquals(MenuWindow.PER_PAGE, clickable);
        assertTrue(first.labels().contains("< Back"));
        for (Option o : first.options()) {
            assertTrue(o.label().length() <= 60, "fits the window: " + o.label());
        }
    }

    // ── running ──

    @Test
    void aScenarioOffersItsDefaultRunAndItsVariants() {
        Page cp = QaMenu.scenarioPage("cp-1", 1, 0);
        assertTrue(cp.labels().containsAll(List.of("Run cp-1", "Run cp-1 married", "Run cp-1 friends")), cp.labels().toString());
        cp.find("Run cp-1 married").pick().apply(gm());
        assertEquals(List.of("!qa run cp-1 married"), ran);

        Page we = QaMenu.scenarioPage("we-1", 1, 0);
        assertTrue(we.labels().containsAll(List.of("Run we-1 same", "Run we-1 mixed")));
        Page rv = QaMenu.scenarioPage("rv-2", 1, 0);
        assertTrue(rv.labels().contains("Run rv-2 small"));
        assertNull(rv.find("Run rv-2").pick().apply(gm()), "running closes the window (it may warp you)");
        assertEquals("!qa run rv-2", ran.get(ran.size() - 1));
    }

    @Test
    void theFirstPageListsTheEveningsAndAutoQa() {
        Page root = QaMenu.root(gm());
        assertTrue(root.labels().get(0).startsWith("Evening 1: bots social ("), root.labels().toString());
        assertTrue(root.labels().get(1).startsWith("Evening 2: economy"));
        assertTrue(root.labels().get(2).startsWith("Evening 3: combat, client"));
        assertTrue(root.labels().get(3).startsWith("Auto-QA"));
        autoQa = false;
        assertFalse(QaMenu.root(gm()).labels().stream().anyMatch(l -> l.startsWith("Auto-QA")),
                "no Auto-QA line when auto-QA is off");
    }

    @Test
    void whileARunIsActiveTheFirstPageShowsItsStepsAndStop() {
        running = "cp-1";
        Character gm = gm();
        Page root = QaMenu.root(gm);
        assertTrue(root.text().contains("Running cp-1"), root.text());
        assertTrue(root.labels().contains("Do: step"), root.labels().toString());
        assertTrue(root.labels().stream().anyMatch(l -> l.contains("Stop cp-1")));
        assertTrue(root.labels().stream().anyMatch(l -> l.startsWith("Evening 1")), "the scenarios stay reachable");

        MenuWindow.Menu next = root.find("Do: step").pick().apply(gm);
        assertEquals(List.of("!qa do step"), ran);
        assertNotNull(next, "a step keeps the window on the steps");
        assertNull(root.find("Stop cp-1").pick().apply(gm));
        assertEquals("!qa stop", ran.get(1));

        running = "tr-2";
        assertTrue(QaMenu.root(gm).render().contains("type: !qa do sim <player line>"));
    }

    // ── auto-QA ──

    @Test
    void autoQaShowsTheCountsAndEachFailWithItsReason() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("PASS", 12);
        counts.put("FAIL", 2);
        counts.put("SKIP", 5);
        counts.put("NOT_RUN", 40);
        report = new AutoQaReport.Snapshot("2026-10-23", "1.5.0-rc6", counts, List.of(
                new AutoQaReport.CheckLine("t-bg-1", "Ten bot guilds", "PASS", null),
                new AutoQaReport.CheckLine("t-vg-1", "An old client is told to update", "FAIL", "no update notice in 30 s"),
                new AutoQaReport.CheckLine("t-ch-1", "Five channels", "FAIL", null),
                new AutoQaReport.CheckLine("t-af-1", "Friend", "SKIP", "manual")));
        Page p = QaMenu.autoQaPage(0);
        assertTrue(p.text().contains("PASS 12"), p.text());
        assertTrue(p.text().contains("FAIL 2"), p.text());
        assertTrue(p.text().contains("SKIP 5"), p.text());
        assertTrue(p.text().contains("NOT_RUN 40"), p.text());
        assertTrue(p.labels().stream().anyMatch(l -> l.contains("vg-1") && l.contains("no update notice")), p.labels().toString());
        assertTrue(p.labels().stream().anyMatch(l -> l.contains("ch-1") && l.contains("Five channels")));
        assertFalse(p.labels().stream().anyMatch(l -> l.contains("bg-1#k")), "passes are only counted");

        p.find("vg-1").pick().apply(gm());
        p.find("smoke").pick().apply(gm());
        assertEquals(List.of("!autoqa run t-vg-1", "!autoqa run bg-1,vg-1,ch-1"), ran);
    }

    @Test
    void autoQaWithoutAReportStillRunsChecks() {
        report = null;
        Page p = QaMenu.autoQaPage(0);
        assertTrue(p.text().contains("no report yet"), p.text());
        Page checks = QaMenu.checksPage(0);
        assertTrue(checks.labels().size() > 3);
        int total = AutoQaCatalog.all().size();
        int pages = (total + MenuWindow.PER_PAGE - 1) / MenuWindow.PER_PAGE;
        assertTrue(checks.text().contains("/" + pages + ")"), checks.text());
        String first = AutoQaCatalog.all().get(0).id();
        checks.options().stream().filter(o -> o.clickable() && o.label().contains(AutoQaCatalog.all().get(0).shortId()))
                .findFirst().orElseThrow().pick().apply(gm());
        assertEquals(List.of("!autoqa run " + first), ran);
    }

    @Test
    void shortTitlesDropTheReleaseTagAndFit() {
        assertEquals("KS war: a rival calls 2-4 bots to KS..",
                QaMenu.shortTitle("KS war: a rival calls 2-4 bots to KS you for a few minutes, then they leave (1.5.0)"));
        for (QaScenario s : QaScenarios.all()) {
            assertTrue(QaMenu.shortTitle(s.title()).length() <= 38, s.id());
        }
    }
}
