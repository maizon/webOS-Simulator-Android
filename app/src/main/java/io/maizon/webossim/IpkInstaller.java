package io.maizon.webossim;

import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Installs only web assets from Debian-ar IPK archives; it never executes native libraries. */
public final class IpkInstaller {
    private static final long MAX_TOTAL_BYTES = 512L * 1024 * 1024;
    private static final long MAX_ENTRY_BYTES = 128L * 1024 * 1024;
    private static final int MAX_ENTRIES = 6000;
    private IpkInstaller() {}

    public static final class InstalledApp {
        public final String id, title, main, version;
        public final File directory;
        public InstalledApp(String id, String title, String main, String version, File directory) {
            this.id = id; this.title = title; this.main = main;
            this.version = version; this.directory = directory;
        }
    }

    private static class KeepOpen extends FilterInputStream {
        KeepOpen(InputStream in) { super(in); }
        @Override public void close() { /* ar input must remain open */ }
    }

    /** Extract an IPK payload for unit tests and installation. Caller controls the empty directory. */
    public static void extract(InputStream raw, File into) throws IOException {
        if (!into.isDirectory() && !into.mkdirs()) throw new IOException("Falha ao criar diretório");
        boolean foundData = false;
        try (ArArchiveInputStream ar = new ArArchiveInputStream(new BufferedInputStream(raw))) {
            ArArchiveEntry part;
            while ((part = ar.getNextEntry()) != null) {
                String name = part.getName().replace("/", "");
                if (name.equals("data.tar.gz")) {
                    if (foundData) throw new IOException("Payload duplicado");
                    foundData = true;
                    try (GzipCompressorInputStream gzip = new GzipCompressorInputStream(new KeepOpen(ar));
                         TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
                        extractTar(tar, into);
                    }
                } else if (name.equals("data.tar")) {
                    if (foundData) throw new IOException("Payload duplicado");
                    foundData = true;
                    try (TarArchiveInputStream tar = new TarArchiveInputStream(new KeepOpen(ar))) {
                        extractTar(tar, into);
                    }
                } else if (name.equals("data.tar.xz") || name.equals("data.tar.zst")) {
                    throw new IOException("Compressão IPK ainda não suportada: " + name);
                }
            }
        }
        if (!foundData) throw new IOException("IPK sem data.tar(.gz)");
    }

    private static void extractTar(TarArchiveInputStream tar, File base) throws IOException {
        TarArchiveEntry entry;
        long total = 0;
        int count = 0;
        while ((entry = tar.getNextEntry()) != null) {
            if (++count > MAX_ENTRIES) throw new IOException("IPK possui muitos arquivos");
            if (entry.isSymbolicLink() || entry.isLink() || entry.isCharacterDevice()
                    || entry.isBlockDevice() || entry.isFIFO()) {
                throw new IOException("IPK contém link ou dispositivo não permitido: " + entry.getName());
            }
            File target = safeResolve(base, entry.getName());
            if (entry.isDirectory()) {
                if (!target.isDirectory() && !target.mkdirs()) throw new IOException("Diretório inválido");
                continue;
            }
            if (!entry.isFile()) continue;
            if (entry.getSize() < 0 || entry.getSize() > MAX_ENTRY_BYTES
                    || total + entry.getSize() > MAX_TOTAL_BYTES) {
                throw new IOException("IPK excede limite de extração");
            }
            total += entry.getSize();
            File parent = target.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Caminho não permitido");
            try (FileOutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[32768];
                long remaining = entry.getSize();
                while (remaining > 0) {
                    int n = tar.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (n < 0) throw new EOFException("Arquivo truncado");
                    out.write(buffer, 0, n);
                    remaining -= n;
                }
            }
        }
    }

    static File safeResolve(File root, String name) throws IOException {
        if (name == null || name.indexOf('\\') >= 0 || name.indexOf((char) 0) >= 0
                || name.startsWith("/") || name.matches("^[A-Za-z]:.*")) {
            throw new IOException("Caminho absoluto ou inválido");
        }
        File base = root.getCanonicalFile();
        File dest = new File(root, name).getCanonicalFile();
        if (!dest.equals(base) && !dest.getPath().startsWith(base.getPath() + File.separator)) {
            throw new IOException("Tentativa de path traversal");
        }
        return dest;
    }

    public static InstalledApp install(InputStream raw, File storage) throws Exception {
        if (!storage.isDirectory() && !storage.mkdirs()) throw new IOException("Armazenamento indisponível");
        File staging = new File(storage, ".install-" + UUID.randomUUID());
        if (!staging.mkdirs()) throw new IOException("Falha ao criar área temporária");
        try {
            extract(raw, staging);
            File manifest = findManifest(staging, 0);
            if (manifest == null) throw new IOException("IPK sem appinfo.json");
            File appRoot = manifest.getParentFile();
            JSONObject info = new JSONObject(new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8));
            String id = info.optString("id", "");
            if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || id.startsWith(".")) {
                throw new IOException("ID inválido em appinfo.json");
            }
            String main = info.optString("main", "index.html");
            if (main.contains("?") || main.contains("#") || main.startsWith("http:") || main.startsWith("https:")) {
                throw new IOException("Entrada remota ou inválida (main)");
            }
            File index = safeResolve(appRoot, main);
            if (!index.isFile()) throw new IOException("Arquivo main não encontrado: " + main);
            File destination = new File(storage, id);
            if (destination.exists()) throw new IOException("Aplicativo já instalado; desinstale antes de atualizar");
            Files.move(appRoot.toPath(), destination.toPath());
            String title = info.optString("title", id);
            return new InstalledApp(id, title, main, info.optString("version", "?"), destination);
        } finally { deleteTree(staging); }
    }

    private static File findManifest(File dir, int depth) {
        if (depth > 9) return null;
        File direct = new File(dir, "appinfo.json");
        if (direct.isFile()) return direct;
        File[] children = dir.listFiles(File::isDirectory);
        if (children != null) {
            for (File child : children) {
                File found = findManifest(child, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    public static List<InstalledApp> installed(File storage) {
        List<InstalledApp> apps = new ArrayList<>();
        File[] dirs = storage.listFiles(File::isDirectory);
        if (dirs == null) return apps;
        for (File dir : dirs) {
            if (dir.getName().startsWith(".")) continue;
            try {
                JSONObject info = new JSONObject(new String(Files.readAllBytes(new File(dir, "appinfo.json").toPath()), StandardCharsets.UTF_8));
                String id = info.optString("id", "");
                if (!id.equals(dir.getName())) continue;
                String main = info.optString("main", "index.html");
                if (!safeResolve(dir, main).isFile()) continue;
                apps.add(new InstalledApp(id, info.optString("title", id), main, info.optString("version", "?"), dir));
            } catch (Exception ignored) { }
        }
        apps.sort((a,b) -> a.title.toLowerCase(Locale.ROOT).compareTo(b.title.toLowerCase(Locale.ROOT)));
        return apps;
    }

    public static void deleteTree(File root) {
        if (root == null || !root.exists()) return;
        File[] children = root.listFiles();
        if (children != null) for (File c : children) deleteTree(c);
        // Intentionally delete only paths belonging to our application storage.
        root.delete();
    }
}
