package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Character;
import client.Skill;
import client.SkillFactory;
import constants.skills.Aran;
import constants.skills.Bishop;
import constants.skills.BlazeWizard;
import constants.skills.Bowmaster;
import constants.skills.Crusader;
import constants.skills.DarkKnight;
import constants.skills.DawnWarrior;
import constants.skills.Evan;
import constants.skills.FPArchMage;
import constants.skills.Hermit;
import constants.skills.Hero;
import constants.skills.ILArchMage;
import constants.skills.Magician;
import constants.skills.NightLord;
import constants.skills.NightWalker;
import constants.skills.Paladin;
import constants.skills.ThunderBreaker;
import constants.skills.WhiteKnight;
import net.server.Server;
import net.server.world.Party;
import server.StatEffect;
import soloMapling.ArtificialPlayer.BotHelpers;
import tools.PacketCreator;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/*
 * Buff-visual packet ids here are vanilla Cosmic (server.StatEffect), not GreenCatMS-sourced.
 * The "show a buff" broadcast for bots. Vanilla StatEffect.applyTo does far more than the
 * visual (HP/MP, consumption, morphs, effect registration) and bails out on many branches,
 * so for cosmetic bots we fire just the two broadcasts that make a buff look like it happened:
 * the cast animation (showBuffEffect) and, for buffs that have one, the aura other players see
 * (StatEffect.foreignBuffPacket, the packet applyTo would pick). No stats are
 * changed and no effect is registered.
 */
public final class BotBuffEffects {

    // effectId 1 + direction 3 = the standard self-cast buff effect (matches the
    // primary-cast broadcast in StatEffect).
    private static final int CAST_EFFECT_ID = 1;

    private BotBuffEffects() {}

    /*
     * 1.6.2 audit #1 (b110): the skill table and the MP rule a hand-out uses. A bot hands a real player the buff at
     * the level it has learned (never the max level), pays that level's MP for it, and hands nothing out for a skill
     * it has not learned. The visual (cast animation, aura) still reads the max-level effect: it changes no stat.
     */
    interface Rules {
        Skill skill(int skillId);
        /* Pays a cast of skillId at level out of the bot's MP (drinking MP potions if it must); false = can't. */
        boolean pay(Character bot, int skillId, int level);
    }

    private static final Rules SERVER = new Rules() {
        @Override public Skill skill(int skillId) { return SkillFactory.getSkill(skillId); }
        @Override public boolean pay(Character bot, int skillId, int level) {
            int cost = soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals.mpCost(bot, skillId, level);
            if (cost <= 0) {
                return true;
            }
            if (!soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals.affordMp(bot, cost)) {
                return false;
            }
            soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals.spend(bot, cost, 0);
            return true;
        }
    };

    private static volatile Rules rules = SERVER;

    /* Test seam: a fake skill table / MP rule, or back to the server's with null. */
    static void useForTest(Rules r) {
        rules = r == null ? SERVER : r;
    }

    private static Skill skill(int skillId) {
        return rules.skill(skillId);
    }

    /* The level this bot casts skillId at: what it has learned, capped at the skill's max; 0 = not learned. */
    public static int castLevel(Character bot, int skillId) {
        if (bot == null) return 0;
        int level = bot.getSkillLevel(skillId);
        if (level <= 0) return 0;
        Skill skill = skill(skillId);
        int max = skill == null ? 0 : skill.getMaxLevel();
        return max > 0 ? Math.min(level, max) : level;
    }

    /* The working effect this bot can hand out: at its learned level, or null when it has not learned the skill. */
    static StatEffect handOutEffect(Character bot, int skillId) {
        int level = castLevel(bot, skillId);
        if (level <= 0) return null;
        Skill skill = skill(skillId);
        return skill == null ? null : skill.getEffect(level);
    }

    /*
     * Pays the MP a cast of skillId costs this bot at its learned level (a skill it has not learned costs nothing:
     * it hands nothing out). The one MP rule for buffs, for population bots and owned alts alike.
     */
    public static boolean payFor(Character bot, int skillId) {
        int level = castLevel(bot, skillId);
        return level <= 0 || rules.pay(bot, skillId, level);
    }

    /*
     * Broadcast the buff visual for skillId on the bot to everyone on its
     * map. Returns the buff's WZ duration in ms (for recast cadence), or 0 if the
     * skill/effect can't be resolved. Does NOT apply any stat.
     */
    public static int showBuff(Character bot, int skillId) {
        if (bot == null || bot.getMap() == null) return 0;

        // The cast animation only needs the skill id - fire it regardless of
        // whether the StatEffect resolves, so the visual is never skipped.
        bot.getMap().broadcastMessage(bot, castPacket(bot, skillId), false);

        // The persistent aura needs the buff's stat list (cheap memoized lookup,
        // no applyTo). Skip silently if the skill has no stat ups.
        Skill skill = skill(skillId);
        if (skill == null) return 0;
        StatEffect effect = skill.getEffect(skill.getMaxLevel());
        if (effect == null) return 0;

        // Combo Attack's aura carries the orb count, which a bot keeps only in BotComboOrbs (its buffs are never
        // registered, so the stock packet would say 0 - no buff at all - and its finishers would draw as plain swings).
        if (skillId == Crusader.COMBO && BotComboOrbs.usesCombo(bot.getJob())) {
            BotComboOrbs.showOrbs(bot);
            return effect.getDuration();
        }

        // Only what players' own casts show others (StatEffect.applyTo): most buffs have no aura at all, and a raw
        // stat list in giveForeignBuff is misread - or overruns the packet for two-state buffs - on every client.
        net.packet.Packet aura = effect.foreignBuffPacket(bot, effect.getDuration());
        if (aura != null) {
            bot.getMap().broadcastMessage(bot, aura, false);
        }

        return effect.getDuration();
    }

    /*
     * The cast effect viewers get for a skill a player casts (SHOW_FOREIGN_EFFECT, effect 1 "skill use": the packet
     * StatEffect.applyBuffEffect broadcasts for a player's buff). The v83 client (CUser::OnEffect 0x9377d9, case 1)
     * plays the skill's own pose on the caster and draws its Skill.wz "effect" (CUser::ShowSkillEffect 0x933990).
     */
    public static net.packet.Packet castPacket(Character bot, int skillId) {
        return PacketCreator.showBuffEffect(bot.getId(), skillId, CAST_EFFECT_ID);
    }

    /* True when the buff reaches party members (its effect has an area box). */
    public static boolean isPartyBuff(int skillId) {
        Skill skill = skill(skillId);
        StatEffect effect = skill == null ? null : skill.getEffect(skill.getMaxLevel());
        return effect != null && effect.isPartyBuff();
    }

    /* The buff's WZ duration (ms) at the level this bot casts it (max level when it has not learned it). */
    public static int durationOf(Character bot, int skillId) {
        int level = castLevel(bot, skillId);
        if (level <= 0) return durationOf(skillId);
        Skill skill = skill(skillId);
        StatEffect effect = skill == null ? null : skill.getEffect(level);
        return effect != null ? effect.getDuration() : 0;
    }

    /* The buff's WZ duration (ms) at max level, or 0 if unresolvable. Cheap memoized lookup. */
    public static int durationOf(int skillId) {
        Skill skill = skill(skillId);
        if (skill == null) return 0;
        StatEffect effect = skill.getEffect(skill.getMaxLevel());
        return effect != null ? effect.getDuration() : 0;
    }

    // Range a party buff reaches around the casting bot (split x/y, since MapleStory
    // maps are wide and short). Members further than this aren't buffed.
    private static final int PARTY_BUFF_RANGE_X = 700;
    private static final int PARTY_BUFF_RANGE_Y = 350;

    /* A sensible "extended" buff length for bots: 10 minutes. */
    public static final int EXTENDED_DURATION_MS = 600_000;

    /*
     * Full buff cast for a bot. Always shows the cast animation on the bot; and if
     * the skill is a PARTY buff (has an area-of-effect box), also grants it to the
     * bot's nearby party members - real players get the working buff, party-member
     * bots get the cosmetic aura. Self-only buffs stay on the bot. Returns the
     * buff's WZ duration (ms) for recast cadence.
     */
    public static int castBuff(Character bot, int skillId) {
        return castBuff(bot, skillId, false);
    }

    /*
     * castBuff, saying whether the caller already paid this cast's MP (BotBuffDriver.maintain pays when it decides).
     * A party buff reaches a real player only at the bot's learned level and only when paid for; unpaid and
     * unaffordable, the real players get nothing and party-member bots still see the aura.
     */
    public static int castBuff(Character bot, int skillId, boolean paid) {
        showBuff(bot, skillId);

        Skill skill = skill(skillId);
        if (skill == null) return 0;
        StatEffect shown = skill.getEffect(skill.getMaxLevel());
        if (shown == null) return 0;

        if (isSharedBuff(skillId, shown)) {
            List<Character> members = nearbyPartyMembers(bot);
            StatEffect real = handOutEffect(bot, skillId);
            // 1.6.7 regular-buffs-real: the caster of a party buff holds it too, as a player's own cast does, and it is
            // one more target a real cast is paid for
            boolean self = real != null && receivesRealBuff(bot, bot);
            if (real != null && !paid && (self || anyRealReceiver(bot, members)) && !payFor(bot, skillId)) {
                real = null;   // no MP: party-member bots see it, real players and companions get nothing
            }
            if (real != null && self) {
                applyToSelf(bot, real);
            }
            applyToTargets(bot, real, skillId, members);
        }
        return shown.getDuration();
    }

    /*
     * 1.6.7 regular-buffs-real: whether this cast really reaches anyone - a real player, or a companion the policy
     * lets a party buff land on. Only a cast that reaches someone is worth its MP.
     */
    private static boolean anyRealReceiver(Character caster, Collection<Character> targets) {
        for (Character c : targets) {
            if (c != null && (receivesRealBuff(caster, c) || !BotHelpers.isBot(c))) return true;
        }
        return false;
    }

    /*
     * 1.6.7 regular-buffs-real: whether a party buff cast by {@code caster} is registered on {@code target}. A real
     * player always gets it; a bot only when it is a companion the policy allows (an owned alt by default, party
     * regulars with -Dslums.companion.buffs.regulars=true). Any other bot keeps the cosmetic aura.
     */
    static boolean receivesRealBuff(Character caster, Character target) {
        if (target == null) return false;
        if (!BotHelpers.isBot(target)) return true;
        return soloMapling.ArtificialPlayer.BotPartySystem.PartyCompanionPolicy.on(
                soloMapling.ArtificialPlayer.BotPartySystem.PartyCompanionPolicy.Feature.REAL_BUFFS, target);
    }

    /*
     * 1.6.7 regular-buffs-real: the buffs that are the CASTER's own, whatever box their WZ level carries. A party cast
     * must never register one of these on the caster, because a self buff's statup is the caster's alone: Combo (and
     * Advanced Combo), Shadow Partner, Infinity, Berserk, Enrage, the weapon charges (the White Knight six, the
     * Paladin holy ones, DawnWarrior's and ThunderBreaker's), Mana Reflection, Concentrate, Shadow Stars and Magic
     * Guard. isPartyBuff() alone is NOT this list - it answers "the WZ effect has an lt/rb box", which a self buff may
     * carry and a charge falls under - so the ids are named here instead of inferred.
     */
    static final java.util.Set<Integer> SELF_ONLY = java.util.Set.of(
            Crusader.COMBO, DawnWarrior.COMBO, Hero.ADVANCED_COMBO, DawnWarrior.ADVANCED_COMBO,
            Hermit.SHADOW_PARTNER, NightWalker.SHADOW_PARTNER,
            FPArchMage.INFINITY, ILArchMage.INFINITY, Bishop.INFINITY,
            DarkKnight.BERSERK, Hero.ENRAGE,
            FPArchMage.MANA_REFLECTION, ILArchMage.MANA_REFLECTION, Bishop.MANA_REFLECTION,
            Bowmaster.CONCENTRATE, NightLord.SHADOW_STARS,
            Magician.MAGIC_GUARD, Evan.MAGIC_GUARD, BlazeWizard.MAGIC_GUARD,
            WhiteKnight.SWORD_FIRE_CHARGE, WhiteKnight.BW_FIRE_CHARGE,
            WhiteKnight.SWORD_ICE_CHARGE, WhiteKnight.BW_ICE_CHARGE,
            WhiteKnight.SWORD_LIT_CHARGE, WhiteKnight.BW_LIT_CHARGE,
            Paladin.SWORD_HOLY_CHARGE, Paladin.BW_HOLY_CHARGE,
            DawnWarrior.SOUL_CHARGE, ThunderBreaker.LIGHTNING_CHARGE, Aran.COMBO_ABILITY);

    /* True only for a buff the caster shares: it has an area box AND is not one of the caster's own. */
    private static boolean isSharedBuff(int skillId, StatEffect shown) {
        return shown.isPartyBuff() && !SELF_ONLY.contains(skillId);
    }

    /* A party buff the caster registers on itself, as the primary branch of a player's cast does. */
    private static void applyToSelf(Character bot, StatEffect effect) {
        effect.applyToTarget(bot, bot);
        showReceivedBuff(bot, effect.getSourceId());
    }

    /*
     * A bot grants a buff to an explicit target list: shows the cast animation on
     * the bot, then applies the REAL buff to each (real players get the actual
     * working effect with no MP cost / no cast pose; bots get the cosmetic aura).
     * Used by the !bot givebuff command.
     */
    public static boolean givePartyBuff(Character bot, int skillId, Collection<Character> targets) {
        StatEffect effect = handOutEffect(bot, skillId);
        if (effect == null || !payFor(bot, skillId)) {
            return false;   // audit #1: not learned, or no MP for it
        }
        soloMapling.ArtificialPlayer.BotConvo.SayDo.event(bot, soloMapling.ArtificialPlayer.BotConvo.SayDo.Ev.BUFF, bot.getMapId()); // rc6 say-do
        showBuff(bot, skillId);
        if (targets != null && !targets.isEmpty()) {
            applyToTargets(bot, effect, skillId, targets);
        }
        return true;
    }

    /* The GM test (!bot givebuff): the bot hands out the max-level buff for free, whatever it has learned. */
    public static void givePartyBuffAsGm(Character bot, int skillId, Collection<Character> targets) {
        showBuff(bot, skillId);
        Skill skill = skill(skillId);
        StatEffect effect = skill == null ? null : skill.getEffect(skill.getMaxLevel());
        if (effect == null || targets == null || targets.isEmpty()) return;
        applyToTargets(bot, effect, skillId, targets);
    }

    // effect null: only the party-member bots' auras (nothing working to hand a player)
    private static void applyToTargets(Character bot, StatEffect effect, int skillId, Collection<Character> targets) {
        for (Character target : targets) {
            if (target == null || target == bot) continue;
            if (BotHelpers.isBot(target) && !receivesRealBuff(bot, target)) {
                showBuff(target, skillId);            // a bot the policy leaves out stays cosmetic
            } else if (effect != null) {
                effect.applyToTarget(bot, target);    // a real player or an allowed companion gets the working buff
                showReceivedBuff(target, skillId);    // + the "received a party buff" particle
            } else {
                showBuff(target, skillId);            // unpaid: nothing working to hand anyone
            }
        }
    }

    /*
     * Show the "received a party buff" particle on target. applyToTarget
     * uses the non-primary apply path, which skips the cast effect, so we fire the exact
     * two packets the engine sends for each affected party member (StatEffect.java:1172-1173):
     * showOwnBuffEffect to the target's own client (they see it on themselves) and a
     * showBuffEffect broadcast to the rest of the map (others see it on them). effectId
     * 2 = the party-receive variant. Works regardless of party membership - it's just packets.
     */
    /* The "received a party buff or heal" particle on a target, for BotPartySupport. */
    public static void showReceived(Character target, int skillId) {
        showReceivedBuff(target, skillId);
    }

    private static void showReceivedBuff(Character target, int skillId) {
        target.sendPacket(PacketCreator.showOwnBuffEffect(skillId, 2));
        target.getMap().broadcastMessage(target, PacketCreator.showBuffEffect(target.getId(), skillId, 2), false);
    }

    /*
     * Like .givePartyBuff but forces a custom duration (e.g. 10 min) on the
     * real buff given to player targets, instead of the WZ duration. Bots stay
     * cosmetic. Useful for extending key party buffs (Holy Symbol, Maple Warrior, ...).
     */
    public static boolean giveExtendedBuff(Character bot, int skillId, Collection<Character> targets, int durationMs) {
        StatEffect effect = handOutEffect(bot, skillId);
        if (effect == null || !payFor(bot, skillId)) {
            return false;   // audit #1: not learned, or no MP for it
        }
        extended(bot, skillId, effect, targets, durationMs);
        return true;
    }

    /* The GM test (!bot extbuff): the max-level buff for free, whatever the bot has learned. */
    public static void giveExtendedBuffAsGm(Character bot, int skillId, Collection<Character> targets, int durationMs) {
        Skill skill = skill(skillId);
        StatEffect effect = skill == null ? null : skill.getEffect(skill.getMaxLevel());
        if (effect != null) {
            extended(bot, skillId, effect, targets, durationMs);
        }
    }

    private static void extended(Character bot, int skillId, StatEffect effect, Collection<Character> targets,
                                 int durationMs) {
        soloMapling.ArtificialPlayer.BotConvo.SayDo.event(bot, soloMapling.ArtificialPlayer.BotConvo.SayDo.Ev.BUFF, bot.getMapId()); // rc6 say-do
        showBuff(bot, skillId);
        if (targets == null || targets.isEmpty()) return;

        for (Character target : targets) {
            if (target == null || target == bot) continue;
            if (BotHelpers.isBot(target)) {
                showBuff(target, skillId);                       // party-member bots stay cosmetic
            } else {
                applyBuffWithDuration(target, effect, skillId, durationMs);
            }
        }
    }

    /*
     * Apply effect to target with a custom duration - the lean core
     * of StatEffect.applyTo's buff path with our own length: the giveBuff
     * packet (client countdown), registerEffect with an explicit expiry
     * (server stat + expiry; confirmed via Character.registerEffect ->
     * addItemEffectHolder(expirationtime) + updateLocalStats), the foreign-buff aura,
     * and the receive particle. No MP cost, no cast pose on the target.
     */
    private static void applyBuffWithDuration(Character target, StatEffect effect, int skillId, int durationMs) {
        if (effect.isMonsterRiding()) {
            return; // a mount buff needs the target's own mount: nothing a bot can hand out
        }
        long start = Server.getInstance().getCurrentTime();
        target.sendPacket(effect.isPirateTwoStateBuff()
                ? PacketCreator.givePirateBuff(effect.getStatups(), effect.getSourceId(), durationMs / 1000)
                : PacketCreator.giveBuff(effect.getBuffSourceId(), durationMs, effect.getStatups()));
        target.registerEffect(effect, start, start + durationMs, false);
        net.packet.Packet aura = effect.foreignBuffPacket(target, durationMs);
        if (aura != null) {
            target.getMap().broadcastMessage(target, aura, false);
        }
        showReceivedBuff(target, skillId);
    }

    /*
     * Ticket 161: whether a party member in reach has lost (or never got) this buff - judged by the buff's first stat on
     * the member. 1.6.7 regular-buffs-real: a companion the policy lets the buff land on counts too, so a dispelled alt
     * gets its recast; any other bot partner's buff is still only a picture.
     */
    public static boolean partyMemberLacks(Character bot, int skillId) {
        Skill skill = skill(skillId);
        StatEffect effect = skill == null ? null : skill.getEffect(skill.getMaxLevel());
        // a self-only buff never lands on a companion, so it can never be lacking on one (and must not churn a recast)
        if (effect == null || effect.getStatups().isEmpty() || !isSharedBuff(skillId, effect)) return false;
        client.BuffStat stat = effect.getStatups().get(0).getLeft();
        for (Character member : nearbyPartyMembers(bot)) {
            if (receivesRealBuff(bot, member) && member.isAlive() && member.getBuffedValue(stat) == null) {
                return true;
            }
        }
        return false;
    }

    /* The bot's party members on the same map within party-buff range (excludes the bot). */
    private static List<Character> nearbyPartyMembers(Character bot) {
        Party party = bot.getParty();
        if (party == null || bot.getMap() == null) return Collections.emptyList();

        Point botPos = bot.getPosition();
        List<Character> result = new ArrayList<>();
        for (Character chr : bot.getMap().getCharacters()) {
            if (chr == bot) continue;
            Party other = chr.getParty();
            if (other == null || other.getId() != party.getId()) continue;

            Point p = chr.getPosition();
            if (botPos != null && p != null) {
                if (Math.abs(p.x - botPos.x) > PARTY_BUFF_RANGE_X) continue;
                if (Math.abs(p.y - botPos.y) > PARTY_BUFF_RANGE_Y) continue;
            }
            result.add(chr);
        }
        return result;
    }
}
