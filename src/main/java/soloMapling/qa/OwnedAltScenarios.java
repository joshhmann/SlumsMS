package soloMapling.qa;

import config.YamlConfig;
import java.util.List;
import java.util.Set;

/** Manual lifecycle check: uses the real player commands and watches their commit/removal events. */
final class OwnedAltScenarios {
    private OwnedAltScenarios() {}
    static List<QaScenario> all() { return List.of(new Lifecycle()); }
    static final class Lifecycle implements QaScenario {
        public String id() { return "oa-1"; }
        public String title() { return "Owned alts: summon, save, dismiss and fast character login"; }
        public Set<QaLog.Topic> topics() { return Set.of(QaLog.Topic.OWNED_ALT); }
        public List<String> stage(QaContext ctx) {
            if (!YamlConfig.config.server.OWNED_ALTS_ENABLED)
                throw new QaRefusal("Enable OWNED_ALTS_ENABLED on an isolated TEST build first.");
            if (!YamlConfig.config.server.OWNED_ALTS_SUMMON_ENABLED)
                throw new QaRefusal("@botme summoning is resting here (OWNED_ALTS_SUMMON_ENABLED off). Turn it on on TEST first.");
            return List.of("Use an account with a second character in this world. Record that character's EXP, mesos, items and quests.",
                    "Type @botme, then @botme <name>. The alt joins your channel, map and party. Repeat: it must refuse a duplicate.",
                    "@dismiss <name> must log one 'save committed' then 'removed'. Log into the alt and compare its progress.",
                    "Repeat summon, owner logout and immediate alt login 50 times. A pending save may refuse login; retry from character selection.",
                    "Summon again, then @dismiss all. No progress after owner logout. Check the log for one final save per dismissal.",
                    "1.6.0.4: walk through a portal - the alt follows. Enter a PQ without it - it waits outside. @botme here brings it back.",
                    "1.6.0.4: change channel - 'Your alts went home.' on arrival. Expel the alt from the party - it goes home saved.",
                    "1.6.1: on a field, hit a mob - your alt walks over and fights it with its own skills (you see its attacks)."
                            + " It never touches a mob a stranger is fighting, and never fights in town.",
                    "1.6.1: mobs hit your alt (its HP drops, it drinks its own potions). Let it die once: it loses EXP like a"
                            + " player, gets up in town and goes home; you are told. Its drops and the party's go into its bag;"
                            + " a full bag is said once in party chat.",
                    "1.6.1: stand still 3 minutes - 'Your alts stopped fighting'; move and it joins in again. Take a boat -"
                            + " it catches up on arrival.",
                    "Formations and automatic trade offers are deferred.");
        }
    }
}
