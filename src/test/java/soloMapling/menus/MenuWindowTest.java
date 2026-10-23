package soloMapling.menus;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.menus.MenuWindow.Option;
import soloMapling.menus.MenuWindow.Page;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuWindowTest {

    static Character player(int id) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(id);
        chr.setName("Tester" + id);
        return chr;
    }

    @AfterEach
    void real() {
        MenuWindow.useForTest(null, null);
    }

    private static List<Option> items(int n) {
        List<Option> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new Option("item " + i, p -> null));
        }
        return out;
    }

    @Test
    void aShortListIsOnePageWithoutPaging() {
        Page p = MenuWindow.paged("Head", items(3), 0, i -> null, List.of(new Option("< Back", x -> null)));
        assertEquals(List.of("item 0", "item 1", "item 2", "< Back"), p.labels());
        assertEquals("Head", p.text());
    }

    @Test
    void seventyItemsSplitIntoPagesOfNineWithNextAndPrevious() {
        List<Option> lines = items(70);
        int pages = (70 + MenuWindow.PER_PAGE - 1) / MenuWindow.PER_PAGE;
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < pages; i++) {
            Page p = MenuWindow.paged("Head", lines, i, n -> null, List.of());
            for (String l : p.labels()) {
                if (l.startsWith("item")) {
                    seen.add(l);
                }
            }
            assertEquals(i + 1 < pages, p.labels().stream().anyMatch(l -> l.contains("Next page")), "next on " + i);
            assertEquals(i > 0, p.labels().stream().anyMatch(l -> l.contains("Previous page")), "previous on " + i);
            assertTrue(p.text().contains("(page " + (i + 1) + "/" + pages + ")"), p.text());
        }
        assertEquals(70, seen.size(), "every item on exactly one page");
        assertEquals(70, seen.stream().distinct().count());
        // out of range clamps to the last page
        assertTrue(MenuWindow.paged("Head", lines, 99, n -> null, List.of()).labels().contains("item 69"));
    }

    @Test
    void nextPageClickShowsThatPage() {
        List<Integer> asked = new ArrayList<>();
        Page p = MenuWindow.paged("Head", items(20), 0, n -> {
            asked.add(n);
            return x -> null;
        }, List.of());
        p.find("Next page").pick().apply(player(1));
        assertEquals(List.of(1), asked);
    }

    @Test
    void aHeadingRepeatsOnThePageThatStartsUnderIt() {
        List<Option> lines = new ArrayList<>();
        lines.add(Option.heading("Section A"));
        lines.addAll(items(12));
        lines.add(Option.heading("Section B"));
        lines.addAll(items(2));
        Page second = MenuWindow.paged("Head", lines, 1, n -> null, List.of());
        assertEquals("Section A", second.options().get(0).label());
        assertTrue(second.render().contains("#dSection A#k"));
        assertTrue(second.render().contains("#dSection B#k"));

        // a heading that would end a page moves to the next one
        List<Option> exact = new ArrayList<>(items(MenuWindow.PER_PAGE));
        exact.add(Option.heading("Late"));
        exact.addAll(items(1));
        Page first = MenuWindow.paged("Head", exact, 0, n -> null, List.of());
        assertFalse(first.render().contains("Late"), first.render());
        assertEquals("Late", MenuWindow.paged("Head", exact, 1, n -> null, List.of()).options().get(0).label());
    }

    @Test
    void headingsAreNotClickableAndIndexesMatchTheLines() {
        Page p = new Page("T", List.of(Option.heading("H"), new Option("a", x -> null)));
        String text = p.render();
        assertTrue(text.contains("#L1#a#l"), text);
        assertFalse(text.contains("#L0#"), text);
    }

    @Test
    void aClickRedrawsWithTheNextMenuOrCloses() {
        Character me = player(2001);
        List<String> shown = new ArrayList<>();
        MenuWindow.useForTest((p, npc) -> shown.add("npc " + npc), (task, delay) -> task.run());
        MenuWindow.Menu second = p -> new Page("second", List.of(new Option("close", x -> null)));
        MenuWindow.Menu first = p -> new Page("first", List.of(new Option("go", x -> second)));
        assertTrue(MenuWindow.open(me, 9200000, first));
        assertEquals(List.of("npc 9200000"), shown);
        assertTrue(MenuWindow.text(me).startsWith("first"));
        assertTrue(MenuWindow.select(me, 0), "the window stays open");
        assertTrue(MenuWindow.text(me).startsWith("second"));
        assertFalse(MenuWindow.select(me, 0), "null closes it");
        assertFalse(MenuWindow.isOpen(me));
        assertNull(MenuWindow.text(me));
    }

    @Test
    void aBadIndexOrAClosedWindowEndsTheMenu() {
        Character me = player(2002);
        MenuWindow.useForTest((p, npc) -> true, (task, delay) -> task.run());
        MenuWindow.open(me, 1, p -> new Page("x", List.of(Option.heading("h"), new Option("a", x -> null))));
        MenuWindow.text(me);
        assertFalse(MenuWindow.select(me, 0), "a heading is not a choice");
        MenuWindow.open(me, 1, p -> new Page("x", List.of()));
        MenuWindow.text(me);
        MenuWindow.closed(me);
        assertFalse(MenuWindow.isOpen(me));
    }

    @Test
    void reopenLaterWaitsForTheAsyncCommand() {
        Character me = player(2003);
        List<Long> delays = new ArrayList<>();
        List<Integer> opened = new ArrayList<>();
        MenuWindow.useForTest((p, npc) -> opened.add(npc), (task, delay) -> {
            delays.add(delay);
            task.run();
        });
        CompletableFuture<Void> work = new CompletableFuture<>();
        MenuWindow.reopenLater(me, 7, p -> new Page("again", List.of()), work);
        assertTrue(opened.isEmpty(), "not before the command finished");
        work.complete(null);
        assertEquals(List.of(7), opened);
        assertTrue(delays.get(0) >= 500, "after the client's NPC click cooldown");
        assertTrue(MenuWindow.text(me).startsWith("again"));
    }

    @Test
    void shortenCutsAtAWord() {
        assertEquals("short", MenuWindow.shorten("short", 10));
        String s = MenuWindow.shorten("Gachapon: 3 tickets at a town's machine, rolls", 20);
        assertTrue(s.length() <= 20, s);
        assertTrue(s.endsWith(".."), s);
    }
}
