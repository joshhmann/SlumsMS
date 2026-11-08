package soloMapling.ArtificialPlayer.BotAttackSystem;

import provider.wz.WZFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1.6.6 (TEST 2026-11-08 04:14:58 UTC): mobs a real client can never hit, because their WZ image (and the image it
 * links to) has no {@code lt} box in any frame: Horntail's linking body 8810018, Pink Bean's controller bodies and the
 * like. A bot hit Horntail's body and its death cleared the run with the visible parts alive. Bots never target these
 * ({@link BotAttackDriver#botMayTarget}). A mob whose image cannot be found is NOT listed: no data, no change.
 * One read per mob id per boot, cached. See docs/bots/no-hitbox-mobs.md.
 */
public final class NoHitboxMobs {

    private static final Pattern LINK = Pattern.compile("name=\"link\" value=\"(\\d+)\"");
    private static final Map<Integer, Boolean> CACHE = new ConcurrentHashMap<>();

    private NoHitboxMobs() {
    }

    /** True only when the mob's WZ image exists and neither it nor its link has a hitbox ({@code lt}) anywhere. */
    public static boolean noHitbox(int mobId) {
        if (mobId <= 0) {
            return false;
        }
        return CACHE.computeIfAbsent(mobId, NoHitboxMobs::read);
    }

    private static boolean read(int mobId) {
        String own = image(mobId);
        if (own == null || own.contains("name=\"lt\"")) {
            return false;
        }
        Matcher link = LINK.matcher(own);
        if (link.find()) {
            String linked = image(Integer.parseInt(link.group(1)));
            if (linked == null || linked.contains("name=\"lt\"")) {
                return false;
            }
        }
        return true;
    }

    private static String image(int mobId) {
        try {
            Path file = WZFiles.MOB.getFile().resolve(String.format("%07d.img.xml", mobId));
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
