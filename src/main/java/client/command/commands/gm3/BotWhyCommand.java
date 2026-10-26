package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeObservation;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.qa.QaSuite;

/** GM-only, opt-in QA diagnostic. It never invokes a bot action or chats on a bot's behalf. */
public class BotWhyCommand extends Command {
    private static final Logger log = LoggerFactory.getLogger(BotWhyCommand.class);

    {
        setDescription("Inspect a bot's latest daily-life decision without changing it: !botwhy <name>");
    }

    public static boolean isEnabled() {
        return soloMapling.ArtificialPlayer.BotLifeSystem.LifeRuntime.enabled()
                || QaSuite.isEnabled() && Boolean.getBoolean("slums.botlife.preview");
    }

    static java.util.List<String> inspectionLines(BotSM bot, long now) {
        var lines = new java.util.ArrayList<>(decisionLines(bot, now));
        // 1.6.0 S1: the Bartle blend and leading type, with the persona layers it joins.
        Character chr = bot.getChr();
        if (chr != null) {
            lines.addAll(soloMapling.ArtificialPlayer.BotLifeSystem.SocialSelf.of(chr.getId()).describe());
            // 1.6.0 S2: its top bonds with other bots, and its crew (where they are)
            lines.addAll(soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds.describe(chr.getId()));
        }
        return java.util.List.copyOf(lines);
    }

    private static java.util.List<String> decisionLines(BotSM bot, long now) {
        if (bot instanceof soloMapling.ArtificialPlayer.BotTypes.TrainingBot training
                && soloMapling.ArtificialPlayer.BotLifeSystem.LifeRuntime.enabled()) {
            var latest = training.lifeRuntime().lastDecision();
            if (latest != null) {
                var saved = latest.report();
                // Scores/needs are exactly those evaluated then; request state and phase are read now.
                var report = new LifeObservation.Report(saved.observed(), saved.profile(), saved.context(),
                        saved.result(), training.lifeActivityState());
                var lines = new java.util.ArrayList<String>();
                var phase = training.lifePhase();
                lines.add("Last actual decision " + Math.max(0, now - latest.at()) / 1000
                        + " s ago; phase now " + (phase == null ? "unknown" : phase.name())
                        + ". Request state below is current.");
                lines.addAll(LifeObservation.describe(report, true));
                return java.util.List.copyOf(lines);
            }
        }
        return LifeObservation.describe(LifeObservation.capture(bot, now));
    }

    @Override
    public void execute(Client client, String[] params) {
        Character gm = client.getPlayer();
        if (!isEnabled()) {
            gm.yellowMessage("Bot life preview needs QA and -Dslums.botlife.preview=true.");
            return;
        }
        if (params.length != 1) {
            gm.yellowMessage("Syntax: !botwhy <online bot name>");
            return;
        }
        Character target = client.getWorldServer().getPlayerStorage().getCharacterByName(params[0]);
        BotSM bot = target == null ? null : CharacterStorage.getBotById(target.getId());
        if (bot == null || bot.getChr() != target) {
            gm.yellowMessage("No active bot with that name in this world.");
            return;
        }
        for (String line : inspectionLines(bot, System.currentTimeMillis())) {
            gm.yellowMessage(line);
            log.info("[BotLifePreview] bot={} {}", target.getId(), line);
        }
    }
}
