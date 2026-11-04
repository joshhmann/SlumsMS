package soloMapling.mechanics;

import client.Character;
import net.server.channel.Channel;
import net.server.world.Party;
import server.expeditions.ExpeditionType;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotPartySystem.ExpeditionBots;
import soloMapling.ArtificialPlayer.BotProgressionSystem.RebirthBosses;

import java.util.ArrayList;
import java.util.List;

import static soloMapling.BotLogger.log;

/**
 * 1.6.4 (owner 2026-11-03): the one caller of {@link ExpeditionBots#startAsCaptain}. A GM asks for a boss run with a
 * bot captain ({@code !mechanics boss ZAKUM [bots]}); this spawns the crew on the row's recruit map, makes the first
 * bot the party leader, and opens the room through the same door a human recruiter uses.
 *
 * <p>Deliberately not autonomous: nothing here runs on a schedule or a tick. The command is the trigger, because a
 * bot that opens boss rooms by itself is bot farming, and the owner has not asked for that.
 */
public final class BossStaging {

    /** What staging did: the door's answer, the bots it spawned, and a sentence to show. */
    public record Staged(ExpeditionBots.Door door, List<Integer> spawned, String why) {
        public boolean started() {
            return door == ExpeditionBots.Door.STARTED;
        }
    }

    /** Spawns the crew on the recruit map (production) - a seam so a test can hand in its own bots. */
    interface Crew {
        List<Character> spawn(MapleMap recruit, int want, List<Integer> spawned, int nearNpc, int minLevel,
                int maxLevel);
    }

    static Crew crew = KpqStaging::spawnBots;

    private BossStaging() {
    }

    /**
     * Spawns {@code bots} bots (or the row's minimum when 0) on the type's recruit map, puts them in one party with the
     * first bot as leader, and asks {@link ExpeditionBots#startAsCaptain} to open the run. On anything but STARTED the
     * bots are taken out again, so a refused start leaves no loose party behind.
     */
    public static Staged start(Character gm, ExpeditionType type, int bots) {
        if (!Mechanics.bossesEnabled()) {
            return new Staged(ExpeditionBots.Door.SWITCH_OFF, List.of(),
                    "the boss rows are off (-D" + Mechanics.BOSS_FLAG + "=true to arm them)");
        }
        if (gm == null || gm.getClient() == null || gm.getClient().getChannelServer() == null) {
            return new Staged(ExpeditionBots.Door.REFUSED, List.of(), "you are not on a channel");
        }
        RebirthBosses.Boss boss = type == null ? null : RebirthBosses.Boss.byType(type);
        MechanicsModel.Content content = boss == null ? null : MechanicsRuns.contentFor(boss.script);
        if (content == null) {
            return new Staged(ExpeditionBots.Door.NO_SCRIPT, List.of(),
                    "no mechanics content row for " + (type == null ? "that type" : type.name())
                            + " (mechanics/content/*.yaml)");
        }
        Channel channel = gm.getClient().getChannelServer();
        MapleMap recruit = channel.getMapFactory().getMap(content.recruitMap());
        if (recruit == null) {
            return new Staged(ExpeditionBots.Door.REFUSED, List.of(), "map " + content.recruitMap() + " did not load");
        }

        int want = bots > 0 ? bots : Math.max(1, content.minPlayers());
        want = Math.min(want, content.maxPlayers());
        int botMin = Math.max(content.minLevel(), 30);
        List<Integer> spawned = new ArrayList<>();
        List<Character> chars = crew.spawn(recruit, want, spawned, content.entryNpc(), botMin, botMin + 5);
        if (chars.size() < Math.min(want, content.minPlayers())) {
            KpqStaging.cleanup(spawned);
            return new Staged(ExpeditionBots.Door.TOO_FEW, List.of(),
                    "only " + chars.size() + " of " + want + " bots spawned on " + content.recruitMap()
                            + " (channel " + channel.getId() + "), and " + content.name() + " needs "
                            + content.minPlayers());
        }

        Character captain = chars.get(0);
        if (!Party.createParty(captain, true) || captain.getParty() == null) {
            KpqStaging.cleanup(spawned);
            return new Staged(ExpeditionBots.Door.REFUSED, List.of(), "the party could not be made");
        }
        Party party = captain.getParty();
        for (Character c : chars) {
            if (c != captain && !Party.joinParty(c, party.getId(), true)) {
                KpqStaging.cleanup(spawned);
                return new Staged(ExpeditionBots.Door.REFUSED, List.of(), c.getName() + " could not join the party");
            }
        }

        ExpeditionBots.Door door = ExpeditionBots.startAsCaptain(captain, type);
        if (door != ExpeditionBots.Door.STARTED) {
            KpqStaging.cleanup(spawned);
            return new Staged(door, List.of(), sentence(door));
        }
        log("[Mechanics] boss run " + type.name() + ": bot captain " + captain.getName() + " opened the room with "
                + chars.size() + " bots (channel " + channel.getId() + ")");
        return new Staged(door, List.copyOf(spawned), sentence(door));
    }

    /** One sentence per door answer, so a refusal is never mistaken for a start. */
    public static String sentence(ExpeditionBots.Door door) {
        return switch (door) {
            case STARTED -> "the bot captain opened the room and its party went in";
            case NOT_A_CAPTAIN -> "the chosen captain is not a bot, so nothing was started";
            case SWITCH_OFF -> "bot captains are off (-D" + ExpeditionBots.CAPTAIN_SWITCH + "=true to allow them)";
            case NO_SCRIPT -> "this tree has no boss script for that type";
            case ROOM_TAKEN -> "the room is already held on this channel";
            case HUMAN_LEADS -> "a real player is in the party, so the door stays his (a bot never overrides a human)";
            case TOO_FEW -> "the party is smaller than the type's own minimum";
            case REFUSED -> "the expedition or its script refused the start";
        };
    }
}
