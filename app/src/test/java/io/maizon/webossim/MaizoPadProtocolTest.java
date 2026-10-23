package io.maizon.webossim;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

/** Wire compatibility with MaizoSNES Android MZRC/1; no device required. */
public final class MaizoPadProtocolTest {
    private static final String NONCE = "0123456789abcdef", TOKEN = "aabbccddeeff0099";

    @Test public void helloModifiedUtf8RoundTrip() throws Exception {
        byte[] wire = MaizoPadProtocol.encode(1, NONCE, null, 0, "Jogador P2", 0, 0);
        MaizoPadProtocol.Packet packet = MaizoPadProtocol.parse(wire, wire.length);
        assertNotNull(packet);
        assertEquals(1, packet.type);
        assertEquals(NONCE, packet.nonce);
        assertEquals("Jogador P2", packet.text);
        byte[] accents = MaizoPadProtocol.encode(1, NONCE, null, 0, "João", 0, 0);
        assertEquals("João", MaizoPadProtocol.parse(accents, accents.length).text);
    }

    @Test public void challengeAcceptRejectByeAndPingUseExactWireLengths() throws Exception {
        byte[] challenge = MaizoPadProtocol.encode(2, NONCE, null, 0, null, 0, 0);
        assertEquals(14, challenge.length);
        assertEquals(NONCE, MaizoPadProtocol.parse(challenge, challenge.length).nonce);

        byte[] accept = MaizoPadProtocol.encode(3, NONCE, TOKEN, 3, "MaizoSNES", 0, 0);
        MaizoPadProtocol.Packet accepted = MaizoPadProtocol.parse(accept, accept.length);
        assertNotNull(accepted);
        assertEquals(3, accepted.slot);
        assertEquals(TOKEN, accepted.token);
        assertEquals("MaizoSNES", accepted.text);

        byte[] denied = MaizoPadProtocol.encode(4, NONCE, null, 0, "Recusado", 0, 0);
        assertEquals("Recusado", MaizoPadProtocol.parse(denied, denied.length).text);

        byte[] ping = MaizoPadProtocol.encode(6, NONCE, TOKEN, 0, null, 0, 0);
        byte[] pong = MaizoPadProtocol.encode(7, NONCE, TOKEN, 0, null, 0, 0);
        assertEquals(NONCE, MaizoPadProtocol.parse(ping, ping.length).nonce);
        assertEquals(TOKEN, MaizoPadProtocol.parse(pong, pong.length).token);

        byte[] bye = MaizoPadProtocol.encode(8, null, TOKEN, 0, null, 0, 0);
        assertEquals(14, bye.length);
        assertEquals(TOKEN, MaizoPadProtocol.parse(bye, bye.length).token);
    }

    @Test public void inputSequenceButtonsAndHighBitPreserved() throws Exception {
        byte[] pressed = MaizoPadProtocol.encode(5, null, TOKEN, 0, "", 0x01020304, 0xE5AC);
        assertEquals(20, pressed.length);
        MaizoPadProtocol.Packet decoded = MaizoPadProtocol.parse(pressed, pressed.length);
        assertEquals(0x01020304, decoded.sequence);
        assertEquals(0xE5AC, decoded.buttons);
        assertEquals(TOKEN, decoded.token);

        byte[] release = MaizoPadProtocol.encode(5, null, TOKEN, 0, "", 0x01020305, 0);
        assertEquals(0, MaizoPadProtocol.parse(release, release.length).buttons);
    }

    @Test public void malformedAndOversizedDatagramsAreRejected() throws Exception {
        byte[] hello = MaizoPadProtocol.encode(1, NONCE, null, 0, "player", 0, 0);
        assertNull(MaizoPadProtocol.parse(hello, 5));
        assertNull(MaizoPadProtocol.parse(hello, hello.length - 1));
        byte[] corrupted = hello.clone(); corrupted[0] = 0x00;
        assertNull(MaizoPadProtocol.parse(corrupted, corrupted.length));
        corrupted = hello.clone(); corrupted[4] = 2;
        assertNull(MaizoPadProtocol.parse(corrupted, corrupted.length));
        byte[] padded = java.util.Arrays.copyOf(hello, hello.length + 1);
        assertNull(MaizoPadProtocol.parse(padded, padded.length));
        assertNull(MaizoPadProtocol.parse(new byte[257], 257));
        try { MaizoPadProtocol.encode(3, NONCE, TOKEN, 9, "", 0, 0); fail(); }
        catch (IOException expected) { }
        try { MaizoPadProtocol.encode(5, null, "zzz", 0, "", 5, 0); fail(); }
        catch (IOException expected) { }
    }

    @Test public void acceptOnlyPrivateOrSameDeviceIpv4() {
        for (String ip : new String[]{"192.168.1.10","10.5.6.7","172.16.0.1",
                "172.31.255.254","127.0.0.1"}) assertTrue(ip, MaizoPadProtocol.privateIPv4(ip));
        for (String ip : new String[]{"8.8.8.8","172.32.0.1","169.254.1.1",
                "192.169.1.1","0.0.0.0","256.0.0.1","127.0.0.1:55437",
                "::ffff:192.168.1.1","192.168.1.10.evil"}) {
            assertFalse(ip, MaizoPadProtocol.privateIPv4(ip));
        }
    }
}
