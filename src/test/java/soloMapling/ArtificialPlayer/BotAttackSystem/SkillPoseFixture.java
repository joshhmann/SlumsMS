package soloMapling.ArtificialPlayer.BotAttackSystem;

import soloMapling.ArtificialPlayer.BotAttackSystem.BotSkillActions.SkillPose;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*
 * MapleRoot's Skill.wz poses and weapons (src/test/resources/soloMapling/mapleroot-skill-poses.tsv, written by
 * tools/skill-poses/skill_poses.py from the server's wz/), installed as BotSkillActions' source so a test sees what
 * the live server reads.
 */
final class SkillPoseFixture {

    private static final Map<Integer, SkillPose> POSES = load();

    private SkillPoseFixture() {}

    static void install() {
        BotSkillActions.useSource(POSES::get);
    }

    static boolean has(int skillId) {
        return POSES.containsKey(skillId);
    }

    private static Map<Integer, SkillPose> load() {
        Map<Integer, SkillPose> poses = new HashMap<>();
        try (InputStream in = SkillPoseFixture.class.getResourceAsStream("/soloMapling/mapleroot-skill-poses.tsv");
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\t", -1);
                List<String> actions = f[2].isEmpty() ? List.of() : Arrays.asList(f[2].split(","));
                Map<Integer, String> levels = new HashMap<>();
                if (!f[3].isEmpty()) {
                    for (String entry : f[3].split(";")) {
                        String[] kv = entry.split("=");
                        levels.put(Integer.parseInt(kv[0]), kv[1]);
                    }
                }
                poses.put(Integer.parseInt(f[0]), new SkillPose(List.copyOf(actions), Map.copyOf(levels),
                        Integer.parseInt(f[1])));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return poses;
    }
}
