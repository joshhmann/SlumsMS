package scripting;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.npc.NPCConversationManager;
import soloMapling.crafting.NormalGearBridge;
import javax.script.Invocable;
import javax.script.ScriptEngine;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NimbleGearGuideTest {
    private String previous;
    @BeforeEach void enable() {previous=System.getProperty(NormalGearBridge.PROPERTY);System.setProperty(NormalGearBridge.PROPERTY,"true");}
    @AfterEach void restore() {if(previous==null)System.clearProperty(NormalGearBridge.PROPERTY);else System.setProperty(NormalGearBridge.PROPERTY,previous);}
    static class Loader extends AbstractScriptManager {ScriptEngine load(){return getInvocableScriptEngine("npc/9977779.js");}}
    static class Run {
        final NPCConversationManager cm=mock(NPCConversationManager.class);
        final Invocable npc;
        Run() {
            ScriptEngine e=new Loader().load();assertNotNull(e);e.put("cm",cm);npc=(Invocable)e;
        }
        void choose(int selection) throws Exception {npc.invokeFunction("action",1,0,selection);}
    }
    @Test void enabledMenuShowsMageEquipmentAndTwoSourcesWithoutGrantingAnything() throws Exception {
        var r=new Run();r.npc.invokeFunction("start");
        verify(r.cm).sendSimple(contains("Normal equipment guide"));
        r.choose(3);r.choose(1);r.choose(0);r.choose(1372010);
        verify(r.cm).sendOk(argThat(s->s.contains("#t1372010#") && s.contains("#m400030500#")
                && s.contains("#m400010100#") && s.contains("Equip level: 98")));
        verify(r.cm,never()).gainItem(anyInt(),anyShort());verify(r.cm,never()).gainMeso(anyInt());
    }
    @Test void disabledGuideIsHiddenAndInjectedChoiceIsRefused() throws Exception {
        System.clearProperty(NormalGearBridge.PROPERTY);var r=new Run();r.npc.invokeFunction("start");
        verify(r.cm).sendSimple(argThat(s->!s.contains("Normal equipment guide")));
        r.choose(3);verify(r.cm).dispose();verify(r.cm,never()).sendOk(anyString());
    }
    @Test void invalidItemCannotShowAnotherFamiliesRoute() throws Exception {
        var r=new Run();r.npc.invokeFunction("start");r.choose(3);r.choose(1);r.choose(0);r.choose(1402036);
        verify(r.cm).dispose();verify(r.cm,never()).sendOk(anyString());
    }
    @Test void cancelAndSwitchDisabledMidDialogCannotContinue() throws Exception {
        var r=new Run();r.npc.invokeFunction("start");r.choose(3);
        System.clearProperty(NormalGearBridge.PROPERTY);r.choose(1);
        verify(r.cm).dispose();verify(r.cm,never()).sendOk(anyString());
    }
}
