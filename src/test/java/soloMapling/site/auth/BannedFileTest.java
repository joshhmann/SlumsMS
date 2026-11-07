package soloMapling.site.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import tools.DatabaseConnection;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 1.6.5 P0-7: banned.txt for the site login service. */
class BannedFileTest {
    @AfterEach
    void clearProps() {
        System.clearProperty("slums.auth.revoked");
        System.clearProperty("slums.weblogin.dir");
    }

    private static String read(Path dir) throws Exception {
        return Files.readString(dir.resolve("banned.txt"), StandardCharsets.US_ASCII);
    }

    @Test
    void writesTheIdsSortedOnePerLineAndAnUnbanDropsTheId() throws Exception {
        assertTrue(BannedFile.write(Set.of(30, 7, 12), tmp));
        assertEquals("7\n12\n30\n", read(tmp));
        assertTrue(BannedFile.write(Set.of(30, 7), tmp));
        assertEquals("7\n30\n", read(tmp));
        assertTrue(BannedFile.write(Set.of(), tmp));
        assertEquals("", read(tmp));
    }

    @TempDir
    Path tmp;

    @Test
    void neverTouchesRevokedTxtOrAnyOtherFileInTheFolder() throws Exception {
        byte[] revoked = "5\n9\n".getBytes(StandardCharsets.US_ASCII);
        byte[] other = new byte[]{0, 1, 2, (byte) 0xff};
        Files.write(tmp.resolve("revoked.txt"), revoked);
        Files.write(tmp.resolve("other.dat"), other);
        BannedFile.write(Set.of(1, 2), tmp);
        BannedFile.write(Set.of(2), tmp);
        BannedFile.write(Set.of(), tmp);
        assertTrue(java.util.Arrays.equals(revoked, Files.readAllBytes(tmp.resolve("revoked.txt"))));
        assertTrue(java.util.Arrays.equals(other, Files.readAllBytes(tmp.resolve("other.dat"))));
        try (Stream<Path> s = Files.list(tmp)) {
            assertEquals(Set.of("revoked.txt", "other.dat", "banned.txt"),
                    s.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
        }
    }

    @Test
    void renameReplacesTheFileAndLeavesNoTempBehind() throws Exception {
        BannedFile.write(Set.of(1), tmp);
        Object first = Files.readAttributes(tmp.resolve("banned.txt"), "unix:ino").get("ino");
        BannedFile.write(Set.of(1, 2), tmp);
        Object second = Files.readAttributes(tmp.resolve("banned.txt"), "unix:ino").get("ino");
        assertFalse(first.equals(second), "a new file is renamed over the old one");
        try (Stream<Path> s = Files.list(tmp)) {
            assertEquals(List.of("banned.txt"), s.map(p -> p.getFileName().toString()).toList());
        }
        assertEquals("rw-r--r--", java.nio.file.attribute.PosixFilePermissions.toString(
                Files.getPosixFilePermissions(tmp.resolve("banned.txt"))));
    }

    @Test
    void unchangedContentSkipsTheRename() throws Exception {
        assertTrue(BannedFile.write(Set.of(4, 8), tmp));
        Object ino = Files.readAttributes(tmp.resolve("banned.txt"), "unix:ino").get("ino");
        assertFalse(BannedFile.write(Set.of(8, 4), tmp));
        assertEquals(ino, Files.readAttributes(tmp.resolve("banned.txt"), "unix:ino").get("ino"));
    }

    @Test
    void folderIsTheParentOfRevokedTxtAndOffWhenUnset() {
        assertEquals(Path.of("/var/lib/slums-login"), BannedFile.directory("/var/lib/slums-login/revoked.txt", "/x"));
        assertEquals(Path.of("/x/approvals"), BannedFile.directory(null, "/x/approvals"));
        assertNull(BannedFile.directory(null, null));
    }

    @Test
    void noFolderConfiguredWritesNothingAndDoesNotThrow() throws Exception {
        try (MockedStatic<DatabaseConnection> db = Mockito.mockStatic(DatabaseConnection.class)) {
            BannedFile.sweep();
            db.verifyNoInteractions();
        }
        try (Stream<Path> s = Files.list(tmp)) {
            assertEquals(0, s.count());
        }
    }

    private static Connection connectionWithIds(int... ids) throws Exception {
        Connection con = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(con.prepareStatement(any())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        int[] at = {-1};
        when(rs.next()).thenAnswer(i -> ++at[0] < ids.length);
        when(rs.getInt(1)).thenAnswer(i -> ids[at[0]]);
        return con;
    }

    @Test
    void theQueryAsksForBannedOrAFutureTempbanAtTheGivenInstant() throws Exception {
        Connection con = connectionWithIds(12, 3);
        PreparedStatement ps = con.prepareStatement("x");
        Set<Integer> ids = BannedFile.queryBannedIds(con, 1_700_000_000_000L);
        assertEquals(Set.of(3, 12), ids);
        verify(con).prepareStatement(BannedFile.BANNED_IDS_SQL);
        verify(ps).setTimestamp(eq(1), eq(new Timestamp(1_700_000_000_000L)));
        assertTrue(BannedFile.BANNED_IDS_SQL.contains("banned = 1"));
        assertTrue(BannedFile.BANNED_IDS_SQL.contains("tempban > ?"), "expired temp bans (and the 2005 default) drop out");
    }

    @Test
    void aSweepWritesTheBannedAccountsAndLeavesRevokedTxtAlone() throws Exception {
        Path revoked = tmp.resolve("revoked.txt");
        Files.writeString(revoked, "77\n");
        System.setProperty("slums.auth.revoked", revoked.toString());
        Connection con = connectionWithIds(42, 8);
        try (MockedStatic<DatabaseConnection> db = Mockito.mockStatic(DatabaseConnection.class)) {
            db.when(DatabaseConnection::getConnection).thenReturn(con);
            BannedFile.sweep();
        }
        assertEquals("8\n42\n", read(tmp));
        assertEquals("77\n", Files.readString(revoked));
    }

    @Test
    void anUnwritableFolderIsNotAnException() throws Exception {
        Connection con = connectionWithIds(5);
        System.setProperty("slums.auth.revoked", tmp.resolve("missing-dir/revoked.txt").toString());
        try (MockedStatic<DatabaseConnection> db = Mockito.mockStatic(DatabaseConnection.class)) {
            db.when(DatabaseConnection::getConnection).thenReturn(con);
            BannedFile.sweep();
        }
        assertFalse(Files.exists(tmp.resolve("missing-dir")));
    }
}
