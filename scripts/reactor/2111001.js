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
function act() {
    if (rm.getPlayer().getEventInstance() != null) {
        rm.getPlayer().getEventInstance().setProperty("summoned", "true");
        rm.getPlayer().getEventInstance().setProperty("canEnter", "false");
    }
    rm.changeMusic("Bgm06/FinalFight");
    // The same reactor id stands on both altars, so the map decides which boss it summons: 280030001 is Chaos Zakum's
    // Altar (its own row is content/zakum-chaos.yaml, behind -Dslums.chaos.zakum). Keeping one script for both keeps the
    // normal altar's lines untouched.
    if (rm.getReactor().getMap().getId() == 280030001) {
        rm.spawnFakeMonster(8800100);
        for (i = 8800103; i < 8800111; i++) {
            rm.spawnMonster(i);
        }
        rm.createMapMonitor(280030001, "ps00");
        feedSpawn("chaoszakum", 8800100, 280030001);
        rm.mapMessage(5, "Chaos Zakum is summoned by the force of Eye of Fire.");
    } else {
        rm.spawnFakeMonster(8800000);
        for (i = 8800003; i < 8800011; i++) {
            rm.spawnMonster(i);
        }
        rm.createMapMonitor(280030000, "ps00");
        feedSpawn("zakum", 8800000, 280030000);
        rm.mapMessage(5, "Zakum is summoned by the force of Eye of Fire.");
    }
}

// 1.6.7 boss-feed-lines: the summon's own line (observe only; the rest of the run's lines are in the event script).
function feedSpawn(id, body, map) {
    try {
        var eim = rm.getPlayer().getEventInstance();
        if (eim == null) {
            return;
        }
        var leader = eim.getLeader();
        eim.feed("RUN boss " + id + " spawn: body " + body + " and 8 arms on " + map + ", party " + eim.getPlayerCount()
                + " [" + id + (leader == null ? "" : ", leader " + leader.getName()) + "]");
    } catch (e) {
    }
}
