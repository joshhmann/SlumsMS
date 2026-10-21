package soloMapling.ArtificialPlayer.BotAttackSystem;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/*
 * The CLIENT's own data, as the v83 client checks another character's attack against it (ticket 32):
 * - src/test/resources/soloMapling/client-attack-data.tsv - every skill's Skill.wz pose / weapon / skillType / counts /
 *   keydown nodes and every weapon's attack type, attack speed and afterimage, read from the client's Data folder
 *   (tools/bot-attack-rules/client_attack_fixture.py; its header carries the sha256 of the files read);
 * - src/test/resources/soloMapling/client-attack-exe.tsv - the body-action table, the plain-pose tables, and per
 *   skill the exe's hard-coded lists, the route DoActiveSkill sends it on and the weapon types it accepts it with
 *   (tools/bot-attack-rules/exe_rules.py, from MapleStory.exe md5 89f69d20cc0457a2d7db5573082c13d7).
 */
final class ClientAttackData {

    record Skill(int id, int skillType, int weapon, List<String> actions, Map<Integer, String> levelActions,
                 int maxLevel, Set<String> nodes, TreeMap<Integer, int[]> counts, String prepareAction) {

        /* mobCount, attackCount, bulletCount at this level (0 = none). */
        int[] countsAt(int level) {
            Map.Entry<Integer, int[]> e = counts.floorEntry(Math.max(1, level));
            if (e == null) {
                e = counts.firstEntry();
            }
            return e == null ? new int[3] : e.getValue();
        }
    }

    record Weapon(int id, int attackType, int attackSpeed, String afterImage) {}

    record ExeSkill(int id, Set<String> flags, String route, Set<Integer> weaponTypes) {}

    final Map<Integer, Skill> skills = new HashMap<>();
    final Map<Integer, Weapon> weapons = new HashMap<>();
    final List<String> actions = new ArrayList<>();
    final Map<String, int[]> tables = new HashMap<>(); // "T1/3/norm" -> codes
    final Map<Integer, ExeSkill> exe = new HashMap<>();
    final List<String> header = new ArrayList<>();

    static final ClientAttackData INSTANCE = new ClientAttackData();

    private ClientAttackData() {
        read("/soloMapling/client-attack-data.tsv");
        read("/soloMapling/client-attack-exe.tsv");
    }

    int[] table(String name, int attackType, boolean type3) {
        int[] t = tables.get(name + "/" + attackType + "/" + (type3 ? "type3" : "norm"));
        return t != null ? t : new int[0];
    }

    private void read(String resource) {
        try (InputStream in = ClientAttackData.class.getResourceAsStream(resource);
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                if (line.startsWith("#")) {
                    header.add(line);
                    continue;
                }
                String[] f = line.split("\t", -1);
                switch (f[0]) {
                    case "S" -> skill(f);
                    case "W" -> weapons.put(Integer.parseInt(f[1]), new Weapon(Integer.parseInt(f[1]),
                            Integer.parseInt(f[2]), Integer.parseInt(f[3]), f[4]));
                    case "A" -> actions.add(f[2]);
                    case "T" -> tables.put(f[1] + "/" + f[2] + "/" + f[3], f[4].isEmpty() ? new int[0]
                            : Arrays.stream(f[4].split(",")).mapToInt(Integer::parseInt).toArray());
                    case "K" -> exe.put(Integer.parseInt(f[1]), new ExeSkill(Integer.parseInt(f[1]),
                            f[2].isEmpty() ? Set.of() : Set.of(f[2].split(",")), f[3],
                            f[4].isEmpty() ? Set.of() : new HashSet<>(Arrays.stream(f[4].split(","))
                                    .map(Integer::parseInt).toList())));
                    default -> throw new IllegalStateException("unknown row " + line);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void skill(String[] f) {
        Map<Integer, String> levelActions = new HashMap<>();
        if (!f[5].isEmpty()) {
            for (String e : f[5].split(";")) {
                String[] kv = e.split("=");
                levelActions.put(Integer.parseInt(kv[0]), kv[1]);
            }
        }
        TreeMap<Integer, int[]> counts = new TreeMap<>();
        if (!f[8].isEmpty()) {
            for (String e : f[8].split(";")) {
                String[] lv = e.split(":");
                String[] c = lv[1].split("/", -1);
                counts.put(Integer.parseInt(lv[0]), new int[]{num(c[0]), num(c[1]), num(c[2])});
            }
        }
        int id = Integer.parseInt(f[1]);
        skills.put(id, new Skill(id, Integer.parseInt(f[2]), Integer.parseInt(f[3]),
                f[4].isEmpty() ? List.of() : List.of(f[4].split(",")), levelActions, Integer.parseInt(f[6]),
                f[7].isEmpty() ? Set.of() : Set.of(f[7].split(",")), counts, f.length > 9 ? f[9] : ""));
    }

    /* A count as the client data has it; a formula ("5+d(x/16)") counts as its leading number. */
    private static int num(String s) {
        if (s.isEmpty()) {
            return 0;
        }
        int end = 0;
        while (end < s.length() && Character.isDigit(s.charAt(end))) {
            end++;
        }
        return end == 0 ? 0 : Integer.parseInt(s.substring(0, end));
    }
}
