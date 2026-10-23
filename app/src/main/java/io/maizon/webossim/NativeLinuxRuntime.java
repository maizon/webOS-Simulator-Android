package io.maizon.webossim;

import android.content.Context;
import android.os.Build;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Experimental Linux ELF runtime driven by separately packaged PRoot.
 * The Linux guest shares the Android application's UID; it is NOT secure
 * isolation against malicious IPKs. Requires user confirmation for each run.
 *
 * This class is deliberately independent of WebView, MaizoSNES UDP/WSS,
 * JS/Luna services, and all game renderers.
 */
public final class NativeLinuxRuntime {
    private static final String VERSION = "v1";
    private static final long ROOTFS_LIMIT = 140L * 1024 * 1024;
    private static final int FILE_LIMIT = 20000;
    private static final int OUTPUT_LIMIT = 96 * 1024;

    public static final class Result {
        public final int code;
        public final boolean timedOut;
        public final String output;
        public final String mode;
        Result(int code, boolean timedOut, String output, String mode) {
            this.code = code; this.timedOut = timedOut; this.output = output; this.mode = mode;
        }
        public boolean ok() { return code == 0 && !timedOut; }
        public String message() {
            return "Runtime: " + mode + "\nExit code: " + code +
                    (timedOut ? "\nTempo máximo atingido." : "") +
                    "\n\n" + (output.isEmpty() ? "(Sem saída no terminal)" : output);
        }
    }

    private final Context context;
    private volatile Process running;
    public NativeLinuxRuntime(Context context) { this.context = context.getApplicationContext(); }

    public static String hostAbi(Context context) {
        String nativePath = context.getApplicationInfo().nativeLibraryDir;
        if (nativePath == null) return "";
        File libdir = new File(nativePath);
        if (!new File(libdir, "libmaizon_proot.so").isFile()) return "";
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi) || "armeabi-v7a".equals(abi)) return abi;
        }
        return "";
    }

    public static String support(Context context) {
        String abi = hostAbi(context);
        if (abi.isEmpty()) return "Motor PRoot não foi empacotado para a ABI deste dispositivo.";
        File libdir = new File(context.getApplicationInfo().nativeLibraryDir);
        boolean loader = new File(libdir, "libmaizon_loader.so").isFile();
        return abi + " · PRoot " + (loader ? "instalado (experimental)" : "loader ausente")
                + " · Linux guest Debian + BusyBox";
    }

    /** Returns an explanatory warning for unavailable CPU architectures. */
    public static String architectureBlocker(Context context, String elfArch) {
        String host = hostAbi(context);
        if (host.isEmpty()) return "Runtime nativo indisponível na ABI Android atual.";
        if (elfArch == null || elfArch.startsWith("ELF desconhecido")) return "Cabeçalho ELF não reconhecido.";
        if (elfArch.contains("AArch64")) {
            return "arm64-v8a".equals(host) ? null
                    : "O ELF Linux ARM64 requer um aparelho Android ARM64.";
        }
        if (elfArch.contains("ARM")) {
            if ("armeabi-v7a".equals(host)) return null;
            if ("arm64-v8a".equals(host) && Build.SUPPORTED_32_BIT_ABIS.length > 0) {
                File loader = new File(context.getApplicationInfo().nativeLibraryDir,
                        "libmaizon_loader32.so");
                return loader.isFile() ? null : "PRoot ARM32 loader não foi empacotado.";
            }
            return "Este Android ARM64 não oferece execução ARM32. QEMU-user ainda não está integrado.";
        }
        return "Arquitetura " + elfArch + " não suportada pelo motor ARM atual; QEMU-user ausente.";
    }

    private File runtimeRoot(String abi) throws IOException {
        synchronized (NativeLinuxRuntime.class) {
            File parent = new File(context.getFilesDir(), "maizon-native-runtime");
            if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Armazenamento nativo indisponível");
            File root = new File(parent, VERSION + "-" + abi);
            File marker = new File(root, ".ready");
            if (marker.isFile() && new File(root, "bin/maizon-probe").isFile()
                    && new File(root, "bin/busybox").isFile()) return root;
            if (root.exists()) IpkInstaller.deleteTree(root);
            File staging = new File(parent, ".unpack-" + abi + "-" + System.nanoTime());
            if (!staging.mkdirs()) throw new IOException("Falha na área temporária Linux");
            try {
                extractOfficialSysroot(staging, abi);
                if (!new File(staging, "bin/maizon-probe").isFile() ||
                        !new File(staging, "bin/busybox").isFile())
                    throw new IOException("Sysroot Linux incompleto no APK");
                if (!Files.move(staging.toPath(), root.toPath()).toFile().isDirectory())
                    throw new IOException("Não foi possível finalizar a instalação Linux");
                if (!marker.createNewFile()) throw new IOException("Falha ao finalizar rootfs");
                return root;
            } finally {
                if (staging.exists()) IpkInstaller.deleteTree(staging);
            }
        }
    }

    private static final class Link {
        final File file;
        final File source;
        final boolean hard;
        Link(File file, File source, boolean hard) {
            this.file = file; this.source = source; this.hard = hard;
        }
    }

    private void extractOfficialSysroot(File destination, String abi) throws IOException {
        String asset = "native-linux/" + abi + "-rootfs.tar.gz";
        List<Link> links = new ArrayList<>();
        long bytes = 0;
        int entries = 0;
        try (InputStream input = context.getAssets().open(asset);
             GzipCompressorInputStream gzip = new GzipCompressorInputStream(
                     new BufferedInputStream(input));
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (++entries > FILE_LIMIT) throw new IOException("Limite de arquivos no rootfs excedido");
                String name = entry.getName();
                while (name.startsWith("./")) name = name.substring(2);
                if (name.isEmpty() || ".".equals(name)) continue;
                File target = IpkInstaller.safeResolve(destination, name);
                if (entry.isCharacterDevice() || entry.isBlockDevice() || entry.isFIFO())
                    throw new IOException("Rootfs contém dispositivo não permitido");
                if (entry.isDirectory()) {
                    if (!target.isDirectory() && !target.mkdirs()) throw new IOException("Falha ao criar: " + name);
                    continue;
                }
                File parent = target.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs())
                    throw new IOException("Falha ao criar diretório: " + name);
                if (entry.isSymbolicLink() || entry.isLink()) {
                    String linkName = entry.getLinkName();
                    if (linkName == null || linkName.isEmpty()) throw new IOException("Link vazio no rootfs");
                    File source = linkName.startsWith("/")
                            ? new File(destination, linkName.substring(1))
                            : (entry.isLink()
                                ? new File(destination, linkName)
                                : new File(parent, linkName));
                    File safe = source.getCanonicalFile();
                    String base = destination.getCanonicalPath() + File.separator;
                    if (!safe.getPath().startsWith(base))
                        throw new IOException("Link fora do rootfs: " + linkName);
                    links.add(new Link(target, safe, entry.isLink()));
                    continue;
                }
                if (!entry.isFile()) continue;
                long size = entry.getSize();
                if (size < 0 || size > 50L * 1024 * 1024 ||
                        bytes + size > ROOTFS_LIMIT)
                    throw new IOException("Rootfs comprimido excede limites de extração");
                bytes += size;
                try (FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buf = new byte[32768];
                    long remain = size;
                    while (remain > 0) {
                        int n = tar.read(buf, 0, (int) Math.min(buf.length, remain));
                        if (n <= 0) throw new IOException("Rootfs incompleto");
                        out.write(buf, 0, n);
                        remain -= n;
                    }
                }
                target.setReadable(true, false);
                // These files are mapped by PRoot's loader, even when Android's
                // private app files are on a noexec partition.
                if ((entry.getMode() & 0111) != 0) target.setExecutable(true, true);
            }
        }
        for (Link link : links) {
            if (link.file.exists()) continue;
            if (!link.file.getParentFile().isDirectory() &&
                    !link.file.getParentFile().mkdirs()) throw new IOException("Link parent missing");
            if (link.hard) {
                if (!link.source.isFile()) throw new IOException("Hard link missing: " + link.source);
                Files.copy(link.source.toPath(), link.file.toPath());
            } else {
                Path relative = link.file.getParentFile().toPath().relativize(link.source.toPath());
                Files.createSymbolicLink(link.file.toPath(), relative);
            }
        }
    }

    private File executable(String name) throws IOException {
        File nativeLibs = new File(context.getApplicationInfo().nativeLibraryDir);
        File path = new File(nativeLibs, name);
        if (!path.isFile()) throw new IOException("Runtime nativo ausente: " + name);
        return path;
    }

    /** ProcessBuilder is fed an argv vector: imported IPK filenames never become shell code. */
    private Result execute(List<String> argv, String mode, long timeoutMs) throws IOException {
        if (timeoutMs < 1000 || timeoutMs > 5 * 60 * 1000)
            throw new IllegalArgumentException("Invalid Linux timeout");
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.redirectErrorStream(true);
        pb.directory(context.getFilesDir());
        String libdir = context.getApplicationInfo().nativeLibraryDir;
        pb.environment().remove("LD_PRELOAD");
        pb.environment().remove("LD_LIBRARY_PATH");
        pb.environment().put("PROOT_LOADER", executable("libmaizon_loader.so").getAbsolutePath());
        File loader32 = new File(libdir, "libmaizon_loader32.so");
        if (loader32.isFile()) pb.environment().put("PROOT_LOADER_32", loader32.getAbsolutePath());
        File scratch = new File(context.getCacheDir(), "proot");
        if (!scratch.isDirectory() && !scratch.mkdirs()) throw new IOException("PROOT_TMP_DIR missing");
        pb.environment().put("PROOT_TMP_DIR", scratch.getAbsolutePath());
        pb.environment().put("PATH", "/system/bin");
        pb.environment().put("ANDROID_ROOT", "/system");
        pb.environment().put("ANDROID_DATA", "/data");
        Process process = pb.start();
        running = process;
        try {
            process.getOutputStream().close();
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (InputStream stream = process.getInputStream()) {
                    byte[] buf = new byte[2048];
                    int n;
                    while ((n = stream.read(buf)) != -1) {
                        synchronized (captured) {
                            int allowed = Math.max(0, OUTPUT_LIMIT - captured.size());
                            if (allowed > 0) captured.write(buf, 0, Math.min(allowed, n));
                        }
                    }
                } catch (IOException ignored) { }
            }, "maizon-native-stdout");
            reader.setDaemon(true);
            reader.start();
            boolean completed;
            try {
                completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IOException("Processo Linux interrompido", e);
            }
            if (!completed) process.destroyForcibly();
            try { reader.join(1400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            int code = completed ? process.exitValue() : -1;
            String output;
            synchronized (captured) {
                output = new String(captured.toByteArray(), StandardCharsets.UTF_8);
                if (captured.size() == OUTPUT_LIMIT) output += "\n[Saída truncada após 96 KiB]";
            }
            return new Result(code, !completed, output, mode);
        } finally {
            if (running == process) running = null;
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    public Result probe() throws IOException {
        String abi = hostAbi(context);
        if (abi.isEmpty()) throw new IOException("Este aparelho não possui motor Linux ARM nativo");
        File proot = executable("libmaizon_proot.so");
        File root = runtimeRoot(abi);
        return execute(NativeLinuxRunPlan.probe(proot.getAbsolutePath(), root.getCanonicalPath()),
                "PRoot " + abi + " · Linux ELF controlado", 20_000);
    }

    public Result launch(IpkInstaller.InstalledApp app) throws IOException {
        String path = app.main;
        File check = IpkInstaller.safeResolve(app.directory, path);
        if (!check.isFile() || !isELF(check)) path = app.runtime.examplePath;
        if (path == null || path.isEmpty())
            throw new IOException("Nenhum ELF identificado dentro deste IPK");
        check = IpkInstaller.safeResolve(app.directory, path);
        String elf = readElfArchitecture(check);
        String blocker = architectureBlocker(context, elf);
        if (blocker != null) throw new IOException(blocker);
        File proot = executable("libmaizon_proot.so");
        String abi = hostAbi(context);
        File root = runtimeRoot(abi);
        return execute(NativeLinuxRunPlan.app(proot.getAbsolutePath(), root.getCanonicalPath(),
                        app.directory, path), "Linux ELF " + elf + " via PRoot " + abi, 120_000);
    }

    private static boolean isELF(File file) {
        return readElfArchitecture(file) != null;
    }
    public static String readElfArchitecture(File file) {
        try (FileInputStream stream = new FileInputStream(file)) {
            byte[] buf = new byte[64];
            int read = stream.read(buf);
            return NativeIpkInspector.elfArchitecture(buf, read);
        } catch (IOException ignored) { return null; }
    }

    public void stop() {
        Process process = running;
        if (process != null) process.destroyForcibly();
    }
}
