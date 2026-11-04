package soloMapling.crafting;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WeaponCoreExchangeTest {
    static final class Bag implements WeaponCoreExchange.Hands {
        final Map<Integer,Integer> items=new HashMap<>();
        boolean room=true;
        public int count(int id) { return items.getOrDefault(id,0); }
        public boolean canHold(int id) { return room; }
        public void take(int id) { items.merge(id,-1,Integer::sum); }
        public void give(int id) { items.merge(id,1,Integer::sum); }
        int total() { return items.values().stream().mapToInt(Integer::intValue).sum(); }
    }
    @Test void exchangePreservesCoreCountAndCanGoBack() {
        Bag b=new Bag(); b.items.put(4036544,2);
        assertTrue(WeaponCoreExchange.exchange(b,4036544,4036545).made());
        assertEquals(1,b.count(4036544));assertEquals(1,b.count(4036545));assertEquals(2,b.total());
        assertTrue(WeaponCoreExchange.exchange(b,4036545,4036544).made());assertEquals(2,b.total());
    }
    @Test void invalidIdsAndSameCoreDoNothing() {
        for(int[] pair:new int[][]{{1,4036545},{4036544,1},{4036544,4036544}}) {
            Bag b=new Bag();b.items.put(4036544,1);
            assertFalse(WeaponCoreExchange.exchange(b,pair[0],pair[1]).made());assertEquals(1,b.total());
        }
    }
    @Test void missingInputCannotMintACore() {
        Bag b=new Bag();assertFalse(WeaponCoreExchange.exchange(b,4036544,4036545).made());assertEquals(0,b.total());
    }
    @Test void fullInventoryPreservesInput() {
        Bag b=new Bag();b.items.put(4036544,1);b.room=false;
        assertFalse(WeaponCoreExchange.exchange(b,4036544,4036545).made());assertEquals(1,b.count(4036544));
    }
    @Test void confirmingAgainCannotRepeatAnAlreadyConsumedInput() {
        Bag b=new Bag();b.items.put(4036544,1);
        assertTrue(WeaponCoreExchange.exchange(b,4036544,4036545).made());
        assertFalse(WeaponCoreExchange.exchange(b,4036544,4036545).made());assertEquals(1,b.total());
    }
    @Test void onlyFiveExistingClassCoresAreAccepted() {
        assertEquals(java.util.Set.of(4036544,4036545,4036546,4036547,4036548),WeaponCoreExchange.CORE_IDS);
    }
}
