package soloMapling.menus;

/** Lets tests outside the package stand in for the NPC window. */
public final class MenuWindowTestAccess {

    private MenuWindowTestAccess() {
    }

    public static void fakeWindow() {
        MenuWindow.useForTest((p, npc) -> true, (task, delay) -> task.run());
    }

    public static void realWindow() {
        MenuWindow.useForTest(null, null);
    }
}
