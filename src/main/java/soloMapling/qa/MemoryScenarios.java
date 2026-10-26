package soloMapling.qa;

import client.Character;
import soloMapling.ArtificialPlayer.BotMemory;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.SocialGate;
import soloMapling.ArtificialPlayer.SocialHooks;
import soloMapling.ArtificialPlayer.BotConvo.RelationshipCard;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static soloMapling.qa.QaLog.Topic.AFFINITY;
import static soloMapling.qa.QaLog.Topic.MEMORY;
import static soloMapling.qa.QaLog.Topic.SOCIAL_GATE;

/**
 * 1.6.0 slice S3, memory kinds: me-1 (a staged KPQ clear leaves "did KPQ together" and a card line, no LLM needed) and
 * me-2 (a staged scam trade leaves a grudge, a SCAM episode and a started-it record in the social gate). Both start
 * from a fresh pair: the staged bot forgets what it knew about the GM.
 */
final class MemoryScenarios {

    /** What me-2's bot "sells" for 1 meso: a Chaos Scroll (priced on every server with the item pool). */
    static final int SCAM_ITEM = 2049100;

    private MemoryScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new Me1PqClear(), new Me2Scam());
    }

    private static Character gmOf(QaContext ctx) {
        return ctx.gmNow() != null ? ctx.gmNow() : ctx.gm();
    }

    private static void fresh(QaContext ctx, int id) {
        Character gm = gmOf(ctx);
        int removed = BotMemory.forgetPair(id - BotStore.BOT_ID_BASE, gm.getId());
        ctx.run().note("ME", "fresh: bot " + id + " forgot " + removed + " note(s) about " + gm.getName());
    }

    /** The bot's memory card about the GM, logged as {@code <tag> CARD bot <id>: <card>}. */
    private static List<String> card(QaContext ctx, String tag, Integer id) {
        Character gm = ctx.gmNow();
        if (id == null || gm == null) {
            return List.of();
        }
        String line = tag + " CARD bot " + id + ": "
                + RelationshipCard.memoryCard(id, gm.getId(), gm.getName(), System.currentTimeMillis());
        ctx.run().note("ME", line);
        return List.of(line);
    }

    /** me-1: a friend bot "clears KPQ" with the GM (a staged PQ_CLEAR): an episode and a line on its card. */
    static final class Me1PqClear extends QaScenarios.AffinityScenario {
        public String id() { return "me-1"; }
        public String title() { return "A KPQ clear together is remembered: episode and card line, no LLM (1.6.0)"; }
        public String usage() { return "; then !qa do card | clear"; }
        @Override public Set<QaLog.Topic> topics() { return EnumSet.of(MEMORY, AFFINITY); }
        @Override public int tickSeconds() { return 10; }

        public List<String> stage(QaContext ctx) {
            int id = stageBot(ctx, "friend", false);
            fresh(ctx, id);
            clear(ctx, id);
            return List.of(QaScenarios.who(ctx, id) + " just cleared KPQ with you (a staged clear, the same path as"
                            + " EventInstanceManager.clearPQ).",
                    "Expect: MEMORY PQ_CLEAR KPQ, MEMORY EPISODE PQ ... \"did KPQ together\" (weight 6), then every 10 s"
                            + " ME1 CARD ... Between you two: did KPQ together (just now).",
                    "!qa do card prints the card; !qa do clear stages the clear again (the same day: no second episode).");
        }

        private List<String> clear(QaContext ctx, int id) {
            SocialHooks.Clear c = SocialHooks.stagedClear("KerningPQ", id, gmOf(ctx));
            String line = "ME1 staged clear: " + (c == null ? "not a PQ" : c.name()) + " with bot " + id;
            ctx.run().note("STEP", line);
            return List.of(line);
        }

        @Override
        public void tick(QaContext ctx) {
            super.tick(ctx);
            card(ctx, "ME1", bot(ctx));
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            Integer id = bot(ctx);
            if (id == null) {
                throw new QaRefusal("me-1: no staged bot");
            }
            return switch (name) {
                case "card" -> card(ctx, "ME1", id);
                case "clear" -> clear(ctx, id);
                default -> throw new QaRefusal("me-1 steps: card, clear");
            };
        }
    }

    /** me-2: a bot gives the GM a Chaos Scroll for 1 meso (a staged scam trade): a grudge and started-it. */
    static final class Me2Scam extends QaScenarios.AffinityScenario {
        public String id() { return "me-2"; }
        public String title() { return "A scam trade is a grudge, a SCAM memory and \"started it\" (1.6.0)"; }
        public String usage() { return "; then !qa do check | scam | fresh"; }
        @Override public Set<QaLog.Topic> topics() { return EnumSet.of(MEMORY, AFFINITY, SOCIAL_GATE); }
        @Override public int tickSeconds() { return 10; }

        public List<String> stage(QaContext ctx) {
            int id = stageBot(ctx, "neutral", false);
            fresh(ctx, id);
            SocialGate.forget(id, gmOf(ctx).getId());
            scam(ctx, id);
            return List.of(QaScenarios.who(ctx, id) + " just traded you a Chaos Scroll for 1 meso (a staged trade"
                            + " through the same path as Trade.completeTrade).",
                    "Expect: MEMORY TRADE SCAM, AFFINITY EVENT scam -20, SOCIAL_GATE STARTED scam, MEMORY EPISODE SCAM"
                            + " \"scammed me on a trade: ...\" (weight 9), then ME2 CHECK lines with started-it yes.",
                    "!qa do check prints it again; !qa do scam stages another (the event cools down 10 min); !qa do fresh"
                            + " clears the bot's memory and started-it record.");
        }

        private List<String> scam(QaContext ctx, int id) {
            Character gm = gmOf(ctx);
            SocialHooks.Read r = SocialHooks.stagedScam(id, "bot " + id, gm, SCAM_ITEM);
            String line = "ME2 staged trade: " + r.outcome().name().toLowerCase(java.util.Locale.ROOT) + ", bot gave "
                    + r.gave() + " for " + r.got() + (r.priced() ? "" : " (staged value: the item is not priced here)");
            ctx.run().note("STEP", line);
            return List.of(line);
        }

        private List<String> check(QaContext ctx) {
            Integer id = bot(ctx);
            Character gm = ctx.gmNow();
            if (id == null || gm == null) {
                return List.of();
            }
            String scamNote;
            try {
                scamNote = BotMemory.about(id - BotStore.BOT_ID_BASE, gm.getId()).stream()
                        .anyMatch(n -> n.kind() == BotMemory.Kind.SCAM) ? "yes" : "no";
            } catch (RuntimeException e) {
                scamNote = "unreadable (" + e.getMessage() + ")";
            }
            List<String> out = new ArrayList<>();
            String line = "ME2 CHECK bot " + id + " -> " + gm.getName() + ": " + ctx.world().affinityOf(id, gm)
                    + "; started it " + (SocialGate.startedIt(id, gm.getId()) ? "yes" : "no")
                    + "; scam memory " + scamNote;
            ctx.run().note("ME", line);
            out.add(line);
            out.addAll(card(ctx, "ME2", id));
            return out;
        }

        @Override
        public void tick(QaContext ctx) {
            super.tick(ctx);
            check(ctx);
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            Integer id = bot(ctx);
            if (id == null) {
                throw new QaRefusal("me-2: no staged bot");
            }
            return switch (name) {
                case "check" -> check(ctx);
                case "scam" -> {
                    List<String> out = new ArrayList<>(scam(ctx, id));
                    out.addAll(check(ctx));
                    yield out;
                }
                case "fresh" -> {
                    fresh(ctx, id);
                    SocialGate.forget(id, gmOf(ctx).getId());
                    yield check(ctx);
                }
                default -> throw new QaRefusal("me-2 steps: check, scam, fresh");
            };
        }
    }
}
