package soloMapling.qa;

import client.Character;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static soloMapling.qa.QaLog.Topic.AFFINITY;
import static soloMapling.qa.QaLog.Topic.BOT_CHAT;
import static soloMapling.qa.QaLog.Topic.CONVO;
import static soloMapling.qa.QaLog.Topic.TRADE;

/**
 * Ticket 59: natural conversations (cv-1 .. cv-8). Every staged bot is an on-demand bot that counts the GM as the
 * given state (ticket 58's staging), memory only, and leaves at stop. The CONVO topic logs each line's read (SENSE /
 * INTENT / TAG), what the gates decided, every bot line said ({@code SAID} with its tier, bank id, voice and latency),
 * the timing plan ({@code TIMING}), threads ({@code THREAD}, {@code JOIN}) and ratings ({@code RATE}).
 */
final class ConvoScenarios {

    static final int HENESYS = 100000000;

    private ConvoScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new Cv1Actions(), new Cv2Haggle(), new Cv3Mood(), new Cv4Voices(), new Cv5Thread(), new Cv6Rate(),
                new Cv7Bank(), new Cv8FollowUp(), new Cv9Hold());
    }

    /** Shared: staged bots per GM, undone at stop. */
    abstract static class ConvoScenario implements QaScenario {
        final Map<Integer, List<Integer>> bots = new ConcurrentHashMap<>();

        public Set<QaLog.Topic> topics() {
            return EnumSet.of(CONVO, BOT_CHAT, AFFINITY);
        }

        int stage(QaContext ctx, String state, boolean training, String voice) {
            QaWorld w = ctx.world();
            if (training && w.liveMobCount(ctx.gm()) == 0) {
                if (!w.warp(ctx.gm(), QaScenarios.HENESYS_HUNTING_GROUND)) {
                    throw new QaRefusal("Cannot warp to a map with mobs.");
                }
                ctx.log("warped to " + QaScenarios.HENESYS_HUNTING_GROUND + " (needs mobs)");
            }
            int id = w.stageConvoBot(ctx.gm(), state, training, voice);
            if (id < 0) {
                throw new QaRefusal("No bot could be staged here.");
            }
            bots.computeIfAbsent(ctx.run().gmId(), k -> {
                List<Integer> list = new CopyOnWriteArrayList<>();
                ctx.onStop("unstage the chat bots", () -> {
                    for (int b : list) {
                        w.unstageConvoBot(b);
                    }
                    bots.remove(ctx.run().gmId());
                });
                return list;
            }).add(id);
            ctx.log("staged " + (training ? "training" : "social") + " bot " + id + " as the GM's " + state
                    + (voice == null ? "" : ", voice " + voice));
            for (String line : w.convoAction(ctx.gm(), "persona", List.of(id), new String[0])) {
                ctx.run().note("PERSONA", line);
            }
            return id;
        }

        List<Integer> staged(QaContext ctx) {
            return bots.getOrDefault(ctx.run().gmId(), List.of());
        }

        List<String> world(QaContext ctx, String action, String[] args) {
            List<String> out = ctx.world().convoAction(ctx.gm(), action, staged(ctx), args);
            for (String l : out) {
                ctx.run().note("STEP", l);
            }
            return out;
        }

        static String word(String[] args, String fallback, String... allowed) {
            for (String a : args) {
                for (String ok : allowed) {
                    if (ok.equalsIgnoreCase(a)) {
                        return ok;
                    }
                }
            }
            return fallback;
        }
    }

    /** cv-1: ask a bot by chat to party, follow, come to a map, help or buff. */
    static final class Cv1Actions extends ConvoScenario {
        public String id() { return "cv-1"; }
        public String title() { return "Ask a bot by chat: party, follow, come to a map, help, buff (ticket 59)"; }
        public String usage() { return "[social|training]; then !qa do sense <line>"; }

        public List<String> stage(QaContext ctx) {
            boolean training = "training".equals(word(ctx.args(), "social", "social", "training"));
            int id = stage(ctx, "friend", training, null);
            return List.of(QaScenarios.who(ctx, id) + " counts you as a friend. Name it, then ask in plain words:",
                    "'wanna party?' (party rules), 'come to henesys' or 'meet me at the fm' (a social bot walks there),",
                    training ? "'help me kill these' (it grinds next to you), 'can u buff me' (its real buff, if its job has one)."
                            : "'show me the way to perion' (it walks ahead: follow it), 'add me' (a real buddy request).",
                    "Each line: INTENT ... -> action: YES/NO (reason) -> outcome, then SAID with the words. Words match the act.",
                    "!qa do sense <line> shows how a line is read (intent, tone, names) without a bot.");
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if ("sense".equals(name)) {
                return world(ctx, "sense", args);
            }
            throw new QaRefusal("cv-1 steps: sense <line>");
        }
    }

    /** cv-2: haggle by chat with a bot that carries an item. */
    static final class Cv2Haggle extends ConvoScenario {
        static final int WORK_GLOVES = 1082002;

        public String id() { return "cv-2"; }
        public String title() { return "Haggle by chat: a quote, your counter, the price band answers (ticket 59)"; }
        public String usage() { return "[item id]"; }

        public Set<QaLog.Topic> topics() {
            return EnumSet.of(CONVO, BOT_CHAT, TRADE);
        }

        public List<String> stage(QaContext ctx) {
            int item = WORK_GLOVES;
            for (String a : ctx.args()) {
                if (a.matches("\\d{7}")) {
                    item = Integer.parseInt(a);
                }
            }
            int id = stage(ctx, "neutral", false, null);
            String name = ctx.world().itemName(item);
            if (ctx.world().giveBotItem(id, item, 1) <= 0) {
                throw new QaRefusal("Could not give the bot item " + item + ".");
            }
            ctx.log("gave bot " + id + " item " + item + " (" + name + ")");
            return List.of(QaScenarios.who(ctx, id) + " carries " + name + ".",
                    "Ask it: '<name> how much for the " + (name == null ? "item" : name.toLowerCase()) + "' -> QUOTE in the log.",
                    "Counter: 'would u do <lower>' or 'thats too much' -> the band's answer (accept, counter, last price).",
                    "The number is always PriceBand's; the words come from the model or the bank. Open a trade to buy.");
        }
    }

    /** cv-3: apology and insult with a rival bot, and a flirt. */
    static final class Cv3Mood extends ConvoScenario {
        public String id() { return "cv-3"; }
        public String title() { return "Apology, insult, flirt: words that move a bot's feelings (ticket 59)"; }
        public String usage() { return "[rival|nemesis|friend]"; }

        public List<String> stage(QaContext ctx) {
            String state = word(ctx.args(), "rival", "rival", "nemesis", "friend");
            int id = stage(ctx, state, true, null);
            return List.of(QaScenarios.who(ctx, id) + " counts you as its " + state + ".",
                    "Say 'sorry for ksing u' -> INTENT apology -> makeup: YES and EVENT apology (a rival's grudge eases).",
                    "Say 'ur trash noob' -> tone rude, EVENT rude_chat, and game-only trash talk back (if @botdrama is on).",
                    "Say 'ur cute' -> a flirt back only with @romance on and a bot that may flirt; else it stays friendly.",
                    "No numbers are ever shown to you; the AFFINITY lines carry the score for the log only.");
        }
    }

    /** cv-4: four voices side by side, and their timing. */
    static final class Cv4Voices extends ConvoScenario {
        public String id() { return "cv-4"; }
        public String title() { return "Personas and timing: four bots in four voices (ticket 59)"; }
        public String usage() { return "; then !qa do persona"; }

        public List<String> stage(QaContext ctx) {
            List<Integer> ids = new ArrayList<>();
            for (String voice : List.of("lower", "proper", "leet", "korean")) {
                ids.add(stage(ctx, "neutral", false, voice));
            }
            return List.of("Four bots next to you: lowercase, proper typer, xX leet kid, Korean-flavoured: " + ids.stream().map(i -> QaScenarios.who(ctx, i)).toList() + ".",
                    "Talk to each by name with the same line: the words, casing and laugh differ; a catchphrase now and then.",
                    "TIMING lines show the plan: typed 1-6 s by length, sometimes split, late or brb; never faster than a",
                    "person could type. !qa do persona prints each card again.");
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if ("persona".equals(name)) {
                return world(ctx, "persona", args);
            }
            throw new QaRefusal("cv-4 steps: persona");
        }
    }

    /** cv-5: a bot thread on the GM's map; the GM jumps in. */
    static final class Cv5Thread extends ConvoScenario {
        public String id() { return "cv-5"; }
        public String title() { return "A bot-to-bot thread you can join (ticket 59)"; }
        public String usage() { return "; then !qa do thread [beef|gossip|lfg|trade|training]"; }

        public List<String> stage(QaContext ctx) {
            if (ctx.gm().getMapId() != HENESYS && !ctx.world().warp(ctx.gm(), HENESYS)) {
                throw new QaRefusal("Cannot warp to Henesys.");
            }
            int a = stage(ctx, "neutral", false, null);
            int b = stage(ctx, "neutral", false, null);
            List<String> out = new ArrayList<>(world(ctx, "thread", new String[]{"lfg"}));
            out.add(0, QaScenarios.who(ctx, a) + " and " + QaScenarios.who(ctx, b) + " start talking now (THREAD lines). Within 45 s of the last line,");
            out.add(1, "say something without a name that answers it ('lpq? i can come too'): JOIN, and the last speaker"
                    + " answers you.");
            out.add(2, "!qa do thread <topic> starts another (beef needs bots of guilds at war: it falls back to gossip).");
            return out;
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if ("thread".equals(name)) {
                return world(ctx, "thread", args);
            }
            throw new QaRefusal("cv-5 steps: thread [topic]");
        }
    }

    /** cv-6: !rate on the last line. */
    static final class Cv6Rate extends ConvoScenario {
        public String id() { return "cv-6"; }
        public String title() { return "!rate good|bad on the last line a bot said to you (ticket 59)"; }
        public String usage() { return "; then !qa do last"; }

        public List<String> stage(QaContext ctx) {
            int id = stage(ctx, "friend", false, null);
            return List.of(QaScenarios.who(ctx, id) + " is next to you. Talk to it, then:",
                    "!rate good  -> RATE good (a model line joins the good examples; a bank line weighs x1.5)",
                    "!rate bad too formal \"certainly\"  -> RATE bad: a model line joins the bad examples and \"certainly\"",
                    "the don't-say list in data/convo/tune.json; a bank line weighs x0.25, a second bad blacklists it.",
                    "!rate last shows the line and where it came from; !qa do last logs it.");
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if ("last".equals(name)) {
                return world(ctx, "last", args);
            }
            throw new QaRefusal("cv-6 steps: last");
        }
    }

    /**
     * cv-8 (owner report 2026-10-22): a bot asks the map a scripted question; the GM answers without a name. ROUTE
     * shows which bot took the answer and why, PROMPT the exact prompt it was asked with (transcript, "You just said",
     * history), SAID its reply.
     */
    static final class Cv8FollowUp extends ConvoScenario {
        public String id() { return "cv-8"; }
        public String title() { return "A bot asks the map a question; your nameless answer gets a follow-up (convo fix)"; }
        public String usage() { return "[question...]; then !qa do ask [question...]"; }

        public List<String> stage(QaContext ctx) {
            int id = stage(ctx, "neutral", false, null);
            List<String> out = new ArrayList<>(world(ctx, "ask", ctx.args()));
            out.add(0, QaScenarios.who(ctx, id) + " is next to you and has never talked to you.");
            out.add("Answer it in map chat WITHOUT its name (e.g. 'im from the east'), within 60 s.");
            out.add("Log: ROUTE (who took your line and which of its lines it answers), PROMPT (the exact prompt:");
            out.add("system || history || the transcript with its own lines marked (you) and 'You just said ...'), SAID.");
            out.add("The reply must follow up on its own question. !qa do ask [question] asks again.");
            return out;
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if ("ask".equals(name)) {
                return world(ctx, "ask", args);
            }
            throw new QaRefusal("cv-8 steps: ask [question]");
        }
    }

    /** cv-9 (owner report 2026-10-24): the bot you are talking to walked away mid-conversation. */
    static final class Cv9Hold extends ConvoScenario {
        public String id() { return "cv-9"; }
        public String title() { return "A bot you are talking to stays with you (conversation hold, 25 s of samples)"; }
        public String usage() { return "[line...]; then !qa do sample"; }

        public int minutes(String[] args) { return 4; }

        public List<String> stage(QaContext ctx) {
            int id = stage(ctx, "friend", false, null);
            List<String> out = new ArrayList<>();
            out.add(QaScenarios.who(ctx, id) + " is next to you. !qa do hold says a line to it and starts the wander");
            out.add("(BotWanderSystem.start, the one a town wanderer's ensureRoaming uses) that used to carry it off.");
            out.add("!qa do sample logs the distance again. Log: HOLD <bot>: <px> px from you, <s> s of hold left,");
            out.add("with you | WANDERED OFF (150 px). It must say \"with you\" for the 25 s after the last line.");
            out.add("Auto-QA runs it as t-cv-8 (cv-9, 5 x 5 s samples).");
            return out;
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            return switch (name) {
                case "hold" -> world(ctx, "hold", args);
                case "sample" -> world(ctx, "sample", args);
                default -> throw new QaRefusal("cv-9 steps: hold [line], sample");
            };
        }
    }

    /** cv-7: bank mode - chat without the LLM, to compare. */
    static final class Cv7Bank extends ConvoScenario {
        public String id() { return "cv-7"; }
        public String title() { return "Bank mode: chat answered from the line bank only, LLM off (ticket 59)"; }
        public String usage() { return "; then !qa do llm | bank"; }

        public List<String> stage(QaContext ctx) {
            List<String> was = world(ctx, "bank", new String[]{"on"});
            ctx.onStop("bank mode off", () -> ctx.world().convoAction(ctx.gmNow() == null ? ctx.gm() : ctx.gmNow(), "bank",
                    List.of(), new String[]{"off"}));
            int id = stage(ctx, "friend", false, null);
            List<String> out = new ArrayList<>(was);
            out.add(QaScenarios.who(ctx, id) + " is next to you. Bank mode is ON for the whole server while this runs: every chat");
            out.add("reply comes from the line bank (SAID ... bank#id) or a script, in the bot's voice, never repeating.");
            out.add("!qa do llm switches the LLM back on to compare the same lines; !qa do bank switches it off again.");
            return out;
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            return switch (name) {
                case "llm" -> world(ctx, "bank", new String[]{"off"});
                case "bank" -> world(ctx, "bank", new String[]{"on"});
                default -> throw new QaRefusal("cv-7 steps: llm, bank");
            };
        }
    }
}
