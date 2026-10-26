package soloMapling.qa;

import client.Character;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifePq;
import soloMapling.ArtificialPlayer.BotMemory;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.SocialGate;
import soloMapling.ArtificialPlayer.SocialInitiative;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static soloMapling.qa.QaLog.Topic.AFFINITY;
import static soloMapling.qa.QaLog.Topic.BOT_CHAT;
import static soloMapling.qa.QaLog.Topic.MEMORY;
import static soloMapling.qa.QaLog.Topic.SOCIAL_GATE;

/**
 * 1.6.0 slice S6, initiative: in-1 (a friend bot greets you within a minute of a login), in-2 (a day later it asks
 * about the goal you named: the initiative's clock runs a day ahead) and in-3 (a bot in a PQ lobby invites you when
 * your level fits the PQ). SocialInitiative logs on the {@code SOCIAL_GATE} topic as {@code INIT ...}, next to the
 * gate's {@code YES|NO} lines.
 */
final class InitiativeScenarios {

    private InitiativeScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new In1LoginGreet(), new In2FollowUp(), new In3PqInvite());
    }

    private static Character gmOf(QaContext ctx) {
        return ctx.gmNow() != null ? ctx.gmNow() : ctx.gm();
    }

    /** in-1: a staged friend bot; the GM's login is replayed now: a greeting by whisper or buddy chat within 60 s. */
    static final class In1LoginGreet extends QaScenarios.AffinityScenario {
        public String id() { return "in-1"; }
        public String title() { return "A friend bot greets you within a minute of logging in (1.6.0)"; }
        public String usage() { return "; then !qa do login"; }
        @Override public Set<QaLog.Topic> topics() { return EnumSet.of(SOCIAL_GATE, AFFINITY, BOT_CHAT); }

        public List<String> stage(QaContext ctx) {
            int id = stageBot(ctx, "friend", false);
            List<String> out = new ArrayList<>(List.of(QaScenarios.who(ctx, id) + " counts you as a friend and is online.",
                    "Your login is replayed now (as PlayerLoggedinHandler does on a real login, every friend rolling yes):",
                    "expect INIT LOGIN PLAN, then within 10-60 s INIT GREET ... at login by whisper (or buddy chat if you",
                    "are buddies) and the line itself. !qa do login replays it again. A real relog works too (not a cc)."));
            out.addAll(login(ctx));
            return out;
        }

        private List<String> login(QaContext ctx) {
            Integer id = bot(ctx);
            if (id != null) {
                SocialGate.forgetBotBudget(id);
            }
            List<String> plan = SocialInitiative.qaLogin(gmOf(ctx));
            String line = "IN1 LOGIN replayed: " + (plan.isEmpty() ? "no friend or buddy bot online to greet" : plan);
            ctx.run().note("IN", line);
            return List.of(line);
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if (!"login".equals(name)) {
                throw new QaRefusal("in-1 steps: login");
            }
            return login(ctx);
        }
    }

    /** in-2: the GM "wants a zhelm"; the initiative's clock runs a day ahead and the friend bot asks about it. */
    static final class In2FollowUp extends QaScenarios.AffinityScenario {
        static final String TAG = "item:zhelm";

        public String id() { return "in-2"; }
        public String title() { return "A day later a friend bot asks about the goal you named (1.6.0)"; }
        public String usage() { return "; then !qa do ask | check"; }
        @Override public Set<QaLog.Topic> topics() { return EnumSet.of(SOCIAL_GATE, MEMORY, AFFINITY); }

        public List<String> stage(QaContext ctx) {
            int id = stageBot(ctx, "friend", false);
            Character gm = gmOf(ctx);
            int slot = id - BotStore.BOT_ID_BASE;
            int removed = BotMemory.forgetPair(slot, gm.getId());
            BotMemory.Note note = BotMemory.episode(slot, gm.getId(), BotMemory.Kind.GOAL, "wants zhelm", 4, TAG,
                    System.currentTimeMillis());
            ctx.run().note("IN", "IN2 fresh pair (" + removed + " old note(s)); goal note "
                    + (note == null ? "NOT written" : "\"wants zhelm\" tag " + TAG));
            check(ctx, "BEFORE");
            SocialInitiative.qaShift(TimeUnit.DAYS.toMillis(1));
            ctx.onStop("the initiative's clock back to real time", () -> SocialInitiative.qaShift(0));
            ctx.run().note("IN", "IN2 clock +1 day");
            check(ctx, "SHIFTED");
            List<String> out = new ArrayList<>(List.of(QaScenarios.who(ctx, id) + " remembers you want a zhelm (a goal"
                            + " note, as a chat leaves one). The initiative's clock now runs a day ahead.",
                    "Expect: IN2 BEFORE due 0, IN2 SHIFTED due 1, then a whisper like \"did u ever get that zhelm?\""
                            + " (INIT FOLLOW_UP ... item:zhelm) and IN2 AFTER due 0, followed up yes.",
                    "!qa do ask asks again (nothing due: no second question); !qa do check prints the state."));
            out.addAll(ask(ctx));
            return out;
        }

        private List<String> ask(QaContext ctx) {
            Integer id = bot(ctx);
            Character gm = ctx.gmNow();
            Character bot = id == null ? null : SocialGateScenarios.botChr(id);
            if (bot == null || gm == null) {
                return List.of("the staged bot is not in the world");
            }
            boolean asked = SocialInitiative.qaFollowUp(bot, gm);
            String line = "IN2 ASK " + (asked ? "asked" : "nothing asked");
            ctx.run().note("IN", line);
            List<String> out = new ArrayList<>(List.of(line));
            out.addAll(check(ctx, "AFTER"));
            return out;
        }

        private List<String> check(QaContext ctx, String tag) {
            Integer id = bot(ctx);
            Character gm = ctx.gmNow();
            if (id == null || gm == null) {
                return List.of();
            }
            int slot = id - BotStore.BOT_ID_BASE;
            long now = System.currentTimeMillis() + SocialInitiative.qaShift();
            String line;
            try {
                int due = BotMemory.dueFollowUps(slot, gm.getId(), now).size();
                boolean done = BotMemory.about(slot, gm.getId()).stream()
                        .anyMatch(n -> TAG.equals(n.tag()) && n.followedUpAt() > 0);
                line = "IN2 " + tag + " due " + due + ", followed up " + (done ? "yes" : "no");
            } catch (RuntimeException e) {
                line = "IN2 " + tag + " memory unreadable (" + e.getMessage() + ")";
            }
            ctx.run().note("IN", line);
            return List.of(line);
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            return switch (name) {
                case "ask" -> ask(ctx);
                case "check" -> check(ctx, "CHECK");
                default -> throw new QaRefusal("in-2 steps: ask, check");
            };
        }
    }

    /** in-3: a bot "in the lobby" of the PQ that fits the GM's level invites the GM (and nobody out of band). */
    static final class In3PqInvite extends QaScenarios.AffinityScenario {
        public String id() { return "in-3"; }
        public String title() { return "A bot in a PQ lobby invites you when your level fits (1.6.0)"; }
        public String usage() { return "; then !qa do invite"; }
        @Override public Set<QaLog.Topic> topics() { return EnumSet.of(SOCIAL_GATE, BOT_CHAT, AFFINITY); }

        public List<String> stage(QaContext ctx) {
            Character gm = gmOf(ctx);
            if (gm.getParty() != null) {
                throw new QaRefusal("Leave your party first: a bot only invites players without one.");
            }
            LifePq.Lobby lobby = LifePq.lobbyFor(gm.getLevel());
            if (lobby == null && gm.getClient() instanceof client.SyntheticClient) {
                int was = gm.getLevel();
                gm.setLevel(25); // auto-QA's pretend player: into KPQ's band for the run, put back at the stop
                ctx.log("pretend player level " + was + " -> 25 (KPQ band)");
                ctx.onStop("pretend player level back to " + was, () -> {
                    Character now = ctx.gmNow();
                    if (now != null) {
                        now.setLevel(was);
                    }
                });
                lobby = LifePq.lobbyFor(gm.getLevel());
            }
            if (lobby == null) {
                throw new QaRefusal("Your level " + gm.getLevel() + " fits no PQ lobby (KPQ 21-30, LPQ 35-50,"
                        + " OPQ 51-70): use a character in one of those bands.");
            }
            int id = stageBot(ctx, "neutral", false);
            Character bot = SocialGateScenarios.botChr(id);
            if (bot == null) {
                throw new QaRefusal("The staged bot is not in the world.");
            }
            SocialInitiative.qaLobby(bot, lobby, gm);
            ctx.onStop("the bot leaves the staged lobby", () -> {
                Character b = SocialGateScenarios.botChr(id);
                if (b != null) {
                    SocialInitiative.qaLobby(b, null, null);
                }
            });
            int outOfBand = lobby.maxLevel() + 10;
            ctx.run().note("IN", "IN3 BAND " + lobby.name() + " " + lobby.minLevel() + "-" + lobby.maxLevel() + ": you lv "
                    + gm.getLevel() + " " + (SocialInitiative.inBand(lobby, gm.getLevel()) ? "fits" : "does not fit")
                    + ", lv " + outOfBand + " " + (SocialInitiative.inBand(lobby, outOfBand) ? "fits" : "does not fit"));
            List<String> out = new ArrayList<>(List.of(QaScenarios.who(ctx, id) + " now stands \"in the " + lobby.name()
                            + " lobby\" (staged: it counts as there wherever it is) and you are lv " + gm.getLevel() + ".",
                    "Expect: a line like \"lf " + lobby.name() + "? u in\" in map chat, a party invite from it (INIT PQ_INVITE"
                            + " ... band ...), and a minute later INIT PQ_INVITE ANSWER (accept it to see \"accepted\"",
                    "and the bot hand you the lead). !qa do invite asks again (cooldowns forgotten)."));
            out.addAll(invite(ctx));
            return out;
        }

        private List<String> invite(QaContext ctx) {
            Integer id = bot(ctx);
            Character gm = ctx.gmNow();
            Character bot = id == null ? null : SocialGateScenarios.botChr(id);
            if (bot == null || gm == null) {
                return List.of("the staged bot is not in the world");
            }
            LifePq.Lobby lobby = LifePq.lobbyFor(gm.getLevel());
            if (lobby != null) {
                SocialInitiative.qaLobby(bot, lobby, gm);
            }
            String line = "IN3 INVITE " + SocialInitiative.qaInvite(bot, gm);
            ctx.run().note("IN", line);
            return List.of(line);
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if (!"invite".equals(name)) {
                throw new QaRefusal("in-3 steps: invite");
            }
            return invite(ctx);
        }
    }
}
