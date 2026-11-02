package scripting;

import client.inventory.Equip;
import client.inventory.manipulator.InventoryManipulator;
import org.junit.jupiter.api.Test;
import scripting.npc.NPCConversationManager;
import server.StarBoost;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NimbleHpTransferTest {
    private Equip transfer(int baseHp, int baseMp, int oldHp, int oldMp) {
        return transfer(baseHp,baseMp,oldHp,oldMp,false);
    }
    private Equip transfer(int baseHp, int baseMp, int oldHp, int oldMp, boolean checkRefund) {
        Equip old = mock(Equip.class, CALLS_REAL_METHODS);
        doReturn(client.inventory.InventoryType.EQUIP).when(old).getInventoryType();
        doReturn((short)1).when(old).getPosition();
        doReturn((short)1).when(old).getQuantity();
        old.setHp((short)oldHp); old.setMp((short)oldMp);
        Equip output = mock(Equip.class, CALLS_REAL_METHODS);
        output.setHp((short)baseHp); output.setMp((short)baseMp);
        NPCConversationManager cm = mock(NPCConversationManager.class);
        when(cm.getStarForceEquip(1)).thenReturn(old);
        when(cm.getStarForceEquipCraftingBonus(old)).thenReturn(new StarBoost(0,0,0,0,0,0));
        when(cm.applyStarForceEquipCraftingBonus(output,1)).thenCallRealMethod();
        when(cm.getStarForceEquipSpellTraceBonus(old)).thenReturn(123);
        when(cm.getStarForceEquipSpellTraceBonus(output)).thenReturn(7);
        try (var inventory = mockStatic(InventoryManipulator.class)) {
            assertSame(output,cm.applyStarForceEquipCraftingBonus(output,1));
            if (checkRefund) verify(cm).gainItem(4000999,(short)123);
            inventory.verify(() -> InventoryManipulator.removeFromSlot(null,old.getInventoryType(),old.getPosition(),old.getQuantity(),false));
        }
        return output;
    }
    @Test void preservesHpAndMpInsteadOfDiscardingTheOldItemsHealth() {
        Equip result=transfer(20,30,900,600);
        assertEquals(920,result.getHp()); assertEquals(630,result.getMp());
    }
    @Test void clampsAtTheExistingEquipStatLimit() {
        Equip result=transfer(20000,20000,20000,20000);
        assertEquals(Short.MAX_VALUE,result.getHp()); assertEquals(Short.MAX_VALUE,result.getMp());
    }
    @Test void refundUsesConsumedEquipmentRatherThanFreshOutput() {
        transfer(20,30,100,200,true);
    }
    @Test void aPlainInputAddsNoExtraHealth() {
        Equip result=transfer(20,30,0,0);
        assertEquals(20,result.getHp()); assertEquals(30,result.getMp());
    }
}
