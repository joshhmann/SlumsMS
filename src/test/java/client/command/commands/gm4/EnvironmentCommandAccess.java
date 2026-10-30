package client.command.commands.gm4;

import java.util.List;

/** Test access to EnvironmentCommand's package-private helpers from other packages. */
public final class EnvironmentCommandAccess {
    private EnvironmentCommandAccess() {
    }

    public static List<String> lod(String[] params, boolean enabled) {
        return EnvironmentCommand.lodPinCommand(params, enabled);
    }
}
