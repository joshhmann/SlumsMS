/*
    This file is part of the HeavenMS MapleStory Server
    Copyleft (L) 2016 - 2019 RonanLana

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package server.maps;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scripting.event.EventInstanceManager;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class MapManager {
    private static final Logger log = LogManager.getLogger(MapManager.class);

    private final int channel;
    private final int world;
    private EventInstanceManager event;

    private final Map<Integer, MapleMap> maps = new HashMap<>();

    private final Lock mapsRLock;
    private final Lock mapsWLock;

    public MapManager(EventInstanceManager eim, int world, int channel) {
        this.world = world;
        this.channel = channel;
        this.event = eim;

        ReadWriteLock readWriteLock = new ReentrantReadWriteLock();
        this.mapsRLock = readWriteLock.readLock();
        this.mapsWLock = readWriteLock.writeLock();
    }

    public MapleMap resetMap(int mapid) {
        mapsWLock.lock();
        try {
            maps.remove(mapid);
        } finally {
            mapsWLock.unlock();
        }

        return getMap(mapid);
    }

    private synchronized MapleMap loadMapFromWz(int mapid, boolean cache) {
        MapleMap map;

        // s-maproute: nothing is substituted behind a direct getMap. A map id the server has no image for, or one
        // the routing table blocks, is no map at all: a script that asked for it gets null instead of silently
        // acting on Henesys (spawning mobs there, reporting another id). Callers that must land somewhere ask for
        // getMapOrFallback.
        if (MapRouting.blocked(mapid)) {
            log.warn("map routing: map {} is blocked ({}), returning no map", mapid, MapRouting.kind(mapid));
            return null;
        }

        if (cache) {
            mapsRLock.lock();
            try {
                map = maps.get(mapid);
            } finally {
                mapsRLock.unlock();
            }

            if (map != null) {
                return map;
            }
        }

        map = MapFactory.loadMapFromWz(mapid, world, channel, event);

        if (cache && map != null) {   // s-maproute: a null image must not be cached, or every later getMap NPEs
            mapsWLock.lock();
            try {
                maps.put(mapid, map);
            } finally {
                mapsWLock.unlock();
            }
        }

        return map;
    }

    /** The map, or null when the server has no image for it or the routing table blocks it ({@link #getMapOrFallback}). */
    public MapleMap getMap(int mapid) {
        MapleMap map;

        mapsRLock.lock();
        try {
            map = maps.get(mapid);
        } finally {
            mapsRLock.unlock();
        }

        return (map != null) ? map : loadMapFromWz(mapid, true);
    }

    /**
     * s-maproute: where a move to {@code mapid} has to end up - the routing table's fallback for a map that is
     * blocked, else the map itself (still null when the server has no image for it). The login path and a map's own
     * return map ask for this: they cannot stay nowhere.
     */
    public MapleMap getMapOrFallback(int mapid) {
        int fallback = MapRouting.fallback(mapid);
        if (fallback < 0) {
            return getMap(mapid);
        }

        log.warn("map routing: map {} is blocked ({}), using {}", mapid, MapRouting.kind(mapid), fallback);
        return getMap(fallback);
    }

    public void setEventInstanceManager(EventInstanceManager eim) {
        this.event = eim;
    }

    public MapleMap getDisposableMap(int mapid) {
        return loadMapFromWz(mapid, false);
    }

    public boolean isMapLoaded(int mapId) {
        mapsRLock.lock();
        try {
            return maps.containsKey(mapId);
        } finally {
            mapsRLock.unlock();
        }
    }

    public Map<Integer, MapleMap> getMaps() {
        mapsRLock.lock();
        try {
            return new HashMap<>(maps);
        } finally {
            mapsRLock.unlock();
        }
    }

    public void updateMaps() {
        for (MapleMap map : getMaps().values()) {
            map.respawn();
            map.mobMpRecovery();
        }
    }

    public void dispose() {
        for (MapleMap map : getMaps().values()) {
            map.dispose();
        }

        this.event = null;
    }

}
