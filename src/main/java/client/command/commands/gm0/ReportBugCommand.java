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
import server.reports.ReportStore;
import tools.PacketCreator;

/** @bug / @reportbug and (via {@link Idea}) @idea: durable report plus a GM heads-up. */
public class ReportBugCommand extends Command {
    {
        setDescription("Report a bug: @bug <what went wrong>.");
    }

    private static final Logger log = LoggerFactory.getLogger(ReportBugCommand.class);

    @Override
    public void execute(Client c, String[] params) {
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
                (world, packet) -> Server.getInstance().broadcastGMMessage(world, packet));
    }

    public static void report(Client c, String[] params, String type, String label, String noun, ReportStore store,
                              java.util.function.BiConsumer<Integer, net.packet.Packet> gmNotifier) {
        Character player = c.getPlayer();
        if (params.length < 1) {
            player.dropMessage(5, "Please say what it is: @" + noun + " <text>");
            return;
        }
        String message = player.getLastCommandMessage();
        MapleMap map = player.getMap();
        ReportStore.Context ctx = new ReportStore.Context(player.getName(), c.getAccID(), c.getWorld(), c.getChannel(),
                map.getId(), map.getMapName(), player.getPosition().x, player.getPosition().y, null);
        store.submit(type, message, ctx).thenAccept(r -> {
            switch (r.status()) {
                case TOO_SHORT -> player.dropMessage(5, "That is too short (at least " + ReportStore.MIN_LENGTH
                        + " characters). Please say a bit more: @" + noun + " <text>");
                case TOO_LONG -> player.dropMessage(5, "That is too long (at most " + ReportStore.MAX_LENGTH
                        + " characters). Please shorten it.");
                case RATE_LIMITED -> player.dropMessage(5, "You have sent a lot of reports lately. Please wait a while and try again.");
                case WRITE_FAILED -> player.dropMessage(5, "Sorry, your " + noun + " could not be saved. Please try again in a moment.");
                case OK -> {
                    String name = Character.makeMapleReadable(player.getName());
                    gmNotifier.accept(c.getWorld(),
                            PacketCreator.sendYellowTip("[" + label + " #" + r.id() + "]: " + name + ": " + message));
                    if (type.equals("bug")) {
                        gmNotifier.accept(c.getWorld(), PacketCreator.serverNotice(1, message));
                    }
                    log.info("{} #{} from {}: {}", label, r.id(), name, message);
                    player.dropMessage(5, "Thanks! Your " + noun + " was logged as #" + r.id() + ".");
                }
            }
        });
    }
}
