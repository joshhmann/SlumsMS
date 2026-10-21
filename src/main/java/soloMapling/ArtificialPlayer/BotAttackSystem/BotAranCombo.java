package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Character;
import client.Job;
import constants.skills.Aran;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
 * An Aran bot's combo counter (ticket 32), kept the way the server keeps a player's (AranComboHandler): every mob a
 * hit lands on adds one, and the count drops to zero when 6 s pass without a hit. The combo finishers spend it -
 * Combo Smash 10, Fenrir 30, Tempest 200, as RangedAttackHandler charges a player - so a bot only finishes on the
 * combo a player would have. The count is the bot's own: a player's combo buff is only shown to that player.
 * Ours (SoloMapling).
 */
public final class BotAranCombo {

    static final long DECAY_MS = 6_000;

    private record State(int combo, long lastHitMs) {}

    private static final Map<Integer, State> byBot = new ConcurrentHashMap<>();

    private BotAranCombo() {}

    /* Whether this job keeps a combo (Aran, holding Combo Ability as the server requires). */
    static boolean counts(Character bot) {
        Job job = bot.getJob();
        return job != null && job.isA(Job.ARAN1) && bot.getSkillLevel(Aran.COMBO_ABILITY) > 0;
    }

    /* The combo held now (0 after DECAY_MS without a hit). */
    public static int combo(Character bot, long now) {
        State s = byBot.get(bot.getId());
        return s == null || now - s.lastHitMs() > DECAY_MS ? 0 : s.combo();
    }

    /* Count a landed attack's hits (mobs hit; every shot of a burst counts). */
    static void afterStrike(Character bot, int skillId, int mobsHit) {
        if (mobsHit <= 0 || !counts(bot)) {
            return;
        }
        long now = System.currentTimeMillis();
        byBot.put(bot.getId(), new State(Math.min(30_000, combo(bot, now) + mobsHit), now));
    }

    /* Spend a finisher's cost (no-op for 0). */
    static void spend(Character bot, int cost) {
        if (cost <= 0) {
            return;
        }
        State s = byBot.get(bot.getId());
        if (s != null) { // spending is not a hit: the decay still runs from the last one
            byBot.put(bot.getId(), new State(Math.max(0, combo(bot, System.currentTimeMillis()) - cost), s.lastHitMs()));
        }
    }

    static void clearBot(int botId) {
        byBot.remove(botId);
    }
}
