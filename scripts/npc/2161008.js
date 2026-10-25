/*  Von Leon (boss spawner)
    Audience room (211070100)
    Talkable NPC that spawns the boss.
 */

    var status;
    const ExpeditionType = Java.type('server.expeditions.ExpeditionType');
    var exped = ExpeditionType.VONLEON;

    function start() {
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
                    if (mode == 1)
                            status++;
                    else
                            status--;
    
                    if(status == 0) {
                        expedition = cm.getExpedition(exped);
                        if (!isLeader()) {   // 1.5.2: no expedition threw here, and a non-leader was left in an open talk
                            cm.sendOk("Only the leader of the expedition can do this.");
                            cm.dispose();
                            return;
                        }
                        if (expedition.isLeader(cm.getPlayer())) {
                            cm.sendYesNo("Are you the warriors who came to defeat me? Or are you from the Anti Black Mage Alliance? It doesn't matter who you are ... There's no need for chitchatting, if we are sure about eachother's purpose... Bring it on, you fools!");
                        }
                    } else if (status == 1) {
                        if (!isLeader()) {   // a forged "next" from a non-leader must not spawn the boss
                            cm.dispose();
                            return;
                        }
                            cm.getMap().spawnMonsterOnGroundBelow(8840000, 49, -181);
                            cm.getMap().destroyNPC(2161008);
                            cm.getMap().killMonster(8840010);
                            cm.dispose();
                    }
    
            }
    }

function isLeader() {
    var expedition = cm.getExpedition(exped);
    return expedition != null && expedition.isLeader(cm.getPlayer());
}
