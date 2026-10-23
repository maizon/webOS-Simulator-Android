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
}
