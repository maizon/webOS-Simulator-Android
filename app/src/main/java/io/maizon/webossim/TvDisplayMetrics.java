package io.maizon.webossim;

/** Platform-neutral 16:9 TV letterboxing and supported webOS logical sizes. */
public final class TvDisplayMetrics {
    private TvDisplayMetrics() {}

    /** Fit a 16:9 frame completely inside the available screen without cropping. */
    public static int[] fit(int width, int height) {
        if (width <= 0 || height <= 0) return new int[]{0, 0};
        long intendedHeight = ((long) width * 9L) / 16L;
        if (intendedHeight <= height) return new int[]{width, (int) intendedHeight};
        int fittedWidth = (int) (((long) height * 16L) / 9L);
        return new int[]{Math.min(width, fittedWidth), height};
    }

    /** The SDK typically declares "1920x1080" or "1280x720". */
    public static int parseWidth(String resolution) {
        if (resolution == null) return 1920;
        String compact = resolution.trim().toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        if ("1280x720".equals(compact)) return 1280;
        return 1920;
    }
}
