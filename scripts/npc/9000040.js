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
/* Dalair
	Medal NPC.

        NPC Equipment Merger:
        * @author Ronan Lana
 */

var status;
var mergeFee = 50000;
var name;
var preview;

// Owner 2026-10-26: the merge is on, but always needs level 160 (110 for Cygnus) and no Maker skill (owner 2026-10-27), whatever
// USE_STARTER_MERGE says, and any one item takes at most 3 merges. The checks live in Character
// (meetsEquipmentMergeRequirements / mergeItemsFromName) so the script cannot skip them.

function start() {
    status = -1;
    action(1, 0, 0);
}

function previewKey(list) {
    var key = "";
    for (var i = 0; i < list.size(); i++) {
        key += list.get(i) + "|";
    }
    return key;
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
            const YamlConfig = Java.type('config.YamlConfig');
            if (!YamlConfig.config.server.USE_ENABLE_CUSTOM_NPC_SCRIPT) {
                cm.sendOk("The medal ranking system is currently unavailable...");
                cm.dispose();
                return;
            }

            var selStr = "The medal ranking system is currently unavailable... Therefore, I am providing the #bEquipment Merge#k service! ";

            if (!cm.getPlayer().meetsEquipmentMergeRequirements()) {
                selStr += "However, you must be at least #rlevel 110#k (Cygnus Knight) or #rlevel 160#k (other classes) and have a fund of #r" + cm.numberWithCommas(mergeFee) + " mesos#k to use the service.";
                cm.sendOk(selStr);
                cm.dispose();
            } else if (cm.getMeso() < mergeFee) {
                selStr += "I'm sorry, but this service tax is of #r" + cm.numberWithCommas(mergeFee) + " mesos#k, which it seems you unfortunately don't have right now... Please, stop by again later.";
                cm.sendOk(selStr);
                cm.dispose();
            } else if (cm.getPlayer().getMergeableEquipped().isEmpty()) {
                selStr += "But every piece of gear you are wearing has already been merged into #r3 times#k, which is as far as I can take it.";
                cm.sendOk(selStr);
                cm.dispose();
            } else {
                selStr += "For the fee of #r" + cm.numberWithCommas(mergeFee) + "#k mesos, merge unnecessary equipments in your inventory into your currently equipped gears to get stat boosts into them, statups based on the attributes of the items used on the merge! Each piece of gear can receive at most #r3 merges#k.";
                cm.sendNext(selStr);
            }
        } else if (status == 1) {
            var selStr = "#rWARNING#b: Make sure you have your items ready to merge at the slots #rAFTER#b the item you have selected to merge.#k Any items #bunder#k the item selected will be merged thoroughly. I will show you the list before anything is destroyed.\r\n\r\nNote that equipments receiving bonuses from merge are going to become #rUntradeable#k thereon, and equipments that already received the merge bonus #rcannot be used for merge#k. Locked items are never merged.\r\n\r\n";
            cm.sendGetText(selStr);
        } else if (status == 2) {
            name = cm.getText();
            preview = cm.getPlayer().previewMergeFromName(name);

            if (preview == null) {
                cm.sendOk("There is no #b'" + name + "'#k in your #bEQUIP#k inventory!");
                cm.dispose();
            } else if (preview.isEmpty()) {
                cm.sendOk("None of the items from #b'" + name + "'#k onward can be merged.");
                cm.dispose();
            } else {
                var selStr = "These #r" + preview.size() + "#k items will be #rdestroyed#k (slot: item):#b";
                for (var i = 0; i < preview.size(); i++) {
                    selStr += "\r\n" + preview.get(i);
                }
                selStr += "#k\r\n\r\nGo ahead for #r" + cm.numberWithCommas(mergeFee) + " mesos#k?";
                cm.sendYesNo(selStr);
            }
        } else if (status == 3) {
            var now = cm.getPlayer().previewMergeFromName(name);
            if (now == null || previewKey(now) != previewKey(preview)) {
                cm.sendOk("Your inventory changed since I listed the items. Talk to me again to see the new list.");
                cm.dispose();
                return;
            }
            if (cm.getMeso() < mergeFee) {
                cm.sendOk("I'm sorry, but this service tax is of #r" + cm.numberWithCommas(mergeFee) + " mesos#k.");
                cm.dispose();
                return;
            }

            const Character = Java.type('client.Character');
            var result = cm.getPlayer().mergeItemsFromName(name);
            if (result == Character.MERGE_OK) {
                cm.gainMeso(-mergeFee);
                cm.sendOk("Merging complete! Thanks for using the service and enjoy your new equipment stats.");
            } else if (result == Character.MERGE_ALL_CAPPED) {
                cm.sendOk("Every piece of gear you are wearing has already been merged into #r3 times#k. Nothing was destroyed.");
            } else if (result == Character.MERGE_NO_GAIN) {
                cm.sendOk("Those items are too weak to add anything to the gear you are wearing. Nothing was destroyed and I charged you nothing.");
            } else if (result == Character.MERGE_REQUIREMENTS) {
                cm.sendOk("You must be at least #rlevel 160#k (110 for Cygnus Knights) to use the service.");
            } else {
                cm.sendOk("There is nothing to merge from #b'" + name + "'#k in your #bEQUIP#k inventory!");
            }

            cm.dispose();
        }
    }
}
