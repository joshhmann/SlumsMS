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
                    "Movement, combat, formations and automatic trade offers are deferred; this check covers the lifecycle foundation.");
        }
    }
}
