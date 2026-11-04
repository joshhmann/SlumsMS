package soloMapling.ArtificialPlayer.BotAffinitySystem;

import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemory;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemoryStore;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind;

import java.util.function.IntFunction;

/**
 * Ticket 181 stage 2: the parts of a bot's character that scale how an event lands on a relationship axis: the Bartle
 * shares the legacy score already uses ({@link BondRules#scale}) and, when the bot's {@link SocialMind} is in memory
 * ({@code LifeMemoryStore.peek(id).mind(..)}, never a database read), four traits. A trait of 0.5 (and shares of 0.25)
 * scales by exactly 1, so a bot with no mind in memory behaves as a neutral one.
 */
public record Persona(double social, double killer, double agreeableness, double jealousy, double empathy,
                      double loyalty) {

    public static final Persona NEUTRAL = new Persona(0.25, 0.25, 0.5, 0.5, 0.5, 0.5);

    private static volatile IntFunction<Persona> override;

    /** This bot: Bartle shares from its life memory or seed, traits from its mind when loaded. */
    public static Persona of(int botId) {
        IntFunction<Persona> fake = override;
        return fake != null ? fake.apply(botId) : of(botId, BotBonds.seededShares(botId));
    }

    /** As {@link #of(int)} with Bartle shares given (the bond tests' disposition seam, or shares already read). */
    public static Persona of(int botId, double[] shares) {
        IntFunction<Persona> fake = override;
        if (fake != null) {
            Persona p = fake.apply(botId);
            return new Persona(shares[0], shares[1], p.agreeableness, p.jealousy, p.empathy, p.loyalty);
        }
        LifeMemory memory = LifeMemoryStore.peek(botId);
        if (memory == null) {
            return new Persona(shares[0], shares[1], 0.5, 0.5, 0.5, 0.5);
        }
        return memory.mind(m -> new Persona(shares[0], shares[1], m.trait(SocialMind.Trait.AGREEABLENESS),
                m.trait(SocialMind.Trait.JEALOUSY), m.trait(SocialMind.Trait.EMPATHY),
                m.trait(SocialMind.Trait.LOYALTY)));
    }

    /** Tests: who a bot is (null restores the real lookup). */
    public static void useForTest(IntFunction<Persona> test) {
        override = test;
    }
}
