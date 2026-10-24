package soloMapling.ArtificialPlayer.BotConvo;

import soloMapling.ArtificialPlayer.BotAffinitySystem.ChatTone;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatDecide;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage;
import soloMapling.ArtificialPlayer.BotBrainSystem.ContentGuard;
import soloMapling.ArtificialPlayer.BotBrainSystem.PlayerChatMeaning;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Ticket 59, owner decision 8: the fast read of one player line, before any LLM. Rules first; then ONE call to
 * GLiNER Decide with three small questions (the flat 15-label intent, the 4-label tone, ticket 36's con question) for
 * what the rules left open. Entities are rules ({@link ConvoEntities}): the endpoint has no span question.
 *
 * <p>Measured on {@code src/test/resources/chat/convo-lines.tsv} (docs/tickets/59, section 9): Decide alone 76% on
 * intent and 69% on tone; it confuses talk with moods and near neighbours, so a Decide intent counts only at
 * {@code solomapling.convo.intentMin} (0.4) and only for intents where a wrong answer is harmless
 * ({@link ConvoIntent#decideMay}). Tone is rules first; a Decide tone counts only at {@code solomapling.convo.toneMin} (0.8): measured, the model tone made the rules worse.
 */
public final class ConvoSense {

    public enum Tone { KIND, RUDE, FLIRTY, NEUTRAL }

    /** Where an answer came from. */
    public enum Source { RULE, DECIDE, DEFAULT }

    /**
     * One line read: the ticket 36/37 triage (kind, legacy intent, con), the conversation intent and tone with their
     * sources, and the names it mentions.
     */
    public record Read(ChatTriage.Result triage, ConvoIntent intent, Source intentSource, double intentConfidence,
                       Tone tone, Source toneSource, ConvoEntities.Found entities) {

        /** The tone as ticket 58's affinity reads it. */
        public ChatTone.Tone affinityTone() {
            return switch (tone) {
                case KIND -> ChatTone.Tone.KIND;
                case RUDE -> ChatTone.Tone.RUDE;
                default -> ChatTone.Tone.PLAIN;
            };
        }

        public String describe() {
            return "intent=" + intent.label() + "/" + intentSource.name().toLowerCase(Locale.ROOT)
                    + (intentSource == Source.DECIDE ? String.format(Locale.ROOT, "@%.2f", intentConfidence) : "")
                    + " tone=" + tone.name().toLowerCase(Locale.ROOT) + "/" + toneSource.name().toLowerCase(Locale.ROOT)
                    + (entities == null || entities.isEmpty() ? "" : " " + entities.describe());
        }
    }

    // ── the questions (kept in step with tools/convo/decide_probe.py) ──

    public static final ChatDecide.Question INTENT = new ChatDecide.Question("convo_intent",
            "What does the player want from you?", labels(
            "party", "join or make a party",
            "follow", "follow them or stay close",
            "come", "come to a map or town",
            "trade", "buy or sell an item",
            "haggle", "bargain over a price",
            "buff", "cast a buff or heal on them",
            "help_kill", "help kill monsters or a boss",
            "guide", "show the way to a place",
            "date", "go on a date",
            "buddy", "become buddies or friends",
            "guild", "join or talk about a guild",
            "apology", "saying sorry",
            "insult", "insulting or mocking you",
            "flirt", "flirting or complimenting looks",
            "talk", "a question or just chatting, no request"));

    public static final ChatDecide.Question TONE = new ChatDecide.Question("convo_tone",
            "How is the player treating you in this line?", labels(
            "kind", "friendly, thankful, sorry or warm",
            "rude", "insulting, mocking or hostile",
            "flirty", "flirting, romantic or calling you cute",
            "neutral", "plain chat, a question or a request"));

    /** Asked together in one request (~170 ms p50 on the test endpoint). */
    static final List<ChatDecide.Question> ASKED = List.of(INTENT, TONE, ChatTriage.CON);

    private static Map<String, String> labels(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    // ── rules ──

    private static final Pattern COME = Pattern.compile("\\b((come|cum|c'?mon|get) (to|over|over to|down to|up to|by|here"
            + "|to me)\\b|meet (me |u |up )?(at|in)\\b|(can|could|will|would) (u|you) come\\b|come (here|over|quick)\\b"
            + "|\\bcome\\??$)");
    private static final Pattern HAGGLE = Pattern.compile("\\b(go lower|any lower|lower\\?|cheaper|discount|too (much|expensive"
            + "|pricey|high)|(would|will|could) (u|you) (do|take|go)|how about \\d|ill (give|pay|do)|i'?ll (give|pay|do)"
            + "|best price|last price|meet (me )?(in the )?middle|negotiable|neg\\b)");
    private static final Pattern BUFF = Pattern.compile("\\b(buff|buffs|bless|haste|hs|heal|heals|hb|hyper body|sharp eyes"
            + "|se|mw|maple warrior|booster|meditation|holy symbol|dispel|res|resurrect)\\b.*\\b(me|pls|plz|please|us|\\?)|"
            + "\\b(can|could) (u|you|i (get|have)) (a |some )?(buff|bless|haste|hs|heal|hb|se)\\b|^(buff|heal|hs|bless|haste)"
            + "( me)?( pls| plz| please)?\\??$");
    private static final Pattern HELP_KILL = Pattern.compile("\\b(help (me )?(kill|with|vs|out|fight|on)|help me\\b(?!.*\\b(find|get"
            + " to|where)\\b)|(can|could|would) (u|you) help\\b|kill (these|this|them|it|the boss)|carry me|need help (with|on|killing)"
            + "|\\bhelp\\b.*\\b(kill|killing|dying|boss|mobs?)\\b|\\b(kill|killing|dying)\\b.*\\bhelp\\b)");
    private static final Pattern GUIDE = Pattern.compile("(\\bhow (do|can|would) (i|u|you|we) (get|go) to\\b|\\bhow to get to\\b"
            + "|\\bshow me (the way|where|how)\\b|\\bwhich way (to|is)\\b|\\bwhere('?s| is) (the )?(\\w+ ){0,3}(npc|portal|station|shop"
            + "|entrance|ship|boat|taxi|cab)\\b|\\bway to (get to )?\\w+\\?|\\btake me to\\b(?!.*\\b(date|amoria)\\b)|\\blead the way\\b)");
    private static final Pattern DATE_EXTRA = Pattern.compile("\\b(take me (to|on) (amoria|a date)|go to amoria (with me|together)"
            + "|amoria date)\\b");
    private static final Pattern MEET_AT = Pattern.compile("\\bmeet (me |u |you |up )?(at|in) \\w+");
    private static final Pattern BUDDY = Pattern.compile("\\b(add me|add u|add you|buddy|buddies|bud list|buddy list|friend list"
            + "|be friends|b friends|friend me)\\b");
    private static final Pattern GUILD = Pattern.compile("\\bguild\\b");
    private static final Pattern APOLOGY = Pattern.compile("\\b(sorry|sry|srry|soz|my bad|mb|apologi[sz]e|didn'?t mean (it|to)"
            + "|didn'?t see (u|you)|forgive me|wont happen again|won'?t happen again)\\b");
    private static final Pattern FLIRT_EXTRA = Pattern.compile("\\b(ur cute|you'?re cute|so cute|ur hot|miss me|missed (u|you)"
            + "|ur pretty|you'?re pretty|kinda pretty|<3)\\b");
    private static final Pattern KIND_EXTRA = Pattern.compile("\\b(sorry|sry|my bad|thanks|thank (u|you)|ty|gg|fun|nice|grats"
            + "|congrats|finally)\\b");

    private ConvoSense() {
    }

    static String normal(String line) {
        return line == null ? "" : line.toLowerCase(Locale.ROOT).replaceAll("[!.,~]+$", "").replaceAll("\\s+", " ").trim();
    }

    /** The intent by rule alone, or empty. Precise rules only: a rule that matches wins over the model. */
    public static Optional<ConvoIntent> intentRule(String line) {
        PlayerChatMeaning.View meaning = PlayerChatMeaning.requests(line);
        String text = normal(meaning.text());
        if (text.isEmpty()) {
            return meaning.filtered() ? Optional.of(ConvoIntent.TALK) : Optional.empty();
        }
        if (ChatTriage.conRule(line).filter(c -> c == ChatTriage.Con.HARASSMENT).isPresent()
                || ChatTone.rule(text, null).filter(t -> t == ChatTone.Tone.RUDE).isPresent()) {
            return Optional.of(ConvoIntent.INSULT);
        }
        if (soloMapling.ArtificialPlayer.BotDramaSystem.BotRomance.asksOutLine(text) || DATE_EXTRA.matcher(text).find()) {
            return Optional.of(ConvoIntent.DATE);
        }
        if (APOLOGY.matcher(text).find()) {
            return Optional.of(ConvoIntent.APOLOGY);
        }
        if (GUIDE.matcher(text).find()) {
            return Optional.of(ConvoIntent.GUIDE);
        }
        if (MEET_AT.matcher(text).find() && !ConvoEntities.find(text).isEmpty()) {
            return Optional.of(ConvoIntent.COME); // "meet me at henesys": a place, not "where are you"
        }
        if (BUFF.matcher(text).find()) {
            return Optional.of(ConvoIntent.BUFF);
        }
        if (HAGGLE.matcher(text).find() || (soloMapling.Economy.HaggleBrain.price(text) > 0
                && !text.matches(".*\\b(wts|wtb|selling|buying|s>|b>)\\b.*"))) {
            return Optional.of(ConvoIntent.HAGGLE);
        }
        Optional<ChatTriage.Intent> legacy = ChatTriage.intentRule(text);
        if (legacy.isPresent()) {
            return switch (legacy.get()) {
                case PARTY -> Optional.of(ConvoIntent.PARTY);
                case FOLLOW, COME_WITH_ME -> Optional.of(ConvoIntent.FOLLOW);
                case TRADE -> Optional.of(ConvoIntent.TRADE);
                // where-do-you-train / where-are-you stay ticket 37's own actions (talk with an outcome)
                case WHERE_TRAIN, WHERE_AT, NONE -> Optional.of(ConvoIntent.TALK);
            };
        }
        if (HELP_KILL.matcher(text).find()) {
            return Optional.of(ConvoIntent.HELP_KILL);
        }
        if (COME.matcher(text).find()) {
            return Optional.of(ConvoIntent.COME);
        }
        if (BUDDY.matcher(text).find()) {
            return Optional.of(ConvoIntent.BUDDY);
        }
        if (GUILD.matcher(text).find()) {
            return Optional.of(ConvoIntent.GUILD);
        }
        if (ContentGuard.flirty(text) || FLIRT_EXTRA.matcher(text).find()) {
            return Optional.of(ConvoIntent.FLIRT);
        }
        return meaning.filtered() ? Optional.of(ConvoIntent.TALK) : Optional.empty();
    }

    /** The tone by rule alone, or empty. Rude wins, then flirty, then kind. */
    public static Optional<Tone> toneRule(String line, ChatTriage.Result triage) {
        String text = normal(line);
        Optional<ChatTone.Tone> base = ChatTone.rule(text, triage);
        if (base.isPresent() && base.get() == ChatTone.Tone.RUDE) {
            return Optional.of(Tone.RUDE);
        }
        if (ContentGuard.flirty(text) || FLIRT_EXTRA.matcher(text).find()) {
            return Optional.of(Tone.FLIRTY);
        }
        if (APOLOGY.matcher(text).find()) {
            return Optional.of(Tone.KIND);
        }
        if (base.isPresent()) {
            return Optional.of(base.get() == ChatTone.Tone.KIND ? Tone.KIND : Tone.NEUTRAL);
        }
        return Optional.empty();
    }

    /** Rules only: what the server does with Decide off, slow or down. */
    public static Read rules(String line) {
        return combine(line, ChatTriage.rules(line), Map.of(), 1, 1, 1);
    }

    /**
     * Rules, then one Decide call for what they left open. Blocks for at most Decide's deadline: off the game thread.
     * With {@code decide} null or {@code -Dsolomapling.convo.sense=false}, rules only.
     */
    public static Read read(String line, ChatDecide decide) {
        ChatTriage.Result byRule = ChatTriage.rules(line);
        Optional<ConvoIntent> intent = intentRule(line);
        Optional<Tone> tone = toneRule(line, byRule);
        boolean need = intent.isEmpty() || tone.isEmpty() || byRule.conSource() != ChatTriage.Source.RULE;
        if (decide == null || !ConvoSwitches.on("sense") || byRule.kind() != ChatTriage.Kind.TALK || !need) {
            return combine(line, byRule, Map.of(), 1, 1, 1);
        }
        Optional<Map<String, ChatDecide.Answer>> answers = decide.askNow(line, ASKED);
        return combine(line, byRule, answers.orElse(Map.of()), ConvoSwitches.num("intentMin", 0.4),
                ConvoSwitches.num("toneMin", 0.8), ConvoSwitches.prop("solomapling.chat.conMin", 0.4));
    }

    /** Rules first, the model's confident answers for the rest. Public for the offline evaluation. */
    public static Read combine(String line, ChatTriage.Result byRule, Map<String, ChatDecide.Answer> answers,
                               double intentMin, double toneMin, double conMin) {
        ChatTriage.Result triage = answers.containsKey(ChatTriage.CON.name())
                ? ChatTriage.combine(byRule, Map.of(ChatTriage.CON.name(), answers.get(ChatTriage.CON.name())), 2, conMin)
                : byRule;
        Optional<ConvoIntent> ruleIntent = intentRule(line);
        ConvoIntent intent = ConvoIntent.TALK;
        Source intentSource = Source.DEFAULT;
        double intentConfidence = 0;
        if (ruleIntent.isPresent()) {
            intent = ruleIntent.get();
            intentSource = Source.RULE;
            intentConfidence = 1;
        } else if (triage.kind() == ChatTriage.Kind.TALK && answers.get(INTENT.name()) instanceof ChatDecide.Answer a
                && a.confidence() >= intentMin && ConvoIntent.parse(a.choice()).decideMay()) {
            intent = ConvoIntent.parse(a.choice());
            intentSource = Source.DECIDE;
            intentConfidence = a.confidence();
        }
        Optional<Tone> ruleTone = toneRule(line, triage);
        Tone tone = Tone.NEUTRAL;
        Source toneSource = Source.DEFAULT;
        if (ruleTone.isPresent()) {
            tone = ruleTone.get();
            toneSource = Source.RULE;
        } else if (answers.get(TONE.name()) instanceof ChatDecide.Answer t && t.confidence() >= toneMin) {
            tone = switch (t.choice()) {
                case "kind" -> Tone.KIND;
                case "rude" -> Tone.RUDE;
                case "flirty" -> Tone.FLIRTY;
                default -> Tone.NEUTRAL;
            };
            toneSource = Source.DECIDE;
        }
        // the legacy intent the ticket 37 actions use: a Decide intent that maps onto them counts too
        if (intentSource == Source.DECIDE && triage.intentSource() != ChatTriage.Source.RULE) {
            ChatTriage.Intent legacy = switch (intent) {
                case PARTY -> ChatTriage.Intent.PARTY;
                case FOLLOW -> ChatTriage.Intent.FOLLOW;
                default -> ChatTriage.Intent.NONE;
            };
            triage = new ChatTriage.Result(triage.kind(), legacy, legacy == ChatTriage.Intent.NONE
                    ? ChatTriage.Source.DEFAULT : ChatTriage.Source.DECIDE, triage.con(), triage.conSource(),
                    triage.conConfidence());
        } else if (intentSource == Source.RULE && intent == ConvoIntent.HAGGLE
                && triage.intent() == ChatTriage.Intent.NONE) {
            // a counter-offer goes down the trade path, where the quote and PriceBand live
            triage = new ChatTriage.Result(triage.kind(), ChatTriage.Intent.TRADE, ChatTriage.Source.RULE, triage.con(),
                    triage.conSource(), triage.conConfidence());
        } else if (intentSource == Source.RULE && intent == ConvoIntent.COME
                && triage.intent() == ChatTriage.Intent.WHERE_AT) {
            // rc6 triage (t-cv-1): "meet me at the fm" names a place, so the convo layer walks the bot there; the
            // legacy WHERE_AT rule ("meet me") only made it say where it stands
            triage = new ChatTriage.Result(triage.kind(), ChatTriage.Intent.NONE, ChatTriage.Source.DEFAULT,
                    triage.con(), triage.conSource(), triage.conConfidence());
        }
        return new Read(triage, intent, intentSource, intentConfidence, tone, toneSource, ConvoEntities.find(line));
    }
}
