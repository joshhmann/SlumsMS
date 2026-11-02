package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage;
import soloMapling.ArtificialPlayer.BotChatPrompt;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMemory;
import soloMapling.ArtificialPlayer.BotPersonality;
import soloMapling.ArtificialPlayer.BotRoster;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotSM;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * m02: reads what a reply to a player may be planned from - the bot's own state (level, job, place, monsters, what it
 * is doing), the player's state (level, job, whether it is a bot), the place (a town has no monsters), the bond
 * (stranger, known, friend, wary, dating and the one memory behind it) and the spots a player of their level trains
 * at. Nothing here decides or words anything: {@link ReplyPlanner} does that, and the planner never touches the game.
 */
public final class BotReplyView {

    private BotReplyView() {
    }

    /** The view of this reply, for the planner. Never null; unreadable pieces are left null and drop their fact. */
    public static ReplyPlanner.View of(BotSM bot, Character player, int slot, String activity, String asked,
                                       ChatTriage.Result triage, boolean botHasSpoken, boolean wary) {
        return of(bot, player, slot, activity, asked, triage, botHasSpoken, wary, null);
    }

    /**
     * As above, with what the action layer already did ({@code action}, m02 stage B: a party ask the gates answered):
     * its verdict and its own words for the answer go into the view, so the move cannot say the opposite.
     */
    public static ReplyPlanner.View of(BotSM bot, Character player, int slot, String activity, String asked,
                                       ChatTriage.Result triage, boolean botHasSpoken, boolean wary,
                                       soloMapling.ArtificialPlayer.BotChatActions.Result action) {
        Character chr = bot == null ? null : bot.getChr();
        if (chr == null || player == null) {
            return null;
        }
        List<String> mobs = RelationshipCard.mobs(chr);
        int mapId = chr.getMapId();
        boolean town = mobs.isEmpty() && StateOfMind.TOWNS.contains(mapId);
        boolean inPq = activity != null && activity.toLowerCase(Locale.ROOT).contains("pq");
        boolean busy = ReplyPlanner.busyActivity(activity);
        String romance = romance(chr, player);
        return new ReplyPlanner.View(chr.getName(), chr.getLevel(), BotChatPrompt.jobName(chr.getJob().getId()),
                RelationshipCard.mapName(chr), town, mobs, activity, inPq, busy,
                player.getName(), player.getLevel(), BotChatPrompt.jobName(player.getJob().getId()),
                BotHelpers.isBot(player),
                bond(slot, chr, player, wary, romance), romance, note(slot, player, wary),
                shared(slot, player, wary), lastLevel(slot, player),
                spots(player.getLevel(), player.getJob().getId()), mySpot(chr, town, activity),
                botHasSpoken, asked,
                triage == null ? null : triage.kind(), triage == null ? null : triage.intent(),
                voiceLine(slot, chr),
                action == null ? null : action.outcome(), action == null ? null : action.fact());
    }

    /** The player's level the last time these two talked, 0 when they never have (or memory is unreadable). */
    private static int lastLevel(int slot, Character player) {
        try {
            return BotMemory.progressLevel(slot, player.getId());
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }

    /** The bond the reply may show: dating beats wary beats friend beats known beats stranger. */
    private static ReplyPlanner.Bond bond(int slot, Character bot, Character player, boolean wary, String romance) {
        if (romance != null) {
            return ReplyPlanner.Bond.DATING; // @romance on and BotCouples knows: RelationshipCard.romance says it
        }
        if (wary) {
            return ReplyPlanner.Bond.WARY;
        }
        try {
            if (affinityFriend(slot, player)) {
                return ReplyPlanner.Bond.FRIEND;
            }
        } catch (RuntimeException | LinkageError e) {
            // affinity unreadable: fall through to what memory says
        }
        return knows(slot, player) ? ReplyPlanner.Bond.KNOWN : ReplyPlanner.Bond.STRANGER;
    }

    private static boolean affinityFriend(int slot, Character player) {
        return soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity.friend(BotStore.BOT_ID_BASE + slot,
                player.getId());
    }

    private static boolean knows(int slot, Character player) {
        try {
            return soloMapling.ArtificialPlayer.BotRecognition.knows(slot, player.getId());
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** The one memory the greeting may draw on: the bot's newest own note about them, unless it is wary of them. */
    private static String note(int slot, Character player, boolean wary) {
        if (wary) {
            return null;
        }
        try {
            List<BotMemory.Note> notes = new ArrayList<>(BotMemory.about(slot, player.getId()));
            notes.removeIf(n -> n.source() != BotMemory.Source.DIRECT);
            notes.sort(Comparator.comparingLong(BotMemory.Note::createdAt).reversed());
            for (BotMemory.Note n : notes) {
                String text = n.text() == null ? "" : n.text().trim();
                if (!text.isEmpty()) {
                    return text.length() <= 90 ? text : text.substring(0, 90) + "…";
                }
            }
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
        return null;
    }

    /**
     * The one thing these two did together that memory kept - "partied together in henesys", "saved me from a mob at
     * kerning" - so a greeting can come back to it instead of starting from nothing. Null for a wary bot (it is short
     * with them) and when memory has no episode; an episode that is the same note is not repeated.
     */
    private static String shared(int slot, Character player, boolean wary) {
        if (wary) {
            return null;
        }
        String note = note(slot, player, false);
        try {
            List<BotMemory.Note> episodes = BotMemory.bestEpisodes(slot, player.getId(),
                    System.currentTimeMillis(), 3);
            for (BotMemory.Note episode : episodes) {
                String text = episode.text() == null ? "" : episode.text().trim();
                if (text.isEmpty() || text.equals(note)) {
                    continue;
                }
                return text.length() <= 90 ? text : text.substring(0, 90) + "…";
            }
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
        return null;
    }

    private static String romance(Character bot, Character player) {
        try {
            return RelationshipCard.romance(bot, player);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Real training spots for the player's level, best first; empty when the leveling path is unreadable. */
    static List<String> spots(int level, int jobId) {
        try {
            List<String> out = new ArrayList<>(1);
            for (TrainingTips.Spot s : TrainingTips.spotsFor(level, jobId, 1)) {
                out.add(s.name());
            }
            return out;
        } catch (RuntimeException | LinkageError e) {
            return List.of();
        }
    }

    /**
     * Where this bot trains: the map it is on when the leveling path trains it there, else the best spot for its level.
     * Never a map off the path (the 1.6.3 cv-13 run had "i train at swampy land in a deep forest", map 105040000,
     * which the path never lists for any level): the bot's own list is {@link TrainingTips}, so a line it words from
     * this fact names a real spot for its band.
     */
    static String mySpot(Character bot, boolean town, String activity) {
        if (!town && "grinding".equals(activity)) {
            String here = onMyPath(bot);
            if (here != null) {
                return here;
            }
        }
        List<String> mine = spots(bot.getLevel(), bot.getJob().getId());
        return mine.isEmpty() ? null : mine.get(0);
    }

    /** What players call the map the bot stands on, when it is one the path trains a bot of its level and job on. */
    private static String onMyPath(Character bot) {
        try {
            return TrainingTips.spotName(bot.getMapId(), bot.getLevel(), bot.getJob().getId());
        } catch (RuntimeException | LinkageError e) {
            return null; // path unreadable: the caller falls back to the best spot for the level
        }
    }

    /**
     * The one voice a reply is typed in (ticket 59's persona line), or null when the persona switch is off. Shared
     * with {@code BotConversation.systemPrompt} so the two prompts cannot drift apart.
     */
    public static String voiceLine(int slot, Character bot) {
        if (!ConvoSwitches.on("persona")) {
            return null;
        }
        BotRoster.Member member = BotRoster.member(slot);
        BotPersonality.Traits traits = member == null ? null : member.traits();
        ConvoPersona voice = ConvoPersona.of(slot, traits == null ? -1 : traits.chattiness());
        return voice.prompt(BotChatPrompt.jobName(bot.getJob().getId()), bot.getGuildId() > 0)
                + ConvoTune.promptLines();
    }
}
