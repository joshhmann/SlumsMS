package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage;
import soloMapling.ArtificialPlayer.BotConvo.StateOfMind;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * m02 round 3, TEST cv-13 2026-11-01: a player asked "what r u doing" and the bot answered "go train elsewhere ^^".
 * The Decide model had called that question a con, so the flagged branch sent the con key's bank line - which answers
 * nothing. A flagged line the rules did not flag, that only asks what the bot is doing, is answered with what the bot
 * is doing; a rule-flagged line ("kys", a phish) keeps the brush-off.
 */
class FlaggedActivityAnswerTest {

    private static final int HENESYS = 100000000;
    private static final int HUNTING_GROUND = 1000002;

    private static StateOfMind.Snapshot inHenesys() {
        return StateOfMind.builder().bot("spear4", 40, "spearman")
                .here(new StateOfMind.Place(HENESYS, "Henesys", HENESYS), List.of())
                .doing("hanging out in town", null)
                .player("Josh", 75, "chief bandit", false)
                .build();
    }

    private static StateOfMind.Snapshot onHuntingGround() {
        return StateOfMind.builder().bot("spear4", 40, "spearman")
                .here(new StateOfMind.Place(HUNTING_GROUND, "Henesys Hunting Ground I", HENESYS),
                        List.of("Snail", "Slime"))
                .doing("grinding", null)
                .player("Josh", 75, "chief bandit", false)
                .build();
    }

    private static StateOfMind.Snapshot noPlace() {
        return StateOfMind.builder().bot("spear4", 40, "spearman")
                .doing("grinding", null)
                .player("Josh", 75, "chief bandit", false)
                .build();
    }

    @Test
    void aModelFlaggedActivityQuestionIsAnsweredWithWhereTheBotIs() {
        String said = BotConversation.flaggedAnswer(inHenesys(), "what r u doing", ChatTriage.Source.DECIDE);
        assertEquals("just chillin in henesys rn", said,
                "a flagged line that only asks what the bot is doing is answered with the bot's place");
    }

    @Test
    void aModelFlaggedActivityQuestionOnAGrindMapIsAnsweredWithTheMobs() {
        String said = BotConversation.flaggedAnswer(onHuntingGround(), "what r u doing rn", ChatTriage.Source.DECIDE);
        assertEquals("killing snails here", said);
    }

    @Test
    void aRuleFlaggedLineKeepsTheBrushOff() {
        assertNull(BotConversation.flaggedAnswer(inHenesys(), "what r u doing", ChatTriage.Source.RULE),
                "the rules flagged it: no activity answer, the con bank line stands");
        assertNull(BotConversation.flaggedAnswer(inHenesys(), "what r u doing kys", ChatTriage.Source.RULE));
    }

    @Test
    void withoutAPlaceTheAnswerIsStillAnActivityAnswer() {
        String said = BotConversation.flaggedAnswer(noPlace(), "what r u doing", ChatTriage.Source.DECIDE);
        assertNotNull(said, "a flagged line that only asks what the bot is doing never gets a dismissive bank line");
        assertEquals("chatting with u rn", said);
    }

    @Test
    void aFlaggedLineThatDoesNotAskWhatTheBotIsDoingIsUntouched() {
        assertNull(BotConversation.flaggedAnswer(inHenesys(), "give me free mesos", ChatTriage.Source.DECIDE));
        assertNull(BotConversation.flaggedAnswer(inHenesys(), "go train elsewhere", ChatTriage.Source.DECIDE));
    }
}
