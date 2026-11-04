package soloMapling.mechanics;

import java.util.List;
import java.util.Map;

/**
 * The six Cafe PQ areas, exactly as their scripts declare them with {@code var eventMaps = [...]}
 * ({@code scripts/event/CafePQ_1.js:36} .. {@code CafePQ_6.js:36}): the first map is where the party enters and the
 * rest are the same area's rooms. Nothing here is inferred; the lists are the scripts' own.
 *
 * <p>The scripts differ in nothing else that matters to a row except the coupon count
 * ({@code couponsNeeded}: 400, 350, 350, 450, 500, 300) and, in the third area, the top drop step
 * ({@code getDroppedQuantity} returns 3 above level 65 rather than 5, {@code CafePQ_3.js:251-259}).
 */
public final class CafeAreas {

    /** Area entry map to every map of that area, in the script's order. */
    static final Map<Integer, List<Integer>> AREAS = Map.of(
            190000000, List.of(190000000, 190000001, 190000002),
            191000000, List.of(191000000, 191000001),
            192000000, List.of(192000000, 192000001),
            195000000, List.of(195000000, 195010000, 195020000, 195030000),
            196000000, List.of(196000000, 196010000),
            197000000, List.of(197000000, 197010000));

    /** Every map of the area this map belongs to (the map itself included), or empty when it is not a Cafe PQ map. */
    static List<Integer> mapsOf(int mapId) {
        for (List<Integer> maps : AREAS.values()) {
            if (maps.contains(mapId)) {
                return maps;
            }
        }
        return List.of();
    }

    /** The area's maps other than this one. */
    static List<Integer> siblingsOf(int mapId) {
        return mapsOf(mapId).stream().filter(m -> m != mapId).toList();
    }

    private CafeAreas() {
    }
}
