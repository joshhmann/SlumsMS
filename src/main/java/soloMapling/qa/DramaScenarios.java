package soloMapling.qa;

import client.Character;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static soloMapling.qa.QaLog.Topic.AFFINITY;
import static soloMapling.qa.QaLog.Topic.BOT_CHAT;
import static soloMapling.qa.QaLog.Topic.ROMANCE;

/**
 * 1.5.0 bot drama scenarios: romance (rm-1 .. rm-4). Every staged bot is an on-demand bot (memory only) and leaves at
 * stop; an engagement or marriage to a staged bot is undone at stop; the GM's {@code @romance} setting is put back.
 * The steps are the {@code !botromance} actions (same code).
 */
final class DramaScenarios {

    static final int AMORIA = 680000000;

    private DramaScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new Rm1Dating(), new Rm2Wedding(), new Rm3Breakup(), new Rm4Objection());
    }

    /** Shared: a drama step logged as STEP lines; staged bots and the romance setting undone at stop. */
    abstract static class DramaScenario implements QaScenario {
        final String area;

        DramaScenario(String area) {
            this.area = area;
        }

        public Set<QaLog.Topic> topics() {
            return EnumSet.of(ROMANCE, AFFINITY, BOT_CHAT);
        }

        List<String> act(QaContext ctx, String... words) {
            List<String> out = ctx.world().dramaAction(ctx.gm(), area, words);
            for (String line : out) {
                ctx.run().note("STEP", String.join(" ", words) + ": " + line);
            }
            return out;
        }

        void unstageAtStop(QaContext ctx) {
            ctx.onStop("take the staged drama bots away", () -> ctx.run().note("CLEANUP", "unstaged "
                    + ctx.world().dramaUnstage(ctx.run().gmId()) + " bot(s)"));
        }

        /** @romance on for the run, put back as it was at stop. */
        void romanceOnForRun(QaContext ctx) {
            boolean was = ctx.world().romanceOn(ctx.gm());
            act(ctx, "on");
            ctx.onStop("@romance back " + (was ? "on" : "off"), () -> {
                Character now = ctx.gmNow();
                if (now != null && !was) {
                    ctx.world().dramaAction(now, "romance", "off");
                }
            });
        }

        void requireLevel50(QaContext ctx) {
            if (ctx.gm().getLevel() < 50) {
                throw new QaRefusal("Proposals need level 50+ on both sides (the stock rule): !level 50 first.");
            }
        }

        /** A step the scenario allows, else a refusal naming them. */
        List<String> step(QaContext ctx, String name, List<String> allowed, String... extra) {
            if (!allowed.contains(name)) {
                throw new QaRefusal(id() + " steps: " + String.join(", ", allowed));
            }
            List<String> words = new ArrayList<>();
            words.add(name);
            words.addAll(List.of(extra));
            return act(ctx, words.toArray(new String[0]));
        }
    }

    /** rm-1: a bot with a crush flirts and asks the GM out; dating: follows, couple chair, whispers, jealousy. */
    static final class Rm1Dating extends DramaScenario {
        Rm1Dating() {
            super("romance");
        }

        public String id() { return "rm-1"; }
        public String title() { return "Bot romance: flirting, asking out, dating (follow, chair, whispers, jealousy) (1.5.0)"; }
        public String usage() { return "; then !qa do ask | date | whisper | follow | jealous | step | off"; }
        public int tickSeconds() { return 30; }

        public List<String> stage(QaContext ctx) {
            romanceOnForRun(ctx);
            unstageAtStop(ctx);
            List<String> out = new ArrayList<>(act(ctx, "stage", "crush"));
            out.add("A bot next to you has a crush on you. Stand near it: it flirts (FLIRT) and, within a few minutes,");
            out.add("asks you out (ASK OUT) - say yes or no in map chat. !qa do ask makes it ask now; or ask it yourself:");
            out.add("\"<name> wanna go out with me?\" (DATING). Dating: change map - it follows (FOLLOW, every time in a QA");
            out.add("run); sit on a couple chair - it sits with you (CHAIR); !qa do whisper (WHISPER; never while you trade,");
            out.add("shop, talk to an NPC or chat with a bot); !qa do jealous (a rival shows up: JEALOUS). !qa do off: nothing");
            out.add("flirts any more (@romance off, the default). Stop puts your @romance setting back.");
            return out;
        }

        public void tick(QaContext ctx) {
            ctx.world().dramaAction(ctx.gm(), "romance", "step");
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            return switch (name) {
                case "date" -> act(ctx, "stage", "dating");
                case "off" -> act(ctx, "off");
                default -> step(ctx, name, List.of("ask", "whisper", "follow", "jealous", "step", "off", "date"));
            };
        }
    }

    /** rm-2: a partner and a ring box: the GM proposes (or the bot does), the Amoria wedding, divorce. */
    static final class Rm2Wedding extends DramaScenario {
        Rm2Wedding() {
            super("romance");
        }

        public String id() { return "rm-2"; }
        public String title() { return "Marry a bot: ring-box proposal (or it proposes), Amoria wedding, divorce (1.5.0)"; }
        public String usage() { return "; then !qa do propose | wedding | divorce"; }
        public int minutes(String[] args) { return 20; }

        public List<String> stage(QaContext ctx) {
            requireLevel50(ctx);
            romanceOnForRun(ctx);
            unstageAtStop(ctx);
            List<String> out = new ArrayList<>(act(ctx, "stage", "dating", "60"));
            ctx.giveBack(2240000, 1);
            out.add("Your partner (lv 60) stands next to you and you hold a Moonstone engagement ring box.");
            out.add("Propose: use the box on it (the stock window) - a few seconds later it says yes (ENGAGED: a token in");
            out.add("your ETC). Or !qa do propose: it proposes to you, accept in the window. Then !qa do wedding: you go to");
            out.add("Amoria, guests (your friend bots + new faces) come, say \"i do\" when asked (MARRIED: a wedding ring with");
            out.add("its ring id in your inventory; equip it for the ring look). Spouse chat reaches it (offline: \"Your spouse");
            out.add("is currently offline\"). !qa do divorce (ring in the equip inventory). Stop undoes it all.");
            return out;
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if ("wedding".equals(name)) {
                List<String> out = new ArrayList<>();
                if (ctx.gm().getMapId() != AMORIA) {
                    ctx.world().warp(ctx.gm(), AMORIA);
                    out.add("warped to Amoria");
                }
                out.addAll(act(ctx, "wedding"));
                return out;
            }
            return step(ctx, name, List.of("propose", "wedding", "divorce", "stopwedding", "status"));
        }
    }

    /** rm-3: neglect, the breakup megaphone, the ex as a rival, a rebound, two-timing found out, an old flame. */
    static final class Rm3Breakup extends DramaScenario {
        Rm3Breakup() {
            super("romance");
        }

        public String id() { return "rm-3"; }
        public String title() { return "Romance drama: neglect breakup, ex rival + rebound, two-timing, old flame, gossip (1.5.0)"; }
        public String usage() { return "; then !qa do neglect | breakup | second | confront | flame | gossip"; }

        public List<String> stage(QaContext ctx) {
            romanceOnForRun(ctx);
            unstageAtStop(ctx);
            List<String> out = new ArrayList<>(act(ctx, "stage", "dating"));
            out.add("A bot is dating you. !qa do neglect: its romance moves 5 days back - a sulky whisper (NEGLECT).");
            out.add("!qa do breakup: the full breakup - \"I'M DONE WITH YOU <you> </3\" megaphone (through the gate), a");
            out.add("whisper, it turns rival (trash talk about the game only), and ~20 s later it rebounds with another bot");
            out.add("(REBOUND). !qa do second: a second bot dates you too; !qa do confront: they find out and have it out in");
            out.add("public chat on your map (TWO-TIMING), one dumps you. !qa do flame: your ex comes back as an old flame.");
            out.add("!qa do gossip: a bot watching writes \"saw <you> with <partner>\" (GOSSIP). @botdrama off: quiet breakups.");
            return out;
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            return switch (name) {
                case "second" -> act(ctx, "stage", "second");
                case "flame" -> {
                    List<String> out = new ArrayList<>(act(ctx, "flame"));
                    if (out.stream().anyMatch(l -> l.contains("is not"))) {
                        out.add("(the latest staged bot is not your ex: !qa do breakup first)");
                    }
                    yield out;
                }
                default -> step(ctx, name, List.of("neglect", "breakup", "second", "confront", "flame", "gossip", "step"));
            };
        }
    }

    /** rm-4: a wedding with an ex in the crowd: I OBJECT, a crashed reception, or a runaway partner. */
    static final class Rm4Objection extends DramaScenario {
        Rm4Objection() {
            super("romance");
        }

        public String id() { return "rm-4"; }
        public String title() { return "Wedding drama: I OBJECT, the ex crashes the reception, a runaway partner (1.5.0)"; }
        public String usage() { return "; then !qa do propose | wedding | runaway"; }
        public int minutes(String[] args) { return 20; }

        public List<String> stage(QaContext ctx) {
            requireLevel50(ctx);
            romanceOnForRun(ctx);
            unstageAtStop(ctx);
            List<String> out = new ArrayList<>(act(ctx, "stage", "ex"));
            out.addAll(act(ctx, "stage", "dating", "60"));
            act(ctx, "object");
            act(ctx, "crash");
            out.add("Your ex and your current partner (lv 60) stand here. !qa do propose (accept the window), then");
            out.add("!qa do wedding: at \"speak now\" your ex shouts I OBJECT (OBJECTION), your partner snaps back, the");
            out.add("officiant moves on; after \"married\" someone crashes the reception (CRASH). !qa do runaway before the");
            out.add("wedding: your partner runs from the altar instead (RUNAWAY - no marriage, still engaged).");
            out.add("@botdrama off: none of this drama happens. Stop undoes the engagement or marriage.");
            return out;
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if ("wedding".equals(name)) {
                List<String> out = new ArrayList<>();
                if (ctx.gm().getMapId() != AMORIA) {
                    ctx.world().warp(ctx.gm(), AMORIA);
                    out.add("warped to Amoria");
                }
                out.addAll(act(ctx, "wedding"));
                return out;
            }
            return step(ctx, name, List.of("propose", "wedding", "runaway", "object", "crash", "stopwedding", "divorce"));
        }
    }
}
