package scripting;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import scripting.npc.NPCConversationManager;
import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NimbleCoreExchangeTest {
    private String previous;
    @BeforeEach void enable() { previous=System.getProperty("slums.forge.coreExchange");System.setProperty("slums.forge.coreExchange","true"); }
    @AfterEach void restore() { if(previous==null) System.clearProperty("slums.forge.coreExchange");else System.setProperty("slums.forge.coreExchange",previous); }
    @Test void disabledExchangeIsHiddenAndRejectsAnInjectedMenuChoice() throws Exception {
        System.clearProperty("slums.forge.coreExchange");
        Run r=new Run();r.bag.put(4036544,1);r.npc.invokeFunction("start");
        verify(r.cm).sendSimple(argThat(text->!text.contains("Exchange weapon cores")));
        r.npc.invokeFunction("action",1,0,2);
        verify(r.cm).dispose();verify(r.cm,never()).gainItem(anyInt(),anyShort());
    }

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load() { return getInvocableScriptEngine("npc/9977779.js"); }
    }
    private static final class Run {
        final Map<Integer,Integer> bag=new HashMap<>();
        final NPCConversationManager cm=mock(NPCConversationManager.class);
        final Inventory inventory=mock(Inventory.class);
        final Invocable npc;
        Run() throws Exception {
            Character player=mock(Character.class);
            when(cm.getPlayer()).thenReturn(player);when(player.getInventory(InventoryType.ETC)).thenReturn(inventory);
            when(cm.isEndChat(anyInt(),anyInt())).thenAnswer(i->((Integer)i.getArgument(0))==0&&((Integer)i.getArgument(1))==1);
            when(cm.getItemQuantity(anyInt())).thenAnswer(i->bag.getOrDefault(i.getArgument(0),0));
            when(inventory.countById(anyInt())).thenAnswer(i->bag.getOrDefault(i.getArgument(0),0));
            when(cm.canHold(anyInt(),eq(1))).thenReturn(true);
            doAnswer(i->{bag.merge(i.getArgument(0),((Number)i.getArgument(1)).intValue(),Integer::sum);return null;})
                    .when(cm).gainItem(anyInt(),anyShort());
            ScriptEngine e=new Loader().load();assertNotNull(e);e.put("cm",cm);npc=(Invocable)e;
        }
        void choose() throws Exception {
            npc.invokeFunction("start");
            npc.invokeFunction("action",1,0,2);
            npc.invokeFunction("action",1,0,4036544);
            npc.invokeFunction("action",1,0,4036545);
        }
        void confirm() throws Exception {npc.invokeFunction("action",1,1,0);}
    }
    @Test void realNimbleMenuExchangesExactlyOneCoreAfterConfirmation() throws Exception {
        Run r=new Run();r.bag.put(4036544,1);r.choose();assertEquals(1,r.bag.get(4036544));
        verify(r.cm).sendYesNo(contains("No meso fee"));
        r.confirm();assertEquals(0,r.bag.get(4036544));assertEquals(1,r.bag.get(4036545));
        verify(r.inventory).lockInventory();verify(r.inventory).unlockInventory();verify(r.cm).dispose();
    }
    @Test void cancellingKeepsTheInput() throws Exception {
        Run r=new Run();r.bag.put(4036544,1);r.choose();r.npc.invokeFunction("action",0,1,0);
        assertEquals(Map.of(4036544,1),r.bag);verify(r.cm,never()).gainItem(anyInt(),anyShort());
    }
    @Test void anInputRemovedAfterTheMenuCannotMintAnOutput() throws Exception {
        Run r=new Run();r.bag.put(4036544,1);r.choose();r.bag.clear();r.confirm();
        assertTrue(r.bag.isEmpty());verify(r.cm).sendOk(contains("no longer"));
    }
}
