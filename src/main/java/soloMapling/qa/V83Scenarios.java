package soloMapling.qa;

import client.Character;

import java.util.List;
import java.util.Set;

/**
 * Ticket 69: the ordinary v83 features a real player uses, one scenario per feature ({@code vb-1}..{@code vb-7}).
 * <p>
 * Staging ({@code !qa run vb-N}) hands out the coupons, mesos, items and party the feature needs and logs it. Each
 * {@code !qa do <step>} runs one real path (an NPC script, a shop handler, a trade window) through
 * {@link QaWorld#v83Step} and writes its outcome lines as {@code VB} notes, e.g. {@code VB STYLE hair 30000 ->
 * 30210 changed}. The matching auto-QA check reads those notes: it asserts the outcome the player would see, not
 * that the call returned.
 */
final class V83Scenarios {

    private V83Scenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new Vb1(), new Vb2(), new Vb3(), new Vb4(), new Vb5(), new Vb6(), new Vb7());
    }

    /** Common shape: staging and steps both live in the world, so a step can reuse the same staging. */
    abstract static class Vb implements QaScenario {
        private final String id;
        private final String title;
        private final Set<QaLog.Topic> topics;

        Vb(String id, String title, QaLog.Topic... topics) {
            this.id = id;
            this.title = title;
            this.topics = Set.of(topics);
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String title() {
            return title;
        }

        @Override
        public Set<QaLog.Topic> topics() {
            return topics;
        }

        @Override
        public String usage() {
            return "run vb: !qa run " + id + "; then !qa do stage|" + steps();
        }

        /** The step names this scenario accepts, for the usage line and the checks. */
        abstract String steps();

        @Override
        public List<String> stage(QaContext ctx) {
            Character gm = ctx.gm();
            if (!(gm.getClient() instanceof client.SyntheticClient)) {
                throw new QaRefusal(id + " only runs on synthetic pretend players (AutoQaOne/AutoQaTwo).");
            }
            // the stage resets the pretend players (job, level, mesos, skills); later checks need them as they were
            ctx.onStop("put the pretend players back as they were", () -> {
                Character now = ctx.gmNow() != null ? ctx.gmNow() : gm;
                for (String line : ctx.world().v83Step(now, id, "restore")) {
                    ctx.run().note("CLEANUP", line);
                }
            });
            return say(ctx, "stage");
        }

        @Override
        public List<String> action(QaContext ctx, String name, String[] args) {
            Character gm = ctx.gm();
            if (!(gm.getClient() instanceof client.SyntheticClient)) {
                throw new QaRefusal(id + " only runs on synthetic pretend players (AutoQaOne/AutoQaTwo).");
            }
            String step = args.length == 0 ? name : name + " " + String.join(" ", args);
            return say(ctx, step);
        }

        private List<String> say(QaContext ctx, String step) {
            Character gm = ctx.gm();
            List<String> lines = ctx.world().v83Step(gm, id, step);
            for (String line : lines) {
                // the note kind is VB, so the message itself does not repeat it
                ctx.run().note("VB", line.startsWith("VB ") ? line.substring(3) : line);
            }
            return "stage".equals(step) ? lines : List.of();
        }
    }

    /** vb-1: the four Henesys salon counters, each with its real coupon. */
    static final class Vb1 extends Vb {
        Vb1() {
            super("vb-1", "Salon: hair, hair colour, face, lenses and skin change with the coupon",
                    QaLog.Topic.NPC);
        }

        @Override
        String steps() {
            return "hair|haircolor|face|lens|skin";
        }
    }

    /** vb-2: delete a character on the pretend player's own account, with PIC. */
    static final class Vb2 extends Vb {
        Vb2() {
            super("vb-2", "Character delete: a PIC-checked delete removes the character",
                    QaLog.Topic.NPC);
        }

        @Override
        String steps() {
            return "delete|wrongpic";
        }
    }

    /** vb-3: a Kerning PQ instance cleared to the reward and the exit warp. */
    static final class Vb3 extends Vb {
        Vb3() {
            super("vb-3", "Kerning PQ: stage 5 clear gives the reward and warps the party out",
                    QaLog.Topic.NPC);
        }

        @Override
        String steps() {
            return "clear|reward";
        }
    }

    /** vb-4: the mount chain: Kenta's watch-hog room, the bottle fee, the perfume, the hog catch. */
    static final class Vb4 extends Vb {
        Vb4() {
            super("vb-4", "Mount chain: 6002 room and hand-in, 6007 bottle fee, 6003 perfume, the hog catch",
                    QaLog.Topic.NPC);
        }

        @Override
        String steps() {
            return "room|bottle|bottle-broke|perfume|hog";
        }
    }

    /** vb-5: an NPC shop buy and sell, then the Cash Shop EXP card taking effect. */
    static final class Vb5 extends Vb {
        Vb5() {
            super("vb-5", "NPC shop buy/sell and a Cash Shop EXP coupon applying to the player",
                    QaLog.Topic.NPC);
        }

        @Override
        String steps() {
            return "buy|sell|expcard";
        }
    }

    /** vb-6: the first job advancement at Dances with Balrog, then one skill point spent. */
    static final class Vb6 extends Vb {
        Vb6() {
            super("vb-6", "Job advancement at Dances with Balrog and a skill point spent",
                    QaLog.Topic.NPC);
        }

        @Override
        String steps() {
            return "advance|spend";
        }
    }

    /** vb-7: storage, Duey and a trade between the two pretend players. */
    static final class Vb7 extends Vb {
        Vb7() {
            super("vb-7", "Storage, Duey and a trade between two pretend players",
                    QaLog.Topic.NPC, QaLog.Topic.STORAGE, QaLog.Topic.TRADE);
        }

        @Override
        String steps() {
            return "storage|duey|trade";
        }
    }
}
