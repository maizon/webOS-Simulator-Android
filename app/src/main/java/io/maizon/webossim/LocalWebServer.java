package io.maizon.webossim;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loopback-only HTTP origin: HTML injection, correct WASM MIME and COOP/COEP. */
public final class LocalWebServer {
    private final Context context;
    private final File appsDir;
    private final ExecutorService workers = Executors.newFixedThreadPool(4);
    private ServerSocket server;
    private Thread acceptThread;
    private volatile boolean running;
    private final boolean isolation;
    private String allowedAppId;

    public LocalWebServer(Context context, File appsDir, boolean isolation) {
        this.context = context.getApplicationContext();
        this.appsDir = appsDir;
        this.isolation = isolation;
    }

    public int start() throws IOException { return start(null); }

    /** Distinct stable localhost origin per app keeps cookies/IndexedDB isolated. */
    public int start(String appId) throws IOException {
        allowedAppId = appId;
        int wanted = appId == null ? 17822 : 18000 + Math.floorMod(appId.hashCode(), 37000);
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), wanted));
        } catch (IOException occupied) {
            if (server != null) try { server.close(); } catch (IOException ignored) { }
            server = new ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"));
        }
        running = true;
        acceptThread = new Thread(() -> {
            while (running) {
                try {
                    Socket client = server.accept();
                    client.setSoTimeout(15000);
                    workers.execute(() -> serve(client));
                } catch (IOException e) { if (running) android.util.Log.w("WebOsServer", "Accept: " + e); }
            }
        }, "webos-loopback");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return server.getLocalPort();
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        workers.shutdownNow();
    }

    public String urlFor(IpkInstaller.InstalledApp app, int port) {
        String base = "http://127.0.0.1:" + port + "/apps/" + UriEncode(app.id) + "/";
        String[] fragments = app.main.split("/");
        StringBuilder url = new StringBuilder(base);
        for (int i = 0; i < fragments.length; i++) {
            if (i != 0) url.append('/');
            url.append(UriEncode(fragments[i]));
        }
        return url.toString();
    }

    private static String UriEncode(String data) {
        try { return java.net.URLEncoder.encode(data, "UTF-8").replace("+", "%20"); }
        catch (Exception e) { return data; }
    }

    private void serve(Socket socket) {
        try (Socket conn = socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.ISO_8859_1));
             OutputStream out = conn.getOutputStream()) {
            String request = reader.readLine();
            if (request == null) return;
            String[] bits = request.split(" ");
            if (bits.length < 2 || (!bits[0].equals("GET") && !bits[0].equals("HEAD"))) {
                writeResponse(out, 405, "text/plain", "Method not allowed".getBytes(StandardCharsets.UTF_8), false);
                return;
            }
            String requestPath = bits[1].split("\\?")[0];
            if (requestPath.equals("/__graphics_doctor.js")) {
                try (InputStream asset = context.getAssets().open("graphics-doctor.js")) {
                    writeResponse(out, 200, "application/javascript", readLimited(asset, 1024 * 1024), bits[0].equals("HEAD"));
                }
                return;
            }
            if (requestPath.equals("/__webos_shim.js")) {
                try (InputStream asset = context.getAssets().open("webos-shim.js")) {
                    writeResponse(out, 200, "application/javascript", readLimited(asset, 1024 * 1024), bits[0].equals("HEAD"));
                }
                return;
            }
            if (!requestPath.startsWith("/apps/")) {
                writeResponse(out, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8), bits[0].equals("HEAD"));
                return;
            }
            String route = requestPath.substring("/apps/".length());
            int slash = route.indexOf('/');
            if (slash < 1) { writeResponse(out, 404, "text/plain", new byte[0], false); return; }
            String id = URLDecoder.decode(route.substring(0, slash), "UTF-8");
            if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || id.startsWith(".")) {
                writeResponse(out, 403, "text/plain", new byte[0], false); return;
            }
            if (allowedAppId == null || !allowedAppId.equals(id)) {
                writeResponse(out, 403, "text/plain", new byte[0], false); return;
            }
            String relative = URLDecoder.decode(route.substring(slash + 1), "UTF-8");
            File appDirectory = new File(appsDir, id);
            File file = IpkInstaller.safeResolve(appDirectory, relative);
            if (!file.isFile()) { writeResponse(out, 404, "text/plain", new byte[0], false); return; }
            if (file.length() > 128L * 1024 * 1024) {
                writeResponse(out, 413, "text/plain", new byte[0], false); return;
            }
            String mime = mimeFor(file.getName());
            if (mime.startsWith("text/html")) {
                byte[] data;
                try (InputStream input = new FileInputStream(file)) { data = readLimited(input, 8 * 1024 * 1024); }
                String html = new String(data, StandardCharsets.UTF_8);
                String shim = "<script src=\"/__webos_shim.js\"></script><script src=\"/__graphics_doctor.js\"></script>";
                int start = html.toLowerCase(Locale.ROOT).indexOf("<head");
                int end = start >= 0 ? html.indexOf('>', start) : -1;
                html = end >= 0 ? html.substring(0, end + 1) + shim + html.substring(end + 1) : shim + html;
                writeResponse(out, 200, mime, html.getBytes(StandardCharsets.UTF_8), bits[0].equals("HEAD"));
            } else {
                writeFile(out, file, mime, bits[0].equals("HEAD"));
            }
        } catch (Exception e) {
            android.util.Log.w("WebOsServer", "Request failed: " + e.getMessage());
        }
    }

    private static byte[] readLimited(InputStream stream, int max) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] bytes = new byte[16384];
        int n;
        while ((n = stream.read(bytes)) != -1) {
            if (buf.size() + n > max) throw new IOException("Arquivo muito grande");
            buf.write(bytes, 0, n);
        }
        return buf.toByteArray();
    }

    private void writeFile(OutputStream out, File file, String mime, boolean head) throws IOException {
        sendHeaders(out, 200, mime, file.length());
        if (head) return;
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32768];
            int n;
            while ((n = input.read(buffer)) != -1) out.write(buffer, 0, n);
        }
        out.flush();
    }

    private void writeResponse(OutputStream out, int status, String mime, byte[] body, boolean head) throws IOException {
        sendHeaders(out, status, mime, body.length);
        if (!head) out.write(body);
        out.flush();
    }

    private void sendHeaders(OutputStream out, int status, String mime, long size) throws IOException {
        String desc = status == 200 ? "OK" : status == 404 ? "Not Found" : "Error";
        String header = "HTTP/1.1 " + status + " " + desc + "\r\n"
                + "Content-Type: " + mime + "\r\n"
                + "Content-Length: " + size + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Cross-Origin-Opener-Policy: same-origin\r\n"
                + "Cross-Origin-Resource-Policy: same-origin\r\n"
                + (isolation ? "Cross-Origin-Embedder-Policy: require-corp\r\n" : "")
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.ISO_8859_1));
    }

    static String mimeFor(String name) {
        String s = name.toLowerCase(Locale.ROOT);
        if (s.endsWith(".html") || s.endsWith(".htm")) return "text/html; charset=utf-8";
        if (s.endsWith(".js") || s.endsWith(".mjs")) return "application/javascript; charset=utf-8";
        if (s.endsWith(".wasm")) return "application/wasm";
        if (s.endsWith(".json") || s.endsWith(".webmanifest")) return "application/json";
        if (s.endsWith(".css")) return "text/css";
        if (s.endsWith(".svg")) return "image/svg+xml";
        if (s.endsWith(".png")) return "image/png";
        if (s.endsWith(".jpg") || s.endsWith(".jpeg")) return "image/jpeg";
        if (s.endsWith(".webp")) return "image/webp";
        if (s.endsWith(".gif")) return "image/gif";
        if (s.endsWith(".woff")) return "font/woff";
        if (s.endsWith(".woff2")) return "font/woff2";
        if (s.endsWith(".ttf")) return "font/ttf";
        if (s.endsWith(".mp3")) return "audio/mpeg";
        if (s.endsWith(".ogg")) return "audio/ogg";
        if (s.endsWith(".wav")) return "audio/wav";
        if (s.endsWith(".mp4")) return "video/mp4";
        if (s.endsWith(".xml")) return "application/xml";
        return "application/octet-stream";
    }
}
