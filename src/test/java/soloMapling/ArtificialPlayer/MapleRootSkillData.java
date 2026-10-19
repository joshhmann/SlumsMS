package soloMapling.ArtificialPlayer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * MapleRoot's skill set as a test fixture: {@code mapleroot-skills.tsv}, written by {@code tools/skill-catalog} from
 * the server's {@code wz/Skill.wz} and {@code wz/String.wz} (the tests have no WZ data of their own). Regenerate it
 * with {@code python3 tools/skill-catalog/skill_catalog.py --wz <Server>/wz --tsv
 * src/test/resources/soloMapling/mapleroot-skills.tsv} when the server's skills change.
 */
public final class MapleRootSkillData {

    /** One skill: its job, maximum level, kind (attack, buff or passive) and name. */
    public record SkillInfo(int id, int job, int maxLevel, String kind, String name) {
    }

    private static final Map<Integer, SkillInfo> SKILLS = load();

    private MapleRootSkillData() {
    }

    public static Map<Integer, SkillInfo> skills() {
        return SKILLS;
    }

    public static SkillInfo get(int skillId) {
        return SKILLS.get(skillId);
    }

    private static Map<Integer, SkillInfo> load() {
        Map<Integer, SkillInfo> skills = new HashMap<>();
        try (InputStream in = MapleRootSkillData.class.getResourceAsStream("/soloMapling/mapleroot-skills.tsv")) {
            if (in == null) {
                throw new IllegalStateException("mapleroot-skills.tsv is missing from the test resources");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\t", -1);
                int id = Integer.parseInt(f[0]);
                skills.put(id, new SkillInfo(id, Integer.parseInt(f[1]), Integer.parseInt(f[2]), f[3], f[4]));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Collections.unmodifiableMap(skills);
    }
}
