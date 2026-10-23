package io.maizon.webossim;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

/** Regression tests for IPK paths, streaming ranges, profiles and script injection. */
public class HttpSupportTest {
    @Test public void rangeOpenSuffixAndExplicit() throws Exception {
        HttpSupport.ByteRange a = HttpSupport.parseRange("bytes=0-99", 1000);
        assertEquals(0, a.start); assertEquals(99, a.end); assertEquals(100, a.length());

        HttpSupport.ByteRange b = HttpSupport.parseRange("bytes=950-", 1000);
        assertEquals(950, b.start); assertEquals(999, b.end); assertEquals(50, b.length());

        HttpSupport.ByteRange c = HttpSupport.parseRange("bytes=-40", 1000);
        assertEquals(960, c.start); assertEquals(999, c.end);

        HttpSupport.ByteRange d = HttpSupport.parseRange("bytes=-2000", 1000);
        assertEquals(0, d.start); assertEquals(999, d.end);

        assertNull(HttpSupport.parseRange(null, 100));
        assertNull(HttpSupport.parseRange("", 100));
    }

    private void rangeFails(String value, long size) {
        try { HttpSupport.parseRange(value, size); fail("Should reject " + value); }
        catch (IOException expected) { /* 416 */ }
    }

    @Test public void rejectUnsafeOrUnsupportedRanges() {
        rangeFails("bytes=10-5", 100);
        rangeFails("bytes=100-", 100);
        rangeFails("bytes=-0", 100);
        rangeFails("bytes=0-1,5-6", 100);
        rangeFails("bytes=999999999999999999999-", 100);
        rangeFails("items=0-2", 100);
        rangeFails("bytes=0-", 0);
    }

    @Test public void properMimeForGamesAndMedia() {
        assertEquals("application/wasm", HttpSupport.mimeFor("Snes9x.WASM"));
        assertEquals("application/javascript; charset=utf-8", HttpSupport.mimeFor("runtime.worker.js"));
        assertEquals("application/javascript; charset=utf-8", HttpSupport.mimeFor("loader.mjs"));
        assertEquals("text/html; charset=utf-8", HttpSupport.mimeFor("index.html"));
        assertEquals("video/webm", HttpSupport.mimeFor("intro.webm"));
        assertEquals("application/octet-stream", HttpSupport.mimeFor("game.data"));
    }

    @Test public void literalPlusAndPercentInPaths() throws Exception {
        assertEquals("/assets/a+b game.bin", HttpSupport.decodePath("/assets/a+b%20game.bin"));
        assertEquals("/app/test#1.bin", HttpSupport.decodePath("/app/test%231.bin"));
        for (String path : new String[]{"/a/%Q1", "/a/%00", "/a/%5cwindows", "file:///tmp/x"}) {
            try { HttpSupport.decodePath(path); fail(path); }
            catch (IOException expected) { /* forbidden */ }
        }
    }

    @Test public void injectionsAreEarlyAndOptional() {
        String sample = "<!doctype html><html><head><script src=\"game.js\"></script></head><body></body></html>";
        String result = HttpSupport.inject(sample, true, true, true);
        assertTrue(result.indexOf("__webos_shim.js") < result.indexOf("game.js"));
        assertTrue(result.indexOf("__graphics_doctor.js") < result.indexOf("game.js"));
        assertTrue(result.indexOf("__compat_viewport.js") < result.indexOf("game.js"));
        assertEquals(sample, HttpSupport.inject(sample, false, false, false));
        String headless = HttpSupport.inject("<html><body>Game</body></html>", true, false, false);
        assertTrue(headless.contains("<head>"));
        assertTrue(headless.contains("Game"));
    }

    @Test public void profilesDoNotPretendToForceUnsupportedGpuFeatures() {
        assertTrue(CompatibilityProfiles.isolate("threaded"));
        assertFalse(CompatibilityProfiles.isolate("legacy"));
        assertFalse(CompatibilityProfiles.isolate("balanced"));
        assertTrue(CompatibilityProfiles.fakeUA("legacy"));
        assertFalse(CompatibilityProfiles.fakeUA("threaded"));
        assertTrue(CompatibilityProfiles.tvViewport("threaded"));
        assertFalse(CompatibilityProfiles.tvViewport("balanced"));
        assertEquals("balanced", CompatibilityProfiles.normalize("unknown"));
    }
}
