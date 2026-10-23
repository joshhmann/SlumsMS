package soloMapling.qa;

import client.Character;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static soloMapling.qa.QaLog.Topic.BOT_CHAT;
import static soloMapling.qa.QaLog.Topic.BOT_GUILD;

/**
 * 1.5.0 bot guild scenarios (gu-1 .. gu-7). Every staged bot is an on-demand bot (memory only, never stored) and
 * leaves at stop; a guild the GM joined during the run is left again at stop.
 */
final class GuildScenarios {

    static final int FM_ENTRANCE = QaScenarios.FM_ENTRANCE;

    private GuildScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new Gu1Apply(), new Gu2InviteBots(), new Gu3Recruit(), new Gu4Rivalry(), new Gu5BossRun(),
                new Gu6Quit(), new Gu7Party(), new Gu8Kick());
    }

    /** Shared: staged bots per GM, undone at stop, and the guild list in the log at start. */
    abstract static class GuildScenario implements QaScenario {
        final Map<Integer, List<Integer>> bots = new ConcurrentHashMap<>();

        public Set<QaLog.Topic> topics() {
            return EnumSet.of(BOT_GUILD, BOT_CHAT);
        }

        int stage(QaContext ctx, String state, String guild, int rank) {
            int id = ctx.world().stageGuildBot(ctx.gm(), state, guild, rank);
            if (id < 0) {
                throw new QaRefusal("No bot could be staged here" + (guild == null ? "." : " in " + guild + "."));
            }
            bots.computeIfAbsent(ctx.run().gmId(), k -> {
                List<Integer> list = new java.util.concurrent.CopyOnWriteArrayList<>();
                ctx.onStop("unstage the guild bots", () -> {
                    for (int b : list) {
                        ctx.world().unstageGuildBot(b);
                    }
                    bots.remove(ctx.run().gmId());
                });
                return list;
            }).add(id);
            ctx.log("staged " + state + " bot " + id + (guild == null ? "" : " in " + guild + " (rank " + rank + ")"));
            return id;
        }

        List<Integer> staged(QaContext ctx) {
            return bots.getOrDefault(ctx.run().gmId(), List.of());
        }

        void listGuilds(QaContext ctx) {
            for (String line : ctx.world().botGuildList()) {
                ctx.run().note("GUILDS", line);
            }
        }

        String botGuild(QaContext ctx, boolean withRival) {
            String wanted = ctx.args().length > 0 && !ctx.args()[0].matches("(?i)\\d+|rival|ignore|gpq|zakum|pap|horntail") ? ctx.args()[0] : null;
            String g = ctx.world().pickBotGuild(wanted, withRival);
            if (g == null) {
                throw new QaRefusal(wanted != null ? "No bot guild called " + wanted + " (!botguild list)."
                        : "No bot guild" + (withRival ? " with a rival" : "") + " yet: they are founded at the first guild"
                        + " step with migration 014 and 40+ roster bots (!botguild step).");
            }
            return g;
        }

        /** At stop, the GM leaves the guild they joined during the run (they were guildless at the start). */
        void leaveJoinedGuildAtStop(QaContext ctx) {
            ctx.onStop("leave the guild joined during the run", () -> {
                Character now = ctx.gmNow();
                if (now != null && ctx.world().guildIdOf(now) > 0) {
                    ctx.run().note("CLEANUP", "left the guild: " + ctx.world().leaveGuild(now));
                }
            });
        }

        void requireGuildless(QaContext ctx) {
            if (ctx.world().guildIdOf(ctx.gm()) > 0) {
                throw new QaRefusal("Leave your guild first: this test has you join a bot guild.");
            }
        }

        void requireGuild(QaContext ctx) {
            if (ctx.world().guildIdOf(ctx.gm()) <= 0) {
                throw new QaRefusal("Be in a guild first (create one at the Guild HQ: !qa run gd-1).");
            }
        }

        List<String> act(QaContext ctx, String action, String... args) {
            List<String> out = ctx.world().botGuildAction(ctx.gm(), action, args);
            for (String line : out) {
                ctx.run().note("STEP", action + ": " + line);
            }
            return out;
        }
    }

    /** gu-1: ask a bot guild's officer to join; the leader decides, a real invite follows; guild chat answers. */
    static final class Gu1Apply extends GuildScenario {
        public String id() { return "gu-1"; }
        public String title() { return "Join a bot guild: ask an officer, get a real invite, guild chat (1.5.0)"; }
        public String usage() { return "[guild]"; }

        public List<String> stage(QaContext ctx) {
            requireGuildless(ctx);
            String guild = botGuild(ctx, false);
            listGuilds(ctx);
            int id = stage(ctx, "friend", guild, 2);
            leaveJoinedGuildAtStop(ctx);
            return List.of(QaScenarios.who(ctx, id) + " next to you is an officer of " + guild + " (and counts you as a friend); its",
                    "guild name shows over its head. Talk to it: \"<name> can i join ur guild?\" - it answers (APPLY YES)",
                    "and an invite pops up a few seconds later (INVITE SENT). Accept: you are in " + guild + " with its bots",
                    "on the guild list. Say \"hi guys\" in guild chat (/g or the guild tab): at most one bot answers (GUILD CHAT).",
                    "Stop leaves the guild again.");
        }
    }

    /** gu-2: the GM's guild invites staged bots: friends say yes, a neutral bot no; past half bots friends get picky. */
    static final class Gu2InviteBots extends GuildScenario {
        public String id() { return "gu-2"; }
        public String title() { return "Invite bots into your guild: friends accept, others decline, soft half-bots limit (1.5.0)"; }
        public String usage() { return "[friends 1-4]; then !qa do join (one more friend, already a member)"; }

        public List<String> stage(QaContext ctx) {
            requireGuild(ctx);
            int n = 2;
            for (String a : ctx.args()) {
                if (a.matches("[1-4]")) {
                    n = Integer.parseInt(a);
                }
            }
            for (int i = 0; i < n; i++) {
                stage(ctx, "friend", null, 5);
            }
            stage(ctx, "neutral", null, 5);
            return List.of(n + " friend bots and 1 neutral bot stand next to you, guildless.",
                    "Invite each (/ginvite <name> or the guild window). In 4-40 s: friends join (INVITE ... JOINED, a thank-you in",
                    "guild chat, the guild name over their heads); the neutral one declines (\"has denied your guild invite\").",
                    "Past half bots a friend gets picky (the INVITE line shows the chance). !qa do join adds a friend who is a member at once.");
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if (!"join".equals(name)) {
                throw new QaRefusal("gu-2 steps: join");
            }
            return List.of("staged member " + stage(ctx, "friend", "mine", 5) + " (joined your guild at once)");
        }
    }

    /** gu-3: a bot guild recruits: megaphone through the gate, out loud in Henesys/FM, a whisper + invite to a friend. */
    static final class Gu3Recruit extends GuildScenario {
        public String id() { return "gu-3"; }
        public String title() { return "A bot guild recruits: megaphone, out loud, whisper + invite to a friend (1.5.0)"; }
        public String usage() { return "[guild]; then !qa do recruit"; }

        public List<String> stage(QaContext ctx) {
            requireGuildless(ctx);
            String guild = botGuild(ctx, false);
            if (ctx.world().warp(ctx.gm(), 100000000)) {
                ctx.log("warped to Henesys");
            }
            stage(ctx, "friend", guild, 2);
            leaveJoinedGuildAtStop(ctx);
            List<String> out = new ArrayList<>(act(ctx, "recruit", guild));
            out.add("An officer of " + guild + " who counts you as a friend stands here. Expect a recruit megaphone (or");
            out.add("\"waiting for the megaphone gate\"), the line out loud here, and a whisper from it followed by a real guild");
            out.add("invite (RECRUIT WHISPER, INVITE SENT). !qa do recruit runs another round (megaphones still gated).");
            return out;
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if (!"recruit".equals(name)) {
                throw new QaRefusal("gu-3 steps: recruit");
            }
            return act(ctx, "recruit", botGuild(ctx, false));
        }
    }

    /** gu-4: two rival guilds meet in the FM: trash talk, a megaphone jab, a grind-map claim. */
    static final class Gu4Rivalry extends GuildScenario {
        public String id() { return "gu-4"; }
        public String title() { return "Rival bot guilds: FM trash talk, megaphone jab, grind-map claim (1.5.0)"; }
        public String usage() { return "[guild]; then !qa do jab"; }
        public Set<String> claims(Character gm, String[] args) { return Set.of("map:" + FM_ENTRANCE); }

        public List<String> stage(QaContext ctx) {
            String guild = botGuild(ctx, true);
            String rival = ctx.world().rivalOf(guild);
            if (!ctx.world().warp(ctx.gm(), FM_ENTRANCE)) {
                throw new QaRefusal("Cannot warp to the FM entrance.");
            }
            ctx.log("warped to the FM entrance");
            stage(ctx, "neutral", guild, 5);
            stage(ctx, "neutral", rival, 5);
            List<String> out = new ArrayList<>(act(ctx, "jab", guild));
            out.add("A member of " + guild + " and one of its rival " + rival + " stand here. One jabs the other out loud (JAB),");
            out.add("the other may jab back; a megaphone jab goes through the gate (MEGA JAB); a party leader may go grind where");
            out.add("the rival grinds (CLAIM, needs both guilds partied). !qa do jab again. Never aimed at players.");
            return out;
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if (!"jab".equals(name)) {
                throw new QaRefusal("gu-4 steps: jab");
            }
            return act(ctx, "jab", botGuild(ctx, true));
        }
    }

    /** gu-5: a staged boss run now: the guild gathers at the entrance, goes in, comes back with a result. */
    static final class Gu5BossRun extends GuildScenario {
        public String id() { return "gu-5"; }
        public String title() { return "A staged guild boss run / GPQ: gathering, in, back with a result (1.5.0)"; }
        public String usage() { return "[guild] [gpq|zakum|pap|horntail]"; }
        public int minutes(String[] args) { return 8; }

        public List<String> stage(QaContext ctx) {
            String guild = botGuild(ctx, false);
            String boss = null;
            for (String a : ctx.args()) {
                if (a.matches("(?i)gpq|zakum|pap|horntail")) {
                    boss = a.toLowerCase();
                }
            }
            List<String> out = new ArrayList<>(boss == null ? act(ctx, "bossrun", guild) : act(ctx, "bossrun", guild, boss));
            if (out.stream().anyMatch(l -> l.startsWith("no boss run"))) {
                throw new QaRefusal(String.join("; ", out) + " (it needs 2+ online roster members of the guild at the"
                        + " boss's level; try another guild or !botguild list)");
            }
            ctx.onStop("end the boss run", () -> ctx.world().botGuildAction(ctx.gmNow() != null ? ctx.gmNow() : ctx.gm(),
                    "bossstop", new String[0]));
            out.add("Members of " + guild + " come to this entrance on your channel (BOSS RUN), say a few things, and after");
            out.add("60 s go in (INSIDE: they leave the world). After 2 min they log back in here (BACK) and one posts the");
            out.add("result in guild chat and by megaphone (RESULT; loot is only named, no item). To join for real: party one");
            out.add("of them while they gather (it always accepts, REAL) - it follows you in and fights as a follower.");
            return out;
        }
    }

    /** gu-6: a bot in the GM's guild quits: it became the GM's rival, or nobody paid it attention for weeks. */
    static final class Gu6Quit extends GuildScenario {
        public String id() { return "gu-6"; }
        public String title() { return "A bot quits your guild: rivals, or ignored for weeks (1.5.0)"; }
        public String usage() { return "; then !qa do rival | ignore"; }

        public List<String> stage(QaContext ctx) {
            requireGuild(ctx);
            int id = stage(ctx, "friend", "mine", 5);
            return List.of(QaScenarios.who(ctx, id) + " is a member of your guild now.",
                    "!qa do rival: it turns into your rival and the quit check runs - it says why in guild chat and leaves (LEAVE).",
                    "!qa do ignore: it joined and was last noticed 30 days ago - it leaves (player-led guilds only). A step on a",
                    "member that already left stages a fresh one and runs on it.");
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if (!"rival".equals(name) && !"ignore".equals(name)) {
                throw new QaRefusal("gu-6 steps: rival, ignore");
            }
            List<Integer> ids = staged(ctx);
            if (ids.isEmpty()) {
                throw new QaRefusal("No staged bot.");
            }
            int id = ids.get(ids.size() - 1);
            List<String> out = act(ctx, name, Integer.toString(id));
            if (out.stream().anyMatch(l -> l.contains("in no guild"))) {
                // rc6 (owner QA 2026-10-23): "run the step again" read as "start over", so the ignore check never ran;
                // the step now stages a fresh member and runs on it at once
                int fresh = stage(ctx, "friend", "mine", 5);
                out = new ArrayList<>(out);
                out.add("staged a new member (" + fresh + ") and ran " + name + " on it:");
                out.addAll(act(ctx, name, Integer.toString(fresh)));
            }
            return out;
        }
    }

    /** gu-7: party invites to bots: a friend accepts and follows, a neutral bot sometimes, a rival never. */
    static final class Gu7Party extends GuildScenario {
        public String id() { return "gu-7"; }
        public String title() { return "Party invites to bots: friend yes, neutral sometimes, rival never (1.5.0)"; }

        public List<String> stage(QaContext ctx) {
            int f = stage(ctx, "friend", null, 5);
            int n = stage(ctx, "neutral", null, 5);
            int r = stage(ctx, "rival", null, 5);
            return List.of("Three bots: " + QaScenarios.who(ctx, f) + " (friend), " + QaScenarios.who(ctx, n) + " (neutral), "
                    + QaScenarios.who(ctx, r) + " (rival).",
                    "Invite each to a party without asking first (PARTY INVITE lines show the chance): the friend joins and",
                    "follows you (it trains with you on a mob map), the neutral one joins about 1 time in 3 (a no holds 3 min),",
                    "the rival never. KPQ/LPQ: a follower standing by you at the PQ NPC is registered with the party; it does",
                    "no stage puzzles (OPQ steps exist only for the OPQ lobby bots).");
        }
    }

    /** gu-8: a bot guild's leader kicks a rival player, with a whisper saying why. */
    static final class Gu8Kick extends GuildScenario {
        public String id() { return "gu-8"; }
        public String title() { return "A bot guild kicks a rival player, with a whisper (1.5.0)"; }
        public String usage() { return "[guild]; then !qa do kick | vote"; }
        private final Map<Integer, String> chosen = new ConcurrentHashMap<>();

        public List<String> stage(QaContext ctx) {
            requireGuildless(ctx);
            String guild = botGuild(ctx, false);
            chosen.put(ctx.run().gmId(), guild);
            ctx.onStop("forget", () -> chosen.remove(ctx.run().gmId()));
            if (!ctx.world().joinBotGuild(ctx.gm(), guild)) {
                throw new QaRefusal("Could not put you in " + guild + " (full?).");
            }
            ctx.log("the GM joined " + guild);
            leaveJoinedGuildAtStop(ctx);
            stage(ctx, "friend", guild, 5);
            return List.of("You are a member of " + guild + " now, with a bot member next to you that likes you.",
                    "!qa do kick: the guild's leader turns into your rival and the kick check runs: a whisper from the leader,",
                    "then the stock \"expelled\" (KICK). !qa do vote (rejoin first with a new run): two more members that are",
                    "your rivals join; 2 of 3 members with feelings is most of them - kicked. Rate limits are skipped here",
                    "(live: one kick per guild per 30 min, 3 an hour world-wide). @botdrama off means never kicked.");
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            String guild = chosen.getOrDefault(ctx.run().gmId(), botGuild(ctx, false));
            if ("kick".equals(name)) {
                return act(ctx, "kick", guild);
            }
            if ("vote".equals(name)) {
                stage(ctx, "rival", guild, 5);
                stage(ctx, "rival", guild, 5);
                return act(ctx, "kickcheck", guild);
            }
            throw new QaRefusal("gu-8 steps: kick, vote");
        }
    }
}
