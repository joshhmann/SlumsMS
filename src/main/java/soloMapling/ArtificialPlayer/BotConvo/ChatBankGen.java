package soloMapling.ArtificialPlayer.BotConvo;

import soloMapling.ArtificialPlayer.BotBrainSystem.ContentGuard;
import soloMapling.server.Json;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * Ticket 59: the offline generator for the line bank ({@link ChatBank}). A main in the server jar, so it checks lines
 * with exactly the server's {@link ContentGuard} and voice check. See {@code tools/chatbank/README.md}.
 *
 * <pre>
 *   seed                       import the dialogue packs' lines (no LLM)
 *   gen [options]              generate with the local model, throttled, resumable
 *   player [options]           labelled pairs (a player's opening line and a bot's reply) for the chat classifier:
 *                              state/pairs.jsonl, never packed into the bank ({@link ChatPairGen})
 *   pack [--out file]          write the gzip bank (default: the shipped resource)
 *   stats                      lines per intent, voice and affinity
 * options: --state dir (tools/chatbank/state) --url http://192.168.0.17:8095/v1 --model qwen9b --target 100000
 *          --per-cell 40 --batch 8 --calls 50 --pause-ms 1500 --hours 2-8 --api-key-env NAME
 *          --budget-usd 0 --price-in 0.28 --price-out 0.42 --no-idle-check --no-verify
 * </pre>
 *
 * <p>Throttling: one request at a time; before each, llama.cpp's {@code /slots} must show every slot idle (so live
 * chat on the same server is never starved); a pause between calls; an optional hours window. Resumable: accepted lines
 * are appended to {@code state/lines.tsv} as they come, and a restart reads them back (ids, duplicates, cell counts).
 */
public final class ChatBankGen {

    /** One bank cell the generator fills: an intent, the player's tone, the bot's feelings, a voice and a place. */
    record Cell(String intent, String tone, String affinity, String voice, String tags) {
        String key() {
            return intent + "|" + tone + "|" + affinity + "|" + voice + "|" + tags;
        }
    }

    /** What a line of each intent does, for the generation prompt; and the slots it may use. */
    static final Map<String, String[]> INTENTS = new LinkedHashMap<>();

    static {
        intent("talk", "reacting casually to something the other player just said (not a question)", "player,map,mob,job,level");
        intent("talk_q", "answering a casual question the other player asked, short, maybe unsure (idk is fine)", "map,mob,job,level");
        intent("greeting", "saying hi back to someone who greeted you", "player");
        intent("recognise", "greeting a player you have met before, glad to see them again", "player");
        intent("thanks", "replying when someone thanks you", "player");
        intent("bye", "saying bye when someone leaves", "player");
        intent("party_yes", "saying yes to their party invite and telling them to send it", "");
        intent("party_no", "saying no to partying right now, casually", "");
        intent("party_already", "saying you are already in a party", "");
        intent("follow_yes", "agreeing to come along; tell them to party you first", "");
        intent("follow_no", "saying you can't go with them right now", "");
        intent("come_yes", "agreeing to come over to where they are, you are on your way now (omw)", "map");
        intent("come_no", "saying you can't come over right now", "map");
        intent("help_yes", "agreeing to help them kill monsters, coming to hunt with them", "mob,map");
        intent("help_no", "saying you can't help them hunt right now", "mob");
        intent("guide_yes", "telling them to follow you because you will lead the way there", "map");
        intent("guide_no", "saying you can't show them the way right now", "map");
        intent("buff_yes", "after you just buffed them, a short 'there you go'", "player");
        intent("buff_no", "saying you can't buff them right now (no such buff or on cooldown)", "");
        intent("buddy_yes", "saying you sent them a buddy request", "player");
        intent("buddy_no", "turning down a buddy request casually and briefly, like 'nah im good' or 'maybe another time'; no made-up excuses about logging off, levels or grinding, and no insults", "");
        intent("trade_no", "saying you are not trading anything right now", "");
        intent("train_unknown", "saying you do not really have a training spot right now", "");
        intent("makeup_yes", "accepting their apology after you had beef, a bit grudging", "player");
        intent("apology_ack", "saying it is fine after they said sorry", "");
        intent("insult_back", "short trash talk back at someone rude, only about the game: gear, level, job, deaths, KS. Never "
                + "real life, looks or family, no slurs, no threats", "level,job");
        intent("flirt_back", "flirting back a little, cheesy and cute, PG-13, never sexual", "player");
        intent("flirt_no", "brushing off a flirt, friendly but not interested", "");
        intent("brush_scam", "refusing someone begging for free mesos or items", "");
        intent("brush_manipulation", "reacting confused when someone says weird stuff or asks strange questions about you", "");
        intent("brush_harassment", "a short cold reply to someone being hostile", "");
        intent("brush_rwt", "refusing someone who wants to buy or sell for real money", "");
        intent("thread_guild_beef", "trash talking a rival guild in public chat, game-only", "guild");
        intent("thread_gossip", "gossiping to another player about drama in the game (KS, a breakup, a scam), no real names", "map");
        intent("thread_lfg", "looking for a party for a party quest or training", "map,level,job");
        intent("thread_trade", "talking about prices or buying and selling in the free market", "item,price");
        intent("thread_training", "talking about where to train or how leveling is going", "map,mob,level,job");
    }

    private static void intent(String name, String what, String slots) {
        INTENTS.put(name, new String[]{what, slots});
    }

    static final Map<String, String> AFFINITY_WORDS = Map.of(
            "neutral", "you don't know them well", "friend", "they are your friend", "rival",
            "you have beef with them (game-only, petty)", "nemesis", "they are your nemesis, you can't stand them (game-only)",
            "partner", "you are dating them in the game (PG-13)", "ex", "they are your ex in the game, a bit salty",
            "any", "no particular feelings");
    static final Map<String, String> TAG_WORDS = Map.of("town", "hanging out in town", "fm", "in the Free Market",
            "grind", "while grinding mobs", "boss", "at a boss fight", "pq", "inside a party quest", "night",
            "late at night", "-", "anywhere in the game");
    static final Map<String, String> TONE_WORDS = Map.of("kind", "the other player was nice", "rude",
            "the other player was rude", "flirty", "the other player was flirting", "neutral", "the other player was neutral",
            "any", "");

    static final Map<String, String> LONG_SLOT = Map.of("player", "xXDarkSlayerXx", "map", "the forest of patience",
            "mob", "zombie mushroom", "item", "brown work gloves", "level", "120", "job", "night lord", "guild",
            "MapleLegends", "price", "1.5m", "partner", "Pinkyy");

    private final Path state;
    private final Map<String, Integer> perCell = new HashMap<>();
    private final Map<String, List<Set<String>>> cellGrams = new HashMap<>();
    private final Set<String> seen = new HashSet<>();
    private int nextId = 1;
    private int accepted;
    private int rejected;
    /** Sent as {@code reasoning_effort} when set (a cloud thinking model); blank = the local {@code enable_thinking: false}. */
    String reasoningEffort = "";
    /** The completion's token cap (the pair generator writes two lines per item and raises it). */
    int maxTokens = 260;
    private final Map<String, Integer> rejectReasons = new LinkedHashMap<>();

    ChatBankGen(Path state) throws IOException {
        this.state = state;
        Files.createDirectories(state);
        Path lines = state.resolve("lines.tsv");
        if (Files.exists(lines)) {
            for (String row : Files.readAllLines(lines, StandardCharsets.UTF_8)) {
                String[] f = row.split("\t", 7);
                if (f.length < 7 || row.startsWith("#")) {
                    continue;
                }
                nextId = Math.max(nextId, Integer.parseInt(f[0]) + 1);
                Cell c = new Cell(f[1], f[2], f[3], f[4], f[5]);
                remember(c, f[6]);
            }
        }
    }

    private void remember(Cell c, String text) {
        seen.add(norm(text));
        perCell.merge(c.key(), 1, Integer::sum);
        cellGrams.computeIfAbsent(c.intent() + "|" + c.affinity(), k -> new ArrayList<>()).add(grams(text));
    }

    // ── checks ──

    static String norm(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\{[a-z]+}", "#").replaceAll("[^a-z0-9#]", "")
                .replaceAll("(.)\\1+", "$1");
    }

    static Set<String> grams(String text) {
        String t = " " + text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9{} ]", "").replaceAll("\\s+", " ").trim() + " ";
        Set<String> g = new HashSet<>();
        for (int i = 0; i + 3 <= t.length(); i++) {
            g.add(t.substring(i, i + 3));
        }
        return g;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        int inter = 0;
        for (String x : a) {
            if (b.contains(x)) {
                inter++;
            }
        }
        return (double) inter / (a.size() + b.size() - inter);
    }

    private static final Pattern SLOT = Pattern.compile("\\{([^}]*)}");
    /** 3-gram Jaccard at which two lines of one intent and feeling count as the same line reworded. */
    static final double NEAR = 0.6;

    /** Why a generated line cannot go in the bank, or null. */
    String problem(Cell c, String text) {
        String bad = lineProblem(c.intent(), text);
        if (bad != null) {
            return bad;
        }
        if (seen.contains(norm(text))) {
            return "duplicate";
        }
        Set<String> g = grams(text);
        for (Set<String> other : cellGrams.getOrDefault(c.intent() + "|" + c.affinity(), List.of())) {
            if (jaccard(g, other) >= NEAR) {
                return "near duplicate";
            }
        }
        return null;
    }

    /**
     * Why a bot line of this bank intent cannot be said, before any duplicate check (also the pair generator's reply
     * check): its slots, markup, length with the longest slot values, the server's voice check and guard, and copied
     * voice examples. Null when fine.
     */
    static String lineProblem(String intent, String text) {
        if (text.isBlank() || text.length() < 2) {
            return "empty";
        }
        Set<String> allowed = new HashSet<>(List.of(INTENTS.getOrDefault(intent, new String[]{"", ""})[1].split(",")));
        Matcher m = SLOT.matcher(text);
        Map<String, String> fill = new HashMap<>();
        while (m.find()) {
            if (!ChatBank.SLOTS.contains(m.group(1)) || !allowed.contains(m.group(1))) {
                return "slot " + m.group(1);
            }
            fill.put(m.group(1), LONG_SLOT.get(m.group(1)));
        }
        if (text.indexOf('{') >= 0 && fill.isEmpty() || text.matches(".*[\\[\\]<>*#\"].*")) {
            return "markup";
        }
        String filled = ChatBank.fill(text, fill);
        if (filled == null || filled.length() > VoiceStyler.MAX) {
            return "too long";
        }
        String bad = ContentGuard.chatProblem(filled);
        if (bad != null) {
            return bad;
        }
        return voiceExample(text) ? "prompt example" : null;
    }

    /** Whether a line is one of the voices' prompt examples, copied. */
    static boolean voiceExample(String text) {
        for (ConvoPersona.Voice v : ConvoPersona.Voice.values()) {
            for (String example : v.examples) {
                if (norm(example).equals(norm(text))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Adds a line when it passes; true when added. */
    boolean offer(Cell c, String raw) throws IOException {
        String text = clean(raw);
        String why = problem(c, text);
        if (why != null) {
            rejected++;
            rejectReasons.merge(why.startsWith("slot") ? "slot" : why, 1, Integer::sum);
            Files.writeString(state.resolve("rejects.tsv"), c.key() + "\t" + why + "\t" + text.replace('\t', ' ') + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return false;
        }
        int id = nextId++;
        Files.writeString(state.resolve("lines.tsv"), id + "\t" + c.intent() + "\t" + c.tone() + "\t" + c.affinity()
                        + "\t" + c.voice() + "\t" + c.tags() + "\t" + text + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        remember(c, text);
        accepted++;
        return true;
    }

    static String clean(String raw) {
        String t = raw.trim().replaceAll("^\\s*(?:[-*•]|\\d+[.)]|line \\d+:)\\s*", "").trim();
        if (t.length() >= 2 && (t.startsWith("\"") && t.endsWith("\"") || t.startsWith("'") && t.endsWith("'"))) {
            t = t.substring(1, t.length() - 1).trim();
        }
        return t.endsWith(".") && !t.endsWith("..") ? t.substring(0, t.length() - 1) : t;
    }

    // ── seeding from the dialogue packs ──

    int seed() throws IOException {
        int before = accepted;
        Map<String, List<String>> lists = new LinkedHashMap<>();
        lists.put("greeting", soloMapling.ArtificialPlayer.ScriptedReplies.seedLines("greeting"));
        for (String key : List.of("thanks", "bye", "talk", "party_yes", "party_no", "party_already", "follow_yes",
                "follow_no", "trade_no", "train_unknown", "come_yes", "come_no", "help_yes", "help_no", "guide_yes",
                "guide_no", "buff_yes", "buff_no", "buddy_yes", "buddy_no", "makeup_yes", "apology_ack", "insult_back",
                "flirt_back", "flirt_no", "brush_scam", "brush_manipulation", "brush_harassment", "brush_rwt")) {
            lists.put(key, soloMapling.ArtificialPlayer.ScriptedReplies.seedLines(key));
        }
        for (Map.Entry<String, List<String>> e : lists.entrySet()) {
            for (String line : e.getValue()) {
                offer(new Cell(e.getKey(), "any", "any", "any", "-"), line);
            }
        }
        // The scripted bot-to-bot conversations stay whole scripts (BotThreads plays them when the model is busy):
        // their single lines lean on the line before and read wrong on their own.
        return accepted - before;
    }

    // ── generating ──

    static List<Cell> cells() {
        List<Cell> out = new ArrayList<>();
        List<String> voices = List.of("lower", "proper", "leet", "korean", "spanish", "terse");
        for (String intent : INTENTS.keySet()) {
            boolean social = intent.startsWith("talk") || intent.equals("greeting") || intent.equals("recognise")
                    || intent.equals("bye") || intent.equals("thanks");
            List<String> affinities = switch (intent) {
                case "insult_back" -> List.of("neutral", "rival", "nemesis");
                case "makeup_yes" -> List.of("rival", "nemesis");
                case "flirt_back" -> List.of("partner", "friend");
                case "recognise" -> List.of("neutral", "friend", "partner");
                default -> social ? List.of("neutral", "friend", "rival", "nemesis", "partner")
                        : intent.startsWith("brush") || intent.startsWith("thread") ? List.of("any")
                        : intent.endsWith("_no") ? List.of("neutral", "rival") : List.of("neutral", "friend");
            };
            List<String> tones = intent.startsWith("talk") ? List.of("neutral", "kind", "rude") : List.of("any");
            List<String> tags = intent.startsWith("talk") || intent.startsWith("thread")
                    ? List.of("-", "town", "fm", "grind", "boss", "pq", "night") : List.of("-");
            for (String voice : voices) {
                for (String a : affinities) {
                    for (String t : tones) {
                        for (String tag : tags) {
                            out.add(new Cell(intent, t, a, voice, tag));
                        }
                    }
                }
            }
        }
        return out;
    }

    String prompt(Cell c, int n) {
        String[] intent = INTENTS.get(c.intent());
        ConvoPersona.Voice v = ConvoPersona.Voice.parse(c.voice());
        StringBuilder s = new StringBuilder();
        s.append("Write ").append(n).append(" different chat lines a real player would type in MapleStory in 2008, ")
                .append(intent[0]).append(". ");
        // the sample batch had lines from the wrong side ("bruh thanks man" for the one GIVING the buff)
        s.append("You are the one typing and you are answering another player: every line is your side of it, never ")
                .append("theirs. ");
        if (v != null) {
            s.append("The player ").append(v.prompt).append(" (like \"").append(String.join("\" / \"", v.examples))
                    .append("\"); use that style lightly: at most one such word in a line, about half the lines with none, ")
                    .append("and the English is otherwise normal chat, not broken. ");
        }
        String tone = TONE_WORDS.getOrDefault(c.tone(), "");
        if (!tone.isEmpty()) {
            s.append("Before this, ").append(tone).append(". ");
        }
        s.append("Toward the other player: ").append(AFFINITY_WORDS.getOrDefault(c.affinity(), "")).append(". ");
        s.append("Where: ").append(TAG_WORDS.getOrDefault(c.tags(), "anywhere")).append(". ");
        String slots = intent[1];
        if (!slots.isBlank()) {
            s.append("Some lines may use these placeholders exactly as written: ");
            for (String slot : slots.split(",")) {
                s.append('{').append(slot).append("} ");
            }
            s.append("(they are filled in later). ");
        }
        return s.toString();
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** Whether llama.cpp's slots are all idle (true when the server has no /slots endpoint). */
    boolean idle(String baseUrl) {
        String root = baseUrl.replaceAll("/v1/?$", "");
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(root + "/slots"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                return true;
            }
            return !r.body().contains("\"is_processing\":true");
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * The fixed rules, first and word for word the same in every call: providers with prefix caching (DeepSeek) bill
     * a repeated prefix at about 1/50 of the input price. Only the cell's details follow, in the user message.
     */
    static final String SYSTEM = "You write short in-game chat lines for a game's line bank: what real players typed "
            + "in MapleStory in 2008. Output only the lines, one per row, nothing else. Rules for every line: under 55 "
            + "characters; era slang; no emojis, no hashtags, no quotes, no numbering; never mention being an AI, a bot "
            + "or a program; nothing about real life, real places or news; insults only about the game (gear, level, "
            + "skill, KS), never about anyone's looks, family, race, gender or sexuality. Vary them: different words, "
            + "not the same line reworded. Placeholders like {map} or {player} are kept exactly as written; they are "
            + "filled in later. When a request names a typing style, use it lightly.";

    /** One completion; returns [text, prompt tokens, completion tokens] or null. */
    Object[] complete(String url, String model, String key, String prompt) throws InterruptedException {
        return complete(url, model, key, SYSTEM, prompt);
    }

    /** One completion with the given fixed system message (the pair generator's own). */
    Object[] complete(String url, String model, String key, String system, String prompt) throws InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        if (model != null && !model.isBlank()) {
            body.put("model", model);
        }
        body.put("messages", List.of(Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", prompt)));
        body.put("max_tokens", maxTokens);
        body.put("temperature", 1.0);
        body.put("stream", false);
        if (reasoningEffort.isEmpty()) {
            body.put("chat_template_kwargs", Map.of("enable_thinking", false));
        } else {
            body.put("reasoning_effort", reasoningEffort); // cloud thinking models, e.g. "none" for deepseek-flash
        }
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url.replaceAll("/$", "") + "/chat/completions"))
                .timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
        if (key != null && !key.isBlank()) {
            req.header("Authorization", "Bearer " + key);
        }
        try {
            HttpResponse<String> r = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                System.err.println("HTTP " + r.statusCode() + ": " + r.body());
                return null;
            }
            String text = soloMapling.ArtificialPlayer.BotBrainSystem.OpenAICompatibleBrain.replyTextOf(r.body());
            long[] usage = soloMapling.ArtificialPlayer.BotBrainSystem.OpenAICompatibleBrain.usageOf(r.body());
            return new Object[]{text, usage[0], usage[1]};
        } catch (IOException e) {
            System.err.println("request failed: " + e);
            return null;
        }
    }

    /** Whether the hour now is inside the {@code --hours} window (always, without one). */
    static boolean inHours(int[] hours) {
        if (hours == null) {
            return true;
        }
        int h = LocalTime.now().getHour();
        return hours[0] <= hours[1] ? h >= hours[0] && h < hours[1] : h >= hours[0] || h < hours[1];
    }

    /** Waits up to 5 minutes for every model slot to be idle; false when it stayed busy. */
    boolean waitIdle(String url) throws InterruptedException {
        int waited = 0;
        while (!idle(url)) {
            if (waited++ > 150) {
                return false;
            }
            Thread.sleep(2000);
        }
        return true;
    }

    void generate(Map<String, String> o) throws Exception {
        String url = o.getOrDefault("url", "http://192.168.0.17:8095/v1");
        String model = o.getOrDefault("model", "qwen9b");
        String key = o.containsKey("api-key-env") ? System.getenv(o.get("api-key-env")) : "";
        int target = Integer.parseInt(o.getOrDefault("target", "100000"));
        int perCellTarget = Integer.parseInt(o.getOrDefault("per-cell", "40"));
        int batch = Integer.parseInt(o.getOrDefault("batch", "8"));
        int calls = Integer.parseInt(o.getOrDefault("calls", "50"));
        long pause = Long.parseLong(o.getOrDefault("pause-ms", "1500"));
        double budget = Double.parseDouble(o.getOrDefault("budget-usd", "0"));
        double priceIn = Double.parseDouble(o.getOrDefault("price-in", "0.30"));
        double priceOut = Double.parseDouble(o.getOrDefault("price-out", "1.20"));
        reasoningEffort = o.getOrDefault("reasoning-effort", "");
        boolean idleCheck = !o.containsKey("no-idle-check");
        int[] hours = o.containsKey("hours") ? java.util.Arrays.stream(o.get("hours").split("-")).mapToInt(Integer::parseInt)
                .toArray() : null;
        List<Cell> cells = cells();
        java.util.Random rng = new java.util.Random(nextId);
        double spent = 0;
        int made = 0;
        for (int call = 0; call < calls; call++) {
            int total = perCell.values().stream().mapToInt(Integer::intValue).sum();
            if (total >= target) {
                System.out.println("target reached: " + total);
                break;
            }
            if (!inHours(hours)) {
                System.out.println("outside the hours window " + o.get("hours") + ": stopping");
                break;
            }
            if (budget > 0 && spent >= budget) {
                System.out.println("budget reached: $" + spent);
                break;
            }
            Cell next = pickCell(cells, perCellTarget, rng);
            if (next == null) {
                System.out.println("every cell has " + perCellTarget + " lines");
                break;
            }
            if (idleCheck && !waitIdle(url)) {
                System.out.println("the model stayed busy for 5 minutes: stopping");
                return;
            }
            Object[] r = complete(url, model, key, prompt(next, batch));
            if (r != null && r[0] instanceof String text) {
                long in = (long) r[1] < 0 ? prompt(next, batch).length() / 4 : (long) r[1];
                long out = (long) r[2] < 0 ? text.length() / 4 : (long) r[2];
                spent += in * priceIn / 1e6 + out * priceOut / 1e6;
                List<String> candidates = new ArrayList<>();
                for (String line : text.split("\\R")) {
                    String c = clean(line);
                    if (!c.isBlank() && candidates.size() < batch) {
                        candidates.add(c); // the model wrote more than asked: keep the first ones
                    }
                }
                Set<Integer> off = o.containsKey("no-verify") ? Set.of() : offIntent(url, model, key, next, candidates);
                int got = 0;
                for (int i = 0; i < candidates.size(); i++) {
                    if (off.contains(i + 1)) {
                        rejected++;
                        rejectReasons.merge("off intent", 1, Integer::sum);
                        Files.writeString(state.resolve("rejects.tsv"), next.key() + "\toff intent\t" + candidates.get(i)
                                + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                        continue;
                    }
                    if (offer(next, candidates.get(i))) {
                        got++;
                    }
                }
                made += got;
                System.out.printf(Locale.ROOT, "%4d %-60s +%d (bank %d, rejected %d)%n", call + 1, next.key(), got,
                        total + got, rejected);
            } else {
                // an empty cell answer still counts, so a cell the model cannot fill does not block the rest
                perCell.merge(next.key(), 1, Integer::sum);
            }
            Thread.sleep(pause);
        }
        System.out.println("made " + made + " lines; rejected " + rejected + " " + rejectReasons);
    }

    /**
     * The next cell to fill: the intent with the fewest lines first (so every intent grows together), then its
     * least-filled cell, ties broken at random (voices and places spread out). Null when every cell is full.
     */
    Cell pickCell(List<Cell> cells, int perCellTarget, java.util.Random rng) {
        Map<String, Integer> byIntent = new HashMap<>();
        perCell.forEach((k, v) -> byIntent.merge(k.substring(0, k.indexOf('|')), v, Integer::sum));
        String intent = null;
        int fewest = Integer.MAX_VALUE;
        for (Cell c : cells) {
            if (perCell.getOrDefault(c.key(), 0) >= perCellTarget) {
                continue;
            }
            int n = byIntent.getOrDefault(c.intent(), 0);
            if (n < fewest) {
                fewest = n;
                intent = c.intent();
            }
        }
        if (intent == null) {
            return null;
        }
        List<Cell> best = new ArrayList<>();
        int least = Integer.MAX_VALUE;
        for (Cell c : cells) {
            int have = perCell.getOrDefault(c.key(), 0);
            if (!c.intent().equals(intent) || have >= perCellTarget) {
                continue;
            }
            if (have < least) {
                least = have;
                best.clear();
            }
            if (have == least) {
                best.add(c);
            }
        }
        return best.get(rng.nextInt(best.size()));
    }

    /**
     * The verifier: a second, cheap call asks the model which lines do NOT do what the cell is for (the generator
     * sometimes drifts: a thank-you under "insult back", a voice example copied). Their numbers (1-based); on any
     * failure, none (the other checks still apply).
     */
    Set<Integer> offIntent(String url, String model, String key, Cell cell, List<String> lines) throws InterruptedException {
        if (lines.isEmpty()) {
            return Set.of();
        }
        StringBuilder p = new StringBuilder("Each numbered chat line below should be what a MapleStory player types when ")
                .append(INTENTS.get(cell.intent())[0]).append(". Toward the other player: ")
                .append(AFFINITY_WORDS.getOrDefault(cell.affinity(), "")).append(".\n");
        for (int i = 0; i < lines.size(); i++) {
            p.append(i + 1).append(". ").append(lines.get(i)).append('\n');
        }
        p.append("A line does NOT fit if it has another purpose, is what the OTHER player would say (the wrong side), ")
                .append("makes no sense, or piles up accent or slang words. ")
                .append("Reply with only the numbers of the lines that do NOT fit, comma separated, or none.");
        Object[] r = complete(url, model, key, p.toString());
        Set<Integer> out = new HashSet<>();
        if (r != null && r[0] instanceof String text && !text.toLowerCase(Locale.ROOT).contains("none")) {
            Matcher m = Pattern.compile("\\b(\\d{1,2})\\b").matcher(text);
            while (m.find()) {
                out.add(Integer.parseInt(m.group(1)));
            }
        }
        return out;
    }

    /** Writes the gzip bank: every accepted line. */
    void pack(Path out) throws IOException {
        Files.createDirectories(out.toAbsolutePath().getParent());
        List<String> rows = Files.exists(state.resolve("lines.tsv"))
                ? Files.readAllLines(state.resolve("lines.tsv"), StandardCharsets.UTF_8) : List.of();
        try (OutputStream raw = Files.newOutputStream(out); GZIPOutputStream gz = new GZIPOutputStream(raw);
             BufferedWriter w = new BufferedWriter(new java.io.OutputStreamWriter(gz, StandardCharsets.UTF_8))) {
            w.write(ChatBank.HEADER_V2 + "\tid\tintent\ttone\taffinity\tvoice\ttags\ttext\n");
            for (String row : rows) {
                if (!row.isBlank() && !row.startsWith("#")) {
                    w.write(withClaimTags(row));
                    w.write('\n');
                }
            }
        }
        System.out.println("packed " + rows.size() + " lines into " + out + " (" + Files.size(out) / 1024 + " KB)");
    }

    /**
     * rc6 say-do: a lines.tsv row with its claim tags added to the tags field ({@code claim:resupply} ...), so the
     * server knows at pick time which lines promise an action ({@link ChatBank#claimTags}).
     */
    static String withClaimTags(String row) {
        String[] f = row.split("\t", 7);
        if (f.length != 7) {
            return row;
        }
        List<String> tags = new ArrayList<>();
        if (!f[5].isBlank() && !"-".equals(f[5].trim())) {
            for (String t : f[5].split(",")) {
                if (!t.trim().startsWith("claim:")) {
                    tags.add(t.trim());
                }
            }
        }
        tags.addAll(ChatBank.claimTags(f[6]));
        f[5] = tags.isEmpty() ? "-" : String.join(",", tags);
        return String.join("\t", f);
    }

    void stats() {
        Map<String, Integer> byIntent = new java.util.TreeMap<>();
        perCell.forEach((k, v) -> byIntent.merge(k.split("\\|")[0], v, Integer::sum));
        System.out.println("lines " + perCell.values().stream().mapToInt(Integer::intValue).sum() + " in "
                + perCell.size() + " cells");
        byIntent.forEach((k, v) -> System.out.printf("  %-22s %d%n", k, v));
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("usage: ChatBankGen seed|gen|player|pack|stats [--state dir] [options] (see the class comment)");
            return;
        }
        Map<String, String> o = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String k = args[i].substring(2);
                String v = i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "true";
                o.put(k, v);
            }
        }
        Path state = Path.of(o.getOrDefault("state", "tools/chatbank/state"));
        if (args[0].equals("player")) {
            new ChatPairGen(state, Path.of(o.getOrDefault("repo-root", "."))).generate(o);
            return;
        }
        ChatBankGen gen = new ChatBankGen(state);
        switch (args[0]) {
            case "seed" -> System.out.println("seeded " + gen.seed() + " lines (rejected " + gen.rejected + " "
                    + gen.rejectReasons + ")");
            case "gen" -> gen.generate(o);
            case "pack" -> gen.pack(Path.of(o.getOrDefault("out", ChatBank.RESOURCE)));
            case "stats" -> {
                gen.stats();
                if (Files.exists(state.resolve(ChatPairGen.PAIRS))) {
                    new ChatPairGen(state, null).stats();
                }
            }
            default -> System.out.println("unknown command " + args[0]);
        }
    }
}
