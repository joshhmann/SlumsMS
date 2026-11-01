package soloMapling.qa;

import client.Character;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static soloMapling.qa.QaLog.Topic.PETPOT;
import static soloMapling.qa.QaLog.Topic.STAGING;

/**
 * 1.6.3 b280: a pet's auto-potion on a real client - the pouch, the red potions, key 91 and the server's own trigger.
 *
 * <p>{@code !qa do petpot} (and {@code !qa run pp-1}, which stages the same scene) puts the GM in the state the test
 * needs: a pet out, the Auto HP Potion Pouch (1812002) on it, 100 red potions in USE, key 91 bound to them and the HP
 * alert at 50%. Then the GM takes damage and watches its pet drink: the client sends {@code PET_AUTO_POT}, the server
 * answers with {@code PETPOT recv}, and a hit that the server itself notices (a mob hit on the character's own HP) is
 * {@code PETPOT server}. The scene stays after {@code !qa stop} (pet, pouch, potions and key are ordinary player
 * things); the lines are in the run's log and in the QA event feed as {@code STAGING}.
 *
 * <p>Registered in {@link QaScenarios#all()}; docs/ops/qa-suite.md has the row.
 */
final class PetPotScenarios {

    private PetPotScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new Pp1());
    }

    /** pp-1: the staged pet, pouch, potions and keys, then the two PETPOT lines when the pet drinks. */
    static final class Pp1 implements QaScenario {

        public String id() {
            return "pp-1";
        }

        public String title() {
            return "Pet auto-potion: the pouch, 100 red potions on key 91 and the server's own trigger";
        }

        public String usage() {
            return "!qa do petpot stages it again";
        }

        public Set<QaLog.Topic> topics() {
            return EnumSet.of(PETPOT, STAGING);
        }

        public int minutes() {
            return 15;
        }

        public List<String> stage(QaContext ctx) {
            return petpot(ctx);
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if (!"petpot".equals(name)) {
                throw new QaRefusal(id() + " has no '" + name + "' step (use petpot).");
            }
            return petpot(ctx);
        }

        /** The staging itself: the game's steps, one log line each, then what the GM should do. */
        private static List<String> petpot(QaContext ctx) {
            Character gm = ctx.gmNow() != null ? ctx.gmNow() : ctx.gm();
            int mapId = gm.getMapId();
            List<String> lines = ctx.world().petPotStage(gm);
            for (String line : lines) {
                QaLog.chars(STAGING, mapId, new int[]{gm.getId()}, "STAGE PETPOT " + line);
                ctx.log("PETPOT " + line);
            }
            List<String> out = new ArrayList<>(lines);
            if (PetPotStaging.anyRefused(lines)) {
                out.add("A step refused: see the lines above and the log. Pet auto-potion needs all five.");
                return out;
            }
            out.add("Take damage below 50% HP on a map with mobs: the pet drinks the red potions by itself."
                    + " The log gets PETPOT recv (the client's request) and PETPOT server (the server's own trigger).");
            out.add("Setup stays after !qa stop: the pet, the pouch, the red potions and key 91 are yours now.");
            return out;
        }
    }
}
