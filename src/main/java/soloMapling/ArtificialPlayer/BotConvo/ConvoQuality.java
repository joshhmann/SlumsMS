package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
import soloMapling.qa.QaLog;

/**
 * Ticket 59, owner decision 6: the quality loop (phase 5). For now the !qa view of what a bot said and how it was
 * timed.
 */
public final class ConvoQuality {

    private ConvoQuality() {
    }

    /** A bot line went out to a player. */
    public static void said(Character bot, Character player, String line, ConvoLines.Line source, String tier,
                            ConvoPersona persona, String asked, long ms) {
        if (QaLog.on(QaLog.Topic.CONVO)) { // !qa cv-4
            QaLog.map(QaLog.Topic.CONVO, player.getMapId(), "SAID " + bot.getName() + " -> " + player.getName() + " ["
                    + tier.toLowerCase(java.util.Locale.ROOT) + (source.fromBank() ? " bank#" + source.bankId() : "")
                    + ", " + persona.voice().label() + ", " + ms + " ms] " + line);
        }
    }

    /** A bot-to-bot thread line went out on a map (phase 5 keeps it for !rate and the transcript). */
    public static void threadLine(Character bot, int mapId, String line, String source, String topic) {
    }

    /** How a reply was timed. */
    public static void timing(Character bot, Character player, HumanTiming.Plan plan) {
        if (QaLog.on(QaLog.Topic.CONVO)) { // !qa cv-4
            StringBuilder s = new StringBuilder("TIMING " + bot.getName() + ": " + plan.kind());
            for (HumanTiming.Part part : plan.parts()) {
                s.append(" | +").append(part.delayMs()).append(" ms \"").append(part.text()).append('"');
            }
            QaLog.map(QaLog.Topic.CONVO, player.getMapId(), s.toString());
        }
    }
}
