package soloMapling.qa;

import java.util.List;
import java.util.Set;
import soloMapling.crafting.WeaponCoreExchange;

/** Hands-on checklist only: does not grant gear, alter a character or declare a gameplay pass. */
final class GearProgressionScenarios {
    private GearProgressionScenarios() { }
    static List<QaScenario> all() { return List.of(new CoreExchange(), new NormalBridge()); }
    static final class NormalBridge implements QaScenario {
        public String id() { return "ng-10"; }
        public String title() { return "Normal gear bridge: Nimble guide, sources and previous-tier combat"; }
        public Set<QaLog.Topic> topics() { return Set.of(); }
        public List<String> stage(QaContext ctx) {
            ctx.log("NORMAL_BRIDGE enabled=" + soloMapling.crafting.NormalGearBridge.isEnabled() + "; hands-on outcome required");
            return List.of(
                "After migration 061, drop cache reload and slums.gear.bridge=true: open Nimble's Normal equipment guide.",
                "Check all five families and three bands, icons, item names, source names/maps and equip requirements; cancel and test the disabled menu.",
                "In actual previous-tier equipment at levels 120/130/140, test source access, hits, misses, potion use and EXP/mesos against Dragon Nest.",
                "Verify actual gear stats and set bonuses, Magic Guard/MP where relevant, and both ordinary drop sources per item.",
                "Record installed client/server data, screenshots and measured results. No gear/currency is granted and no combat pass is asserted by this checklist.");
        }
    }
    static final class CoreExchange implements QaScenario {
        public String id() { return "ng-8"; }
        public String title() { return "Nimble: class weapon core exchange, cancellation and full inventory"; }
        public Set<QaLog.Topic> topics() { return Set.of(); }
        public List<String> stage(QaContext ctx) {
            ctx.log("CORE_EXCHANGE enabled=" + WeaponCoreExchange.isEnabled() + "; hands-on outcome required");
            return List.of(
                "Bring one class weapon core to Nimble (9977779). No items or currency are granted by this checklist.",
                "With slums.forge.coreexchange=true: exchange to another class, then back. Total cores and mesos must stay unchanged.",
                "Cancel at confirmation; remove the selected input before confirming; fill the Etc inventory. Each denial must preserve inventory and mesos.",
                "With the switch absent: the exchange is hidden and an injected selection is refused.",
                "Record the actual client/build, screenshots and before/after inventory counts in t-ng-8; this scenario does not automatically pass it.");
        }
    }
}
