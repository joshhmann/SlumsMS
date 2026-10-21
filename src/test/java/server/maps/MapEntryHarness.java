package server.maps;

import client.BotClient;
import client.Character;
import client.Client;
import config.YamlConfig;
import net.packet.Packet;
import net.server.world.World;
import org.mockito.MockedStatic;
import server.TimerManager;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * A real MapleMap with bots, drops and reactors, and a real-player entrant whose client records every packet: the
 * map-entry perf harness (MapEntryPerfHarnessTest) and the placement tests share it. WZ-less: no footholds, no
 * mobs (mobs are never deferred; their cost is reported from the code, not measured here).
 */
final class MapEntryHarness implements AutoCloseable {

    static void initItemProvider() throws Exception {
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection).thenReturn(mock(java.sql.Connection.class, RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(any(provider.wz.WZFiles.class)))
                    .thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    /** Runs the placement's deferred chunks when the test says so. */
    static final class ManualScheduler implements MapEntryPlacement.Scheduler {
        final List<Runnable> queue = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();

        @Override
        public void schedule(Runnable task, long delayMs) {
            queue.add(task);
            delays.add(delayMs);
        }

        /** Runs one pending step; false when none was pending. */
        boolean step() {
            if (queue.isEmpty()) {
                return false;
            }
            queue.remove(0).run();
            return true;
        }

        int drain() {
            int steps = 0;
            while (step()) {
                steps++;
            }
            return steps;
        }
    }

    /** A packet the entrant was sent, and whether it came during the entry (true) or from a deferred step. */
    record Sent(byte[] bytes, boolean sync) {
        int opcode() {
            return (bytes[0] & 0xFF) | ((bytes[1] & 0xFF) << 8);
        }

        int intAt(int offset) {
            return (bytes[offset] & 0xFF) | (bytes[offset + 1] & 0xFF) << 8 | (bytes[offset + 2] & 0xFF) << 16
                    | (bytes[offset + 3] & 0xFF) << 24;
        }
    }

    final MapleMap map;
    final ManualScheduler scheduler = new ManualScheduler();
    final List<Character> bots = new ArrayList<>();
    final List<Sent> sent = new ArrayList<>();
    private final MockedStatic<TimerManager> timers;
    private final MapEntryPlacement.Scheduler previousScheduler = MapEntryPlacement.scheduler;
    private final boolean previousTrickle = YamlConfig.config.server.MAP_ENTRY_TRICKLE;
    private final boolean previousMaxRange = YamlConfig.config.server.USE_MAXRANGE;
    private boolean inEntry;
    private int nextId = 1_000_000;

    MapEntryHarness(int mapId) {
        timers = mockStatic(TimerManager.class);
        timers.when(TimerManager::getInstance).thenReturn(mock(TimerManager.class));
        YamlConfig.config.server.ITEM_MONITOR_TIME = 300_000;
        YamlConfig.config.server.ITEM_EXPIRE_CHECK = 10_000;
        YamlConfig.config.server.MOB_STATUS_AGGRO_INTERVAL = 1_000;
        YamlConfig.config.server.USE_MAXRANGE = true; // as on live
        MapEntryPlacement.scheduler = scheduler;
        map = spy(new MapleMap(mapId, 0, 1, 0, 1f));
        doReturn(mock(World.class)).when(map).getWorldServer();
        map.setOnUserEnter("");
        map.setOnFirstUserEnter("");
    }

    void trickle(boolean on) {
        YamlConfig.config.server.MAP_ENTRY_TRICKLE = on;
    }

    Character bot(int x) {
        Character b = Character.getDefault(new BotClient(0, 0));
        b.setID(nextId++);
        b.setName("Bot" + b.getId());
        b.setPosition(new Point(x, 0));
        b.setMap(map);
        map.addPlayer(b);
        bots.add(b);
        return b;
    }

    /** n bots spread evenly over [-width/2, width/2]. */
    void bots(int n, int width) {
        for (int i = 0; i < n; i++) {
            bot(n == 1 ? 0 : -width / 2 + (int) ((long) width * i / (n - 1)));
        }
    }

    MapItem drop(int x) {
        Character owner = bots.isEmpty() ? bot(0) : bots.get(0);
        MapItem item = new MapItem(100, new Point(x, 0), owner, owner, owner.getClient(), (byte) 2, false);
        map.addMapObject(item);
        return item;
    }

    void drops(int n, int width) {
        for (int i = 0; i < n; i++) {
            drop(n == 1 ? 0 : -width / 2 + (int) ((long) width * i / (n - 1)));
        }
    }

    void reactors(int n) {
        for (int i = 0; i < n; i++) {
            Reactor r = new Reactor(new ReactorStats(), 2202004);
            r.setPosition(new Point(366, 154));
            r.setMap(map);
            map.addMapObject(r);
        }
    }

    /** A real player (its own client records what it is sent), not yet on the map. */
    Character player(int x) {
        Client c = mock(Client.class);
        Character me = Character.getDefault(c);
        when(c.getPlayer()).thenReturn(me);
        doAnswer(inv -> {
            sent.add(new Sent(((Packet) inv.getArgument(0)).getBytes(), inEntry));
            return null;
        }).when(c).sendPacket(any());
        me.setID(5);
        me.setName("Entrant");
        me.setPosition(new Point(x, 0));
        return me;
    }

    /** The entrant enters; the server time of MapleMap.addPlayer in ns. */
    long enter(Character me) {
        me.setMap(map);
        inEntry = true;
        long t0 = System.nanoTime();
        try {
            map.addPlayer(me);
            return System.nanoTime() - t0;
        } finally {
            inEntry = false;
        }
    }

    void leave(Character me) {
        map.removePlayer(me);
    }

    List<Sent> sync() {
        return sent.stream().filter(Sent::sync).toList();
    }

    List<Sent> later() {
        return sent.stream().filter(s -> !s.sync()).toList();
    }

    static long bytes(List<Sent> list) {
        return list.stream().mapToLong(s -> s.bytes().length + 4L).sum();
    }

    @Override
    public void close() {
        MapEntryPlacement.scheduler = previousScheduler;
        YamlConfig.config.server.MAP_ENTRY_TRICKLE = previousTrickle;
        YamlConfig.config.server.USE_MAXRANGE = previousMaxRange;
        timers.close();
    }
}
