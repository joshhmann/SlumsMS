package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 59: names in a line, by rules (Decide has no span question). */
class ConvoEntitiesTest {

    @AfterEach
    void reset() {
        ConvoEntities.useForTest(null);
    }

    private static void names() {
        ConvoEntities.useForTest(new ConvoEntities.Names() {
            public Map<String, Integer> maps() {
                return Map.of("ant tunnel i", 105050000, "pig beach", 104010001);
            }

            public Map<String, Integer> mobs() {
                return Map.of("zombie mushroom", 2230101, "drake", 5130100, "jr balrog", 8130100);
            }

            public Map<String, Integer> items() {
                return Map.of("work gloves", 1082002, "ilbi throwing stars", 2070006);
            }
        });
    }

    @Test
    void aliasesAnswerWithoutTheWzIndex() {
        ConvoEntities.Found f = ConvoEntities.find("come to the fm ch1");
        assertEquals(910000000, f.first(ConvoEntities.Kind.MAP).id());
        assertEquals(103000000, ConvoEntities.find("kpq anyone").first(ConvoEntities.Kind.MAP).id());
        assertTrue(ConvoEntities.find("lol ok").isEmpty());
    }

    @Test
    void longestNameWinsAndPluralsMatch() {
        names();
        ConvoEntities.Found f = ConvoEntities.find("help me kill these zombie mushrooms at ant tunnel i");
        assertEquals(2230101, f.first(ConvoEntities.Kind.MOB).id());
        assertEquals(105050000, f.first(ConvoEntities.Kind.MAP).id());
        assertEquals(1082002, ConvoEntities.find("how much for the work gloves").first(ConvoEntities.Kind.ITEM).id());
        assertNull(ConvoEntities.find("can u help").first(ConvoEntities.Kind.MOB), "stop words never match");
    }

    @Test
    void playersByNameOnTheMap() {
        List<ConvoEntities.Entity> p = ConvoEntities.players("is pinky online today", List.of("Pinky", "xKaizx", "Bob"));
        assertEquals(1, p.size());
        assertEquals("Pinky", p.get(0).name());
        assertEquals("xKaizx", ConvoEntities.players("did u see kaiz drop that", List.of("xKaizx")).get(0).name());
    }
}
