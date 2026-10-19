package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.BuffStat;
import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import constants.game.GameConstants;
import constants.skills.Crusader;
import constants.skills.Hero;
import server.StatEffect;
import tools.PacketCreator;
import tools.Pair;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/*
 * Combo Attack orbs for Crusader-line bots (Crusader 111, Hero 112 and up). Panic and Coma are finishers: a
 * viewer's client draws one with the "finish" effect for the attacker's orb count (Skill.wz 1111003/finish/1..10),
 * which it reads from the attacker's COMBO buff, never from the attack packet. A bot's buffs are only shown, never
 * registered, so it had no orbs and its Panic was drawn as a plain swing. This keeps a cosmetic orb count per bot
 * the way CloseRangeDamageHandler does for players: every other hit (Shout excepted) adds an orb, Advanced Combo
 * sometimes two, up to the skill's x; a finisher is used only on full orbs and empties them. Each change is shown
 * to the map as the foreign COMBO buff (value = orbs + 1), so the orbs circle the bot too. Ours (SoloMapling).
 *
 * Dawn Warriors have the same combo (11111001) but no configured bot attacks yet, so they never send a finisher.
 */
public final class BotComboOrbs {

    // Combo Attack's x at max level in v83 and MapleRoot's Skill.wz; the WZ value wins when it resolves.
    private static final int COMBO_ORBS_FALLBACK = 5;
    private static final int ADVANCED_COMBO_ORBS_FALLBACK = 10;
    private static final double ADVANCED_COMBO_DOUBLE_FALLBACK = 0.6;

    // botId -> orbs currently held (0 = the buff with no orbs yet)
    private static final Map<Integer, Integer> orbsByBot = new ConcurrentHashMap<>();

    private BotComboOrbs() {}

    /* True for the jobs whose finishers need orbs. */
    public static boolean usesCombo(Job job) {
        return job != null && job.isA(Job.CRUSADER);
    }

    /* The orbs a bot of this job holds when full: Combo Attack's x, Advanced Combo's from Hero on. */
    static int maxOrbs(Job job) {
        if (job.isA(Job.HERO)) {
            StatEffect adv = maxEffect(Hero.ADVANCED_COMBO);
            return adv != null && adv.getX() > 0 ? adv.getX() : ADVANCED_COMBO_ORBS_FALLBACK;
        }
        StatEffect combo = maxEffect(Crusader.COMBO);
        return combo != null && combo.getX() > 0 ? combo.getX() : COMBO_ORBS_FALLBACK;
    }

    public static int orbs(Character bot) {
        return orbsByBot.getOrDefault(bot.getId(), 0);
    }

    /* The COMBO buff value a viewer's client expects: one more than the orbs held. */
    public static int comboValue(Character bot) {
        return orbs(bot) + 1;
    }

    /*
     * True when the bot must charge instead of firing this skill: a finisher while its orbs aren't full. The
     * caller swaps in a charging attack, so a Crusader slashes its orbs up and then lands Panic/Coma on them.
     */
    public static boolean mustCharge(Character bot, int skillId) {
        return usesCombo(bot.getJob()) && GameConstants.isFinisherSkill(skillId)
                && orbs(bot) < maxOrbs(bot.getJob());
    }

    /*
     * Account for a landed attack, after its packet went out (the finisher is drawn with the orbs it used):
     * a finisher empties the orbs, any other hit but Shout adds one (Advanced Combo: a chance of two).
     */
    public static void afterStrike(Character bot, int skillId) {
        Job job = bot.getJob();
        if (!usesCombo(job)) {
            return;
        }
        int held = orbs(bot);
        int next = nextOrbs(held, skillId, maxOrbs(job), job.isA(Job.HERO) && advancedComboDoubles());
        if (next != held) {
            orbsByBot.put(bot.getId(), next);
            showOrbs(bot);
        }
    }

    /* The orb count after a hit with skillId, holding `held` of at most `max`. */
    static int nextOrbs(int held, int skillId, int max, boolean bonusOrb) {
        if (GameConstants.isFinisherSkill(skillId)) {
            return 0;
        }
        if (skillId == Crusader.SHOUT || held >= max) {
            return held;
        }
        return Math.min(max, held + (bonusOrb ? 2 : 1));
    }

    /* Show the bot's current orbs to its map as the foreign COMBO buff. */
    public static void showOrbs(Character bot) {
        if (bot.getMap() == null) {
            return;
        }
        bot.getMap().broadcastMessage(bot, PacketCreator.giveForeignBuff(bot.getId(),
                List.of(new Pair<>(BuffStat.COMBO, comboValue(bot)))), false);
    }

    public static void clearBot(int botId) {
        orbsByBot.remove(botId);
    }

    private static boolean advancedComboDoubles() {
        StatEffect adv = maxEffect(Hero.ADVANCED_COMBO);
        return adv != null ? adv.makeChanceResult()
                : ThreadLocalRandom.current().nextDouble() < ADVANCED_COMBO_DOUBLE_FALLBACK;
    }

    private static StatEffect maxEffect(int skillId) {
        Skill skill = SkillFactory.getSkill(skillId);
        return skill != null ? skill.getEffect(skill.getMaxLevel()) : null;
    }
}
