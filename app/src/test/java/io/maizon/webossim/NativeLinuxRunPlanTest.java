package io.maizon.webossim;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import static org.junit.Assert.*;

public final class NativeLinuxRunPlanTest {
    @Rule public final TemporaryFolder tmp = new TemporaryFolder();

    @Test public void binaryPathIsArgumentNeverInterpolatedInShell() throws Exception {
        File app = tmp.newFolder("sample");
        File game = new File(app, "My Game;echo untrusted.arm64");
        Files.write(game.toPath(), new byte[] {127, 'E', 'L', 'F'});
        List<String> cmd = NativeLinuxRunPlan.app("/native/libmaizon_proot.so",
                "/rootfs", app, game.getName());
        assertEquals("/native/libmaizon_proot.so", cmd.get(0));
        assertTrue(cmd.contains("--rootfs=/rootfs"));
        assertTrue(cmd.contains("--bind=" + app.getCanonicalPath() + ":/app"));
        assertEquals("/app/My Game;echo untrusted.arm64", cmd.get(cmd.size() - 1));
        assertFalse(cmd.get(cmd.size() - 3).contains(game.getName()));
        assertEquals("maizon", cmd.get(cmd.size() - 2));
    }

    @Test public void guestEntryRejectsTraversalAndUnknownFiles() throws Exception {
        File root = tmp.newFolder("apps");
        for (String entry : new String[]{"../outside", "/bin/sh", "missing.elf", "a/../../bad"}) {
            try {
                NativeLinuxRunPlan.guestPath(root, entry);
                fail("Should reject " + entry);
            } catch (IOException expected) { /* safe */ }
        }
        Files.write(new File(root, "native").toPath(), new byte[]{0});
        assertEquals("/app/native", NativeLinuxRunPlan.guestPath(root, "native"));
    }

    @Test public void nativeProbeRunsInsideGuestAndKeepsAndroidAbiSeparate() {
        List<String> cmd = NativeLinuxRunPlan.probe("/native/libmaizon_proot.so", "/linux-root");
        assertEquals("/bin/maizon-probe", cmd.get(cmd.size() - 1));
        assertFalse(cmd.toString().contains("--bind=/app"));
        assertTrue(cmd.contains("--link2symlink"));
        assertTrue(cmd.contains("--rootfs=/linux-root"));
    }

    @Test public void disallowInvalidRuntimeParameters() {
        for (String p : new String[]{"", null}) {
            try {
                NativeLinuxRunPlan.command(p, "/guest", null, "/bin/maizon-probe");
                fail("Expected invalid host path");
            } catch (IllegalArgumentException expected) { /* safe */ }
        }
    }
}
