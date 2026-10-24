package io.maizon.webossim;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public final class ElfProgramInspectorTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    private File elf(int bits, String interp) throws Exception {
        byte[] bytes = new byte[256];
        bytes[0] = 0x7f; bytes[1] = 'E'; bytes[2] = 'L'; bytes[3] = 'F';
        bytes[4] = (byte) (bits == 32 ? 1 : 2);
        bytes[5] = 1; // Little-endian Linux ARM
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort(18, (short) (bits == 32 ? 40 : 183));
        int phoff = bits == 32 ? 52 : 64;
        if (bits == 32) {
            buf.putInt(28, phoff);
            buf.putShort(42, (short) 32);
            buf.putShort(44, (short) (interp == null ? 0 : 1));
        } else {
            buf.putLong(32, phoff);
            buf.putShort(54, (short) 56);
            buf.putShort(56, (short) (interp == null ? 0 : 1));
        }
        if (interp != null) {
            byte[] name = interp.getBytes(StandardCharsets.UTF_8);
            buf.putInt(phoff, 3); // PT_INTERP
            if (bits == 32) {
                buf.putInt(phoff + 4, 160);
                buf.putInt(phoff + 16, name.length + 1);
            } else {
                buf.putLong(phoff + 8, 160);
                buf.putLong(phoff + 32, name.length + 1);
            }
            System.arraycopy(name, 0, bytes, 160, name.length);
        }
        File file = folder.newFile("elf-" + bits + "-" + System.nanoTime());
        Files.write(file.toPath(), bytes);
        return file;
    }

    @Test public void readsArm32DynamicLinkerAndExplainsNotFoundForMissingLoader()
            throws Exception {
        File game = elf(32, "/lib/ld-linux-armhf.so.3");
        assertEquals("/lib/ld-linux-armhf.so.3", ElfProgramInspector.interpreter(game));
        File sysroot = folder.newFolder("armhf");
        assertFalse("Binary exists but missing PT_INTERP causes ENOENT",
                ElfProgramInspector.interpreterPresent(sysroot,
                        ElfProgramInspector.interpreter(game)));
        File lib = new File(sysroot, "lib");
        assertTrue(lib.mkdirs());
        Files.write(new File(lib, "ld-linux-armhf.so.3").toPath(), new byte[] { 1 });
        assertTrue(ElfProgramInspector.interpreterPresent(sysroot,
                ElfProgramInspector.interpreter(game)));
    }

    @Test public void readsArm64DynamicLinker() throws Exception {
        File game = elf(64, "/lib/ld-linux-aarch64.so.1");
        assertEquals("/lib/ld-linux-aarch64.so.1", ElfProgramInspector.interpreter(game));
    }

    @Test public void staticElfRequiresNoInterpreter() throws Exception {
        File binary = elf(32, null);
        assertNull(ElfProgramInspector.interpreter(binary));
        assertTrue(ElfProgramInspector.interpreterPresent(folder.getRoot(), null));
    }

    @Test public void detectsMalformedAndUnsafePhdrs() throws Exception {
        File game = elf(32, "/lib/ld-linux-armhf.so.3");
        byte[] malformed = Files.readAllBytes(game.toPath());
        ByteBuffer.wrap(malformed).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(52 + 4, 999999); // PT_INTERP beyond EOF
        Files.write(game.toPath(), malformed);
        try {
            ElfProgramInspector.interpreter(game);
            fail("Expected bounds validation");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("PT_INTERP"));
        }
    }

    @Test public void guestArchitectureMustMatchElfNotHostPRoot() throws Exception {
        assertEquals("armeabi-v7a",
                NativeLinuxRunPlan.guestAbi("arm64-v8a", "ELF32 ARM", true));
        assertEquals("arm64-v8a",
                NativeLinuxRunPlan.guestAbi("arm64-v8a", "ELF64 AArch64", true));
        assertEquals("armeabi-v7a",
                NativeLinuxRunPlan.guestAbi("armeabi-v7a", "ELF32 ARM", false));
        try {
            NativeLinuxRunPlan.guestAbi("arm64-v8a", "ELF32 ARM", false);
            fail("Missing 32-bit loader must block");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("loader32"));
        }
        try {
            NativeLinuxRunPlan.guestAbi("arm64-v8a", "ELF64 x86_64", true);
            fail("Missing QEMU cannot claim x86 support");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("QEMU"));
        }
    }
}
