package reo.us;

import java.nio.charset.StandardCharsets;

/**
 * Tracker data between web pages (protocol.md 6.18): KkService.trkRelay() checks the gateway's "REOGW-TRK" + 11
 * bytes and builds "REOGW-TRK" + u32 tag + the same 11 bytes for the other members. The relay to the members of the
 * same game only is checked end to end by tools/kktrk.py (KkLocal).
 *   javac -cp build/reo-bioserver.jar -d OUT tests/TrkRelayTest.java && java -cp build/reo-bioserver.jar:OUT reo.us.TrkRelayTest
 */
public class TrkRelayTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) fails++; }

    static byte[] trk(int slot, int ch, int st, int virus, int av, int herb, int bleed) {
        byte[] p = "REOGW-TRK".getBytes(StandardCharsets.ISO_8859_1), d = new byte[p.length + 11];
        System.arraycopy(p, 0, d, 0, p.length);
        int n = p.length;
        d[n] = (byte) slot; d[n + 1] = (byte) ch; d[n + 2] = (byte) st;
        int[] w = {virus, av, herb, bleed};
        for (int i = 0; i < 4; i++) { d[n + 3 + 2 * i] = (byte) (w[i] >> 8); d[n + 4 + 2 * i] = (byte) w[i]; }
        return d;
    }

    static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }

    public static void main(String[] a) {
        byte[] out = KkService.trkRelay(trk(1, 7, 2, 343, 0, 600, 1115), 0x1234ABCD);
        check(out != null && hex(out).equals(hex("REOGW-TRK".getBytes(StandardCharsets.ISO_8859_1)) + "1234abcd" + "010702" + "0157" + "0000" + "0258" + "045b"),
                "valid notice -> REOGW-TRK + tag + body (" + (out == null ? "null" : hex(out)) + ")");
        check(KkService.trkRelay(trk(4, 7, 2, 343, 0, 0, 0), 1) == null, "slot 4 refused");
        check(KkService.trkRelay(trk(0, 8, 0, 343, 0, 0, 0), 1) == null, "character 8 refused");
        check(KkService.trkRelay(trk(0, 0, 0, 1001, 0, 0, 0), 1) == null, "virus over 1000 refused");
        check(KkService.trkRelay(trk(3, 0, 255, 1000, 65535, 65535, 65535), 1) != null, "limits accepted");
        byte[] longer = java.util.Arrays.copyOf(trk(0, 0, 0, 1, 0, 0, 0), 21);
        check(KkService.trkRelay(longer, 1) == null, "wrong length refused");
        byte[] other = trk(0, 0, 0, 1, 0, 0, 0); other[6] = 'X';
        check(KkService.trkRelay(other, 1) == null, "other prefix refused");
        System.out.println(fails == 0 ? "TrkRelayTest: all ok" : "TrkRelayTest: " + fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
