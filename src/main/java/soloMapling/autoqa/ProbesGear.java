package soloMapling.autoqa;

import client.inventory.Inventory;
import client.inventory.InventoryType;
import scripting.AbstractPlayerInteraction;
import soloMapling.crafting.WeaponCoreExchange;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** t-ng-8: exercise the real exchange adapter on a synthetic player, restoring every staged core. */
final class ProbesGear {
    private ProbesGear() { }

    static List<String> probe(String name, SyntheticPlayer player) {
        if ("normalGearBridge".equals(name)) {
            if (!soloMapling.crafting.NormalGearBridge.isEnabled())
                return List.of("SKIP needs -D" + soloMapling.crafting.NormalGearBridge.PROPERTY + "=true");
            try {
                return soloMapling.crafting.NormalGearBridge.verifyActiveDrops();
            } catch (Exception e) {
                return List.of("FAIL normal bridge guide/drop verification: " + e.getClass().getSimpleName());
            }
        }
        if (!"forgeCoreExchange".equals(name)) return null;
        if (!WeaponCoreExchange.isEnabled()) {
            return List.of("SKIP needs -D" + WeaponCoreExchange.PROPERTY + "=true");
        }
        if (player == null || player.character() == null) return List.of("SKIP needs a synthetic player");
        return exchange(player.character().getAbstractPlayerInteraction());
    }

    static List<String> exchange(AbstractPlayerInteraction cm) {
        Inventory inventory = cm.getPlayer().getInventory(InventoryType.ETC);
        inventory.lockInventory();
        Map<Integer, Integer> before = new LinkedHashMap<>();
        try {
            for (int id : WeaponCoreExchange.CORE_IDS) before.put(id, cm.getItemQuantity(id));
            int source = 4036544, target = 4036545;
            int mesos = cm.getPlayer().getMeso();
            if (!cm.canHold(source, 1) || !cm.canHold(target, 1)) {
                return List.of("SKIP make room in the synthetic player's Etc inventory");
            }
            cm.gainItem(source, (short) 1);
            if (cm.getItemQuantity(source) != before.get(source) + 1) {
                return List.of("FAIL could not stage one core");
            }
            if (!WeaponCoreExchange.exchange(cm, source, target).made()
                    || !matches(cm, before, target)) {
                return List.of("FAIL forward exchange did not preserve exactly one staged core");
            }
            if (!WeaponCoreExchange.exchange(cm, target, source).made()
                    || !matches(cm, before, source)) {
                return List.of("FAIL reverse exchange changed the core total");
            }
            if (WeaponCoreExchange.exchange(cm, source, source).made() || !matches(cm, before, source)) {
                return List.of("FAIL same-core request changed inventory");
            }
            if (cm.getPlayer().getMeso() != mesos) return List.of("FAIL exchange changed mesos");
            return List.of("PASS core exchange round trip, same-core denial, core total and mesos; staged cores restored");
        } finally {
            try {
                for (var entry : before.entrySet()) {
                    int delta = entry.getValue() - cm.getItemQuantity(entry.getKey());
                    if (delta != 0) cm.gainItem(entry.getKey(), (short) delta);
                }
                for (var entry : before.entrySet()) {
                    if (cm.getItemQuantity(entry.getKey()) != entry.getValue()) {
                        throw new IllegalStateException("core probe cleanup failed for " + entry.getKey());
                    }
                }
            } finally {
                inventory.unlockInventory();
            }
        }
    }

    private static boolean matches(AbstractPlayerInteraction cm, Map<Integer, Integer> before, int staged) {
        return before.entrySet().stream().allMatch(e -> cm.getItemQuantity(e.getKey())
                == e.getValue() + (e.getKey() == staged ? 1 : 0));
    }
}
