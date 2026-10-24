package io.maizon.webossim;

import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.tukaani.xz.XZOutputStream;
import org.tukaani.xz.LZMA2Options;

import static org.junit.Assert.*;

public class IpkInstallerTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void blocksTraversalAndAbsolutePaths() throws Exception {
        File base = temp.newFolder("apps");
        for (String path : new String[]{"../other", "/etc/passwd", "a/../../other", "C:/Windows"}) {
            try { IpkInstaller.safeResolve(base, path); fail("Must reject: " + path); }
            catch (IOException expected) { /* safe */ }
        }
        assertEquals(new File(base, "web/index.html").getCanonicalFile(),
                     IpkInstaller.safeResolve(base, "web/index.html"));
    }

    private byte[] sample(String targetName) throws Exception {
        ByteArrayOutputStream tarCompressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(tarCompressed);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            byte[] payload = "<html><h1>hello</h1></html>".getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry entry = new TarArchiveEntry(targetName);
            entry.setSize(payload.length);
            tar.putArchiveEntry(entry);
            tar.write(payload);
            tar.closeArchiveEntry();
            tar.finish();
        }
        ByteArrayOutputStream ipk = new ByteArrayOutputStream();
        try (ArArchiveOutputStream ar = new ArArchiveOutputStream(ipk)) {
            byte[] version = "2.0\n".getBytes(StandardCharsets.UTF_8);
            ar.putArchiveEntry(new ArArchiveEntry("debian-binary", version.length));
            ar.write(version); ar.closeArchiveEntry();
            byte[] data = tarCompressed.toByteArray();
            ar.putArchiveEntry(new ArArchiveEntry("data.tar.gz", data.length));
            ar.write(data); ar.closeArchiveEntry();
            ar.finish();
        }
        return ipk.toByteArray();
    }

    @Test public void extractsSimpleIpk() throws Exception {
        File dest = temp.newFolder("target");
        IpkInstaller.extract(new ByteArrayInputStream(sample("usr/palm/applications/test/index.html")), dest);
        File html = new File(dest, "usr/palm/applications/test/index.html");
        assertTrue(html.isFile());
        assertTrue(new String(java.nio.file.Files.readAllBytes(html.toPath()), StandardCharsets.UTF_8).contains("hello"));
    }

    @Test public void rejectsTraversalInTar() throws Exception {
        File dest = temp.newFolder("destination");
        try {
            IpkInstaller.extract(new ByteArrayInputStream(sample("../../outside.txt")), dest);
            fail("Expected unsafe archive path to fail");
        } catch (IOException expected) { /* path outside sandbox */ }
    }

    /** Different Debian-ar payload compression formats must decode the same safe tree. */
    private byte[] sampleCompressed(String codec) throws Exception {
        ByteArrayOutputStream tarRaw = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(tarRaw)) {
            byte[] file = "<html>compat-ok</html>".getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry entry = new TarArchiveEntry("usr/palm/applications/compat/index.html");
            entry.setSize(file.length);
            tar.putArchiveEntry(entry);
            tar.write(file);
            tar.closeArchiveEntry();
            tar.finish();
        }
        ByteArrayOutputStream dataOut = new ByteArrayOutputStream();
        if ("xz".equals(codec)) {
            try (XZOutputStream stream = new XZOutputStream(dataOut, new LZMA2Options())) {
                stream.write(tarRaw.toByteArray());
            }
        } else if ("bz2".equals(codec)) {
            try (BZip2CompressorOutputStream stream = new BZip2CompressorOutputStream(dataOut)) {
                stream.write(tarRaw.toByteArray());
            }
        } else {
            throw new IllegalArgumentException(codec);
        }
        ByteArrayOutputStream ipk = new ByteArrayOutputStream();
        try (ArArchiveOutputStream ar = new ArArchiveOutputStream(ipk)) {
            byte[] payload = dataOut.toByteArray();
            ar.putArchiveEntry(new ArArchiveEntry("data.tar." + codec, payload.length));
            ar.write(payload);
            ar.closeArchiveEntry();
            ar.finish();
        }
        return ipk.toByteArray();
    }

    @Test public void extractsXzCompressedIpk() throws Exception {
        File dest = temp.newFolder("xz-install");
        IpkInstaller.extract(new ByteArrayInputStream(sampleCompressed("xz")), dest);
        assertTrue(new File(dest, "usr/palm/applications/compat/index.html").isFile());
    }

    @Test public void extractsBzip2CompressedIpk() throws Exception {
        File dest = temp.newFolder("bz2-install");
        IpkInstaller.extract(new ByteArrayInputStream(sampleCompressed("bz2")), dest);
        assertTrue(new File(dest, "usr/palm/applications/compat/index.html").isFile());
    }

    /**
     * Regression: tar(5) carries owner execute bits, whereas FileOutputStream
     * creates 0644 files. PRoot's shell then returns 126/EACCES for an ELF.
     */
    private byte[] nativeIpkWithPermissions() throws Exception {
        String base = "usr/palm/applications/com.maizon.elfperm/";
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            byte[] info = ("{\"id\":\"com.maizon.elfperm\",\"title\":\"ELF test\","
                    + "\"type\":\"native\",\"main\":\"retroarch\"}")
                    .getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry manifest = new TarArchiveEntry(base + "appinfo.json");
            manifest.setMode(0644);
            manifest.setSize(info.length);
            tar.putArchiveEntry(manifest); tar.write(info); tar.closeArchiveEntry();

            byte[] elf = new byte[64];
            elf[0] = 0x7f; elf[1] = 'E'; elf[2] = 'L'; elf[3] = 'F';
            elf[4] = 1; elf[5] = 1; elf[18] = 40;
            TarArchiveEntry binary = new TarArchiveEntry(base + "retroarch");
            binary.setMode(0755);
            binary.setSize(elf.length);
            tar.putArchiveEntry(binary); tar.write(elf); tar.closeArchiveEntry();

            byte[] data = "not executable".getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry file = new TarArchiveEntry(base + "readme.txt");
            file.setMode(0644);
            file.setSize(data.length);
            tar.putArchiveEntry(file); tar.write(data); tar.closeArchiveEntry();
            tar.finish();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ArArchiveOutputStream ar = new ArArchiveOutputStream(out)) {
            byte[] archive = compressed.toByteArray();
            ar.putArchiveEntry(new ArArchiveEntry("data.tar.gz", archive.length));
            ar.write(archive);
            ar.closeArchiveEntry();
            ar.finish();
        }
        return out.toByteArray();
    }

    @Test public void installingIpkPreservesUnixExecuteBitsWithoutMakingDataExecutable()
            throws Exception {
        File storage = temp.newFolder("native-install");
        IpkInstaller.InstalledApp app = IpkInstaller.install(
                new ByteArrayInputStream(nativeIpkWithPermissions()), storage);
        File executable = new File(app.directory, "retroarch");
        File data = new File(app.directory, "readme.txt");
        assertEquals(NativeIpkInspector.Kind.NATIVE, app.runtime.kind);
        assertTrue("TAR 0755 must be preserved for Linux ELF", executable.canExecute());
        assertFalse("TAR 0644 data must not become executable", data.canExecute());
    }

    @Test public void repairsNativeExecutablesInstalledBeforePermissionFix()
            throws Exception {
        File storage = temp.newFolder("legacy-native-install");
        IpkInstaller.InstalledApp app = IpkInstaller.install(
                new ByteArrayInputStream(nativeIpkWithPermissions()), storage);
        File executable = new File(app.directory, "retroarch");
        assertTrue(executable.setExecutable(false, false));
        assertFalse(executable.canExecute());

        NativeLinuxRuntime.ensureExecutableElf(executable);
        assertTrue("Restores previously stripped ELF execute permission", executable.canExecute());
        File readme = new File(app.directory, "readme.txt");
        try {
            NativeLinuxRuntime.ensureExecutableElf(readme);
            fail("Non-ELF must never be chmod executable");
        } catch (IOException expected) { /* safe */ }
        assertFalse(readme.canExecute());
    }
}
