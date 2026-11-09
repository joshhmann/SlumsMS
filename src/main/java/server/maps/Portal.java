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

import client.Client;

import java.awt.*;

public interface Portal {
    int TELEPORT_PORTAL = 1;
    int MAP_PORTAL = 2;
    int DOOR_PORTAL = 6;
    boolean OPEN = true;
    boolean CLOSED = false;
    int getType();
    int getId();

    /**
     * b339 (1.6.8): the index the client knows this portal by, for SET_FIELD's spawn byte. The v83 client keeps the
     * type-6 town-portal points (pt 6, "tp") out of its spawn portal list, so a portal's client index is its WZ index
     * minus the tp entries before it; the server's own id is that WZ index (tp points get 0x80+). Equal to getId() on
     * maps with no tp entry before the portal, and for the tp points themselves.
     */
    default int getClientIndex() {
        return getId();
    }
    Point getPosition();
    String getName();
    String getTarget();
    String getScriptName();
    void setScriptName(String newName);
    void setPortalStatus(boolean newStatus);
    boolean getPortalStatus();
    int getTargetMapId();
    void enterPortal(Client c);
    void setPortalState(boolean state);
    boolean getPortalState();
}
