/*
    This file is part of the HeavenMS MapleStory Server, commands OdinMS-based
    Copyleft (L) 2016 - 2019 RonanLana

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

/*
   @Author: Arthur L - Refactored command content into modules
*/
package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import net.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapleMap;
import server.reports.BugClientLogs;
import server.reports.BugContext;
import server.reports.ReportStore;
import client.command.commands.gm2.BugsCommand;
import tools.PacketCreator;

/**
 * @bug / @reportbug and (via {@link Idea}) @idea: a durable report plus a GM heads-up. Ticket 131: a bug carries an
 * automatic context (versions, the character, last maps / NPC talks / events, surroundings, the server's WARN/ERROR
 * lines about the reporter; {@link BugContext}) and gets a ref like "B-12"; a client that can (stamp caps bit 1) is
 * then asked for its log tail ({@link BugClientLogs}). A GM typing {@code !bug B-12} (or {@code @bug B-12}) sees that
 * report's summary instead (see also {@code !bugs}).
 */
public class ReportBugCommand extends Command {
    {
        setDescription("Report a bug: @bug <what went wrong>. Your version, map and recent steps are attached.");
    }

    private static final Logger log = LoggerFactory.getLogger(ReportBugCommand.class);

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (player.isGM() && params.length == 1 && ReportStore.normalizeRef(params[0]) != null) {
            for (String line : BugsCommand.describe(ReportStore.getInstance(), params[0])) {
                player.yellowMessage(line);
            }
            return;
        }
        report(c, params, "bug", "Bug", "bug");
    }

    /** @idea shares the whole path with a different type and wording. */
    public static class Idea extends Command {
        {
            setDescription("Suggest a feature or change: @idea <your idea>.");
        }

        @Override
        public void execute(Client c, String[] params) {
            report(c, params, "feature", "Idea", "idea");
        }
    }

    static void report(Client c, String[] params, String type, String label, String noun) {
        report(c, params, type, label, noun, ReportStore.getInstance(),
                (world, packet) -> Server.getInstance().broadcastGMMessage(world, packet), BugContext.Names.GAME);
    }

    public static void report(Client c, String[] params, String type, String label, String noun, ReportStore store,
                              java.util.function.BiConsumer<Integer, net.packet.Packet> gmNotifier) {
        report(c, params, type, label, noun, store, gmNotifier, BugContext.Names.NONE);
    }

    public static void report(Client c, String[] params, String type, String label, String noun, ReportStore store,
                              java.util.function.BiConsumer<Integer, net.packet.Packet> gmNotifier, BugContext.Names names) {
        Character player = c.getPlayer();
        int max = "bug".equals(type) ? ReportStore.BUG_MAX_LENGTH : ReportStore.MAX_LENGTH;
        if (params.length < 1) {
            player.dropMessage(5, "Usage: @" + noun + " <" + ("bug".equals(type) ? "what went wrong" : "your idea")
                    + "> (up to " + max + " characters)."
                    + ("bug".equals(type) ? " Your version, map and recent steps are attached automatically." : ""));
            return;
        }
        String message = player.getLastCommandMessage();
        MapleMap map = player.getMap();
        String build = c.getClientBuild() > 0 ? c.getClientBuild() + (c.getClientRelease() == null || c.getClientRelease().isEmpty()
                ? "" : " (" + c.getClientRelease() + ")") : null;
        ReportStore.Context ctx = new ReportStore.Context(player.getName(), c.getAccID(), c.getWorld(), c.getChannel(),
                map.getId(), map.getMapName(), player.getPosition().x, player.getPosition().y, build,
                ReportStore.versions(constants.net.ServerConstants.SLUMSMS_VERSION, constants.net.BuildInfo.commit(),
                        c.getClientRelease(), c.getClientBuild()));
        long now = System.currentTimeMillis();
        java.util.function.Supplier<java.util.Map<String, Object>> context = "bug".equals(type)
                ? () -> BugContext.cap(BugContext.capture(c, player, now, names), BugContext.MAX_BYTES) : null;
        store.submit(type, message, ctx, context).thenAccept(r -> {
            switch (r.status()) {
                case TOO_SHORT -> player.dropMessage(5, "That is too short (at least " + ReportStore.MIN_LENGTH
                        + " characters). Please say a bit more: @" + noun + " <text>");
                case TOO_LONG -> player.dropMessage(5, "That is too long (at most " + max
                        + " characters). Please shorten it.");
                case RATE_LIMITED -> player.dropMessage(5, "You can send " + ReportStore.PER_ACCOUNT_WINDOW
                        + " reports every 10 minutes. Please wait a little and try again.");
                case WRITE_FAILED -> player.dropMessage(5, "Sorry, your " + noun + " could not be saved. Please try again in a moment.");
                case OK -> {
                    String name = Character.makeMapleReadable(player.getName());
                    gmNotifier.accept(c.getWorld(),
                            PacketCreator.sendYellowTip("[" + label + " #" + r.ref() + "]: " + name + ": " + message));
                    if (type.equals("bug")) {
                        gmNotifier.accept(c.getWorld(), PacketCreator.serverNotice(1, message));
                    }
                    log.info("{} {} filed by {} ({})", label, r.ref(), name, store.file()); // the text lives only in the report
                    player.dropMessage(5, "Thanks! " + label + " #" + r.ref() + " sent.");
                    if (type.equals("bug")) {
                        BugClientLogs.get().request(c, player, r.ref(), System.currentTimeMillis());
                    }
                }
            }
        });
    }
}
