/*
    reo-web bioserver - fan server for Resident Evil Outbreak File #1/#2
    (C) 2026 reo-web contributors. AGPL-3.0-or-later, see LICENSE.txt.

    Framing of the US (SLUS-20765) client, read from SLUS_207.65 (docs/notes/protocol.md section 6):
      header, 8 bytes:  len16 (BE, payload only)  qsw  cmd16 (BE)  pid8  err8  0xFF
      qsw: 0x01 QUERY, 0x02 TELL (reply, copies the QUERY's pid), 0x10 BROADCAST
      string: len16 (= n+2)  sum16  n bytes, each byte  b ^ "MINAMIOH"[i&7] ^ (seed + pid + i)
              sum = (sum(plain) + pid*257) & 0x7FFF, checked by the client
    The server speaks first; the seed is the u16 the server sends in QUERY 0x1002.
*/

package reo.us;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class UsCodec {
    public static final int QUERY = 0x01, TELL = 0x02, BCAST = 0x10;
    public static final int HEADER = 8;
    static final byte[] KEY = "MINAMIOH".getBytes(StandardCharsets.ISO_8859_1);

    private UsCodec() {}

    public static byte[] crypt(byte[] data, int off, int len, int pid, int seed) {
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++)
            out[i] = (byte) (data[off + i] ^ KEY[i & 7] ^ ((seed + pid + i) & 0xFF));
        return out;
    }

    public static int checksum(byte[] plain, int pid) {
        int s = 0;
        for (byte b : plain) s += b & 0xFF;
        return (s + pid * 257) & 0x7FFF;
    }

    public static byte[] frame(int qsw, int cmd, int pid, int err, byte[] payload) {
        byte[] f = new byte[HEADER + payload.length];
        f[0] = (byte) (payload.length >> 8);
        f[1] = (byte) payload.length;
        f[2] = (byte) qsw;
        f[3] = (byte) (cmd >> 8);
        f[4] = (byte) cmd;
        f[5] = (byte) pid;
        f[6] = (byte) err;
        f[7] = (byte) 0xFF;
        System.arraycopy(payload, 0, f, HEADER, payload.length);
        return f;
    }

    /** Payload builder; strings are encrypted with the pid of the frame they go in. */
    public static final class Out {
        private final ByteArrayOutputStream b = new ByteArrayOutputStream();
        private final int pid, seed;
        public Out(int pid, int seed) { this.pid = pid; this.seed = seed; }
        public Out u8(int v) { b.write(v); return this; }
        public Out u16(int v) { b.write(v >> 8); b.write(v); return this; }
        public Out u32(long v) { u16((int) (v >> 16)); return u16((int) v); }
        public Out str(byte[] plain) {
            u16(plain.length + 2);
            u16(checksum(plain, pid));
            b.writeBytes(crypt(plain, 0, plain.length, pid, seed));
            return this;
        }
        public Out str(String s) { return str(s.getBytes(StandardCharsets.ISO_8859_1)); }
        public byte[] bytes() { return b.toByteArray(); }
    }

    /** Payload reader. Reading past the end returns zeros, like the client's own readers. */
    public static final class In {
        private final byte[] p;
        private final int pid, seed;
        private int o;
        public In(byte[] payload, int pid, int seed) { this.p = payload; this.pid = pid; this.seed = seed; }
        public int u8() { return o < p.length ? p[o++] & 0xFF : 0; }
        public int u16() { return (u8() << 8) | u8(); }
        public long u32() { return ((long) u16() << 16) | u16(); }
        public byte[] str() {
            int n = u16() - 2;
            u16();   // sum
            if (n <= 0 || o + n > p.length) { o = Math.min(p.length, o + Math.max(n, 0)); return new byte[0]; }
            byte[] s = crypt(p, o, n, pid, seed);
            o += n;
            return s;
        }
        public String string() { return new String(str(), StandardCharsets.ISO_8859_1); }
    }
}
