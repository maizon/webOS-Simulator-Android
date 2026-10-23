package io.maizon.webossim;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Locale;

/** MaizoSNES MZRC/1 UDP wire format, independent of Android and webOS runtimes. */
public final class MaizoPadProtocol {
    public static final int MAGIC = 0x4d5a5243, VERSION = 1, MAX_DATAGRAM = 256;
    private MaizoPadProtocol() {}

    public static final class Packet {
        public final int type;
        public final String nonce, token, text;
        public final int sequence, buttons, slot;
        private Packet(int type, String nonce, String token, String text,
                       int sequence, int buttons, int slot) {
            this.type = type; this.nonce = nonce; this.token = token; this.text = text;
            this.sequence = sequence; this.buttons = buttons; this.slot = slot;
        }
    }

    public static Packet parse(byte[] bytes, int size) {
        if (bytes == null || size < 6 || size > MAX_DATAGRAM || size > bytes.length)
            return null;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 0, size));
            if (in.readInt() != MAGIC || in.readUnsignedByte() != VERSION) return null;
            int type = in.readUnsignedByte();
            String nonce = null, token = null, text = null;
            int seq = 0, buttons = 0, slot = 0;
            switch (type) {
                case 1:
                    nonce = hex(read8(in));
                    text = in.readUTF();
                    if (text.length() > 32) return null;
                    break;
                case 2:
                    nonce = hex(read8(in));
                    break;
                case 3:
                    nonce = hex(read8(in));
                    token = hex(read8(in));
                    slot = in.readUnsignedByte();
                    text = in.readUTF();
                    break;
                case 4:
                    nonce = hex(read8(in));
                    text = in.readUTF();
                    break;
                case 5:
                    token = hex(read8(in));
                    seq = in.readInt();
                    buttons = in.readUnsignedShort();
                    break;
                case 6: case 7:
                    token = hex(read8(in));
                    nonce = hex(read8(in)); // stamp/echo
                    break;
                case 8:
                    token = hex(read8(in));
                    break;
                default: return null;
            }
            return in.available() == 0 ? new Packet(type, nonce, token, text, seq, buttons, slot) : null;
        } catch (Exception ignored) { return null; }
    }

    public static byte[] encode(int type, String nonce, String token, int slot,
                                String text, int sequence, int buttons) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(out);
        data.writeInt(MAGIC);
        data.writeByte(VERSION);
        data.writeByte(type);
        switch (type) {
            case 1:
                data.write(unhex8(nonce)); data.writeUTF(shorten(text, 32)); break;
            case 2:
                data.write(unhex8(nonce)); break;
            case 3:
                data.write(unhex8(nonce)); data.write(unhex8(token));
                if (slot < 2 || slot > 4) throw new IOException("Invalid controller slot");
                data.writeByte(slot); data.writeUTF(shorten(text, 76)); break;
            case 4:
                data.write(unhex8(nonce)); data.writeUTF(shorten(text, 80)); break;
            case 5:
                data.write(unhex8(token)); data.writeInt(sequence); data.writeShort(buttons); break;
            case 6: case 7:
                data.write(unhex8(token)); data.write(unhex8(nonce)); break;
            case 8:
                data.write(unhex8(token)); break;
            default: throw new IOException("MZRC packet type unsupported");
        }
        data.flush();
        byte[] result = out.toByteArray();
        if (result.length > MAX_DATAGRAM) throw new IOException("MZRC datagram too large");
        return result;
    }

    private static String shorten(String s, int limit) {
        if (s == null) return "";
        return s.length() > limit ? s.substring(0, limit) : s;
    }
    private static byte[] read8(DataInputStream input) throws IOException {
        byte[] bytes = new byte[8]; input.readFully(bytes); return bytes;
    }
    public static String hex(byte[] bytes) {
        final char[] alphabet = "0123456789abcdef".toCharArray();
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            text.append(alphabet[(b >>> 4) & 15]).append(alphabet[b & 15]);
        }
        return text.toString();
    }
    public static byte[] unhex8(String value) throws IOException {
        if (value == null || !value.matches("[a-fA-F0-9]{16}"))
            throw new IOException("Invalid MZRC nonce/token");
        byte[] bytes = new byte[8];
        for (int i = 0; i < 8; i++)
            bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }

    /** RFC1918 IPv4; loopback permitted only for local device-to-device integration tests. */
    public static boolean privateIPv4(String ip) {
        if (ip == null || !ip.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) return false;
        String[] parts = ip.split("\\.");
        int[] n = new int[4];
        for (int i = 0; i < 4; i++) {
            n[i] = Integer.parseInt(parts[i]);
            if (n[i] > 255) return false;
        }
        return n[0] == 10 || (n[0] == 172 && n[1] >= 16 && n[1] <= 31)
            || (n[0] == 192 && n[1] == 168) || n[0] == 127;
    }
}
