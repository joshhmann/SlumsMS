package soloMapling.ArtificialPlayer.BotConvo;

import soloMapling.ArtificialPlayer.BotAffinitySystem.ChatTone;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatDecide;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatScope;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatStats;
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
            + "|best price|last price|lowest price|ur lowest|meet (me )?(in the )?middle|negotiable|neg\\b)");
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
    /** "where is kerning city": the way to a place (only when what follows names a map). */
    private static final Pattern WHERE_IS = Pattern.compile("\\bwhere('?s| is) (.+)$");
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
    /** Where a price sits in the line, so the price rule can be scoped like the others. */
    private static final Pattern PRICE_AT = Pattern.compile("\\d[\\d,.]*");
    private static final Pattern KIND_EXTRA = Pattern.compile("\\b(sorry|sry|my bad|thanks|thank (u|you)|ty|gg|fun|nice|grats"
            + "|congrats|finally)\\b");

    private ConvoSense() {
    }

    static String normal(String line) {
        return line == null ? "" : line.toLowerCase(Locale.ROOT).replaceAll("[!.,~]+$", "").replaceAll("\\s+", " ").trim();
    }

    /**
     * The intent by rule alone, or empty. Precise rules only: a rule that matches wins over the model. A request cue
     * out of scope ({@link ChatScope}: "dont follow me", "he said party pls") is no request; when every request cue
     * that matched is out of scope the line is talk by rule.
     */
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
        // a sorry that comes with a refusal is part of the refusal ("not looking to trade right now sorry")
        if (APOLOGY.matcher(text).find() && !declines(line, text)) {
            return Optional.of(ConvoIntent.APOLOGY);
        }
        Scope s = new Scope(text);
        if (s.liveQuestion(GUIDE)) {
            return Optional.of(ConvoIntent.GUIDE);
        }
        java.util.regex.Matcher whereIs = WHERE_IS.matcher(text);
        if (whereIs.find() && ConvoEntities.find(whereIs.group(2)).first(ConvoEntities.Kind.MAP) != null
                && s.liveQuestion(WHERE_IS)) {
            return Optional.of(ConvoIntent.GUIDE);
        }
        if (s.live(MEET_AT) && !ConvoEntities.find(text).isEmpty()) {
            return Optional.of(ConvoIntent.COME); // "meet me at henesys": a place, not "where are you"
        }
        if (s.live(BUFF)) {
            return Optional.of(ConvoIntent.BUFF);
        }
        if (s.live(HAGGLE) || (soloMapling.Economy.HaggleBrain.price(text) > 0
                && !text.matches(".*\\b(wts|wtb|selling|buying|s>|b>)\\b.*") && s.live(PRICE_AT))) {
            return Optional.of(ConvoIntent.HAGGLE);
        }
        Optional<ChatTriage.Intent> legacy = ChatTriage.intentRule(text);
        if (legacy.isPresent()) {
            switch (legacy.get()) {
                case PARTY -> {
                    return Optional.of(ConvoIntent.PARTY);
                }
                case FOLLOW, COME_WITH_ME -> {
                    return Optional.of(ConvoIntent.FOLLOW);
                }
                case TRADE -> {
                    return Optional.of(ConvoIntent.TRADE);
                }
                // where-do-you-train / where-are-you stay ticket 37's own actions (talk with an outcome)
                case WHERE_TRAIN, WHERE_AT -> {
                    return Optional.of(ConvoIntent.TALK);
                }
                case NONE -> s.outOfScope = true; // its cues matched out of scope; the rules below may still match
            }
        }
        if (s.live(HELP_KILL)) {
            return Optional.of(ConvoIntent.HELP_KILL);
        }
        if (s.live(COME)) {
            return Optional.of(ConvoIntent.COME);
        }
        if (s.live(BUDDY)) {
            return Optional.of(ConvoIntent.BUDDY);
        }
        if (s.live(GUILD)) {
            return Optional.of(ConvoIntent.GUILD);
        }
        if (ContentGuard.flirty(text) || FLIRT_EXTRA.matcher(text).find()) {
            return Optional.of(ConvoIntent.FLIRT);
        }
        return s.outOfScope || meaning.filtered() ? Optional.of(ConvoIntent.TALK) : Optional.empty();
    }

    /** The request cues this class owns, for {@link #declines}. */
    private static final List<Pattern> CUES = List.of(MEET_AT, BUFF, HAGGLE, HELP_KILL, COME, BUDDY, GUILD);

    private static boolean declines(String line, String text) {
        if (ChatTriage.declines(line)) {
            return true;
        }
        for (Pattern cue : CUES) {
            if (ChatScope.negated(cue, text)) {
                return true;
            }
        }
        return false;
    }

    /** Request cues read through {@link ChatScope}; remembers whether any matched out of scope. */
    private static final class Scope {
        final String text;
        boolean outOfScope;

        Scope(String text) {
            this.text = text;
        }

        boolean live(Pattern cue) {
            return live(cue, true);
        }

        /** A cue that asks the way: a negator does not scope it ("i dont know how to get to ellinia"). */
        boolean liveQuestion(Pattern cue) {
            return live(cue, false);
        }

        private boolean live(Pattern cue, boolean negation) {
            ChatScope.Frame f = ChatScope.scan(cue, text, negation);
            outOfScope |= f != null && f != ChatScope.Frame.NONE;
            return f == ChatScope.Frame.NONE;
        }
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
        return read(line, decide, null);
    }

    /**
     * As {@link #read(String, ChatDecide)}; {@code speaker} (the player's name, may be null) only goes to the
     * {@link DecideShadow} log as a hash. A line the model judged is offered to the shadow run after the decision.
     */
    public static Read read(String line, ChatDecide decide, String speaker) {
        return read(line, decide, decide == null ? null : ChatDecide.getTone(), speaker);
    }

    /**
     * As above with an explicit tone client (1.6.1 split). With one, intent + con go to {@code decide} and tone to
     * {@code tone} in parallel and the answers are merged; a failed half leaves its fields to the rules. The tone
     * call is skipped when a rule already gave the tone; the intent/con call when a rule gave the intent and con.
     */
    public static Read read(String line, ChatDecide decide, ChatDecide tone, String speaker) {
        ChatTriage.Result byRule = ChatTriage.rules(line);
        Optional<ConvoIntent> intent = intentRule(line);
        Optional<Tone> toneByRule = toneRule(line, byRule);
        boolean needIntentCon = intent.isEmpty() || byRule.conSource() != ChatTriage.Source.RULE;
        boolean need = needIntentCon || toneByRule.isEmpty();
        if (decide == null || !ConvoSwitches.on("sense") || byRule.kind() != ChatTriage.Kind.TALK || !need) {
            return combine(line, byRule, Map.of(), 1, 1, 1);
        }
        long started = System.nanoTime();
        Optional<Map<String, ChatDecide.Answer>> answers;
        if (tone == null) {
            answers = decide.askNow(line, ASKED);
        } else {
            var main = needIntentCon ? decide.ask(line, List.of(INTENT, ChatTriage.CON)) : null;
            var toneCall = toneByRule.isEmpty() ? tone.ask(line, List.of(TONE)) : null;
            Map<String, ChatDecide.Answer> merged = new java.util.LinkedHashMap<>();
            merged.putAll(await(main));
            merged.putAll(await(toneCall));
            answers = merged.isEmpty() ? Optional.empty() : Optional.of(merged);
        }
        long ms = (System.nanoTime() - started) / 1_000_000;
        double intentMin = ConvoSwitches.num("intentMin", 0.4), toneMin = ConvoSwitches.num("toneMin", 0.8);
        double conMin = ConvoSwitches.prop("solomapling.chat.conMin", 0.4);
        Read read = combine(line, byRule, answers.orElse(Map.of()), intentMin, toneMin, conMin);
        ChatStats.read("intent", read.intentSource().name());
        ChatStats.read("tone", read.toneSource().name());
        ChatStats.read("con", read.triage().conSource().name());
        // 1.6.0 shadow run (TEST, -Dslums.decide.shadow): logged only, never waits, never changes this read
        DecideShadow.offer(line, speaker, byRule, answers.orElse(null), ms, read, intentMin, toneMin, conMin);
        return read;
    }

    private static Map<String, ChatDecide.Answer> await(
            java.util.concurrent.CompletableFuture<Optional<Map<String, ChatDecide.Answer>>> call) {
        if (call == null) {
            return Map.of();
        }
        try {
            return call.get(1000, java.util.concurrent.TimeUnit.MILLISECONDS).orElse(Map.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of();
        } catch (Exception e) {
            return Map.of();
        }
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
