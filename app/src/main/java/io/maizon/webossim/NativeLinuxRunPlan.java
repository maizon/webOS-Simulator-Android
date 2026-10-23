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
            args.add("LD_LIBRARY_PATH=/app/lib:/app/usr/lib:/webos/lib:/webos/usr/lib:/lib:/usr/lib:/usr/local/lib; "
                    + "export LD_LIBRARY_PATH; exec \"$1\"");
            args.add("maizon");
            args.add(guest);
        } else {
            args.add(guest);
        }
        return Collections.unmodifiableList(args);
    }
}
