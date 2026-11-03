/*
    reo-web bioserver - fan server for Resident Evil Outbreak File #1/#2
    (C) 2026 reo-web contributors. AGPL-3.0-or-later, see LICENSE.txt.

    The US "app01" service (app01.reo.capcom.sf.yav4.com:10127), the first thing a SLUS-20765 client
    talks to after DNAS. It is a news/download service, not the lobby: greeting (0x1001/0x1002),
    optional update (0x6001/0x6002), the news page (0x6101/0x6102), operator messages (0x6401/0x6402),
    extra files (0x6201/0x6202), a stats report (0x6301) and goodbye (0x1004). After it the client
    shows the DNAS EULA and the NETWORK menu, and GO ONLINE goes to regweb (see RegWeb.java).
    Formats and the client addresses they were read from: docs/notes/protocol.md section 6.1.
    Port of the prototype tools/usapp.py.

    Config: us.app.enabled (true), us.app.port (10127), us.app.info_file (optional path to the
    HTML shown as 01/TOP_INFOR.HTM; at most 0x1000 bytes).
*/

package reo.us;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import reo.common.PacketLog;
import reo.common.ServerConfig;
import static reo.us.UsCodec.*;

public final class UsAppService implements Runnable {
    static final int MAX_INFO = 0x1000;
    private final String bind;
    private final int port;
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    /** Clients currently connected, for the HTTP API. */
    public static final AtomicInteger connected = new AtomicInteger();

    public UsAppService(ServerConfig cfg) throws IOException {
        this.bind = cfg.get("bind", "0.0.0.0");
        this.port = cfg.getInt("us.app.port", 10127);
        // default: no news page. 6101 then answers found=1 with size 0, which (BIN_3 0x1F60B0) goes straight
        // to 6401 without 6102 and without the "Press X to continue" page. found=0 aborts the client (-0x3E).
        byte[] info = new byte[0];
        String path = cfg.get("us.app.info_file", "");
        if (!path.isEmpty()) info = Files.readAllBytes(Path.of(path));
        files.put("01/TOP_INFOR.HTM", Arrays.copyOf(info, Math.min(info.length, MAX_INFO)));
        // DATABASE (DOWN TOWN menu): with a 0-byte page the client fetched its 6402 blocks and then waited ~45 s
        // and dropped with "A connection error has occurred". A short page instead (us.app.database_file to change it).
        String db = cfg.get("us.app.database_file", "");
        byte[] dbPage = db.isEmpty()
            ? "<HTML><BODY>DATABASE<BR>Not available on this server yet.</BODY></HTML>".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)
            : Files.readAllBytes(Path.of(db));
        files.put("01/DATABASE.HTM", Arrays.copyOf(dbPage, Math.min(dbPage.length, MAX_INFO)));
        configureAreas(cfg);
    }

    /** Extra files of 6201/6202. File 1 is the RULE table read by BIN_3 through *(0x35EC7C) (0x58A600 and the
     *  getters 0x58A830..0x58A870): without it every pointer is 0, all bytes read 0, and the RULE screen
     *  shows Room Title / Password / players / time / difficulty as "Unadjustable" (red). Layout
     *  (docs/notes/protocol.md 6.8): +0..3 row adjustable (players, waiting time, difficulty, friendly fire),
     *  +4..7 row used (+7 = friendly fire row shown), +8..0xB default option per row, +0xC..0x11 per mode
     *  (scenario, free) {?, difficulty, friendly fire}, +0x12 s16 scenario-mode wait (x30 frames),
     *  +0x14 title adjustable, +0x15 password adjustable, +0x16 default title[15], +0x25 default password[8].
     *  File 0 is the scenario table, copied raw to *(0x324424)+0x15540 (loader 0x5EE990 -> 0x57B700, 0x2100 bytes):
     *  5 records of 0x48 {u8 ?, u8 icon (0x57FC50), u8 scenario id given to the game (0x57FBF0 -> 0x6E881C),
     *  name[0x41] at +3, s32 +0x44 = offset of the description in the text pool at +0xE08 (< 0 = not offered)}.
     *  Without it the Scenario row of RULE is empty and the default (id 0, Outbreak) is played. */
    static final String[][] SCENARIOS = {
        {"Outbreak", "J's Bar, Raccoon City. The night it all began."},
        {"Below Freezing Point", "An underground research facility under the snow."},
        {"The Hive", "Raccoon General Hospital and the Umbrella lab beneath it."},
        {"Hellfire", "The Apple Inn is burning. Find a way out."},
        {"Decisions, Decisions", "Raccoon University. The last chance to escape."},
    };

    static final String EXTRA_NAME = "01/RULE.DAT";
    static final byte[][] EXTRA = {
        scenarios(),
        rule(),
    };

    static byte[] scenarios() {
        byte[] f = new byte[0xE08 + 0x400];
        int pool = 0;
        for (int i = 0; i < SCENARIOS.length; i++) {
            int r = i * 0x48;
            f[r] = 0; f[r + 1] = (byte) i; f[r + 2] = (byte) i;
            byte[] nm = SCENARIOS[i][0].getBytes(StandardCharsets.ISO_8859_1);
            System.arraycopy(nm, 0, f, r + 3, Math.min(nm.length, 0x40));
            ByteBuffer.wrap(f, r + 0x44, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(pool);
            byte[] d = ("<BODY><SIZE=3>" + SCENARIOS[i][1] + "<END>").getBytes(StandardCharsets.ISO_8859_1);
            System.arraycopy(d, 0, f, 0xE08 + pool, d.length);
            pool += d.length + 1;
        }
        return Arrays.copyOf(f, 0xE08 + pool);
    }

    static byte[] rule() {
        byte[] r = new byte[0x30];
        for (int i = 0; i < 4; i++) { r[i] = 1; r[4 + i] = 1; }
        r[8] = 0; r[9] = 0; r[10] = 1; r[11] = 0;      // 2 players, 5 min, NORMAL, friendly fire off
        // per mode {difficulty control, difficulty, friendly fire}, copied to the start info +0x13..0x15 (netwk 0x58A880).
        // Control 0 = "Auto": the game ignores the room's difficulty and plays EASY (netwk getter 0x5DE270 returns
        // +0x4A2, cleared at start); 1 = "Fixed": RULE's difficulty (0 EASY .. 3 VERY HARD). FREE mode takes the
        // difficulty and friendly fire from the RULE screen when r[2]/r[6] and r[3]/r[7] are set (protocol.md ??6.8).
        r[0xC] = 1; r[0xD] = 1; r[0xE] = 0;           // scenario mode: fixed NORMAL
        r[0xF] = 1; r[0x10] = 1; r[0x11] = 0;         // free mode: fixed, RULE's choice (default NORMAL)
        r[0x12] = (byte) 300; r[0x13] = (byte) (300 >> 8);
        r[0x14] = 1; r[0x15] = 1;
        return r;
    }

    public static void startFor(ServerConfig cfg) throws IOException {
        if (!cfg.getBool("us.app.enabled", true)) return;
        Thread t = new Thread(new UsAppService(cfg), "us-app");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void run() {
        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(bind, port));
            System.out.println("us app01 service on " + bind + ":" + port);
            while (true) {
                Socket s;
                try { s = ss.accept(); } catch (IOException e) { System.out.println("us app01 accept: " + e); continue; }
                if (connected.get() >= MAX_CONN) {   // each connection is a thread: cap them
                    System.out.println("us app01: too many connections, refusing " + s.getRemoteSocketAddress());
                    try { s.close(); } catch (IOException ignored) { }
                    continue;
                }
                Thread t = new Thread(() -> serve(s), "us-app-" + s.getRemoteSocketAddress());
                t.setDaemon(true);
                t.start();
            }
        } catch (IOException e) {
            System.out.println("us app01 service: " + e);
        }
    }

    static final int MAX_CONN = 64;

    private void serve(Socket s) {
        connected.incrementAndGet();
        try (s) {
            s.setSoTimeout(120_000);
            new Conn(s).loop();
        } catch (EOFException e) {
            // client hung up
        } catch (IOException | RuntimeException e) {   // malformed request: drop this connection only
            System.out.println("us app01 " + s.getRemoteSocketAddress() + ": " + e);
        } finally {
            connected.decrementAndGet();
        }
    }

    /** File #2 app01 extra files (6203/6204): at most 2, 0x2000 bytes each; file 0 lands at 0x3784A0, file 1 at
     *  0x37A4A0 (pointers 0x37EEA4/0x37EEA8) and netwk copies both (0x607F80). Empty = "nothing to fetch". */
    static byte[][] f2Files;
    static String f2FilesName = "02/AREA.DAT";

    /** File #2 area names shown in AREA SELECT (kk objects obf201..obf210): {name (<= 23 chars), description}.
     *  The description is "line 1|line 2" (line 2 in color 3) or raw markup starting with "<BODY>" (<= 255 bytes).
     *  No US source survives for the original names: these follow the fan server Bioserver2 (ref/Bioserver2
     *  bioserver/Areas.java), each with its own RULE rows (rules, see f2Rule) and scenarios (bit k-1 = scenario k:
     *  0x1F story, 0xE0 elimination 1-3, 0x700 showdown 1-3, 0x7FF all) (docs/notes/protocol.md 7, "Reglas por área").
     *  Overridable in server.properties: us.f2.area.N.name / .desc / .rules / .scenarios (N = 1..10). */
    static final String[][] F2_AREAS = {
        {"Free Area", "Join games or create your own.|Normal rules, all scenarios.",
            "difficulty=NORMAL, ff=Off, movie=On, hint=Off", "7FF"},
        {"Beginners", "New to online play? Start here.|Easy or Normal, item hints on.",
            "easy=EASY, ff=Off!, movie=On, hint=On", "7FF"},
        {"Veterans", "For experienced players.|Hard or Very Hard only.",
            "hard=HARD, ff=Off, movie=On", "7FF"},
        {"Survival", "Less life, more virus.|Life MAX 50%, virus 30%, Hard.",
            "difficulty=HARD, life=50%, virus=30%, ff=Off, movie=On~", "7FF"},
        {"Nightmare", "NIGHTMARE mode is on in every game.|Stronger, faster enemies.",
            "nightmare=On!, difficulty=NORMAL, ff=Off, movie=On", "7FF"},
        {"Infinity", "INFINITY mode is on in every game.|Weapons never run out.",
            "infinity=On!, difficulty=NORMAL, ff=Off, movie=On", "7FF"},
        {"Elimination", "Play elimination 1-3.|Defeat the enemies within the time limit.",
            "difficulty=NORMAL, ff=Off, movie=On", "E0"},
        {"Showdown", "Play showdown 1-3.|Defeat the bosses with joined forces.",
            "difficulty=NORMAL, ff=Off, movie=On", "700"},
        {"Scenario Run", "Play the story scenarios.|From wild things to end of the road.",
            "difficulty=NORMAL, ff=Off, movie=On", "1F"},
        {"Lounge", "Meet other players and chat.|Relaxed games: Easy, movies play.",
            "difficulty=EASY, ff=Off, movie=Off", "7FF"},
    };
    /** RULE descriptors of netwk 0x625BD0 (0x120 each: name, +0x1D start-info offset, +0x1E size, +0x1F option count,
     *  options of 0x20 from +0x20 = text[0x1E] + value): {short config name, option labels}. The labels are only for
     *  the config; the client shows its own texts. Option index = what the area item and the RULE choice carry. */
    static final String[][] F2_RULE_DESC = {
        {"control", "Auto", "Fixed"},                                                // 0 difficulty control (start +0x13)
        {"difficulty", "EASY", "NORMAL", "HARD", "VERY HARD"},                       // 1 +0x14
        {"ff", "Off", "On"},                                                         // 2 Friendly Fire +0x15
        {"movie", "Off", "On"},                                                      // 3 Movie Skippable +0x16
        {"virus", "0%", "30%", "50%", "80%"},                                        // 4 Virus Level +0x17
        {"nightmare", "Off", "On"},                                                  // 5 NIGHTMARE +0x18
        {"infinity", "Off", "On"},                                                   // 6 INFINITY +0x19
        {"hint", "Off", "On"},                                                       // 7 SP Item Hint +0x1A
        {"points", "default", "x0.5", "x1.0", "x1.5", "x2.0", "x3.0", "x5.0", "x10"},   // 8 Point Multiplier +0x1B
        {"life", "100%", "80%", "50%", "30%"},                                       // 9 Life MAX +0x1C
        {"time", "5 hours", "3 hours", "1 hour", "30 minutes", "15 minutes", "10 minutes", "5 minutes"}, // 10 +0x1D
        {"easy", "EASY", "NORMAL"},                                                  // 11 Difficulty (values 0/1) +0x14
        {"hard", "HARD", "VERY HARD"},                                               // 12 Difficulty (values 2/3) +0x14
        {"rankkind", "0", "1", "2", "3", "4", "5", "6", "7"},                         // 13 +0x21
        {"ranktype", "0", "1", "2", "3", "4", "5", "6", "7"},                         // 14 +0x22
        {"prize", "None", "RITA", "AXEMAN", "ARNOLD", "NIGHTMARE", "INFINITY"},       // 15 Prize Item (2 bytes) +0x23
    };
    /** Items per mode sub-record: +3..+0x2A holds 10 of 4 bytes (the second list at +0x2B is the server descriptors).
     *  The RULE screen has room for 4 shown items only (rows 5-8; Scenario and Character stay on rows 9-10 and a 5th
     *  item is drawn over them, run f2/m4): f2Rule refuses more. */
    static final int F2_RULE_MAX = 10, F2_RULE_SHOWN_MAX = 4;
    /** Parses an area rule list "key=option[!|~], ..." into items {descriptor, default option, shown, adjustable}:
     *  key = F2_RULE_DESC name (or its index), option = label (case/space-insensitive) or index; "!" = shown but
     *  fixed, "~" = hidden (applied, not shown). A hidden "control=Fixed" is added when absent: with Auto the game
     *  ignores the RULE difficulty (getter 0x5F60E0). Throws IllegalArgumentException on a bad entry. */
    static int[][] f2Rule(String spec) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        boolean control = false;
        for (String e : spec.split(",")) {
            e = e.trim();
            if (e.isEmpty()) continue;
            int shown = 1, adj = 1;
            if (e.endsWith("!")) { adj = 0; e = e.substring(0, e.length() - 1); }
            else if (e.endsWith("~")) { shown = 0; adj = 0; e = e.substring(0, e.length() - 1); }
            int eq = e.indexOf('=');
            if (eq < 0) throw new IllegalArgumentException("rule item without '=': " + e);
            String k = e.substring(0, eq).trim().toLowerCase(), v = norm(e.substring(eq + 1));
            int d = -1, o = -1;
            for (int i = 0; i < F2_RULE_DESC.length; i++) if (F2_RULE_DESC[i][0].equals(k) || k.equals("" + i)) d = i;
            if (d < 0) throw new IllegalArgumentException("unknown rule: " + k);
            for (int i = 1; i < F2_RULE_DESC[d].length; i++) if (norm(F2_RULE_DESC[d][i]).equals(v)) o = i - 1;
            if (o < 0 && v.matches("\\d+") && Integer.parseInt(v) < F2_RULE_DESC[d].length - 1) o = Integer.parseInt(v);
            if (o < 0) throw new IllegalArgumentException("unknown option for " + k + ": " + v);
            if (d == 0) control = true;
            out.add(new int[] {d, o, shown, adj});
        }
        if (!control) out.add(new int[] {0, 1, 0, 0});
        if (out.size() > F2_RULE_MAX) throw new IllegalArgumentException("more than " + F2_RULE_MAX + " rule items");
        if (out.stream().filter(it -> it[2] != 0).count() > F2_RULE_SHOWN_MAX)
            throw new IllegalArgumentException("more than " + F2_RULE_SHOWN_MAX + " shown rule items (hide some with ~)");
        return out.toArray(new int[0][]);
    }
    static String norm(String s) { return s.replace(" ", "").toLowerCase(); }
    /** Applies us.f2.area.N.name / .desc / .rules / .scenarios from the config and rebuilds the area table (extra file 0). */
    /** us.f2.npcs=false hides the NPC characters again (default: offered, see f2Areas). Default-false field on purpose:
     *  f2Areas() first runs from the static initializer, before any field initializer below it. */
    static boolean f2NoNpcs;
    /** us.f2.all_scenarios=false offers only what each save has unlocked again (default: all 11, see f2Areas). */
    static boolean f2SaveScenarios;
    static synchronized void configureAreas(ServerConfig cfg) {
        f2NoNpcs = !cfg.getBool("us.f2.npcs", true);
        f2SaveScenarios = !cfg.getBool("us.f2.all_scenarios", true);
        final String[] keys = {".name", ".desc", ".rules", ".scenarios"};
        for (int i = 0; i < F2_AREAS.length; i++)
            for (int k = 0; k < keys.length; k++) {
                String v = cfg.get("us.f2.area." + (i + 1) + keys[k], "").trim();
                if (v.isEmpty()) continue;
                try {   // a bad rules/scenarios value keeps the default (logged) instead of breaking every area
                    if (k == 2) f2Rule(v);
                    if (k == 3) Integer.parseInt(v.replaceFirst("^0[xX]", ""), 16);
                    F2_AREAS[i][k] = v;
                } catch (IllegalArgumentException e) {
                    System.err.println("[us-app] us.f2.area." + (i + 1) + keys[k] + " ignored: " + e.getMessage());
                }
            }
        f2Files = new byte[][] { f2Areas() };
    }
    static String areaMarkup(String d) {
        if (d.startsWith("<BODY>")) return d;
        int bar = d.indexOf('|');
        return bar < 0 ? "<BODY><SIZE=3>" + d + "<END>"
            : "<BODY><SIZE=3>" + d.substring(0, bar) + "<BR><BODY><C=3>" + d.substring(bar + 1) + "<END>";
    }
    static { f2Files = new byte[][] { f2Areas() }; }

    /** File #2 extra file 0: the area table, 0x177C bytes (netwk keeps a backup of that size, 0x60ACE8). 10 records of
     *  0x1E4 (getters 0x597D70.., 0x5A2800..): +0 u8 kind (0x6091B4: 1/2 = restricted), +1 s8 (0x597F00, compared with
     *  the disc/save at 0x300310), +2 s8, +3 u8, +0x14/+0x18 u32, +0x1C name (24 bytes copied, 0x5A0E64), +0x4C
     *  description (0x100), +0x14C u8 status (3 = open: AREA SELECT 0x609124 refuses anything else), +0x14D/+0x15D
     *  strings, +0x16B flags, +0x16C 2 x 0x3C sub-records; after the records +0x12EB s8 and a block at +0x12FC. */
    static void putLE(byte[] f, int o, int v) { f[o] = (byte) v; f[o + 1] = (byte) (v >> 8); f[o + 2] = (byte) (v >> 16); f[o + 3] = (byte) (v >> 24); }

    static byte[] f2Areas() {
        // QUICK START room size: option index of the No. of Players row (0 = 1 .. 3 = 4 players) [HIPOTESIS: 1 = 2 players]
        // (a local, not a static field: this runs from the static initializer above the field declarations)
        final int QS_MAX_OPT = Integer.getInteger("reo.us.qs_max_opt", 1);
        byte[] f = new byte[0x177C];
        for (int i = 0; i < F2_AREAS.length; i++) {
            int r = i * 0x1E4;
            byte[] nm = F2_AREAS[i][0].getBytes(StandardCharsets.ISO_8859_1);
            System.arraycopy(nm, 0, f, r + 0x1C, Math.min(nm.length, 0x17));
            byte[] d = areaMarkup(F2_AREAS[i][1]).getBytes(StandardCharsets.ISO_8859_1);
            System.arraycopy(d, 0, f, r + 0x4C, Math.min(d.length, 0xFF));
            f[r + 0x14C] = 3;
            f[r + 3] = (byte) 0xFF;                       // characters offered (bit per character, netwk 0x5EBFD4)
            // NPC characters offered: 128 bits at +4..+0x13 (getter netwk 0x597EB0, only caller the CHARACTER SELECT list
            // 0x5EBFA0: NPC i is listed when this bit AND the save's unlock bit 0x2FEEB0[i>>5] & (1<<(i&31)) are set).
            // They were 0, so bought NPCs never showed online (reports 22-23/10). Costumes come from the save only
            // (0x2FEEA8 + character, 4 bits), not from the area.
            if (!f2NoNpcs) java.util.Arrays.fill(f, r + 4, r + 0x14, (byte) 0xFF);
            // RULE scenario row (netwk 0x5EBEC0): offered = (save unlock mask >> 1) & +0x14 | +0x18, bit k-1 = scenario k
            // of the ELF table 0x219E70 (wild things .. showdown 3). +0x18 is the "always offered" one: with it at 0 a new
            // save only got the 4 basic scenarios (run u2, 23/10; players asked for all of them online).
            int scen = Integer.parseInt(F2_AREAS[i][3].replaceFirst("^0[xX]", ""), 16) & 0x7FF;   // per area (F2_AREAS)
            putLE(f, r + 0x14, scen);
            if (!f2SaveScenarios) putLE(f, r + 0x18, scen);
            int[][] items = f2Rule(F2_AREAS[i][2]);
            f[r + 0x16B] = 3;                              // bit 0 / bit 1: room title / password adjustable
            for (int m = 0; m < 2; m++) {                  // rule sub-record per mode (+0x16C + mode*0x3C), netwk 0x5A2C50
                int q = r + 0x16C + m * 0x3C;
                // mode 1 = QUICK START (the 5 doors right of FREE): the room is created with the maximum of this row and
                // the game waits for that many players (run q4/qs2: 2 of 4 waited for minutes) -> QS_MAX players
                f[q] = (byte) (m == 1 ? 0xC0 | 0x05 | (QS_MAX_OPT << 4) : 0xC0 | 0x35);               // No. of Players (netwk 0x5A2980): bit 7 shown, bit 6 adjustable, bits 0-1 default, 2-3 min, 4-5 max option (2..4, default 2)
                f[q + 1] = (byte) (0xC0 | 0x21);           // Waiting Time: bits 0-2 default, 3-5 options-1 (all 5, default 5 min)
                // {descriptor 0x625BD0, default option, shown, adjustable} from the area's rules (f2Rule): netwk 0x5A2B30
                // makes a RULE row of each shown item, 0x5A3940 writes the chosen (or, hidden, the default) option's
                // value to start info +0x13 + descriptor offset; 0x5F6110 copies NIGHTMARE/INFINITY/... into the game
                // settings at 0x2F51C0 when the scenario starts. Hidden "difficulty control" = Fixed (protocol.md 6.8).
                f[q + 2] = (byte) items.length;
                for (int k = 0; k < items.length; k++)
                    for (int j = 0; j < 4; j++) f[q + 3 + 4 * k + j] = (byte) items[k][j];
            }
        }
        return f;
    }
    /** Lobby (kk) host of File #2; the client copies it to 0x36F8E0 (default from the ELF, but cleared between sessions). */
    static final String F2_KK_HOST = "snap01.capcom.client.sf.yav4.com";
    /** File #2 area groups of the one server: {kk name prefix, name, description}. */
    static final int F2_AREA_MAX = 400;
    static final String[][] F2_AREA_GROUPS = {
        {"obf2", "AREA 01", "Area one"}, {"obf2", "AREA 02", "Area two"}, {"obf2", "AREA 03", "Area three"},
        {"obf2", "AREA 04", "Area four"}, {"obf2", "AREA 05", "Area five"}, {"obf2", "AREA 06", "Area six"},
        {"obf2", "AREA 07", "Area seven"}, {"obf2", "AREA 08", "Area eight"}, {"obf2", "AREA 09", "Area nine"},
        {"obf2", "AREA 10", "Area ten"}};

    private final class Conn {
        final Socket s;
        final DataInputStream in;
        final OutputStream out;
        final int seed = 0;
        int pid = 0;
        byte[] fileName = new byte[0], fileData = new byte[0];
        int extraIdx;
        /** 1 = File #1 (SLUS-20765), 2 = File #2 (SLUS-20984): TELL 0x1002 carries an empty string and 01 01 from
         *  File #1, the account name and 04 01 from File #2 (docs/notes/protocol.md 7). */
        int title = 1;

        Conn(Socket s) throws IOException {
            this.s = s;
            this.in = new DataInputStream(s.getInputStream());
            this.out = s.getOutputStream();
        }

        void send(int qsw, int cmd, int replyPid, byte[] payload) throws IOException {
            int p = replyPid;
            if (p < 0) { pid = (pid + 1) & 0xFF; p = pid; }
            byte[] f = frame(qsw, cmd, p, 0, payload);
            PacketLog.log("us-app", false, s.getRemoteSocketAddress(), f, 0, f.length);
            out.write(f);
            out.flush();
        }

        void loop() throws IOException {
            send(QUERY, 0x1001, -1, new byte[0]);
            byte[] h = new byte[HEADER];
            while (true) {
                in.readFully(h);
                int len = ((h[0] & 0xFF) << 8) | (h[1] & 0xFF);
                byte[] p = new byte[len];
                in.readFully(p);
                byte[] all = new byte[HEADER + len];
                System.arraycopy(h, 0, all, 0, HEADER);
                System.arraycopy(p, 0, all, HEADER, len);
                PacketLog.log("us-app", true, s.getRemoteSocketAddress(), all, 0, all.length);
                handle(h[2] & 0xFF, ((h[3] & 0xFF) << 8) | (h[4] & 0xFF), h[5] & 0xFF, p);
            }
        }

        /** File #2 only queries (SLUS-20984 app01 client, table 0x241370; docs/notes/protocol.md 7). Returns false for
         *  the ones File #1 also has. Handlers: 1007 0x1FDA00, 6103 0x1FAD10, 6105 0x1FB1A0, 6203 0x1F9740,
         *  6204 0x1F9B20, 6211 0x1F93D0, 6212 0x1F9140, 6213 0x1F9230, 6403 0x1FA2F0, 6501 0x1FB850, 6502 0x1FC050,
         *  6510 0x1FD160. */
        boolean f2(int cmd, In r, Out w, int qpid) throws IOException {
            switch (cmd) {
                case 0x1007 -> w.u8(1).u8(0).str("").str("");          // u8 1 (0 = error), u8, string <= 0x13, string <= 0xFEB; next 6103
                case 0x6103 -> w.u16(0);                                // u16 n (<= 8) + n x (u32, u32); 0 -> 6212
                case 0x6105 -> w.u16(0).u32(0).u32(0);
                case 0x6212 -> w.u32(0);                                // u32 -> 0x37EEB0; next 6203
                case 0x6203 -> {                                        // u8 1, name (<= 0x1F; same as cached = skip), u16 n (1..2), n x u32 size (<= 0x2000); u8 2 = none
                    if (f2Files.length == 0) w.u8(2);
                    else { w.u8(1).str(f2FilesName).u16(f2Files.length); for (byte[] f : f2Files) w.u32(f.length); }
                }
                case 0x6204 -> {                                        // Q: u16 idx, u32 offset, u16 max. T: u16 idx, u32 offset (echoed), string block <= 0x2F2
                    int idx = r.u16(); int off = (int) r.u32(); int max = r.u16();
                    byte[] f = idx < f2Files.length ? f2Files[idx] : new byte[0];
                    if (off < 0 || off > f.length) off = f.length;
                    int end = Math.max(off, Math.min(f.length, off + Math.min(max, 0x2F2)));
                    w.u16(idx).u32(off).str(Arrays.copyOfRange(f, off, end));
                }
                case 0x6211 -> w.u32(0);                                // u32 -> 0x37EEAC; next 6213 (or 6301/1004 by session mode)
                case 0x6213 -> { for (int i = 0; i < 8; i++) w.u16(0); } // 8 x u16; next 6501
                case 0x6403 -> w.u16(0).u32(0);
                case 0x6501 -> w.u8(1).u32(0).u32(0);                   // lobby server list: u8 1, u32 (0x37C834), u32 (0x37C830) -> 6503 (u8 0 -> 6502/6510)
                case 0x6503 -> {
                    // Q: u16 start, u16 count (8). T (0x1FBA80): u16 total (1..16), u16 start (echoed), u8 k, k x server
                    // {string host <= 0x3E (+0x20, resolved and used as the kk lobby: 0x5B5CF0 -> 0x1F4CD0),
                    //  string name <= 0xF (+0x5F), 8 x u8 (+0x6F), string <= 0xFF (+0x77)}; entries of 0x260 at 0x37C830.
                    int start = r.u16(); r.u16();
                    String[][] servers = {{F2_KK_HOST, "REO WEB"}};
                    w.u16(servers.length).u16(start);
                    int k = Math.max(0, servers.length - start);
                    w.u8(k);
                    for (int i = start; i < servers.length; i++) {
                        w.str(servers[i][0]).str(servers[i][1]);
                        for (int j = 0; j < 8; j++) w.u8(0);
                        w.str("");
                    }
                }
                case 0x6502 -> w.u8(1).u32(0).u32(0);                   // Q: u32, string host. u8 1, u32 (0x37C838), u32 -> 6504 (areas of that server)
                case 0x6504 -> {
                    // Q: u16 start, u16 count, ... T (0x1FC440): u16 total (<= 0x40), u16 start, u8 k, k x area
                    // {string prefix <= 0xF (+0, 8 x u8 at +0x20 after the name; the lobby asks the kk directory for prefix+"01" <= NAME <= prefix+"10",
                    //  netwk 0x59C910), string name <= 0xF (+0x10), u8 (+0x20), string <= 0xFF (+0x28), u16 (+0x128),
                    //  u16 (+0x12A), u32 (+0x130)}; then 6510 (players per area).
                    int start = r.u16(); r.u16();
                    String[][] groups = F2_AREA_GROUPS;
                    w.u16(groups.length).u16(start);
                    w.u8(Math.max(0, groups.length - start));
                    for (int i = start; i < groups.length; i++)
                        { w.str(groups[i][0]).str(groups[i][1]); for (int j = 0; j < 8; j++) w.u8(0); w.str(groups[i][2]).u16(0).u16(F2_AREA_MAX).u32(0); }   // +0x12A = capacity (netwk 0x5B3380: more players than this = full)
                }
                case 0x6510 -> {                                        // players per area: u8 n (= the n of the query, else no values are read) + n x u16, string <= 0x3E; all areas -> 1004
                    int n = r.u8(); w.u8(n); for (int i = 0; i < n; i++) w.u16(0); w.str(F2_KK_HOST);   // Q: u8 n, n x string prefix, u8, string host; the string goes back to the server entry +0x20 (its host)
                }
                default -> { return false; }
            }
            send(TELL, cmd, qpid, w.bytes());
            return true;
        }

        void handle(int qsw, int cmd, int qpid, byte[] p) throws IOException {
            In r = new In(p, qpid, seed);
            Out w = new Out(qpid, seed);
            if (qsw == TELL && cmd == 0x1001) {
                send(QUERY, 0x1002, -1, new Out(0, 0).u16(seed).bytes());
            } else if (qsw == TELL && cmd == 0x1002) {
                r.str();
                title = r.u8() == 4 ? 2 : 1;
                send(BCAST, 0x6001, -1, new byte[] {0});            // 0: no update to download
            } else if (qsw == QUERY && cmd == 0x6110) {
                // File #2 (TELL handler 0x1FB620): u16 (nonzero -> 0x36F3E8), string (<= 0xFF, nonempty replaces the
                // host at 0x36F2E0), string (<= 0xFF, 0x36F8E0). Empty values keep the client's defaults; next 6210.
                send(TELL, cmd, qpid, w.u16(100).str("").str(F2_KK_HOST).bytes());
            } else if (qsw == QUERY && cmd == 0x6210) {
                // File #2 (0x1F9510): 17 x u32; the first three, when nonzero, go to 0x241280 / 0x2412A0 / 0x35DA80. Next 6101.
                for (int i = 0; i < 17; i++) w.u32(0);
                send(TELL, cmd, qpid, w.bytes());
            } else if (qsw == QUERY && cmd == 0x6101) {
                r.u32();
                fileName = r.str();
                String fn = new String(fileName, StandardCharsets.ISO_8859_1);
                // File #1 asks 01/TOP_INFOR.HTM, File #2 its own folder: the same page for both
                fileData = files.getOrDefault(fn.endsWith("/TOP_INFOR.HTM") ? "01/TOP_INFOR.HTM"
                        : fn.endsWith("/DATABASE.HTM") ? "01/DATABASE.HTM" : fn, new byte[0]);
                if (!files.containsKey(fn)) System.out.println("us app01: news file " + fn.replaceAll("[^\\x20-\\x7E]", "?"));
                // found=1 (0 makes the client fail with -0x3E), u32 ?, name (echoed), size
                send(TELL, cmd, qpid, w.u8(1).u32(0).str(fileName).u32(fileData.length).bytes());
            } else if (qsw == QUERY && cmd == 0x6102) {
                r.str();
                int off = (int) Math.min(r.u32(), Integer.MAX_VALUE);
                if (off < 0) off = 0;
                int chunk = 0x2FE - (fileName.length + 12);
                int end = Math.max(off, Math.min(fileData.length, off + chunk));
                byte[] part = Arrays.copyOfRange(fileData, Math.min(off, fileData.length), end);
                send(TELL, cmd, qpid, w.str(fileName).u32(off).str(part).bytes());
            } else if (qsw == QUERY && cmd == 0x6401) {
                send(TELL, cmd, qpid, w.u16(0).bytes());               // no operator messages
            } else if (qsw == QUERY && cmd == 0x6201) {
                // extra files (handler 0x1F59C0): u8 1, string name (<= 0x1F), u16 n (<= 3), n x u32 size;
                // the client keeps them at 0x357AD0 + i*0x2000 (pointers 0x35EC78[i]) and fetches them with 6202
                Out o = w.u8(1).str(EXTRA_NAME).u16(EXTRA.length);
                for (byte[] f : EXTRA) o.u32(f.length);
                send(TELL, cmd, qpid, o.bytes());
            } else if (qsw == QUERY && cmd == 0x6402) {
                // DATABASE in the DOWN TOWN menu (File #1, also seen from File #2): after 6101 "DATABASE.HTM" the client
                // fetches blocks 0 and 1 with 6402 {u16 index, u32 offset, u16 0x2F2}. The TELL handler (File #1 0x1F56D0,
                // File #2 0x1FA040) wants the index and offset echoed, then a string block; the bare TELL we sent left the
                // client on "Getting information" until our 120-s read timeout (production 22/10: ~1.5 a minute).
                // No database here: every block is empty.
                int idx = r.u16();
                int off = (int) r.u32();
                send(TELL, cmd, qpid, w.u16(idx).u32(off).str(new byte[0]).bytes());
            } else if (qsw == QUERY && cmd == 0x6202) {
                // Q: u16 index, u32 offset, u16 0x2F2. T (0x1F5C20): u16 index, u32 offset (both echoed), string block
                int idx = r.u16();
                int off = (int) r.u32();
                int max = r.u16();
                byte[] f = idx < EXTRA.length ? EXTRA[idx] : new byte[0];
                if (off < 0 || off > f.length) off = f.length;
                int end = Math.max(off, Math.min(f.length, off + Math.min(max, 0x2F2)));
                byte[] part = Arrays.copyOfRange(f, Math.min(off, f.length), end);
                send(TELL, cmd, qpid, w.u16(idx).u32(off).str(part).bytes());
            } else if (title == 2 && qsw == QUERY && f2(cmd, r, w, qpid)) {
                // handled (File #2 only messages)
            } else if (qsw == QUERY && (cmd == 0x6301 || cmd == 0x1004)) {
                send(TELL, cmd, qpid, new byte[0]);                    // 0x6301 report ack, 0x1004 bye
            } else if (qsw == QUERY) {
                System.out.printf("us app01: unknown QUERY 0x%04x (%d bytes)%n", cmd, p.length);
                send(TELL, cmd, qpid, new byte[0]);
            }
        }
    }
}
