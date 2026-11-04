package soloMapling;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import provider.Data;
import scripting.event.EventInstanceManager;
import server.StatEffect;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotCombatTuning;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotDeaths;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.4 item 3: a dead bot must be reachable by the thing that revives players. The selection already reaches it -
 * {@code StatEffect.partyBuffTargets} asks the map's players for {@code !isAlive()} when the effect is a resurrection
 * and filters nobody for bots - and the HP change already applies, so the gap was {@code BotDeaths}: the bot stayed a
 * corpse afterwards (no hits, no acting, and the run could not drive it). That is the line this pins.
 */
class BotResurrectionTest {

    private static final int BOT = 701;
    private static final int BISHOP = 702;

    @AfterEach
    void forget() {
        BotDeaths.clearBot(BOT);
        BotCombatTuning.reset();
    }

    /** The Bishop's Resurrection as the server builds it: the id is all {@code isResurrection()} reads. */
    private static StatEffect resurrection() {
        Data none = none();
        return StatEffect.loadSkillEffectFromData(none, constants.skills.Bishop.RESURRECTION, false);
    }

    private static Data none() {
        return (Data) java.lang.reflect.Proxy.newProxyInstance(Data.class.getClassLoader(),
                new Class<?>[]{Data.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getChildByPath" -> null;
                    case "getType" -> DataType.NONE;
                    case "getName", "getData" -> "";
                    case "toString" -> "no-wz";
                    case "hashCode" -> 1;
                    case "equals" -> proxy == args[0];
                    default -> method.getReturnType() == boolean.class ? false
                            : method.getReturnType() == int.class ? 0 : null;
                });
    }

    /** Only what the WZ loader's return type needs; {@code Data.type} is an enum from provider. */
    enum DataType { NONE }

    private static Character bot(int hp, int maxHp) {
        Character c = testutil.Mocks.chr("Laz");
        when(c.getId()).thenReturn(BOT);
        when(c.getHp()).thenReturn(hp);
        when(c.getMaxHp()).thenReturn(maxHp);
        when(c.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        when(c.isBot()).thenReturn(true);
        when(c.getCurrentMaxHp()).thenReturn(maxHp);
        when(c.applyHpMpChange(anyInt(), anyInt(), anyInt())).thenReturn(true);
        return c;
    }

    private static Character bishop() {
        Character c = testutil.Mocks.chr("Bishop");
        when(c.getId()).thenReturn(BISHOP);
        when(c.getPosition()).thenReturn(new java.awt.Point(0, 0));
        return c;
    }

    @Test
    void aResurrectionTakesADeadBotOutOfTheDeadState() {
        StatEffect effect = resurrection();
        Character bot = bot(0, 1_000);

        try (var movement = mockStatic(GCMovement.class)) {
            BotDeaths.die(bot);
            assertTrue(BotDeaths.isDead(BOT), "the bot lies dead");

            assertTrue(effect.applyToTarget(bishop(), bot), "the effect applies");

            assertFalse(BotDeaths.isDead(BOT), "and the bot is no longer a corpse: it can act, take hits and be driven");
            movement.verify(() -> GCMovement.releaseCommitmentHold(bot), org.mockito.Mockito.times(1));

            // and it is mortal again: the very next lethal hit lands, so the revival did not leave it immune
            soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals.hurt(bot, 5);
            assertTrue(BotDeaths.isDead(BOT), "a revived bot takes damage like any other again");
        }
    }

    @Test
    void aResurrectionOnALivingBotChangesNothing() {
        StatEffect effect = resurrection();
        Character bot = bot(900, 1_000);

        try (var movement = mockStatic(GCMovement.class)) {
            assertFalse(BotDeaths.isDead(BOT));
            effect.applyToTarget(bishop(), bot);
            assertFalse(BotDeaths.isDead(BOT));
            movement.verify(() -> GCMovement.releaseCommitmentHold(bot), org.mockito.Mockito.never());
        }
    }

    @Test
    void theResurrectionIsTheEffectThatDoesThisAndAnOrdinaryBuffIsNot() {
        StatEffect heal = StatEffect.loadSkillEffectFromData(none(), constants.skills.Cleric.HEAL, false);
        assertNotNull(heal);
        Character bot = bot(0, 1_000);

        try (var movement = mockStatic(GCMovement.class)) {
            BotDeaths.die(bot);
            heal.applyToTarget(bishop(), bot);
            assertTrue(BotDeaths.isDead(BOT), "Heal does not revive: only the resurrection branch does");
        }
    }
}
