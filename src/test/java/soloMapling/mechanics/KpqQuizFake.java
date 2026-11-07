package soloMapling.mechanics;

import java.util.function.ToIntFunction;

/** kpq1-questions: a test world's settings for Cloto's live answers - five channels and EXP x3, as live has today. */
final class KpqQuizFake {

    static final ToIntFunction<String> WORLD = key -> switch (key) {
        case KpqQuiz.CHANNELS -> 5;
        case KpqQuiz.EXP_RATE -> 3;
        default -> -1;
    };

    private KpqQuizFake() {
    }
}
