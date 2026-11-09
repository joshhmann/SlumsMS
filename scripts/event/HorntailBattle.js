
const ExpeditionType = Java.type("server.expeditions.ExpeditionType");

var isPq = true;
var minPlayers = 6, maxPlayers = 30;
var minLevel = 100, maxLevel = 255;
var entryMap = 240060000;
var exitMap = 240050600;
var recruitMap = 240050400;
var clearMap = 240050600;

var minMapId = 240060000;
var maxMapId = 240060200;

var eventTime = 120;     // 120 minutes

const maxLobbies = 1;

// 1.6.7 boss-feed-lines: observe-only lines for the QA feed and the server log (logger BossFeed). No gameplay here.
var feedPartLo = 8810002, feedPartHi = 8810009;

function feedLine(eim, text) {
    try {   // an observer never breaks the fight
        var leader = eim.getLeader();
        eim.feed("RUN boss horntail " + text + " [horntail" + (leader == null ? "" : ", leader " + leader.getName()) + "]");
    } catch (e) {
    }
}

function feedSecs(eim) {
    var t0 = parseInt(eim.getProperty("feedT0"));
    return isNaN(t0) ? 0 : Math.floor((Date.now() - t0) / 1000);
}

function feedPartDown(mob, eim) {
    var id = mob.getId();
    if (id >= feedPartLo && id <= feedPartHi) {
        var total = feedPartHi - feedPartLo + 1;
        var down = parseInt(eim.getProperty("feedParts") || "0") + 1;
        eim.setProperty("feedParts", "" + down);
        if (down == total) {
            feedLine(eim, "parts down: all " + total + " after " + feedSecs(eim) + " s");
        }
    }
}

function init() {
    setEventRequirements();
}

function getMaxLobbies() {
    return maxLobbies;
}

function setEventRequirements() {
    var reqStr = "";

    reqStr += "\r\n    Number of players: ";
    if (maxPlayers - minPlayers >= 1) {
        reqStr += minPlayers + " ~ " + maxPlayers;
    } else {
        reqStr += minPlayers;
    }

    reqStr += "\r\n    Level range: ";
    if (maxLevel - minLevel >= 1) {
        reqStr += minLevel + " ~ " + maxLevel;
    } else {
        reqStr += minLevel;
    }

    reqStr += "\r\n    Time limit: ";
    reqStr += eventTime + " minutes";

    em.setProperty("party", reqStr);
}

function setEventExclusives(eim) {
    var itemSet = [];
    eim.setExclusiveItems(itemSet);
}

function setEventRewards(eim) {
    var itemSet, itemQty, evLevel, expStages, mesoStages;

    evLevel = 1;    //Rewards at clear PQ
    itemSet = [];
    itemQty = [];
    eim.setEventRewards(evLevel, itemSet, itemQty);

    expStages = [];    //bonus exp given on CLEAR stage signal
    eim.setEventClearStageExp(expStages);

    mesoStages = [];    //bonus meso given on CLEAR stage signal
    eim.setEventClearStageMeso(mesoStages);
}

function afterSetup(eim) {}

function setup(channel) {
    var eim = em.newInstance("Horntail" + channel);     // thanks Thora (Arufonsu) for reporting an issue with misleading event name here
    eim.setProperty("canJoin", 1);
    eim.setProperty("defeatedBoss", 0);
    eim.setProperty("defeatedHead", 0);
    eim.setProperty("feedT0", "" + Date.now());

    var level = 1;
    eim.getInstanceMap(240060000).resetPQ(level);
    eim.getInstanceMap(240060100).resetPQ(level);
    eim.getInstanceMap(240060200).resetPQ(level);

    const LifeFactory = Java.type('server.life.LifeFactory');
    const Point = Java.type('java.awt.Point');
    var map, mob;
    map = eim.getInstanceMap(240060000);
    mob = LifeFactory.getMonster(8810000);
    map.spawnMonsterOnGroundBelow(mob, new Point(960, 120));

    map = eim.getInstanceMap(240060100);
    mob = LifeFactory.getMonster(8810001);
    map.spawnMonsterOnGroundBelow(mob, new Point(-420, 120));

    eim.startEventTimer(eventTime * 60000);
    setEventRewards(eim);
    setEventExclusives(eim);

    return eim;
}

function playerEntry(eim, player) {
    eim.dropMessage(5, "[Expedition] " + player.getName() + " has entered the map.");
    var map = eim.getMapInstance(entryMap);
    player.changeMap(map, map.getPortal(0));
}

function scheduledTimeout(eim) {
    end(eim);
}

function changedMap(eim, player, mapid) {
    if (mapid < minMapId || mapid > maxMapId) {
        if (eim.isExpeditionTeamLackingNow(true, minPlayers, player)) {
            eim.unregisterPlayer(player);
            eim.dropMessage(5, "[Expedition] Either the leader has quit the expedition or there is no longer the minimum number of members required to continue it.");
            end(eim);
        } else {
            eim.dropMessage(5, "[Expedition] " + player.getName() + " has left the instance.");
            eim.unregisterPlayer(player);
        }
    }
}

function changedLeader(eim, leader) {}

function playerDead(eim, player) {}

function playerRevive(eim, player) {
    if (eim.isExpeditionTeamLackingNow(true, minPlayers, player)) {
        eim.unregisterPlayer(player);
        eim.dropMessage(5, "[Expedition] Either the leader has quit the expedition or there is no longer the minimum number of members required to continue it.");
        end(eim);
    } else {
        eim.dropMessage(5, "[Expedition] " + player.getName() + " has left the instance.");
        eim.unregisterPlayer(player);
    }
}

function playerDisconnected(eim, player) {
    if (eim.isExpeditionTeamLackingNow(true, minPlayers, player)) {
        eim.unregisterPlayer(player);
        eim.dropMessage(5, "[Expedition] Either the leader has quit the expedition or there is no longer the minimum number of members required to continue it.");
        end(eim);
    } else {
        eim.dropMessage(5, "[Expedition] " + player.getName() + " has left the instance.");
        eim.unregisterPlayer(player);
    }
}

function leftParty(eim, player) {}

function disbandParty(eim) {}

function monsterValue(eim, mobId) {
    return 1;
}

function playerUnregistered(eim, player) {}

function playerExit(eim, player) {
    eim.unregisterPlayer(player);
    player.changeMap(exitMap, 0);
}

function end(eim) {
    var party = eim.getPlayers();
    var moved = party.size();
    for (var i = 0; i < party.size(); i++) {
        playerExit(eim, party.get(i));
    }
    feedLine(eim, "cleanup: " + moved + " members moved to " + exitMap + ", instance disposed");
    eim.dispose();
}

function giveRandomEventReward(eim, player) {
    eim.giveEventReward(player);
}

function clearPQ(eim) {
    feedLine(eim, "cleared after " + feedSecs(eim) + " s: defeatedBoss=1");
    eim.stopEventTimer();
    eim.setEventCleared(ExpeditionType.HORNTAIL);
}

function isHorntailHead(mob) {
    var mobid = mob.getId();
    return (mobid == 8810000 || mobid == 8810001);
}

function isHorntail(mob) {
    var mobid = mob.getId();
    return (mobid == 8810018);
}

function monsterKilled(mob, eim) {
    feedPartDown(mob, eim);
    if (isHorntail(mob)) {
        eim.setIntProperty("defeatedBoss", 1);
        eim.showClearEffect(mob.getMap().getId());
        eim.clearPQ();

        var party = eim.getPlayers();
        var rewarded = 0, botsSkipped = 0;
        var baseExp = 107545760; // Set to Horntail's base EXP value
        var expLimit = 2147483647; // Maximum 32-bit integer value (~2.1 billion)

        for (var i = 0; i < party.size(); i++) {
            var player = party.get(i);
            var expRate = player.getExpRate(); // Fetch the player's individual EXP rate
            var totalExp = Math.floor(baseExp * expRate); // Calculate the total experience points as an integer

            // Check if the total EXP exceeds the limit
            if (totalExp > expLimit) {
                // Split EXP into smaller chunks that fit within the limit
                var chunks = Math.floor(totalExp / expLimit);
                var remainderExp = totalExp % expLimit;

                // Give EXP in chunks
                for (var j = 0; j < chunks; j++) {
                    player.gainExp(expLimit, true, true, true); // Grant the max EXP amount per chunk
                }

                // Give the remaining EXP
                if (remainderExp > 0) {
                    player.gainExp(remainderExp, true, true, true); // Grant the remaining EXP
                }
            } else {
                // If totalExp is within the limit, grant it normally
                player.gainExp(totalExp, true, true, true);
            }

            // Grant item reward
            if (!player.isBot()) { // 1.6.1: a bot has no client to hand leaves to (it threw and stopped the loop)
                player.getClient().getAbstractPlayerInteraction().gainItem(4001126, 40);
                rewarded++;
            } else {
                botsSkipped++;
            }
        }
        feedLine(eim, "rewards: exp " + baseExp + " each (rate-scaled), item 4001126 x40 to " + rewarded + " members, " + botsSkipped + " bots skipped");

        eim.dispatchRaiseQuestMobCount(8810018, 240060200);
        // mob.getMap().broadcastHorntailVictory();

    } else if (isHorntailHead(mob)) {
        var killed = eim.getIntProperty("defeatedHead");
        eim.setIntProperty("defeatedHead", killed + 1);
        feedLine(eim, "head " + (killed + 1) + " down after " + feedSecs(eim) + " s");
        eim.showClearEffect(mob.getMap().getId());
    }
}



function allMonstersDead(eim) {}

function cancelSchedule() {}

function dispose(eim) {}
