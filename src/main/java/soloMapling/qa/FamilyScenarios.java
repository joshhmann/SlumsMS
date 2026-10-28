package soloMapling.qa;

import client.Character;
import client.FamilyBuffs;
import client.FamilyEntry;
import config.YamlConfig;
import soloMapling.ArtificialPlayer.FamilyQa;

import java.util.List;
import java.util.Set;

/**
 * 1.6.1, ticket 53: fy-1, the Family system. Stages a family with the GM as senior and an online bot as their junior
 * (the bot says yes through its family rules), shows the junior's rep reaching the GM, and gives the GM enough rep to
 * click every entitlement in the client's Family window once. {@code !qa do check} prints rep and the active buffs.
 * Stop dissolves the family again. The auto-QA check t-fy-1 runs the same staging without a client.
 */
final class FamilyScenarios {
    /** Rep the GM gets for the run: every entitlement's cost added up (19,200), rounded up. */
    static final int QA_REP = 20_000;

    private FamilyScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new Family());
    }

    static final class Family implements QaScenario {
        public String id() { return "fy-1"; }
        public String title() { return "Family: a bot junior, rep, and all the entitlements (EXP / drop buffs, Bonding)"; }
        public Set<QaLog.Topic> topics() { return Set.of(); }
        public int minutes(String[] args) { return 30; }

        public List<String> stage(QaContext ctx) {
            if (!YamlConfig.config.server.USE_FAMILY_SYSTEM) {
                throw new QaRefusal("The Family system is off on this server (USE_FAMILY_SYSTEM).");
            }
            Character gm = ctx.gm();
            FamilyQa.Staged st;
            try {
                st = FamilyQa.stage(gm);
            } catch (IllegalStateException e) {
                throw new QaRefusal(e.getMessage());
            }
            ctx.onStop("dissolve the QA family", () -> ctx.run().note("CLEANUP", FamilyQa.teardown(ctx.gmNow(), st)));
            FamilyEntry mine = gm.getFamilyEntry();
            int before = mine.getReputation();
            st.bot().getFamilyEntry().giveReputationToSenior(YamlConfig.config.server.FAMILY_REP_PER_LEVELUP, true);
            int fromJunior = mine.getReputation() - before;
            mine.gainReputation(QA_REP, false);
            ctx.log("FY1 START family=" + st.familyId() + " junior=" + st.bot().getName() + " lv" + st.bot().getLevel()
                    + " repFromJuniorLevelUp=" + fromJunior + " rep=" + mine.getReputation());
            return List.of(
                    st.bot().getName() + " (lv " + st.bot().getLevel() + ") accepted being your junior. Their level-up"
                            + " just gave you " + fromJunior + " rep; you also got " + QA_REP + " rep for this run.",
                    "1) Open the Family window: you are the senior, " + st.bot().getName() + " your junior.",
                    "2) Use each EXP / drop entitlement once (1.5x and 2x, 15 and 30 min, party): a yellow"
                            + " [Family] line says what you got; kill a few mobs and compare EXP and drops.",
                    "3) Family Bonding refuses unless " + net.server.channel.handlers.FamilyUseHandler.bondingMinimum()
                            + " members below you are online (nothing spent). Reunion / Summon need a 2nd member.",
                    "!qa do check shows your rep and buffs. !qa stop dissolves the family (relog to clear the window).");
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if (!"check".equals(name)) {
                throw new QaRefusal("fy-1 steps: check");
            }
            Character gm = ctx.gmNow();
            FamilyEntry e = gm == null ? null : gm.getFamilyEntry();
            long now = System.currentTimeMillis();
            String line = "FY1 CHECK rep " + (e == null ? "-" : e.getReputation()) + ", EXP x"
                    + FamilyBuffs.expMultiplier(gm.getId(), now) + ", drop x" + FamilyBuffs.dropMultiplier(gm.getId(), now)
                    + ", minutes left " + java.util.Arrays.toString(FamilyBuffs.minutesLeft(gm.getId(), now));
            ctx.run().note("FY1", line);
            return List.of(line);
        }
    }
}
