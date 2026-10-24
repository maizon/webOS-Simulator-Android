package io.maizon.webossim;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Deterministic argv builder: no sh -c concatenation with user filenames.
 * PRoot is NOT a security boundary: the Linux program retains Android app UID.
 */
public final class NativeLinuxRunPlan {
    private NativeLinuxRunPlan() {}

    public static String guestPath(File appRoot, String relative) throws IOException {
        if (relative == null || relative.isEmpty()) throw new IOException("Missing native entry");
        File file = IpkInstaller.safeResolve(appRoot, relative);
        if (!file.isFile()) throw new IOException("Native ELF file not found: " + relative);
        return "/app/" + relative.replace(File.separatorChar, '/');
    }

    /**
     * Select Linux userspace based on guest ELF, not Android host ABI.
     * Particularly important when a 64-bit PRoot host launches an ARM32 game.
     * The 32-bit loader also requires AArch32 support in the device kernel.
     */
    public static String guestAbi(String hostAbi, String architecture, boolean loader32)
            throws IOException {
        if ("ELF64 AArch64".equals(architecture)) {
            if ("arm64-v8a".equals(hostAbi)) return "arm64-v8a";
            throw new IOException("ELF64 AArch64 precisa de host Android ARM64; QEMU ausente.");
        }
        if ("ELF32 ARM".equals(architecture)) {
            if ("armeabi-v7a".equals(hostAbi)) return "armeabi-v7a";
            if ("arm64-v8a".equals(hostAbi) && loader32) return "armeabi-v7a";
            throw new IOException("ELF32 ARM precisa do loader32, kernel AArch32 ou QEMU; "
                    + "este aparelho não tem compatibilidade confirmada.");
        }
        throw new IOException("ELF sem suporte para execução nativa: " + architecture +
                ". QEMU-user de outras arquiteturas não está integrado.");
    }

    public static List<String> probe(String hostBinary, String rootfs) {
        return command(hostBinary, rootfs, null, "/bin/maizon-probe");
    }

    public static List<String> app(String hostBinary, String rootfs, File appRoot,
                                   String relative) throws IOException {
        return command(hostBinary, rootfs, appRoot.getCanonicalPath(),
                guestPath(appRoot, relative));
    }

    static List<String> command(String hostBinary, String rootfs, String appPath, String guest) {
        if (hostBinary == null || hostBinary.isEmpty() || rootfs == null || rootfs.isEmpty()
                || guest == null || !guest.startsWith("/") || guest.indexOf((char) 0) >= 0)
            throw new IllegalArgumentException("invalid proot command");
        List<String> args = new ArrayList<>();
        args.add(hostBinary);
        args.add("--kill-on-exit");
        args.add("--link2symlink");
        args.add("--sysvipc");
        args.add("-L");
        args.add("-0");
        args.add("--rootfs=" + rootfs);
        args.add("--cwd=" + (appPath == null ? "/" : "/app"));
        if (appPath != null) {
            args.add("--bind=" + appPath + ":/app");
            File originalPayload = new File(appPath, ".webos-package");
            if (originalPayload.isDirectory()) {
                args.add("--bind=" + originalPayload.getAbsolutePath() + ":/webos");
            }
        }
        args.add("--bind=/dev:/dev");
        args.add("--bind=/proc:/proc");
        // BusyBox is static Linux ELF. Its ash shell sets only GUEST library
        // paths and treats the target strictly as argv, not executable shell text.
        if (appPath != null) {
            args.add("/bin/busybox");
            args.add("sh");
            args.add("-c");
            args.add("if [ ! -e \"$1\" ]; then "
                    + "echo 'MAIZON_GUEST_PATH_MISSING: executável não está visível dentro de /app' >&2; "
                    + "exit 127; fi; "
                    + "LD_LIBRARY_PATH=/app/lib:/app/usr/lib:/webos/lib:/webos/usr/lib:/lib:/usr/lib:/usr/local/lib; "
                    + "export LD_LIBRARY_PATH; exec \"$1\"");
            args.add("maizon");
            args.add(guest);
        } else {
            args.add(guest);
        }
        return Collections.unmodifiableList(args);
    }
}
