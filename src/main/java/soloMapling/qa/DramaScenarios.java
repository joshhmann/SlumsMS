package soloMapling.qa;

import client.Character;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static soloMapling.qa.QaLog.Topic.AFFINITY;
import static soloMapling.qa.QaLog.Topic.BOT_CHAT;
import static soloMapling.qa.QaLog.Topic.BOT_GUILD;
import static soloMapling.qa.QaLog.Topic.GUILD_WAR;
import static soloMapling.qa.QaLog.Topic.RIVALRY;
import static soloMapling.qa.QaLog.Topic.ROMANCE;

/**
 * 1.5.0 bot drama scenarios: romance (rm-1 .. rm-4), rivals (rv-1 .. rv-4), guild wars (gw-1 .. gw-4). Every staged bot is an on-demand bot (memory only) and leaves at
 * stop; an engagement or marriage to a staged bot is undone at stop; the GM's {@code @romance} setting is put back.
 * The steps are the {@code !botromance} actions (same code).
 */
final class DramaScenarios {

    static final int AMORIA = 680000000;
    static final int HUNTING_GROUND = QaScenarios.HENESYS_HUNTING_GROUND;

    private DramaScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new Rm1Dating(), new Rm2Wedding(), new Rm3Breakup(), new Rm4Objection(),
                new Rv1Roast(), new Rv2KsWar(), new Rv3Callout(), new Rv4Revenge(),
                new Gw1BotWar(), new Gw2PlayerWar(), new Gw3Poach(), new Gw4Alliance());
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

    // ---- rivals (rv-1 .. rv-4) ----

    /** A rival (or nemesis) training bot held on a map with mobs (the GM is warped to one if needed). */
    abstract static class RivalScenario extends DramaScenario {
        RivalScenario() {
            super("rival");
        }

        public Set<QaLog.Topic> topics() {
            return EnumSet.of(RIVALRY, AFFINITY, BOT_CHAT);
        }

        List<String> stageRival(QaContext ctx, String state) {
            if (ctx.world().liveMobCount(ctx.gm()) == 0) {
                if (!ctx.world().warp(ctx.gm(), HUNTING_GROUND)) {
                    throw new QaRefusal("Cannot warp to " + HUNTING_GROUND + ".");
                }
                ctx.log("warped to " + HUNTING_GROUND + " (needs mobs)");
            }
            unstageAtStop(ctx);
            return new ArrayList<>(act(ctx, "stage", state));
        }
    }

    /** rv-1: roasts from the GM's real game facts (level vs map, deaths, gear, job, KS). */
    static final class Rv1Roast extends RivalScenario {
        public String id() { return "rv-1"; }
        public String title() { return "Rival roasts: trash talk from your real level, map, deaths, gear, job (1.5.0)"; }
        public String usage() { return "; then !qa do roast | die | nemesis"; }

        public List<String> stage(QaContext ctx) {
            List<String> out = stageRival(ctx, "rival");
            out.addAll(act(ctx, "roast"));
            out.add("A rival training bot stands here and roasted you (ROAST, with the facts it used). Its taunts on");
            out.add("sight are roasts too (live: one per 20 min per pair). !qa do roast for another; !qa do die counts a");
            out.add("death for today (the roasts pick it up); !qa do nemesis stages a nemesis. With the chat model live,");
            out.add("half the roasts are model lines (ROAST (model)) checked by the content guard: only the game, never");
            out.add("looks, family, real life or slurs.");
            return out;
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            return switch (name) {
                case "nemesis" -> act(ctx, "stage", "nemesis");
                default -> step(ctx, name, List.of("roast", "die", "status"));
            };
        }
    }

    /** rv-2: a KS war: the rival calls 2-4 bots to KS the GM on this map for a few minutes, then they leave. */
    static final class Rv2KsWar extends RivalScenario {
        public String id() { return "rv-2"; }
        public String title() { return "KS war: a rival calls 2-4 bots to KS you for a few minutes, then they leave (1.5.0)"; }
        public String usage() { return "[small]; then !qa do fight | kswar [2-4] | endwar"; }

        /**
         * rc6 (QA t-rv-2: "the 3 bots are just finding their own lanes and killing ... maybe in other maps with less
         * lanes is better"): {@code small} holds the war on a compact field (the Rain-Forest East of Henesys: three
         * bands of low mobs, Map.wz 100020000), where the callers have to crowd into the GM's own lane.
         */
        static final int SMALL_MAP = 100020000;

        public List<String> stage(QaContext ctx) {
            if (Arrays.stream(ctx.args()).anyMatch("small"::equalsIgnoreCase)) {
                if (!ctx.world().warp(ctx.gm(), SMALL_MAP)) {
                    throw new QaRefusal("Cannot warp to " + SMALL_MAP + ".");
                }
                ctx.log("warped to " + SMALL_MAP + " (a field with few lanes)");
            }
            List<String> out = stageRival(ctx, "nemesis");
            ctx.onStop("end the KS war", () -> ctx.world().dramaAction(ctx.gmNow() != null ? ctx.gmNow() : ctx.gm(),
                    "rival", "endwar"));
            out.add("A nemesis training bot fights here. Start fighting, then !qa do kswar [2-4]: it calls guildmates or");
            out.add("friends (KS WAR, with who came), they show up next to you and each takes up to 5 of your mobs in");
            out.add("its own reach (KS lines) for 3 minutes, then they say something smug and go back (KS WAR over).");
            out.add("Live: at most once per player per day, 3 an hour world-wide (the QA step skips the caps). Nobody");
            out.add("blocks a map; @botdrama off: no KS war.");
            return out;
        }

        /** GMs whose run "fights": every tick they hit the mobs around them again (new spawns, the ones still up). */
        final Set<Integer> fighting = java.util.concurrent.ConcurrentHashMap.newKeySet();

        /** How far the GM's "fight" reaches: past where the called bots stand (60-180 px) plus their KS reach. */
        static final int FIGHT_X = 500, FIGHT_Y = 120, FIGHT_MOBS = 8;

        public int tickSeconds() {
            return 5;
        }

        public void tick(QaContext ctx) {
            if (fighting.contains(ctx.run().gmId()) && ctx.gmNow() != null) {
                ctx.world().playerHitsMobs(ctx.gmNow(), FIGHT_X, FIGHT_Y, FIGHT_MOBS);
            }
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if ("kswar".equals(name)) {
                return act(ctx, "kswar", args.length > 0 ? args[0] : "3");
            }
            if ("fight".equals(name)) {
                // rc6 auto-QA triage (t-rv-2: 0 of 3 called bots took a mob): a helper takes only a mob the player
                // has hit (AffinityReactions.ksPick: Monster.isAttackedBy), and the pretend player never attacks.
                // "fight" hits the mobs around the GM now and every 5 s until the run stops, as a fighting player does.
                Character gm = ctx.gmNow() != null ? ctx.gmNow() : ctx.gm();
                int n = ctx.world().playerHitsMobs(gm, FIGHT_X, FIGHT_Y, FIGHT_MOBS);
                int id = ctx.run().gmId();
                if (fighting.add(id)) {
                    ctx.onStop("stop fighting", () -> fighting.remove(id));
                }
                String line = "fighting: hit " + n + " mob(s) within " + FIGHT_X + " px (again every 5 s)";
                ctx.run().note("STEP", line);
                return List.of(line);
            }
            return step(ctx, name, List.of("kswar", "endwar", "status", "fight"));
        }
    }

    /** rv-3: a callout megaphone: "X is a KSer, dont party with them". */
    static final class Rv3Callout extends RivalScenario {
        public String id() { return "rv-3"; }
        public String title() { return "Public callout: a nemesis megaphones that you are a KSer (1.5.0)"; }
        public String usage() { return "; then !qa do callout"; }

        public List<String> stage(QaContext ctx) {
            List<String> out = stageRival(ctx, "nemesis");
            out.addAll(act(ctx, "callout"));
            out.add("A nemesis called you out by megaphone (CALLOUT: sent, or waiting for the megaphone gate - one bot");
            out.add("megaphone per 2 min world-wide). Live: once per player per day, 4 an hour world-wide, only from a");
            out.add("nemesis or a rival you KS'd a lot. @quiet megaphones hides it; @botdrama off: never.");
            return out;
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            return step(ctx, name, List.of("callout", "status"));
        }
    }

    /** rv-4: the revenge arc: the rival follows the GM once to the next map and demands an apology. */
    static final class Rv4Revenge extends RivalScenario {
        public String id() { return "rv-4"; }
        public String title() { return "Revenge arc: a rival follows you to your next map and wants an apology (1.5.0)"; }
        public String usage() { return "; then change map, say sorry (or mock it); !qa do revenge arms it again"; }

        public List<String> stage(QaContext ctx) {
            List<String> out = stageRival(ctx, "rival");
            out.addAll(act(ctx, "revenge"));
            out.add("A rival is armed to follow you. Take any portal: 5-8 s later it shows up next to you and demands");
            out.add("an apology (REVENGE). Say \"sorry\" (or \"my bad\"): it calms down (+30, back toward neutral) and goes");
            out.add("back. Mock it (\"lol cry more\"): it becomes your NEMESIS and goes back. Say nothing for 90 s: it gives");
            out.add("up. It follows only once (live: once per pair per day, 50% after a taunt or KS). !qa do revenge arms");
            out.add("it again (it must still be your rival).");
            return out;
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            return step(ctx, name, List.of("revenge", "status", "roast"));
        }
    }

    // ---- guild wars (gw-1 .. gw-4) ----

    abstract static class WarScenario extends DramaScenario {
        WarScenario() {
            super("war");
        }

        public Set<QaLog.Topic> topics() {
            return EnumSet.of(GUILD_WAR, BOT_GUILD, BOT_CHAT);
        }

        /** Wars and alliances started in the run end at stop (no result megaphone); staged guild bots leave. */
        void cleanupAtStop(QaContext ctx) {
            ctx.onStop("end the wars and alliances started here", () -> {
                Character now = ctx.gmNow();
                for (String line : ctx.world().dramaAction(now != null ? now : ctx.gm(), "war", "cleanup")) {
                    ctx.run().note("CLEANUP", line);
                }
            });
        }

        String guild(QaContext ctx, boolean withRival) {
            String wanted = ctx.args().length > 0 ? ctx.args()[0] : null;
            String g = ctx.world().pickBotGuild(wanted, withRival);
            if (g == null) {
                throw new QaRefusal("No bot guild" + (withRival ? " with a rival" : "") + " yet (!botguild list).");
            }
            return g;
        }

        int stageMember(QaContext ctx, String state, String guild) {
            return stageMember(ctx, state, guild, false);
        }

        int stageMember(QaContext ctx, String state, String guild, boolean grinder) {
            int id = grinder ? ctx.world().stageGuildGrinder(ctx.gm(), state, guild, 5)
                    : ctx.world().stageGuildBot(ctx.gm(), state, guild, 5);
            if (id < 0) {
                throw new QaRefusal("No bot could be staged in " + guild + ".");
            }
            ctx.onStop("unstage guild bot " + id, () -> ctx.world().unstageGuildBot(id));
            ctx.log("staged " + state + " member " + id + " of " + guild);
            return id;
        }

        void onAGrindMap(QaContext ctx) {
            if (ctx.world().liveMobCount(ctx.gm()) == 0) {
                if (!ctx.world().warp(ctx.gm(), HUNTING_GROUND)) {
                    throw new QaRefusal("Cannot warp to " + HUNTING_GROUND + ".");
                }
                ctx.log("warped to " + HUNTING_GROUND + " (turf counts on grind maps)");
            }
        }
    }

    /** gw-1: two rival bot guilds go to war: megaphones, turf on grind maps, taunts, a weekly score. */
    static final class Gw1BotWar extends WarScenario {
        public String id() { return "gw-1"; }
        public String title() { return "Bot guild war: declaration megaphones, turf on grind maps, taunts, score (1.5.0)"; }
        public String usage() { return "[guild]; then !qa do turf | taunt | score | end"; }

        public List<String> stage(QaContext ctx) {
            String g = guild(ctx, true);
            String rival = ctx.world().rivalOf(g);
            onAGrindMap(ctx);
            cleanupAtStop(ctx);
            // rc6 (owner QA 2026-10-23: "rival guild members just stood there"): the sides grind this map, held here
            stageMember(ctx, "neutral", g, true);
            stageMember(ctx, "neutral", g, true);
            stageMember(ctx, "neutral", rival, true);
            // "fresh": a war the pair already fights ends first, so this run's declaration (and its megaphone) happens
            List<String> out = new ArrayList<>(act(ctx, "declare", g, rival, "fresh"));
            out.addAll(act(ctx, "skirmish"));
            out.addAll(act(ctx, "turf"));
            out.add(g + " declared war on " + rival + " (WAR DECLARED; the GuildWarDeclare megaphone goes out at once in a");
            out.add("QA run, MEGAPHONE in the log; a line in both guild chats). Two of " + g + " and one of " + rival
                    + " grind this map,");
            out.add("trade jabs now (JAB) and " + g + " holds it (TURF). Every minute each grind map is held by the side with");
            out.add("more members there (+1). !qa do turf (score a minute now), !qa do taunt (a war megaphone + a turf fight:");
            out.add("a grinder goes to a map the other side holds), !qa do skirmish (jabs again), !qa do score (@guildwar),");
            out.add("!qa do end (ends it with the result megaphone).");
            return out;
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            return step(ctx, name, List.of("turf", "taunt", "skirmish", "score", "end", "list", "step"));
        }
    }

    /** gw-2: a bot guild declares war on the GM's (player) guild; @guildwar shows it; @guildwar peace ends it. */
    static final class Gw2PlayerWar extends WarScenario {
        public String id() { return "gw-2"; }
        public String title() { return "A bot guild declares war on your guild: @guildwar score, @guildwar peace (1.5.0)"; }
        public String usage() { return "[guild]; then !qa do check | turf | score | peace"; }

        public List<String> stage(QaContext ctx) {
            if (ctx.world().guildIdOf(ctx.gm()) <= 0) {
                throw new QaRefusal("Be in a (player) guild first (create one at the Guild HQ: !qa run gd-1).");
            }
            String g = guild(ctx, false);
            onAGrindMap(ctx);
            cleanupAtStop(ctx);
            stageMember(ctx, "rival", g);
            List<String> out = new ArrayList<>(act(ctx, "check", g));
            out.add("A member of " + g + " next to you is your rival, so " + g + "'s rivals are all of your guild online:");
            out.add("it declares war on your guild (WAR DECLARED ... on player guild; a [Guild War] notice for your guild's");
            out.add("members; a megaphone, at once in a QA run: MEGAPHONE in the log). @guildwar shows the score and the maps each side holds; !qa do turf scores a");
            out.add("minute; !qa do taunt sends a grinder to your map with a jab. @guildwar peace (master / jr. master)");
            out.add("ends it (GuildWarPeace megaphone, a whisper). @botdrama off members are never counted or notified.");
            return out;
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if ("check".equals(name)) {
                return act(ctx, "check", guild(ctx, false));
            }
            return step(ctx, name, List.of("turf", "taunt", "score", "peace", "end", "list"));
        }
    }

    /** gw-3: poaching: a bot guild lures a member from its rival; and whispers a player in a player guild. */
    static final class Gw3Poach extends WarScenario {
        public String id() { return "gw-3"; }
        public String title() { return "Poaching: a bot guild lures a rival's member; whispers a player in a player guild (1.5.0)"; }
        public String usage() { return "[guild]; then !qa do poach | player"; }

        public List<String> stage(QaContext ctx) {
            String g = guild(ctx, true);
            List<String> out = new ArrayList<>(act(ctx, "poach", g));
            if (ctx.world().guildIdOf(ctx.gm()) > 0) {
                stageMember(ctx, "friend", g);
                out.add("A member of " + g + " who likes you stands here: !qa do player - it whispers you an offer (POACH");
                out.add("WHISPER); leave your guild within 15 minutes and a real invite follows (POACH INVITE).");
            } else {
                out.add("(Join a player guild to test the player whisper: !qa do player after staging again.)");
            }
            out.add(g + " lured a member away from its rival (POACH): a goodbye in the old guild chat, \"traitor\" from");
            out.add("the old leader, a welcome in the new one, a megaphone in a QA run (live 1 in 4). !qa do poach again.");
            out.add("Live: per bot guild every 6 h at most, 30%; player whispers once a week per player, 2 an hour.");
            return out;
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            String g = guild(ctx, true);
            return switch (name) {
                case "poach" -> act(ctx, "poach", g);
                case "player" -> act(ctx, "poach", g, "player");
                default -> throw new QaRefusal("gw-3 steps: poach, player");
            };
        }
    }

    /** gw-4: two bot guilds ally (shout-outs, a joint boss run); then one betrays the other and it is war. */
    static final class Gw4Alliance extends WarScenario {
        public String id() { return "gw-4"; }
        public String title() { return "Alliance and betrayal: allies, a joint boss run, a backstab, then war (1.5.0)"; }
        public String usage() { return "[guild]; then !qa do chat | bossrun | betray"; }
        public int minutes(String[] args) { return 15; }

        public List<String> stage(QaContext ctx) {
            String g = guild(ctx, false);
            cleanupAtStop(ctx);
            ctx.onStop("end the boss run", () -> ctx.world().botGuildAction(ctx.gmNow() != null ? ctx.gmNow() : ctx.gm(),
                    "bossstop", new String[0]));
            List<String> out = new ArrayList<>(act(ctx, "ally", g));
            out.add(g + " and an ally (ALLIANCE; GuildAlliance megaphone, a line in both guild chats). !qa do chat: a");
            out.add("shout-out to the ally in guild chat. !qa do bossrun: a joint staged boss run (both guilds' members");
            out.add("gather; you are warped to the entrance). !qa do betray: one backstabs the other (BETRAYAL megaphone,");
            out.add("guild chat) and they are at war at once (WAR DECLARED). Stop ends the alliance and the war.");
            return out;
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            return switch (name) {
                case "chat" -> act(ctx, "allychat");
                case "bossrun" -> act(ctx, "bossrun", guild(ctx, false));
                case "betray" -> act(ctx, "betray");
                default -> throw new QaRefusal("gw-4 steps: chat, bossrun, betray");
            };
        }
    }
}
