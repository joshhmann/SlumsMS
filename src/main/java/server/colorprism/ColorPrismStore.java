package server.colorprism;

import client.Character;
import client.inventory.Equip;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Coloring Prism persistence, migration 010:
 * <ul>
 *   <li>{@code equip_tints}: one row per tinted equip row, keyed by {@code inventoryitemid}, deleted with the item
 *       row by its foreign key. ItemFactory rewrites a character's item rows on every save (new ids), so the tints
 *       are written right after them, in the same transaction.</li>
 *   <li>{@code character_look_tints}: one row per character with a hair or face tint.</li>
 * </ul>
 * Separate tables rather than new columns: {@code inventoryequipment} is written with a positional INSERT, so a
 * column added there would break this jar with the flag off and every older jar on a migrated database.
 * Every load goes through the {@link Equip} / {@link Character} setters, which reject anything but a valid tint.
 */
public final class ColorPrismStore {
    private static final Logger log = LoggerFactory.getLogger(ColorPrismStore.class);

    static final String EQUIP_TINTS = "equip_tints";
    static final String LOOK_TINTS = "character_look_tints";

    private static volatile Boolean schemaPresent;

    private ColorPrismStore() {
    }

    public static boolean enabled() {
        return ColorPrism.enabled();
    }

    /** True once both tables are seen; a missing table is logged once and keeps the feature off. */
    static boolean schemaPresent() {
        Boolean known = schemaPresent;
        if (known != null) {
            return known;
        }
        synchronized (ColorPrismStore.class) {
            if (schemaPresent != null) {
                return schemaPresent;
            }
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM information_schema.tables "
                         + "WHERE table_schema = DATABASE() AND table_name IN (?, ?)")) {
                ps.setString(1, EQUIP_TINTS);
                ps.setString(2, LOOK_TINTS);
                try (ResultSet rs = ps.executeQuery()) {
                    boolean present = rs.next() && rs.getInt(1) == 2;
                    if (!present) {
                        log.error("COLOR_PRISM_ENABLED is set but migration 010 (tables {} / {}) is not applied; "
                                + "the Coloring Prism stays off", EQUIP_TINTS, LOOK_TINTS);
                    }
                    schemaPresent = present;
                    return present;
                }
            } catch (SQLException e) {
                log.warn("Could not check for the Coloring Prism tables; the feature is off for now", e);
                return false;
            }
        }
    }

    /** Tests: skip the database check (null re-arms it). */
    public static void setSchemaPresent(Boolean present) {
        schemaPresent = present;
    }

    // ---- equips -------------------------------------------------------------------------------------------------

    public record EquipTintRow(int inventoryItemId, short hue, byte chroma, byte bright,
                               short fxHue, byte fxChroma, byte fxBright) {
    }

    /** The extra join for an item load query; empty when the feature is off (the table may not exist). */
    public static String equipTintJoin(boolean withTints) {
        return withTints ? " LEFT JOIN `" + EQUIP_TINTS + "` USING(`inventoryitemid`)" : "";
    }

    /** Reads the join's columns (NULL, i.e. no row, reads as 0 and so as untinted). */
    public static void readEquipTints(ResultSet rs, Equip equip) throws SQLException {
        equip.setTint(rs.getInt("tint_hue"), rs.getInt("tint_chroma"), rs.getInt("tint_bright"));
        equip.setFxTint(rs.getInt("fx_hue"), rs.getInt("fx_chroma"), rs.getInt("fx_bright"));
    }

    /** Queues a just-inserted equip row's tint, if it has one and the feature is on. */
    public static void collect(List<EquipTintRow> out, int inventoryItemId, Equip equip) {
        if (!(equip.isTinted() || equip.isFxTinted()) || !enabled()) {
            return;
        }
        out.add(new EquipTintRow(inventoryItemId, equip.getTintHue(), equip.getTintChroma(), equip.getTintBright(),
                equip.getTintFxHue(), equip.getTintFxChroma(), equip.getTintFxBright()));
    }

    public static void saveEquipTints(Connection con, List<EquipTintRow> rows) throws SQLException {
        if (rows.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO `" + EQUIP_TINTS + "` "
                + "(inventoryitemid, tint_hue, tint_chroma, tint_bright, fx_hue, fx_chroma, fx_bright) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (EquipTintRow r : rows) {
                ps.setInt(1, r.inventoryItemId());
                ps.setShort(2, r.hue());
                ps.setByte(3, r.chroma());
                ps.setByte(4, r.bright());
                ps.setShort(5, r.fxHue());
                ps.setByte(6, r.fxChroma());
                ps.setByte(7, r.fxBright());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ---- hair / face --------------------------------------------------------------------------------------------

    public static void loadLookTints(Connection con, Character chr) throws SQLException {
        if (!ColorPrism.isPlayer(chr) || !enabled()) {
            return;
        }
        try (PreparedStatement ps = con.prepareStatement("SELECT hair_hue, hair_chroma, hair_bright, "
                + "face_hue, face_chroma, face_bright FROM `" + LOOK_TINTS + "` WHERE characterid = ?")) {
            ps.setInt(1, chr.getId());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    chr.setHairTint(rs.getInt("hair_hue"), rs.getInt("hair_chroma"), rs.getInt("hair_bright"));
                    chr.setFaceTint(rs.getInt("face_hue"), rs.getInt("face_chroma"), rs.getInt("face_bright"));
                }
            }
        }
    }

    /** Inside saveCharToDB's transaction: one row while either look is tinted, none otherwise. */
    public static void saveLookTints(Connection con, Character chr) throws SQLException {
        if (!ColorPrism.isPlayer(chr) || !enabled()) {
            return;
        }
        if (!chr.isHairTinted() && !chr.isFaceTinted()) {
            try (PreparedStatement ps = con.prepareStatement("DELETE FROM `" + LOOK_TINTS + "` WHERE characterid = ?")) {
                ps.setInt(1, chr.getId());
                ps.executeUpdate();
            }
            return;
        }
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO `" + LOOK_TINTS + "` "
                + "(characterid, hair_hue, hair_chroma, hair_bright, face_hue, face_chroma, face_bright) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE hair_hue = VALUES(hair_hue), "
                + "hair_chroma = VALUES(hair_chroma), hair_bright = VALUES(hair_bright), face_hue = VALUES(face_hue), "
                + "face_chroma = VALUES(face_chroma), face_bright = VALUES(face_bright)")) {
            ps.setInt(1, chr.getId());
            ps.setShort(2, chr.getHairTintHue());
            ps.setByte(3, chr.getHairTintChroma());
            ps.setByte(4, chr.getHairTintBright());
            ps.setShort(5, chr.getFaceTintHue());
            ps.setByte(6, chr.getFaceTintChroma());
            ps.setByte(7, chr.getFaceTintBright());
            ps.executeUpdate();
        }
    }
}
