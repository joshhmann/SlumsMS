package soloMapling.crafting;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class NormalGearBridgeTest {
    @Test void actualGuideHasTwoSourcesPerItemAndAllFamilies() throws Exception {
        var targets=NormalGearBridge.targets();assertEquals(226,targets.size());
        var counts=new HashMap<Integer,Integer>();targets.keySet().forEach(p->counts.merge(p.item(),1,Integer::sum));
        assertEquals(113,counts.size());assertTrue(counts.values().stream().allMatch(n->n==2));
    }
    @Test void questOnlyAndRareRowsDoNotPassAsAdequateNormalSupply() {
        var pair=new NormalGearBridge.Pair(2400000,1302059);var targets=Map.of(pair,2100);
        for(var actual: java.util.List.of(Map.<NormalGearBridge.Pair,NormalGearBridge.Drop>of(),
                Map.of(pair,new NormalGearBridge.Drop(99,999999)),Map.of(pair,new NormalGearBridge.Drop(0,60)))) {
            assertTrue(NormalGearBridge.checkRates(targets,actual).getFirst().startsWith("FAIL"));
        }
        assertTrue(NormalGearBridge.checkRates(targets,Map.of(pair,new NormalGearBridge.Drop(0,7000)))
                .getFirst().startsWith("PASS"));
    }
}
