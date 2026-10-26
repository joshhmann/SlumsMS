package soloMapling.Economy.board;

import soloMapling.server.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.function.Consumer;

/**
 * Writes the outbox files for the site. On 164 the outbox is {@code root:slums-market}, mode 2750 (setgid), so a new
 * file takes the group from the folder: the temp file is made inside the outbox itself and renamed into place, mode
 * 0640 is set explicitly (the umask may differ), and nothing is chowned or chgrp'd. A missing folder is created
 * (its group is the install step's job).
 */
public final class BoardFiles {

    public static final String MODE = "rw-r-----";
    /** Tests see each temp file's path. */
    static volatile Consumer<Path> onTemp;

    private BoardFiles() {
    }

    public static void writeJson(Path file, Object json) throws IOException {
        write(file, Json.write(json));
    }

    public static void write(Path file, String content) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "." + file.getFileName(), ".tmp");
        Consumer<Path> hook = onTemp;
        if (hook != null) {
            hook.accept(tmp);
        }
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString(MODE));
            } catch (UnsupportedOperationException e) {
                // not a POSIX file system
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
