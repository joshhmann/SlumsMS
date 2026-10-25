package soloMapling.qa;

import soloMapling.ArtificialPlayer.BotConvo.MessyChat;
import soloMapling.ArtificialPlayer.BotTownSystem.AfkCampers;
import soloMapling.ArtificialPlayer.GCMoveSystem.WatchGate;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static soloMapling.qa.QaLog.Topic.LIKENESS;

/**
 * 1.6.0 player likeness: lk-1 logs the three owner-approved changes as they happen on the server - AFK campers per
 * channel ({@code CAMPERS}, {@code CAMP START}/{@code CAMP END}), bots that walked instead of snapping because a player
 * was on their map ({@code WATCHED WALK}, and {@code WATCHED SNAP} when one still snapped), and bot lines before and
 * after the messy-chat pass ({@code MESSY}). Nothing is staged: walk around Henesys, the FM entrance or Kerning.
 */
final class LikenessScenarios {

    private LikenessScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new Lk1Likeness());
    }

    /** Fixed lines for {@code !qa do sample}: what a few bot styles make of them. */
    static final List<String> SAMPLES = List.of(
            "Hey, are you going to Henesys right now?",
            "Thank you so much for the buff!",
            "I'm selling Brown Work Gloves for 50k, anyone?",
            "Okay, be right back. I need potions.",
            "What level are you? I'm 34.");

    static final class Lk1Likeness implements QaScenario {
        public String id() { return "lk-1"; }
        public String title() { return "Player likeness: AFK campers, bots walk when watched, messy chat"; }
        public Set<QaLog.Topic> topics() { return EnumSet.of(LIKENESS); }
        public int minutes(String[] args) { return 20; }
        public int tickSeconds() { return 30; }

        public List<String> stage(QaContext ctx) {
            status(ctx);
            return List.of("Walk round Henesys, Kerning or the FM entrance: 5-15 bots a channel stand AFK, some on a chair.",
                    "Watch bots near you: none should blink across the map; WATCHED WALK lines show snaps turned into walks.",
                    "Bot chat should be mostly lowercase and short (u, ur, ty, brb); MESSY lines show before -> after.",
                    "!qa do camp runs the camper pass now; !qa do sample styles five fixed lines for a few bots.");
        }

        public void tick(QaContext ctx) {
            status(ctx);
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            return switch (name) {
                case "camp" -> {
                    List<String> out = AfkCampers.passNow();
                    out.forEach(l -> ctx.run().note("LIKENESS", l));
                    yield out;
                }
                case "sample" -> {
                    List<String> out = samples();
                    out.forEach(l -> ctx.run().note("LIKENESS", l));
                    yield out;
                }
                default -> throw new QaRefusal("lk-1 steps: camp, sample.");
            };
        }

        static List<String> samples() {
            List<String> out = new ArrayList<>();
            for (int slot : new int[]{11, 42, 77, 305}) {
                for (String line : SAMPLES) {
                    out.add("MESSY sample slot " + slot + " (" + MessyChat.styleOf(slot).name().toLowerCase() + "): \""
                            + line + "\" -> \"" + MessyChat.apply(line, slot, List.of()) + "\"");
                }
            }
            return out;
        }

        private static void status(QaContext ctx) {
            for (String line : AfkCampers.describe()) {
                ctx.run().note("LIKENESS", line);
            }
            ctx.run().note("LIKENESS", WatchGate.describe() + " messyChat=" + (MessyChat.enabled() ? "on" : "off"));
        }
    }
}
