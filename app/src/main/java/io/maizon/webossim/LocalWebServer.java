package io.maizon.webossim;

import android.content.Context;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loopback-only IPK content origin: root-relative assets, streaming and HTTP ranges.
 * Does not execute webOS native binaries or claim equivalence with LG firmware.
 */
public final class LocalWebServer {
    private static final String TAG = "WebOsServer";
    private static final long MAX_FILE = 512L * 1024 * 1024;
    private final Context context;
    private final File appsDir;
    private final ExecutorService workers = Executors.newFixedThreadPool(8);
    private final boolean isolation;
    private final boolean shimEnabled;
    private final boolean tvViewport;
    private ServerSocket server;
    private Thread acceptThread;
    private volatile boolean running;
    private volatile String allowedAppId;

    public LocalWebServer(Context context, File appsDir, boolean isolation) {
        this(context, appsDir, isolation, true, false);
    }

    public LocalWebServer(Context context, File appsDir, boolean isolation,
                          boolean shimEnabled, boolean tvViewport) {
        this.context = context.getApplicationContext();
        this.appsDir = appsDir;
        this.isolation = isolation;
        this.shimEnabled = shimEnabled;
        this.tvViewport = tvViewport;
    }

    public int start() throws IOException { return start(null); }

    /** Stable port per app for IndexedDB/cookies; the alternate port is a best-effort fallback. */
    public int start(String appId) throws IOException {
        if (appId != null && !appId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
            throw new IOException("Invalid app id");
        allowedAppId = appId;
        int wanted = appId == null ? 17822 : 18000 + Math.floorMod(appId.hashCode(), 37000);
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), wanted), 32);
        } catch (IOException occupied) {
            if (server != null) try { server.close(); } catch (IOException ignored) {}
            server = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
        }
        running = true;
        acceptThread = new Thread(() -> {
            while (running) {
                try {
                    Socket client = server.accept();
                    client.setSoTimeout(12000);
                    workers.execute(() -> serve(client));
                } catch (IOException e) {
                    if (running) android.util.Log.w(TAG, "accept: " + e);
                } catch (java.util.concurrent.RejectedExecutionException rejected) {
                    if (!running) return;
                }
            }
        }, "webos-loopback");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return server.getLocalPort();
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        workers.shutdownNow();
    }

    public String urlFor(IpkInstaller.InstalledApp app, int port) {
        String base = "http://127.0.0.1:" + port + "/apps/" + encode(app.id) + "/";
        String[] fragments = app.main.split("/", -1);
        StringBuilder url = new StringBuilder(base);
        for (int i = 0; i < fragments.length; i++) {
            if (i != 0) url.append('/');
            url.append(encode(fragments[i]));
        }
        return url.toString();
    }

    private static String encode(String text) {
        try { return java.net.URLEncoder.encode(text, "UTF-8").replace("+", "%20"); }
        catch (Exception e) { return text; }
    }

    private void serve(Socket socket) {
        try (Socket conn = socket;
             BufferedReader input = new BufferedReader(new InputStreamReader(
                     conn.getInputStream(), StandardCharsets.ISO_8859_1));
             BufferedOutputStream output = new BufferedOutputStream(conn.getOutputStream())) {
            String first = input.readLine();
            if (first == null || first.length() > 16384) return;
            String[] request = first.split(" ", 3);
            if (request.length != 3) {
                respond(output, 400, "text/plain", "Bad request".getBytes(StandardCharsets.UTF_8), false);
                return;
            }
            String verb = request[0];
            boolean head = "HEAD".equals(verb);
            if (!"GET".equals(verb) && !head && !"OPTIONS".equals(verb)) {
                respond(output, 405, "text/plain", new byte[0], false);
                return;
            }
            Map<String, String> headers = new HashMap<>();
            int budget = 32768;
            for (int i = 0; i < 64; i++) {
                String line = input.readLine();
                if (line == null || line.isEmpty()) break;
                budget -= line.length();
                if (budget < 0) { respond(output, 400, "text/plain", new byte[0], head); return; }
                int colon = line.indexOf(':');
                if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
            if ("OPTIONS".equals(verb)) {
                sendHeaders(output, 204, "text/plain", 0, null, false, 0);
                output.flush();
                return;
            }
            String requestTarget = request[1];
            if (!requestTarget.startsWith("/") || requestTarget.length() > 8192) {
                respond(output, 400, "text/plain", new byte[0], head); return;
            }
            String rawPath = requestTarget.split("[?#]", 2)[0];
            String path = HttpSupport.decodePath(rawPath);
            String asset = null;
            if (path.equals("/__graphics_selftest.html")) asset = "graphics-selftest.html";
            if (path.equals("/__graphics_doctor.js")) asset = "graphics-doctor.js";
            if (path.equals("/__webos_shim.js")) asset = "webos-shim.js";
            if (path.equals("/__compat_viewport.js")) asset = "compat-viewport.js";
            if (asset != null) {
                String mime = HttpSupport.mimeFor(asset);
                try (InputStream stream = context.getAssets().open(asset)) {
                    respond(output, 200, mime, readLimited(stream, 1024 * 1024), head);
                }
                return;
            }
            String appId = allowedAppId;
            if (appId == null) { respond(output, 404, "text/plain", new byte[0], head); return; }
            String relative;
            if (path.startsWith("/apps/")) {
                String remaining = path.substring("/apps/".length());
                int delim = remaining.indexOf('/');
                if (delim < 1 || !remaining.substring(0, delim).equals(appId)) {
                    respond(output, 403, "text/plain", new byte[0], head); return;
                }
                relative = remaining.substring(delim + 1);
            } else {
                // Games often use absolute /js/, /assets/, /core.wasm or /index.js paths.
                // They all resolve within the ONE currently running application's sandbox.
                relative = path.substring(1);
            }
            if (relative.isEmpty()) relative = "index.html";
            File appDirectory = new File(appsDir, appId);
            File file = IpkInstaller.safeResolve(appDirectory, relative);
            if (!file.isFile()) {
                android.util.Log.w(TAG, "HTTP 404 " + path + " app=" + appId);
                respond(output, 404, "text/plain", "Asset missing".getBytes(StandardCharsets.UTF_8), head);
                return;
            }
            if (file.length() > MAX_FILE) {
                respond(output, 413, "text/plain", new byte[0], head); return;
            }
            String mime = HttpSupport.mimeFor(file.getName());
            String range = headers.get("range");
            if (mime.startsWith("text/html") && (range == null || range.isEmpty())) {
                try (InputStream stream = new FileInputStream(file)) {
                    String html = new String(readLimited(stream, 8 * 1024 * 1024), StandardCharsets.UTF_8);
                    html = HttpSupport.inject(html, shimEnabled, true, tvViewport);
                    respond(output, 200, mime, html.getBytes(StandardCharsets.UTF_8), head);
                }
            } else {
                fileResponse(output, file, mime, range, head);
            }
        } catch (Exception ex) {
            android.util.Log.w(TAG, "request failed: " + ex.toString());
        }
    }

    private static byte[] readLimited(InputStream stream, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[16384];
        int n;
        while ((n = stream.read(chunk)) != -1) {
            if (n > limit - out.size()) throw new IOException("Document exceeds limit");
            out.write(chunk, 0, n);
        }
        return out.toByteArray();
    }

    private void fileResponse(OutputStream out, File file, String mime,
                              String rangeHeader, boolean head) throws IOException {
        long total = file.length();
        HttpSupport.ByteRange range;
        try { range = HttpSupport.parseRange(rangeHeader, total); }
        catch (IOException e) {
            sendHeaders(out, 416, mime, 0, "bytes */" + total, true, 0);
            out.flush();
            return;
        }
        long offset = range == null ? 0 : range.start;
        long length = range == null ? total : range.length();
        String contentRange = range == null ? null
                : "bytes " + range.start + "-" + range.end + "/" + total;
        sendHeaders(out, range == null ? 200 : 206, mime, length, contentRange, true, 0);
        if (!head && length > 0) {
            try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                input.seek(offset);
                long left = length;
                byte[] bytes = new byte[65536];
                while (left > 0) {
                    int n = input.read(bytes, 0, (int) Math.min(bytes.length, left));
                    if (n == -1) throw new EOFException("Asset truncated during response");
                    out.write(bytes, 0, n);
                    left -= n;
                }
            }
        }
        out.flush();
    }

    private void respond(OutputStream out, int status, String mime, byte[] body,
                         boolean head) throws IOException {
        sendHeaders(out, status, mime, body.length, null, false, 0);
        if (!head) out.write(body);
        out.flush();
    }

    private void sendHeaders(OutputStream out, int status, String mime, long length,
                             String contentRange, boolean rangedFile, int unused) throws IOException {
        String reason = status == 200 ? "OK" : status == 204 ? "No Content"
                : status == 206 ? "Partial Content" : status == 400 ? "Bad Request"
                : status == 403 ? "Forbidden" : status == 404 ? "Not Found"
                : status == 405 ? "Method Not Allowed" : status == 413 ? "Payload Too Large"
                : status == 416 ? "Range Not Satisfiable" : "Error";
        StringBuilder headers = new StringBuilder("HTTP/1.1 ").append(status).append(' ')
                .append(reason).append("\r\n")
                .append("Content-Type: ").append(mime).append("\r\n")
                .append("Content-Length: ").append(length).append("\r\n")
                .append("Cache-Control: private, max-age=120\r\n")
                .append("X-Content-Type-Options: nosniff\r\n")
                .append("Cross-Origin-Resource-Policy: same-origin\r\n");
        if (isolation) {
            headers.append("Cross-Origin-Opener-Policy: same-origin\r\n")
                    .append("Cross-Origin-Embedder-Policy: require-corp\r\n");
        }
        if (rangedFile || status == 416) headers.append("Accept-Ranges: bytes\r\n");
        if (contentRange != null) headers.append("Content-Range: ").append(contentRange).append("\r\n");
        if (status == 204) headers.append("Allow: GET, HEAD, OPTIONS\r\n")
                .append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
                .append("Access-Control-Allow-Headers: Content-Type, Range\r\n");
        headers.append("Connection: close\r\n\r\n");
        out.write(headers.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    static String mimeFor(String name) { return HttpSupport.mimeFor(name); }
}
