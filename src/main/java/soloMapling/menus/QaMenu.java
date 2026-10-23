package soloMapling.menus;

import client.Character;
import client.command.CommandsExecutor;
import soloMapling.autoqa.AutoQa;
import soloMapling.autoqa.AutoQaCatalog;
import soloMapling.autoqa.AutoQaReport;
import soloMapling.autoqa.AutoQaService;
import soloMapling.autoqa.Check;
import soloMapling.menus.MenuWindow.Option;
import soloMapling.menus.MenuWindow.Page;
import soloMapling.qa.QaScenario;
import soloMapling.qa.QaSuite;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 1.5.0 {@code !qa} with no arguments (test server only, like the command): the scenarios in the NPC window, grouped
 * by the QA page's evenings ({@link QaMenuCatalog}). A click on a scenario offers its runs ({@code !qa run <id>
 * [variant]}); while a run is active the first page lists its {@code !qa do} steps and Stop; Auto-QA (when on) shows
 * the latest report and runs a check or the smoke set. Every click runs the typed command through
 * {@link CommandsExecutor}, so the GM audit, refusals and QA logs are the command's.
 */
public final class QaMenu {

    /** Maple Administrator frames the window. */
    public static final int NPC = 9010000;

    /** What the menu reads (a test stands in for the server's suite and auto-QA report). */
    interface Source {
        List<QaScenario> scenarios();

        String runningId(int gmId);

        boolean autoQaEnabled();

        AutoQaReport.Snapshot autoQaReport();

        List<Check> autoQaChecks();
    }

    static final Source LIVE = new Source() {
        public List<QaScenario> scenarios() {
            return QaSuite.get().scenarios();
        }

        public String runningId(int gmId) {
            return QaSuite.get().runningId(gmId);
        }

        public boolean autoQaEnabled() {
            return AutoQa.isEnabled();
        }

        public AutoQaReport.Snapshot autoQaReport() {
            return AutoQaService.get().latest();
        }

        public List<Check> autoQaChecks() {
            return AutoQaCatalog.all();
        }
    };

    static final MenuWindow.Commands TYPED = (player, line) -> {
        CommandsExecutor.getInstance().handle(player.getClient(), line);
        return null;
    };

    private static volatile Source source = LIVE;
    private static volatile MenuWindow.Commands commands = TYPED;

    private QaMenu() {
    }

    /** {@code !qa}: opens the menu; false when the window could not open. */
    public static boolean open(Character gm) {
        return MenuWindow.open(gm, NPC, QaMenu::root);
    }

    // ── pages ──

    /** The first page: the running scenario's steps (if any), the evenings, Auto-QA. */
    static Page root(Character gm) {
        StringBuilder text = new StringBuilder("#e!qa#n release test scenarios (logs/qa).");
        List<Option> lines = new ArrayList<>();
        String running = source.runningId(gm.getId());
        if (running != null) {
            QaScenario s = scenario(running);
            text.append("\r\n#bRunning ").append(running).append("#k ").append(s == null ? "" : shortTitle(s.title()));
            QaMenuCatalog.Entry e = QaMenuCatalog.find(running);
            if (e != null && !e.steps().isEmpty()) {
                lines.add(Option.heading("Steps (!qa do ...):"));
                for (String step : e.steps()) {
                    lines.add(new Option("Do: " + step, p -> doStep(p, step)));
                }
            }
            if (e != null) {
                for (String typed : e.typed()) {
                    if (!typed.startsWith("run:")) {
                        lines.add(Option.heading("type: !qa do " + typed));
                    }
                }
            }
            lines.add(new Option("#rStop " + running + "#k", p -> run(p, "!qa stop")));
            lines.add(Option.heading("Scenarios:"));
        }
        for (QaMenuCatalog.Evening ev : QaMenuCatalog.EVENINGS) {
            int n = QaMenuCatalog.evening(ev.number()).size();
            lines.add(new Option(ev.title() + " (" + n + ")", p -> eveningMenu(ev.number(), 0)));
        }
        if (source.autoQaEnabled()) {
            lines.add(new Option("Auto-QA: latest report, run checks", p -> autoQaMenu(0)));
        }
        return new Page(text.toString(), lines);
    }

    static MenuWindow.Menu eveningMenu(int evening, int page) {
        return gm -> eveningPage(evening, page);
    }

    /** One evening's scenarios under their section headings, paged. */
    static Page eveningPage(int evening, int page) {
        QaMenuCatalog.Evening ev = QaMenuCatalog.EVENINGS.get(evening - 1);
        List<Option> lines = new ArrayList<>();
        String section = null;
        for (QaMenuCatalog.Entry e : QaMenuCatalog.evening(evening)) {
            QaScenario s = scenario(e.id());
            if (s == null) {
                continue; // placed but not in this build
            }
            if (!e.section().equals(section)) {
                section = e.section();
                lines.add(Option.heading(section));
            }
            lines.add(new Option("#b" + e.id() + "#k " + shortTitle(s.title()),
                    p -> scenarioMenu(e.id(), evening, page)));
        }
        return MenuWindow.paged("#e" + ev.title() + "#n", lines, page, i -> eveningMenu(evening, i),
                List.of(new Option("< Back", p -> QaMenu::root)));
    }

    static MenuWindow.Menu scenarioMenu(String id, int evening, int page) {
        return gm -> scenarioPage(id, evening, page);
    }

    /** A scenario: its title and arguments, a Run line per variant, the steps it will offer. */
    static Page scenarioPage(String id, int evening, int page) {
        QaScenario s = scenario(id);
        QaMenuCatalog.Entry e = QaMenuCatalog.find(id);
        StringBuilder text = new StringBuilder("#e").append(id).append("#n");
        if (s != null && !s.aliases().isEmpty()) {
            text.append(" (also ").append(String.join(", ", s.aliases())).append(')');
        }
        text.append(": ").append(s == null ? "not in this build" : s.title());
        if (s != null && !s.usage().isBlank()) {
            text.append("\r\n#dArgs: ").append(MenuWindow.shorten(s.usage(), 90)).append("#k");
        }
        if (e != null && !e.steps().isEmpty()) {
            text.append("\r\nSteps once running: ").append(String.join(", ", e.steps()));
        }
        List<Option> lines = new ArrayList<>();
        lines.add(new Option("Run " + id, p -> run(p, "!qa run " + id)));
        if (e != null) {
            for (QaMenuCatalog.Variant v : e.variants()) {
                lines.add(new Option("Run " + id + " " + v.label(), p -> run(p, "!qa run " + id + " " + v.args())));
            }
            for (String typed : e.typed()) {
                lines.add(Option.heading("type: " + (typed.startsWith("run:") ? "!qa run " + id + typed.substring(4)
                        : "!qa do " + typed)));
            }
        }
        lines.add(new Option("< Back", p -> eveningMenu(evening, page)));
        return new Page(text.toString(), lines);
    }

    static MenuWindow.Menu autoQaMenu(int page) {
        return gm -> autoQaPage(page);
    }

    /** Auto-QA: the latest report's counts, each failing check with its reason (click: run it again), runs. */
    static Page autoQaPage(int page) {
        AutoQaReport.Snapshot r = source.autoQaReport();
        StringBuilder text = new StringBuilder("#eAuto-QA#n ");
        List<Option> lines = new ArrayList<>();
        if (r == null) {
            text.append("has no report yet.");
        } else {
            text.append("report ").append(r.day()).append(" (").append(r.build()).append("):\r\n")
                    .append("#bPASS ").append(r.count("PASS")).append("#k  #rFAIL ").append(r.count("FAIL"))
                    .append("#k  SKIP ").append(r.count("SKIP")).append("  NOT_RUN ").append(r.count("NOT_RUN"));
            boolean any = false;
            for (AutoQaReport.CheckLine c : r.checks()) {
                if (!"FAIL".equals(c.status())) {
                    continue;
                }
                if (!any) {
                    lines.add(Option.heading("Failing (click to run again):"));
                    any = true;
                }
                lines.add(new Option("#r" + shortId(c.id()) + "#k " + MenuWindow.shorten(
                        c.reason() == null ? c.title() : c.reason(), 40), p -> run(p, "!autoqa run " + c.id())));
            }
        }
        List<Option> tail = List.of(
                new Option("Run the smoke set (" + String.join(", ", AutoQaService.SMOKE) + ")",
                        p -> run(p, "!autoqa run " + String.join(",", AutoQaService.SMOKE))),
                new Option("Run one check...", p -> checksMenu(0)),
                new Option("< Back", p -> QaMenu::root));
        return MenuWindow.paged(text.toString(), lines, page, QaMenu::autoQaMenu, tail);
    }

    static MenuWindow.Menu checksMenu(int page) {
        return gm -> checksPage(page);
    }

    /** Every auto-QA check with its last status; a click runs it. */
    static Page checksPage(int page) {
        AutoQaReport.Snapshot r = source.autoQaReport();
        List<Option> lines = new ArrayList<>();
        for (Check c : source.autoQaChecks()) {
            String status = "";
            if (r != null) {
                for (AutoQaReport.CheckLine l : r.checks()) {
                    if (l.id().equals(c.id())) {
                        status = statusTag(l.status()) + " ";
                    }
                }
            }
            String manual = c.kind() == Check.Kind.MANUAL ? " (manual)" : "";
            lines.add(new Option(status + "#b" + c.shortId() + "#k " + MenuWindow.shorten(c.title(), 30) + manual,
                    p -> run(p, "!autoqa run " + c.id())));
        }
        return MenuWindow.paged("#eAuto-QA checks#n: click one to run it.", lines, page, QaMenu::checksMenu,
                List.of(new Option("< Back", p -> autoQaMenu(0))));
    }

    // ── clicks ──

    /** Runs a command and closes the window (its lines go to chat; a run or stop may warp you). */
    static MenuWindow.Menu run(Character gm, String command) {
        commands.run(gm, command);
        return null;
    }

    /** {@code !qa do <step>}: the window stays on the steps unless the step moved you to another map. */
    static MenuWindow.Menu doStep(Character gm, String step) {
        int map = gm.getMapId();
        commands.run(gm, "!qa do " + step);
        return gm.getMapId() == map && source.runningId(gm.getId()) != null ? QaMenu::root : null;
    }

    // ── text ──

    private static QaScenario scenario(String id) {
        for (QaScenario s : source.scenarios()) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        return null;
    }

    /** A scenario title cut to fit a menu line: no trailing "(1.5.0)" / "(ticket 58)", at most 38 characters. */
    static String shortTitle(String title) {
        String t = title.strip();
        if (t.endsWith(")")) {
            int open = t.lastIndexOf(" (");
            if (open > 0) {
                t = t.substring(0, open);
            }
        }
        return MenuWindow.shorten(t, 38);
    }

    private static String shortId(String checkId) {
        return checkId.startsWith("t-") ? checkId.substring(2) : checkId;
    }

    private static String statusTag(String status) {
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "PASS" -> "#bPASS#k";
            case "FAIL" -> "#rFAIL#k";
            case "SKIP" -> "SKIP";
            default -> "--";
        };
    }

    /** Test seam (null restores the real ones). */
    static void useForTest(Source testSource, MenuWindow.Commands testCommands) {
        source = testSource == null ? LIVE : testSource;
        commands = testCommands == null ? TYPED : testCommands;
    }
}
