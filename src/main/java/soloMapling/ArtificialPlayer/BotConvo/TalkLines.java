package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
import soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Answer;
import soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Manner;
import soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Move;
import soloMapling.ArtificialPlayer.BotStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * 1.6.1 (ticket 114): the words of a bot-to-bot conversation, from its intents. The line bank answers what it has
 * (greetings, small talk, the yes / no of a party, help or come-along, goodbyes) in the bot's voice and in the
 * manner's column (warm = friend, curt = neutral, rude = rival); the asks, counters and grounded small talk are short
 * lines here. A line whose slots cannot be filled is never said with a hole in it. PG-13 by construction: nothing here
 * swears past "damn", and the bank is screened at build time.
 */
public final class TalkLines {

    private TalkLines() {
    }

    /** A worded line and where it came from ("bank" or "lines"). */
    public record Worded(String text, String source) {
    }

    // built-in lines, per manner: WARM, CURT, RUDE
    static final Map<String, String[][]> LINES = new HashMap<>();

    static {
        put("greet", new String[]{"hey {name}!", "oh hey {name} :)", "{name}!! hii", "yo {name} whats up"},
                new String[]{"hey", "yo {name}", "sup", "oh hi"},
                new String[]{"oh great its {name}", "ugh, {name}", "look who it is", "{name}. again"});
        put("greet_group", new String[]{"hey guys!", "yo everyone :)", "hii all"},
                new String[]{"sup guys", "yo", "hey all"},
                new String[]{"ugh this crowd again", "oh its u lot", "great, everyone's here"});
        put("greet_back", new String[]{"heyy {name}", "hi {name}!", "yo yo"},
                new String[]{"hey", "sup", "yo"},
                new String[]{"what do u want", "oh. hi", "hm"});
        put("small", new String[]{"{map} is so packed today lol", "how's lvl {lv} treating u?", "nice seeing u around {map}"},
                new String[]{"{map} again huh", "lvl {lv} now?", "slow day"},
                new String[]{"still lvl {lv}? lol", "why are u always in {map}", "u again in {map}, figures"});
        put("small_met", new String[]{"how'd it go after {about}?", "still thinking about {about} lol", "{about} was fun"},
                new String[]{"so after {about}...", "u still on {about}?", "remember {about}"},
                new String[]{"still salty about {about}", "dont bring up {about}", "{about}, really?"});
        put("ask_party", new String[]{"wanna party up and grind?", "party with me? more exp together", "duo? ill send inv"},
                new String[]{"party?", "wanna duo", "pt?"},
                new String[]{"u can party with me i guess", "party, if u can keep up", "fine, duo?"});
        put("ask_party_pq", new String[]{"wanna do {pq} with me?", "{pq} together? :)", "need 1 more for {pq}, u in?"},
                new String[]{"{pq}?", "lf1 {pq}, u?", "{pq} run?"},
                new String[]{"{pq}, u coming or what", "{pq}. dont be slow", "need a body for {pq}, u'll do"});
        put("ask_help", new String[]{"could u help me train at {spot}? pls", "help me at {spot} for a bit?", "ur stronger, help me at {spot}?"},
                new String[]{"help at {spot}?", "carry me at {spot}?", "come help, {spot}"},
                new String[]{"u owe me, help at {spot}", "come help me at {spot} already", "help me at {spot}, its not hard"});
        put("ask_come", new String[]{"come with me to {town}?", "lets go hang in {town} :)", "{town}? come along"},
                new String[]{"{town}?", "going {town}, coming?", "u coming to {town}"},
                new String[]{"im going {town}, keep up", "{town}. move", "follow me to {town} or dont"});
        put("ask_trade", new String[]{"wanna buy my {item}? {price}", "i got {item}, {price} for u", "{item} for {price}? friend price"},
                new String[]{"{item}, {price}?", "selling {item} {price}", "{item} {price} u want?"},
                new String[]{"{item}, {price}, take it or leave it", "{price} for {item}, no haggling", "buy my {item}. {price}"});
        put("ask_gossip", new String[]{"omg did u hear? {about}", "u hear about {about}??", "ok so {about} lol"},
                new String[]{"heard {about}", "so {about}", "{about}, u know?"},
                new String[]{"lol {about}, figures", "{about}. typical", "{about}, what a mess"});
        put("ask_group_come", new String[]{"anyone wanna come to {town}?", "lets all go {town}!", "{town} anyone? :)"},
                new String[]{"{town}, anyone?", "going {town}", "who's coming {town}"},
                new String[]{"im going {town}, whoever", "{town}. come or dont", "{town}, if any of u can keep up"});
        put("ask_group_party", new String[]{"anyone wanna party up?", "party anyone? :)", "who wants to duo"},
                new String[]{"party?", "pt anyone", "anyone party"},
                new String[]{"party, if any of u are any good", "need a party, whoever", "pt. dont all jump at once"});
        put("yes_trade", new String[]{"deal! {price} works", "sure, ill take it", "ok deal :)"},
                new String[]{"k deal", "ok", "fine, deal"},
                new String[]{"fine. {price}, not a meso more", "ugh ok", "whatever, deal"});
        put("no_trade", new String[]{"aw nah, cant afford it rn", "maybe later? not rn", "ill pass this time"},
                new String[]{"no", "pass", "nah"},
                new String[]{"lol no", "at {price}? no way", "nope, rip off"});
        put("yes_gossip", new String[]{"no way!! really?", "omg seriously", "wait what lol"},
                new String[]{"huh", "ok", "figures"},
                new String[]{"lol of course", "not surprised", "pfft"});
        put("no_gossip", new String[]{"eh i dont really care lol", "lets not gossip", "hm idk about that"},
                new String[]{"dont care", "k", "whatever"},
                new String[]{"who cares", "stop gossiping", "boring"});
        put("counter_come", new String[]{"how about {town} instead?", "nah lets do {town} :)", "{town}? i was going there"},
                new String[]{"{town} instead", "rather {town}", "{town}?"},
                new String[]{"no, {town}. obviously", "{town} or nothing", "nah, {town}, keep up"});
        put("reply_yes", new String[]{"yay ok!", "nice :)", "sweet lets go"},
                new String[]{"k", "ok", "cool"},
                new String[]{"fine", "ugh fine", "whatever"});
        put("reply_no", new String[]{"aw ok, next time", "np!", "all good"},
                new String[]{"k", "ok then", "fair"},
                new String[]{"ur loss", "fine, whatever", "didnt want u anyway"});
        put("yes_generic", new String[]{"sure!", "ya ok :)", "sounds good"},
                new String[]{"k", "ok", "sure"},
                new String[]{"fine", "ugh ok", "if i must"});
        put("no_generic", new String[]{"sorry, cant rn", "aw not now", "maybe later?"},
                new String[]{"no", "nah", "cant"},
                new String[]{"no", "lol no", "not happening"});
        put("bye", new String[]{"cya {name}!", "later :)", "bye bye"},
                new String[]{"cya", "later", "bye"},
                new String[]{"bye. finally", "later i guess", "go away then"});
    }

    private static void put(String key, String[] warm, String[] curt, String[] rude) {
        LINES.put(key, new String[][]{warm, curt, rude});
    }

    /** The bank key for an intent's line, or null when only the built-in lines say it. */
    static String bankKey(TalkIntent i) {
        if (i.move() == Move.GREET) {
            return "greeting";
        }
        if (i.move() == Move.BYE) {
            return "bye";
        }
        if (i.move() == Move.ANSWER && i.ask() != null && i.answer() != Answer.COUNTER) {
            boolean yes = i.answer() == Answer.YES;
            return switch (i.ask()) {
                case PARTY -> yes ? "party_yes" : "party_no";
                case HELP -> yes ? "help_yes" : "help_no";
                case COME_ALONG -> yes ? "come_yes" : "come_no";
                case TRADE -> yes ? null : "trade_no";
                case GOSSIP -> null;
            };
        }
        return null;
    }

    /** The built-in key for an intent (always has lines). */
    static String builtInKey(TalkIntent i, boolean group, boolean pq, boolean met, boolean answering) {
        return switch (i.move()) {
            case GREET -> answering ? "greet_back" : group ? "greet_group" : "greet";
            case SMALL_TALK -> met ? "small_met" : "small";
            case ASK -> switch (i.ask()) {
                case PARTY -> group ? "ask_group_party" : pq ? "ask_party_pq" : "ask_party";
                case HELP -> "ask_help";
                case COME_ALONG -> group ? "ask_group_come" : "ask_come";
                case TRADE -> "ask_trade";
                case GOSSIP -> "ask_gossip";
            };
            case ANSWER -> i.answer() == Answer.COUNTER ? "counter_come"
                    : i.ask() == TalkIntent.Ask.TRADE ? (i.answer() == Answer.YES ? "yes_trade" : "no_trade")
                    : i.ask() == TalkIntent.Ask.GOSSIP ? (i.answer() == Answer.YES ? "yes_gossip" : "no_gossip")
                    : i.answer() == Answer.YES ? "yes_generic" : "no_generic";
            case REPLY -> i.answer() == Answer.YES ? "reply_yes" : "reply_no";
            case BYE -> "bye";
        };
    }

    /**
     * The line for {@code intent}, said by {@code speaker} to {@code to} (null: the group). {@code slots} fill the
     * built-in lines ({@code name}, {@code town}, {@code pq}, {@code item}, {@code price}, {@code about}, {@code map},
     * {@code lv}, {@code spot}). {@code bankFirst}: the bank is tried first when it has the key. Never null.
     */
    public static Worded line(Character speaker, Character to, TalkIntent intent, Map<String, String> slots,
                              boolean group, boolean pq, boolean met, boolean answering, boolean bankFirst,
                              Random rng) {
        String key = bankKey(intent);
        // a greeting back is the bank's greeting too; a group's opener has its own lines
        if (key != null && bankFirst && !(intent.move() == Move.GREET && group && !answering)) {
            String bank = bank(key, speaker, to, intent.manner(), intent.answer() == Answer.YES, rng);
            if (bank != null) {
                return new Worded(bank, "bank");
            }
        }
        String text = builtIn(builtInKey(intent, group, pq, met, answering), intent.manner(), slots, rng);
        return new Worded(text, "lines");
    }

    /** A built-in line with its slots filled; a line with an unfilled slot is skipped (the last resort is "ok"). */
    static String builtIn(String key, Manner manner, Map<String, String> slots, Random rng) {
        String[][] byManner = LINES.get(key);
        if (byManner == null) {
            return "ok";
        }
        String[] pool = byManner[manner == null ? 1 : manner.ordinal()];
        int start = rng.nextInt(pool.length);
        for (int k = 0; k < pool.length; k++) {
            String filled = fill(pool[(start + k) % pool.length], slots);
            if (filled != null) {
                return filled;
            }
        }
        for (String[] other : byManner) { // another manner's line rather than a hole
            for (String raw : other) {
                String filled = fill(raw, slots);
                if (filled != null) {
                    return filled;
                }
            }
        }
        return "ok";
    }

    /** {@code raw} with every {@code {slot}} filled, or null when a slot has no value. */
    static String fill(String raw, Map<String, String> slots) {
        Matcher m = ChatBank.SLOT.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v = slots == null ? null : slots.get(m.group(1));
            if (v == null || v.isBlank()) {
                return null;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(v));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Bank keys whose yes lines may carry a claim (the act follows the line: an invite, a trip). */
    static final Set<String> CLAIMS_ON_YES = Set.of("party_yes", "help_yes", "come_yes");

    /**
     * 1.6.1 say-do: the claims a bank line for this key may make: what the act after a yes really does. A come-along
     * yes travels ("omw"); a help yes parties and grinds on the same map, so it may offer the party but never "omw"
     * (it is already here: TEST 2026-10-28 counted "coming" claims nobody walked for); a no, a greeting or a goodbye
     * claims nothing ("hold on", "brb" in a refusal are promises nobody keeps).
     */
    static java.util.Set<ClaimRules.Kind> allowedClaims(String key, boolean yes) {
        if (!yes || !CLAIMS_ON_YES.contains(key)) {
            return java.util.EnumSet.noneOf(ClaimRules.Kind.class);
        }
        return "help_yes".equals(key) ? java.util.EnumSet.of(ClaimRules.Kind.PARTY_OFFER, ClaimRules.Kind.PARTY_ASK)
                : ConvoLines.claimsFor(key);
    }

    /**
     * Whether every claim the line makes now (the rules of this build, weak ones too: a refusal's "hold on" is still
     * read as a brb) is one of {@code allowed}.
     */
    static boolean claimsFit(String text, java.util.Set<ClaimRules.Kind> allowed) {
        for (ClaimRules.Claim c : ClaimRules.classify(text)) {
            if (!allowed.contains(c.kind())) {
                return false;
            }
        }
        return true;
    }

    /** A bank line for {@code key} in the manner's column and the bot's voice, or null. */
    static String bank(String key, Character speaker, Character to, Manner manner, boolean yes, Random rng) {
        if (!ConvoSwitches.on("bank") || speaker == null) {
            return null;
        }
        ChatBank bank = ChatBank.get();
        if (bank == null) {
            return null;
        }
        try {
            int slot = speaker.getId() - BotStore.BOT_ID_BASE;
            ConvoPersona persona = ConvoPersona.of(slot);
            Map<String, String> slots = ConvoLines.slots(speaker, to);
            java.util.Set<ClaimRules.Kind> allowed = allowedClaims(key, yes);
            long claims = ChatBank.claimMask(allowed);
            ChatBank.Query q = new ChatBank.Query(key, manner == null ? "neutral" : manner.tone,
                    manner == null ? "neutral" : manner.affinity, persona.voice(), ConvoLines.situation(speaker, false),
                    slots, claims);
            RecentLines.Window window = new RecentLines.Window(to == null ? 0 : to.getId(), speaker.getId());
            ChatBank.Pick pick = bank.pick(q, window, ChatBank.Weights.NONE, rng);
            if (pick == null) {
                return null;
            }
            if (!claimsFit(pick.text(), allowed)) {
                return null; // the pack's claim tags are older than the rules: the built-in line answers
            }
            RecentLines.used(to == null ? 0 : to.getId(), speaker.getId(), pick.id());
            return pick.text();
        } catch (RuntimeException | LinkageError e) {
            return null; // a slot read failed: the built-in line answers
        }
    }

    /** Every built-in line, raw (the content test screens them). */
    static List<String> allBuiltIns() {
        List<String> out = new java.util.ArrayList<>();
        for (String[][] byManner : LINES.values()) {
            for (String[] pool : byManner) {
                out.addAll(List.of(pool));
            }
        }
        return out;
    }
}
