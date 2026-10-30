/*
This file is part of the OdinMS Maple Story Server
Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
Matthias Butz <matze@odinms.de>
Jan Christian Meyer <vimes@odinms.de>

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

import client.Character;
import client.Client;
import constants.game.GameConstants;
import constants.id.MapId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.portal.PortalScriptManager;
import tools.PacketCreator;

import java.awt.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class GenericPortal implements Portal {
    private static final Logger log = LoggerFactory.getLogger(GenericPortal.class);
    private String name;
    private String target;
    private Point position;
    private int targetmap;
    private final int type;
    private boolean status = true;
    private int id;
    private String scriptName;
    private boolean portalState;
    private Lock scriptLock = null;

    public GenericPortal(int type) {
        this.type = type;
    }

    @Override
    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Point getPosition() {
        return position;
    }

    @Override
    public String getTarget() {
        return target;
    }

    @Override
    public void setPortalStatus(boolean newStatus) {
        this.status = newStatus;
    }

    @Override
    public boolean getPortalStatus() {
        return status;
    }

    @Override
    public int getTargetMapId() {
        return targetmap;
    }

    @Override
    public int getType() {
        return type;
    }

    @Override
    public String getScriptName() {
        return scriptName;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setPosition(Point position) {
        this.position = position;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public void setTargetMapId(int targetmapid) {
        this.targetmap = targetmapid;
    }

    @Override
    public void setScriptName(String scriptName) {
        this.scriptName = scriptName;

        if (scriptName != null) {
            if (scriptLock == null) {
                scriptLock = new ReentrantLock(true);
            }
        } else {
            scriptLock = null;
        }
    }

    @Override
    public void enterPortal(Client c) {
        boolean changed = false;

        Character chr = c.getPlayer();
        int fromMapId = chr.getMapId();

        // GM Level 2+ will teleport skipping scripts except if there is no destination and preventing double entry
        if (chr.gmLevel() >= 2) {
            if (getTargetMapId() != MapId.NONE) {
                changed = attemptTeleportForUnscriptedPortal(c, chr, changed);
            } else if (getScriptName() != null) {
                try {
                    scriptLock.lock();
                    try {
                        changed = runScript(c, chr, fromMapId);
                    } finally {
                        scriptLock.unlock();
                    }
                } catch (NullPointerException npe) {
                    npe.printStackTrace();
                }
            }
        } else if (getScriptName() != null) {
            try {
                scriptLock.lock();
                try {
                    changed = runScript(c, chr, fromMapId);
                } finally {
                    scriptLock.unlock();
                }
            } catch (NullPointerException npe) {
                npe.printStackTrace();
            }
        } else if (getTargetMapId() != MapId.NONE) {
            changed = attemptTeleportForUnscriptedPortal(c, chr, changed);
        }

        if (!changed) {
            c.sendPacket(PacketCreator.enableActions());
        } else if (soloMapling.server.MapleRootSolo.isEnabled() && chr.getMapId() != fromMapId
                && soloMapling.ArtificialPlayer.BotHelpers.isVisibleRealPlayer(chr)) {
            // SlumsMS: which portal real players take between two maps (NavPlayerLearning), for the world graph.
            soloMapling.server.MapleRootSolo.runHook("portal learning", () -> soloMapling.ArtificialPlayer.GCMoveSystem
                    .NavPlayerLearning.onPortal(chr, fromMapId, getName(), chr.getMapId()));
        }
    }

    /** Runs the script and logs what happened (1.6.1: a missing or refusing script used to leave no trace). */
    private boolean runScript(Client c, Character chr, int fromMapId) {
        PortalScriptManager.Outcome outcome = PortalScriptManager.getInstance().runPortalScript(this, c);
        String text = outcome == PortalScriptManager.Outcome.NO_SCRIPT
                ? outcome.text + " portal/" + getScriptName() + ".js" : outcome.text;
        String line = scriptLogLine(getScriptName(), chr.getName(), fromMapId, text);
        if (firstInAWhile(line, System.currentTimeMillis())) {
            log.info(line);
        }
        return outcome == PortalScriptManager.Outcome.RAN;
    }

    private static final long REPEAT_QUIET_MS = 60_000;
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> lastLogged = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 1.6.2 b089: a range-trigger portal (Marketplace pcs00) fires every few seconds while a character stands near it,
     * so the same line was logged over and over. The same character, portal, map and outcome is logged once a minute.
     */
    static boolean firstInAWhile(String line, long now) {
        if (lastLogged.size() > 10_000) {
            lastLogged.clear();
        }
        Long before = lastLogged.get(line);
        if (before != null && now - before < REPEAT_QUIET_MS) {
            return false;
        }
        lastLogged.put(line, now);
        return true;
    }

    /** "portal script <name> by <chr> on <map>: <ran|refused: reason>" (the line ops grep for). */
    public static String scriptLogLine(String script, String chr, int mapId, String outcome) {
        return "portal script " + script + " by " + chr + " on " + mapId + ": " + outcome;
    }

    private boolean attemptTeleportForUnscriptedPortal(Client c, Character chr, boolean changed) {
        String gate = AreaGates.denyReason(chr, getTargetMapId()); // ticket 81 round 3: level / rebirth areas
        if (gate != null) {
            chr.dropMessage(5, gate);
            return changed;
        }
        if (!(chr.getChalkboard() != null && GameConstants.isFreeMarketRoom(getTargetMapId()))) {
            MapleMap to = chr.getEventInstance() == null ? c.getChannelServer().getMapFactory().getMap(getTargetMapId()) : chr.getEventInstance().getMapInstance(getTargetMapId());
            Portal pto = to.getPortal(getTarget());
            if (pto == null) {// fallback for missing portals - no real life case anymore - interesting for not implemented areas
                pto = to.getPortal(0);
            }
            chr.changeMap(to, pto); //late resolving makes this harder but prevents us from loading the whole world at once
            changed = true;
        } else {
            chr.dropMessage(5, "You cannot enter this map with the chalkboard opened.");
        }
        return changed;
    }

    @Override
    public void setPortalState(boolean state) {
        this.portalState = state;
    }

    @Override
    public boolean getPortalState() {
        return portalState;
    }
}
