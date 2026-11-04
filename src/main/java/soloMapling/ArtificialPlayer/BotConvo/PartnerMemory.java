package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
import soloMapling.ArtificialPlayer.BotEpisodes;
import soloMapling.ArtificialPlayer.BotMemory;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialSim;
import soloMapling.ArtificialPlayer.BotStore;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * Stage 3 (ticket 181): what one bot REALLY remembers about the bot it is talking to, for the bot-to-bot talk paths
 * ({@link BotThreads} and {@link BotTalk}). The notes come from {@link BotMemory} - written only by the server's own
 * events ({@link BotEpisodes#botEvent}, world news, bond events), never by the model - and reach the prompt as facts the
 * lines may refer to, or the bank as a callback line ("remember when u partied with me"). So a conversation points at a
 * real shared past instead of an invented one.
 *
 * <p>Reads only what is already in memory ({@link BotMemory#recallIfCached}): a prompt is built on the director's tick,
 * which never waits on the database; a pair not yet loaded is loaded in the background and its memories show up the next
 * time those two talk. Any trouble means "no memory": the prompt and the bank are exactly what they were. A note whose
 * text names someone besides the two speakers is dropped (the name guard), so a memory can never bring in a third party.
 */
final class PartnerMemory {

    /** Notes of one bot about the other that a prompt gets, at most. */
    static final int LIMIT = 3;
    /** The kinds of thing a bot brings up as "remember when": things the two really did or that went wrong between them. */
    static final Set<BotMemory.Kind> CALLBACK_KINDS = EnumSet.of(BotMemory.Kind.PARTY, BotMemory.Kind.HELP,
            BotMemory.Kind.TRADE, BotMemory.Kind.GIFT, BotMemory.Kind.FAVOR, BotMemory.Kind.SCAM, BotMemory.Kind.ASKED);

    /** Where the notes come from (a seam for tests): the speaker's and the partner's character ids. */
    @FunctionalInterface
    interface Source {
        List<BotMemory.Note> recall(int speakerCharId, int partnerCharId, BotMemory.Query q, long now, int limit);
    }

    static volatile Source source = (speaker, partner, q, now, limit) ->
            BotMemory.recallIfCached(speaker - BotStore.BOT_ID_BASE, partner, q, now, limit);
    /** A map's short place name in lower case, or null (a seam for tests). */
    static volatile IntFunction<String> places = BotEpisodes::placeName;

    private PartnerMemory() {
    }

    /**
     * The notes {@code speaker} holds about {@code partner} that fit what is being talked about ({@code topic}, free
     * text) and where they stand, best first, at most {@link #LIMIT}; slots excluded; none on any trouble. Stamps them
     * as used.
     */
    static List<BotMemory.Note> about(Character speaker, Character partner, String topic, long now) {
        try {
            BotMemory.Query q = BotMemory.Query.of(null, 0, speaker.getMapId(), topic, 0);
            List<BotMemory.Note> notes = source.recall(speaker.getId(), partner.getId(), q, now, LIMIT);
            List<BotMemory.Note> out = new ArrayList<>(notes.size());
            Set<String> allowed = Set.of(speaker.getName(), partner.getName());
            for (BotMemory.Note n : notes) {
                if (NameGuard.check(n.text(), allowed).clean()) {
                    out.add(n);
                }
            }
            return out;
        } catch (RuntimeException | LinkageError e) {
            return List.of();
        }
    }

    /**
     * The text a thread's or talk's prompt gets about these two, sentences joined without a closing period ("" when
     * neither remembers anything): what each remembers about the other, as the facts a line may refer to, and the rule
     * that nothing else about their past exists. Memory only.
     */
    static String clause(Character a, Character b, String topic, long now) {
        String ofB = describe(about(a, b, topic, now));
        String ofA = describe(about(b, a, topic, now));
        if (ofB.isEmpty() && ofA.isEmpty()) {
            return "";
        }
        List<String> sentences = new ArrayList<>();
        sentences.add("Their real shared history (the only past they have; a line may refer to one thing from it, in the"
                + " speaker's own words, and must not invent any other favor, fight or shared past)");
        if (!ofB.isEmpty()) {
            sentences.add(a.getName() + " remembers " + b.getName() + ": " + ofB);
        }
        if (!ofA.isEmpty()) {
            sentences.add(b.getName() + " remembers " + a.getName() + ": " + ofA);
        }
        return String.join(". ", sentences);
    }

    /** Notes as one phrase list: "partied with me in ellinia (felt good); heard they killed Zakum". */
    static String describe(List<BotMemory.Note> notes) {
        List<String> parts = new ArrayList<>();
        for (BotMemory.Note n : notes) {
            parts.add(phrase(n));
        }
        return String.join("; ", parts);
    }

    private static String phrase(BotMemory.Note n) {
        StringBuilder s = new StringBuilder(n.text());
        String place = n.locationMapId() > 0 ? places.apply(n.locationMapId()) : null;
        if (place != null && !place.isBlank()) {
            s.append(" in ").append(place);
        }
        if (n.valence() >= 0.3) {
            s.append(" (felt good)");
        } else if (n.valence() <= -0.3) {
            s.append(" (felt bad)");
        }
        return s.toString();
    }

    /**
     * A "remember when" line {@code speaker} may say to {@code partner} from its strongest real shared memory of them
     * (a party, a trip, help, a trade, a gift, a refused ask), in the speaker's words; null when it has none worth
     * saying. Own memory only (never hearsay), and the line names nobody.
     */
    static String callback(Character speaker, Character partner, long now) {
        try {
            BotMemory.Query q = new BotMemory.Query(CALLBACK_KINDS, 0, speaker.getMapId(), Set.of(), 0);
            List<BotMemory.Note> notes = source.recall(speaker.getId(), partner.getId(), q, now, LIMIT);
            Set<String> allowed = Set.of(speaker.getName(), partner.getName());
            for (BotMemory.Note n : notes) {
                if (n.source() != BotMemory.Source.DIRECT || !CALLBACK_KINDS.contains(n.kind())
                        || !NameGuard.check(n.text(), allowed).clean()) {
                    continue;
                }
                String text = n.text().toLowerCase(Locale.ROOT);
                String place = n.locationMapId() > 0 ? places.apply(n.locationMapId()) : null;
                String at = place == null || place.isBlank() ? "" : " in " + place;
                return n.valence() < -0.1 ? "ngl i still remember when u " + text + at
                        : "remember when u " + text + at + (n.valence() > 0.2 ? " lol" : "");
            }
            return null;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** The chance a greeting between two bots with a shared past opens with a "remember when" line instead of a hello. */
    static final double CALLBACK_CHANCE = 0.5;

    /**
     * The first friendly greeting of a planned talk between two bots that really share a past may become a "remember
     * when" line from {@link #callback} ({@code SocialSim} draws the chance per speaker). Marks the step's source
     * {@code memory}. One swap per plan; true when it happened. Never throws.
     */
    static boolean greetFromMemory(TalkPlanner.Plan plan, long now) {
        try {
            for (TalkPlanner.Step s : plan.steps) {
                if (s.intent.move() != TalkIntent.Move.GREET || s.to == null
                        || s.intent.manner() == TalkIntent.Manner.RUDE) {
                    continue;
                }
                if (SocialSim.random(s.speaker.getId(), "memory-callback").nextDouble() >= CALLBACK_CHANCE) {
                    continue;
                }
                String line = callback(s.speaker, s.to, now);
                if (line != null) {
                    s.text = line;
                    s.source = "memory";
                    return true;
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // no memory line: the planned greeting stands
        }
        return false;
    }

    /** Test seam: where notes come from and map names (nulls restore the real ones). */
    static void useForTest(Source testSource, IntFunction<String> testPlaces) {
        source = testSource != null ? testSource : (speaker, partner, q, now, limit) ->
                BotMemory.recallIfCached(speaker - BotStore.BOT_ID_BASE, partner, q, now, limit);
        places = testPlaces != null ? testPlaces : BotEpisodes::placeName;
    }
}
