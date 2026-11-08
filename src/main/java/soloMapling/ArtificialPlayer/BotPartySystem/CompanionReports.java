package soloMapling.ArtificialPlayer.BotPartySystem;

// Ported from https://github.com/Incluwin/BotStory.git
//   src/main/java/server/agents/capabilities/dialogue/AgentDialogueReportFormatter.java (pots, stats)
//   src/main/java/server/agents/capabilities/dialogue/AgentInventoryDialogueReporter.java (slots)
//   src/main/java/server/agents/capabilities/dialogue/AgentSupplyDialogueReporter.java (the low-supply note)
//   src/main/java/server/agents/commands/AgentCommandTypoSuggester.java ("did you mean")
// and from https://github.com/Kiwiism/cosmic_agents.git
//   src/main/java/server/agents/dialogue/AgentDialogueCatalog.java (the confirm prompt)
//   src/main/java/server/agents/dialogue/AgentPendingChatActionFlow.java (one pending action per player)
//   src/main/java/server/agents/chat/AgentChatCommandClassifier.java (the yes words)
//   src/main/java/server/agents/inventory/AgentInventoryDropService.java (the drop-limited-map refusal)
// @ 81a3b1dfb3b6ec1619be4ced43ae0a8b6882b364 and 3c7e094357329932734a860e5a831d57add27a00
// changed: one class for both kinds of companion, everything behind PartyCompanionPolicy.Feature.CHAT_REPORTS;
// counts come from our own inventory code (BotVitals, Inventory); the typo dictionary is our parser's verbs, not
// BotStory's, and the suggestion only fires when the line is addressed to a companion; one pending drop confirm per
// player, 30 s, no item-choice or skill-tree flows.

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotChatPrompt;
import soloMapling.ArtificialPlayer.BotMessagingSystem.BotAddressee;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotSupplies;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals;
import soloMapling.ArtificialPlayer.BotTradeSystem.TradeCategories;
import soloMapling.ArtificialPlayer.BotPartySystem.PartyCompanionPolicy.Feature;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 1.6.7 (alt-qol-batch group A): the chat side of companions - the reports a player can ask for ("pots", "stats",
 * "slots"), the "did you mean 'grind'?" hint for a mistyped order, the low-pots note on an acknowledgement, and the
 * confirm-before-drop. Every answer goes to the player who asked, never to the map.
 *
 * <p>Wording: a companion talks like the player's friend, never about being anything else. All of it is behind
 * {@link Feature#CHAT_REPORTS}: owned alts on, party regulars off, so a regular behaves exactly as before.
 */
public final class CompanionReports {

    /** Below this many pots (HP, or MP for a job that uses MP) an acknowledgement says how few are left. */
    public static final int LOW_NOTE_AT = 20;

    /** How long "just to confirm, drop my pots?" waits for a yes. */
    public static final long CONFIRM_MS = 30_000L;

    /** How often the same full bag may be named (OwnedAltLoot's window). */
    public static final long FULL_SAY_MS = 300_000L;

    /**
     * cosmic_agents' AgentChatCommandClassifier, tightened: what counts as a yes is the whole line, made of these
     * words and nothing else, so "ok i'll be back in five" is a sentence, not a yes.
     */
    private static final Set<String> YES_WORDS = Set.of("yes", "yep", "yeah", "yea", "y", "ok", "okay", "sure",
            "confirm", "confirming");

    /** Words that may ride along with a yes ("yes please", "ok thanks", "sure then"). */
    private static final Set<String> YES_FILLER = Set.of("please", "pls", "now", "then", "thanks", "thank", "you",
            "k", "kk", "do", "it", "go", "ahead", "for", "of", "course", "absolutely", "fine", "cool", "alright");

    /**
     * BotStory's CHAT_DENYLIST: ordinary chat that sits close to a verb ("hello" -> help, "yes" -> ...). Never
     * corrected.
     */
    private static final Set<String> CHAT_DENYLIST = Set.of("hello", "hey", "yes", "yeah", "yep", "nope", "sure",
            "thanks", "thank", "ok", "okay", "lol", "hmm", "haha", "what", "where", "when", "who", "why", "how");

    private static final Set<String> GREETINGS = Set.of("hey", "yo", "oi", "ok", "okay", "k", "kk", "alright",
            "pls", "plz", "please", "now", "lets");

    private static final Map<Integer, Pending> PENDING = new ConcurrentHashMap<>();
    private static volatile LongSupplier clock = System::currentTimeMillis;

    private CompanionReports() {
    }

    /** A drop waiting for a yes: whose companion, who has to say it, what, and when it was asked. */
    private record Pending(CompanionItems.Companion companion, Character asker, String category, long at) {
    }

    // ── the reports ──

    /** "I have 120 hp pots and 40 mp pots" / "no pots on me rn". */
    public static String pots(Character c) {
        int hp = BotVitals.potionCount(c, true);
        int mp = BotVitals.potionCount(c, false);
        if (hp <= 0 && mp <= 0) {
            return "no pots on me rn";
        }
        if (mp <= 0) {
            return "I have " + hp + " hp pot" + (hp == 1 ? "" : "s") + ", no mp pots";
        }
        if (hp <= 0) {
            return "no hp pots, " + mp + " mp pot" + (mp == 1 ? "" : "s");
        }
        return "I have " + hp + " hp pots and " + mp + " mp pots";
    }

    /** One line: level, job, hp/mp, the four stats, and how much AP is free when any is. */
    public static String stats(Character c) {
        String line = String.format(Locale.ROOT, "lv%d %s, hp %d/%d mp %d/%d, %d str %d dex %d int %d luk",
                c.getLevel(), BotChatPrompt.jobName(c.getJob().getId()),
                c.getHp(), c.getMaxHp(), c.getMp(), c.getMaxMp(),
                c.getStr(), c.getDex(), c.getInt(), c.getLuk());
        int ap = c.getRemainingAp();
        return ap > 0 ? line + ", " + ap + " ap free" : line;
    }

    /** "equip 12/24, use 30/48, etc 31/48" - used slots out of the tab's limit, from the inventory itself. */
    public static String slots(Character c) {
        List<String> out = new ArrayList<>();
        for (InventoryType t : List.of(InventoryType.EQUIP, InventoryType.USE, InventoryType.ETC,
                InventoryType.SETUP)) {
            Inventory bag = c.getInventory(t);
            if (bag == null) {
                continue;
            }
            int total = bag.getSlotLimit();
            out.add(t.name().toLowerCase(Locale.ROOT) + " " + (total - bag.getNumFreeSlot()) + "/" + total);
        }
        return out.isEmpty() ? "no bags on me rn" : String.join(", ", out);
    }

    /** " (only 4 HP pots)" when the companion is nearly out, "" when not. */
    public static String lowNote(Character c) {
        String line = lowLine(c);
        return line.isEmpty() ? "" : " (" + line + ")";
    }

    /** The note without the brackets: "only 4 HP pots", "only 4 HP pots and 3 MP pots". */
    public static String lowLine(Character c) {
        if (c == null) {
            return "";
        }
        int hp = BotVitals.potionCount(c, true);
        boolean usesMp = BotSupplies.needs(c).usesMp();
        int mp = usesMp ? BotVitals.potionCount(c, false) : Integer.MAX_VALUE;
        boolean lowHp = hp < LOW_NOTE_AT;
        boolean lowMp = mp < LOW_NOTE_AT;
        if (!lowHp && !lowMp) {
            return "";
        }
        if (lowHp && lowMp) {
            return "only " + hp + " HP pots and " + mp + " MP pots";
        }
        return lowHp ? "only " + hp + " HP pots" : "only " + mp + " MP pots";
    }

    /**
     * Answers one report verb for {@code companion}, through {@code say} (the existing reply path). False when the
     * switch is off for that companion or the verb is not a report, so the caller can stay quiet.
     */
    public static boolean answer(Character companion, BotCommandParser.Verb verb, Consumer<String> say) {
        if (companion == null || say == null || !on(companion)) {
            return false;
        }
        String line = switch (verb) {
            case POTS -> pots(companion);
            case STATS -> stats(companion);
            case SLOTS -> slots(companion);
            default -> null;
        };
        if (line == null) {
            return false;
        }
        say.accept(line);
        return true;
    }

    // ── "did you mean 'grind'?" ──

    /**
     * The verb a mistyped word near-misses: the first word of the line that is not a greeting or one of
     * {@code ignore} (the companions' own names), within one edit for a 3-5 letter word and two from 6 up (BotStory's
     * rule). Empty for a word that is already a verb, a too-short word, and the chat denylist.
     */
    public static Optional<String> typoSuggestion(String line, Collection<String> ignore) {
        Set<String> known = BotCommandParser.verbWords();
        for (String word : words(line)) {
            if (GREETINGS.contains(word) || (ignore != null && ignore.contains(word))) {
                continue; // a greeting or the companion's name comes first: the word after it is the order
            }
            if (word.length() < 3 || CHAT_DENYLIST.contains(word) || known.contains(word)) {
                return Optional.empty();
            }
            int allowed = word.length() >= 6 ? 2 : 1;
            String best = null;
            int bestDistance = allowed + 1;
            for (String verb : known) {
                if (Math.abs(verb.length() - word.length()) > allowed) {
                    continue;
                }
                int d = distance(word, verb, allowed);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = verb;
                }
            }
            return bestDistance <= allowed ? Optional.ofNullable(best) : Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * The companion a line is addressed to by its first word ("jane grnd"), with the same name rules chat routing
     * uses ({@link BotAddressee}); null when it does not open with one of the names.
     */
    public static BotAddressee.Match addressedTo(String line, Collection<BotAddressee.Candidate> companions) {
        if (companions == null || companions.isEmpty()) {
            return null;
        }
        for (String word : words(line)) {
            BotAddressee.Result r = BotAddressee.resolve(word, companions);
            if (r == null || r.best() == null) {
                return null;
            }
            return r.sure() || r.worthAsking() ? r.best() : null;
        }
        return null;
    }

    /** Bounded Levenshtein: {@code bound + 1} once it is clear the distance is over the bound. */
    static int distance(String a, String b, int bound) {
        if (Math.abs(a.length() - b.length()) > bound) {
            return bound + 1;
        }
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            int rowMin = curr[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, curr[j]);
            }
            if (rowMin > bound) {
                return bound + 1;
            }
            int[] t = prev;
            prev = curr;
            curr = t;
        }
        return Math.min(prev[b.length()], bound + 1);
    }

    // ── confirm before drop ──

    /** True on a map where the ground takes no drops (FieldLimit DROP_LIMIT: the Free Market rooms). */
    public static boolean dropBlocked(Character self) {
        MapleMap map = self == null ? null : self.getMap();
        return map != null && server.maps.FieldLimit.DROP_LIMIT.check(map.getFieldLimit());
    }

    /** What a companion says instead of dropping on such a map. */
    public static String dropBlockedLine() {
        return "drops are off on this map, i can't drop anything here";
    }

    /**
     * Asks first and remembers the yes it waits for (the same player, {@link #CONFIRM_MS}). False when the caller
     * should drop straight away, which is what happens with the switch off for that companion.
     */
    public static boolean confirmFirst(CompanionItems.Companion c, Character asker, String category) {
        Character self = c == null ? null : c.self();
        if (self == null || !on(self)) {
            return false;
        }
        if (dropBlocked(self)) {
            c.tell(dropBlockedLine());
            return true;
        }
        if (asker == null) {
            return true; // nobody to say yes: nothing goes on the ground
        }
        long now = clock.getAsLong();
        PENDING.values().removeIf(old -> now - old.at() > CONFIRM_MS); // nobody knocks anything stale loose
        PENDING.put(asker.getId(), new Pending(c, asker, category, now));
        c.tell("just to confirm, drop my " + TradeCategories.said(category) + "? say yes");
        return true;
    }

    /**
     * The player's reply to a pending "drop my pots?": a yes inside {@link #CONFIRM_MS} drops it and is consumed
     * (true). Anything else cancels it, so the line stays ordinary chat (false).
     */
    public static boolean confirmDrop(Character player, String line) {
        if (player == null || line == null) {
            return false;
        }
        Pending p = PENDING.remove(player.getId());
        if (p == null) {
            return false;
        }
        if (clock.getAsLong() - p.at() > CONFIRM_MS || !isYes(line)) {
            return false;
        }
        // the alt may have walked onto a drop limited map since it asked; there the ground destroys what lands on it
        if (dropBlocked(p.companion().self())) {
            p.companion().tell(dropBlockedLine());
            return true;
        }
        CompanionItems.dropNow(p.companion(), p.category());
        return true;
    }

    /** The whole line is a yes: a yes word somewhere in it, and nothing but yes words and filler. */
    private static boolean isYes(String line) {
        List<String> w = words(line);
        if (w.isEmpty() || w.size() > 6) {
            return false;
        }
        boolean yes = false;
        for (String t : w) {
            if (YES_WORDS.contains(t)) {
                yes = true;
            } else if (!YES_FILLER.contains(t)) {
                return false; // "not", "but", "wait": a sentence, so it cancels
            }
        }
        return yes || (w.contains("go") && (w.contains("ahead") || (w.contains("for") && w.contains("it"))));
    }

    /** Tests only: forget every waiting confirm. */
    static void clearPending() {
        PENDING.clear();
    }

    /** Tests only: the clock the confirm window runs on (null for the real one). */
    static void clock(LongSupplier supplier) {
        clock = supplier == null ? System::currentTimeMillis : supplier;
    }

    private static boolean on(Character companion) {
        return PartyCompanionPolicy.on(Feature.CHAT_REPORTS, companion);
    }

    /** The words of a line, lowercased, punctuation dropped. */
    private static List<String> words(String line) {
        if (line == null) {
            return List.of();
        }
        String text = line.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").trim();
        return text.isEmpty() ? List.of() : List.of(text.split("\\s+"));
    }
}
