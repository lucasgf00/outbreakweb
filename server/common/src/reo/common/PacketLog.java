/*
    reo-web bioserver - fan server for Resident Evil Outbreak File #1/#2
    Based on BioServer (C) 2013-2019 obsrv.org; modifications (C) 2026 reo-web contributors.

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version. See LICENSE.txt.
*/

package reo.common;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Packet logger for protocol work (e.g. first contact with the US client).
 *
 * Every chunk read from / written to a socket is logged with:
 *  - the raw bytes as hexdump (mode "hex"), independent of any framing assumption,
 *  - a best-effort parse of the JP Bioserver 12-byte header for each frame found
 *    in the chunk (modes "header" and "hex"). If the bytes do not look like that
 *    header (trailer ff ff ff, sane length) it says so, which is exactly the
 *    signal we want when the US client speaks something different.
 *
 * Config: packetlog.mode=off|header|hex, packetlog.file=logs/packets.log, packetlog.stdout=false
 */
public final class PacketLog {
    public enum Mode { OFF, HEADER, HEX }

    private static Mode mode = Mode.OFF;
    private static PrintStream out = null;
    private static boolean echo = false;

    private static final Map<Integer, String> NAMES = new HashMap<>();
    static {
        // names from Bioserver1/2 Commands.java (JP protocol)
        Object[][] t = {
            {0x6101, "LOGIN"}, {0x61A0, "UNKN61A0"}, {0x6103, "CHECKVERSION"}, {0x600E, "CHECKRND"},
            {0x61A1, "UNKN61A1"}, {0x6131, "IDHNPAIRS"}, {0x6132, "HNSELECT"}, {0x6104, "UNKN6104/LOGIN_DONE"},
            {0x614C, "MOTHEDAY"}, {0x6190, "CHARSELECT"}, {0x6881, "UNKN6881"}, {0x6882, "UNKN6882"},
            {0x6145, "RANKINGS"}, {0x6141, "UNKN6141"}, {0x6203, "AREACOUNT"}, {0x6205, "AREAPLAYERCNT"},
            {0x6206, "AREASTATUS"}, {0x6204, "AREANAME"}, {0x620A, "AREADESCRIPT"}, {0x6202, "HEARTBEAT"},
            {0x6207, "AREASELECT"}, {0x6209, "EXITAREA"}, {0x6301, "ROOMSCOUNT"}, {0x6303, "ROOMPLAYERCNT"},
            {0x6304, "ROOMSTATUS"}, {0x6302, "ROOMNAME"}, {0x6308, "UNKN6308"}, {0x6305, "ENTERROOM"},
            {0x6401, "SLOTCOUNT"}, {0x6403, "SLOTPLRSTATUS"}, {0x6404, "SLOTSTATUS"}, {0x6402, "SLOTTITLE"},
            {0x640B, "SLOTATTRIB2"}, {0x6405, "SLOTPWDPROT"}, {0x650A, "SLOTSCENTYPE"}, {0x6603, "RULESCOUNT"},
            {0x6607, "RULEATTCOUNT"}, {0x6601, "UNKN6601"}, {0x6602, "UNKN6602"}, {0x6604, "RULEDESCRIPT"},
            {0x6606, "RULEVALUE"}, {0x6605, "RULEATTRIB"}, {0x6608, "ATTRDESCRIPT"}, {0x660E, "ATTRATTRIB"},
            {0x640A, "PLAYERSTATS"}, {0x6408, "EXITSLOTLIST"}, {0x6407, "CREATESLOT"}, {0x6509, "SCENESELECT"},
            {0x6609, "SLOTNAME"}, {0x660B, "SETRULE"}, {0x660C, "UNKN660C"}, {0x6409, "SLOTTIMER"},
            {0x6412, "UNKN6412"}, {0x6504, "UNKN6504"}, {0x6501, "CANCELSLOT"}, {0x6502, "LEAVESLOT"},
            {0x6503, "PLAYERSTATBC"}, {0x6505, "CANCELSLOTBC"}, {0x6506, "PLAYEROK"}, {0x6508, "STARTGAME"},
            {0x6701, "CHATIN"}, {0x6702, "CHATOUT"}, {0x6910, "GETREADY"}, {0x6911, "PLAYERCOUNT"},
            {0x6912, "PLAYERNUMBER"}, {0x6913, "PLAYERSTAT"}, {0x6917, "PLAYERSCORE"}, {0x6915, "GAMESESSION"},
            {0x6914, "GAMEDIFF"}, {0x6916, "GSINFO"}, {0x6002, "UNKN6002"}, {0x6210, "ENTERAGL"},
            {0x6213, "AGLSTATS"}, {0x6212, "AGLPLAYERCNT"}, {0x6211, "LEAVEAGL"}, {0x6406, "JOINGAME"},
            {0x6214, "AGLLEAVE"}, {0x6215, "AGLJOIN"}, {0x6801, "GETINFO"}, {0x670D, "EVENTDAT"},
            {0x670E, "EVENTDATBC"}, {0x6707, "BUDDYLIST"}, {0x6703, "CHECKBUDDY"}, {0x6704, "PRIVATEMSG"},
            {0x6705, "PRIVATEMSGBC"}, {0x6181, "UNKN6181"}, {0x6001, "CONNCHECK"}, {0x6006, "LOGOUT"},
            {0x660A, "SLOTPASSWD"}, {0x6138, "POSTGAMEINFO"}, {0x6121, "PATCHSTART"}, {0x6122, "PATCHDATA"},
            {0x6123, "PATCHFOOTER"}, {0x6124, "PATCHLINECHECK"}, {0x6125, "PATCHFINISH"}, {0x6003, "SHUTDOWN"},
            {0x1031, "GSLOGIN"},
        };
        for (Object[] e : t) NAMES.put((Integer) e[0], (String) e[1]);
    }

    private PacketLog() {}

    public static synchronized void init(String modeName, String file, boolean toStdout) throws IOException {
        mode = Mode.valueOf(modeName.trim().toUpperCase());
        echo = toStdout;
        if (mode != Mode.OFF && file != null && !file.isEmpty()) {
            Path f = Path.of(file);
            if (f.getParent() != null) Files.createDirectories(f.getParent());
            out = new PrintStream(new FileOutputStream(f.toFile(), true), true, "UTF-8");
        }
        if (mode != Mode.OFF) System.out.println("packet log: mode=" + mode + " file=" + file);
    }

    public static boolean enabled() {
        return mode != Mode.OFF;
    }

    public static String commandName(int cmd) {
        String n = NAMES.get(cmd);
        return n == null ? "?" : n;
    }

    private static String peer(Object p) {
        try {
            if (p instanceof SocketChannel) return String.valueOf(((SocketChannel) p).getRemoteAddress());
        } catch (IOException | RuntimeException e) {
            return "?";
        }
        return String.valueOf(p);
    }

    /**
     * @param channel  e.g. "file1/lobby", "file1/game", "sniff:80"
     * @param inbound  true = client->server
     */
    public static void log(String channel, boolean inbound, Object peerObj, byte[] buf, int off, int len) {
        if (mode == Mode.OFF || len <= 0) return;
        StringBuilder sb = new StringBuilder();
        sb.append(LocalDateTime.now()).append(' ').append(channel).append(' ')
          .append(inbound ? "C>S" : "S>C").append(' ').append(peer(peerObj))
          .append(" bytes=").append(len).append('\n');
        boolean game = channel.endsWith("/game");
        parseFrames(sb, buf, off, len, game);
        if (mode == Mode.HEX) hexdump(sb, buf, off, len);
        write(sb.toString());
    }

    private static synchronized void write(String s) {
        if (out != null) out.print(s);
        if (echo || out == null) System.out.print(s);
    }

    private static int u8(byte[] b, int i) { return b[i] & 0xff; }
    private static int u16(byte[] b, int i) { return ((b[i] & 0xff) << 8) | (b[i + 1] & 0xff); }

    static void parseFrames(StringBuilder sb, byte[] b, int off, int len, boolean game) {
        int o = off, end = off + len;
        int frames = 0;
        while (o < end && frames < 64) {
            int rem = end - o;
            boolean hdr = rem >= 12 && u8(b, o + 9) == 0xff && u8(b, o + 10) == 0xff && u8(b, o + 11) == 0xff;
            if (hdr) {
                int who = u8(b, o), qsw = u8(b, o + 1), cmd = u16(b, o + 2), plen = u16(b, o + 4), pid = u16(b, o + 6), err = u8(b, o + 8);
                sb.append(String.format("  [jp-hdr] who=%02x(%s) qsw=%02x(%s) cmd=%04x(%s) len=%d pid=%04x err=%02x%s%n",
                        who, whoName(who), qsw, qswName(qsw), cmd, commandName(cmd), plen, pid, err,
                        (12 + plen > rem) ? " [TRUNCATED/fragmented]" : ""));
                o += 12 + plen;
            } else if (game && rem >= 1 && u8(b, o) > 0) {
                int plen = u8(b, o);
                sb.append(String.format("  [game] len=%d first=%s%s%n", plen, hex(b, o, Math.min(8, rem)),
                        plen > rem ? " [TRUNCATED/fragmented]" : ""));
                o += plen;
            } else {
                sb.append(String.format("  [unparsed] %d bytes do not match the JP 12-byte header (who qsw cmd16 len16 pid16 err ff ff ff)%n", rem));
                break;
            }
            frames++;
        }
    }

    private static String whoName(int w) {
        switch (w) {
            case 0x18: return "SERVER";
            case 0x81: return "CLIENT";
            case 0x28: return "GAMESERVER";
            case 0x82: return "GAMECLIENT";
            default: return "?";
        }
    }

    private static String qswName(int q) {
        switch (q) {
            case 0x01: return "QUERY";
            case 0x02: return "TELL";
            case 0x10: return "BROADCAST";
            default: return "?";
        }
    }

    private static String hex(byte[] b, int off, int n) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < n; i++) s.append(String.format("%02x", b[off + i] & 0xff));
        return s.toString();
    }

    static void hexdump(StringBuilder sb, byte[] b, int off, int len) {
        for (int line = 0; line < len; line += 16) {
            int n = Math.min(16, len - line);
            sb.append(String.format("  %04x: ", line));
            for (int i = 0; i < 16; i++) {
                if (i < n) sb.append(String.format("%02x ", b[off + line + i] & 0xff));
                else sb.append("   ");
            }
            sb.append(" |");
            for (int i = 0; i < n; i++) {
                int c = b[off + line + i] & 0xff;
                sb.append(c >= 0x20 && c < 0x7f ? (char) c : '.');
            }
            sb.append("|\n");
        }
    }
}
