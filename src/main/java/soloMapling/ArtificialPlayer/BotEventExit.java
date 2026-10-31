package soloMapling.ArtificialPlayer;

import client.Character;
import constants.id.MapId;
import scripting.event.EventInstanceManager;
import server.maps.MapleMap;

/**
 * The one way a bot or an owned alt leaves an event instance (PQ, boss expedition, event map), moved here from
 * OwnedAltCompanion (audit #8 b115 + 162-kpqfix K6/b118) so population followers use it too (1.6.2 S3). It goes the
 * way the event's script lets a member go ({@code playerExit}: KPQ, Zakum and the other expeditions unregister it and
 * send it to their exit map), never through the disconnect/revive rules that end the whole run when the team drops
 * below its minimum - so the run goes on without it - and never by a raw warp out of the event. If the script has no
 * exit for it, it is unregistered and sent to the map's return town. Only the last member still goes through
 * {@code playerDisconnected}, so an instance is never left running with nobody in it.
 */
public final class BotEventExit {

    private BotEventExit() {
    }

    public static void leave(Character chr) {
        EventInstanceManager eim = chr.getEventInstance();
        if (eim == null || eim.isEventDisposed()) {
            return;
        }
        if (eim.getPlayerCount() <= 1) {
            eim.playerDisconnected(chr);
            return;
        }
        try {
            eim.invokeScriptFunction("playerExit", eim, chr); // KPQ: unregister, then to the exit map
        } catch (Exception e) {
            soloMapling.BotLogger.log("[EventExit] " + chr.getName() + " could not leave " + eim.getName()
                    + " through its script: " + e);
        }
        if (chr.getEventInstance() != null) {
            eim.unregisterPlayer(chr);
            MapleMap map = chr.getMap();
            if (map != null && map.getReturnMapId() > 0 && map.getReturnMapId() != MapId.NONE) {
                chr.changeMap(map.getReturnMapId());
            }
        }
    }
}
