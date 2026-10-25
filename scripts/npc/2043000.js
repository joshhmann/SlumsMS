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
/* Papulatus
	Origin of Clock Tower (922020300)
	Time Leap quest NPC.
 */

var status;

function start() {  // thanks iPunchEm for NPC visibility
    status = -1;
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode == -1) {
        cm.dispose();
    } else {
        if (mode == 0 && type > 0) {
            cm.dispose();
            return;
        }
        if (mode == 1) {
            status++;
        } else {
            status--;
        }

        if (status == 0) {
            cm.sendNext("You don't belong to this world... Return now.");
        } else if (status == 1) {
            if (cm.isQuestStarted(6363)) {
                // SlumsMS 1.5.2: the server's Quest.wz has quest 6363 "A New Revelation" (Buccaneer Time Leap) wait for
                // record 6364 = "2" before Rolonay accepts the hand-in, and nothing set it (stock Cosmic's data has no
                // such check). Being sent back to the present is the step that record stands for.
                cm.setQuestProgress(6363, 6364, "2");
            }
            cm.warp(220080000);
            cm.dispose();
        }
    }
}