package soloMapling.mechanics;

import soloMapling.ArtificialPlayer.BotRunOutcome;
import soloMapling.ArtificialPlayer.BotRunOutcome.Pending;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifePq;
import soloMapling.mechanics.MechanicsModel.Content;
import soloMapling.mechanics.RunState.Ending;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 1.7.0 run chatter (owner 2026-11-03: "outcomes once bots exit out they say things like woo we beat or dang we tried
 * but failed etc"). When a run ends, the bots that played it say something about how it went: a win line when it
 * cleared, a loss line when it was played and failed, nothing otherwise ({@link RunState#ending()}).
 *
 * <p>This only decides <i>who</i> says <i>what</i> and records it, once, on those bots ({@link BotRunOutcome}). It never
 * speaks: the run ends on the server tick, and chat has its own pacing, so each chosen bot says its line from its own
 * macro tick, inside the ambient chat budget, where a real player can see it. Behind {@code
 * -Dslums.mechanics.chatter} (default on); off records nothing.
 *
 * <p><b>Who speaks</b>: the leader (when it is a bot) and one other bot picked at random, never more than
 * {@value #MAX_SPEAKERS}. That is how the rest of the chat code paces a burst: {@code LevelUpGz} lets 0 to 2 bots say a
 * gz and {@code BotChatter} runs a pair, never a crowd; {@code BotChatTuning.allowAmbient} lets one line a map through
 * every few seconds (every 5 minutes in a boss room), so a party of eight saying a line each would be refused seven
 * times out of eight anyway. The leader is the natural voice of the party ("woo we beat Kerning PQ"); the second is a
 * different member with a different line. A human leader speaks for himself, so two random bots answer him.
 *
 * <p><b>Owned alts</b> never speak: they are not in the population registry and have no macro tick to say it from, and
 * their only voice is {@code OwnedAltCompanion.say}, party chat to their owner for acknowledging orders, never a public
 * line. An alt boasting in map chat would be the one thing that gives it away, and its owner is in the run to say it
 * himself.
 */
final class RunChatter {

    /** The most bots that speak about one run. */
    static final int MAX_SPEAKERS = 2;

    private RunChatter() {
    }

    /**
     * The content as players say it: "Kerning PQ" for the three quests the bots' own LFG calls that
     * ({@link LifePq}), otherwise the row's name without its qualifier, with "Party Quest" as "PQ" ("Zakum
     * (expedition)" -> "Zakum", "Henesys Party Quest" -> "Henesys PQ").
     */
    static String nameFor(Content content) {
        for (LifePq.Lobby lobby : LifePq.LOBBIES) {
            if (lobby.name().equalsIgnoreCase(content.id())) {
                return lobby.label();
            }
        }
        return MechanicsRuns.shortName(content.name()).replace("Party Quest", "PQ");
    }

    /** Boss rows and expeditions are talked about as a kill; everything else as a party quest. */
    static boolean boss(Content content) {
        return content.kind() == MechanicsModel.Kind.BOSS || content.kind() == MechanicsModel.Kind.EXPEDITION;
    }

    /**
     * Who speaks: {@code leaderId} first when it is one of the {@code bots}, then others chosen with {@code rng}, up to
     * {@code max}. The bots are taken in id order, so a seeded {@code rng} gives the same speakers every time.
     */
    static List<Integer> speakers(Collection<Integer> bots, int leaderId, Random rng, int max) {
        List<Integer> others = new ArrayList<>(bots);
        Collections.sort(others);
        List<Integer> out = new ArrayList<>(Math.min(max, others.size()));
        if (max > 0 && others.remove(Integer.valueOf(leaderId))) {
            out.add(leaderId);
        }
        Collections.shuffle(others, rng);
        for (Integer id : others) {
            if (out.size() >= max) {
                break;
            }
            out.add(id);
        }
        return out;
    }

    /**
     * What to record for a run that ended: each speaker's id and its line, in speaker order, or nothing when the
     * switch is off, the run ended silently, or no bot is left to say it.
     *
     * @param bots the run's bots that can speak (population bots still logged in, never owned alts)
     * @param now  the clock the lines' expiry counts from
     */
    static Map<Integer, Pending> plan(RunState run, Collection<Integer> bots, long now, Random rng) {
        Map<Integer, Pending> out = new LinkedHashMap<>();
        if (!Mechanics.chatterEnabled() || bots.isEmpty()) {
            return out;
        }
        Ending ending = run.ending();
        if (ending == Ending.SILENT) {
            return out;
        }
        int leaderId = run.members().stream().filter(RunMember::leader).mapToInt(RunMember::characterId).findFirst()
                .orElse(-1);
        List<Integer> who = speakers(bots, leaderId, rng, MAX_SPEAKERS);
        Content content = run.content();
        List<String> lines = BotRunOutcome.lines(BotRunOutcome.Pool.of(ending == Ending.WON, boss(content)),
                nameFor(content), who.size(), rng);
        for (int i = 0; i < lines.size(); i++) {
            out.put(who.get(i), new Pending(lines.get(i), now + BotRunOutcome.TTL_MS));
        }
        return out;
    }
}
