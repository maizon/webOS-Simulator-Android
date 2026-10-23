package io.maizon.webossim;

import android.content.Context;
import android.net.wifi.WifiManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Compatibility implementation of the MaizoSNES IPK's native LAN companion.
 *
 * Android does not ship Node.js/webos-service. This Java adapter reproduces the
 * existing IPK's AUTHENTICATED wire protocol instead of pretending to execute
 * arbitrary Node modules. Supported app: com.maizon.maizosnes.webos only.
 *
 *  - 127.0.0.1 ephemeral WebSocket /bridge, authenticated with 192-bit token.
 *  - TCP 55435 MZNP transport; UDP 55436 MZSCAN2/3; UDP 55437 MZRC/1 pad.
 *  - Up to three remote pads (P2-P4); the IPK UI asks consent before accepting.
 *  - No internet relay/proxy, ROM storage, arbitrary process execution or LAN
 *    sockets granted to other apps.
 */
public final class MaizoLanRuntime {
    public static final String APP_ID = "com.maizon.maizosnes.webos";
    private static final int TCP_PORT = 55435, DISCOVERY_PORT = 55436, PAD_PORT = 55437;
    private static final int MAX_WS = 65536, MAX_TCP_CHUNK = 32768;
    private final Context context;
    private final int expectedHttpPort;
    private final SecureRandom random = new SecureRandom();
    private final ExecutorService network = Executors.newCachedThreadPool(task -> {
        Thread t = new Thread(task, "maizo-lan-network"); t.setDaemon(true); return t;
    });
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread t = new Thread(task, "maizo-lan-ticker"); t.setDaemon(true); return t;
    });
    private final Map<String, PendingPad> pending = new LinkedHashMap<>();
    private final Map<Integer, RemotePad> pads = new HashMap<>();
    private final Reporter reporter;
    private volatile ServerSocket websocketServer, tcpServer;
    private volatile DatagramSocket padUdp, discoveryUdp, scanUdp;
    private volatile Socket tcpSocket;
    private volatile Peer browser;
    private volatile WifiManager.MulticastLock multicastLock;
    private volatile boolean closed;
    private volatile int port;
    private volatile String token, title = "MaizoSNES", romHash = "", lastFailure = "";
    private int tcpEpoch, queuedReports;

    public interface Reporter { void log(String level, String text); }

    public MaizoLanRuntime(Context context, int expectedHttpPort, Reporter reporter) {
        this.context = context.getApplicationContext();
        this.expectedHttpPort = expectedHttpPort;
        this.reporter = reporter;
    }

    private void log(String text) {
        if (reporter != null) reporter.log("LAN", text);
    }

    public synchronized JSONObject open() {
        if (closed) return LunaServiceEngine.error(-1, "LAN runtime closed");
        if (websocketServer == null) {
            try {
                byte[] secret = new byte[24]; random.nextBytes(secret);
                token = MaizoPadProtocol.hex(secret);
                websocketServer = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
                websocketServer.setSoTimeout(1000);
                port = websocketServer.getLocalPort();
                network.execute(this::acceptBrowsers);
                ticker.scheduleAtFixedRate(this::tickPads, 80, 80, TimeUnit.MILLISECONDS);
                log("Authenticated LAN companion started on localhost:" + port);
            } catch (Exception e) {
                lastFailure = "Loopback websocket: " + e.getMessage();
                return LunaServiceEngine.error(-1, lastFailure);
            }
        }
        JSONObject response = state();
        try { response.put("subscribed", true); } catch (JSONException ignored) {}
        return response;
    }

    public JSONObject health() {
        JSONObject response = state();
        try {
            response.put("node", false);
            response.put("active", !closed && websocketServer != null && !websocketServer.isClosed());
            response.put("description", "MaizoSNES Android-native MZNP/MZRC transport adapter (not a Node.js runtime)");
        } catch (JSONException ignored) {}
        return response;
    }

    public JSONObject state() {
        JSONObject response = LunaServiceEngine.result("ANDROID_LAN");
        JSONObject capabilities = new JSONObject();
        try {
            capabilities.put("udp", padUdp != null && !padUdp.isClosed());
            capabilities.put("discovery", discoveryUdp != null && !discoveryUdp.isClosed());
            capabilities.put("tcp", tcpServer != null && !tcpServer.isClosed());
            // The bundled service.js does NOT run. Match protocol, not an unavailable runtime.
            capabilities.put("node", false);
            capabilities.put("androidAdapter", true);
            capabilities.put("service", "Android Java MZNP/MZRC compatible bridge");
            capabilities.put("localOnly", true);
            response.put("port", port);
            response.put("token", token == null ? "" : token);
            response.put("capabilities", capabilities);
            response.put("lastFailure", lastFailure);
        } catch (JSONException ignored) {}
        return response;
    }

    private void acceptBrowsers() {
        while (!closed && websocketServer != null && !websocketServer.isClosed()) {
            try {
                Socket client = websocketServer.accept();
                client.setSoTimeout(8000);
                if (!client.getInetAddress().isLoopbackAddress() || browser != null) {
                    client.close(); continue;
                }
                network.execute(() -> acceptBrowser(client));
            } catch (SocketTimeoutException ignored) {
            } catch (IOException e) {
                if (!closed) log("Browser accept: " + e.getMessage());
            }
        }
    }

    private void acceptBrowser(Socket socket) {
        Peer local = null;
        try {
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            int b, match = 0;
            while (header.size() < 8192 && (b = input.read()) != -1) {
                header.write(b);
                match = b == "\r\n\r\n".charAt(match) ? match + 1 : (b == '\r' ? 1 : 0);
                if (match == 4) break;
            }
            if (match != 4) throw new IOException("Malformed websocket upgrade");
            String headers = header.toString("ISO-8859-1");
            String[] lines = headers.split("\r\n");
            if (lines.length < 4 || !"GET /bridge HTTP/1.1".equals(lines[0]))
                throw new IOException("Invalid websocket endpoint");
            String key = null, origin = null, upgrade = null, version = null;
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon < 1) continue;
                String name = lines[i].substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT);
                String value = lines[i].substring(colon + 1).trim();
                if ("sec-websocket-key".equals(name)) key = value;
                else if ("origin".equals(name)) origin = value;
                else if ("upgrade".equals(name)) upgrade = value;
                else if ("sec-websocket-version".equals(name)) version = value;
            }
            String expectedOrigin = "http://127.0.0.1:" + expectedHttpPort;
            if (!expectedOrigin.equals(origin) || !"websocket".equalsIgnoreCase(upgrade) ||
                    !"13".equals(version) || key == null ||
                    !key.matches("[A-Za-z0-9+/]{22}==")) {
                throw new IOException("Rejected websocket origin/headers");
            }
            byte[] secret = android.util.Base64.decode(key, android.util.Base64.DEFAULT);
            if (secret.length != 16) throw new IOException("Invalid websocket key");
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((key +
                    "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII));
            String response = android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP);
            output.write(("HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + response + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            output.flush();
            synchronized (this) {
                if (closed || browser != null) throw new IOException("Browser already connected");
                local = new Peer(socket);
                browser = local;
            }
            // The authorization token is not placed in HTTP headers or cookies.
            local.run();
        } catch (Exception e) {
            if (!closed) log("Loopback: " + e.getClass().getSimpleName() + " " + e.getMessage());
        } finally {
            if (local != null) {
                synchronized (this) { if (browser == local) browser = null; }
                local.close();
                stopTcp();
                stopPads();
                stopDiscovery();
            } else {
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
    }

    private final class Peer {
        private final Socket socket;
        private final DataInputStream in;
        private final OutputStream out;
        private boolean authenticated;
        private volatile boolean gone;

        Peer(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new DataInputStream(socket.getInputStream());
            this.out = socket.getOutputStream();
        }

        synchronized void send(int opcode, byte[] payload) throws IOException {
            if (gone || closed) throw new IOException("Websocket closed");
            if (payload.length > MAX_WS) throw new IOException("Oversized websocket reply");
            int size = payload.length;
            out.write(0x80 | opcode);
            if (size < 126) out.write(size);
            else {
                out.write(126);
                out.write((size >> 8) & 0xff); out.write(size & 0xff);
            }
            out.write(payload); out.flush();
        }
        void json(JSONObject data) {
            try { send(1, data.toString().getBytes(StandardCharsets.UTF_8)); }
            catch (IOException e) { close(); }
        }
        void run() throws IOException {
            socket.setSoTimeout(1000);
            long started = System.currentTimeMillis();
            while (!gone && !closed) {
                try {
                    int first = in.readUnsignedByte();
                    int second = in.readUnsignedByte();
                    int opcode = first & 15;
                    if ((first & 0xf0) != 0x80 || (second & 0x80) == 0)
                        throw new IOException("Websocket continuation/unmasked frame not supported");
                    int size = second & 0x7f;
                    if (size == 126) size = in.readUnsignedShort();
                    else if (size == 127) {
                        int high = in.readInt(), low = in.readInt();
                        if (high != 0 || low < 0) throw new IOException("Oversized frame");
                        size = low;
                    }
                    if (size > MAX_WS) throw new IOException("Oversized websocket frame");
                    byte[] mask = new byte[4], bytes = new byte[size];
                    in.readFully(mask);
                    in.readFully(bytes);
                    for (int i = 0; i < size; i++) bytes[i] ^= mask[i & 3];
                    if (opcode == 8) return;
                    if (opcode == 9) { send(10, bytes); continue; }
                    if (opcode != 1 && opcode != 2) throw new IOException("Websocket opcode not supported");
                    if (!authenticated) {
                        if (opcode != 1) throw new IOException("Not authorized");
                        JSONObject message = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                        if (!"auth".equals(message.optString("t")) ||
                                !token.equals(message.optString("token")))
                            throw new IOException("Unauthorized websocket");
                        authenticated = true;
                        socket.setSoTimeout(0);
                        json(event("ready").put("capabilities", state().optJSONObject("capabilities")));
                    } else if (opcode == 1) {
                        if (size > 4096) throw new IOException("Oversized LAN command");
                        JSONObject message = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                        handleCommand(message);
                    } else {
                        if (size > MAX_TCP_CHUNK) throw new IOException("Oversized TCP fragment");
                        Socket tcp = tcpSocket;
                        if (tcp == null || tcp.isClosed()) {
                            emit(event("error").put("reason", "TCP LAN session unavailable"));
                            continue;
                        }
                        synchronized (tcp) {
                            tcp.getOutputStream().write(bytes);
                        }
                    }
                } catch (SocketTimeoutException ignored) {
                    if (!authenticated && System.currentTimeMillis() - started > 5000)
                        throw new IOException("LAN browser authentication expired");
                } catch (JSONException e) {
                    throw new IOException("Invalid websocket command JSON", e);
                }
            }
        }
        void close() {
            gone = true;
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private static JSONObject event(String type) {
        JSONObject out = new JSONObject();
        try { out.put("t", type); } catch (JSONException ignored) {}
        return out;
    }
    private void emit(JSONObject e) {
        Peer peer = browser;
        if (peer != null && peer.authenticated && !peer.gone) peer.json(e);
    }
    private void failure(String component, Exception exception) {
        lastFailure = component + ": " + exception.getMessage();
        log(lastFailure);
        try { emit(event("capability-failed").put("component", component)
                .put("message", String.valueOf(exception.getMessage()))); }
        catch (JSONException ignored) {}
    }

    private void handleCommand(JSONObject command) throws JSONException {
        String action = command.optString("t", "");
        switch (action) {
            case "status":
                emit(event("status").put("capabilities", state().optJSONObject("capabilities"))); break;
            case "pad-start":
                startPads(command.optString("title", "MaizoSNES"),
                          command.optString("romHash", "")); break;
            case "pad-stop": stopPads(); break;
            case "pad-decide":
                decide(command.optString("nonce", ""), command.optBoolean("accept", false)); break;
            case "tcp-host": startTcpHost(); break;
            case "tcp-join": joinTcp(command.optString("ip"), command.optInt("port", -1)); break;
            case "tcp-stop": stopTcp(); break;
            case "scan": scanLAN(); break;
            default: emit(event("error").put("reason", "Unsupported LAN command")); break;
        }
    }

    private void startPads(String game, String hash) {
        if (game != null) title = game.replaceAll("[^\\x20-\\x7e]", "_")
                .replace('|', '_').substring(0, Math.min(76, game.length()));
        if (hash != null && hash.matches("[0-9a-f]{16}")) romHash = hash;
        DatagramSocket existing = padUdp;
        if (existing != null && !existing.isClosed()) {
            try { emit(event("pad-running").put("port", PAD_PORT)); }
            catch (JSONException ignored) {}
            return;
        }
        try {
            DatagramSocket socket = new DatagramSocket(new InetSocketAddress("0.0.0.0", PAD_PORT));
            socket.setSoTimeout(600);
            padUdp = socket;
            network.execute(() -> padLoop(socket));
            emit(event("pad-running").put("port", PAD_PORT));
            startTcpHost();
        } catch (Exception e) { failure("pad", e); }
    }

    private void padLoop(DatagramSocket socket) {
        while (!closed && socket == padUdp && !socket.isClosed()) {
            try {
                byte[] buffer = new byte[MaizoPadProtocol.MAX_DATAGRAM];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String ip = packet.getAddress().getHostAddress();
                if (!MaizoPadProtocol.privateIPv4(ip)) continue;
                MaizoPadProtocol.Packet decoded = MaizoPadProtocol.parse(packet.getData(), packet.getLength());
                if (decoded != null) processPad(decoded, packet.getAddress(), packet.getPort());
            } catch (SocketTimeoutException ignored) {
            } catch (Exception e) { if (!socket.isClosed()) failure("pad", e); }
        }
    }

    private static final class PendingPad {
        String nonce, name, ip;
        int port, slot;
        InetAddress address;
        long since;
    }
    private static final class RemotePad {
        String nonce, token, name, ip;
        int port, slot, buttons, sequence;
        boolean seenSequence;
        InetAddress address;
        long last;
    }

    private synchronized void processPad(MaizoPadProtocol.Packet packet, InetAddress address,
                                         int port) throws JSONException {
        String ip = address.getHostAddress(), endpoint = ip + ":" + port;
        long now = System.currentTimeMillis();
        if (packet.type == 1) {
            for (RemotePad active : pads.values()) {
                if (active.nonce.equals(packet.nonce) && active.ip.equals(ip)) {
                    active.port = port; active.address = address; active.last = now;
                    sendPad(address, port, 3, active.nonce, active.token, active.slot, title, 0, 0);
                    return;
                }
            }
            PendingPad request = pending.get(packet.nonce);
            if (request == null) {
                if (pending.size() >= 3) {
                    sendPad(address, port, 4, packet.nonce, null, 0, "Limite de controles pendentes", 0, 0);
                    return;
                }
                int slot = 0;
                for (int s = 2; s <= 4; s++) {
                    boolean taken = pads.containsKey(s);
                    for (PendingPad item : pending.values()) if (item.slot == s) taken = true;
                    if (!taken) { slot = s; break; }
                }
                if (slot == 0) {
                    sendPad(address, port, 4, packet.nonce, null, 0, "Sem slot disponivel", 0, 0);
                    return;
                }
                request = new PendingPad();
                request.nonce = packet.nonce; request.ip = ip; request.port = port;
                request.address = address; request.slot = slot;
                request.name = packet.text == null ? "Jogador" : packet.text;
                request.since = now;
                pending.put(packet.nonce, request);
                emit(event("pad-offer").put("nonce", request.nonce)
                        .put("slot", slot).put("name", request.name).put("ip", ip));
            }
            if (request.ip.equals(ip)) {
                request.address = address; request.port = port;
                sendPad(address, port, 2, packet.nonce, null, 0, "", 0, 0);
            }
            return;
        }
        RemotePad peer = null;
        for (RemotePad value : pads.values()) {
            if (value.token.equals(packet.token) && value.ip.equals(ip) && value.port == port) {
                peer = value; break;
            }
        }
        if (peer == null) return;
        peer.last = now;
        if (packet.type == 5) {
            if (peer.seenSequence && packet.sequence - peer.sequence <= 0) return;
            peer.sequence = packet.sequence; peer.seenSequence = true;
            int buttons = packet.buttons & 65535;
            if (peer.buttons != buttons) {
                peer.buttons = buttons;
                emit(event("pad-input").put("slot", peer.slot).put("buttons", buttons));
            }
        } else if (packet.type == 6) {
            sendPad(address, port, 7, packet.nonce, peer.token, 0, "", 0, 0);
        } else if (packet.type == 8) {
            pads.remove(peer.slot);
            emit(event("pad-input").put("slot", peer.slot).put("buttons", 0));
            emit(event("pad-disconnected").put("slot", peer.slot));
        }
    }

    private synchronized void decide(String nonce, boolean accepted) throws JSONException {
        PendingPad request = pending.remove(nonce);
        if (request == null) return;
        if (!accepted) {
            sendPad(request.address, request.port, 4, request.nonce, null, 0,
                    "Controle recusado no dispositivo", 0, 0);
            return;
        }
        if (pads.containsKey(request.slot)) return;
        RemotePad peer = new RemotePad();
        byte[] secret = new byte[8]; random.nextBytes(secret);
        peer.token = MaizoPadProtocol.hex(secret);
        peer.nonce = request.nonce; peer.ip = request.ip; peer.name = request.name;
        peer.address = request.address; peer.port = request.port; peer.slot = request.slot;
        peer.last = System.currentTimeMillis();
        pads.put(peer.slot, peer);
        sendPad(peer.address, peer.port, 3, peer.nonce, peer.token, peer.slot, title, 0, 0);
        emit(event("pad-connected").put("slot", peer.slot).put("name", peer.name));
    }

    private void sendPad(InetAddress ip, int port, int type, String nonce, String token,
                         int slot, String message, int sequence, int buttons) {
        DatagramSocket socket = padUdp;
        if (socket == null || socket.isClosed()) return;
        try {
            byte[] payload = MaizoPadProtocol.encode(type, nonce, token, slot, message, sequence, buttons);
            socket.send(new DatagramPacket(payload, payload.length, ip, port));
        } catch (IOException e) { log("UDP reply: " + e.getMessage()); }
    }

    private synchronized void tickPads() {
        if (closed) return;
        long now = System.currentTimeMillis();
        for (String nonce : new ArrayList<>(pending.keySet())) {
            PendingPad p = pending.get(nonce);
            if (p != null && now - p.since > 15000) {
                pending.remove(nonce);
                sendPad(p.address, p.port, 4, p.nonce, null, 0, "Tempo de autorizacao esgotado", 0, 0);
            }
        }
        for (int slot : new ArrayList<>(pads.keySet())) {
            RemotePad p = pads.get(slot);
            if (p == null) continue;
            try {
                if (now - p.last >= 650 && p.buttons != 0) {
                    p.buttons = 0;
                    emit(event("pad-input").put("slot", slot).put("buttons", 0));
                }
                if (now - p.last > 12000) {
                    pads.remove(slot);
                    emit(event("pad-disconnected").put("slot", slot));
                }
            } catch (JSONException ignored) {}
        }
    }

    private synchronized void stopPads() {
        DatagramSocket socket = padUdp; padUdp = null;
        if (socket != null) socket.close();
        try {
            for (RemotePad p : pads.values())
                emit(event("pad-input").put("slot", p.slot).put("buttons", 0));
        } catch (JSONException ignored) {}
        pads.clear(); pending.clear();
        emit(event("pad-stopped"));
    }

    private synchronized void startTcpHost() {
        if (closed) return;
        ServerSocket existing = tcpServer;
        if (existing != null && !existing.isClosed()) {
            try { emit(event("tcp-listening").put("port", TCP_PORT)); }
            catch (JSONException ignored) {}
            return;
        }
        try {
            ServerSocket listening = new ServerSocket();
            listening.setReuseAddress(true);
            listening.bind(new InetSocketAddress("0.0.0.0", TCP_PORT), 4);
            listening.setSoTimeout(600);
            tcpServer = listening;
            network.execute(() -> tcpAcceptLoop(listening));
            emit(event("tcp-listening").put("port", TCP_PORT));
            startDiscovery();
        } catch (Exception e) { failure("tcp", e); }
    }

    private void tcpAcceptLoop(ServerSocket listening) {
        while (!closed && tcpServer == listening && !listening.isClosed()) {
            try {
                Socket peer = listening.accept();
                String ip = peer.getInetAddress().getHostAddress();
                if (tcpSocket != null || !MaizoPadProtocol.privateIPv4(ip) || browser == null ||
                        !browser.authenticated) {
                    peer.close();
                    continue;
                }
                peer.setTcpNoDelay(true);
                peer.setKeepAlive(true);
                stopPads();
                bindTcp(peer, true, ip);
            } catch (SocketTimeoutException ignored) {
            } catch (IOException e) {
                if (!closed && !listening.isClosed()) failure("tcp", e);
            }
        }
    }

    private void bindTcp(Socket peer, boolean hosting, String address) {
        tcpSocket = peer; int epoch = ++tcpEpoch;
        try { emit(event("tcp-connected").put("host", hosting)
                .put("peer", address).put("port", TCP_PORT)); }
        catch (JSONException ignored) {}
        network.execute(() -> {
            try {
                InputStream in = peer.getInputStream();
                byte[] buffer = new byte[MAX_TCP_CHUNK];
                int n;
                while (!closed && peer == tcpSocket && (n = in.read(buffer)) != -1) {
                    Peer listener = browser;
                    if (listener == null || !listener.authenticated) break;
                    listener.send(2, Arrays.copyOf(buffer, n));
                }
            } catch (Exception e) {
                if (!closed && peer == tcpSocket) {
                    try { emit(event("tcp-error").put("message", String.valueOf(e.getMessage()))); }
                    catch (JSONException ignored) {}
                }
            } finally {
                try { peer.close(); } catch (IOException ignored) {}
                if (tcpSocket == peer) {
                    tcpSocket = null;
                    try { emit(event("tcp-closed").put("epoch", epoch)); }
                    catch (JSONException ignored) {}
                }
            }
        });
    }

    private void joinTcp(String ip, int port) {
        if (!MaizoPadProtocol.privateIPv4(ip) || port != TCP_PORT) {
            try { emit(event("tcp-error").put("message", "Only local IPv4 and port 55435 allowed")); }
            catch (JSONException ignored) {}
            return;
        }
        stopPads(); stopDiscovery(); stopTcp();
        network.execute(() -> {
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(ip, port), 8000);
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                bindTcp(socket, false, ip);
            } catch (IOException e) {
                try { emit(event("tcp-error").put("message", "TCP " + e.getMessage())); }
                catch (JSONException ignored) {}
            }
        });
    }

    private synchronized void stopTcp() {
        Socket tcp = tcpSocket; tcpSocket = null;
        if (tcp != null) try { tcp.close(); } catch (IOException ignored) {}
        ServerSocket server = tcpServer; tcpServer = null;
        if (server != null) try { server.close(); } catch (IOException ignored) {}
    }

    private synchronized void startDiscovery() {
        if (closed || discoveryUdp != null) return;
        try {
            acquireMulticast();
            DatagramSocket socket = new DatagramSocket(new InetSocketAddress("0.0.0.0", DISCOVERY_PORT));
            socket.setSoTimeout(600);
            discoveryUdp = socket;
            network.execute(() -> discoveryLoop(socket));
            log("UDP discovery 55436 active");
        } catch (Exception e) { failure("discovery", e); }
    }
    private void discoveryLoop(DatagramSocket socket) {
        while (!closed && socket == discoveryUdp && !socket.isClosed()) {
            try {
                byte[] bytes = new byte[24];
                DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
                socket.receive(packet);
                String remoteIp = packet.getAddress().getHostAddress();
                if (!MaizoPadProtocol.privateIPv4(remoteIp) || packet.getLength() > 16) continue;
                String text = new String(bytes, 0, packet.getLength(), StandardCharsets.US_ASCII);
                if (!text.equals("MZSCAN2") && !text.equals("MZSCAN3")) continue;
                String label = title.replace("|", "_");
                String mode = padUdp != null ? "B" : "L";
                String answer = text.equals("MZSCAN3")
                    ? "MZOFFER3|" + TCP_PORT + "|" + label + "|" + romHash + "|0||" + mode
                    : "MZOFFER2|" + TCP_PORT + "|" + label + "|" + romHash + "|0";
                byte[] reply = answer.getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(reply, reply.length, packet.getAddress(), packet.getPort()));
            } catch (SocketTimeoutException ignored) {
            } catch (Exception e) { if (!socket.isClosed()) failure("discovery", e); }
        }
    }

    private synchronized void stopDiscovery() {
        DatagramSocket socket = discoveryUdp; discoveryUdp = null;
        if (socket != null) socket.close();
        WifiManager.MulticastLock lock = multicastLock; multicastLock = null;
        if (lock != null) try { if (lock.isHeld()) lock.release(); } catch (RuntimeException ignored) {}
    }
    private void acquireMulticast() {
        try {
            WifiManager manager = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (manager == null) return;
            WifiManager.MulticastLock lock = manager.createMulticastLock("maizo-local-discovery");
            lock.setReferenceCounted(false);
            lock.acquire(); multicastLock = lock;
        } catch (Exception e) { log("Wi-Fi multicast lock unavailable: " + e.getMessage()); }
    }

    private void scanLAN() {
        if (scanUdp != null) {
            try { emit(event("scan-error").put("message", "Scan already active")); }
            catch (JSONException ignored) {}
            return;
        }
        network.execute(() -> {
            Map<String, JSONObject> results = new LinkedHashMap<>();
            try (DatagramSocket socket = new DatagramSocket(0)) {
                scanUdp = socket;
                socket.setBroadcast(true);
                socket.setSoTimeout(200);
                ArrayList<InetAddress> targets = new ArrayList<>();
                targets.add(InetAddress.getByName("255.255.255.255"));
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                while (interfaces != null && interfaces.hasMoreElements()) {
                    NetworkInterface iface = interfaces.nextElement();
                    for (InterfaceAddress address : iface.getInterfaceAddresses()) {
                        InetAddress broadcast = address.getBroadcast();
                        if (broadcast != null && broadcast instanceof Inet4Address &&
                                MaizoPadProtocol.privateIPv4(address.getAddress().getHostAddress()))
                            targets.add(broadcast);
                    }
                }
                for (InetAddress target : targets) {
                    for (String query : new String[]{"MZSCAN2", "MZSCAN3"}) {
                        byte[] bytes = query.getBytes(StandardCharsets.US_ASCII);
                        try { socket.send(new DatagramPacket(bytes, bytes.length, target, DISCOVERY_PORT)); }
                        catch (IOException ignored) {}
                    }
                }
                long until = System.currentTimeMillis() + 1800;
                while (!closed && System.currentTimeMillis() < until) {
                    try {
                        byte[] bytes = new byte[512];
                        DatagramPacket reply = new DatagramPacket(bytes, bytes.length);
                        socket.receive(reply);
                        String ip = reply.getAddress().getHostAddress();
                        if (!MaizoPadProtocol.privateIPv4(ip)) continue;
                        String data = new String(reply.getData(), 0, reply.getLength(), StandardCharsets.UTF_8);
                        if (!data.startsWith("MZOFFER2|") && !data.startsWith("MZOFFER3|")) continue;
                        String[] fields = data.split("\\|", -1);
                        if (fields.length < 5 || !String.valueOf(TCP_PORT).equals(fields[1])) continue;
                        JSONObject room = new JSONObject().put("ip", ip).put("port", TCP_PORT)
                            .put("title", fields[2].substring(0, Math.min(76, fields[2].length())))
                            .put("hash", fields[3].matches("[0-9a-f]{16}") ? fields[3] : "")
                            .put("mode", data.startsWith("MZOFFER3|") && fields.length > 6
                                    ? fields[6] : "legacy");
                        results.put(ip, room);
                        if (results.size() >= 40) break;
                    } catch (SocketTimeoutException ignored) {}
                }
            } catch (Exception e) {
                try { emit(event("scan-error").put("message", "Scan: " + e.getMessage())); }
                catch (JSONException ignored) {}
            } finally {
                scanUdp = null;
                JSONArray rooms = new JSONArray();
                for (JSONObject item : results.values()) rooms.put(item);
                try { emit(event("scan-results").put("rooms", rooms)); }
                catch (JSONException ignored) {}
            }
        });
    }

    public synchronized void close() {
        if (closed) return;
        closed = true;
        Peer listener = browser; browser = null;
        if (listener != null) listener.close();
        ServerSocket server = websocketServer; websocketServer = null;
        if (server != null) try { server.close(); } catch (IOException ignored) {}
        stopTcp(); stopPads(); stopDiscovery();
        DatagramSocket scanning = scanUdp; scanUdp = null;
        if (scanning != null) scanning.close();
        ticker.shutdownNow(); network.shutdownNow();
        token = "";
        log("LAN service stopped; Wi-Fi/WSS fallback unaffected");
    }
}
