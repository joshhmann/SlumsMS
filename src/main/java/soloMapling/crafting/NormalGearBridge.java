package soloMapling.crafting;

import com.google.gson.JsonParser;
import soloMapling.DataFiles;
import tools.DatabaseConnection;
import java.nio.file.Files;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Guide activation and read-only source proof; never grants items or edits drop tables. */
public final class NormalGearBridge {
    public static final String PROPERTY = "slums.gear.bridge";
    public static final String GUIDE = "scripts/json/normalGearGuide.json";
    private NormalGearBridge() { }
    public static boolean isEnabled() { return Boolean.getBoolean(PROPERTY); }
    public record Pair(int mob, int item) { }
    public record Drop(int quest, int chance) { }

    public static Map<Pair,Integer> targets() throws Exception {
        var root = JsonParser.parseString(Files.readString(DataFiles.path(GUIDE))).getAsJsonObject();
        Map<Pair,Integer> result = new LinkedHashMap<>();
        var families = root.getAsJsonArray("families");
        if (families.size()!=5) throw new IllegalArgumentException("Guide needs five families");
        for (var family : families) {
            var bands=family.getAsJsonObject().getAsJsonArray("bands");
            if (bands.size()!=3) throw new IllegalArgumentException("Guide needs three bands");
            for (var band : bands) for (var entry : band.getAsJsonObject().getAsJsonArray("items")) {
                var item=entry.getAsJsonObject();
                int id=item.get("id").getAsInt(), chance=item.get("chance").getAsInt();
                if(id<1000000 || id>=2000000 || chance<=0 || chance>1000000)
                    throw new IllegalArgumentException("Invalid guide item/rate");
                var sources=item.getAsJsonArray("sources");
                if(sources.size()!=2) throw new IllegalArgumentException("Item needs two sources");
                for (var source : sources) {
                    int mob=source.getAsJsonObject().get("mob").getAsInt();
                    if(mob<=0 || result.putIfAbsent(new Pair(mob,id),chance)!=null)
                        throw new IllegalArgumentException("Duplicate/invalid source pair");
                }
            }
        }
        if(result.size()!=226 || result.keySet().stream().map(Pair::item).distinct().count()!=113)
            throw new IllegalArgumentException("Incomplete normal bridge guide");
        return Map.copyOf(result);
    }

    public static List<String> checkRates(Map<Pair,Integer> targets, Map<Pair,Drop> actual) {
        var bad=targets.entrySet().stream().filter(e -> {
            Drop d=actual.get(e.getKey());
            return d==null || d.quest()!=0 || d.chance()<e.getValue();
        }).toList();
        if(!bad.isEmpty()) {
            String sample=bad.stream().limit(4).map(e->e.getKey().mob()+"/"+e.getKey().item())
                    .collect(Collectors.joining(", "));
            return List.of("FAIL "+bad.size()+" normal bridge sources missing, quest-only or below rate floor: "+sample);
        }
        return List.of("PASS "+targets.size()+" normal bridge source rates in DB; drop cache, client menu and combat need hands-on proof");
    }

    public static List<String> verifyActiveDrops() throws Exception {
        var targets=targets();
        String mobs=targets.keySet().stream().map(Pair::mob).distinct().sorted()
                .map(String::valueOf).collect(Collectors.joining(","));
        Map<Pair,Drop> actual=new LinkedHashMap<>();
        try(Connection db=DatabaseConnection.getConnection();
            var statement=db.prepareStatement("SELECT dropperid,itemid,questid,chance FROM drop_data WHERE dropperid IN ("+mobs+")");
            var rows=statement.executeQuery()) {
            while(rows.next()) {
                var pair=new Pair(rows.getInt(1),rows.getInt(2));
                if(targets.containsKey(pair)) actual.put(pair,new Drop(rows.getInt(3),rows.getInt(4)));
            }
        }
        return checkRates(targets,actual);
    }
}
