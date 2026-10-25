package scripting;

import org.junit.jupiter.api.Test;
import server.quest.HiddenQuests;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.5.2 owner decision (#10): NPC and quest dialogue must stop promising skills MapleRoot removed (Leafre 4th-job
 * NPCs, Kyrin's Super Transformation, Aran's Polearm Booster, the job instructors' skill descriptions). The removed
 * skills are the "Skill.wz: removed" table of docs/release/1.5.2-jobs-skills-audit.md. No script may teach one, show
 * its icon or name (#s / #q), or name it in highlighted dialogue.
 */
class RemovedSkillDialogueTest {

    private static final Path AUDIT = Path.of("docs/release/1.5.2-jobs-skills-audit.md");
    private static final Path SCRIPTS = Path.of("scripts");

    /** Handled by another 1.5.2 change (Job Master, rebirth helper, Gold Compass, stylist), or not dialogue. */
    private static final Set<String> SKIP = Set.of("npc/9010003.js", "npc/9977778.js", "npc/2084000.js",
            "npc/STYLIST.js", "npc/9900000.js",
            "npc/9977777.js");   // the repack's changelog NPC ("Reviewed many skills, such as Steal")

    @Test
    void noScriptTeachesShowsOrPromisesARemovedSkill() throws IOException {
        Map<Integer, String> removed = removedSkills();
        assertTrue(removed.size() >= 60, "the audit's removed-skill table was read: " + removed.size());

        List<Pattern> namePatterns = new ArrayList<>();
        for (String name : Set.copyOf(removed.values())) {
            String n = Pattern.quote(name);
            // highlighted (#b..#k / #r..#k), quoted, or "<name> skill"
            namePatterns.add(Pattern.compile("#[brde]" + n + "#k|[\"']" + n + "[\"']|\\b" + n + " skill\\b",
                    Pattern.CASE_INSENSITIVE));
        }
        Pattern idUse = Pattern.compile("(?:teachSkill\\(\\s*|#[sq])0*(\\d{4,8})");

        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SCRIPTS)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".js"))::iterator) {
                String rel = SCRIPTS.relativize(f).toString().replace('\\', '/');
                if (SKIP.contains(rel) || hiddenQuestScript(rel)) {
                    continue;
                }
                List<String> lines = Files.readAllLines(f);
                boolean inBlockComment = false;
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    String t = line.trim();
                    if (inBlockComment || t.startsWith("/*")) {   // file headers name the NPC's old purpose
                        inBlockComment = !line.contains("*/");
                        continue;
                    }
                    if (t.startsWith("//") || t.startsWith("*")) {
                        continue;
                    }
                    Matcher m = idUse.matcher(line);
                    while (m.find()) {
                        int id = Integer.parseInt(m.group(1));
                        if (removed.containsKey(id)) {
                            problems.add(rel + ":" + (i + 1) + " uses removed skill " + id + " " + removed.get(id));
                        }
                    }
                    for (Pattern p : namePatterns) {
                        Matcher n = p.matcher(line);
                        if (n.find()) {
                            problems.add(rel + ":" + (i + 1) + " names a removed skill: " + n.group());
                        }
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /** Quest scripts of quests nobody can start any more (HiddenQuests, ticket 82) keep their old story. */
    private static boolean hiddenQuestScript(String rel) {
        Matcher m = Pattern.compile("quest/(\\d+)\\.js").matcher(rel);
        return m.matches() && HiddenQuests.ids().contains(Integer.parseInt(m.group(1)));
    }

    /** id -> name from the audit's "Skill.wz: removed" table. */
    static Map<Integer, String> removedSkills() throws IOException {
        Map<Integer, String> out = new LinkedHashMap<>();
        boolean in = false;
        for (String line : Files.readAllLines(AUDIT)) {
            if (line.startsWith("## ")) {
                in = line.startsWith("## Skill.wz: removed");
                continue;
            }
            Matcher m = Pattern.compile("^\\| (\\d+) \\| [^|]+ \\| ([^|]+?) \\|").matcher(line);
            if (in && m.find()) {
                out.put(Integer.parseInt(m.group(1)), m.group(2).strip());
            }
        }
        return out;
    }
}
