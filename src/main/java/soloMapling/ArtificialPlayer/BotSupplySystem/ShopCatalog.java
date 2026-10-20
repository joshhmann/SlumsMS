package soloMapling.ArtificialPlayer.BotSupplySystem;

import client.Character;
import constants.id.MapId;
import server.Shop;
import server.ShopFactory;
import server.ShopItem;
import server.life.NPC;
import server.maps.MapObject;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import server.maps.Portal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Point;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Real bots ticket 09: the NPC shops a bot can walk to from its town, read from the server's own shop data.
 *
 * A town's shops are the NPCs standing on the town map and on the maps its portals lead to that belong to the same
 * town (the same map id block: Henesys 100000000 and its stores 1000001xx, two portals deep so a store behind a market
 * map counts). An NPC is a shop when the server would open one for a player who talks to it
 * (ShopFactory.getShopForNPC, the shops/shopitems tables), and its listing is that Shop's items in the order the
 * client is sent them, with the server's own prices: a rechargeable item listed at price 0 is the recharge entry the
 * server gives every shop for every star and bullet.
 */
public final class ShopCatalog {

    private ShopCatalog() {}

    private static final Logger log = LoggerFactory.getLogger(ShopCatalog.class);

    private static final int TOWN_DEPTH = 2;   // portals from the town map a store may sit behind

    /* One item of a shop as the server lists it: price in mesos (0 = not sold for mesos), pitch in Perfect Pitches. */
    public record Listing(int slot, int itemId, int price, int pitch) {
        boolean soldForMesos() {
            return price > 0;
        }
    }

    /* A shop NPC where it stands, with its listing. */
    public record Site(int mapId, int npcId, int shopId, Point npcPos, List<Listing> items) {
        public Listing listing(int itemId) {
            for (Listing l : items) {
                if (l.itemId() == itemId) {
                    return l;
                }
            }
            return null;
        }

        public boolean sells(int itemId) {
            Listing l = listing(itemId);
            return l != null && l.soldForMesos();
        }
    }

    interface Source {
        /* The shop NPCs on this map. */
        List<Site> sitesOnMap(Character bot, int mapId);

        /* The maps the town's portals lead to (no scripted warps). */
        List<Integer> neighbours(Character bot, int mapId);
    }

    static final Source SERVER = new Source() {
        @Override
        public List<Site> sitesOnMap(Character bot, int mapId) {
            MapleMap map = mapOf(bot, mapId);
            if (map == null) {
                return List.of();
            }
            List<Site> out = new ArrayList<>();
            for (MapObject obj : map.getMapObjects()) {
                if (obj.getType() != MapObjectType.NPC) {
                    continue;
                }
                NPC npc = (NPC) obj;
                Shop shop;
                // ShopFactory caches in plain HashMaps; bots ask from many threads, so they at least never race
                // each other there.
                synchronized (ShopFactory.class) {
                    shop = ShopFactory.getInstance().getShopForNPC(npc.getId());
                }
                if (shop == null) {
                    continue;
                }
                List<Listing> items = new ArrayList<>();
                List<ShopItem> shopItems = shop.getItems();
                for (int slot = 0; slot < shopItems.size(); slot++) {
                    ShopItem si = shopItems.get(slot);
                    items.add(new Listing(slot, si.getItemId(), si.getPrice(), si.getPitch()));
                }
                out.add(new Site(mapId, npc.getId(), shop.getId(), new Point(npc.getPosition()), List.copyOf(items)));
            }
            return out;
        }

        @Override
        public List<Integer> neighbours(Character bot, int mapId) {
            MapleMap map = mapOf(bot, mapId);
            if (map == null) {
                return List.of();
            }
            List<Integer> out = new ArrayList<>();
            for (Portal p : map.getPortals()) {
                int target = p.getTargetMapId();
                if (target != MapId.NONE && target != mapId && !out.contains(target)) {
                    out.add(target);
                }
            }
            return out;
        }

        private MapleMap mapOf(Character bot, int mapId) {
            try {
                return bot.getClient().getChannelServer().getMapFactory().getMap(mapId);
            } catch (RuntimeException unavailable) {
                return null;
            }
        }
    };

    private static volatile Source source = SERVER;

    // Shop data and NPC placement do not change while the server runs (a GM's !reloadshops aside), so each map and
    // town is looked up once.
    private static final Map<Integer, List<Site>> byMap = new ConcurrentHashMap<>();
    private static final Map<Integer, List<Site>> byTown = new ConcurrentHashMap<>();

    /* The shop NPCs a bot whose home is this town can walk to, the town map's own first. */
    public static List<Site> town(Character bot, int townMapId) {
        if (townMapId <= 0) {
            return List.of();
        }
        List<Site> cached = byTown.get(townMapId);
        if (cached != null) {
            return cached;
        }
        List<Site> sites = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{townMapId, 0});
        seen.add(townMapId);
        while (!queue.isEmpty()) {
            int[] at = queue.poll();
            sites.addAll(onMap(bot, at[0]));
            if (at[1] >= TOWN_DEPTH) {
                continue;
            }
            for (int next : source.neighbours(bot, at[0])) {
                if (sameTown(townMapId, next) && seen.add(next)) {
                    queue.add(new int[]{next, at[1] + 1});
                }
            }
        }
        List<Site> result = List.copyOf(sites);
        byTown.put(townMapId, result);
        if (log.isDebugEnabled()) {
            log.debug("Shops of town {}: {}", townMapId, result.stream()
                    .map(s -> "npc " + s.npcId() + " on " + s.mapId()).toList());
        }
        return result;
    }

    /* The shop NPCs on one map. */
    public static List<Site> onMap(Character bot, int mapId) {
        List<Site> cached = byMap.get(mapId);
        if (cached != null) {
            return cached;
        }
        List<Site> sites;
        try {
            sites = List.copyOf(source.sitesOnMap(bot, mapId));
        } catch (RuntimeException e) {
            log.warn("Could not read the shops on map {}", mapId, e);
            return List.of();   // not cached: tried again next time
        }
        byMap.put(mapId, sites);
        return sites;
    }

    // A town's own maps share its id down to the last three digits (Henesys 100000000, its market 100000100, its
    // stores 100000101/102); the fields around it do not.
    static boolean sameTown(int townMapId, int mapId) {
        return mapId / 1000 == townMapId / 1000;
    }

    /* Tests swap the shops in; null puts the server's back. Either way the lookups start over. */
    static void useForTest(Source test) {
        source = test != null ? test : SERVER;
        clearCache();
    }

    /* Forgets what was looked up (after a GM reloads the shops). */
    public static void clearCache() {
        byMap.clear();
        byTown.clear();
    }
}
