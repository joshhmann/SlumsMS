package scripting;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import soloMapling.WzTestData;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every quest the server's Quest.wz marks as script-started or script-ended ({@code startscript} / {@code endscript}
 * in Check.img) needs {@code scripts/quest/<id>.js} with that function. When the file is missing the server falls
 * back to medalQuest.js, which completes the quest with no reward and says "&lt;&gt; is not coded"; when the function is
 * missing the quest can never be handed in. Found in the 1.5.2 quest sweep: MapleRoot's Quest.wz flags quests as
 * script-ended that stock Cosmic's data completes without a script (2236, 3114, 3311, 3345, 3360, 3926, 3927, 3929,
 * 21728). Reads the real WZ, so it skips without {@code wz/} (run with {@code -Dwz-path=<server wz>}).
 */
class QuestScriptCoverageTest {

    /**
     * Stock Cosmic has no script for these either (tools/questcheck baseline). Not fixed here: 1028 (the Maple
     * Island boat, handled by Shanks's NPC script), 2233 / 2234 (family Rep quests), 20015 (Cygnus intro).
     */
    private static final Set<Integer> STOCK_WITHOUT_SCRIPT = Set.of(1028, 2233, 2234, 20015);

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");

    private static List<Element> children(Node parent) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) {
                out.add(e);
            }
        }
        return out;
    }

    private static Element child(Element parent, String name) {
        for (Element e : children(parent)) {
            if (name.equals(e.getAttribute("name"))) {
                return e;
            }
        }
        return null;
    }

    private static Element root(String img) throws Exception {
        Path file = WzTestData.dir().resolve("Quest.wz").resolve(img);
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile()).getDocumentElement();
    }

    @Test
    void everyScriptedQuestHasItsScriptFunction() throws Exception {
        WzTestData.assumePresent("Quest.wz");
        Map<String, Element> info = new HashMap<>();
        for (Element e : children(root("QuestInfo.img.xml"))) {
            info.put(e.getAttribute("name"), e);
        }
        String now = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMddHH"));

        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (Element quest : children(root("Check.img.xml"))) {
            String id = quest.getAttribute("name");
            Element qi = info.get(id);
            if (qi != null && child(qi, "viewMedalItem") != null) {
                continue;   // medal quests are meant to run medalQuest.js
            }
            Element start = child(quest, "0");
            Element endDate = start == null ? null : child(start, "end");
            if (endDate != null && endDate.getAttribute("value").compareTo(now) < 0) {
                continue;   // an event quest that can no longer be started
            }
            if (STOCK_WITHOUT_SCRIPT.contains(Integer.parseInt(id))) {
                continue;
            }
            for (String[] side : new String[][]{{"0", "startscript", "start"}, {"1", "endscript", "end"}}) {
                Element phase = child(quest, side[0]);
                if (phase == null || child(phase, side[1]) == null) {
                    continue;
                }
                checked++;
                Path script = Path.of("scripts", "quest", id + ".js");
                if (!Files.isRegularFile(script)) {
                    problems.add(id + ": " + side[1] + " but no " + script);
                    continue;
                }
                String code = LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(Files.readString(script)).replaceAll(""))
                        .replaceAll("");
                if (!Pattern.compile("function\\s+" + side[2] + "\\s*\\(").matcher(code).find()) {
                    problems.add(id + ": " + side[1] + " but " + script + " has no " + side[2] + "()");
                }
            }
        }
        assertTrue(checked > 100, "expected the real Quest.wz, checked only " + checked);
        assertTrue(problems.isEmpty(), "scripted quests without their script:\n" + String.join("\n", problems));
    }
}
