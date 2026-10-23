package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.Job;
import client.command.Command;
import client.command.commands.gm2.ClassTestCommand;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile;
import soloMapling.ArtificialPlayer.BotDecoratorSystem.BotWeapons;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotSupplySystem.StarterStash;
import soloMapling.ArtificialPlayer.BotTypes.ShowcaseBot;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

/*
 * Test server only (registered when attack capture is on, -Dslums.attackcapture=true; ticket 32):
 *   !botshowcase [4th | pirates | pacing | <job name or id> ...] [minutes]
 * spawns one bot per chosen job next to the GM - level 200 for a 4th job, every skill of its line at master level,
 * a weapon of its own kind, its ammo - that cycles every attack its job has (ShowcaseBot) at the nearest mobs for the
 * given minutes (default 5, at most 30) and then leaves. Each bot's report line lists its attacks with the cadence a
 * player gets for them (1.5.0 attack pacing): "pacing" spawns the set the owner's spot check watches - Hurricane and
 * Rapid Fire held streams, Inferno Aura 2121054 and Flame Barrier 12121012 (MapleRoot's 219 ms alert2 spells), staff,
 * bow and 2H-sword swings, Aran's quick swings and the long Barrage pose. Stand on a map with mobs. With the client's attack
 * diagnostic on (slums.ini [Debug] AttackDiag=1) the client logs every bot attack it drops, with the reason.
 */
public class BotShowcaseCommand extends Command {
    {
        setDescription("Test server: spawn one bot per 4th job (or the given jobs) cycling all its attacks.");
    }

    static final List<Job> FOURTH = List.of(Job.HERO, Job.PALADIN, Job.DARKKNIGHT, Job.FP_ARCHMAGE, Job.IL_ARCHMAGE,
            Job.BISHOP, Job.BOWMASTER, Job.MARKSMAN, Job.NIGHTLORD, Job.SHADOWER, Job.BUCCANEER, Job.CORSAIR,
            Job.DAWNWARRIOR4, Job.BLAZEWIZARD4, Job.WINDARCHER4, Job.NIGHTWALKER4, Job.THUNDERBREAKER4, Job.ARAN4);
    static final List<Job> PIRATES = List.of(Job.BUCCANEER, Job.CORSAIR, Job.THUNDERBREAKER4, Job.ARAN4);
    static final List<Job> PACING = List.of(Job.BOWMASTER, Job.CORSAIR, Job.FP_ARCHMAGE, Job.BLAZEWIZARD4, Job.BISHOP,
            Job.HERO, Job.ARAN4, Job.THUNDERBREAKER4);
    private static final int DEFAULT_MINUTES = 5, MAX_MINUTES = 30;

    public record Request(List<Job> jobs, int minutes) {}

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        Request request = parse(params);
        if (request == null) {
            player.yellowMessage("Syntax: !botshowcase [4th | pirates | pacing | <job name or id> ...] [minutes]"
                    + "   e.g. !botshowcase 4th 10, !botshowcase aran4 buccaneer");
            return;
        }
        List<String> spawned = new ArrayList<>();
        spawn(player, request, spawned);
        player.yellowMessage("Showcase for " + request.minutes() + " min: " + spawned.size() + " bot(s).");
        for (String line : spawned) {
            player.yellowMessage("  " + line);
        }
    }

    /* Spawns the showcase next to the player; the bots' ids, one report line per bot added to {@code report} (also !qa bk-1). */
    public static List<Integer> spawn(Character player, Request request, List<String> report) {
        return spawn(player, request, report, null);
    }

    /** Where the showcase line is centred: by the mobs when a spot is known, else at the player. */
    static Point anchor(Point near, Point player) {
        return near != null ? new Point(near) : player;
    }

    /** As above, around {@code near} instead of the player when it is not null (!qa bk-1: by the mobs). */
    public static List<Integer> spawn(Character player, Request request, List<String> report, Point near) {
        MapleMap map = player.getMap();
        Point pos = anchor(near, player.getPosition());
        List<Integer> ids = new ArrayList<>();
        int slotBase = BotStore.nextDynamicSlots(request.jobs().size());
        int i = 0;
        for (Job job : request.jobs()) {
            Point at = new Point(pos.x + (i % 2 == 0 ? 1 : -1) * (40 + 30 * (i / 2)), pos.y);
            int level = ClassTestCommand.defaultLevel(job);
            int niche = Math.max(1, Math.min(5, job.getJobNiche()));
            int botId = BotGeneration.createBot(slotBase + i, at, map, niche, level, level, job.getId());
            i++;
            Character bot = BotHelpers.getCharFromChannelStorage(botId);
            if (bot == null) {
                continue;
            }
            for (int image : ClassTestCommand.lineageImages(job)) {
                ClassTestCommand.maxSkills(bot, image);
            }
            BotWeapons.ensureFittingWeapon(bot);
            StarterStash.grant(bot);
            ShowcaseBot showcase = new ShowcaseBot(bot, request.minutes() * 60_000L);
            CharacterStorage.addActiveBot(bot.getId(), showcase);
            showcase.setRunning(true);
            showcase.startScheduledTask(2000L);
            List<String> skills = new ArrayList<>();
            for (BotAttackProfile p : ShowcaseBot.rotationFor(bot)) {
                skills.add(p.skillId + " " + ShowcaseBot.cadenceLabel(bot, p));
            }
            ids.add(bot.getId());
            report.add(job.name() + " " + bot.getName() + " (" + botId + ") " + skills);
        }
        return ids;
    }

    /* "[4th|pirates|<job>...] [minutes]" -> the jobs and minutes, or null when a word is not a job. */
    public static Request parse(String[] params) {
        List<Job> jobs = new ArrayList<>();
        int minutes = DEFAULT_MINUTES;
        if (params != null) {
            for (int k = 0; k < params.length; k++) {
                String p = params[k].trim();
                if (p.isEmpty()) {
                    continue;
                }
                if (k == params.length - 1 && p.matches("\\d{1,2}") && !jobs.isEmpty()) {
                    minutes = Math.max(1, Math.min(MAX_MINUTES, Integer.parseInt(p)));
                    continue;
                }
                if (p.equalsIgnoreCase("4th")) {
                    addAll(jobs, FOURTH);
                } else if (p.equalsIgnoreCase("pirates") || p.equalsIgnoreCase("wave1")) {
                    addAll(jobs, PIRATES);
                } else if (p.equalsIgnoreCase("pacing")) {
                    addAll(jobs, PACING);
                } else {
                    Job job = job(p);
                    if (job == null) {
                        return null;
                    }
                    if (!jobs.contains(job)) {
                        jobs.add(job);
                    }
                }
            }
        }
        if (jobs.isEmpty()) {
            jobs.addAll(FOURTH);
        }
        return new Request(List.copyOf(jobs), minutes);
    }

    private static void addAll(List<Job> jobs, List<Job> more) {
        for (Job j : more) {
            if (!jobs.contains(j)) {
                jobs.add(j);
            }
        }
    }

    private static Job job(String word) {
        if (word.matches("\\d{3,4}")) {
            Job j = Job.getById(Integer.parseInt(word));
            return j != null && j != Job.BEGINNER ? j : null;
        }
        try {
            return Job.valueOf(word.toUpperCase().replace("-", "_"));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
