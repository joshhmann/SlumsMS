package soloMapling.menus;

import client.Character;
import client.Client;
import scripting.npc.NPCScriptManager;
import server.TimerManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * 1.5.0: menus in the stock v83 NPC talk window, driven from Java ({@code @settings}, {@code !qa}). The window is
 * {@code scripts/npc/slumsmenu.js}: it asks {@link #text} for what to show and hands each click to {@link #select}.
 * A {@link Menu} draws a {@link Page} (text, then clickable lines); a click returns the menu to show next (the window
 * stays open and redraws), or null to close it. The NPC id only frames the window with its head: no such NPC needs
 * to stand on the map.
 */
public final class MenuWindow {

    /** The script that shows every Java menu. */
    public static final String SCRIPT = "slumsmenu";

    /** Clickable lines per page before Next/Back paging kicks in. */
    public static final int PER_PAGE = 9;

    /** Wait before a menu opens again after its window closed (the client's NPC click cooldown is 500 ms). */
    static final long REOPEN_DELAY_MS = 700;

    /** One line. A null {@code pick} is a plain text line (a heading); otherwise a click runs it. */
    public record Option(String label, Function<Character, Menu> pick) {

        public static Option heading(String text) {
            return new Option(text, null);
        }

        public boolean clickable() {
            return pick != null;
        }
    }

    /** One screen: the text on top, then its lines. */
    public record Page(String text, List<Option> options) {

        public Page {
            options = List.copyOf(options);
        }

        /** The window's text: each clickable line is {@code #L<index>#...#l}, its index in {@link #options}. */
        public String render() {
            StringBuilder out = new StringBuilder(text == null ? "" : text);
            for (int i = 0; i < options.size(); i++) {
                Option o = options.get(i);
                out.append("\r\n");
                if (o.clickable()) {
                    out.append("#L").append(i).append('#').append(o.label()).append("#l");
                } else {
                    out.append("#d").append(o.label()).append("#k");
                }
            }
            return out.toString();
        }

        /** The labels of the clickable lines, in order (tests). */
        public List<String> labels() {
            List<String> out = new ArrayList<>();
            for (Option o : options) {
                if (o.clickable()) {
                    out.add(o.label());
                }
            }
            return out;
        }

        /** The clickable line whose label contains {@code part} (tests). */
        public Option find(String part) {
            for (Option o : options) {
                if (o.clickable() && o.label().contains(part)) {
                    return o;
                }
            }
            throw new IllegalArgumentException("no line with '" + part + "' in " + labels());
        }
    }

    /** A menu draws its page for a player each time it shows (so the state on it is current). */
    @FunctionalInterface
    public interface Menu {
        Page page(Character player);
    }

    /** Runs a command line exactly as if the player typed it ({@code @botdrama off}, {@code !qa run af-1}). */
    public interface Commands {
        /** @return the command's work still running off the game thread, or null when it is done */
        CompletableFuture<?> run(Character player, String line);
    }

    /** How the window reaches the player (a test can stand in). */
    public interface View {
        /** Opens the NPC window; false when it could not. */
        boolean show(Character player, int npc);
    }

    private record Session(int npc, Menu menu, Page shown) {
    }

    private static final Map<Integer, Session> sessions = new ConcurrentHashMap<>();
    private static volatile View view = MenuWindow::showWindow;
    private static volatile Scheduler scheduler = (task, delayMs) -> TimerManager.getInstance().schedule(task, delayMs);

    /** Runs a task later (tests run it at once). */
    interface Scheduler {
        void schedule(Runnable task, long delayMs);
    }

    private MenuWindow() {
    }

    /**
     * Opens a menu in the NPC window framed by {@code npc}'s head.
     *
     * @return false when the window could not open (another NPC window is open, or the player is offline)
     */
    public static boolean open(Character player, int npc, Menu menu) {
        if (player == null || menu == null) {
            return false;
        }
        sessions.put(player.getId(), new Session(npc, menu, null));
        if (!view.show(player, npc)) {
            sessions.remove(player.getId());
            return false;
        }
        return true;
    }

    /** The window's text for the player's open menu; null when none is open. Called by {@code slumsmenu.js}. */
    public static String text(Character player) {
        Session s = sessions.get(player.getId());
        if (s == null) {
            return null;
        }
        Page page;
        try {
            page = s.menu().page(player);
        } catch (RuntimeException e) {
            sessions.remove(player.getId());
            player.yellowMessage("That menu failed: " + e.getMessage());
            return null;
        }
        sessions.put(player.getId(), new Session(s.npc(), s.menu(), page));
        return page.render();
    }

    /**
     * A click on line {@code index}. Called by {@code slumsmenu.js}.
     *
     * @return true when the window stays open and shows the next menu ({@link #text} again), false to close it
     */
    public static boolean select(Character player, int index) {
        Session s = sessions.get(player.getId());
        if (s == null || s.shown() == null || index < 0 || index >= s.shown().options().size()
                || !s.shown().options().get(index).clickable()) {
            sessions.remove(player.getId());
            return false;
        }
        Menu next;
        try {
            next = s.shown().options().get(index).pick().apply(player);
        } catch (RuntimeException e) {
            sessions.remove(player.getId());
            player.yellowMessage("That did not work: " + e.getMessage());
            return false;
        }
        if (next == null) {
            sessions.remove(player.getId(), s);
            return false;
        }
        sessions.put(player.getId(), new Session(s.npc(), next, null));
        return true;
    }

    /** The window closed without a click. Called by {@code slumsmenu.js}. */
    public static void closed(Character player) {
        sessions.remove(player.getId());
    }

    /** Whether the player has one of these menus open (tests). */
    static boolean isOpen(Character player) {
        return sessions.containsKey(player.getId());
    }

    /**
     * Opens {@code menu} again once {@code after} is done (an async command) and the closed window's cooldown has
     * passed. Nothing opens when the player went offline or another NPC window is open by then.
     */
    public static void reopenLater(Character player, int npc, Menu menu, CompletableFuture<?> after) {
        CompletableFuture<?> done = after == null ? CompletableFuture.completedFuture(null) : after;
        done.whenComplete((r, e) -> scheduler.schedule(() -> open(player, npc, menu), REOPEN_DELAY_MS));
    }

    /**
     * A menu of {@code lines} split into pages of {@link #PER_PAGE} clickable lines with Next / Back. Headings
     * (non-clickable lines) stay with the lines under them, and repeat at the top of a page that starts under them.
     *
     * @param page     which page (clamped)
     * @param goTo     the menu showing another page
     * @param tail     lines under every page (e.g. Back to the previous menu)
     */
    public static Page paged(String text, List<Option> lines, int page, IntFunction<Menu> goTo, List<Option> tail) {
        List<List<Option>> pages = new ArrayList<>();
        List<Option> cur = new ArrayList<>();
        int clickable = 0;
        Option heading = null;
        for (Option o : lines) {
            if (!o.clickable()) {
                heading = o;
                cur.add(o);
                continue;
            }
            if (clickable == PER_PAGE) {
                // a heading that ended the page belongs on the next one
                Option dangling = !cur.isEmpty() && !cur.get(cur.size() - 1).clickable() ? cur.remove(cur.size() - 1) : null;
                pages.add(cur);
                cur = new ArrayList<>();
                clickable = 0;
                if (dangling != null) {
                    cur.add(dangling);
                } else if (heading != null) {
                    cur.add(heading);
                }
            }
            cur.add(o);
            clickable++;
        }
        pages.add(cur);
        int n = pages.size();
        int p = Math.max(0, Math.min(page, n - 1));
        List<Option> out = new ArrayList<>(pages.get(p));
        if (p + 1 < n) {
            int nextPage = p + 1;
            out.add(new Option("#bNext page >#k", who -> goTo.apply(nextPage)));
        }
        if (p > 0) {
            int prevPage = p - 1;
            out.add(new Option("#b< Previous page#k", who -> goTo.apply(prevPage)));
        }
        out.addAll(tail);
        String head = text == null ? "" : text;
        if (n > 1) {
            head += " #k(page " + (p + 1) + "/" + n + ")";
        }
        return new Page(head, out);
    }

    /** Shortens a line to {@code max} characters at a word break, with ".." when cut. */
    public static String shorten(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        int cut = s.lastIndexOf(' ', max - 2);
        if (cut < max / 2) {
            cut = max - 2;
        }
        String head = s.substring(0, cut);
        while (!head.isEmpty() && ",;:-(".indexOf(head.charAt(head.length() - 1)) >= 0) {
            head = head.substring(0, head.length() - 1).stripTrailing();
        }
        return head + "..";
    }

    private static boolean showWindow(Character player, int npc) {
        Client c = player.getClient();
        // start() would close an NPC window the player already has open; during the NPC click cooldown it shows
        // nothing yet reports success.
        if (c == null || c.getPlayer() != player || !player.isLoggedinWorld()
                || NPCScriptManager.getInstance().getCM(c) != null) {
            return false;
        }
        if (!c.canClickNPC()) {
            return false;
        }
        return NPCScriptManager.getInstance().start(c, npc, SCRIPT, player);
    }

    /** Test seam: a stand-in window and scheduler (null restores the real ones). */
    static void useForTest(View testView, Scheduler testScheduler) {
        view = testView == null ? MenuWindow::showWindow : testView;
        scheduler = testScheduler == null
                ? (task, delayMs) -> TimerManager.getInstance().schedule(task, delayMs) : testScheduler;
        sessions.clear();
    }
}
