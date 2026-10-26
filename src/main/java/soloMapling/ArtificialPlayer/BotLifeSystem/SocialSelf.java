package soloMapling.ArtificialPlayer.BotLifeSystem;

import soloMapling.ArtificialPlayer.BotConvo.ConvoPersona;
import soloMapling.ArtificialPlayer.BotPersona;
import soloMapling.ArtificialPlayer.BotPersonality;
import soloMapling.ArtificialPlayer.BotRoster;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 1.6.0 S1: one read model of who a bot is socially. It joins the four personality layers that grew up separately,
 * without changing any of them:
 * <ul>
 *   <li>{@link SocialProfile}: the Bartle blend and leading type (seed plus learned delta, from {@link LifeMemory});</li>
 *   <li>{@link LifeProfile}: the seven daily-life bias channels the brain scores with;</li>
 *   <li>{@link BotPersonality.Traits}: session mix, chattiness and play window (roster row; null before first login);</li>
 *   <li>{@link BotPersona.Persona}: vibe and interests for the chat prompt (roster row; null before first login);</li>
 *   <li>{@link ConvoPersona}: voice, typos, job pride, guild loyalty, catchphrases (derived from the slot).</li>
 * </ul>
 * Later slices (memory, gate, initiative, GOAP, learning) ask here instead of reaching into each class.
 *
 * <p>Cost: map lookups plus one small {@code ConvoPersona} draw. The first call for a roster member may read the
 * life-profile table once for the whole roster ({@link LifeMemoryStore}); never call it per bot per tick in a loop
 * over every bot.
 */
public record SocialSelf(int characterId, int slot, SocialProfile social, SocialProfile socialSeed,
                         LifeProfile life, LifeProfile lifeSeed, BotPersonality.Traits traits,
                         BotPersona.Persona persona, ConvoPersona voice) {

    /** Who this character is. Works for any character id; non-roster ids get their seeded profile. */
    public static SocialSelf of(int characterId) {
        int slot = SocialProfile.slotOf(characterId);
        LifeMemory memory = LifeMemoryStore.of(characterId);
        BotRoster.Member member = characterId >= soloMapling.ArtificialPlayer.BotStore.BOT_ID_BASE
                ? BotRoster.loadedMember(slot) : null;
        BotPersonality.Traits traits = member == null ? null : member.traits();
        BotPersona.Persona persona = member == null ? null : member.personaOrNull();
        ConvoPersona voice = ConvoPersona.of(slot, traits == null ? -1 : traits.chattiness());
        return new SocialSelf(characterId, slot, memory.social(), memory.socialSeed(), memory.profile(),
                memory.seed(), traits, persona, voice);
    }

    public static SocialSelf of(client.Character chr) {
        return of(chr.getId());
    }

    /** The leading Bartle type (of the effective blend). */
    public SocialProfile.Type leading() {
        return social.leading();
    }

    /** Whether learning has moved this bot's leading type away from its seed (S9 watches this; it should be rare). */
    public boolean leadingFlipped() {
        return social.leading() != socialSeed.leading();
    }

    /** The Killer disposition 0..1: rivalry, KS spats, trash talk (hook for S7/S8; nothing acts on it in S1). */
    public double rivalPull() {
        return social.rivalPull();
    }

    /** The Socializer pull 0..1: hanging out, crews, initiative. */
    public double socialPull() {
        return social.socialPull();
    }

    /** How much this bot talks, 0..1: the stored trait when it has one, else the voice's seeded value. */
    public double chattiness() {
        return traits != null ? traits.chattiness() : voice.chattiness();
    }

    /** A seven-channel bias (the brain's personality term input) of the effective profile. */
    public double channel(LifeProfile.Archetype archetype) {
        return life.blend().get(archetype);
    }

    /** The lines {@code !botwhy} shows: Bartle blend and leading type, seed vs learned, and the joined layers. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        boolean learned = !social.equals(socialSeed) || !life.equals(lifeSeed);
        lines.add("Bartle " + social.describe() + String.format(Locale.ROOT, "; rival pull %.2f, social pull %.2f",
                rivalPull(), socialPull()) + (learned ? "; seed " + socialSeed.describe() : "; seed (nothing learned)"));
        lines.add("Life channels " + percent(life.blend()) + (learned ? "; seed " + percent(lifeSeed.blend()) : ""));
        lines.add("Persona " + (persona == null ? "none yet" : persona.vibe() + " / " + persona.interests())
                + "; voice " + voice.describe()
                + (traits == null ? "" : String.format(Locale.ROOT, "; sessions grind %.2f town %.2f social %.2f",
                traits.grind(), traits.town(), traits.social())));
        return List.copyOf(lines);
    }

    private static String percent(Map<LifeProfile.Archetype, Double> blend) {
        StringBuilder out = new StringBuilder();
        for (LifeProfile.Archetype type : LifeProfile.Archetype.values()) {
            if (!out.isEmpty()) out.append(' ');
            out.append(type.name().toLowerCase(Locale.ROOT)).append(' ')
                    .append(String.format(Locale.ROOT, "%.0f%%", 100 * blend.get(type)));
        }
        return out.toString();
    }
}
