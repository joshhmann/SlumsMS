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
package client.command.commands.gm6;

import client.Character;
import client.Character.SaveResult;
import client.Client;
import client.command.Command;
import net.server.Server;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.PacketCreator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class SaveAllCommand extends Command {
    private static final Logger log = LoggerFactory.getLogger(SaveAllCommand.class);
    private static final int NAMES_SHOWN = 20;

    {
        setDescription("Save all characters.");
    }

    /** What a sweep did (P0-5). A skipped character (roster bot, not logged in) is neither saved nor failed. */
    public record Report(int saved, int skipped, List<String> failed) {
        public String message() {
            String text = saved + " saved, " + failed.size() + " failed";
            if (!failed.isEmpty()) {
                List<String> shown = failed.subList(0, Math.min(NAMES_SHOWN, failed.size()));
                text += " (" + String.join(", ", shown)
                        + (failed.size() > NAMES_SHOWN ? " and " + (failed.size() - NAMES_SHOWN) + " more" : "") + ")";
            }
            return text + (skipped > 0 ? ", " + skipped + " skipped." : ".");
        }
    }

    /** Saves each character now (not queued) and counts what really happened. */
    public static Report saveAll(Collection<Character> characters) {
        int saved = 0;
        int skipped = 0;
        List<String> failed = new ArrayList<>();
        for (Character chr : characters) {
            SaveResult result;
            try {
                result = chr.saveCharToDBResult(true, null);
            } catch (RuntimeException e) {
                log.error("!saveall: save of {} threw", chr.getName(), e);
                result = SaveResult.FAILED;
            }
            switch (result) {
                case SAVED -> saved++;
                case FAILED -> failed.add(chr.getName());
                default -> skipped++;
            }
        }
        return new Report(saved, skipped, failed);
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        List<Character> everyone = new ArrayList<>();
        for (World world : Server.getInstance().getWorlds()) {
            everyone.addAll(world.getPlayerStorage().getAllCharacters());
        }
        Report report = saveAll(everyone);
        log.info("!saveall by {}: {} saved, {} failed, {} skipped{}", player.getName(), report.saved(),
                report.failed().size(), report.skipped(), report.failed().isEmpty() ? "" : " " + report.failed());
        String message = player.getName() + " used !saveall.";
        Server.getInstance().broadcastGMMessage(c.getWorld(), PacketCreator.serverNotice(5, message));
        player.message("Save all: " + report.message());
    }
}
