package io.maizon.webossim;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Read-only bounded ELF program-header inspection. PT_INTERP is the name of
 * the Linux dynamic loader; when it is missing execve() reports ENOENT even
 * when the executable itself exists. Never executes the untrusted file.
 */
public final class ElfProgramInspector {
    private ElfProgramInspector() {}

    private static long unsigned(byte[] bytes, int start, int count, boolean little) {
        long value = 0;
        if (little) {
            for (int i = count - 1; i >= 0; i--) value = (value << 8) | (bytes[start + i] & 255L);
        } else {
            for (int i = 0; i < count; i++) value = (value << 8) | (bytes[start + i] & 255L);
        }
        return value;
    }

    /** Null means static or no declared Linux ELF interpreter; does not imply runnable. */
    public static String interpreter(File file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            if (raf.length() < 52) throw new IOException("Cabeçalho ELF incompleto");
            byte[] header = new byte[64];
            raf.seek(0);
            raf.readFully(header, 0, (int) Math.min(raf.length(), header.length));
            if (header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F')
                throw new IOException("Arquivo não é ELF");
            int elfClass = header[4] & 255;
            int encoding = header[5] & 255;
            if ((elfClass != 1 && elfClass != 2) || (encoding != 1 && encoding != 2))
                throw new IOException("Formato ELF não reconhecido");
            if (elfClass == 2 && raf.length() < 64)
                throw new IOException("Cabeçalho ELF64 incompleto");
            boolean little = encoding == 1;
            int phOffset = elfClass == 1 ? 28 : 32;
            int entrySizeOffset = elfClass == 1 ? 42 : 54;
            int countOffset = elfClass == 1 ? 44 : 56;
            long table = unsigned(header, phOffset, elfClass == 1 ? 4 : 8, little);
            int stride = (int) unsigned(header, entrySizeOffset, 2, little);
            int count = (int) unsigned(header, countOffset, 2, little);
            int minimum = elfClass == 1 ? 32 : 56;
            if (count == 0) return null;
            if (stride < minimum || stride > 1024 || count > 2048
                    || table < 0 || table > raf.length()
                    || (long) stride * count > raf.length() - table)
                throw new IOException("Tabela de segmentos ELF inválida");
            byte[] ph = new byte[minimum];
            for (int i = 0; i < count; i++) {
                raf.seek(table + (long) i * stride);
                raf.readFully(ph);
                long type = unsigned(ph, 0, 4, little);
                if (type != 3) continue; // PT_INTERP
                long offset = unsigned(ph, elfClass == 1 ? 4 : 8, elfClass == 1 ? 4 : 8, little);
                long size = unsigned(ph, elfClass == 1 ? 16 : 32, elfClass == 1 ? 4 : 8, little);
                if (offset < 0 || size < 2 || size > 4096 ||
                        offset > raf.length() || size > raf.length() - offset)
                    throw new IOException("Segmento PT_INTERP inválido");
                byte[] path = new byte[(int) size];
                raf.seek(offset);
                raf.readFully(path);
                int end = 0;
                while (end < path.length && path[end] != 0) end++;
                if (end == 0 || end == path.length)
                    throw new IOException("Caminho do carregador ELF inválido");
                String interpreter = new String(path, 0, end, StandardCharsets.UTF_8);
                if (!interpreter.startsWith("/") || interpreter.indexOf((char) 0) >= 0)
                    throw new IOException("Caminho de PT_INTERP inválido");
                return interpreter;
            }
            return null;
        }
    }

    /** Resolve absolute PT_INTERP within the guest sysroot, never in Android host FS. */
    public static boolean interpreterPresent(File rootfs, String interpreter) throws IOException {
        if (interpreter == null) return true;
        if (!interpreter.startsWith("/")) return false;
        String stripped = interpreter.substring(1);
        File resolved = IpkInstaller.safeResolve(rootfs, stripped);
        return resolved.isFile();
    }
}
