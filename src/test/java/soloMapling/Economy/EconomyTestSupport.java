package soloMapling.Economy;

import client.inventory.Item;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Shared fixtures for the economy lane's tests: an in-memory store, a manual clock, defaults from code. */
final class EconomyTestSupport {

    static final long DAY = 86_400_000L;

    final EconomyStore.Memory store = new EconomyStore.Memory();
    final AtomicLong now = new AtomicLong(100 * DAY);
    final Map<Integer, Long> statics = new HashMap<>();

    EconomyTestSupport() {
        EconomyConfig.useForTest(Map.of());
        PriceBook.useForTest(store, Runnable::run, now::get, item -> statics.getOrDefault(item.getItemId(), 0L));
        EconomyMeters.useForTest(store, Runnable::run, () -> LocalDate.ofEpochDay(now.get() / DAY));
        SaleRules.useForTest(Set.of(4000999), id -> id / 1_000_000 == 5, id -> id == 4031999);
    }

    static void reset() {
        EconomyConfig.useForTest(null);
        PriceBook.useForTest(null, null, null, null);
        EconomyMeters.useForTest(null, null, null);
        SaleRules.useForTest(null, null, null);
    }

    static Item item(int id, int qty) {
        return new Item(id, (short) 0, (short) qty);
    }
}
