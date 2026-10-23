package io.maizon.webossim;

/**
 * These are genuine browser-host configuration differences, not GPU driver replacement.
 * LEGACY: LG-looking user agent + 1080p logical viewport, no isolation.
 * BALANCED: original Android user agent, no COEP, scaled layout for ordinary web apps.
 * THREADED: COOP/COEP for SharedArrayBuffer (may break cross-origin assets).
 */
public final class CompatibilityProfiles {
    public static final String BALANCED = "balanced";
    public static final String LEGACY = "legacy";
    public static final String THREADED = "threaded";
    private CompatibilityProfiles() {}

    public static String normalize(String value) {
        if (LEGACY.equals(value) || THREADED.equals(value)) return value;
        return BALANCED;
    }
    public static boolean isolate(String value) { return THREADED.equals(normalize(value)); }
    public static boolean fakeUA(String value) { return LEGACY.equals(normalize(value)); }
    public static boolean tvViewport(String value) { return !BALANCED.equals(normalize(value)); }
    public static String title(String value) {
        switch (normalize(value)) {
            case LEGACY: return "LG TV legado · viewport 1080p";
            case THREADED: return "WASM threads · COOP/COEP";
            default: return "Balanceado · WebView real";
        }
    }
}
