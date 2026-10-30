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

    public static java.util.List<String> inspectionLines(BotSM bot, long now) {
        var lines = new java.util.ArrayList<>(decisionLines(bot, now));
        // 1.6.0 S1: the Bartle blend and leading type, with the persona layers it joins.
        Character chr = bot.getChr();
        if (chr != null) {
            lines.addAll(soloMapling.ArtificialPlayer.BotLifeSystem.SocialSelf.of(chr.getId()).describe());
            // 1.6.0 S2: its top bonds with other bots, and its crew (where they are)
            lines.addAll(soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds.describe(chr.getId()));
            // 1.6.0 S9: what it learned (quadrant, crossings, today's budget), the fads it follows, its band's trends
            lines.addAll(soloMapling.ArtificialPlayer.BotLifeSystem.LifeLearning.describe(chr.getId(), chr.getLevel(), now));
            // ticket 157: the last gear pick and why (TEST only, DebugTools)
            String gear = soloMapling.ArtificialPlayer.BotGearSystem.BotGearWhy.describe(chr.getId(), now);
            if (gear != null) lines.add(gear);
        }
        return java.util.List.copyOf(lines);
    }

    private static java.util.List<String> decisionLines(BotSM bot, long now) {
        // 1.6.0 S8: any bot type that runs the brain (training, social, town), with its goals and plan steps.
        var runtime = soloMapling.ArtificialPlayer.BotLifeSystem.LifeRuntime.enabled() ? runtimeOf(bot) : null;
        if (runtime != null) {
            var latest = runtime.lastDecision();
            var lines = new java.util.ArrayList<String>();
            if (latest != null) {
                var saved = latest.report();
                // Scores/needs are exactly those evaluated then; request state and phase are read now.
                var report = new LifeObservation.Report(saved.observed(), saved.profile(), saved.context(),
                        saved.result(), bot instanceof soloMapling.ArtificialPlayer.BotLifeSystem.LifeHost host
                        ? host.lifeActivityState() : runtime.host() == null ? null : runtime.host().lifeActivityState());
                String phase = bot instanceof soloMapling.ArtificialPlayer.BotTypes.TrainingBot training
                        && training.lifePhase() != null ? training.lifePhase().name() : bot.getBotType();
                lines.add("Last actual decision " + Math.max(0, now - latest.at()) / 1000
                        + " s ago; phase now " + phase + ". Request state below is current.");
                lines.addAll(LifeObservation.describe(report, true));
            } else {
                lines.addAll(LifeObservation.describe(LifeObservation.capture(bot, now)));
            }
            var plan = runtime.describePlan();
            if (plan != null) lines.addAll(plan);
            return java.util.List.copyOf(lines);
        }
        return LifeObservation.describe(LifeObservation.capture(bot, now));
    }

    /** The brain this bot runs: a training bot's own, or a social/town bot's light host's (1.6.0 S8). */
    static soloMapling.ArtificialPlayer.BotLifeSystem.LifeRuntime runtimeOf(BotSM bot) {
        if (bot instanceof soloMapling.ArtificialPlayer.BotLifeSystem.LifeHost host) return host.lifeRuntime();
        if (bot instanceof soloMapling.ArtificialPlayer.BotTypes.SocialBot social && social.life() != null) {
            return social.life().lifeRuntime();
        }
        if (bot instanceof soloMapling.ArtificialPlayer.BotTypes.TownWandererBot wanderer && wanderer.life() != null) {
            return wanderer.life().lifeRuntime();
        }
        return null;
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
