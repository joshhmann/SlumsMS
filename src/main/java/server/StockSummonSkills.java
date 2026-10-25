package server;

import com.esotericsoftware.yamlbeans.YamlReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 1.5.1: stock v83 duration and cooldown for summon skills, read from {@value #DATA_PATH}.
 *
 * <p>MapleRoot's server Skill.wz gives summons a 20 s duration and a 60 s cooldown at every level (and 3600 s to
 * Beholder, Octopus and the Blaze Wizard Ifrit). The wz folder is not part of a deploy, so instead of editing it,
 * {@link StatEffect} asks this table for each skill level it loads and takes the stock "time" and "cooltime" from
 * here when the skill is listed. The server is authoritative for both: the summon is removed when its SUMMON buff
 * (registered with this duration) runs out, and the cooldown is enforced server side and sent with the cooldown
 * packet. Only the client's tooltip still shows the client's own wz values.
 */
public final class StockSummonSkills {
    private static final Logger log = LoggerFactory.getLogger(StockSummonSkills.class);

    static final String DATA_PATH = "src/main/java/soloMapling/server/stock-summon-skills.yaml";

    /** skill id -> per level (index 0 = level 1) {time seconds, cooltime seconds}. */
    private final Map<Integer, int[][]> bySkill;

    private StockSummonSkills(Map<Integer, int[][]> bySkill) {
        this.bySkill = bySkill;
    }

    private static final class Holder {
        static final StockSummonSkills SHARED = load(soloMapling.DataFiles.path(DATA_PATH));
    }

    /** {time seconds, cooltime seconds} for this skill level, or null when the skill keeps its wz values. */
    public static int[] lookup(int skillId, int level) {
        return Holder.SHARED.get(skillId, level);
    }

    int[] get(int skillId, int level) {
        int[][] levels = bySkill.get(skillId);
        if (levels == null || level < 1 || level > levels.length) {
            return null;
        }
        return levels[level - 1];
    }

    int size() {
        return bySkill.size();
    }

    static StockSummonSkills load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            log.warn("Stock summon skills: {} is missing, summons keep the server wz duration and cooldown", DATA_PATH);
            return new StockSummonSkills(Collections.emptyMap());
        }
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            StockSummonSkills table = parse(new YamlReader(in).read());
            log.info("Stock summon skills: {} skills use stock v83 duration and cooldown", table.size());
            return table;
        } catch (Exception e) {
            log.warn("Stock summon skills: could not read {}, summons keep the server wz duration and cooldown", file, e);
            return new StockSummonSkills(Collections.emptyMap());
        }
    }

    /** A skill whose time and cooltime lists are missing, unequal or not numbers is left out (and logged). */
    static StockSummonSkills parse(Object root) {
        Map<Integer, int[][]> out = new HashMap<>();
        Object skills = root instanceof Map<?, ?> m ? m.get("skills") : null;
        if (skills instanceof Map<?, ?> entries) {
            for (Map.Entry<?, ?> e : entries.entrySet()) {
                try {
                    int id = Integer.parseInt(String.valueOf(e.getKey()).trim());
                    Map<?, ?> body = (Map<?, ?>) e.getValue();
                    List<?> time = (List<?>) body.get("time");
                    List<?> cool = (List<?>) body.get("cooltime");
                    if (time == null || cool == null || time.isEmpty() || time.size() != cool.size()) {
                        throw new IllegalArgumentException("time and cooltime need one value per level");
                    }
                    int[][] levels = new int[time.size()][];
                    for (int i = 0; i < levels.length; i++) {
                        levels[i] = new int[]{Integer.parseInt(String.valueOf(time.get(i)).trim()),
                                Integer.parseInt(String.valueOf(cool.get(i)).trim())};
                    }
                    out.put(id, levels);
                } catch (RuntimeException ex) {
                    log.warn("Stock summon skills: skipping entry {}: {}", e.getKey(), ex.toString());
                }
            }
        }
        return new StockSummonSkills(out);
    }
}
