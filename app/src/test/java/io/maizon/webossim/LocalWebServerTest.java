package io.maizon.webossim;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** Prevent regressions in common webOS HTML/WASM content types. */
public class LocalWebServerTest {
    @Test public void essentialGraphicsAndWasmMimeTypes() {
        assertEquals("text/html; charset=utf-8", LocalWebServer.mimeFor("index.html"));
        assertEquals("application/javascript; charset=utf-8", LocalWebServer.mimeFor("worker.mjs"));
        assertEquals("application/wasm", LocalWebServer.mimeFor("core.wasm"));
        assertEquals("text/css", LocalWebServer.mimeFor("layout.css"));
        assertEquals("image/png", LocalWebServer.mimeFor("cover.png"));
    }
}
