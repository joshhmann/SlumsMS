package soloMapling.ArtificialPlayer.BotDramaSystem;

import soloMapling.ArtificialPlayer.BotStore;

import java.util.Locale;

/**
 * One player's romance with one bot (1.5.0 bot drama, {@code botromance}, migration 015). {@code botId} is the bot's
 * slot (character id minus {@link BotStore#BOT_ID_BASE}). Times are epoch ms: {@code startedAt} when they got together
 * (or engaged/married, the latest step), {@code togetherAt} the last time both stood on one map, {@code endedAt} the
 * breakup (EX only). {@code ringItemId} is the wedding ring kind of an engaged or married pair; {@code botRingId} the
 * bot's half of the stock {@code rings} pair once married.
 */
public record Romance(int charId, int botId, Stage stage, long startedAt, long togetherAt, long endedAt, int ringItemId,
                      int botRingId, boolean staged) {

    public enum Stage {
        DATING, ENGAGED, MARRIED, EX;

        /** Together now (not an ex). */
        public boolean together() {
            return this != EX;
        }

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Stage parse(String raw) {
            if (raw == null) {
                return null;
            }
            for (Stage s : values()) {
                if (s.name().equalsIgnoreCase(raw.trim())) {
                    return s;
                }
            }
            return null;
        }
    }

    public int botCharId() {
        return BotStore.BOT_ID_BASE + botId;
    }

    Romance withStage(Stage s, long at) {
        return new Romance(charId, botId, s, at, Math.max(togetherAt, at), s == Stage.EX ? at : 0, ringItemId, botRingId,
                staged);
    }

    Romance together(long at) {
        return new Romance(charId, botId, stage, startedAt, at, endedAt, ringItemId, botRingId, staged);
    }

    Romance withRing(int itemId, int botRing) {
        return new Romance(charId, botId, stage, startedAt, togetherAt, endedAt, itemId, botRing, staged);
    }

    Romance backdated(long started, long together, long ended) {
        return new Romance(charId, botId, stage, started, together, ended, ringItemId, botRingId, staged);
    }
}
