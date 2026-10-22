package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityEvent;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatStats;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage;
import soloMapling.ArtificialPlayer.BotChatActions;
import soloMapling.ArtificialPlayer.BotChatActions.Outcome;
import soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotTypes.FollowerBot;
import soloMapling.ArtificialPlayer.BotTypes.SocialBot;
import soloMapling.ArtificialPlayer.BotTypes.TownWandererBot;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.ArtificialPlayer.ScriptedReplies;
import soloMapling.qa.QaLog;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Ticket 59, owner decision 1: carries out what {@link ConvoGates} allowed, with the existing systems, and says what
 * happened as a {@link BotChatActions.Result} - the same shape ticket 37's actions use, so the prompt's outcome line,
 * the words-must-fit check and the scripted fallback work unchanged. The bot's behaviour follows the words: a yes to
 * "come to ant tunnel" starts the walk before the line is typed.
 *
 * <p>Two doors: {@link #onIntent} (the player asked; the common case) and {@link #onProposal} (the model tagged an
 * action when the player asked for nothing; only {@link ConvoAction#postHoc} actions, still gated).
 */
public final class ConvoActions {

    private static final Logger log = LoggerFactory.getLogger(ConvoActions.class);

    static final long WALK_GAP_MS = TimeUnit.MINUTES.toMillis(10);
    static final long HOLD_MS = TimeUnit.MINUTES.toMillis(10);

    /** What the server does for real; the game's own systems behind a seam for the tests. */
    public interface Effects {
        /** A grinder trains on {@code mapId} for a while: it leaves its spot and walks there (world-event steer). */
        void grindAt(BotSM bot, int mapId);

        /** A town bot walks to {@code mapId} (near the player when they are there). */
        void walkTo(Character bot, int mapId, Character player);

        /** Casts the bot's buff on the player; the buff's name or null. */
        String buff(Character bot, Character player, String line);

        /** The stock buddy request through ticket 49's gates; true when it was sent. */
        boolean buddy(int botCharId, int playerId);

        /** A feelings event (ticket 58), with its own cooldowns. */
        void affinity(Character bot, Character player, AffinityEvent event, int delta);
    }

    /** What the gates need, read from the game; behind a seam for the tests. */
    public interface FactSource {
        ConvoGates.Facts facts(BotSM bot, Character player, ConvoAction action, ConvoSense.Read read, String line);
    }

    private static volatile Effects effects = new GameEffects();
    private static volatile FactSource factSource = new GameFacts();
    private static final Map<Long, Long> walkedAt = new ConcurrentHashMap<>();

    private ConvoActions() {
    }

    public static void useForTest(Effects testEffects, FactSource testFacts) {
        effects = testEffects == null ? new GameEffects() : testEffects;
        factSource = testFacts == null ? new GameFacts() : testFacts;
        walkedAt.clear();
    }

    static long pair(int botId, int playerId) {
        return ((long) botId << 32) ^ (playerId & 0xffffffffL);
    }

    static boolean recentlyWalked(int botId, int playerId, long now) {
        Long at = walkedAt.get(pair(botId, playerId));
        return at != null && now - at < WALK_GAP_MS;
    }

    /** Whether this layer handles the intent (the others stay ticket 37's or the drama/guild code's). */
    public static boolean handles(ConvoIntent intent) {
        return switch (intent) {
            case COME, HELP_KILL, GUIDE, BUFF, BUDDY, APOLOGY, INSULT, FLIRT -> true;
            default -> false;
        };
    }

    /** The player asked for something this layer handles: decide, do, and say what happened. */
    public static BotChatActions.Result onIntent(BotSM bot, Character player, ConvoSense.Read read, String line) {
        if (bot == null || bot.getChr() == null || player == null || !ConvoSwitches.on("contract")
                || !handles(read.intent())) {
            return BotChatActions.Result.NONE;
        }
        ConvoAction action = switch (read.intent()) {
            case APOLOGY -> ConvoAction.MAKEUP;
            case BUFF -> line != null && line.toLowerCase(Locale.ROOT).matches(".*\\bheals?\\b.*") ? ConvoAction.HEAL
                    : ConvoAction.BUFF;
            default -> read.intent().action();
        };
        ConvoGates.Facts facts = factSource.facts(bot, player, action, read, line);
        ConvoGates.Decision d = ConvoGates.decide(action, facts);
        BotChatActions.Result result = carryOut(bot, player, action, d, facts, line, false);
        qa(bot.getChr(), player, "INTENT " + read.describe() + " -> " + action.tag() + ": " + d.verdict() + " ("
                + d.reason() + ") -> " + result.outcome());
        return result;
    }

    /** What a model's tag led to. {@code result} is NONE when the tag was ignored. */
    public record Proposal(ConvoAction action, ConvoGates.Decision decision, BotChatActions.Result result) {
        static final Proposal IGNORED = new Proposal(ConvoAction.NONE, new ConvoGates.Decision(ConvoGates.Verdict.NO,
                "ignored"), BotChatActions.Result.NONE);

        public boolean done() {
            return decision.yes();
        }
    }

    /**
     * The model tagged an action and the player had asked for nothing: only post-hoc actions, only to the player's own
     * map, all gates. Party goes through ticket 37's own party rules.
     */
    public static Proposal onProposal(BotSM bot, Character player, ConvoContract.Reply reply, ConvoSense.Read read,
                                      String line) {
        if (reply == null || reply.action() == ConvoAction.NONE || bot == null || bot.getChr() == null
                || player == null || !ConvoSwitches.on("contract")) {
            return Proposal.IGNORED;
        }
        ConvoAction action = reply.action();
        if (!action.postHoc || read.intent() != ConvoIntent.TALK || read.triage().flagged()) {
            ChatStats.action("tag-ignored:" + action.tag());
            qa(bot.getChr(), player, "TAG [do:" + action.tag() + "] ignored (" + (!action.postHoc ? "not post-hoc"
                    : read.triage().flagged() ? "flagged" : "player asked for " + read.intent().label()) + ")");
            return Proposal.IGNORED;
        }
        String why = tagProblem(action, reply.text(), read);
        if (why != null) {
            // Probed 2026-10-22: qwen9b tags freely ("grindin zombie mushies lol [do:buff]"). A tag only counts when
            // the words say it and the player's line called for it.
            ChatStats.action("tag-ignored:" + action.tag());
            qa(bot.getChr(), player, "TAG [do:" + action.tag() + "] ignored (" + why + ")");
            return Proposal.IGNORED;
        }
        if (action == ConvoAction.PARTY) {
            BotChatActions.Result party = BotChatActions.act(bot, player, ChatTriage.Intent.PARTY, line);
            boolean yes = party.outcome() == Outcome.PARTY_ACCEPTED;
            qa(bot.getChr(), player, "TAG [do:party] -> " + party.outcome());
            return new Proposal(action, yes ? new ConvoGates.Decision(ConvoGates.Verdict.YES, "party rules")
                    : new ConvoGates.Decision(ConvoGates.Verdict.NO, "party rules"), party);
        }
        // a tagged walk or hunt only ever goes to the player's own map
        ConvoSense.Read here = new ConvoSense.Read(read.triage(), read.intent(), read.intentSource(),
                read.intentConfidence(), read.tone(), read.toneSource(), ConvoEntities.Found.NONE);
        ConvoGates.Facts facts = factSource.facts(bot, player, action, here, line);
        ConvoGates.Decision d = ConvoGates.decide(action, facts);
        BotChatActions.Result result = carryOut(bot, player, action, d, facts, line, true);
        ChatStats.action("tag:" + action.tag() + ":" + d.verdict().name().toLowerCase(Locale.ROOT));
        qa(bot.getChr(), player, "TAG [do:" + action.tag() + "] -> " + d.verdict() + " (" + d.reason() + ") -> "
                + result.outcome());
        return new Proposal(action, d, result);
    }

    private static final java.util.regex.Pattern COMMITS_COME = java.util.regex.Pattern.compile(
            "\\b(omw|otw|on my way|coming|be there|heading (over|there)|brt|be right there)\\b");
    private static final java.util.regex.Pattern COMMITS_HELP = java.util.regex.Pattern.compile(
            "\\b(omw|otw|coming|on my way|i'?ll help|lemme help|let me help|lets kill|let'?s kill|on it|help (u|you)"
                    + "|i got (u|you)|ill come)\\b");
    private static final java.util.regex.Pattern COMMITS_BUFF = java.util.regex.Pattern.compile(
            "\\b(buff|buffs|buffed|buffing|haste|bless|hs|holy symbol|heal|healed|healing|hb|se|mw|there u go|there you go"
                    + "|here u go|here you go)\\b");
    private static final java.util.regex.Pattern COMMITS_BUDDY = java.util.regex.Pattern.compile(
            "\\b(add|added|adding|buddy|buddies|req|request)\\b");
    private static final java.util.regex.Pattern COMMITS_PARTY = java.util.regex.Pattern.compile(
            "\\b(inv|invite|party|pt|send it)\\b");
    private static final java.util.regex.Pattern COMMITS_SORRY = java.util.regex.Pattern.compile(
            "\\b(sorry|sry|my bad|mb|oops|didnt mean|didn'?t mean)\\b");

    /** Why a model's tag does not count, or null: the words must say it, and moods need the player's tone. */
    static String tagProblem(ConvoAction action, String text, ConvoSense.Read read) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return switch (action) {
            case COME -> COMMITS_COME.matcher(t).find() ? null : "words do not say it";
            case HELP -> COMMITS_HELP.matcher(t).find() ? null : "words do not say it";
            case BUFF, HEAL -> COMMITS_BUFF.matcher(t).find() ? null : "words do not say it";
            case BUDDY -> COMMITS_BUDDY.matcher(t).find() ? null : "words do not say it";
            case PARTY -> COMMITS_PARTY.matcher(t).find() ? null : "words do not say it";
            case APOLOGIZE -> COMMITS_SORRY.matcher(t).find() ? null : "words do not say it";
            case FLIRT -> read.tone() == ConvoSense.Tone.FLIRTY ? null : "the player was not flirting";
            case INSULT -> read.tone() == ConvoSense.Tone.RUDE ? null : "the player was not rude";
            case MAKEUP -> read.intent() == ConvoIntent.APOLOGY ? null : "the player did not say sorry";
            default -> null;
        };
    }

    private static BotChatActions.Result carryOut(BotSM bot, Character player, ConvoAction action,
                                                  ConvoGates.Decision d, ConvoGates.Facts f, String line,
                                                  boolean tagged) {
        Character chr = bot.getChr();
        boolean yes = d.yes();
        long now = System.currentTimeMillis();
        switch (action) {
            case COME -> {
                if (!yes) {
                    return tooFar(d) ? tooFar(Outcome.COME_NO) : result(Outcome.COME_NO,
                            "Say no, you are not coming over right now (" + d.reason() + ").", "come_no");
                }
                String where = mapWord(f.targetMap());
                if (!f.targetIsHere()) {
                    walkedAt.put(pair(chr.getId(), player.getId()), now);
                    if (f.kind() == ConvoGates.BotKind.GRINDER) {
                        effects.grindAt(bot, f.targetMap());
                    } else {
                        effects.walkTo(chr, f.targetMap(), player);
                    }
                }
                return result(Outcome.COME_YES, f.targetIsHere() ? "Say you are already here with them."
                        : "Say yes, you are on your way to " + (where == null ? "them" : where) + " now (omw).",
                        "come_yes");
            }
            case HELP -> {
                if (!yes) {
                    return tooFar(d) ? tooFar(Outcome.HELP_NO) : result(Outcome.HELP_NO,
                            "Say no, you cannot help them hunt right now (" + d.reason() + ").", "help_no");
                }
                if (!f.targetIsHere()) {
                    walkedAt.put(pair(chr.getId(), player.getId()), now);
                }
                effects.grindAt(bot, f.targetMap());
                return result(Outcome.HELP_YES, f.targetIsHere() ? "Say yes, you will help kill the mobs here with them."
                        : "Say yes, you are coming to help them hunt (omw).", "help_yes");
            }
            case GUIDE -> {
                if (!yes) {
                    return tooFar(d) ? tooFar(Outcome.GUIDE_NO) : result(Outcome.GUIDE_NO,
                            "Say you cannot show them the way right now (" + d.reason() + ").", "guide_no");
                }
                walkedAt.put(pair(chr.getId(), player.getId()), now);
                effects.walkTo(chr, f.targetMap(), null);
                String where = mapWord(f.targetMap());
                return new BotChatActions.Result(Outcome.GUIDE_YES, "Tell them to follow you, you are walking to "
                        + (where == null ? "there" : where) + " now.", ScriptedReplies.outcome("guide_yes"), null);
            }
            case BUFF, HEAL -> {
                String buff = yes ? effects.buff(chr, player, line) : null;
                if (buff == null) {
                    return result(Outcome.BUFF_NO, "Say you cannot buff them right now (" + (yes ? "it failed"
                            : d.reason()) + ").", "buff_no");
                }
                return result(Outcome.BUFF_YES, "You just gave them " + buff + ": say so, short.", "buff_yes");
            }
            case BUDDY -> {
                boolean sent = d.verdict() == ConvoGates.Verdict.DELEGATE && effects.buddy(chr.getId(), player.getId());
                return sent ? result(Outcome.BUDDY_YES, "You just sent them a buddy request: say so.", "buddy_yes")
                        : result(Outcome.BUDDY_NO, "Say not right now to being buddies, casually.", "buddy_no");
            }
            case MAKEUP -> {
                if (yes) {
                    effects.affinity(chr, player, AffinityEvent.APOLOGY, AffinityEvent.APOLOGY.delta());
                    return result(Outcome.MAKEUP_YES, "They said sorry and you accept it: the beef is over, say so"
                            + " (a bit grudging is fine).", "makeup_yes");
                }
                return result(Outcome.MOOD, "They said sorry; say it is fine, casually.", "apology_ack");
            }
            case APOLOGIZE -> {
                effects.affinity(chr, player, AffinityEvent.KIND_CHAT, AffinityEvent.KIND_CHAT.delta());
                return result(Outcome.MOOD, null, "apology_ack");
            }
            case INSULT -> {
                if (yes) {
                    return result(Outcome.MOOD, "They were rude: answer with short game-only trash talk (gear, level,"
                            + " job, deaths, KS), never real life, no slurs, no threats.", "insult_back");
                }
                // review 2026-10-22: a refused insult (no grudge, @botdrama off) falls back to a neutral brush-off
                return result(Outcome.MOOD_NO, "Shrug it off, short and plain, no insult.", "shrug");
            }
            case FLIRT -> {
                if (yes) {
                    if (!tagged) {
                        effects.affinity(chr, player, AffinityEvent.ROMANCE, 1);
                    }
                    return result(Outcome.MOOD, "Flirt back a little, cheesy and cute, PG-13.", "flirt_back");
                }
                return result(Outcome.MOOD_NO, "Do not flirt: keep it friendly and plain.", "flirt_no");
            }
            default -> {
                return BotChatActions.Result.NONE;
            }
        }
    }

    private static boolean tooFar(ConvoGates.Decision d) {
        return ConvoGates.TOO_FAR.equals(d.reason());
    }

    private static final List<String> TOO_FAR_LINES = List.of("too far lol", "thats way too far", "nah too far rn",
            "too far for me lol");

    /** A place the bot cannot walk to: it says so, and nothing moves. */
    private static BotChatActions.Result tooFar(Outcome outcome) {
        return new BotChatActions.Result(outcome, "Say it is too far to go there right now, casually.",
                TOO_FAR_LINES.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(TOO_FAR_LINES.size())), null);
    }

    private static BotChatActions.Result result(Outcome outcome, String fact, String list) {
        return new BotChatActions.Result(outcome, fact, ScriptedReplies.outcome(list), null);
    }

    /** "ant tunnel" for a map id, as players say it; null when unknown. */
    static String mapWord(int mapId) {
        if (mapId < 0) {
            return null;
        }
        for (Map.Entry<String, Integer> e : ConvoEntities.MAP_ALIASES.entrySet()) {
            if (e.getValue() == mapId && e.getKey().length() > 3) {
                return e.getKey();
            }
        }
        try {
            String name = server.maps.MapFactory.loadPlaceName(mapId);
            return name == null || name.isBlank() ? null : name.toLowerCase(Locale.ROOT);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    static void qa(Character bot, Character player, String message) {
        if (QaLog.on(QaLog.Topic.CONVO)) { // !qa cv-1, cv-3
            QaLog.map(QaLog.Topic.CONVO, player.getMapId(), bot.getName() + " <- player "
                    + ConvoQuality.hash(player.getId()) + ": " + ConvoQuality.anonymise(message, player.getName()));
        }
    }

    // ── the game ──

    static final class GameEffects implements Effects {
        @Override
        public void grindAt(BotSM bot, int mapId) {
            int id = bot.getChr().getId();
            // the walk a player asked for never warps (review 2026-10-22): its own trips give up instead
            soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.forbidWarps(bot.getChr(), HOLD_MS);
            soloMapling.worldevents.WorldEventSteer.hold(id, mapId);
            if (bot instanceof TrainingBot tb && bot.getChr().getMapId() != mapId) {
                tb.callToEvent(); // leaves its spot at the next tick and walks to the held map
            }
            soloMapling.server.BotTiming.after(HOLD_MS, () -> soloMapling.worldevents.WorldEventSteer.unhold(id));
        }

        @Override
        public void walkTo(Character bot, int mapId, Character player) {
            int x = 0;
            int y = 0;
            if (player != null && player.getMapId() == mapId && player.getPosition() != null) {
                x = player.getPosition().x + (java.util.concurrent.ThreadLocalRandom.current().nextBoolean() ? 60 : -60);
                y = player.getPosition().y;
            } else {
                try {
                    var map = bot.getClient().getChannelServer().getMapFactory().getMap(mapId);
                    var spawn = map.getPortal(0);
                    if (spawn != null) {
                        x = spawn.getPosition().x;
                        y = spawn.getPosition().y;
                    }
                } catch (RuntimeException e) {
                    log.debug("No spawn point for {} on {}", bot.getName(), mapId, e);
                }
            }
            soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.forbidWarps(bot, HOLD_MS);
            soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.travelTo(bot, mapId, x, y);
        }

        @Override
        public String buff(Character bot, Character player, String line) {
            return soloMapling.ArtificialPlayer.BotBuffRequestSystem.BotBuffRequestHandler.grantFromBot(bot, player, line);
        }

        @Override
        public boolean buddy(int botCharId, int playerId) {
            return soloMapling.ArtificialPlayer.BotBuddies.offerAsFriend(botCharId, playerId).endsWith("ok");
        }

        @Override
        public void affinity(Character bot, Character player, AffinityEvent event, int delta) {
            BotAffinity.record(bot.getId(), player.getId(), event, delta, player.getName(), bot.getName(),
                    player.getMapId());
        }
    }

    static final class GameFacts implements FactSource {
        @Override
        public ConvoGates.Facts facts(BotSM bot, Character player, ConvoAction action, ConvoSense.Read read,
                                      String line) {
            Character chr = bot.getChr();
            int slot = chr.getId() - BotStore.BOT_ID_BASE;
            ConvoEntities.Entity named = read.entities() == null ? null : read.entities().first(ConvoEntities.Kind.MAP);
            int target = named != null ? named.id()
                    : action == ConvoAction.COME || action == ConvoAction.HELP ? player.getMapId() : -1;
            if (action == ConvoAction.HELP) {
                target = player.getMapId(); // help is always where the player is
            }
            int mobLevel = target < 0 ? -1 : MapMobIndex.level(target);
            int level = chr.getLevel();
            boolean grindable = mobLevel >= 0 && mobLevel >= level - 25 && mobLevel <= level + 6;
            boolean town = target >= 0 && mobLevel < 0 && (ConvoEntities.MAP_ALIASES.containsValue(target)
                    || target == player.getMapId() && player.getMap() != null && player.getMap().isTown());
            return new ConvoGates.Facts(soloMapling.ArtificialPlayer.PlayerBusy.isBusy(player), read.triage().flagged(),
                    BotAffinity.hostile(chr.getId(), player.getId()), BotChatActions.wary(slot, player.getId()),
                    BotAffinity.optedOut(player.getId()),
                    soloMapling.ArtificialPlayer.BotDramaSystem.BotRomance.mayFlirtWith(chr.getId(), player.getId()),
                    kindOf(bot), freeForScene(chr, player), target, target == chr.getMapId() && target == player.getMapId(),
                    target == player.getMapId(), grindable, town,
                    action == ConvoAction.BUFF || action == ConvoAction.HEAL
                            ? soloMapling.ArtificialPlayer.BotBuffRequestSystem.BotBuffRequestHandler.buffProblem(chr, player, line)
                            : null,
                    recentlyWalked(chr.getId(), player.getId(), System.currentTimeMillis()),
                    read.intent() == ConvoIntent.APOLOGY, read.tone() == ConvoSense.Tone.RUDE,
                    target >= 0 && walkable(chr, target));
        }

        static boolean walkable(Character bot, int target) {
            try {
                return soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.walkable(bot, target);
            } catch (RuntimeException | LinkageError e) {
                return false; // no world graph: do not promise a walk
            }
        }

        static ConvoGates.BotKind kindOf(BotSM bot) {
            if (bot instanceof TrainingBot) {
                return ConvoGates.BotKind.GRINDER;
            }
            if (bot instanceof SocialBot) {
                return ConvoGates.BotKind.SOCIAL;
            }
            if (bot instanceof TownWandererBot) {
                return ConvoGates.BotKind.WANDERER;
            }
            if (bot instanceof FollowerBot) {
                return ConvoGates.BotKind.FOLLOWER;
            }
            return ConvoGates.BotKind.SERVICE;
        }

        static boolean freeForScene(Character bot, Character player) {
            if (bot.getEventInstance() != null || soloMapling.ArtificialPlayer.BotDramaSystem.BotDrama.inScene(bot.getId())) {
                return false;
            }
            net.server.world.Party party = bot.getParty();
            if (party == null) {
                return true;
            }
            for (net.server.world.PartyCharacter m : party.getMembers()) {
                if (m != null && m.getId() != player.getId() && !BotHelpers.isArtificial(m.getId())) {
                    return false; // it is out with another real player
                }
            }
            return true;
        }
    }
}
