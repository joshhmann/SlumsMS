package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.BotClient;
import client.BuffStat;
import client.Character;
import client.Disease;
import client.Job;
import constants.skills.Cleric;
import constants.skills.Priest;
import net.server.world.Party;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotPartySupport.Pot;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.7 regular-buffs-real: a companion Priest with Dispel learned clears the disease a mob put on a party member the
 * cast may really reach (a player, or a companion the policy allows - an owned alt by default), for the skill's MP, at
 * most once every {@code DISPEL_GAP_MS}, and Heal still comes first. A party regular with the default switches, a
 * companion whose Heal support is switched off, and a disease this server's Dispel does not clear
 * (Character.dispelDebuffs: CURSE, DARKNESS, POISON, SEAL, WEAKEN, SLOW) are all left alone.
 */
class BotPartyDispelTest {

    private static final int BOT = 1_000_000_000 + 61;
    private static final int ALT = 1_000_000_000 + 62;
    private static final int REGULAR = 1_000_000_000 + 63;
    private static final int HURT = 401;
    private static final int SEALED = 402;
    private static final long T0 = 1_800_000_000_000L;

    private final List<Integer> paid = new ArrayList<>();
    private final List<Integer> healed = new ArrayList<>();
    private final List<Integer> dispelled = new ArrayList<>();
    private boolean hasMp = true;
    private boolean realPlayerInParty = true;
    private List<Character> party = new ArrayList<>();
    private Disease diseaseOfSealed = Disease.SEAL;

    @BeforeEach
    void seam() {
        System.setProperty("slums.bots.partysupport", "true");
        BotPartySupport.useForTest(new BotPartySupport.Effects() {
            @Override public List<Character> partyInReach(Character bot) { return party; }
            @Override public boolean partiedWithPlayer(Character bot) { return realPlayerInParty; }
            @Override public boolean fighting(Character bot) { return false; }
            @Override public int mpCost(Character bot, int skillId, int level) {
                return skillId == Priest.DISPEL ? 15 : 40;
            }
            @Override public boolean pay(Character bot, int mp) { paid.add(mp); return hasMp; }
            @Override public void heal(Character bot, Character target, int skillId, int level) { healed.add(target.getId()); }
            @Override public void dispel(Character bot, Character target, int skillId, int level) {
                target.dispelDebuffs();
                dispelled.add(target.getId());
            }
            @Override public List<Pot> pots(Character bot) { return List.of(); }
            @Override public Set<BuffStat> active(Character bot) { return Set.of(); }
            @Override public boolean drink(Character bot, Pot pot) { return false; }
        });
    }

    @AfterEach
    void clean() {
        System.clearProperty("slums.bots.partysupport");
        BotPartySupport.useForTest(null);
        BotPartySupport.clearBot(BOT);
        BotPartySupport.clearBot(ALT);
        BotPartySupport.clearBot(REGULAR);
        for (String p : new String[]{"slums.companion.buffs.alts", "slums.companion.buffs.regulars"}) {
            System.clearProperty(p);
        }
    }

    /** A party member in reach: hurt controls the HP share, disease what a mob put on it, or null for a clean one. */
    private Character member(int id, int hp, int maxHp, Disease disease) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getHp()).thenReturn(hp);
        when(c.getCurrentMaxHp()).thenReturn(maxHp);
        when(c.isAlive()).thenReturn(true);
        when(c.hasDisease(any(Disease.class))).thenAnswer(i -> i.getArgument(0) == disease);
        return c;
    }

    private Character priest(int id, int dispelLevel, int healLevel, boolean alt) {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(id);
        when(bot.getMap()).thenReturn(mock(MapleMap.class));
        when(bot.getSkillLevel(Priest.DISPEL)).thenReturn(dispelLevel);
        when(bot.getSkillLevel(Cleric.HEAL)).thenReturn(healLevel);
        when(bot.getJob()).thenReturn(Job.PRIEST);
        when(bot.getClient()).thenReturn(mock(BotClient.class));
        when(bot.isOwnedAlt()).thenReturn(alt);
        return bot;
    }

    @Test
    void aPriestAltDispelsTheSealedMemberForMpAndThenWaitsOutTheGap() {
        party = List.of(member(HURT, 990, 1_000, null), member(SEALED, 980, 1_000, Disease.SEAL));
        Character bot = priest(BOT, 1, 10, true);

        assertTrue(BotPartySupport.tick(bot, T0), "the companion dispelled the member");
        assertEquals(List.of(SEALED), dispelled);
        assertEquals(List.of(15), paid, "it paid Dispel's MP, not Heal's");
        assertTrue(healed.isEmpty(), "nobody was hurt enough to heal");

        assertFalse(BotPartySupport.tick(bot, T0 + 1_000), "still inside the gap");
        assertTrue(BotPartySupport.tick(bot, T0 + BotPartySupport.DISPEL_GAP_MS));
        assertEquals(List.of(SEALED, SEALED), dispelled);
    }

    @Test
    void healComesFirstAndTheDispelWaitsForIt() {
        Character hurt = member(HURT, 200, 1_000, null);
        Character sealed = member(SEALED, 980, 1_000, Disease.SEAL);
        party = List.of(hurt, sealed);
        Character bot = priest(BOT, 1, 10, true);

        assertTrue(BotPartySupport.tick(bot, T0));
        assertEquals(List.of(HURT), healed, "Heal first");
        assertEquals(List.of(40), paid);
        assertTrue(dispelled.isEmpty(), "the Heal was the cast this tick");

        // the hurt member is back on their feet, so the next look is the sealed one's
        party = List.of(member(HURT, 1_000, 1_000, null), sealed);
        assertTrue(BotPartySupport.tick(bot, T0 + BotPartySupport.HEAL_GAP_MS));
        assertEquals(List.of(SEALED), dispelled, "then the Dispel");
        assertEquals(List.of(40, 15), paid);
    }

    @Test
    void nobodyDiseasedNoDispel() {
        party = List.of(member(HURT, 990, 1_000, null), member(SEALED, 980, 1_000, null));
        assertFalse(BotPartySupport.tick(priest(BOT, 1, 10, true), T0));
        assertTrue(dispelled.isEmpty());
        assertTrue(paid.isEmpty());
    }

    @Test
    void aPriestWithoutDispelLearnedDoesNothing() {
        party = List.of(member(SEALED, 980, 1_000, Disease.SEAL));
        assertFalse(BotPartySupport.tick(priest(BOT, 0, 10, true), T0));
        assertTrue(dispelled.isEmpty());
        assertTrue(paid.isEmpty());
    }

    @Test
    void aRegularNeverDispelsWithTheDefaults() {
        party = List.of(member(SEALED, 980, 1_000, Disease.SEAL));
        assertFalse(BotPartySupport.tick(priest(REGULAR, 1, 10, false), T0),
                "the regulars switch is off by default");
        assertTrue(dispelled.isEmpty());
        assertTrue(paid.isEmpty());

        System.setProperty("slums.companion.buffs.regulars", "true");
        assertTrue(BotPartySupport.tick(priest(REGULAR, 1, 10, false), T0 + 1),
                "a server that switches regulars on gets the Dispel");
        assertEquals(List.of(SEALED), dispelled);
    }

    @Test
    void healsOffStopsTheDispelForThatCompanion() {
        party = List.of(member(SEALED, 980, 1_000, Disease.SEAL));
        Character off = priest(ALT, 2, 10, true);
        Character on = priest(ALT + 1, 2, 10, true);
        Character owner = mock(Character.class);
        when(owner.getId()).thenReturn(4_000_061);
        soloMapling.companions.CompanionSettingsStore.useForTest(
                new soloMapling.companions.CompanionSettingsStore.Memory(), Runnable::run);
        try {
            soloMapling.companions.CompanionSettingsStore.save(owner, off,
                    soloMapling.companions.CompanionSettings.DEFAULT.withHeals(false));

            assertFalse(BotPartySupport.tick(off, T0), "heals off: the companion does nothing");
            assertTrue(dispelled.isEmpty());

            assertTrue(BotPartySupport.tick(on, T0), "the other companion still dispels");
            assertEquals(List.of(SEALED), dispelled);
        } finally {
            soloMapling.companions.CompanionSettingsStore.useForTest(null, null);
        }
    }

    @Test
    void noMpNoCastAndNoRetryUntilTheGapPasses() {
        hasMp = false;
        party = List.of(member(SEALED, 980, 1_000, Disease.SEAL));
        Character bot = priest(BOT, 1, 10, true);
        assertFalse(BotPartySupport.tick(bot, T0));
        assertTrue(dispelled.isEmpty());
        hasMp = true;
        assertFalse(BotPartySupport.tick(bot, T0 + 500), "not every tick");
        assertTrue(BotPartySupport.tick(bot, T0 + BotPartySupport.DISPEL_GAP_MS));
        assertEquals(List.of(SEALED), dispelled);
    }

    @Test
    void aMemberTheCastMayNotReachIsLeftAlone() {
        Character strangerBot = member(SEALED, 980, 1_000, Disease.SEAL);
        when(strangerBot.getClient()).thenReturn(mock(BotClient.class));
        when(strangerBot.isOwnedAlt()).thenReturn(false);
        party = List.of(strangerBot);
        assertFalse(BotPartySupport.tick(priest(BOT, 1, 10, true), T0),
                "a party regular is not a target a companion's Dispel may land on by default");
        assertTrue(dispelled.isEmpty());
        assertTrue(paid.isEmpty());
    }

    @Test
    void theTargetRuleTakesOnlyWhatDispelCanClear() {
        Character bot = priest(BOT, 1, 10, true);
        Character sealed = member(SEALED, 980, 1_000, Disease.SEAL);
        Character cursed = member(SEALED + 1, 980, 1_000, Disease.CURSE);

        assertEquals(sealed, BotPartySupport.pickDispelTarget(bot, List.of(member(HURT, 990, 1_000, null), sealed)));
        assertNull(BotPartySupport.pickDispelTarget(bot, List.of()), "nobody there");
        assertEquals(cursed, BotPartySupport.pickDispelTarget(bot, List.of(cursed)), "CURSE is one Dispel clears");

        // what Character.dispelDebuffs actually clears: CURSE, DARKNESS, POISON, SEAL, WEAKEN, SLOW - and nothing else
        for (Disease cleared : List.of(Disease.DARKNESS, Disease.POISON, Disease.WEAKEN, Disease.SLOW)) {
            Character c = member(SEALED + 10 + cleared.ordinal(), 980, 1_000, cleared);
            assertEquals(c, BotPartySupport.pickDispelTarget(bot, List.of(c)), cleared + " is cleared by Dispel");
        }
        // SEDUCE, ZOMBIFY, CONFUSE, STUN are not (Character.purgeDebuffs, not dispelDebuffs)
        for (Disease kept : List.of(Disease.SEDUCE, Disease.ZOMBIFY, Disease.CONFUSE, Disease.STUN)) {
            Character c = member(SEALED + 20 + kept.ordinal(), 980, 1_000, kept);
            assertNull(BotPartySupport.pickDispelTarget(bot, List.of(c)),
                    kept + " is not something this server's Dispel clears");
        }
    }
}
