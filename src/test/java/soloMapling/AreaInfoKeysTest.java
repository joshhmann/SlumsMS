package soloMapling;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import soloMapling.fishing.StarterChairs;
import soloMapling.menus.GuideMenu;
import soloMapling.menus.TutorialIslandMenu;

/** Each SlumsMS "seen once" flag needs its own area-info key, or one feature marks another as done. */
class AreaInfoKeysTest {

    @Test
    void everyAreaInfoKeyIsUnique() {
        List<Short> keys = List.of(GuideMenu.SEEN_AREA, GuideMenu.UI_TIP_AREA, TutorialIslandMenu.ASKED_AREA,
                StarterChairs.GIVEN_AREA, StarterChairs.NOTICED_AREA);
        Set<Short> unique = new HashSet<>(keys);
        assertEquals(keys.size(), unique.size(), "area-info keys collide: " + keys);
    }
}
