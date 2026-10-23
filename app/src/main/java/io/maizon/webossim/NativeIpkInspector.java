package io.maizon.webossim;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Passive inspection of packaged applications. Never executes ELF or shell code.
 * Uses file headers, not filename extensions alone, to identify native binaries.
 */
public final class NativeIpkInspector {
    public enum Kind { WEB, HYBRID, NATIVE }
    public static final class Report {
        public final Kind kind;
        public final boolean webEntry;
        public final String architecture;
        public final String examplePath;
        public final int elfCount;
        public final boolean scanLimited;

        Report(Kind kind, boolean webEntry, String architecture,
               String examplePath, int elfCount, boolean scanLimited) {
            this.kind = kind;
            this.webEntry = webEntry;
            this.architecture = architecture;
            this.examplePath = examplePath;
            this.elfCount = elfCount;
            this.scanLimited = scanLimited;
        }

        public String title() {
            switch (kind) {
                case NATIVE: return "Nativo · execução indisponível";
                case HYBRID: return "Híbrido · recursos nativos limitados";
                default: return "Web / HTML5 / WASM";
            }
        }
        public String details() {
            String summary = kind == Kind.WEB
                    ? "Entrada HTML executável pelo Android WebView."
                    : kind == Kind.HYBRID
                    ? "O IPK possui entrada HTML e arquivos ELF. A parte web pode funcionar, mas binários nativos e serviços próprios não são executados."
                    : "IPK nativo detectado. O Android não executa diretamente binários Linux/webOS, QEMU/proot/ABI Linux ainda não estão integrados. Nenhum executável do pacote será iniciado.";
            return summary + "\nArquitetura(s) ELF: " + architecture +
                    "\nArquivos ELF encontrados: " + elfCount +
                    (examplePath.isEmpty() ? "" : "\nExemplo: " + examplePath) +
                    (scanLimited ? "\nInspeção limitada para não atrasar a interface." : "");
        }
    }

    private NativeIpkInspector() {}

    /**
     * The report is a bounded, best-effort classification. The caller must still
     * validate the main path with IpkInstaller.safeResolve before installing.
     */
    public static Report inspect(File root, String main, String declaredType) {
        String start = main == null ? "" : main.toLowerCase(Locale.ROOT);
        boolean webEntry = (start.endsWith(".html") || start.endsWith(".htm"));
        try { webEntry &= IpkInstaller.safeResolve(root, main).isFile(); }
        catch (IOException ignored) { webEntry = false; }
        Set<String> architectures = new LinkedHashSet<>();
        ArrayDeque<File> pending = new ArrayDeque<>();
        pending.add(root);
        String example = "";
        int count = 0, visited = 0;
        while (!pending.isEmpty() && visited < 2048) {
            File item = pending.removeFirst();
            File[] files = item.listFiles();
            if (files == null) continue;
            for (File file : files) {
                if (++visited > 2048) break;
                if (file.isDirectory()) {
                    pending.add(file);
                    continue;
                }
                if (!file.isFile() || file.length() < 20) continue;
                // Normal web resources dwarf ELF binaries by quantity. Cheap
                // two-byte magic test before reading the remaining ELF header.
                try (FileInputStream input = new FileInputStream(file)) {
                    byte[] header = new byte[64];
                    int read = input.read(header);
                    String arch = elfArchitecture(header, read);
                    if (arch != null) {
                        count++;
                        architectures.add(arch);
                        if (example.isEmpty()) {
                            String full = file.getPath();
                            String base = root.getPath() + File.separator;
                            example = full.startsWith(base) ? full.substring(base.length()) : file.getName();
                        }
                    }
                } catch (IOException ignored) {}
            }
        }
        String type = declaredType == null ? "" : declaredType.toLowerCase(Locale.ROOT);
        boolean nativeHint = "native".equals(type) || "qml".equals(type)
                || "shell".equals(type) || "service".equals(type);
        Kind kind = webEntry
                ? (count > 0 || nativeHint ? Kind.HYBRID : Kind.WEB)
                : Kind.NATIVE;
        return new Report(kind, webEntry,
                architectures.isEmpty() ? "não detectada" : String.join(", ", architectures),
                example, count, !pending.isEmpty() || visited >= 2048);
    }

    /** Returns null for non-ELF; only metadata is read, no dynamic loading. */
    public static String elfArchitecture(byte[] h, int n) {
        if (h == null || n < 20 || h[0] != 0x7f || h[1] != 'E' ||
                h[2] != 'L' || h[3] != 'F') return null;
        int width = h[4] & 255, endian = h[5] & 255;
        if ((width != 1 && width != 2) || (endian != 1 && endian != 2))
            return "ELF desconhecido";
        int machine = endian == 1
                ? ((h[18] & 255) | ((h[19] & 255) << 8))
                : (((h[18] & 255) << 8) | (h[19] & 255));
        String arch;
        switch (machine) {
            case 40: arch = "ARM"; break;
            case 183: arch = "AArch64"; break;
            case 3: arch = "x86"; break;
            case 62: arch = "x86_64"; break;
            case 8: arch = "MIPS"; break;
            default: arch = "machine " + machine; break;
        }
        return "ELF" + (width == 1 ? "32 " : "64 ") + arch;
    }
}
