package soloMapling.ArtificialPlayer.BotConvo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Ticket 59, owner decision 3: how one bot types, stable for its whole life. Seeded by the bot's slot, so it needs no
 * column and no migration, and it sits on top of the stored {@code BotPersona} (vibe, interests) and
 * {@code BotPersonality} (chattiness). The mix is a "mixed real server": most lowercase with u/ur/lol/brb, some
 * proper typers, a few xX leet kids, a few Korean- and Spanish-flavoured players, a few who type the least they can.
 */
public record ConvoPersona(Voice voice, double typoRate, double chattiness, double jobPride, double guildLoyalty,
                           List<String> catchphrases) {

    /** The voice families, with their share of the population. */
    public enum Voice {
        LOWER(55, "types all lowercase with u/ur/r/lol/brb/idk/tbh, no apostrophes, no full stops",
                List.of("lol ya", "idk tbh", "brb pots"),
                List.of("lol", "rip", "ez", "gg", "brb", "lmao", "ya ya", "tbh")),
        // 1.6.0 tune: "I have 400k mesos. That is all, my friend." read like a butler; still 2009 game chat
        PROPER(18, "spells words out with capitals and apostrophes, but keeps it short and casual like 2009 game"
                + " chat (never formal, no \"my friend\", no speeches)",
                List.of("Yeah, almost there", "I'm at 37, can I come?"),
                List.of("Nice.", "Fair enough.", "Good game.", "Not bad.", "Haha, nice.")),
        LEET(8, "a hyper xX kid: random caps, xD, lolz, !!!, 1337 spelling now and then",
                List.of("LOLZ ur so pro xD", "omg 1 more lvl!!!"),
                List.of("xD", "lolz", "pwned", "1337", "roflmao", "noob lol", "!!!1")),
        KOREAN(7, "a Korean player typing English: short, ^^ and kekeke, -nim for people you respect",
                List.of("hi ^^", "ok kekeke", "thx nim ^^"),
                List.of("^^", "kekeke", "-_-", "ok ok", "hehe ^^", "fighting!")),
        // 1.6.0 tune (TEST trace 2026-10-26: 26 of 194 thread lines, some all Spanish): a light accent only
        SPANISH(7, "types lowercase English with a light Spanish accent: at most one of jaja / porfa / amigo in a"
                + " line, never a sentence in Spanish",
                List.of("jaja ya", "wait porfa", "ty amigo"),
                List.of("jaja", "jajaja", "porfa", "amigo", "q onda", "vamos")),
        TERSE(5, "types the fewest words possible, no punctuation, often one word",
                List.of("ya", "nah", "omw"),
                List.of("k", "ya", "nah", "ok", "sure"));

        final int share;
        public final String prompt;
        public final List<String> examples;
        final List<String> phrases;

        Voice(int share, String prompt, List<String> examples, List<String> phrases) {
            this.share = share;
            this.prompt = prompt;
            this.examples = examples;
            this.phrases = phrases;
        }

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Voice parse(String raw) {
            if (raw == null) {
                return null;
            }
            for (Voice v : values()) {
                if (v.label().equalsIgnoreCase(raw.trim())) {
                    return v;
                }
            }
            return null;
        }
    }

    /**
     * 1.6.0 tune: of the SPANISH voice's slice of the roll only the first this many points keep the accent (3% of
     * bots, was 7%); the rest type LOWER. Every other bot keeps the voice it had.
     */
    static final int SPANISH_KEPT = 3;

    /** The persona of the bot in this roster slot (any slot: staff and on-demand bots get one too). */
    public static ConvoPersona of(int botSlot) {
        return of(botSlot, -1);
    }

    /**
     * As {@link #of(int)}, with the bot's stored chattiness when known (0..1; negative = draw one from the seed). A
     * GM or {@code !qa} override of the voice (cv-4) is honoured.
     */
    public static ConvoPersona of(int botSlot, double chattiness) {
        Random rng = new Random(botSlot * 0x5DEECE66DL + 0x2F);
        int roll = rng.nextInt(100);
        Voice voice = Voice.LOWER;
        for (Voice v : Voice.values()) {
            if (roll < v.share) {
                voice = v == Voice.SPANISH && roll >= SPANISH_KEPT ? Voice.LOWER : v;
                break;
            }
            roll -= v.share;
        }
        Voice forced = overrides.get(botSlot);
        if (forced != null) {
            voice = forced;
        }
        double typo = switch (voice) {
            case PROPER -> rng.nextDouble() * 0.01;
            case LEET -> 0.03 + rng.nextDouble() * 0.03;
            case TERSE -> rng.nextDouble() * 0.02;
            default -> 0.005 + rng.nextDouble() * 0.035;
        };
        double chat = chattiness >= 0 ? chattiness : Math.pow(rng.nextDouble(), 1.5);
        List<String> pool = new ArrayList<>(voice.phrases);
        List<String> phrases = new ArrayList<>();
        int n = 1 + rng.nextInt(3);
        while (phrases.size() < n && !pool.isEmpty()) {
            phrases.add(pool.remove(rng.nextInt(pool.size())));
        }
        return new ConvoPersona(voice, typo, chat, rng.nextDouble(), rng.nextDouble(), List.copyOf(phrases));
    }

    private static final java.util.Map<Integer, Voice> overrides = new java.util.concurrent.ConcurrentHashMap<>();

    /** GM / {@code !qa cv-4}: give a slot a voice until {@link #clearVoice} (memory only). */
    public static void forceVoice(int botSlot, Voice voice) {
        if (voice == null) {
            overrides.remove(botSlot);
        } else {
            overrides.put(botSlot, voice);
        }
    }

    public static void clearVoice(int botSlot) {
        overrides.remove(botSlot);
    }

    /** The persona's lines in the system prompt: the voice, two examples, the catchphrases, pride and loyalty. */
    public String prompt(String job, boolean inGuild) {
        StringBuilder s = new StringBuilder("Your typing: ").append(voice.prompt).append(" (like \"")
                .append(String.join("\" / \"", voice.examples)).append("\"). ");
        if (!catchphrases.isEmpty()) {
            s.append("You sometimes say ").append(String.join(", ", catchphrases.stream().map(c -> "\"" + c + "\"").toList()))
                    .append(". ");
        }
        if (jobPride > 0.7 && job != null) {
            s.append("You are proud of being a ").append(job).append(" and defend it. ");
        }
        if (inGuild && guildLoyalty > 0.7) {
            s.append("You are loyal to your guild. ");
        }
        return s.toString();
    }

    /** One line for logs and !qa. */
    public String describe() {
        return String.format(Locale.ROOT, "%s typo=%.0f%% chat=%.2f pride=%.2f loyalty=%.2f phrases=%s", voice.label(),
                typoRate * 100, chattiness, jobPride, guildLoyalty, catchphrases);
    }
}
