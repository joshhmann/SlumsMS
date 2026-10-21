package client.command.commands.gm2;

import client.Character;
import client.Client;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.command.Command;
import client.inventory.manipulator.InventoryManipulator;
import constants.id.ItemId;
import constants.inventory.EquipType;
import provider.Data;
import provider.DataProviderFactory;
import provider.wz.WZFiles;
import server.ItemInformationProvider;
import soloMapling.ArtificialPlayer.BotDecoratorSystem.BotWeapons;

import java.util.ArrayList;
import java.util.List;

import static soloMapling.itemPool.ItemInformationProviderUtilities.getRandomEquipForWearing;

/*
 * Test server only (registered when attack capture is on, -Dslums.attackcapture=true): turns the GM into a job in
 * one step for an attack-capture session (ticket 32) - the job, a level (30 / 70 / 120 / 200 by job tier unless
 * given), every skill of the job's line at its master level (MapleRoot's NPC 2084000 package and hyper skills sit
 * in the 4th-job image, so they are included), one weapon of each kind the bots of that job use (BotWeapons), two
 * stacks of the ammo it fires, stats to equip it, and full HP/MP.
 */
public class ClassTestCommand extends Command {
    {
        setDescription("Test server: become a job with all its skills, weapons and ammo (attack capture).");
    }

    public record Request(int jobId, int level) {}

    static final int STATS = 999;
    private static final int ILBI_THROWING_STARS = 2070006;
    private static final int ARROW_FOR_BOW = 2060000;
    private static final int ARROW_FOR_CROSSBOW = 2061000;

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        Request request = parse(params);
        Job job = request != null ? Job.getById(request.jobId()) : null;
        if (job == null) {
            player.yellowMessage("Syntax: !classtest <job id> [level]   e.g. !classtest 512 (Buccaneer), !classtest 2112 200 (Aran)");
            return;
        }
        int level = request.level() > 0 ? request.level() : defaultLevel(job);

        player.changeJob(job);
        new LevelCommand().execute(c, new String[]{String.valueOf(level)});
        int skills = 0;
        for (int image : lineageImages(job)) {
            skills += maxSkills(player, image);
        }
        player.updateStrDexIntLuk(STATS);

        List<String> given = new ArrayList<>();
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        for (EquipType kind : BotWeapons.weaponsFor(job)) {
            Integer weapon = getRandomEquipForWearing(kind, player);
            if (weapon != null && InventoryManipulator.addById(c, weapon, (short) 1)) {
                given.add(ii.getName(weapon));
            }
            int ammo = ammoFor(kind);
            if (ammo > 0) {
                short stack = ii.getSlotMax(player, ammo);
                InventoryManipulator.addById(c, ammo, stack);
                InventoryManipulator.addById(c, ammo, stack);
                given.add(ii.getName(ammo));
            }
        }
        player.updateMaxHpMaxMp(30000, 30000);
        player.healHpMp();
        player.equipChanged();
        player.yellowMessage("Class test: " + job + " level " + player.getLevel() + ", " + skills + " skills maxed"
                + (given.isEmpty() ? "" : ", given " + String.join(", ", given)) + ". Equip a weapon from your inventory.");
    }

    /* "<job id> [level]", or null when the job id is missing or anything is not a number. */
    public static Request parse(String[] params) {
        if (params == null || params.length < 1 || params.length > 2) {
            return null;
        }
        try {
            int jobId = Integer.parseInt(params[0]);
            int level = params.length > 1 ? Integer.parseInt(params[1]) : 0;
            return level < 0 ? null : new Request(jobId, level);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /* The level a job is checked at: where its tier's attacks are all learnable. */
    public static int defaultLevel(Job job) {
        return switch (job.getJobTier()) {
            case 1 -> 30;
            case 2 -> 70;
            case 3 -> 120;
            default -> 200;
        };
    }

    /* The Skill.wz images of a job's line, first job first: Buccaneer is 500, 510, 511, 512. */
    public static List<Integer> lineageImages(Job job) {
        List<Integer> images = new ArrayList<>();
        for (Job j : Job.values()) {
            if (job.isA(j) && !images.contains(j.getId())) {
                images.add(j.getId());
            }
        }
        images.sort(null);
        return images;
    }

    /* The ammo a weapon kind fires, or 0 for one that fires none. */
    public static int ammoFor(EquipType kind) {
        return switch (kind) {
            case CLAW -> ILBI_THROWING_STARS;
            case BOW -> ARROW_FOR_BOW;
            case CROSSBOW -> ARROW_FOR_CROSSBOW;
            case PISTOL -> ItemId.BULLET;
            default -> 0;
        };
    }

    // Every skill of one Skill.wz image at its master level; returns how many.
    public static int maxSkills(Character player, int image) {
        Data skills;
        try {
            Data img = DataProviderFactory.getDataProvider(WZFiles.SKILL).getData(String.format("%03d.img", image));
            skills = img != null ? img.getChildByPath("skill") : null;
        } catch (RuntimeException e) {
            return 0; // no such image
        }
        if (skills == null) {
            return 0;
        }
        int maxed = 0;
        for (Data entry : skills.getChildren()) {
            Skill skill;
            try {
                skill = SkillFactory.getSkill(Integer.parseInt(entry.getName()));
            } catch (NumberFormatException e) {
                continue;
            }
            if (skill != null && skill.getMaxLevel() > 0) {
                player.changeSkillLevel(skill, (byte) skill.getMaxLevel(), skill.getMaxLevel(), -1);
                maxed++;
            }
        }
        return maxed;
    }
}
