package io.maizon.webossim;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public final class EmulatorCompatibilityTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    private void elf(File target, boolean arm64) throws Exception {
        byte[] header = new byte[64];
        header[0] = 0x7f; header[1] = 'E'; header[2] = 'L'; header[3] = 'F';
        header[4] = (byte) (arm64 ? 2 : 1); header[5] = 1;
        header[18] = (byte) (arm64 ? 183 : 40);
        try (FileOutputStream out = new FileOutputStream(target)) { out.write(header); }
    }

    @Test public void recognisesArmAndAarch64WithoutExecutingFiles() throws Exception {
        File app = temp.newFolder("native-only");
        elf(new File(app, "retroarch"), false);
        NativeIpkInspector.Report report = NativeIpkInspector.inspect(app, "retroarch", "native");
        assertEquals(NativeIpkInspector.Kind.NATIVE, report.kind);
        assertFalse(report.webEntry);
        assertEquals("ELF32 ARM", report.architecture);
        assertEquals(1, report.elfCount);
        assertTrue(report.details().contains("não executa diretamente"));

        File another = temp.newFile("arm64");
        elf(another, true);
        byte[] bytes = java.nio.file.Files.readAllBytes(another.toPath());
        assertEquals("ELF64 AArch64", NativeIpkInspector.elfArchitecture(bytes, bytes.length));
    }

    @Test public void distinguishesWebHybridAndNativeAndEmptyDirectories() throws Exception {
        File app = temp.newFolder("html");
        java.nio.file.Files.write(new File(app, "index.html").toPath(),
                "<html></html>".getBytes(StandardCharsets.UTF_8));
        NativeIpkInspector.Report basic = NativeIpkInspector.inspect(app, "index.html", "web");
        assertTrue(basic.webEntry);
        assertEquals(NativeIpkInspector.Kind.WEB, basic.kind);
        assertEquals(0, basic.elfCount);

        elf(new File(app, "libretro.so"), false);
        NativeIpkInspector.Report mixed = NativeIpkInspector.inspect(app, "index.html", "web");
        assertTrue(mixed.webEntry);
        assertEquals(NativeIpkInspector.Kind.HYBRID, mixed.kind);
        assertEquals(1, mixed.elfCount);

        NativeIpkInspector.Report noApp = NativeIpkInspector.inspect(app, "nonexistent", "native");
        assertFalse(noApp.webEntry);
        assertEquals(NativeIpkInspector.Kind.NATIVE, noApp.kind);
    }

    @Test public void rejectsWrongElfMagicAndInvalidEndian() {
        byte[] raw = new byte[64];
        assertNull(NativeIpkInspector.elfArchitecture(raw, raw.length));
        raw[0] = 0x7f; raw[1] = 'E'; raw[2] = 'L'; raw[3] = 'F';
        raw[4] = 2; raw[5] = 2; raw[18] = 0; raw[19] = (byte) 183;
        assertEquals("ELF64 AArch64", NativeIpkInspector.elfArchitecture(raw, raw.length));
        raw[4] = 9;
        assertEquals("ELF desconhecido", NativeIpkInspector.elfArchitecture(raw, raw.length));
    }

    @Test public void letterboxAlwaysPreservesSixteenByNine() {
        assertArrayEquals(new int[]{1920, 1080}, TvDisplayMetrics.fit(1920, 1080));
        assertArrayEquals(new int[]{1280, 720}, TvDisplayMetrics.fit(1280, 800));
        assertArrayEquals(new int[]{400, 225}, TvDisplayMetrics.fit(400, 900));
        assertArrayEquals(new int[]{1600, 900}, TvDisplayMetrics.fit(2000, 900));
        assertArrayEquals(new int[]{0, 0}, TvDisplayMetrics.fit(0, 100));
        assertEquals(1280, TvDisplayMetrics.parseWidth("1280 x 720"));
        assertEquals(1920, TvDisplayMetrics.parseWidth("1920x1080"));
        assertEquals(1920, TvDisplayMetrics.parseWidth("invalid"));
    }

    @Test public void injectingTvViewportUsesManifestResolutionNotAppSideEffects() {
        String html = "<html><head><meta name=\"viewport\" content=\"width=device-width\">"
                + "<script src=\"index.js\"></script></head><body></body></html>";
        String result = HttpSupport.inject(html, true, true, true, 1280);
        assertTrue(result.contains("data-tv-width=\"1280\""));
        assertTrue(result.indexOf("__compat_viewport.js") < result.indexOf("index.js"));
        assertFalse(HttpSupport.inject(html, false, false, false, 1280)
                .contains("__compat_viewport.js"));
    }
}
