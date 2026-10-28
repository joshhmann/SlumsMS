package soloMapling.qa;

import java.util.List;

/** The lines cv-12 (t-cv-11) sends to the chat-model endpoints. */
public final class ConvoSplitLines {
    private ConvoSplitLines() {
    }

    /** Lines no rule settles (intent, tone and con all left to the model); the test pins that. */
    public static final List<String> LINES = List.of(
            "hey do you feel like going out to grind some mobs together later",
            "honestly you are the worst player i have ever met in this game",
            "that was really kind of you to share the loot with me yesterday",
            "i am thinking about what to do with my weekend maybe fishing");
}
