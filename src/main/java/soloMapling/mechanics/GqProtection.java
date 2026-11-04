package soloMapling.mechanics;

import java.util.Optional;

/**
 * Guild Quest's air: every map from {@code 990000300} on takes {@code decHP 30000} from whoever does not have the
 * Protector Rock earring on ({@code MapFactory.java:358-359}, {@code Character.doHurtHp}: {@code Character.java:3606-3609}),
 * so no stage may ask a member to do anything on those maps before it is wearing it. {@link #step} is the one answer
 * every Guild Quest solver gives first: nothing to do (null) when the member wears the earring, otherwise the step that
 * fixes it - put it on when it is carried, say why it cannot when it may not wear it, or wait naming the missing item
 * (a stage then costs the party the time the missing drop row costs, never a mystery).
 *
 * <p>The earring is item {@code 1032033}: level 10, +10 magic defence, one per character
 * ({@code Character.wz/Accessory/01032033.img.xml}); it drops from the rocks on {@code 990000100} and {@code 990000200}
 * (reactors {@code 9202000}/{@code 9202009}, {@code scripts/reactor/9202000.js}; the drop row is seed data,
 * {@code reactordrops 9202000 -> 1032033}, <b>live DB unverified</b>).
 */
final class GqProtection {

    /** The Protector Rock. */
    static final int EARRING = 1032033;

    private GqProtection() {
    }

    /** What this member must do about the earring before anything else, or null when it is wearing it. */
    static SolverStep step(StageView view, RunMember member) {
        int id = member.characterId();
        if (view.wearing(id, EARRING)) {
            return null;
        }
        if (view.itemCount(id, EARRING) > 0) {
            Optional<String> cannot = view.cannotWear(id, EARRING);
            if (cannot.isPresent()) {
                return new SolverStep.Abandon("a member cannot wear the Protector Rock, so every map from 990000300 on "
                        + "would take 30000 HP a tick from it: " + cannot.get());
            }
            return new SolverStep.EquipItem(EARRING, "the maps from 990000300 on take 30000 HP a tick without it");
        }
        return new SolverStep.Wait(2_000, "no Protector Rock (" + EARRING + ") on this member: the rocks on 990000100 "
                + "drop it (live reactordrops row unverified)");
    }
}
