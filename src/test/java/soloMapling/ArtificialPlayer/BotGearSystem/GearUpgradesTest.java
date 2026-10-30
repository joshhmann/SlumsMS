package soloMapling.ArtificialPlayer.BotGearSystem;

import client.BotClient;
import client.Character;
import client.Job;
import client.inventory.Equip;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Known;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Valuer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * 1.6.2 ticket 99 / 162 part 3: upgrade sharing (nutnnut/Cosmic BotOfferManager) through one small scoring interface;
 * the default scores through GearScoring.live() (the scorer bots dress by), counting what the recipient wears and carries.
 */
class GearUpgradesTest {

    static final int HAT = 1002000;
    static final int BETTER_HAT = 1002001;
    static final int BEST_HAT = 1002002;
    static final int HIGH_HAT = 1002003;

    private static final Valuer STR = (weapon, str, dex, int_, luk, watk, matk) -> str + watk * 10.0;
    private final Map<Integer, Known> data = new HashMap<>();

    @AfterEach
    void tearDown() {
        GearUpgrades.install(null);
    }

    private void know(int itemId, int reqLevel) {
        data.put(itemId, new Known((short) -1, reqLevel, 0, 0, 0, 0, 0, 0));
    }

    private static Character chr(int id, int level) {
        Character c = Character.getDefault(new BotClient(0, 0));
        c.setID(id);
        c.setJob(Job.BEGINNER);
        c.setLevel(level);
        return c;
    }

    private static Equip equip(int itemId, short position, int str) {
        Equip e = mock(Equip.class, CALLS_REAL_METHODS);
        doReturn(itemId).when(e).getItemId();
        e.setPosition(position);
        e.setStr((short) str);
        return e;
    }

    /* The default scorer over a GearScoring that reads this test's item data (BotGear's own scoring code). */
    private GearUpgrades.Scorer planner() {
        GearScoring scoring = new GearScoring() {
            @Override
            public double upgradeValue(Character chr, Equip candidate) {
                return BotGear.upgradeValue(chr, candidate, data::get, worn -> STR);
            }

            @Override
            public double upgradeFraction(Character chr, Equip candidate) {
                return BotGear.upgradeFraction(chr, candidate, data::get, worn -> STR);
            }

            @Override
            public Map<Short, Equip> bestSet(Character chr, java.util.Collection<Equip> extras) {
                return BotGear.bestSet(chr, extras, data::get, worn -> STR);
            }
        };
        return GearUpgrades.from(scoring);
    }

    @Test
    void aBetterPieceIsAnUpgradeAWorseOneIsNot() {
        know(HAT, 0);
        know(BETTER_HAT, 0);
        Character owner = chr(10, 30);
        owner.getInventory(InventoryType.EQUIPPED).addItemFromDB(equip(HAT, (short) -1, 2));
        assertEquals(3.0, planner().gain(owner, equip(BETTER_HAT, (short) 5, 5)), 1e-9);
        assertEquals(0.0, planner().gain(owner, equip(BETTER_HAT, (short) 5, 1)), 1e-9);
    }

    @Test
    void comparedAgainstWhatTheRecipientCarriesToo() {
        know(HAT, 0);
        know(BETTER_HAT, 0);
        know(BEST_HAT, 0);
        Character owner = chr(10, 30);
        owner.getInventory(InventoryType.EQUIPPED).addItemFromDB(equip(HAT, (short) -1, 2));
        owner.getInventory(InventoryType.EQUIP).addItemFromDB(equip(BEST_HAT, (short) 1, 9));
        assertEquals(0.0, planner().gain(owner, equip(BETTER_HAT, (short) 5, 5)), 1e-9,
                "it already carries a better hat than the offer");
    }

    @Test
    void aPieceTheRecipientCannotWearIsNoUpgrade() {
        know(HAT, 0);
        know(HIGH_HAT, 50);
        Character owner = chr(10, 30);
        owner.getInventory(InventoryType.EQUIPPED).addItemFromDB(equip(HAT, (short) -1, 2));
        assertEquals(0.0, planner().gain(owner, equip(HIGH_HAT, (short) 5, 20)), 1e-9);
    }

    @Test
    void eachSparePieceGoesToWhoeverItHelpsMostBestFirst() {
        Character owner = chr(10, 30);
        Character sibling = chr(11, 30);
        Equip a = equip(BETTER_HAT, (short) 1, 0);
        Equip b = equip(BEST_HAT, (short) 2, 0);
        Equip c = equip(HAT, (short) 3, 0);
        GearUpgrades.Scorer fake = (wearer, piece) -> {
            if (piece == a) {
                return wearer == owner ? 2 : 5;
            }
            if (piece == b) {
                return wearer == owner ? 9 : 1;
            }
            return 0;
        };
        List<GearUpgrades.Offer> offers = GearUpgrades.offers(List.of(a, b, c), List.of(owner, sibling), fake);
        assertEquals(2, offers.size(), "nobody wants the third");
        assertSame(b, offers.get(0).item());
        assertSame(owner, offers.get(0).to());
        assertSame(a, offers.get(1).item());
        assertSame(sibling, offers.get(1).to());
    }

    @Test
    void ticket162PlugsInItsOwnScorer() {
        GearUpgrades.Scorer mine = (wearer, piece) -> 1;
        GearUpgrades.install(mine);
        assertSame(mine, GearUpgrades.scorer());
        GearUpgrades.install(null);
        assertSame(GearUpgrades.LIVE, GearUpgrades.scorer(), "the default is the shared GearScoring.live() scorer");
    }
}
