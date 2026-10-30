package soloMapling.qa;

import config.YamlConfig;
import java.util.List;
import java.util.Set;

/** Manual lifecycle check: uses the real player commands and watches their commit/removal events. */
final class OwnedAltScenarios {
    private OwnedAltScenarios() {}
    static List<QaScenario> all() { return List.of(new Lifecycle(), new Companions()); }
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
                    "1.6.2 companion orders, trades, upgrades and supplies: !qa run oa-2.");
        }
    }

    /** 1.6.2 ticket 99: the companion orders and the sharing set, one switch each. */
    static final class Companions implements QaScenario {
        public String id() { return "oa-2"; }
        public String title() { return "Companions: orders, formations, trades, upgrades, supplies, invite summon"; }
        public Set<QaLog.Topic> topics() { return Set.of(QaLog.Topic.OWNED_ALT); }
        public List<String> stage(QaContext ctx) {
            var c = YamlConfig.config.server;
            if (!c.OWNED_ALTS_ENABLED)
                throw new QaRefusal("Enable OWNED_ALTS_ENABLED on an isolated TEST build first.");
            if (!(c.COMPANION_ORDERS_ENABLED || c.COMPANION_TRADES_ENABLED || c.COMPANION_UPGRADES_ENABLED
                    || c.COMPANION_SUPPLIES_ENABLED || c.OWNED_ALTS_INVITE_SUMMON_ENABLED))
                throw new QaRefusal("Turn on the COMPANION_* switches (and OWNED_ALTS_INVITE_SUMMON_ENABLED) on TEST first.");
            return List.of("Use an account with two more characters in this world (QA staging: !qa do alts makes them: <your name>Alt1 lv30 with potions, hat, ETC and a sword; Alt2 lv30 with 5 Red Potions; run it again to top up; !qa do alts remove deletes only those it made; !qa stop keeps them). Call both with @botme <name>. !companions shows the switches and each one's stance.",
                    "Orders (party chat or @alts): 'stay' - they stop and fight nothing they would walk to; 'follow' - they walk again; '1 move here' - the first walks to where you stand and holds; 'sentry' - they hold and hit only what comes near; 'patrol' - they walk back and forth around you. Stand 3 minutes still: they stop fighting (gate).",
                    "Formations: 'spread', 'formation left 80', 'tight', 'loose', 'stack', 'formation random' - they keep those places while following; 'snap' - every one back to following at its place.",
                    "Trades (normal window, a beat between each step): 'trade pots', 'trade hp pots', 'trade scrolls', 'trade etc', 'trade equips', 'trade 100k', 'trade <item name>', 'trade hat' (a spare hat in its bag). Cancel once: the items go back to the alt. Accept once: check the trade log line.",
                    "'show me your hat' - it takes the hat off, shows it in a trade window; cancel - it wears it again. 'drop etc' - it drops up to 9 stacks, one a beat.",
                    "Upgrades: give alt 1 a spare equip that is better for you and one better for alt 2. 'any upgrades?' names both; 'request?' names your spare that helps it; 'trade upgrades' trades yours to you, then the other to alt 2 (no window for you).",
                    "Supplies: empty your HP pots, say 'need hp pots' - the alt with the most to spare (it keeps 50) trades you up to 100. Empty one alt's pots: within ~15 s its sibling trades it some.",
                    "Invite summon: dismiss an alt, then invite its name from the party window - it is called as with @botme. A name that is not yours: the normal 'cannot find' answer.",
                    "Never: a line saying it is a bot, an item moving outside a trade window or a drop, an alt walking off on its own.");
        }

        /** {@code !qa do alts [remove]}: stage (or clean up) the two extra characters; GM 3 and audited by the command. */
        public List<String> action(QaContext ctx, String name, String[] args) {
            if (!"alts".equals(name)) throw new QaRefusal("oa-2 steps: alts | alts remove");
            OwnedAltStaging staging = new OwnedAltStaging(OwnedAltStaging.DEFAULT_MARKER);
            var host = OwnedAltStaging.liveHost(ctx.gmNow());
            boolean remove = args != null && args.length > 0 && "remove".equalsIgnoreCase(args[0]);
            List<String> lines = remove ? staging.remove(host) : staging.make(host);
            for (String l : lines) ctx.log(l);
            return lines;
        }
    }
}
