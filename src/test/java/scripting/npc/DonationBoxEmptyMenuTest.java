package scripting.npc;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import scripting.AbstractScriptManager;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * cygnus-lv30-random-crash (1.6.7): the Donation Box (9000041.js and quicksell.js) listed only occupied slots, so an
 * empty tab, or a start at the last item, sent a menu with no row, and Enter on it crashed the client. Those steps
 * now answer with an OK dialog and close; a tab with items still gets its menu.
 */
class DonationBoxEmptyMenuTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    public static final class Cm {
        public Character player;
        public final List<String> dialogs = new ArrayList<>();
        public boolean disposed;

        public Character getPlayer() {
            return player;
        }

        public void sendSimple(String text) {
            dialogs.add("simple:" + text);
        }

        public void sendOk(String text) {
            dialogs.add("ok:" + text);
        }

        public void dispose() {
            disposed = true;
        }
    }

    /** A USE tab holding an item only in {@code slots}. */
    private static Cm withUseSlots(short... slots) {
        Inventory use = mock(Inventory.class);
        when(use.getItem(anyShort())).thenReturn(null);
        for (short s : slots) {
            Item item = mock(Item.class);
            when(item.getItemId()).thenReturn(2000000);
            when(use.getItem(s)).thenReturn(item);
        }
        Character chr = mock(Character.class);
        when(chr.getInventory(any(InventoryType.class))).thenReturn(use);
        Cm cm = new Cm();
        cm.player = chr;
        return cm;
    }

    /** start (tab list), then pick USE, then (when given) a start slot; the dialogs after the tab list. */
    private static Cm talk(String script, int useSelection, Integer startSlot, short... slots) throws Exception {
        ScriptEngine engine = new Loader().load(script);
        assertNotNull(engine, script + " evaluates");
        Cm cm = withUseSlots(slots);
        engine.put("cm", cm);
        Invocable npc = (Invocable) engine;
        npc.invokeFunction("start");
        npc.invokeFunction("action", 1, 4, useSelection);
        if (startSlot != null && !cm.disposed) {
            npc.invokeFunction("action", 1, 4, startSlot);
        }
        return cm;
    }

    private static String last(Cm cm) {
        return cm.dialogs.get(cm.dialogs.size() - 1);
    }

    @Test
    void anEmptyTabIsAnOkDialogNotARowlessMenu() throws Exception {
        for (Cm cm : List.of(talk("npc/9000041.js", 1, null), talk("npc/quicksell.js", 2, null))) {
            assertEquals("ok:There is nothing in that tab to sell.", last(cm));
            assertTrue(cm.disposed);
        }
    }

    @Test
    void startingAtTheLastItemIsAnOkDialogNotARowlessMenu() throws Exception {
        for (Cm cm : List.of(talk("npc/9000041.js", 1, 7, (short) 3, (short) 7),
                talk("npc/quicksell.js", 2, 7, (short) 3, (short) 7))) {
            assertTrue(last(cm).startsWith("ok:There is no item after that slot"), last(cm));
            assertTrue(cm.disposed);
        }
    }

    @Test
    void aTabWithItemsStillGetsItsMenu() throws Exception {
        for (Cm cm : List.of(talk("npc/9000041.js", 1, null, (short) 3, (short) 7),
                talk("npc/quicksell.js", 2, null, (short) 3, (short) 7))) {
            assertTrue(last(cm).startsWith("simple:") && last(cm).contains("#L3#") && last(cm).contains("#L7#"),
                    last(cm));
        }
    }
}
