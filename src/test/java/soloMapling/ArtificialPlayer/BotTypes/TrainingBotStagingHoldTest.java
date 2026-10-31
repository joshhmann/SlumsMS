package soloMapling.ArtificialPlayer.BotTypes;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.worldevents.WorldEventSteer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * t-gw-1 on TEST 2026-10-31: the war staged Wafflez (a SlumLords grinder) on the war map 104040000 and, two seconds
 * later, the war made the guild party Cookie6 + Wafflez with Cookie6 steered to the contest map 105040306. Its next
 * decision read the (bot) party's map before the GM staging hold, walked off the war map and sat in Henesys town (no
 * mesos for the cab), so SlumLords killed nothing and the check failed. A GM / !qa staging hold comes first unless the
 * party has a real player in it (their map, and party EXP needs it); a real world event still defers to the party.
 */
class TrainingBotStagingHoldTest {

    private static final int WAR_MAP = 104040000;
    private static final int TURF_MAP = 105040306;

    private int id = BotStore.BOT_ID_BASE + 62_150;

    @AfterEach
    void unhold() {
        WorldEventSteer.unhold(id);
    }

    private Character bot() {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(id);
        chr.setName("Wafflez");
        chr.setMapId(WAR_MAP);
        return chr;
    }

    @Test
    void aStagingHoldOutranksABotPartysMap() {
        Character chr = bot();
        WorldEventSteer.hold(id, WAR_MAP);
        TrainingBot.Steer staged = TrainingBot.steer(chr, TURF_MAP, false);
        assertNotNull(staged, "a staged bot and a party map: it still goes somewhere");
        assertTrue(staged.staged(), "a bot party's map does not outrank a GM staging hold");
        assertEquals(WAR_MAP, staged.mapId());
        assertEquals(WAR_MAP, TrainingBot.stagedMap(chr));
    }

    /** The player's rule stands: partied with a real player, the bot trains on the player's map (party EXP). */
    @Test
    void thePlayersMapOutranksTheHold() {
        WorldEventSteer.hold(id, WAR_MAP);
        TrainingBot.Steer party = TrainingBot.steer(bot(), TURF_MAP, true);
        assertNotNull(party);
        assertFalse(party.staged(), "a real player's party map outranks a staging hold");
        assertEquals(TURF_MAP, party.mapId());
    }

    @Test
    void withoutAHoldThePartysMapIsUsed() {
        Character chr = bot();
        assertEquals(-1, TrainingBot.stagedMap(chr), "no hold: nothing staged");
        TrainingBot.Steer party = TrainingBot.steer(chr, TURF_MAP, false);
        assertNotNull(party);
        assertFalse(party.staged());
        assertEquals(TURF_MAP, party.mapId());
        assertNull(TrainingBot.steer(chr, null, false), "no hold and no party: the bot discovers a map as usual");
        assertNull(TrainingBot.steer(chr, null, true), "no hold, no party: nothing to steer");
    }

    /** The hold can never push a bot past a rule it must respect (a restricted map is ignored, not entered). */
    @Test
    void aHoldOnARestrictedMapIsIgnored() {
        int restricted = 401053002; // Knight Stronghold: no bot enters (HyperTP restricted)
        WorldEventSteer.hold(id, restricted);
        assertEquals(-1, TrainingBot.stagedMap(bot()));
        TrainingBot.Steer party = TrainingBot.steer(bot(), TURF_MAP, false);
        assertNotNull(party);
        assertFalse(party.staged(), "an unreachable hold falls back to the party");
        assertEquals(TURF_MAP, party.mapId());
    }
}
