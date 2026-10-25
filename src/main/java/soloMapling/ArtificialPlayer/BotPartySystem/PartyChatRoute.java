package soloMapling.ArtificialPlayer.BotPartySystem;

import soloMapling.ArtificialPlayer.BotMessagingSystem.BotAddressee;
import soloMapling.ArtificialPlayer.BotMessagingSystem.MapChatLog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Ticket 85: which party bot answers a player's party-chat line, and whether it does. Pure. The owner's map-chat
 * order (name, facing, clear reply, closest, nobody) adapted to a party, which has no hearing bubble and no facing:
 * <ol>
 *   <li><b>A name wins</b> ({@link BotAddressee}, the same fuzzy match map chat uses).</li>
 *   <li><b>A clear reply</b> to the party's last bot line (under {@link #REPLY_MS} old): the line asks something, is
 *   a short acknowledgement, or shares a word with it. The same bot answers.</li>
 *   <li><b>Otherwise the closest bot on the player's map</b> (any party bot when none is on the map).</li>
 *   <li>Exactly one bot answers a line. An unaddressed line may get no answer at all: it is just party chat.</li>
 * </ol>
 */
public final class PartyChatRoute {

    public static final long REPLY_MS = TimeUnit.SECONDS.toMillis(60);

    private static final Set<String> ACKS = Set.of("ok", "okay", "k", "kk", "ya", "yes", "yeah", "yep", "yup", "no", "nope",
            "nah", "sure", "lol", "thx", "ty", "thanks", "np", "gg", "gl", "ready", "rdy", "go", "omw", "brb", "wait",
            "wtf", "same", "true", "nice", "oh", "ah");
    private static final Set<String> ALL_WORDS = Set.of("guys", "everyone", "anyone", "any1", "all", "party", "team",
            "yall", "folks");

    public enum Step {
        NAME("1-name"), REPLY("2-reply"), CLOSEST("3-closest"), ANY("3-any"), NONE("4-none");

        public final String label;

        Step(String label) {
            this.label = label;
        }
    }

    /** A bot in the party. {@code distance}: px from the player, or Integer.MAX_VALUE when on another map. */
    public record Member(int id, String name, int distance) {
        boolean sameMap() {
            return distance != Integer.MAX_VALUE;
        }
    }

    /** The party's last bot line: who said it, what, when (botId -1 for none). */
    public record Last(int botId, String line, long at) {
        public static final Last NONE = new Last(-1, "", 0);
    }

    public record Choice(Step step, int botId) {
        public boolean answers() {
            return botId >= 0;
        }
    }

    private PartyChatRoute() {
    }

    public static Choice route(String line, List<Member> bots, Last last, long now, Random rng) {
        if (bots == null || bots.isEmpty() || line == null || line.isBlank()) {
            return new Choice(Step.NONE, -1);
        }
        List<BotAddressee.Candidate> cands = new ArrayList<>();
        for (Member m : bots) {
            cands.add(new BotAddressee.Candidate(m.id(), m.name()));
        }
        BotAddressee.Result named = BotAddressee.resolve(line, cands);
        if (named.best() != null && (named.sure() || named.worthAsking())) {
            return new Choice(Step.NAME, named.best().id());
        }
        if (last != null && last.botId() >= 0 && now - last.at() <= REPLY_MS && contains(bots, last.botId())
                && fitsReply(line, last.line())) {
            return new Choice(Step.REPLY, last.botId());
        }
        List<Member> onMap = new ArrayList<>();
        for (Member m : bots) {
            if (m.sameMap()) {
                onMap.add(m);
            }
        }
        if (!onMap.isEmpty()) {
            onMap.sort(Comparator.comparingInt(Member::distance).thenComparingInt(Member::id));
            return new Choice(Step.CLOSEST, onMap.get(0).id());
        }
        return new Choice(Step.ANY, bots.get(rng.nextInt(bots.size())).id());
    }

    static boolean contains(List<Member> bots, int id) {
        for (Member m : bots) {
            if (m.id() == id) {
                return true;
            }
        }
        return false;
    }

    /** The line answers the bot's last party line: a question, a short acknowledgement, or a shared word. */
    static boolean fitsReply(String line, String botLine) {
        String l = line.trim().toLowerCase(Locale.ROOT);
        String[] tokens = l.split("[^a-z0-9?]+");
        if (l.contains("?") || tokens.length <= 5 && anyAck(tokens)) {
            return true;
        }
        Set<String> theirs = MapChatLog.words(botLine);
        for (String w : MapChatLog.words(line)) {
            if (theirs.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyAck(String[] tokens) {
        for (String t : tokens) {
            if (ACKS.contains(t)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The chance the chosen bot actually answers. A name or a reply always gets an answer; a line to the whole party
     * (a question, a greeting, "guys") nearly always; ordinary chatter is answered now and then, more when the party
     * has a single bot (the player is plainly talking to it).
     */
    public static double answerChance(Step step, String line, int botCount) {
        if (step == Step.NAME || step == Step.REPLY) {
            return 1.0;
        }
        if (step == Step.NONE) {
            return 0.0;
        }
        String l = " " + line.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9? ]", " ") + " ";
        boolean toAll = l.contains("?") || l.matches(".* (hi|hey|yo|sup|hello|gm|gn|ready|rdy|omw|brb|wait|go) .*");
        for (String w : ALL_WORDS) {
            if (l.contains(" " + w + " ")) {
                toAll = true;
            }
        }
        if (toAll) {
            return botCount <= 1 ? 0.95 : 0.85;
        }
        return botCount <= 1 ? 0.5 : 0.3;
    }
}
