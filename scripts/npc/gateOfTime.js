// The Gate of Time's menu (opened by scripts/portal/270000000.10.js; NPC 2140000 is the Temple of Time's keeper).
// 1.6.1 b040: the Future, or lower Grandis (Pantheon), both behind server.maps.AreaGates: level 120 (or 2 rebirths
// for the Future, 3 for Grandis). Owner 2026-10-29: Leafre of the Past (Crack in Time, the Future's lv 124 maps) is the
// third choice, behind the same gate. Bots take the Grandis and Leafre of the Past sides through BotTransit.yaml's
// "Gate of Time" rows.
const AreaGates = Java.type('server.maps.AreaGates');
var destinations = [
    [271000000, "The Future (Gate to the Future)"],
    [400000000, "Grandis (Pantheon)"],
    [272000000, "Leafre of the Past (Crack in Time)"]
];
var status = -1;

function start() {
    status = -1;
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode !== 1) {
        cm.dispose();
        return;
    }
    status++;
    if (status === 0) {
        var menu = "The Gate of Time shimmers. Where should it take you?#b";
        for (var i = 0; i < destinations.length; i++) {
            menu += "\r\n#L" + i + "#" + destinations[i][1] + "#l";
        }
        cm.sendSimple(menu);
    } else if (status === 1) {
        if (selection < 0 || selection >= destinations.length) {
            cm.dispose();
            return;
        }
        var to = destinations[selection][0];
        var gate = AreaGates.denyReason(cm.getPlayer(), to);
        if (gate != null) {
            cm.sendOk("#r" + gate);
            cm.dispose();
            return;
        }
        cm.warp(to, 0);
        cm.dispose();
    } else {
        cm.dispose();
    }
}
