package soloMapling.MapVFX;

import client.Character;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;
import server.maps.MapleMap;
import server.maps.Reactor;
import server.maps.ReactorDropEntry;
import server.maps.ReactorFactory;
import tools.PacketCreator;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

import static soloMapling.ArtificialPlayer.BotLogic.isPointNear;
import static soloMapling.DebugUtilities.debugprint;
import static soloMapling.itemPool.GachaFillerSystem.getRandomMesoGachaFiller;

    /*
    reactor drops todo's
    [] fading pattern until last item (port) - basically all items disappear, only leaving 1 item. for a wheel of fortune effect
    [] invisible reactor
     */

public class CustomReactor {
    /*
        Every drop this class fabricates is a prop: it stands for no value taken out of real reactor content,
        so it goes out with conjured=true and a bot loot removes it without crediting. What a reactor drops,
        where, how many, and with what delay is unchanged.
     */
    private static final int dropSprayLength = 2; // length from center of item drop spray size (Gachapon drop vfx effect)
    private static final int dropSprayFullWidth = dropSprayLength * 2; // full width of item drop spray
    private static final int itemDropOffset = 30; // space between each item drop

    public static void getAllReactorsData(Character fakechar) {
        List<Reactor> reactors = fakechar.getMap().getAllReactors();
        for (Reactor reactor : reactors) {
            if (reactor.getState() < 4 && reactor.isAlive()) {
                debugprint("Reactor: ", reactor.getObjectId(), reactor.getId(), reactor.getPosition(), reactor.isAlive(), reactor.isActive(), reactor.getState());
            }
        }
    }

    public static int getNearestReactor(Character fakechar) {
        List<Reactor> reactors = fakechar.getMap().getAllReactors();
        for (Reactor reactor : reactors) {
            if (reactor.getState() < 4 && reactor.isAlive()) {
                if (isPointNear(reactor.getPosition(), fakechar.getPosition(), 25)) {
//                    debugprint("Reactor near: ", reactor.getObjectId(), reactor.getId(), reactor.getPosition(), fakechar.getPosition());
                    return reactor.getObjectId();
                }
            }
        }
        return 0;
    }

    public static Reactor spawnReactor(Character fakechar) {
        return spawnReactor(fakechar.getPosition(), fakechar.getMap());
    }

    public static Reactor spawnReactor(Point position, MapleMap map) {
        int reactorID = 2202004;
        Reactor reactor = new Reactor(ReactorFactory.getReactorS(reactorID), reactorID);
        reactor.setPosition(position);
        reactor.resetReactorActions(0);
        map.spawnReactor(reactor);
        return reactor;
    }

    public static void deleteReactor(MapleMap map, int oid) {
        map.destroyReactor(oid);
    }

    /*
        Server-side forced drop of a single item at a reactor's position, owned by `owner`.
        Bots have no client, so when they break a reactor via spliced hitReactor packets the
        normal drop pipeline never fires (no owner attached, no DropEntry resolution). This
        is the fallback: spawn one item directly via the same MapleMap#dropFromReactor path
        the real drop code uses, attributing ownership to the bot so it can pick it up.
     */
    public static void dropItemAtReactor(MapleMap map, int oid, int itemId, Character owner) {
        if (map == null || owner == null) return;
        Reactor reactor = map.getReactorByOid(oid);
        if (reactor == null) return;

        Item drop;
        if (ItemConstants.getInventoryType(itemId) != InventoryType.EQUIP) {
            drop = new Item(itemId, (short) 0, (short) 1);
        } else {
            drop = (Equip) ItemInformationProvider.getInstance().getEquipById(itemId);
        }
        Point dropPos = new Point(reactor.getPosition());
        map.dropFromReactor(owner, reactor, drop, dropPos, (short) 0, (short) 0, true);
    }

    /*
        Allows you to "Hit" a reactor via server code manually. After 4, it will break
        Doesn't "Spray" Items, purely animation based.
        Ludi PQ Blue Box: 2202004
         */
    public static void hitReactor(MapleMap map, int oid) {
        Reactor reactor = map.getReactorByOid(oid);
        byte state = reactor.getState();
        state++;
        reactor.setState(state);
        map.broadcastMessage(PacketCreator.triggerReactor(reactor, (short) 0));
    }

    public static void threeHitReactor(MapleMap map, int oid) {
        hitReactor(map, oid);
        hitReactor(map, oid);
        hitReactor(map, oid);
    }

    public static List<ReactorDropEntry> createReactorDropList(List<Integer> itemIds) {
        List<ReactorDropEntry> items = new ArrayList<>(List.of());
        for (Integer id : itemIds) {
            items.add(new ReactorDropEntry(id, 15, 0));
        }
        return items;
    }

    /*
        Spray Animation - Looks like a fountain dropping items
         */
    public static void sprayFromReactor(MapleMap map, int oid, List<ReactorDropEntry> drops, Character owner) {
        dropFromReactorCustom(map, oid, drops, owner, true);
    }

    public static void dropFromReactor(MapleMap map, int oid, List<ReactorDropEntry> drops, Character owner) {
        dropFromReactorCustom(map, oid, drops, owner, false);
    }

    private static void notDelayedReactorDrops(Character owner, List<ReactorDropEntry> drops, Reactor reactor, Point dropPos, int posX) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();

        byte p = 1;
        for (ReactorDropEntry d : drops) {
            dropPos.x = posX + ((p % 2 == 0) ? (25 * ((p + 1) / 2)) : -(25 * (p / 2)));
            p++;

            if (d.itemId == 0) {
                int mesoDrop = (1000 * owner.getWorldServer().getMesoRate());
                reactor.getMap().spawnMesoDrop(mesoDrop, reactor.getMap().calcDropPos(dropPos,
                        reactor.getPosition()), reactor, owner, false, (byte) 2, (short) 0, true);
            } else {
                Item drop;

                if (ItemConstants.getInventoryType(d.itemId) != InventoryType.EQUIP) {
                    drop = new Item(d.itemId, (short) 0, (short) 1);
                } else {
                    drop = (Equip) ii.getEquipById(d.itemId);
                }
                reactor.getMap().dropFromReactor(owner, reactor, drop, dropPos, (short) d.questid, (short) 0, true);
            }
        }
    }

    private static Point adjustCenterPositionXAxis(Point center, int currIndex, int initialIncrement, int subsequentIncrement, int offset) {
        // initialIncrement = How many units it will go left
        // SubsequentIncrement = How many units it will go right (Usually 2x initial Increment for an even "spread"
        // Offset = how much space between each item
        if (currIndex < initialIncrement) {
            center.x += offset;
        } else {
            int adjustedIndex = currIndex - initialIncrement;
            int cycle = (adjustedIndex / subsequentIncrement) % 2;

            if (cycle == 0) { // Even cycle, increment by 30
                if (adjustedIndex % subsequentIncrement < subsequentIncrement) {
                    center.x -= offset;
                }
            } else { // Odd cycle, decrement by 30
                if (adjustedIndex % subsequentIncrement < subsequentIncrement) {
                    center.x += offset;
                }
            }
        }
        return center;
    }

    // Uses back-and-forth spray pattern
    private static void delayedReactorDrops(Character owner, List<ReactorDropEntry> drops, Reactor reactor, Point dropPos) {
        final int worldMesoRate = owner.getWorldServer().getMesoRate();

        Point center2 = dropPos;
        short delay = 0;
        int dropIndex = 0;
        for (ReactorDropEntry d : drops) {
            center2 = adjustCenterPositionXAxis(center2, dropIndex, dropSprayLength, dropSprayFullWidth, itemDropOffset);
            if (d.itemId == 0) {
                int mesoDrop = getRandomMesoGachaFiller();
                reactor.getMap().spawnMesoDrop(mesoDrop, reactor.getMap().calcDropPos(center2, reactor.getPosition()), reactor, owner,
                        false, (byte) 2, delay, true);
            } else {
                final Item drop;
                if (ItemConstants.getInventoryType(d.itemId) != InventoryType.EQUIP) {
                    drop = new Item(d.itemId, (short) 0, (short) 1);
                } else {
                    ItemInformationProvider ii = ItemInformationProvider.getInstance();
                    drop = (Equip) ii.getEquipById(d.itemId);
                }
                reactor.getMap().dropFromReactor(owner, reactor, drop, center2, (short) d.questid, delay, true);
            }
            delay += 200;
            dropIndex++;
        }
    }

    // Original single line spray drops
    private static void delayedReactorDropsStandard(Character owner, List<ReactorDropEntry> drops, Reactor reactor, Point dropPos, int posX) {
        final int worldMesoRate = owner.getWorldServer().getMesoRate();

        dropPos.x -= (12 * drops.size());
        short delay = 0;
        for (ReactorDropEntry d : drops) {
            if (d.itemId == 0) {
                int mesoDrop = 1000 * worldMesoRate;
                reactor.getMap().spawnMesoDrop(mesoDrop, reactor.getMap().calcDropPos(dropPos, reactor.getPosition()), reactor, owner,
                        false, (byte) 2, delay, true);
            } else {
                final Item drop;
                if (ItemConstants.getInventoryType(d.itemId) != InventoryType.EQUIP) {
                    drop = new Item(d.itemId, (short) 0, (short) 1);
                } else {
                    ItemInformationProvider ii = ItemInformationProvider.getInstance();
                    drop = (Equip) ii.getEquipById(d.itemId);
                }
                reactor.getMap().dropFromReactor(owner, reactor, drop, dropPos, (short) d.questid, delay, true);
            }

            dropPos.x += 25;
            delay += 200;
        }
    }

    private static void dropFromReactorCustom(MapleMap map, int oid, List<ReactorDropEntry> drops, Character owner, boolean delayed) {
        Reactor reactor = map.getReactorByOid(oid);
        int posX = (int) reactor.getPosition().getX();
        int posY = (int) reactor.getPosition().getY();
        boolean meso = true;
        int mesoChance = 10;
        final int minMeso = 100;
        final int maxMeso = 500;
        int minItems = 1;

        if (owner == null) {
            return;
        }

        if (drops.size() % 2 == 0) {
            posX -= 12;
        }
        final Point dropPos = new Point(posX, posY);

        if (!delayed) {
            notDelayedReactorDrops(owner, drops, reactor, dropPos, posX);
        } else {
            delayedReactorDrops(owner, drops, reactor, dropPos);
        }
        hitReactor(map, oid);
    }

    /*
        1.4.2 (perf): the box a pop spawns is taken off the map once its spray has landed. Before, every pop left
        its broken box in the map's objects for good (about 385 an hour with three gacha bots on Henesys Market),
        and each one went to every client entering the map: 9,000 extra packets per entry after a day of uptime.
        The pop also works on the box it spawned instead of whichever unbroken box stands nearest.
     */
    static final long RETIRE_AFTER_LAST_DROP_MS = 1500;

    public static void gachaPop(Character fakechar, List<ReactorDropEntry> drops) {
        MapleMap map = fakechar.getMap();
        Reactor box = spawnReactor(fakechar);
        int oid = box.getObjectId();
        threeHitReactor(map, oid);
        sprayFromReactor(map, oid, drops, fakechar);
        retireLater(map, box, retireDelayMs(drops.size()));
        server.TimerManager.getInstance().schedule(() -> sweepShowDrops(map, box), SHOW_SWEEP_MS);
    }

    /*
        1.5.0: a show pop's prize and filler never enter the economy. They are conjured (bots that loot them are
        credited nothing), and whatever is still on the floor SHOW_SWEEP_MS after the pop vanishes, before its owner
        protection (15 s) would let a player pick it up.
     */
    static final long SHOW_SWEEP_MS = 12_000;

    /** Removes every conjured drop the box sprayed that is still on the map; how many. */
    static int sweepShowDrops(MapleMap map, Reactor box) {
        if (map == null || box == null) {
            return 0;
        }
        int removed = 0;
        for (server.maps.MapObject o : map.getMapObjectsInRange(box.getPosition(), Double.POSITIVE_INFINITY,
                java.util.List.of(server.maps.MapObjectType.ITEM))) {
            if (o instanceof server.maps.MapItem item && isShowDrop(item, box) && map.makeDisappearItemFromMap(item)) {
                removed++;
            }
        }
        return removed;
    }

    static boolean isShowDrop(server.maps.MapItem item, Reactor box) {
        return item.isConjured() && item.getDropper() == box;
    }

    /* The spray drops one item every 200 ms (delayedReactorDrops); the box goes after the last one has landed. */
    static long retireDelayMs(int drops) {
        return Math.max(0, drops - 1) * 200L + RETIRE_AFTER_LAST_DROP_MS;
    }

    static void retireLater(MapleMap map, Reactor box, long delayMs) {
        server.TimerManager.getInstance().schedule(() -> retire(map, box), delayMs);
    }

    /* Takes a box this class spawned off the map: clients drop it, entering players no longer get it. */
    public static void retire(MapleMap map, Reactor box) {
        if (map == null || box == null || map.getMapObject(box.getObjectId()) != box) {
            return;
        }
        box.setAlive(false);
        map.broadcastMessage(PacketCreator.destroyReactor(box));
        map.removeMapObject(box);
    }
}
