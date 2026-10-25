package constants.game;

import constants.id.MapId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Constants the MapleRoot import left in a state that compiles but cannot work.
 *
 * <p>{@link MapId#ARAN_TUTORIAL_START} became a second name for {@code MAPLEROOT_HUB} (993910000) while
 * {@link MapId#ARAN_TUTORIAL_MAX} stayed 914000500, so the tutorial range check in
 * {@code AbstractDealDamageHandler:720} can never be true and its +80000 damage headroom is dead.
 *
 * <p>{@code GOTO_AREAS} lost stock Cosmic's last entry, {@code "fm"}, so a GM's {@code @goto fm} answered "Area not
 * available" even though the Free Market is where the shops are. (Players only get {@code GOTO_TOWNS}, so this is a GM
 * destination either way.)
 */
class MapConstantsTest {

    @Test
    void aranTutorialConstantPointsIntoTheTutorialRange() {
        assertTrue(MapId.ARAN_TUTORIAL_START <= MapId.ARAN_TUTORIAL_MAX,
                "ARAN_TUTORIAL_START " + MapId.ARAN_TUTORIAL_START + " is above ARAN_TUTORIAL_MAX " + MapId.ARAN_TUTORIAL_MAX
                        + ", so the range check can never match");
        assertTrue(MapId.ARAN_TUTORIAL_START / 1000000 == 914,
                "the Aran tutorial maps are 9140000xx, not " + MapId.ARAN_TUTORIAL_START);
    }

    @Test
    void gotoCanSendAGmToTheFreeMarket() {
        assertEquals(MapId.FM_ENTRANCE, GameConstants.GOTO_AREAS.get("fm"), "@goto fm");
    }
}
