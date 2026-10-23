package io.maizon.webossim;

import java.io.IOException;
import java.net.URLDecoder;
import java.util.Locale;

/** Pure-Java HTTP and HTML helpers used by the IPK loopback server and unit tests. */
public final class HttpSupport {
    private HttpSupport() {}

    public static final class ByteRange {
        public final long start;
        public final long end;
        ByteRange(long start, long end) { this.start = start; this.end = end; }
        public long length() { return end - start + 1; }
    }

    /** Null means a normal full response; malformed or unsatisfiable ranges produce HTTP 416. */
    public static ByteRange parseRange(String header, long size) throws IOException {
        if (header == null || header.trim().isEmpty()) return null;
        if (size < 1 || !header.startsWith("bytes=")) throw new IOException("Unsupported range");
        String part = header.substring(6).trim();
        if (part.indexOf(',') >= 0) throw new IOException("Multipart ranges unsupported");
        int dash = part.indexOf('-');
        if (dash < 0 || part.indexOf('-', dash + 1) >= 0) throw new IOException("Malformed range");
        String from = part.substring(0, dash).trim();
        String to = part.substring(dash + 1).trim();
        if (from.isEmpty()) {
            long suffix = nonNegative(to);
            if (suffix == 0) throw new IOException("Empty suffix");
            return new ByteRange(Math.max(0, size - suffix), size - 1);
        }
        long start = nonNegative(from);
        long end = to.isEmpty() ? size - 1 : Math.min(size - 1, nonNegative(to));
        if (start >= size || end < start) throw new IOException("Unsatisfiable range");
        return new ByteRange(start, end);
    }

    private static long nonNegative(String input) throws IOException {
        if (input.isEmpty() || !input.matches("[0-9]{1,19}"))
            throw new IOException("Invalid range number");
        try { return Long.parseLong(input); }
        catch (NumberFormatException ex) { throw new IOException("Range overflow", ex); }
    }

    /** Decode a URI path, retaining literal '+' in filenames (URLDecoder otherwise converts it). */
    public static String decodePath(String text) throws IOException {
        if (text == null || text.length() > 8192 || !text.startsWith("/"))
            throw new IOException("Invalid URL path");
        try {
            String decoded = URLDecoder.decode(text.replace("+", "%2B"), "UTF-8");
            if (decoded.indexOf(0) >= 0 || decoded.indexOf('\\') >= 0)
                throw new IOException("Forbidden path character");
            return decoded;
        } catch (IllegalArgumentException e) { throw new IOException("Invalid percent encoding", e); }
    }

    public static String mimeFor(String name) {
        String s = name.toLowerCase(Locale.ROOT);
        if (s.endsWith(".html") || s.endsWith(".htm")) return "text/html; charset=utf-8";
        if (s.endsWith(".js") || s.endsWith(".mjs") || s.endsWith(".worker")) return "application/javascript; charset=utf-8";
        if (s.endsWith(".wasm")) return "application/wasm";
        if (s.endsWith(".json") || s.endsWith(".webmanifest") || s.endsWith(".map")) return "application/json";
        if (s.endsWith(".css")) return "text/css; charset=utf-8";
        if (s.endsWith(".svg")) return "image/svg+xml";
        if (s.endsWith(".png")) return "image/png";
        if (s.endsWith(".jpg") || s.endsWith(".jpeg")) return "image/jpeg";
        if (s.endsWith(".webp")) return "image/webp";
        if (s.endsWith(".gif")) return "image/gif";
        if (s.endsWith(".ico")) return "image/x-icon";
        if (s.endsWith(".avif")) return "image/avif";
        if (s.endsWith(".bmp")) return "image/bmp";
        if (s.endsWith(".woff")) return "font/woff";
        if (s.endsWith(".woff2")) return "font/woff2";
        if (s.endsWith(".ttf")) return "font/ttf";
        if (s.endsWith(".otf")) return "font/otf";
        if (s.endsWith(".mp3")) return "audio/mpeg";
        if (s.endsWith(".ogg") || s.endsWith(".oga")) return "audio/ogg";
        if (s.endsWith(".wav")) return "audio/wav";
        if (s.endsWith(".flac")) return "audio/flac";
        if (s.endsWith(".aac")) return "audio/aac";
        if (s.endsWith(".m4a")) return "audio/mp4";
        if (s.endsWith(".mp4") || s.endsWith(".m4v")) return "video/mp4";
        if (s.endsWith(".webm")) return "video/webm";
        if (s.endsWith(".vtt")) return "text/vtt";
        if (s.endsWith(".xml")) return "application/xml";
        if (s.endsWith(".pdf")) return "application/pdf";
        if (s.endsWith(".txt") || s.endsWith(".log")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }

    /** Inject scripts before application scripts while preserving the original IPK files on disk. */
    public static String inject(String html, boolean shim, boolean doctor, boolean tvViewport) {
        StringBuilder injection = new StringBuilder();
        if (shim) injection.append("<script src=\"/__webos_shim.js\"></script>");
        if (doctor) injection.append("<script src=\"/__graphics_doctor.js\"></script>");
        if (tvViewport) injection.append("<script src=\"/__compat_viewport.js\"></script>");
        if (injection.length() == 0) return html;
        String low = html.toLowerCase(Locale.ROOT);
        int head = low.indexOf("<head");
        int end = head >= 0 ? html.indexOf('>', head) : -1;
        if (end >= 0) return html.substring(0, end + 1) + injection + html.substring(end + 1);
        int htmlStart = low.indexOf("<html");
        int htmlEnd = htmlStart >= 0 ? html.indexOf('>', htmlStart) : -1;
        if (htmlEnd >= 0) return html.substring(0, htmlEnd + 1) + "<head>" + injection
                + "</head>" + html.substring(htmlEnd + 1);
        return "<head>" + injection + "</head>" + html;
    }
}
