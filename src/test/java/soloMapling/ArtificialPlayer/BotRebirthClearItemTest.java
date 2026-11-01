package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import client.Job;
import client.inventory.InventoryType;
import client.inventory.Item;
import config.YamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotProgressionSystem.BotRebirth;
import soloMapling.ArtificialPlayer.BotProgressionSystem.RebirthBosses.Boss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * t-rb-2 (round 2 review): the check wants Von Leon's item 4001693 to have been consumed FOR this character's
 * rebirth. Round 1 accepted "the bag is empty and the next key's count went up", which also describes a key that
 * arrived from another bot's clear, a GM grant or another route; a key that appeared any other way is not the item.
 * The evidence is the bot's own craft, recorded at the consume itself (see {@code BotRebirth.craft}).
 */
class BotRebirthClearItemTest {

    /** Von Leon's hand-out, the ingredient the 2nd key must be crafted from. */
    private static final int ITEM = Boss.VONLEON.clearItem;

    private boolean useRebirth;

    /** {@code Character#getReborns} refuses to answer unless the server's rebirth system is on (as on TEST). */
    @BeforeEach
    void enableRebirths() {
        useRebirth = YamlConfig.config.server.USE_REBIRTH_SYSTEM;
        YamlConfig.config.server.USE_REBIRTH_SYSTEM = true;
    }

    @AfterEach
    void restoreRebirths() {
        YamlConfig.config.server.USE_REBIRTH_SYSTEM = useRebirth;
    }

    private static Character bot(int id, int reborns) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(id);
        chr.setName("Evidence" + (id % 10));
        chr.setJob(Job.getById(412));
        chr.setLevel(200);
        chr.setBotReborns(reborns);
        return chr;
    }

    @Test
    void anEmptyBagAndARisingKeyCountPassNothingWithoutThisCharactersCraft() {
        Character chr = bot(1_000_000_000 + 7621, 1);
        int keyBefore = BotRebirthQa.keyAhead(chr);
        assertTrue(BotHoldings.grantItem(chr, new Item(BotRebirth.keyItemFor(1), (short) 0, (short) 1)),
                "the key arrived from somewhere else: another bot's clear, a GM grant, another route");
        assertEquals(0, chr.getInventory(InventoryType.ETC).countById(ITEM), "the item is not in the bag");
        assertTrue(BotRebirthQa.keyAhead(chr) > keyBefore,
                "and the next key's count did go up - the shape the round 1 predicate passed");
        assertFalse(BotRebirthQa.clearItemConsumed(chr, ITEM, 1, 0L),
                "nothing this character did spent the item on its rebirth: a key count that went up is not the item");
    }

    @Test
    void aCharacterThatNeverCraftedHasNoEvidence() {
        Character chr = bot(1_000_000_000 + 7622, 1);
        assertFalse(BotRebirthQa.clearItemConsumed(chr, ITEM, 1, 0L), "no craft, no evidence");
        assertFalse(BotRebirthQa.clearItemConsumed(null, ITEM, 1, 0L), "no character, no evidence");
    }
}
