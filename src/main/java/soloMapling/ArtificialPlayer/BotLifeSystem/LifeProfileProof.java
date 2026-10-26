package soloMapling.ArtificialPlayer.BotLifeSystem;

import client.Character;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotRoster;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.server.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code !qa bl-4} (1.6.0 S1): proves on a running server that a bot's learned life profile survives a non-training
 * session and a save/load round trip through the database. It nudges one real roster bot once (a normal life event,
 * the same 0.5% a wedding or a big drop gives, within the daily cap), records a SOCIAL session for it through the
 * roster's own sweep, reads the row back from {@code botlifeprofile} and compares it with the memory in use.
 */
public final class LifeProfileProof {

    private LifeProfileProof() {
    }

    /** The session a SocialBot writes: its type name, no home, an empty state (the case that used to wipe). */
    static final BotRoster.Activity SOCIAL_SESSION = new BotRoster.Activity("social", 0, Map.of());

    /** Log lines ending in {@code LIFE PROFILE PASS ...}, or an {@link IllegalStateException} saying what failed. */
    public static List<String> run(Character chr, long now) {
        int slot = chr.getId() - BotStore.BOT_ID_BASE;
        if (chr.getId() < BotStore.BOT_ID_BASE || BotRoster.member(slot) == null) {
            throw new IllegalStateException(chr.getName() + " is not a roster bot");
        }
        if (!LifeMemoryStore.available()) {
            throw new IllegalStateException("botlifeprofile is missing: apply migration 028 first");
        }
        List<String> lines = new ArrayList<>();
        LifeMemory memory = LifeMemoryStore.of(chr.getId());
        lines.add("LIFE PROFILE bot=" + chr.getName() + " slot=" + slot + " " + memory.social().describe()
                + " restored=" + memory.restored());
        LifeMemory.Event nudged = null;
        for (LifeMemory.Event event : LifeMemory.Event.values()) {
            if (memory.event(event, now)) {
                nudged = event;
                break;
            }
        }
        if (nudged == null && memory.delta().isEmpty()) {
            throw new IllegalStateException(chr.getName() + " has no drift and its daily nudges are used up; pick another bot");
        }
        lines.add("LIFE PROFILE NUDGE event=" + (nudged == null ? "none (daily cap; existing drift)" : nudged.name())
                + " delta=" + format(memory.delta()));

        BotSM brain = CharacterStorage.getBotById(chr.getId());
        boolean realSocial = brain != null && !(brain instanceof TrainingBot);
        int written = realSocial ? BotRoster.recordSessions(List.of(chr), now, false)
                : BotRoster.recordSessionAs(chr, SOCIAL_SESSION, now);
        BotRoster.Member member = BotRoster.member(slot);
        String recorded = member == null ? "?" : member.activity();
        boolean lifeInRow = member != null && member.activityState() != null
                && member.activityState().contains("\"" + LifeMemoryStore.STATE_KEY + "\"");
        lines.add("LIFE PROFILE SESSION recorded=" + recorded + " via=" + (realSocial
                ? brain.getClass().getSimpleName() + " snapshot" : "a SOCIAL snapshot (bot is a TrainingBot)")
                + " rows=" + written + " rosterCarriesLife=" + lifeInRow);
        if (!realSocial) {
            // Put the bot's real session back so its next login resumes what it is actually doing.
            BotRoster.recordSessions(List.of(chr), now, false);
        }
        if (written != 1 || !"social".equals(recorded) && !realSocial) {
            throw new IllegalStateException("the session was not recorded (rows=" + written + ")");
        }
        if (lifeInRow) {
            throw new IllegalStateException("the roster row still carries the life profile although the table answered");
        }

        String row;
        try {
            row = LifeMemoryStore.readRow(slot);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("could not read botlifeprofile: " + e.getMessage());
        }
        if (row == null) {
            throw new IllegalStateException("no botlifeprofile row was written for slot " + slot);
        }
        LifeMemory reloaded = new LifeMemory(chr.getId(), Json.parse(row));
        boolean delta = reloaded.delta().equals(memory.delta());
        boolean profile = reloaded.profile().equals(memory.profile());
        boolean social = reloaded.social().equals(memory.social()) && reloaded.socialSeed().equals(memory.socialSeed());
        lines.add("LIFE PROFILE RELOAD version=" + LifeMemory.VERSION + " deltaEqual=" + delta + " profileEqual="
                + profile + " bartleEqual=" + social + " delta=" + format(reloaded.delta()));
        if (!delta || !profile || !social || !reloaded.restored()) {
            throw new IllegalStateException("the reloaded profile differs from the one in use");
        }
        lines.add("LIFE PROFILE PASS bot=" + chr.getId() + " drift survived a social session and a reload");
        return lines;
    }

    private static String format(Map<LifeProfile.Archetype, Double> delta) {
        StringBuilder out = new StringBuilder("{");
        for (LifeProfile.Archetype type : LifeProfile.Archetype.values()) {
            Double d = delta.get(type);
            if (d == null) continue;
            if (out.length() > 1) out.append(' ');
            out.append(type.name().toLowerCase(Locale.ROOT)).append(String.format(Locale.ROOT, "%+.4f", d));
        }
        return out.append('}').toString();
    }
}
