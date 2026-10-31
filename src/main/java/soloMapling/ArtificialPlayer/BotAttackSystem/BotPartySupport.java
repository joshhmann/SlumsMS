package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.BuffStat;
import client.Character;
import client.Skill;
import client.SkillFactory;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.skills.Cleric;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import server.ItemInformationProvider;
import server.StatEffect;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotHoldings;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals;
import soloMapling.ArtificialPlayer.BotSupportSwitches;
import soloMapling.qa.QaLog;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ticket 161 item 2 (1.6.2, behind {@code slums.bots.partysupport}): a bot partied with a real player looks after the
 * party with its own skills and potions, the way nutnnut/Cosmic's BotBuffManager supports a party - the part
 * {@link BotBuffDriver} (a cast on a timer, free of MP) and {@code BotBuffRequestHandler} (a buff on request) leave out:
 * <ul>
 *   <li><b>Heal.</b> A bot that holds Cleric's Heal (a Cleric, Priest or Bishop) casts it when the party member in
 *       the Heal's reach whose HP is lowest is under {@value #HEAL_BELOW_PERCENT}%, for the skill's MP, at most once
 *       every {@value #HEAL_GAP_MS} ms. The cast heals as a player's Heal does (1.6.2 healamt): every party member in
 *       its box and the caster get maxHp x hp% / (people healed), by the stock StatEffect selection and formula.
 *       It stays silent when it lacks the MP (after trying its potions) and never casts for itself alone.</li>
 *   <li><b>Buff potions.</b> A bot already holding an attack or defence potion drinks one - out of its ledger, through
 *       the item's real buff - when the stat is not already up and a fight is on. It never buys one; see
 *       {@link #pickPot}.</li>
 * </ul>
 * Heal shows viewers what a player's Heal shows them (b140), see {@link #showHealCast}. NOT yet verified on a real
 * client (ticket 161 needs a real-client check before this switch goes on).
 */
public final class BotPartySupport {

    static final int HEAL_BELOW_PERCENT = 60;
    static final long HEAL_GAP_MS = 4_000;
    static final long POT_GAP_MS = 3_000;
    static final int REACH_X = 600;
    static final int REACH_Y = 300;
    static final long HEAL_LOG_GAP_MS = 2_000;
    /*
     * b140: how long a Heal cast holds the bot's next attack. The cast plays Heal's pose (alert2, 270 ms) and its light
     * column (Skill.wz 2301002/effect, 9 frames, 1,080 ms); an attack straight after it (Angel Ray on TEST) puts the
     * attack's pose on the caster at once. 900 ms lets the pose and most of the column play first.
     */
    static final long HEAL_ATTACK_HOLD_MS = 900;

    private static final Map<Integer, Long> lastHealAt = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> lastPotAt = new ConcurrentHashMap<>();

    /** The pot stats worth a drink in a fight. */
    private static final Set<BuffStat> POT_STATS = Set.of(BuffStat.WATK, BuffStat.MATK, BuffStat.WDEF, BuffStat.MDEF);

    private BotPartySupport() {
    }

    // ---------------------------------------------------------------- pure rules

    /** One party member in reach, as the rules read them. */
    public record Member(int id, int hp, int maxHp, boolean alive, boolean bot) {
        double fraction() {
            return maxHp <= 0 ? 1.0 : (double) hp / maxHp;
        }
    }

    /** The member to heal: the lowest HP share under the line, or null. A dead member is a resurrection, not a heal. */
    public static Member pickHealTarget(List<Member> members) {
        Member best = null;
        for (Member m : members) {
            if (!m.alive() || m.maxHp() <= 0 || m.fraction() * 100.0 >= HEAL_BELOW_PERCENT) {
                continue;
            }
            if (best == null || m.fraction() < best.fraction()) {
                best = m;
            }
        }
        return best;
    }

    public static boolean healDue(long now, Long lastCast) {
        return lastCast == null || now - lastCast >= HEAL_GAP_MS;
    }

    /** A buff potion the bot holds: its item id and the stat it lifts (a pot of several is judged by its first). */
    public record Pot(int itemId, BuffStat stat, int amount) {
    }

    /**
     * The pot to drink: for a stat not already up, the strongest held, a mage's attack pot being the magic one and
     * everyone else's the weapon one. Null when nothing is worth drinking.
     */
    public static Pot pickPot(List<Pot> held, Set<BuffStat> active, boolean mage) {
        BuffStat attack = mage ? BuffStat.MATK : BuffStat.WATK;
        Pot best = null;
        for (Pot p : held) {
            if (!POT_STATS.contains(p.stat()) || active.contains(p.stat())) {
                continue;
            }
            if (p.stat() == BuffStat.WATK && mage || p.stat() == BuffStat.MATK && !mage) {
                continue;   // a magic pot is wasted on a swordsman and the other way round
            }
            boolean attackPot = p.stat() == attack;
            boolean bestAttack = best != null && best.stat() == attack;
            if (best == null || (attackPot && !bestAttack) || (attackPot == bestAttack && p.amount() > best.amount())) {
                best = p;
            }
        }
        return best;
    }

    /*
     * 162 runfix (t-oa-1 on TEST 10-30): getItemEffect throws an NPE for a USE item with no effect data - scrolls,
     * arrows, stars, bullets, summon sacks, the White Scroll. pots() read every USE item, so a partied bot holding any
     * of them threw out of tick(): an owned alt's tick died there on every field tick (before its gate), and the alt
     * never fought or looted. Only potion and food item ids (200xxxx-202xxxx) can be a pot.
     */
    static boolean mayBePot(int itemId) {
        int cat = itemId / 10000;
        return cat >= 200 && cat <= 202;
    }

    // ---------------------------------------------------------------- the game

    /** What the server does for real; behind a seam for the tests. */
    public interface Effects {
        List<Character> partyInReach(Character bot);

        boolean partiedWithPlayer(Character bot);

        boolean fighting(Character bot);

        int mpCost(Character bot, int skillId, int level);

        /** Makes sure the bot has the MP (drinking MP potions if it must) and pays it; false when it cannot. */
        boolean pay(Character bot, int mp);

        void heal(Character bot, Character target, int skillId, int level);

        List<Pot> pots(Character bot);

        Set<BuffStat> active(Character bot);

        boolean drink(Character bot, Pot pot);
    }

    private static volatile Effects effects = new GameEffects();

    public static void useForTest(Effects test) {
        effects = test == null ? new GameEffects() : test;
        lastHealAt.clear();
        lastPotAt.clear();
    }

    public static void clearBot(int botId) {
        lastHealAt.remove(botId);
        lastPotAt.remove(botId);
    }

    /** One look at the party. Cheap when the switch is off or nobody needs anything; true when the bot did something. */
    public static boolean tick(Character bot) {
        return tick(bot, System.currentTimeMillis());
    }

    static boolean tick(Character bot, long now) {
        if (!BotSupportSwitches.partySupport() || bot == null || bot.getMap() == null || !effects.partiedWithPlayer(bot)) {
            return false;
        }
        return heal(bot, now) || drinkPot(bot, now);
    }

    private static boolean heal(Character bot, long now) {
        int level = bot.getSkillLevel(Cleric.HEAL);
        if (level <= 0 || !healDue(now, lastHealAt.get(bot.getId()))) {
            return false;
        }
        List<Character> party = effects.partyInReach(bot);
        List<Member> members = new ArrayList<>(party.size());
        for (Character c : party) {
            members.add(new Member(c.getId(), c.getHp(), c.getCurrentMaxHp(), c.isAlive(), BotHelpers.isBot(c)));
        }
        Member target = pickHealTarget(members);
        if (target == null) {
            return false;
        }
        if (!effects.pay(bot, effects.mpCost(bot, Cleric.HEAL, level))) {
            lastHealAt.put(bot.getId(), now);   // no MP: try again after the gap, not every tick
            return false;
        }
        lastHealAt.put(bot.getId(), now);
        for (Character c : party) {
            if (c.getId() == target.id()) {
                effects.heal(bot, c, Cleric.HEAL, level);
                return true;
            }
        }
        return false;
    }

    private static boolean drinkPot(Character bot, long now) {
        Long last = lastPotAt.get(bot.getId());
        if (last != null && now - last < POT_GAP_MS || !effects.fighting(bot)) {
            return false;
        }
        Pot pot = pickPot(effects.pots(bot), effects.active(bot),
                bot.getJob() != null && bot.getJob().isA(client.Job.MAGICIAN));
        if (pot == null) {
            return false;
        }
        lastPotAt.put(bot.getId(), now);
        return effects.drink(bot, pot);
    }

    // ---------------------------------------------------------------- the Heal cast a viewer sees

    /*
     * b140: what a real player's Heal produces in this server, read from the v83 client (md5 89f69d20...) and the
     * stock handlers:
     *  - The client's Heal (CUserLocal 0x96a69d) sends SPECIAL_MOVE (0x5B), draws its own column locally, then tries a
     *    magic attack; it sends MAGIC_ATTACK only when an undead mob is in the box (0x956731: no mob, no packet).
     *  - SpecialMoveHandler -> StatEffect.applyTo -> applyBuff: per party member healed, the HP change, then
     *    showOwnBuffEffect(2301002, 2) to that member and showBuffEffect(member, 2301002, 2) to the rest of the map.
     *    Heal is not a buff over time, so nothing is broadcast for the caster.
     * A viewer's client draws the column on a remote caster only from SHOW_FOREIGN_EFFECT effect 1 (CUser::OnEffect
     * case 1: the skill's pose, then ShowSkillEffect with the same arguments the caster's own client uses). The stock
     * server never sends it for Heal, so this is the one packet a bot adds: the cast effect a player's buff gets.
     * The 0-mob MAGIC_ATTACK the bot used to send is gone - no client ever sends one, and a viewer's OnAttack resets
     * the caster's pose for it (0x980483).
     */
    static final String SENT_CAST = "SHOW_FOREIGN_EFFECT/1>map";
    static final String SENT_OWN_AFFECTED = "SHOW_ITEM_GAIN_INCHAT/2>target";
    static final String SENT_FOREIGN_AFFECTED = "SHOW_FOREIGN_EFFECT/2>map";

    /* b140: the caster's cast effect for Heal to the bot's map (all but the bot), sent by the same StatEffect code as a
       player's Heal (1.6.2 healamt); what was sent, or null. */
    static String showHealCast(Character bot, StatEffect heal) {
        if (bot == null || bot.getMap() == null || heal == null) {
            return null;
        }
        heal.showCastEffect(bot);
        return SENT_CAST;
    }

    /* b140: one COMBAT line per bot Heal cast (at most one per bot every 2 s); a real player's heal to that player's run. */
    static void logHeal(Character bot, Character target, int level, List<String> sent, int hpBefore, int hpAfter,
                        int healedCount, int share) {
        if (QaLog.on(QaLog.Topic.COMBAT) && QaLog.every("party-heal:" + bot.getId(), HEAL_LOG_GAP_MS)) {
            String line = "PARTY HEAL " + bot.getName() + " -> " + target.getName()
                    + " skill " + Cleric.HEAL + " lv " + level
                    + " hp " + hpBefore + "->" + hpAfter
                    + " healed " + healedCount + " x " + share
                    + " sent " + (sent == null || sent.isEmpty() ? "none" : String.join(" ", sent))
                    + " hold " + HEAL_ATTACK_HOLD_MS + "ms";
            if (!BotHelpers.isBot(target)) {
                QaLog.owner(QaLog.Topic.COMBAT, target.getId(), line);   // the run the healed player started, any map
            } else {
                QaLog.map(QaLog.Topic.COMBAT, bot.getMapId(), line);
            }
        }
    }

    // ---------------------------------------------------------------- real effects

    static final class GameEffects implements Effects {
        @Override
        public List<Character> partyInReach(Character bot) {
            Party party = bot.getParty();
            List<Character> out = new ArrayList<>();
            if (party == null || bot.getMap() == null) {
                return out;
            }
            // 1.6.2 healamt: with Heal, the members its cast really reaches (the stock StatEffect selection), so the
            // member picked is one the cast heals
            StatEffect heal = healEffect(bot.getSkillLevel(Cleric.HEAL));
            if (heal != null && heal.isPartyBuff()) {
                return heal.partyBuffTargets(bot, false);
            }
            Point here = bot.getPosition();
            for (PartyCharacter pc : party.getMembers()) {
                Character c = pc == null ? null : pc.getPlayer();
                // b168 follow-up: the same map OBJECT, not just the id (another channel's or instance's copy is out of reach)
                if (c == null || c == bot || !BotHelpers.sameMap(c, bot) || c.getPosition() == null || here == null) {
                    continue;
                }
                if (Math.abs(c.getPosition().x - here.x) <= REACH_X && Math.abs(c.getPosition().y - here.y) <= REACH_Y) {
                    out.add(c);
                }
            }
            return out;
        }

        @Override
        public boolean partiedWithPlayer(Character bot) {
            Party party = bot.getParty();
            if (party == null) {
                return false;
            }
            for (PartyCharacter pc : party.getMembers()) {
                Character p = pc == null ? null : pc.getPlayer();
                if (p != null && p != bot && !BotHelpers.isBot(p)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean fighting(Character bot) {
            return bot.getMap().getAllMonsters().stream().anyMatch(m -> m.isAlive());
        }

        @Override
        public int mpCost(Character bot, int skillId, int level) {
            return BotVitals.mpCost(bot, skillId, level);
        }

        @Override
        public boolean pay(Character bot, int mp) {
            if (!BotVitals.affordMp(bot, mp)) {
                return false;
            }
            BotVitals.spend(bot, mp, 0);
            return true;
        }

        @Override
        public void heal(Character bot, Character target, int skillId, int level) {
            StatEffect effect = healEffect(level);
            if (effect != null) {
                castHeal(bot, target, level, effect);
            }
        }

        private static StatEffect healEffect(int level) {
            if (level <= 0) {
                return null;
            }
            Skill skill = SkillFactory.getSkill(Cleric.HEAL);
            return skill == null ? null : skill.getEffect(level);
        }

        /*
         * One bot Heal with the skill level's effect, as a player's Heal (SpecialMoveHandler -> StatEffect.applyTo):
         * the cast effect, then every party member the cast's box reaches - the stock selection - gets maxHp x hp% /
         * (members healed + the caster), its HP change and the "affected" effects, then the caster its own share.
         * {@code target} is the member the bot cast for (the log's subject). Then the hold and the log.
         */
        static void castHeal(Character bot, Character target, int level, StatEffect effect) {
            int skillId = Cleric.HEAL;
            int before = target.getHp();
            List<String> sent = new ArrayList<>(3);
            String cast = showHealCast(bot, effect);
            if (cast != null) {
                sent.add(cast);
            }
            List<Character> healed = effect.partyBuffTargets(bot, false);
            int count = healed.size() + 1;   // the caster counted, as StatEffect.applyBuff counts
            int share = effect.partyMemberHpChange(bot, count);
            for (Character c : healed) {
                if (BotHelpers.isBot(c)) {
                    BotVitals.heal(c, share);
                } else {
                    effect.applyToTarget(bot, c, count);
                }
                BotBuffEffects.showReceived(c, skillId);
            }
            if (!healed.isEmpty()) {
                sent.add(SENT_OWN_AFFECTED);
                sent.add(SENT_FOREIGN_AFFECTED);
            }
            BotVitals.heal(bot, share);   // the caster's own share (a player's primary applyTo; Heal has no HP cost)
            BotAttackDriver.holdAttacks(bot.getId(), System.currentTimeMillis() + HEAL_ATTACK_HOLD_MS);
            logHeal(bot, target, level, sent, before, target.getHp(), count, share);
        }

        @Override
        public List<Pot> pots(Character bot) {
            List<Pot> out = new ArrayList<>();
            Inventory use = bot.getInventory(InventoryType.USE);
            use.lockInventory();
            try {
                for (Item item : use.list()) {
                    if (item.getQuantity() <= 0 || !mayBePot(item.getItemId())
                            || BotVitals.isRecoveryItem(item.getItemId())) {
                        continue;
                    }
                    StatEffect fx;
                    try {
                        fx = ItemInformationProvider.getInstance().getItemEffect(item.getItemId());
                    } catch (RuntimeException e) {
                        continue;   // an item with no effect data: not a pot
                    }
                    if (fx == null || fx.getStatups().isEmpty()) {
                        continue;
                    }
                    out.add(new Pot(item.getItemId(), fx.getStatups().get(0).getLeft(), fx.getStatups().get(0).getRight()));
                }
            } finally {
                use.unlockInventory();
            }
            return out;
        }

        @Override
        public Set<BuffStat> active(Character bot) {
            Set<BuffStat> up = new java.util.HashSet<>();
            for (BuffStat s : POT_STATS) {
                if (bot.getBuffedValue(s) != null) {
                    up.add(s);
                }
            }
            return up;
        }

        @Override
        public boolean drink(Character bot, Pot pot) {
            Item held = null;
            for (Item item : bot.getInventory(InventoryType.USE).listById(pot.itemId())) {
                held = item;
                break;
            }
            StatEffect fx = ItemInformationProvider.getInstance().getItemEffect(pot.itemId());
            if (held == null || fx == null || BotHoldings.consumeHeld(bot, held, 1) != 1) {
                return false;
            }
            fx.applyTo(bot);
            return true;
        }
    }
}
