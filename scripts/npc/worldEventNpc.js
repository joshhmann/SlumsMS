/*
    World event NPCs (SlumsMS ticket 21): the hidden treasure chest and the wandering traveler's one-off quest.
    NPCTalkHandler sends any NPC a running world event spawned here; the logic lives in
    soloMapling.worldevents.WorldEvents.talk, this only shows its replies.
*/
var WorldEvents = Java.type('soloMapling.worldevents.WorldEvents');
var reply = null;

function show() {
    if (reply == null) {
        cm.dispose();
        return;
    }
    if (reply.yesNo()) {
        cm.sendYesNo(reply.text());
    } else {
        cm.sendOk(reply.text());
    }
}

function start() {
    reply = WorldEvents.getInstance().talk(cm.getPlayer(), cm.getNpcObjectId(), false);
    show();
}

function action(mode, type, selection) {
    if (mode != 1 || reply == null || !reply.yesNo()) {
        cm.dispose();
        return;
    }
    reply = WorldEvents.getInstance().talk(cm.getPlayer(), cm.getNpcObjectId(), true);
    show();
}
