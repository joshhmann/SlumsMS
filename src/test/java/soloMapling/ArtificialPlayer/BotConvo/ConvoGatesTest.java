package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 59, owner decision 1: "the LLM proposes, server rules decide". */
class ConvoGatesTest {

    /** A friendly grinder asked to come to a grindable map it is not on. */
    static ConvoGates.Facts base() {
        return new ConvoGates.Facts(false, false, false, false, false, false, ConvoGates.BotKind.GRINDER, true, 105050000,
                false, true, true, false, null, false, false, false, true);
    }

    static ConvoGates.Facts with(java.util.function.UnaryOperator<Object[]> change) {
        ConvoGates.Facts b = base();
        Object[] f = {b.busy(), b.flagged(), b.hostile(), b.wary(), b.dramaOptOut(), b.mayFlirt(), b.kind(),
                b.freeForScene(), b.targetMap(), b.targetIsHere(), b.targetIsPlayers(), b.targetGrindable(),
                b.targetIsTown(), b.buffProblem(), b.recentlyWalked(), b.playerSorry(), b.playerRude(), b.targetWalkable()};
        f = change.apply(f);
        return new ConvoGates.Facts((boolean) f[0], (boolean) f[1], (boolean) f[2], (boolean) f[3], (boolean) f[4],
                (boolean) f[5], (ConvoGates.BotKind) f[6], (boolean) f[7], (int) f[8], (boolean) f[9], (boolean) f[10],
                (boolean) f[11], (boolean) f[12], (String) f[13], (boolean) f[14], (boolean) f[15], (boolean) f[16],
                (boolean) f[17]);
    }

    private static boolean yes(ConvoAction a, ConvoGates.Facts f) {
        return ConvoGates.decide(a, f).yes();
    }

    @Test
    void comeNeedsAFreeFriendlyBotAndAFittingPlace() {
        assertTrue(yes(ConvoAction.COME, base()));
        assertFalse(yes(ConvoAction.COME, with(f -> { f[0] = true; return f; })), "player busy");
        assertFalse(yes(ConvoAction.COME, with(f -> { f[2] = true; return f; })), "rival");
        assertFalse(yes(ConvoAction.COME, with(f -> { f[3] = true; return f; })), "wary");
        assertFalse(yes(ConvoAction.COME, with(f -> { f[7] = false; return f; })), "in a scene or event");
        assertFalse(yes(ConvoAction.COME, with(f -> { f[11] = false; return f; })), "grinder, not a map for it");
        assertFalse(yes(ConvoAction.COME, with(f -> { f[14] = true; return f; })), "walked recently");
        assertFalse(yes(ConvoAction.COME, with(f -> { f[8] = -1; return f; })), "nowhere");
        assertTrue(yes(ConvoAction.COME, with(f -> { f[6] = ConvoGates.BotKind.SOCIAL; f[12] = true; return f; })));
        assertFalse(yes(ConvoAction.COME, with(f -> { f[6] = ConvoGates.BotKind.SOCIAL; return f; })), "town bots stay in town");
        assertFalse(yes(ConvoAction.COME, with(f -> { f[6] = ConvoGates.BotKind.SERVICE; f[12] = true; return f; })));
    }

    @Test
    void aPlaceTheBotCannotWalkToIsRefusedNeverWarped() {
        // review 2026-10-22: a social bot on a disconnected map was sent to Henesys and GCTravel warped it
        ConvoGates.Decision come = ConvoGates.decide(ConvoAction.COME, with(f -> { f[17] = false; return f; }));
        assertFalse(come.yes());
        assertEquals(ConvoGates.TOO_FAR, come.reason());
        assertEquals(ConvoGates.TOO_FAR, ConvoGates.decide(ConvoAction.HELP, with(f -> { f[17] = false; return f; })).reason());
        assertEquals(ConvoGates.TOO_FAR, ConvoGates.decide(ConvoAction.GUIDE, with(f -> { f[6] = ConvoGates.BotKind.SOCIAL;
            f[10] = false; f[12] = true; f[17] = false; return f; })).reason());
        assertTrue(yes(ConvoAction.HELP, with(f -> { f[9] = true; f[17] = false; return f; })), "already here: no walk needed");
    }

    @Test
    void helpIsAGrinderOnThePlayersMap() {
        assertTrue(yes(ConvoAction.HELP, base()));
        assertFalse(yes(ConvoAction.HELP, with(f -> { f[10] = false; return f; })));
        assertFalse(yes(ConvoAction.HELP, with(f -> { f[6] = ConvoGates.BotKind.SOCIAL; return f; })));
    }

    @Test
    void buffFollowsTheBuffCheck() {
        assertTrue(yes(ConvoAction.BUFF, base()));
        assertFalse(yes(ConvoAction.BUFF, with(f -> { f[13] = "no such buff"; return f; })));
        assertFalse(yes(ConvoAction.HEAL, with(f -> { f[2] = true; return f; })), "no buffs for a rival");
    }

    @Test
    void moodsShiftOnlyWithTheirOptIns() {
        assertFalse(yes(ConvoAction.FLIRT, base()), "no flirting without romance on");
        assertTrue(yes(ConvoAction.FLIRT, with(f -> { f[5] = true; return f; })));
        assertFalse(yes(ConvoAction.INSULT, base()), "no reason to trash talk");
        assertTrue(yes(ConvoAction.INSULT, with(f -> { f[16] = true; return f; })));
        assertFalse(yes(ConvoAction.INSULT, with(f -> { f[2] = true; f[4] = true; return f; })), "@botdrama off");
        assertFalse(yes(ConvoAction.MAKEUP, with(f -> { f[2] = true; return f; })), "no sorry, no makeup");
        assertTrue(yes(ConvoAction.MAKEUP, with(f -> { f[2] = true; f[15] = true; return f; })));
        assertTrue(yes(ConvoAction.APOLOGIZE, base()));
    }

    @Test
    void ownSystemsDecideTheirOwnCapsAndNothingPassesAFlag() {
        for (ConvoAction a : new ConvoAction[]{ConvoAction.PARTY, ConvoAction.TRADE, ConvoAction.HAGGLE,
                ConvoAction.GIFT, ConvoAction.GUILD, ConvoAction.DATE, ConvoAction.FOLLOW}) {
            assertEquals(ConvoGates.Verdict.DELEGATE, ConvoGates.decide(a, base()).verdict(), a.name());
            assertEquals(ConvoGates.Verdict.NO, ConvoGates.decide(a, with(f -> { f[1] = true; return f; })).verdict());
        }
        assertEquals(ConvoGates.Verdict.NO, ConvoGates.decide(ConvoAction.LEAVE_GUILD, base()).verdict());
        assertEquals(ConvoGates.Verdict.NO, ConvoGates.decide(ConvoAction.NONE, base()).verdict());
    }
}
