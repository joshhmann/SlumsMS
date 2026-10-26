package soloMapling.qa;

import client.Character;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityEvent;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityReactions;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotBuddies;
import soloMapling.ArtificialPlayer.BotMegaphoneSystem.BotNoiseFilter;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotWhispers;
import soloMapling.ArtificialPlayer.SocialGate;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static soloMapling.qa.QaLog.Topic.AFFINITY;
import static soloMapling.qa.QaLog.Topic.BOT_CHAT;
import static soloMapling.qa.QaLog.Topic.SOCIAL_GATE;

/**
 * 1.6.0 slice S5, the social gate: sg-1 ({@code @quiet bots} stops a friend bot's whispers, invites and buddy requests)
 * and sg-2 (a fresh player gets no spice from a rival until they KS or roast it). The gate logs every unprompted act
 * as {@code SOCIAL_GATE YES|NO <act> <bot> -> <player>: <why>}.
 */
final class SocialGateScenarios {

    private SocialGateScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new Sg1Quiet(), new Sg2StartedIt());
    }

    static Character botChr(int id) {
        BotSM sm = CharacterStorage.getBotById(id);
        return sm == null ? null : sm.getChr();
    }

    /** sg-1: a staged friend bot next to a GM with {@code @quiet bots}: 0 whispers, invites or buddy requests. */
    static final class Sg1Quiet extends QaScenarios.AffinityScenario {
        private final Map<Integer, AtomicInteger> tries = new ConcurrentHashMap<>();

        public String id() { return "sg-1"; }
        public String title() { return "@quiet bots: a friend bot sends no whispers, invites or buddy requests (1.6.0)"; }
        public String usage() { return "; then !qa do try | loud | quiet"; }
        @Override public Set<QaLog.Topic> topics() { return EnumSet.of(SOCIAL_GATE, AFFINITY, BOT_CHAT); }
        @Override public int tickSeconds() { return 20; }

        public List<String> stage(QaContext ctx) {
            Character gm = ctx.gm();
            BotNoiseFilter.Setting was = BotNoiseFilter.get(gm.getId());
            BotNoiseFilter.set(gm.getId(), new BotNoiseFilter.Setting(was.megaphones(), true));
            ctx.log("@quiet bots on (was " + (was.bots() ? "on" : "off") + ")");
            ctx.onStop("@quiet bots back " + (was.bots() ? "on" : "off"), () -> {
                Character now = ctx.gmNow();
                BotNoiseFilter.set(now != null ? now.getId() : gm.getId(), was);
            });
            int id = stageBot(ctx, "friend", false);
            // owner (S5 follow-up): quiet holds map-chat spice too - the friend may tease, so the hold is quiet's alone
            SocialGate.playerStarted(id, gm.getId(), SocialGate.Provocation.STAGED);
            tries.put(ctx.run().gmId(), new AtomicInteger());
            ctx.onStop("forget tries", () -> tries.remove(ctx.run().gmId()));
            return List.of("@quiet bots is ON for you and " + QaScenarios.who(ctx, id) + " counts you as a friend.",
                    "Every 20 s (or !qa do try) it tries to whisper you, party-invite, guild-invite, buddy-request you and",
                    "taunt you in map chat. Expect: none of it; SOCIAL_GATE NO ... @quiet bots lines.",
                    "!qa do loud turns @quiet bots off to compare (then they go through); !qa do quiet turns it back on.");
        }

        @Override
        public void tick(QaContext ctx) {
            super.tick(ctx);
            attempt(ctx);
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            Character gm = ctx.gmNow() != null ? ctx.gmNow() : ctx.gm();
            return switch (name) {
                case "try" -> attempt(ctx);
                case "loud", "quiet" -> {
                    BotNoiseFilter.Setting now = BotNoiseFilter.get(gm.getId());
                    BotNoiseFilter.set(gm.getId(), new BotNoiseFilter.Setting(now.megaphones(), "quiet".equals(name)));
                    String line = "@quiet bots " + ("quiet".equals(name) ? "on" : "off");
                    ctx.run().note("STEP", line);
                    yield List.of(line);
                }
                default -> throw new QaRefusal("sg-1 steps: try, loud, quiet");
            };
        }

        /** The friend bot tries each unsolicited kind once through the real senders; what got through. */
        List<String> attempt(QaContext ctx) {
            Integer id = bot(ctx);
            Character gm = ctx.gmNow();
            Character bot = id == null ? null : botChr(id);
            if (bot == null || gm == null) {
                return List.of("the staged bot is not in the world");
            }
            int n = tries.computeIfAbsent(ctx.run().gmId(), k -> new AtomicInteger()).incrementAndGet();
            List<String> out = new ArrayList<>();
            boolean whisper = BotWhispers.send(gm, bot.getName(), Math.max(0, bot.getClient().getChannel() - 1),
                    "hey u around? (sg-1 try " + n + ")", false);
            out.add("whisper " + (whisper ? "SENT" : "held"));
            out.add(BotBuddies.offerAsFriend(bot.getId(), gm.getId()));
            out.add("party invite " + (SocialGate.allow(bot, gm, SocialGate.Act.PARTY_INVITE) ? "allowed" : "held"));
            out.add("guild invite " + (SocialGate.allow(bot, gm, SocialGate.Act.GUILD_INVITE) ? "allowed" : "held"));
            boolean taunt = SocialGate.allow(bot, gm, SocialGate.Act.TAUNT);
            if (taunt) {
                soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak(bot,
                        "lol " + gm.getName() + " nice gear (sg-1 taunt " + n + ")");
            }
            out.add("map-chat taunt " + (taunt ? "SAID" : "held"));
            String line = "SG1 try " + n + " (" + (SocialGate.quietBots(gm.getId()) ? "quiet" : "loud") + "): "
                    + String.join(", ", out);
            ctx.run().note("SG", line);
            return List.of(line);
        }
    }

    /** sg-2: a rival bot and a GM who never provoked it: no spice until the GM KSes or roasts it. */
    static final class Sg2StartedIt extends QaScenarios.AffinityScenario {
        public String id() { return "sg-2"; }
        public String title() { return "Started it: a rival spices only after you KS or roast it (1.6.0)"; }
        public String usage() { return "; then !qa do check | taunt | ks | roast | fresh"; }
        @Override public Set<QaLog.Topic> topics() { return EnumSet.of(SOCIAL_GATE, AFFINITY, BOT_CHAT); }

        public List<String> stage(QaContext ctx) {
            holdKsWars(ctx);
            int id = stageBot(ctx, "rival", true);
            fresh(ctx, id);
            return List.of(QaScenarios.who(ctx, id) + ", a training bot held here, is your rival, but you never started"
                            + " anything with it (its started-it record is cleared).",
                    "Expect: no taunt / KS spree / defame (SOCIAL_GATE NO taunt|ks_spree ...: they did not start it).",
                    "!qa do taunt asks it to taunt you now (held). !qa do ks or !qa do roast records your KS / rude line:",
                    "after that it taunts and KSes back (SOCIAL_GATE STARTED, then YES and AFFINITY ACT lines). !qa do fresh"
                            + " clears it again; !qa do check prints the gate's answers.");
        }

        @Override
        public void tick(QaContext ctx) {
            super.tick(ctx);
            check(ctx);
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            Integer id = bot(ctx);
            Character gm = ctx.gmNow() != null ? ctx.gmNow() : ctx.gm();
            if (id == null) {
                throw new QaRefusal("sg-2: no staged bot");
            }
            return switch (name) {
                case "check" -> check(ctx);
                case "fresh" -> {
                    fresh(ctx, id);
                    yield check(ctx);
                }
                case "ks", "roast" -> {
                    AffinityEvent e = "ks".equals(name) ? AffinityEvent.KS : AffinityEvent.RUDE_CHAT;
                    boolean counted = BotAffinity.record(id, gm.getId(), e, e.delta(), gm.getName(), "bot " + id,
                            gm.getMapId());
                    if (!counted) { // the event's own cooldown (or ties not loaded): the ledger still hears it
                        SocialGate.playerStarted(id, gm.getId(), "ks".equals(name) ? SocialGate.Provocation.KS
                                : SocialGate.Provocation.ROAST);
                    }
                    String line = "you " + ("ks".equals(name) ? "KS'd" : "roasted") + " it"
                            + (counted ? "" : " (ledger only: the affinity event was cooling down)");
                    ctx.run().note("STEP", line);
                    List<String> out = new ArrayList<>(List.of(line));
                    out.addAll(check(ctx));
                    yield out;
                }
                case "taunt" -> {
                    BotSM sm = CharacterStorage.getBotById(id);
                    Character bot = sm == null ? null : sm.getChr();
                    boolean asked = bot != null && AffinityReactions.forceTaunt(sm, bot, gm);
                    String line = "taunt asked: " + (asked ? "the gate decides (YES/NO line)" : "not a rival now");
                    ctx.run().note("STEP", line);
                    yield List.of(line);
                }
                default -> throw new QaRefusal("sg-2 steps: check, taunt, ks, roast, fresh");
            };
        }

        private void fresh(QaContext ctx, int id) {
            Character gm = ctx.gmNow() != null ? ctx.gmNow() : ctx.gm();
            SocialGate.forget(id, gm.getId());
            ctx.run().note("SG", "fresh: bot " + id + " has no started-it record for " + gm.getName());
        }

        private List<String> check(QaContext ctx) {
            Integer id = bot(ctx);
            Character gm = ctx.gmNow();
            if (id == null || gm == null) {
                return List.of();
            }
            String line = "SG2 bot " + id + " -> " + gm.getName() + ": " + SocialGate.describe(id, gm.getId())
                    + "; tease " + (SocialGate.rules(id, gm.getId(), SocialGate.Act.TEASE).ok() ? "yes" : "no");
            ctx.run().note("SG", line);
            return List.of(line);
        }
    }
}
