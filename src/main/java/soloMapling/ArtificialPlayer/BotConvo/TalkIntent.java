package soloMapling.ArtificialPlayer.BotConvo;

import java.util.Locale;

/**
 * 1.6.1 (owner 2026-10-27, ticket 114): what a bot means by a line it says to another bot. The speaker's brain chose
 * it, so it travels with the line ({@link BotTalk}, {@link BotLineIntents}) and the listener's brain answers it
 * directly: a bot line never goes through the chat classifier (ConvoSense / Decide).
 *
 * <p>The moves follow the owner's tree: greet, small talk, an ask (party, trade, help, come along, gossip), the other
 * bot's answer (yes, no or a counter) in a manner (warm, curt, rude), then the act and a goodbye.
 */
public record TalkIntent(Move move, Ask ask, Answer answer, Manner manner, int toId, String about) {

    /** Where in the conversation the line is. */
    public enum Move { GREET, SMALL_TALK, ASK, ANSWER, REPLY, BYE }

    /** What a bot can ask another bot for; each is carried out by an existing action. */
    public enum Ask {
        /** Party up: a PQ party ({@link LfgParty}) when both fit a lobby, else a plain duo party. */
        PARTY,
        /** Buy or sell a potion or a common item ({@code BotBotTrade}). */
        TRADE,
        /** Come help me train (the helper's daily life goes to the asker's map: HELP_FRIEND). */
        HELP,
        /** Come along to a town (both bots' daily life heads there). */
        COME_ALONG,
        /** "Did u hear ..." - a true story ({@code BotGossip}); the listener may keep it. */
        GOSSIP
    }

    /** The listener's answer to an ask. */
    public enum Answer { YES, NO, COUNTER }

    /** How the line is said; the line bank's affinity column carries it (friend / neutral / rival). */
    public enum Manner {
        WARM("kind", "friend"), CURT("neutral", "neutral"), RUDE("rude", "rival");

        public final String tone;
        public final String affinity;

        Manner(String tone, String affinity) {
            this.tone = tone;
            this.affinity = affinity;
        }
    }

    public static TalkIntent greet(Manner manner, int toId) {
        return new TalkIntent(Move.GREET, null, null, manner, toId, null);
    }

    public static TalkIntent smallTalk(Manner manner, int toId, String about) {
        return new TalkIntent(Move.SMALL_TALK, null, null, manner, toId, about);
    }

    public static TalkIntent ask(Ask ask, Manner manner, int toId, String about) {
        return new TalkIntent(Move.ASK, ask, null, manner, toId, about);
    }

    public static TalkIntent answer(Ask ask, Answer answer, Manner manner, int toId, String about) {
        return new TalkIntent(Move.ANSWER, ask, answer, manner, toId, about);
    }

    public static TalkIntent bye(Manner manner, int toId) {
        return new TalkIntent(Move.BYE, null, null, manner, toId, null);
    }

    /** "ASK PARTY warm", "ANSWER COME_ALONG COUNTER rude" (logs, trace, QA). */
    public String label() {
        StringBuilder b = new StringBuilder(move.name());
        if (ask != null) {
            b.append(' ').append(ask.name());
        }
        if (answer != null) {
            b.append(' ').append(answer.name());
        }
        if (manner != null) {
            b.append(' ').append(manner.name().toLowerCase(Locale.ROOT));
        }
        return b.toString();
    }
}
