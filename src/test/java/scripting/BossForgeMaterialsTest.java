package scripting;

import org.junit.jupiter.api.Test;
import javax.script.ScriptEngine;
import static org.junit.jupiter.api.Assertions.*;

/** Execute the real clear/claim script with a JS event and player, without a running world. */
class BossForgeMaterialsTest {
    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load() { return getInvocableScriptEngine("npc/3004006.js"); }
    }
    private ScriptEngine clear(int map) throws Exception {
        ScriptEngine e=new Loader().load();assertNotNull(e);e.put("testMap",map);
        e.eval("""
            var awards={};var properties={};
            var recipient={gainItem:function(id,n){awards[id]=(awards[id]||0)+n;}};
            var character={isBot:function(){return false;},getWorld:function(){return 1;},getId:function(){return 123;},
                getAbstractPlayerInteraction:function(){return recipient;},
                getMap:function(){return {getReturnMapId:function(){return 100000000;}};},
                getClient:function(){return {getWorldServer:function(){return {removeUnclaimed:function(){}};}};}};
            var event={isEventCleared:function(){return true;},sendDmgDealt:function(){return '';},
                getPlayers:function(){return [character];},getProperty:function(k){return properties[k];},
                setProperty:function(k,v){properties[k]=v;}};
            var cm={getEventInstance:function(){return event;},getMapId:function(){return testMap;},
                getPlayer:function(){return character;},sendYesNo:function(){},warp:function(){},dispose:function(){}};
            start();action(1,0,0);
            """);
        return e;
    }
    private int amount(ScriptEngine e,int item) throws Exception { return ((Number)e.eval("awards["+item+"]||0")).intValue(); }
    @Test void normalLucidGivesOneNonQuestForgeTrophy() throws Exception {
        ScriptEngine e=clear(450004751);assertEquals(1,amount(e,4001879));assertEquals(1,amount(e,4033732));
    }
    @Test void hardLucidGivesTwoTrophiesAndTheHardToken() throws Exception {
        ScriptEngine e=clear(450004750);assertEquals(2,amount(e,4001879));assertEquals(1,amount(e,4036535));
    }
    @Test void normalClearDoesNotGiveTheHardModeToken() throws Exception {
        assertEquals(0,amount(clear(450004751),4036535));
    }
    @Test void everySetWeaponTrophyHasAnActualClearReceipt() throws Exception {
        for (int[] pair:new int[][]{{211070100,4001693},{271040100,4000659},{450004751,4001879},{450007441,4001890},{450012211,4001894}})
            assertEquals(1,amount(clear(pair[0]),pair[1]),"item "+pair[1]);
    }
    @Test void theHardTokensAndMagnusInputsHaveClearReceipts() throws Exception {
        for (int[] pair:new int[][]{{450004750,4036535},{450007440,4036536},{450012210,4036537},{450010100,4036538},{401060100,4036540},{401060100,4036543}})
            assertEquals(1,amount(clear(pair[0]),pair[1]),"item "+pair[1]);
    }
    @Test void eachRequiredBossClearGivesEnoughAnyClassCoresForTheExchange() throws Exception {
        for (int[] pair:new int[][]{{271040100,1},{450004751,3},{450007441,3},{450012211,3},{450004750,5},{450007440,5},{450012210,5},{450010100,5},{401060100,7}}) {
            ScriptEngine e=clear(pair[0]);int total=0;
            for (int id=4036544;id<=4036548;id++) total+=amount(e,id);
            assertEquals(pair[1],total,"map "+pair[0]);
        }
    }
    @Test void anotherTalkCannotClaimTheSameClearAgain() throws Exception {
        ScriptEngine e=clear(450004751);e.eval("start();action(1,0,0);");assertEquals(1,amount(e,4001879));
    }
}
