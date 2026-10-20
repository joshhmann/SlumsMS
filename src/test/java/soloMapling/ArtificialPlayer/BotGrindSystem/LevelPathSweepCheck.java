package soloMapling.ArtificialPlayer.BotGrindSystem;

/*
 * Dev tool, not a unit test (ticket 31 / ticket 03 follow-up): runs the `!env path sweep` logic offline against a
 * real WZ tree - per level band and rebirth count, the usable path maps a bot can reach from the start map - and
 * prints the maps no route reaches at rebirth 3. Static WZ + nav data only, like the live command.
 *
 * Mob levels are read straight from Mob.wz (LifeFactory wants the drop database, which a dev box doesn't have).
 *
 *   java -cp <jar>:<test-classes> -Dwz-path=wz soloMapling.ArtificialPlayer.BotGrindSystem.LevelPathSweepCheck [fromMap]
 */
public final class LevelPathSweepCheck {

    private LevelPathSweepCheck() {
    }

    public static void main(String[] args) {
        int from = args.length > 0 ? Integer.parseInt(args[0]) : constants.id.MapId.HENESYS;
        long t0 = System.nanoTime();
        preloadMobLevels();
        for (String line : LevelingPathChooser.sweep(from)) {
            System.out.println(line);
        }
        System.out.printf("[LevelPathSweep] done in %d ms%n", (System.nanoTime() - t0) / 1_000_000);
    }

    // Fills MapMobIndex's mob-level cache from Mob.wz info/level so it never asks LifeFactory.
    @SuppressWarnings("unchecked")
    private static void preloadMobLevels() {
        try {
            java.lang.reflect.Field f = MapMobIndex.class.getDeclaredField("MOB_LEVEL");
            f.setAccessible(true);
            java.util.Map<Integer, Integer> levels = (java.util.Map<Integer, Integer>) f.get(null);
            provider.DataProvider mobs = provider.DataProviderFactory.getDataProvider(provider.wz.WZFiles.MOB);
            java.nio.file.Path dir = java.nio.file.Path.of(provider.wz.WZFiles.MOB.getFilePath());
            try (var files = java.nio.file.Files.list(dir)) {
                for (java.nio.file.Path file : (Iterable<java.nio.file.Path>) files::iterator) {
                    String name = file.getFileName().toString();
                    if (!name.endsWith(".img.xml")) {
                        continue;
                    }
                    try {
                        int id = Integer.parseInt(name.substring(0, name.length() - ".img.xml".length()));
                        provider.Data info = mobs.getData(name.substring(0, name.length() - 4)).getChildByPath("info");
                        int level = info == null ? -1 : provider.DataTool.getInt("level", info, -1);
                        if (level <= 0 && info != null) {
                            String link = provider.DataTool.getString("link", info, "");
                            if (!link.isEmpty()) {
                                provider.Data linked = mobs.getData(String.format("%07d.img", Integer.parseInt(link)));
                                provider.Data li = linked == null ? null : linked.getChildByPath("info");
                                level = li == null ? -1 : provider.DataTool.getInt("level", li, -1);
                            }
                        }
                        levels.put(id, level);
                    } catch (RuntimeException ignored) {
                        // not a mob file
                    }
                }
            }
        } catch (ReflectiveOperationException | java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
