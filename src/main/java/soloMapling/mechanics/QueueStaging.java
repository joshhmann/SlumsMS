package soloMapling.mechanics;

import client.Character;
import net.server.channel.Channel;
import scripting.event.EventInstanceManager;
import scripting.event.EventManager;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds;
import soloMapling.mechanics.MechanicsModel.Content;
import soloMapling.mechanics.MechanicsModel.Seat;
import soloMapling.server.ExecutorServiceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Starts a run of a content that is entered through the event's <b>world queue</b> (a row with {@code queue:}), the way
 * its door NPC does it - {@link KpqStaging} is the same job for a content that a party starts. For Guild Quest
 * ({@code scripts/npc/9040000.js}):
 *
 * <ol>
 *   <li>the guild's master registers the guild ({@code :70-91}: {@code isQueueFull()}, {@code getQueueSize()},
 *   {@code addGuildToQueue(guildId, leaderId)}); with nobody ahead the world starts the instance at once with the master
 *   alone inside ({@code EventManager.java:959-976});</li>
 *   <li>every other member talks to the NPC and is registered into <em>the guild's</em> instance ({@code :54-65},
 *   {@code findLobby}: the lobby whose join window is still open);</li>
 *   <li>the run is armed on the instance with everyone in it.</li>
 * </ol>
 *
 * <p>A bot master and bot members go through the NPC's own script ({@link Door}: a bot party enters the way a player's
 * does); a GM master is let through with the same {@code EventManager} calls the script makes, the way {@link KpqStaging}
 * presses Lakelis' button for a GM. Nothing here makes a guild: the master and the members are the caller's, in their
 * guild already.
 *
 * <p><b>Composition.</b> A row that asks something of the party ({@link Content#composition()}: Guild Quest's one member
 * of level 30 or below, {@code under30gate.js:25}) is checked <em>before</em> the guild is registered - registering starts
 * an instance that has to be ended again - and a seat the members leave empty is filled by seating one more bot in the
 * guild at the seat's own level band ({@link Seater}). If none can be seated the staging refuses with the line that says
 * which seat; it never stands a high-level member in for the missing one and never changes a level.
 */
public final class QueueStaging {

    /** How the guild goes through the queue's door; null from either means it went through. */
    interface Door {
        /** Registers {@code master}'s guild with the queue; the reason it did not, or null. */
        String register(Character master);

        /** Registers {@code member} into the guild's instance; the reason it did not, or null. */
        String join(Character member);
    }

    /** Puts bots at a level band into a guild. */
    interface Seater {
        /**
         * Up to {@code count} bots of level {@code minLevel}..{@code maxLevel} now in guild {@code guildId} and standing on
         * {@code recruit}; every id made goes into {@code spawned} (for {@link #cleanup}).
         */
        List<Character> seat(MapleMap recruit, int count, int minLevel, int maxLevel, int guildId, List<Integer> spawned);

        /** Takes the bots {@link #seat} made out of the guild and the world. */
        void cleanup(List<Integer> spawned);
    }

    /** What staging did: the run (null when it could not start), the bots it seated, why it failed. */
    public record Staged(MechanicsRuns.Active active, List<Integer> seated, String error) {
        public boolean ok() {
            return error == null && active != null && !active.run().ended();
        }
    }

    private QueueStaging() {
    }

    /** The door a master goes through: a bot by the NPC's own script, anyone else by the script's own calls. */
    static Door doorFor(Character master, EventManager em, Content content) {
        return MechanicsRuns.drivable(master) ? new NpcDoor(content, em) : new DirectDoor(em);
    }

    /** The door as a bot walks through it: {@code scripts/npc/9040000.js} run for the bot, answers from the row. */
    static final class NpcDoor implements Door {
        private final Content content;
        private final EventManager em;

        NpcDoor(Content content, EventManager em) {
            this.content = content;
            this.em = em;
        }

        @Override
        public String register(Character master) {
            BotNpcTalk.Result r = BotNpcTalk.INSTANCE.converse(master, content.entryNpc(), content.queue().register());
            if (!r.ran()) {
                return "the door NPC's script failed: " + r.error();
            }
            return instanceOf(em, master, content) != null ? null : "the door NPC said: " + r.lastLine();
        }

        @Override
        public String join(Character member) {
            BotNpcTalk.Result r = BotNpcTalk.INSTANCE.converse(member, content.entryNpc(), content.queue().join());
            return r.ran() ? null : "the door NPC's script failed: " + r.error();
        }
    }

    /** The door as a GM walks through it: the calls {@code 9040000.js} makes, with its own checks. */
    static final class DirectDoor implements Door {
        private final EventManager em;
        private volatile EventInstanceManager lobby;

        DirectDoor(EventManager em) {
            this.em = em;
        }

        @Override
        public String register(Character master) {
            byte answer = em.addGuildToQueue(master.getGuildId(), master.getId());
            return switch (answer) {
                case 2 -> null;
                case 1 -> "the guild is queued behind another (the queue is not empty)";
                case 0 -> "the queue on this channel is full";
                default -> "the guild is already queued on a channel";
            };
        }

        @Override
        public String join(Character member) {
            EventInstanceManager eim = lobby;
            if (eim == null) {
                return "no lobby to join";
            }
            eim.registerPlayer(member);
            return null;
        }

        void lobby(EventInstanceManager eim) {
            this.lobby = eim;
        }
    }

    /**
     * Registers the guild of {@code master} through the queue, brings {@code members} in and starts the run. Blocks (call
     * it off the packet thread). {@code members} are the other guild members, already standing on the channel.
     */
    public static Staged start(Content content, Channel ch, Character master, List<Character> members, boolean perfect,
                               long budgetMs, Consumer<String> reporter, Consumer<MechanicsRuns.Active> afterCleanup) {
        if (content == null || content.queue() == null) {
            return new Staged(null, List.of(), "this content is not entered through a queue");
        }
        EventManager em = ch == null || ch.getEventSM() == null ? null
                : ch.getEventSM().getEventManager(content.eventScript());
        if (em == null) {
            return new Staged(null, List.of(), "no " + content.eventScript() + " event on this channel");
        }
        Door door = doorFor(master, em, content);
        return start(content, ch.getMapFactory().getMap(content.recruitMap()), em, master, members, perfect, budgetMs,
                reporter, afterCleanup, door, new LiveSeater());
    }

    static Staged start(Content content, MapleMap recruit, EventManager em, Character master, List<Character> members,
                        boolean perfect, long budgetMs, Consumer<String> reporter,
                        Consumer<MechanicsRuns.Active> afterCleanup, Door door, Seater seater) {
        if (!Mechanics.enabledFor(content)) {
            return new Staged(null, List.of(), content.id() + " is off (start the server with -D" + Mechanics.FLAG
                    + "=true and the row's own switch" + (content.flag() == null ? "" : " -D" + content.flag() + "=true") + ")");
        }
        String refused = refusal(content, master, members);
        if (refused != null) {
            return new Staged(null, List.of(), refused);
        }
        if (em.isQueueFull()) {
            return new Staged(null, List.of(), "the queue on this channel is full (" + em.getQueueSize() + " guild(s))");
        }
        if (em.getQueueSize() > 0) {
            return new Staged(null, List.of(), "another guild is ahead in the queue (" + em.getQueueSize()
                    + "): staging does not wait for a turn, so it would be a run that starts at some later time");
        }

        // who the party is, and what it still lacks, before anything is registered
        List<Character> party = new ArrayList<>();
        party.add(master);
        party.addAll(members);
        List<Integer> seated = new ArrayList<>();
        String unfilled = content.compositionProblem(levels(party));
        if (unfilled != null) {
            for (Seat seat : content.composition()) {
                long fitting = party.stream().filter(c -> seat.fits(c.getLevel())).count();
                int missing = (int) (seat.min() - fitting);
                if (missing <= 0) {
                    continue;
                }
                List<Character> seatedNow = seater.seat(recruit, missing, seat.minLevel(),
                        seat.maxLevel() > 0 ? seat.maxLevel() : seat.minLevel() + 5, master.getGuildId(), seated);
                party.addAll(seatedNow);
                members = concat(members, seatedNow);
            }
            unfilled = content.compositionProblem(levels(party));
            if (unfilled != null) {
                seater.cleanup(seated);
                return new Staged(null, List.of(), unfilled + "; no bot of that level could be seated in the guild");
            }
        }
        if (party.size() < content.minPlayers() || party.size() > content.maxPlayers()) {
            seater.cleanup(seated);
            return new Staged(null, List.of(), content.name() + " takes " + content.minPlayers() + "-"
                    + content.maxPlayers() + " members, not " + party.size());
        }

        // the guild goes through the door: held off from the watch, which would arm a second run on the same instance
        String why = MechanicsRuns.staging(() -> door.register(master));
        EventInstanceManager eim = instanceOf(em, master, content);
        if (why != null || eim == null) {
            seater.cleanup(seated);
            return new Staged(null, List.of(), why != null ? why : "the guild registered but the world started no instance"
                    + " for it (another guild may have been called first)");
        }
        if (door instanceof DirectDoor direct) {
            direct.lobby(eim);
        }
        List<String> outside = new ArrayList<>();
        for (Character member : members) {
            String no = MechanicsRuns.staging(() -> door.join(member));
            if (no != null || !inside(eim, member)) {
                outside.add(member.getName() + (no == null ? "" : " (" + no + ")"));
            }
        }
        if (!outside.isEmpty()) {
            MechanicsRuns.endInstance(eim, content.exitMap());
            seater.cleanup(seated);
            return new Staged(null, List.of(), "the guild's instance did not take " + String.join(", ", outside)
                    + " (a member must be in the guild and standing on the channel to be registered)");
        }
        reporter.accept("RUN guild " + master.getGuildId() + " entered " + content.id() + " through the queue: "
                + (1 + members.size()) + " member(s)" + (seated.isEmpty() ? "" : ", " + seated.size() + " seated for the row"));
        List<Integer> ours = List.copyOf(seated);
        MechanicsRuns.Active a = MechanicsRuns.get().start(eim, content, perfect, budgetMs, 1, 0, true, reporter, done -> {
            ExecutorServiceManager.getScheduledExecutorService().schedule(() -> {
                seater.cleanup(ours);
                if (afterCleanup != null) {
                    afterCleanup.accept(done);
                }
            }, KpqStaging.DESPAWN_AFTER_MS, TimeUnit.MILLISECONDS);
        });
        if (a.run().ended()) {
            seater.cleanup(seated);
            return new Staged(a, ours, a.run().endedBecause());
        }
        return new Staged(a, ours, null);
    }

    /** Why this guild cannot go through at all, or null: the script's own checks ({@code 9040000.js:70-91}). */
    static String refusal(Content content, Character master, List<Character> members) {
        if (master == null) {
            return "no guild master to register the guild";
        }
        if (master.getGuildId() <= 0) {
            return master.getName() + " is in no guild: the queue registers guilds";
        }
        if (!master.isGuildLeader()) {
            return master.getName() + " is not the guild's master or jr. master: only they can register it ("
                    + content.entryNpc() + " says so)";
        }
        List<String> strangers = new ArrayList<>();
        for (Character m : members) {
            if (m.getGuildId() != master.getGuildId()) {
                strangers.add(m.getName());
            }
        }
        if (!strangers.isEmpty()) {
            return "not in the master's guild: " + String.join(", ", strangers) + " (all members of the same guild)";
        }
        return null;
    }

    /** The instance the queue started for this master: the one he leads whose join window is still open. */
    static EventInstanceManager instanceOf(EventManager em, Character master, Content content) {
        for (EventInstanceManager eim : em.getInstances()) {
            if (eim.getLeaderId() == master.getId() && !eim.isEventDisposed()
                    && !content.queue().closed(eim.getProperty(content.queue().closedProperty()))) {
                return eim;
            }
        }
        return null;
    }

    private static boolean inside(EventInstanceManager eim, Character member) {
        for (Character c : eim.getPlayers()) {
            if (c != null && c.getId() == member.getId()) {
                return true;
            }
        }
        return false;
    }

    private static List<Integer> levels(List<Character> party) {
        return party.stream().map(Character::getLevel).toList();
    }

    private static List<Character> concat(List<Character> a, List<Character> b) {
        List<Character> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    /** The live seater: a fresh bot at the band, joined to the guild in memory, as {@code GuildStaging} stages one. */
    static final class LiveSeater implements Seater {
        @Override
        public List<Character> seat(MapleMap recruit, int count, int minLevel, int maxLevel, int guildId,
                                    List<Integer> spawned) {
            List<Integer> made = new ArrayList<>();
            List<Character> bots = KpqStaging.spawnBots(recruit, count, made, 0, minLevel, maxLevel);
            spawned.addAll(made);
            List<Character> in = new ArrayList<>();
            for (Character bot : bots) {
                if (BotGuilds.join(bot, guildId, 5, System.currentTimeMillis())) {
                    in.add(bot);
                }
            }
            return in;
        }

        @Override
        public void cleanup(List<Integer> spawned) {
            for (int id : spawned) {
                BotGuilds.leave(id, "staging over");
            }
            KpqStaging.cleanup(spawned);
        }
    }
}
