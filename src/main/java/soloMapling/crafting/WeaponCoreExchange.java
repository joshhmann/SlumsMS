package soloMapling.crafting;

import client.inventory.Inventory;
import client.inventory.InventoryType;
import scripting.AbstractPlayerInteraction;
import java.util.Set;

/** Nimble's one-for-one class-core exchange. Never grants more cores than it consumes. */
public final class WeaponCoreExchange {
    public static final Set<Integer> CORE_IDS=Set.of(4036544,4036545,4036546,4036547,4036548);
    public static final String PROPERTY = "slums.forge.coreExchange";
    public static boolean isEnabled() { return Boolean.getBoolean(PROPERTY); }
    private WeaponCoreExchange() { }
    public record Result(boolean made,String text) { }
    public interface Hands {
        int count(int itemId);
        boolean canHold(int itemId);
        void take(int itemId);
        void give(int itemId);
    }
    public static Result exchange(Hands hands,int source,int target) {
        if(!CORE_IDS.contains(source)||!CORE_IDS.contains(target)||source==target) {
            return new Result(false,"Choose two different class weapon cores.");
        }
        if(hands.count(source)<1) return new Result(false,"You no longer hold the core you selected.");
        if(!hands.canHold(target)) return new Result(false,"Make room for one item in your Etc inventory first.");
        hands.take(source);
        hands.give(target);
        return new Result(true,"Exchanged one #t"+source+"# for one #t"+target+"#. No meso fee.");
    }
    /** Keep checks and both item moves under the same inventory lock. */
    public static Result exchange(AbstractPlayerInteraction cm,int source,int target) {
        if (!isEnabled()) return new Result(false,"Weapon core exchange is currently unavailable.");
        Inventory inventory=cm.getPlayer().getInventory(InventoryType.ETC);
        inventory.lockInventory();
        try {
            return exchange(new Hands() {
                public int count(int id) { return inventory.countById(id); }
                public boolean canHold(int id) { return cm.canHold(id,1); }
                public void take(int id) { cm.gainItem(id,(short)-1); }
                public void give(int id) { cm.gainItem(id,(short)1); }
            },source,target);
        } finally {
            inventory.unlockInventory();
        }
    }
}
