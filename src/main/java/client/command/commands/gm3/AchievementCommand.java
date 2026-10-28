package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.achievements.AchievementService;
import soloMapling.achievements.AchievementStore;
import soloMapling.achievements.Achievements;
import soloMapling.achievements.Goal;
import soloMapling.achievements.Subject;

import java.sql.SQLException;
import java.util.Locale;

/**
 * !achievement (1.6.1, ticket 93): {@code list}, {@code preview <char> <id>}, {@code grant <char> <id>},
 * {@code reset <char> <id>}. The character can be a player (online or not) or a bot. A grant counts as earned at
 * once and hands over the medal (an offline character gets it at the next login); it is logged, and never shown on
 * the public site. A reset forgets the goal; a medal already in the bag stays there.
 */
public class AchievementCommand extends Command {
    {
        setDescription("Achievements: !achievement list | preview|grant|reset <character> <goal id>.");
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AchievementCommand.class);

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        AchievementService svc = Achievements.service();
        if (svc == null) {
            gm.yellowMessage("Achievements are off (-Dslums.achievements=true) or achievements.yaml is invalid.");
            return;
        }
        String sub = params.length == 0 ? "" : params[0].toLowerCase(Locale.ROOT);
        if (sub.equals("list")) {
            StringBuilder sb = new StringBuilder();
            for (Goal g : svc.catalog().goals()) {
                if (sb.length() > 90) {
                    gm.yellowMessage(sb.toString());
                    sb.setLength(0);
                }
                sb.append(sb.length() == 0 ? "" : ", ").append(g.id());
            }
            gm.yellowMessage(sb.toString());
            return;
        }
        if (params.length < 3 || !(sub.equals("preview") || sub.equals("grant") || sub.equals("reset"))) {
            gm.yellowMessage("Syntax: !achievement list | preview|grant|reset <character> <goal id>");
            return;
        }
        Goal goal = svc.catalog().get(params[2]);
        if (goal == null) {
            gm.yellowMessage("No goal '" + params[2] + "'. !achievement list shows the ids.");
            return;
        }
        Subject who = find(c, params[1]);
        if (who == null) {
            gm.yellowMessage("No character '" + params[1] + "'.");
            return;
        }
        try {
            AchievementStore.Row row = svc.snapshot(who.id()).get(goal.id());
            String state = row == null ? "not started" : switch (row.state()) {
                case AchievementStore.EARNED -> "earned, medal waiting";
                case AchievementStore.GRANTED -> "earned, medal given";
                default -> goal.progressText(row.progress());
            };
            switch (sub) {
                case "preview" -> {
                    gm.yellowMessage(goal.id() + ": " + goal.title() + " - " + goal.text() + " Medal "
                            + goal.medal() + " (" + server.ItemInformationProvider.getInstance().getName(goal.medal()) + ").");
                    gm.yellowMessage(params[1] + ": " + state + ".");
                    gm.yellowMessage("Achievement: " + goal.title() + " - " + goal.text()); // what the player would see
                }
                case "grant" -> {
                    AchievementService.Grant g = svc.forceEarn(who, goal);
                    log.warn("{} granted achievement {} to {} (was: {}) -> {}", gm.getName(), goal.id(), params[1], state, g);
                    gm.yellowMessage("Granted " + goal.id() + " to " + params[1] + ": " + switch (g) {
                        case GRANTED -> "medal handed over.";
                        case NO_ROOM -> "earned; no room in their Equip inventory yet, it follows when there is.";
                        case WAITING -> "earned; they get the medal at their next login.";
                        case ALREADY -> "they already had it.";
                    });
                }
                default -> {
                    boolean had = svc.reset(who.id(), goal);
                    log.warn("{} reset achievement {} for {} (was: {})", gm.getName(), goal.id(), params[1], state);
                    gm.yellowMessage("Reset " + goal.id() + " for " + params[1] + (had ? " (was " + state + ")" : " (had nothing)")
                            + ". A medal already in their bag stays.");
                }
            }
        } catch (SQLException e) {
            gm.yellowMessage("The achievement store failed: " + e.getMessage());
        }
    }

    /** A player online here, a bot, or a character that is offline (by name, from the database). */
    static Subject find(Client c, String name) {
        Character online = c.getWorldServer().getPlayerStorage().getCharacterByName(name);
        if (online != null) {
            return Subject.of(online);
        }
        for (var bot : soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getAllBots().values()) {
            Character chr = bot.getChr();
            if (chr != null && chr.getName().equalsIgnoreCase(name)) {
                return Subject.of(chr);
            }
        }
        int id = Character.getIdByName(name);
        return id < 0 ? null : new Subject(id, id >= soloMapling.server.SoloMaplingConstants.GameConstants.BOT_BASE_ID, null);
    }
}
