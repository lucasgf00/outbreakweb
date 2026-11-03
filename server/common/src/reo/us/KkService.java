/*
    reo-web bioserver - fan server for Resident Evil Outbreak File #1/#2
    (C) 2026 reo-web contributors. AGPL-3.0-or-later, see LICENSE.txt.

    The US File #1 lobby: the client's "kkClient Ver 2.1" library talks UDP to snap01:9090.
    Login 0x2C -> 0x2D (Blowfish-ECB, key = password), session connect 0x01 -> 0x29, then a
    directory of named objects queried with 0x48: obmft (front?), obmsNN (SCENARIO MODE
    servers, auto-matching) and obmfNN (FREE MODE areas: the ROOM LIST). Attributes (USER,
    MAXI) with 0x09, enter an object with 0x06. Everything else gets the generic reply 0x28
    {u32 request type, u32 status 0}. Reliable client messages (flag 0x8000) are acked with
    flag 0x4000 + ack = their sequence. Formats: docs/notes/protocol.md section 6.5.
    Port of the prototype tools/kkudp.py.

    Areas are the Bioserver1 areas (bioserver1.Areas): area i is object "obmf%02d" with OID 0x200+i.

    File #2 (SLUS-20984) uses the same kkClient and lobby messages; it runs as a second instance (title 2,
    config us.kk2.*: port 9092, public_ip 198.18.0.11, reached as snap01.capcom.client.sf.yav4.com) with
    its own areas "obf2NN" (01..10: the client asks prefix+"01" <= NAME <= prefix+"10", the prefix comes
    from app01 6504, UsAppService.F2_AREA_GROUPS) and rooms. Accounts are shared: one name/credential
    works for both games (docs/notes/protocol.md 7).

    Config: us.kk.enabled (true), us.kk.port (9090), us.kk.public_ip (the address the client
    reaches this service at; default public_ip or 198.18.0.10), us.kk.accounts_file (name=credential,
    default data/kk-accounts.properties), us.kk.password (optional dev password for names that are
    not registered; empty = off, which is the default).

    Accounts: the web pre-menu registers {name, credential} with POST /api/kk/register before the
    game starts (credential = hex(SHA-256("reo-web:"+name+":"+token))[0:12], protocol.md 6.4). The
    login reply 0x2D is encrypted with the stored credential of that name; a client with another
    password cannot decrypt it and shows "Incorrect password".
*/

package reo.us;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import reo.common.PacketLog;
import reo.common.ServerConfig;

public final class KkService implements Runnable {
    static final int TRAILER = 0xBA476611;
    static final int OID_FRONT = 0x100, OID_SCENARIO = 0x100, OID_AREA = 0x200;
    // A client in the area or room lists sends nothing for minutes, so liveness comes from our own reliable
    // ping (a 0x28 {0x14, 0} presence reply with flag 0x8000, which the client acks): sent after PING_MS
    // without traffic; a session with no traffic at all (not even that ack) for deadMs is dropped.
    static final long PING_MS = 8_000;
    /** After the gateway's close notice: time to come back on a new flow before the session is dropped. */
    static final long BYE_GRACE_MS = 30_000;
    /** Unreliable game data (0x0F) kept for a member that is still loading, per member. */
    static final int HELD_LOSSY_MAX = 256;
    /** Reliable resends (1 s apart) before giving up; the session is dropped earlier when the client goes silent. */
    static final int RESEND_MAX = 600;
    /** Largest kk message (header + payload): the length field is 10 bits (receiver 0x1DFDA8: & 0x3FF). */
    static final int MAX_MSG = 0x3FF;
    static final int TRACE_LEN = 48;

    public static volatile KkService instance;    // File #1
    public static volatile KkService instance2;   // File #2
    /** 1 = File #1 (us.kk.*), 2 = File #2 (us.kk2.*). */
    final int title;
    final String svc;
    /** Name prefix of the area objects: obmf (File #1) / obf2 (File #2). */
    final String areaPrefix;

    /** One logged-in client, keyed by the session tag it echoes in every message. */
    static final class Session {
        final int tag;
        final String name;
        volatile SocketAddress addr;
        volatile int cur;             // OID of the object the client is in (0 = none)
        volatile long lastSeen = System.currentTimeMillis();
        // last kk messages of this session (in and out: ms, direction, type, flags, length), dumped when it leaves a
        // started game (peerGone): what a player sent and got right before its game quit (scenario event reports)
        final long[] trace = new long[TRACE_LEN];
        int traceN;
        final java.util.Set<Integer> unhandledSeen = java.util.concurrent.ConcurrentHashMap.newKeySet();
        volatile byte[] profile = new byte[0];
        int relSeq;
        volatile long lastSent;
        volatile long lastPing;       // last liveness ping (reliable 0x28 {0x14, 0})
        // game start: the scenario registers its 0x0F handlers (ELF 0x1CBE00/0x1CBEA0) only after loading, and a
        // member that loads later would lose the first game messages of the others. 0x0F to a member that has not
        // sent its own first 0x0F yet is held here and flushed when it does.
        volatile boolean gameReady;
        final List<Object[]> held = new ArrayList<>();
        // reliable relays not acked yet: our seq -> {datagram, last send ms, tries}; resent every second (tick)
        final Map<Integer, Object[]> unacked = new LinkedHashMap<>();
        // reliable client seqs already handled (the client resends every 2 s until our ack arrives)
        final java.util.LinkedHashSet<Integer> seenRel = new java.util.LinkedHashSet<>();
        int lastCreateSeq = -1, lastCreateOid;                  // last time we sent this client anything (keepalive)                              // our reliable (0x8000) sequence to this client: 0, 1, 2...   // last 0x0C payload (0xF0 bytes: character etc.)
        int seq;
        final long since = System.currentTimeMillis();   // for the moderation panel
        volatile SocketAddress alt;   // new address of a reconnected client, until it acks (offer())
        volatile long altSent;
        volatile long byeAt;          // the gateway said this flow closed (GATEWAY_BYE); dropped after BYE_GRACE_MS
        volatile long lastListSync;   // last time resyncLists() sent this client the room list of its area
        long enteredAt;               // when this client last entered s.cur (a repeated join right after it is a resend)
        // OID of the started room whose scenario this member is still playing (0 = none): set at START, cleared when it
        // is back in the waiting room (0x0A/0x09 on the room, only BIN_3 sends those) or leaves. Members come back one
        // by one (died, escaped), so a room "back to waiting" can still have members in game (production 24/10:
        // Melvo, File #1, kept playing 30 min in room 16d2 after it went back to waiting; when the host dropped from
        // the waiting room the disband sent him 0x10 class 0x0D and the area list pushes in game, then he lost the game).
        volatile int playing;
        volatile boolean strayLogged;   // its game data (0x0F) outside a game was logged once (not relayed, see case 0x0F)
        // OID of the game whose scenario this member left while its session goes on (dead or zombified, on the results
        // screen until EXIT): its runtime said so (REOGW-LEFT, see gatewayLeft()). Its game data is no longer relayed
        // to the members still playing. Cleared at START.
        volatile int leftGame;
        volatile long trkAt;          // last REOGW-TRK relayed for it (gatewayTrk(): at most one every TRK_GAP_MS)
        Session(int tag, String name, SocketAddress addr) { this.tag = tag; this.name = name; this.addr = addr; }
    }

    /** Error codes of a 0x27 reply to a room join (0x06), read by BIN_3 0x587A10: 0x0F = wrong password, others = cannot enter. */
    static final int JOIN_BAD_PASSWORD = 0x0F, JOIN_REFUSED = 0x01;

    /** A room (game) created inside an area with 0x04; members enter it with 0x06. */
    static final class Room {
        final int oid, parent;
        final String name;
        final int max, flags;
        final byte[] create;          // raw 0x04 payload, kept for the log / later decoding
        final String password;        // 0x04 +0x14 (16 bytes); "" = public
        volatile boolean started;     // the creator sent STAT 0x40000000 (START SCENARIO)
        volatile long startedAt;      // when (for the "game ready after N s" log)
        volatile int stat;            // last "STAT" attribute written with 0x08 (File #2 lists filter on it with 0x49)
        volatile int owner;           // session tag of the creator
        volatile String ownerName;    // and its name (host marked with "*" in gameStats)
        volatile long emptyAt;        // since when nobody is in it (0 = occupied), for expire()
        // the creator left a started room (dropped or 0x07): the members cannot START again (only the creator's client
        // can), so the room never goes back to the lists; members coming back to the waiting room get the disband
        volatile boolean hostGone;
        // back in the waiting room after a scenario: its members can START again, but nobody new can join. The host's
        // game does not take a newcomer there (the join waits for an answer that never comes and the client leaves the
        // room after 60 s): production 22/10, 632 of 636 such 60-s failures were in rooms back from a game, and those
        // rooms started 5 times against 827 fresh ones. So it stays out of the lists and a join gets the usual refusal.
        volatile boolean afterGame;
        // tags already announced with REOGW-GONE to the members of the current game (peerGone(), leftScenario()): one
        // notice per member and game, since each notice makes the runtime drop one more slot. Cleared at START.
        final java.util.Set<Integer> goneTold = ConcurrentHashMap.newKeySet();
        // game data (0x0F) sent by each member during the current game: name -> {messages, bytes}; logged per game
        // (gameStats) so a slow client shows up as a low rate next to the others (desync measurements, 23/10)
        final Map<String, long[]> gameMsgs = new ConcurrentHashMap<>();
        final Map<String, byte[]> attrs = new ConcurrentHashMap<>();
        // gateway round trips (REOGW-RTT) of each member during the current game: name -> {samples, sum, max, sum of
        // |change|, last}; logged by gameStats as the estimated one-way delay between members (lag measurements, 24/10)
        final Map<String, long[]> gameRtt = new ConcurrentHashMap<>();
        Room(int oid, int parent, String name, int max, int flags, byte[] create, String password) {
            this.oid = oid; this.parent = parent; this.name = name; this.max = max; this.flags = flags; this.create = create;
            this.password = password;
        }
        /** Directory flags of the list entry: bit 0x10000 = password (BIN_3 0x588DF0 tests (f >> 1) & 0x8000). */
        int entryFlags() { return flags | (password.isEmpty() ? 0 : 0x10000); }
    }

    /** A directory object: 0x24-byte entry = name[16] + 5 u32 (group, ?, max, ?, OID). */
    record Obj(String name, int group, int f14, int max, int f1c, int oid) {}

    private final String bind;
    private final int port;
    private final int publicPort;   // port the client is told to use (us.kk.public_port; default = port)
    private final byte[] publicIp;
    private final byte[] password;     // dev fallback (empty = off)
    // Accounts are shared by the File #1 and File #2 instances (same name, same credential).
    private static final java.util.Properties accounts = new java.util.Properties();
    private static java.nio.file.Path accountsFile;
    private static final Map<String, long[]> regRate = new ConcurrentHashMap<>();
    // Silence limits. Real disconnections come from the gateway's close notice (GATEWAY_BYE: WebSocket closed or
    // its ping lost), so these only catch what that cannot see and can be long: a scenario load in the browser
    // (disc ranges over the internet) keeps a client quiet for minutes, and dropping it there broke whole games.
    private final long deadMs;         // us.kk.dead_secs (default 180): lobby and waiting room
    private final long gameDeadMs;     // us.kk.game_dead_secs (default 600): in a started room (loading, playing)
    // us.kk.log_relays (default false): one stdout line per relayed message. In production that was thousands of lines a
    // second; journald dropped ~90 % of the log and, when it fell behind, println blocked every thread that logs (all
    // share System.out), the 8 API threads included: the API stalled for ~30 s (22/10 23:20 UTC)
    private final boolean logRelays;
    // us.kk.list_pushes / us.kk2.list_pushes: room list pushes 0x04/0x05 (areaPush, resyncLists, fixListAfterRefusal).
    // On for File #1, whose list lives on them. Off by default for File #2: its list comes from its own 0x49 polls
    // with 0x28-byte entries, and the 0x24-byte File #1 entries pushed every 5 s made rooms flicker, show another
    // room in a slot and come back (report e3c97c7f5a14, 22/10)
    private final boolean listPushes;
    private final int membersPerPart;        // us.kk.members_per_part (tests): member records per 0x0A part (default and max 3 = what fits in 0x3FF)
    private final boolean ownerFirst;        // us.kk.members_owner_first (tests): the room owner first in the 0x0A list
    // us.kk.host_bye_secs (default 6, 0 = off): the HOST of a game in progress whose gateway flow closed (REOGW-BYE)
    // and did not come back on a new flow within this time is dropped then, not after BYE_GRACE_MS: the others get
    // REOGW-GONE at once and their game elects a new host (protocol.md 6.15/6.16). The web runtime reopens its
    // WebSocket 3 s after a drop and its next packet moves the session (offer()), so a quick reconnect still makes it.
    private final long hostByeMs;
    // us.kk.rejoin_push (default false, protocol.md 6.16): members come back from the scenario one by one, and the one
    // back first never learns about the later ones (it shows "1P" and does not answer their 0x10: native runs
    // lat/BG2 and hg/R2, File #2). With this, a member still in the scenario is left out of the 0x0A list of the
    // ones already back, and when it comes back they get the usual "member entered" push 0x06, as for a fresh join.
    private final boolean rejoinPush;
    private final boolean memberBackGone;   // us.kk.member_back_gone (default true): REOGW-GONE also for a non-host member back from the scenario
    private final boolean peerGoneNotice;   // us.kk.peer_gone_notice (default true): REOGW-GONE to the gateway, see peerGone()
    private final boolean sessionGoneNotice; // us.kk[2].session_gone_notice (default: File #1 only): 0x31 to a tag we do not know, see sessionGone()
    /** Last 0x31 per address (sessionGone() sends at most one per SESSION_GONE_MS to each). */
    private final Map<SocketAddress, Long> goneSent = new ConcurrentHashMap<>();
    static final long SESSION_GONE_MS = 2_000;
    private final bioserver1.Areas areas = new bioserver1.Areas();
    private final Map<Integer, Session> sessions = new ConcurrentHashMap<>();
    private final Map<SocketAddress, Integer> seqByAddr = new ConcurrentHashMap<>();
    private final java.security.SecureRandom rnd = new java.security.SecureRandom();
    /** H1: one-time tickets handed out in the encrypted 0x2D (only the credential holder can read them) and
     *  required in the 0x01 session connect (payload +0x24 u32 length, +0x28 ticket, echoed by the client). */
    private final Map<String, Object[]> tickets = new ConcurrentHashMap<>();   // hex(ticket) -> {name, expiry ms}
    static final long TICKET_MS = 60_000;
    /** H4: per-address token bucket (packets/s, burst) in front of datagram(); caps on sessions and rooms. */
    private final Map<SocketAddress, double[]> buckets = new ConcurrentHashMap<>();   // {tokens, last ms}
    private final int ratePps, rateBurst, maxSessions, maxRooms;
    private final Map<Integer, Room> rooms = new ConcurrentHashMap<>();
    private int nextRoom = 0x1001;
    private DatagramSocket sock;
    /** Request id of the message being answered: the client puts it in the high byte of the type
     *  field and matches the reply by it (BIN_3 0x585380), so every reply echoes it. */
    private int curReq;

    public KkService(ServerConfig cfg) throws Exception { this(cfg, 1); }

    public KkService(ServerConfig cfg, int title) throws Exception {
        this.title = title;
        svc = title == 2 ? "us-kk2" : "us-kk";
        areaPrefix = title == 2 ? "obf2" : "obmf";
        String k = title == 2 ? "us.kk2." : "us.kk.";
        bind = cfg.get("bind", "0.0.0.0");
        port = cfg.getInt(k + "port", title == 2 ? 9092 : 9090);
        publicPort = cfg.getInt(k + "public_port", title == 2 ? cfg.getInt("us.kk.public_port", port) : port);
        publicIp = InetAddress.getByName(cfg.get(k + "public_ip", title == 2 ? "198.18.0.11" : "198.18.0.10")).getAddress();
        password = cfg.get("us.kk.password", "").getBytes(StandardCharsets.ISO_8859_1);
        deadMs = Math.max(10, cfg.getInt("us.kk.dead_secs", 180)) * 1000L;
        gameDeadMs = Math.max(deadMs / 1000, cfg.getInt("us.kk.game_dead_secs", 600)) * 1000L;
        peerGoneNotice = cfg.getBool("us.kk.peer_gone_notice", true);
        hostByeMs = Math.max(0, cfg.getInt("us.kk.host_bye_secs", 6)) * 1000L;
        rejoinPush = cfg.getBool(k + "rejoin_push", cfg.getBool("us.kk.rejoin_push", false));
        sessionGoneNotice = cfg.getBool(k + "session_gone_notice", title == 1);
        logRelays = cfg.getBool("us.kk.log_relays", false);
        memberBackGone = cfg.getBool("us.kk.member_back_gone", true);
        listPushes = cfg.getBool(k + "list_pushes", title != 2);
        membersPerPart = Math.max(1, Math.min((MAX_MSG - 16 - 12) / 0x108, cfg.getInt("us.kk.members_per_part", 3)));
        ownerFirst = cfg.getBool("us.kk.members_owner_first", false);
        ratePps = cfg.getInt("us.kk.rate_pps", 200);
        rateBurst = cfg.getInt("us.kk.rate_burst", 400);
        maxSessions = cfg.getInt("us.kk.max_sessions", 500);
        maxRooms = cfg.getInt("us.kk.max_rooms", 200);
        synchronized (accounts) {
            if (accountsFile == null) {
                accountsFile = java.nio.file.Path.of(cfg.get("us.kk.accounts_file", "data/kk-accounts.properties"));
                if (java.nio.file.Files.exists(accountsFile))
                    try (var in = java.nio.file.Files.newInputStream(accountsFile)) { accounts.load(in); }
            }
        }
    }

    public static void startFor(ServerConfig cfg) throws Exception {
        if (cfg.getBool("us.kk.enabled", true)) {
            KkService k = new KkService(cfg, 1);
            instance = k;
            Thread t = new Thread(k, "us-kk");
            t.setDaemon(true);
            t.start();
        }
        if (cfg.getBool("us.kk2.enabled", true)) {
            KkService k = new KkService(cfg, 2);
            instance2 = k;
            Thread t = new Thread(k, "us-kk2");
            t.setDaemon(true);
            t.start();
        }
    }

    /** Number of registered US accounts (read-only, for Metrics). */
    public static int accountCount() { synchronized (accounts) { return accounts.size(); } }

    /** Registered names (stored lowercase) containing f, at most max, for the moderation search of offline players. */
    public static java.util.List<String> accountNames(String f, int max) {
        java.util.List<String> out = new java.util.ArrayList<>();
        synchronized (accounts) {
            for (String k : accounts.stringPropertyNames()) {
                if (k.contains(f)) out.add(k);
                if (out.size() >= max) break;
            }
        }
        return out;
    }

    /** Admin "release": frees a registered name so anyone can register it again (memory and file together; editing
     *  the file by hand does not work while the server runs, the next registration writes the old list back).
     *  Returns false when the name was not registered. */
    public static boolean release(String name) throws Exception {
        synchronized (accounts) {
            if (accountsFile == null || accounts.remove(name.toLowerCase(java.util.Locale.ROOT)) == null) return false;
            saveAccounts();
        }
        System.out.println("us-kk: released " + name);
        reo.common.Social.forget(name);
        return true;
    }

    /** Both lobby instances that are running (File #1 first). */
    public static List<KkService> instances() {
        List<KkService> l = new ArrayList<>();
        if (instance != null) l.add(instance);
        if (instance2 != null) l.add(instance2);
        return l;
    }

    /** Areas of this title: the Bioserver1 ones for File #1, 10 for File #2 (the client asks for prefix 01..10). */
    int areaCount() { return title == 2 ? 10 : areas.getAreaCount(); }

    String areaName(int a) { return title == 2 ? String.format("Area %02d", a) : areas.getName(a); }

    // ---- directory -------------------------------------------------------------------------

    private TreeMap<String, Obj> objects() {
        TreeMap<String, Obj> m = new TreeMap<>();
        m.put(areaPrefix + "t", new Obj(areaPrefix + "t", 0xF7E00002, 0, 100, 0, OID_FRONT));
        if (title == 1)
            for (int i = 1; i <= 5; i++)
                m.put(String.format("obms%02d", i), new Obj(String.format("obms%02d", i), 0xF7E00010 + i, 0, 100, 0, OID_SCENARIO + i));
        for (int a = 1; a <= areaCount(); a++)
            m.put(String.format("%s%02d", areaPrefix, a), new Obj(String.format("%s%02d", areaPrefix, a), 0xF7E00020 + a, 0, 4, 0, OID_AREA + a));
        return m;
    }

    private int users(int oid) {
        int n = 0;
        for (Session s : sessions.values())
            if (oid == OID_FRONT || s.cur == oid || (rooms.containsKey(s.cur) && rooms.get(s.cur).parent == oid)) n++;
        return n;
    }

    // ---- wire ------------------------------------------------------------------------------

    static String cstr(byte[] b, int off, int max) {
        int e = off;
        while (e < off + max && e < b.length && b[e] != 0) e++;
        return new String(b, off, e - off, StandardCharsets.ISO_8859_1);
    }

    static final java.util.regex.Pattern NAME_RE = java.util.regex.Pattern.compile("[A-Za-z0-9]{1,15}");
    static final java.util.regex.Pattern CRED_RE = java.util.regex.Pattern.compile("[0-9a-f]{12}");

    /** Registers name -> credential. Returns null when created or already registered with the same
     *  credential, otherwise an error string. At most 10 attempts per minute per client address. */
    public String register(String name, String cred, String ip) throws Exception {
        synchronized (accounts) { return register0(name, cred, ip); }
    }

    private static String register0(String name, String cred, String ip) throws Exception {
        long now = System.currentTimeMillis();
        if (regRate.size() > 10_000) regRate.entrySet().removeIf(e -> now - e.getValue()[0] > 60_000);
        long[] r = regRate.computeIfAbsent(ip, k -> new long[2]);
        if (now - r[0] > 60_000) { r[0] = now; r[1] = 0; }
        if (++r[1] > 30) return "rate limited";   // per address and minute: several players behind one NAT re-register on every page load
        if (name == null || !NAME_RE.matcher(name).matches()) return "bad name";
        if (cred == null || !CRED_RE.matcher(cred).matches()) return "bad credential";
        String key = name.toLowerCase(java.util.Locale.ROOT);
        String old = accounts.getProperty(key);
        if (old != null) return old.equals(cred) ? null : "name taken";
        accounts.setProperty(key, cred);
        if (accountsFile.getParent() != null) java.nio.file.Files.createDirectories(accountsFile.getParent());
        saveAccounts();
        System.out.println("us-kk: registered " + name);
        return null;
    }

    /** H5: write to a 0600 temp file next to it and move it over the old one (atomic, never world-readable). */
    private static void saveAccounts() throws Exception {
        java.nio.file.Path dir = accountsFile.toAbsolutePath().getParent();
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile(dir, ".kk-accounts", ".tmp");
        try {
            try { java.nio.file.Files.setPosixFilePermissions(tmp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) { }
            try (var out = java.nio.file.Files.newOutputStream(tmp)) { accounts.store(out, "reo-web US kk accounts: name=credential"); }
            java.nio.file.Files.move(tmp, accountsFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally { java.nio.file.Files.deleteIfExists(tmp); }
    }

    /** Blowfish key for a login name: its registered credential, else the dev password, else null. */
    private byte[] keyFor(String name) {
        String c = accounts.getProperty(name.toLowerCase(java.util.Locale.ROOT));
        if (c != null) return c.getBytes(StandardCharsets.ISO_8859_1);
        return password.length >= 4 ? password : null;
    }

    private byte[] blowfish(byte[] data, boolean enc) throws Exception { return blowfish(data, enc, password); }

    private static byte[] blowfish(byte[] data, boolean enc, byte[] key) throws Exception {
        int n = (data.length + 7) & ~7;
        byte[] in = java.util.Arrays.copyOf(data, n);
        Cipher c = Cipher.getInstance("Blowfish/ECB/NoPadding");
        c.init(enc ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "Blowfish"));
        return c.doFinal(in);
    }

    private void send(SocketAddress to, int type, byte[] payload, int flags, int tag, int ack) throws Exception {
        sendSeq(to, type, payload, flags, tag, ack, -1);
    }

    /** seq < 0: unreliable, clock-based. Reliable messages (0x8000) carry the receiver's own ordered counter:
     *  the client queues them by seq from 0 (ctx+0x5C, 0x1DC84C) and acks them. */
    private byte[] sendSeq(SocketAddress to, int type, byte[] payload, int flags, int tag, int ack, int rseq) throws Exception {
        // sequence numbers grow with the clock, so a client resumed from a save state (or a restarted
        // server) never sees them go backwards (unreliable messages with seq < last are dropped)
        int seq = rseq >= 0 ? rseq : seqByAddr.merge(to, (int) ((System.currentTimeMillis() / 10) & 0x3FFFFFFF), (a, b) -> Math.max(a + 1, b));
        if (type < 0x100) type |= curReq << 8;
        if (16 + payload.length > MAX_MSG)
            System.out.println(svc + ": BUG message type " + Integer.toHexString(type) + " too long (" + payload.length + " bytes) for " + to);
        ByteBuffer b = ByteBuffer.allocate(16 + payload.length + 4);
        b.putShort((short) (flags | (16 + payload.length))).putShort((short) type)
         .putInt(tag).putInt(seq).putInt(ack).put(payload).putInt(TRAILER);
        byte[] d = b.array();
        PacketLog.log(svc, false, to, d, 0, d.length);
        if (tag != 0) { Session ss = sessions.get(tag); if (ss != null && to.equals(ss.addr)) { ss.lastSent = System.currentTimeMillis(); trace(ss, true, type, flags, payload.length); } }
        sock.send(new DatagramPacket(d, d.length, to));
        return d;
    }

    /** H6: if the loop ever dies (socket error, a Throwable outside the per-datagram guard), reopen and go on. */
    @Override public void run() {
        while (true) {
            runOnce();
            try { if (sock != null) sock.close(); } catch (Exception ignored) { }
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            System.out.println(svc + ": restarting the UDP loop");
        }
    }

    private void runOnce() {
        try {
            sock = new DatagramSocket(new InetSocketAddress(bind, port));
            System.out.println("US kk lobby (UDP) on " + bind + ":" + port);
            byte[] buf = new byte[4096];
            sock.setSoTimeout(200);
            while (true) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(p);
                } catch (java.net.SocketTimeoutException e) {
                    tick();
                    continue;
                } catch (java.io.IOException e) {   // e.g. ICMP port unreachable surfacing on some platforms
                    System.out.println(svc + ": receive: " + e);
                    continue;
                }
                if (!allow(p.getSocketAddress())) { tick(); continue; }
                byte[] d = java.util.Arrays.copyOf(p.getData(), p.getLength());
                PacketLog.log(svc, true, p.getSocketAddress(), d, 0, d.length);
                try {
                    datagram(p.getSocketAddress(), d);
                } catch (Throwable e) {     // malformed payloads (BufferUnderflow...) must not stop the service
                    System.out.println(svc + ": " + p.getSocketAddress() + ": " + e);
                }
                tick();
            }
        } catch (Throwable e) {
            System.out.println(svc + " stopped: " + e);
        }
    }

    private long dropped, lastDropLog;

    /** Token bucket per source address; floods from one address cannot starve the others. */
    private boolean allow(SocketAddress a) {
        long now = System.currentTimeMillis();
        if (buckets.size() > 20_000) buckets.entrySet().removeIf(e -> now - (long) e.getValue()[1] > 10_000);
        if (buckets.size() > 20_000) buckets.clear();
        double[] b = buckets.computeIfAbsent(a, k -> new double[] { rateBurst, now });
        b[0] = Math.min(rateBurst, b[0] + (now - b[1]) * ratePps / 1000.0);
        b[1] = now;
        if (b[0] >= 1) { b[0] -= 1; return true; }
        dropped++;
        if (now - lastDropLog > 10_000) { lastDropLog = now; System.out.println(svc + ": rate limit, " + dropped + " datagrams dropped so far (last from " + a + ")"); }
        return false;
    }

    /** Names and other client strings go to the log: no control characters. */
    static String safe(String x) {
        StringBuilder b = new StringBuilder(x.length());
        for (char c : x.toCharArray()) b.append(c < 0x20 || (c >= 0x7F && c < 0xA0) ? '?' : c);
        return b.toString();
    }

    /** A client waiting in a room sends nothing for minutes, so NAT flows (our gateway, home routers) would
     *  drop our pushes. Every 15 s an idle session gets the same harmless reply it gets for 0x14 presence. */
    private long lastSlowTick;
    volatile long retransmits;
    volatile long drops;           // sessions dropped without a goodbye (timeout, same name again), for Metrics

    /** Every loop: resend unacked reliable relays (1 s, up to 30 times); every 5 s: keepalive and expiry. */
    private void tick() {
        try { tick0(); } catch (Throwable e) { System.out.println(svc + ": tick: " + e); }
    }

    private void tick0() {
        long now = System.currentTimeMillis();
        for (Session s : sessions.values()) {
            synchronized (s) {
                if (s.unacked.isEmpty()) continue;
                var it = s.unacked.entrySet().iterator();
                while (it.hasNext()) {
                    var e = it.next();
                    Object[] u = e.getValue();
                    if (now - (Long) u[1] < 1000) continue;
                    int tries = (Integer) u[2] + 1;
                    // the client takes reliable messages strictly in seq order (ctx+0x5C): giving one up stalls every
                    // later one for good (a member stuck in the lobby at START). Resend until the session dies instead.
                    if (tries > RESEND_MAX) { it.remove(); System.out.println(svc + ": " + s.name + " never acked rel seq " + e.getKey()); continue; }
                    u[1] = now; u[2] = tries;
                    byte[] d = (byte[]) u[0];
                    try { sock.send(new DatagramPacket(d, d.length, s.addr)); retransmits++; } catch (Exception ignored) { }
                    SocketAddress alt = s.alt;
                    if (alt != null) try { sock.send(new DatagramPacket(d, d.length, alt)); } catch (Exception ignored) { }
                }
            }
        }
        if (now - lastSlowTick < 1000) return;
        lastSlowTick = now;
        keepalive();
        expire();
        resyncLists(now);
        for (String k; (k = kicks.poll()) != null; )   // moderation: kick() comes from an HTTP thread, applied here
            for (Session s : new ArrayList<>(sessions.values())) if (s.name.equalsIgnoreCase(k)) drop(s, "disconnected by a moderator");
        tickets.entrySet().removeIf(e -> now > (long) e.getValue()[1]);
    }

    private void keepalive() {
        long now = System.currentTimeMillis();
        for (Session s : sessions.values()) {
            if (now - s.lastSeen < PING_MS || now - s.lastPing < PING_MS) continue;
            s.lastPing = now;
            // reliable, so the client acks it (and tick() resends it every second until then)
            try { pushReliable(s, 0x28, ByteBuffer.allocate(8).putInt(0x14).putInt(0).array(), 0x1000); } catch (Exception ignored) { }
        }
        if (seqByAddr.size() > 4096) seqByAddr.clear();   // only keeps seqs monotonic per address; safe to forget
        if (goneSent.size() > 4096) goneSent.clear();
    }

    private void expire() {
        long now = System.currentTimeMillis();
        for (Session s : new ArrayList<>(sessions.values()))
        {
            // a scenario load can keep a slow client (big download, busy browser tab) quiet for a while: more slack in game
            if (s.byeAt != 0 && now - s.byeAt > BYE_GRACE_MS) { drop(s, "disconnected (connection closed, " + where(s) + ")"); continue; }
            if (s.byeAt != 0 && hostByeMs > 0 && now - s.byeAt > hostByeMs && hostOfGame(s)) {
                drop(s, "disconnected (connection closed " + (now - s.byeAt) / 1000 + " s ago, host of " + where(s) + " with members in game: not waiting longer)");
                continue;
            }
            Room rm = rooms.get(s.cur);
            long limit = rm != null && rm.started ? gameDeadMs : deadMs;
            if (now - s.lastSeen > limit) drop(s, "disconnected (no traffic for " + (now - s.lastSeen) / 1000 + " s, " + where(s) + ")");
        }
        // a room nobody is in any more (left without going through leftRoom) must not stay in the lists
        for (Room rm : new ArrayList<>(rooms.values())) {
            if (rm.oid < 0x1000) continue;
            if (!members(rm.oid).isEmpty()) { rm.emptyAt = 0; continue; }
            if (rm.emptyAt == 0) { rm.emptyAt = now; continue; }
            if (now - rm.emptyAt < 20000) continue;
            rooms.remove(rm.oid);
            gameStats(rm, "room empty");
            System.out.println(svc + ": room " + Integer.toHexString(rm.oid) + " closed (empty)");
            try { areaPush(rm); } catch (Exception e) { System.out.println(svc + ": areaPush " + e); }
        }
    }

    /** Session gone without a goodbye (timeout, or the same name connected again). The other members of its
     *  room are told; if it created a room that is still waiting, the room is disbanded: the members get the
     *  host's "disband" room message (0x10 class 0x0D, BIN_3 0x58395C: "The room has been disbanded by the
     *  host." and back to the room list) and go back to the area, and the room leaves the lists (push 5). */
    private void drop(Session s, String why) {
        if (sessions.remove(s.tag) == null) return;
        drops++;   // read-only counter for the metrics panel (reo.common.Metrics)
        System.out.println(svc + ": " + s.name + " " + why);
        int oid = s.cur;
        if (s.playing != 0 && s.playing == oid && (rooms.get(oid) == null || !rooms.get(oid).started))   // started: peerGone() dumps it
            System.out.println(svc + ": " + s.name + " last messages before leaving game " + Integer.toHexString(oid) + ": " + traceDump(s));
        s.cur = 0;
        try {
            Room rm = rooms.get(oid);
            if (rm != null && rm.owner == s.tag && !rm.started) {
                leftScenario(rm, s, s.playing == oid, "disconnected");
                if (!deferDisband(rm, s)) disband(rm, s);
                else leftRoom(s, oid);
            } else {
                if (rm != null && rm.started) peerGone(rm, s);
                else leftScenario(rm, s, s.playing == oid, "disconnected");
                leftRoom(s, oid);
            }
        } catch (Exception e) {
            System.out.println(svc + ": drop " + s.name + ": " + e);
        }
    }

    /** Sent to the gateway on a member's flow when another member of its started room is gone for good:
     *  "REOGW-GONE" + u32 tag of the member that left. The gateway does not forward it to the guest; it tells the
     *  runtime with a WebSocket text message (protocol.md 6.10). The scenario has no message for a member that
     *  left: it only notices silence, and the runtime raised that timeout to 300 s (slow loads in the browser), so
     *  without this the others wait up to 5 min for a host that is gone (no item pickups, enemies frozen). */
    static final byte[] GATEWAY_GONE = "REOGW-GONE".getBytes(StandardCharsets.ISO_8859_1);

    /** A member left a started room for good (dropped, or 0x07 in game): mark an orphaned room, tell the others. */
    private void peerGone(Room rm, Session s) {
        System.out.println(svc + ": " + s.name + " last messages before leaving game " + Integer.toHexString(rm.oid) + ": " + traceDump(s));
        if (rm.owner == s.tag && !rm.hostGone) {
            rm.hostGone = true;
            System.out.println(svc + ": room " + Integer.toHexString(rm.oid) + " lost its host " + s.name + " in game");
        }
        if (!rm.goneTold.add(s.tag)) return;   // already announced (leftScenario)
        int n = tellGone(rm, s, false);
        if (n > 0) System.out.println(svc + ": " + s.name + " gone from game " + Integer.toHexString(rm.oid) + ": told " + n + " member(s)");
    }

    /** REOGW-GONE + s's tag to the other members of rm (onlyPlaying: only those still in its scenario). Count told. */
    private int tellGone(Room rm, Session s, boolean onlyPlaying) {
        if (!peerGoneNotice) return 0;
        byte[] d = ByteBuffer.allocate(GATEWAY_GONE.length + 4).put(GATEWAY_GONE).putInt(s.tag).array();
        int n = 0;
        for (Session o : members(rm.oid)) {
            if (o == s || (onlyPlaying && o.playing != rm.oid)) continue;
            try { sock.send(new DatagramPacket(d, d.length, o.addr)); n++; } catch (Exception ignored) { }
            SocketAddress alt = o.alt;
            if (alt != null) try { sock.send(new DatagramPacket(d, d.length, alt)); } catch (Exception ignored) { }
        }
        return n;
    }

    /** s is out of the scenario of a room already "back to waiting" while members still play it: the host back in
     *  the waiting room (0x0A, STAT), or out of it (0x07, a new room, dropped), or any member still playing that is
     *  dropped. Nobody else tells their games (peerGone() is only for started rooms): the host stops sending game data
     *  and the others wait for it up to the runtime's 300-s teammate timeout, with nobody arbitrating items and
     *  enemies (production 25/10, room 1331: yhama "cannot pick anything up"). With REOGW-GONE their runtime marks
     *  its slot and the game drops it and elects a new host itself: lowest active slot, ELF 0x1C7A28 -> 0x33505E,
     *  announced with 0xF003 (protocol.md 6.15). Once per member and game. */
    private void leftScenario(Room rm, Session s, boolean wasPlaying, String why) {
        if (rm == null || rm.started || rm.oid < 0x1000) return;   // started rooms: peerGone()
        boolean host = rm.owner == s.tag;
        if (!host && !wasPlaying) return;                            // a member already out of the game: nothing to tell
        if (stillPlaying(rm, s) == 0 || !rm.goneTold.add(s.tag)) return;
        int n = tellGone(rm, s, true);
        System.out.println(svc + ": room " + Integer.toHexString(rm.oid) + ": " + (host ? "host " : "") + s.name + " " + why
                + " with " + stillPlaying(rm, s) + " member(s) still in game"
                + (peerGoneNotice ? ": told " + n : " (us.kk.peer_gone_notice off: not told)"));
    }

    /** A member of an orphaned room (hostGone) is back in its waiting room after the scenario: nobody there can
     *  START, so it gets the host's disband (0x10 class 0x0D relayed with the old host's tag, as in disband()) and
     *  goes back to the area. The room stays out of the lists and closes when empty. */
    private void orphaned(Session s, Room rm) throws Exception {
        if (s.cur != rm.oid) return;
        s.cur = rm.parent;
        s.gameReady = false;
        synchronized (s.held) { s.held.clear(); }
        relay(s, rm.owner, "(host gone)", 0x10, ByteBuffer.allocate(12).putInt(1).putInt(s.tag).put((byte) 0x0D).array(), 0x8000);
        System.out.println(svc + ": " + s.name + " back in room " + Integer.toHexString(rm.oid) + " without its host: disbanded for it");
        if (members(rm.oid).isEmpty()) { rooms.remove(rm.oid); gameStats(rm, "closed"); System.out.println(svc + ": room " + Integer.toHexString(rm.oid) + " closed"); }
    }

    /** A message with a session tag we do not know: the server restarted under a logged-in player (deploy), or its
     *  session was dropped (silence, moderator, same name again). Without an answer the client resends into the void
     *  for ~100 s ("N frames in, 0 out" at the gateway) before it gives up. kkClient message 0x31 = disconnect with
     *  error (handler 0x1E0AEC in SLUS_207.65, 0x166084 in SLUS_209.84): it closes the client side (0x1E1B3C, which
     *  sends 0x02) and calls the login callback (ctx+0x5B8) with status 0x27, which shows the lobby error and leaves
     *  the online menus (protocol.md 6.12). Payload {u32 code, u32, u32 detail} (the callback reads the detail).
     *  Not for game data 0x0F: in the scenario BIN_3 is not loaded and that callback points at nothing (the 0x07
     *  push crashed it there, 6.10). At most one per address every SESSION_GONE_MS (32 bytes; no reply bigger than that for spoofed tags). */
    private void sessionGone(SocketAddress from, int tag, int type, int seq, int flags) throws Exception {
        long now = System.currentTimeMillis();
        Long last = goneSent.get(from);
        if (last != null && now - last < SESSION_GONE_MS) return;
        goneSent.put(from, now);
        System.out.println(svc + ": unknown session " + Integer.toHexString(tag) + " from " + from + " (type " + Integer.toHexString(type) + "): told it is gone (0x31)");
        send(from, 0x31, new byte[12], flags & 0x1000, tag, (flags & 0x8000) != 0 ? seq : 0);
    }

    /** "REOGW-LEFT" + u8 slot from the gateway (net/gateway guestControl) on a member's flow: its runtime saw its own
     *  player leave the scenario while the session goes on (dead or zombified, on the results screen; protocol.md
     *  6.17). Until it presses EXIT it keeps sending game data but no longer arbitrates: if it was the host nobody
     *  can pick anything up. The members still playing get "REOGW-LEFT" + u32 tag + u8 slot (their runtime drops that
     *  slot at once and the game elects a new host) and its game data is no longer relayed to them. Once per member and
     *  game (goneTold, shared with peerGone()/leftScenario(): its later EXIT or drop is not announced again). */
    static final byte[] GATEWAY_LEFT = "REOGW-LEFT".getBytes(StandardCharsets.ISO_8859_1);

    private void gatewayLeft(SocketAddress from, int slot) {
        for (Session s : sessions.values()) {
            if (!from.equals(s.addr)) continue;
            Room rm = rooms.get(s.cur);
            if (rm == null || rm.oid < 0x1000 || s.playing != rm.oid || s.leftGame == rm.oid) return;
            s.leftGame = rm.oid;
            boolean host = rm.owner == s.tag;
            int n = 0;
            if (peerGoneNotice && rm.goneTold.add(s.tag)) {
                byte[] d = ByteBuffer.allocate(GATEWAY_LEFT.length + 5).put(GATEWAY_LEFT).putInt(s.tag).put((byte) slot).array();
                for (Session o : members(rm.oid)) {
                    if (o == s || o.playing != rm.oid || o.leftGame == rm.oid) continue;
                    try { sock.send(new DatagramPacket(d, d.length, o.addr)); n++; } catch (Exception ignored) { }
                    SocketAddress alt = o.alt;
                    if (alt != null) try { sock.send(new DatagramPacket(d, d.length, alt)); } catch (Exception ignored) { }
                }
            }
            System.out.println(svc + ": " + (host ? "host " : "") + s.name + " left the scenario of game " + Integer.toHexString(rm.oid)
                    + " (slot " + slot + ", session goes on): told " + n + ", its game data no longer relayed");
            return;
        }
    }

    /** "REOGW-TRK" + 11 bytes from the gateway (net/gateway guestControl) on a member's flow: what the web page's
     *  status tracker shows of its own player and the game does not send to the others (protocol.md 6.18):
     *  u8 slot, u8 character, u8 status, u16 virus in tenths of a percent (0-1000), u16 antivirus, u16 herb,
     *  u16 bleeding (big endian). Relayed only to the other members still playing the same game, as "REOGW-TRK" +
     *  u32 tag + the same 11 bytes; never through the game relay. At most one per session every TRK_GAP_MS. */
    static final byte[] GATEWAY_TRK = "REOGW-TRK".getBytes(StandardCharsets.ISO_8859_1);
    static final int TRK_BODY = 11;
    static final long TRK_GAP_MS = 400;

    /** The datagram for the other members, or null when d is no valid REOGW-TRK (length, slot 0-3, character 0-7,
     *  virus 0-1000). */
    static byte[] trkRelay(byte[] d, int tag) {
        int n = GATEWAY_TRK.length;
        if (d.length != n + TRK_BODY || !java.util.Arrays.equals(d, 0, n, GATEWAY_TRK, 0, n)) return null;
        int slot = d[n] & 0xFF, ch = d[n + 1] & 0xFF, virus = ((d[n + 3] & 0xFF) << 8) | (d[n + 4] & 0xFF);
        if (slot > 3 || ch > 7 || virus > 1000) return null;
        return ByteBuffer.allocate(n + 4 + TRK_BODY).put(GATEWAY_TRK).putInt(tag).put(d, n, TRK_BODY).array();
    }

    private void gatewayTrk(SocketAddress from, byte[] d) {
        for (Session s : sessions.values()) {
            if (!from.equals(s.addr)) continue;
            Room rm = rooms.get(s.cur);
            if (rm == null || rm.oid < 0x1000 || s.playing != rm.oid) return;
            long now = System.currentTimeMillis();
            if (now - s.trkAt < TRK_GAP_MS) return;
            byte[] out = trkRelay(d, s.tag);
            if (out == null) return;
            s.trkAt = now;
            for (Session o : members(rm.oid)) {
                if (o == s || o.playing != rm.oid) continue;
                try { sock.send(new DatagramPacket(out, out.length, o.addr)); } catch (Exception ignored) { }
            }
            return;
        }
    }

    /** Sent by the gateway (net/gateway forwardUDP) on a UDP flow when it ends: the WebSocket of that player closed. */
    static final byte[] GATEWAY_BYE = "REOGW-BYE".getBytes(StandardCharsets.ISO_8859_1);

    /** Sent by the gateway (net/gateway notifyRTT) on each UDP flow every 15 s: "REOGW-RTT" + u32 BE ms, the
     *  browser <-> gateway WebSocket round trip of that player. Kept per address (it also arrives before the login). */
    static final byte[] GATEWAY_RTT = "REOGW-RTT".getBytes(StandardCharsets.ISO_8859_1);
    private final Map<SocketAddress, Integer> rttByAddr = new ConcurrentHashMap<>();

    private void gatewayRtt(SocketAddress from, int ms) {
        if (ms < 0 || ms > 120_000) return;
        if (rttByAddr.size() > 4096) rttByAddr.clear();
        rttByAddr.put(from, ms);
        for (Session s : sessions.values())
            if (from.equals(s.addr)) {
                Room g = rooms.get(s.cur);
                if (g != null && g.started) noteRtt(g, s.name, ms);
            }
    }

    private static void noteRtt(Room g, String name, int ms) {
        long[] r = g.gameRtt.computeIfAbsent(name, k -> new long[] { 0, 0, 0, 0, -1 });
        synchronized (r) {
            r[0]++; r[1] += ms; r[2] = Math.max(r[2], ms);
            if (r[4] >= 0) r[3] += Math.abs(ms - r[4]);
            r[4] = ms;
        }
    }

    private void datagram(SocketAddress from, byte[] d) throws Exception {
        if (d.length == GATEWAY_RTT.length + 4 && java.util.Arrays.equals(d, 0, GATEWAY_RTT.length, GATEWAY_RTT, 0, GATEWAY_RTT.length)) {
            gatewayRtt(from, ByteBuffer.wrap(d, GATEWAY_RTT.length, 4).getInt());
            return;
        }
        if (d.length == GATEWAY_LEFT.length + 1 && java.util.Arrays.equals(d, 0, GATEWAY_LEFT.length, GATEWAY_LEFT, 0, GATEWAY_LEFT.length)) {
            gatewayLeft(from, d[GATEWAY_LEFT.length] & 0xFF);
            return;
        }
        if (d.length == GATEWAY_TRK.length + TRK_BODY && java.util.Arrays.equals(d, 0, GATEWAY_TRK.length, GATEWAY_TRK, 0, GATEWAY_TRK.length)) {
            gatewayTrk(from, d);
            return;
        }
        if (java.util.Arrays.equals(d, GATEWAY_BYE)) {
            // only the session at that exact address (the flow's own port): nobody can close someone else's.
            // Not dropped at once: the web runtime reopens its WebSocket 3 s after a drop and comes back on a new
            // flow (offer()); a player that does not come back within BYE_GRACE_MS is gone.
            for (Session s : sessions.values())
                if (from.equals(s.addr) && s.byeAt == 0) {
                    s.byeAt = System.currentTimeMillis();
                    System.out.println(svc + ": " + s.name + " connection closed (" + where(s) + ", last message " + (s.byeAt - s.lastSeen) / 100 / 10.0
                            + " s before), waiting " + (hostByeMs > 0 && hostOfGame(s) ? hostByeMs / 1000 + " s (host with members in game)"
                            : BYE_GRACE_MS / 1000 + " s") + " for a reconnect");
                }
            return;
        }
        if (d.length < 20 || (ByteBuffer.wrap(d, d.length - 4, 4).getInt() & 0xFFFFFF00) != 0xBA476600) return;
        ByteBuffer b = ByteBuffer.wrap(d, 0, d.length - 4);
        // Flag 0x0800 on the first message = a packet of several messages (e.g. the host's START: 0x10 class 3 to
        // each member + STAT in one datagram). Only that first header carries a real seq/ack: the following ones
        // have stale stack values in seq and ack (seen 0 and values reused from older packets, run q4/t1), so they
        // are neither deduplicated nor acked on their own: the packet is a duplicate when its first message is.
        sub = false; packetDup = false;
        boolean multi = false;
        while (b.remaining() >= 16) {
            int start = b.position();
            int fl = b.getShort() & 0xFFFF, tf = b.getShort() & 0xFFFF, type = tf & 0x7F, tag = b.getInt(), seq = b.getInt();
            int ackIn = b.getInt();
            int len = fl & 0x3FF;
            if (len < 16 || start + len > d.length - 4) return;
            byte[] pl = java.util.Arrays.copyOfRange(d, start + 16, start + len);
            b.position(start + len);
            curReq = tf >> 8;
            int f = fl & 0xFC00;
            if (sub) f &= ~0x4000;            // a stale ack field must not clear one of our pending messages
            message(from, f, type, tag, seq, ackIn, pl);
            if (start == 0) multi = (fl & 0x0800) != 0;
            sub = multi;
        }
        sub = false; packetDup = false;
    }

    /** Set by datagram(): the message being handled follows the first one of a 0x0800 packet; its first was a resend. */
    private boolean sub, packetDup;

    private void message(SocketAddress from, int flags, int type, int tag, int seq, int ackIn, byte[] pl) throws Exception {
        // replies mirror the request flag 0x1000: the client picks the callback slot by it
        // (e.g. 0x09 sent with 0xA000 -> slot 0x16, with 0xB000 -> slot 0x17)
        int rf = flags & 0x1000, ack = 0;
        if ((flags & 0x8000) != 0) { rf |= 0x4000; ack = seq; }
        Session s = sessions.get(tag);
        if (s != null && !from.equals(s.addr)) {
            // H2: a valid tag from another address moves the session only with an ack of one of our pending
            // reliable messages (which only the real client has seen); anything else is ignored
            boolean proof;
            synchronized (s) { proof = (flags & 0x4000) != 0 && s.unacked.containsKey(ackIn); }
            if (!proof) {
                // the player's WebSocket reconnected (the web runtime reopens it 3 s after a drop): same game, same
                // tag, but a new gateway flow = new source port. Offer our pending reliable messages there too; the
                // client's ack of one of them is the proof above and moves the session. Before this, a reconnected
                // player was ignored until the silence timeout (gateway: "N frames in, 0 out").
                offer(s, from);
                return;
            }
            System.out.println(svc + ": " + s.name + " moved " + s.addr + " -> " + from + " (" + where(s) + ")");
            s.addr = from;
            s.alt = null;
            s.byeAt = 0;
        }
        if (s == null && sessionGoneNotice && tag != 0 && type != 0x2C && type != 0x01 && type != 0x02 && type != 0x0F) { sessionGone(from, tag, type, seq, flags); return; }
        if (s != null) {
            long now = System.currentTimeMillis(), gap = now - s.lastSeen;
            if (gap >= 12_000) {
                Room sr = rooms.get(s.cur);
                System.out.println(svc + ": " + s.name + " silent for " + gap / 1000 + " s (" + where(s) + ")"
                        + (sr != null && sr.oid >= 0x1000 && sr.owner == s.tag ? " (host)" : ""));
            }
            s.lastSeen = now;
            trace(s, false, type, flags, pl.length);
        }
        // flag 0x4000: ack of one of our reliable relays (bare ack, or piggybacked on a client message)
        if (s != null && (flags & 0x4000) != 0) synchronized (s) { s.unacked.remove(ackIn); }
        if ((flags & 0xC000) == 0x4000 && pl.length == 0 && type != 0x07) return;   // a bare ack of one of our reliable messages
        if (s != null && !HANDLED.contains(type) && s.unhandledSeen.add(type))
            System.out.println(svc + ": " + s.name + " unhandled type " + Integer.toHexString(type) + " flags " + Integer.toHexString(flags)
                    + " " + pl.length + " bytes (" + where(s) + ", generic 0x28): " + hex(java.util.Arrays.copyOf(pl, Math.min(pl.length, 24))));
        ByteBuffer r;
        switch (type) {
            case 0x2C: login(from, pl); return;
            case 0x01: {
                if (pl.length < 0x10 + 20) return;
                String name = cstr(pl, 0x10, 0x10).replaceAll("-+$", "");
                // H1: the ticket of a login that decrypted (payload +0x24 u32 length, +0x28 ticket), for this name
                Object[] tk = null;
                if (pl.length >= 0x28 + 16 && ByteBuffer.wrap(pl).getInt(0x24) == 16)
                    tk = tickets.remove(hex(java.util.Arrays.copyOfRange(pl, 0x28, 0x28 + 16)));
                if (tk == null || !((String) tk[0]).equalsIgnoreCase(name) || System.currentTimeMillis() > (long) tk[1]) {
                    System.out.println(svc + ": connect " + safe(name) + " from " + from + " refused (no valid ticket)");
                    return;
                }
                if (sessions.size() >= maxSessions) { System.out.println(svc + ": connect " + safe(name) + " refused (server full)"); return; }
                for (Session o : new ArrayList<>(sessions.values()))
                    if (o.name.equalsIgnoreCase(name)) drop(o, "replaced by a new connection");
                int t;
                do { t = rnd.nextInt() & 0x7FFFFFFF; } while (t == 0 || sessions.containsKey(t));
                sessions.put(t, new Session(t, name, from));
                System.out.println(svc + ": " + safe(name) + " connected (session " + Integer.toHexString(t) + ")");
                send(from, 0x29, ByteBuffer.allocate(12).putInt(0).putInt(1).putInt(t).array(), rf, 0, ack);
                return;
            }
            case 0x48: if (s == null) return; query(from, pl, rf, tag, ack); return;   // H3: no reply bigger than the request without a session
            case 0x25: {               // Search for User
                if (s == null) return;   // H3 (BIN_3 0x59A640): {name[16] (padded with -), u32 len, u32 1}
                String want = cstr(pl, 0, 16).replaceAll("-+$", "");
                Session f = null;
                for (Session o : sessions.values()) if (o.name.equalsIgnoreCase(want)) f = o;
                // reply (parser 0x1DEF1C, callback 0x59A580): u32, u32 found (!= 0), u32 n, n x {name[16], u32 online (!= 0),
                // u32 OID of its area (or obmft), u32 OID of its room (0 = none), u32 len, data[len]}; the client then asks
                // 0x48 OID=area and 0x49 OID=room and shows mode / area / room (0x59A310); MOVE goes there
                int n = f == null ? 0 : 1;
                r = ByteBuffer.allocate(12 + 0x20 * n).putInt(0x25).putInt(n).putInt(n);
                if (f != null) {
                    int roomOid = rooms.containsKey(f.cur) ? f.cur : 0;
                    int areaOid = roomOid != 0 ? rooms.get(roomOid).parent : (f.cur > OID_AREA && f.cur <= OID_AREA + areaCount() ? f.cur : OID_FRONT);
                    r.put(java.util.Arrays.copyOf(f.name.getBytes(StandardCharsets.ISO_8859_1), 16)).putInt(1).putInt(areaOid).putInt(roomOid).putInt(0);
                }
                System.out.println(svc + ": " + s.name + " searches " + safe(want) + (f == null ? ": not found" : ": in " + Integer.toHexString(f.cur)));
                send(from, 0x25, r.array(), rf, tag, ack);
                return;
            }
            case 0x49: {               // object by OID (MOVE after a search, BIN_3 0x58AE64; cond "OID", op, u32 OID, u32)
                if (pl.length >= 17 && "LOID".equals(new String(pl, 8, 4, StandardCharsets.ISO_8859_1))) {
                    // File #2 room list: u32 max (0 = any), u8 n, 3 pad, {"LOID" op 0x01 u32 area OID} (9 bytes), then
                    // {"STAT" op '!' u32 mask, u32 value} (13 bytes): rooms of that area whose STAT & mask == value. max 0 = the
                    // lobby counters ("In lobby / Now playing", netwk 0x5B5A80 sums +0x14), max 30 = ROOM LIST (callback 0x5A1790).
                    ByteBuffer q = ByteBuffer.wrap(pl);
                    int max = q.getInt(0) <= 0 ? 31 : Math.min(q.getInt(0), 31), n = pl[4] & 0xFF, parent = q.getInt(13);
                    List<int[]> stat = new ArrayList<>();
                    for (int k = 1, o = 17; k < n && o + 13 <= pl.length; k++, o += 13) stat.add(new int[] { q.getInt(o + 5), q.getInt(o + 9) });
                    List<Room> hits = new ArrayList<>();
                    for (Room rm : rooms.values()) {
                        if (rm.parent != parent || rm.hostGone || rm.afterGame) continue;
                        int st = roomStat(rm);
                        boolean ok = true;
                        for (int[] c : stat) ok &= (st & c[0]) == c[1];   // op '!' (0x21) = masked equality (list: 0x00080000 set, 0x40000000 clear)
                        if (ok && hits.size() < max) hits.add(rm);
                    }
                    r = ByteBuffer.allocate(12 + 0x28 * hits.size()).putInt(0x49).putInt(0).putInt(hits.size());
                    for (Room rm : hits)
                        r.put(java.util.Arrays.copyOf(rm.name.getBytes(StandardCharsets.ISO_8859_1), 16)).putInt(rm.parent).putInt(members(rm.oid).size())
                         .putInt(0).putInt(rm.entryFlags() | (rm.started ? 0x40000000 : 0)).putInt(rm.max).putInt(rm.oid);
                    send(from, 0x49, r.array(), rf, tag, ack);
                    return;
                }
                int oid = pl.length >= 17 ? ByteBuffer.wrap(pl).getInt(13) : 0;
                Room rm = rooms.get(oid);
                // reply: u32 0x49, u32 0, u32 n, n x 0x28 {name[16], 6 u32}; callback 0x58AC20 keeps it at 0x67B7F8 and the
                // display tests +0x1C & 0x40000000 (in game). [HIPOTESIS] {parent, users, 0, flags, max, OID}
                int n = rm == null ? 0 : 1;
                r = ByteBuffer.allocate(12 + 0x28 * n).putInt(0x49).putInt(0).putInt(n);
                if (rm != null)
                    r.put(java.util.Arrays.copyOf(rm.name.getBytes(StandardCharsets.ISO_8859_1), 16)).putInt(rm.parent).putInt(members(oid).size())
                     .putInt(0).putInt(rm.entryFlags() | (rm.started ? 0x40000000 : 0)).putInt(rm.max).putInt(oid);
                // diagnosis of "Now searching" that never ends (hiihamster, File #2, 27/10): the search flow is 0x25, then
                // 0x48 (area) and this lookup; log what the client asked and got
                System.out.println(svc + ": " + (s != null ? s.name : "?") + " looks up " + Integer.toHexString(oid) + ": "
                        + (rm == null ? "none" : (rm.started ? "in game" : rm.afterGame ? "back to waiting" : "waiting") + ", "
                        + members(oid).size() + "/" + rm.max + (rm.hostGone ? ", host gone" : "")) + " (" + pl.length + " bytes)");
                send(from, 0x49, r.array(), rf, tag, ack);
                return;
            }
            case 0x09: {
                if (pl.length < 8) break;
                int oid = ByteBuffer.wrap(pl).getInt();
                String attr = new String(pl, 4, 4, StandardCharsets.ISO_8859_1);
                Room rq = rooms.get(oid);
                if (s != null && s.cur == oid) backFromGame(s, rq);   // only the waiting room (BIN_3) asks USER/MAXI of the room
                if (s != null && s.cur == oid && rq != null && rq.owner == s.tag) leftScenario(rq, s, false, "back in the waiting room");
                int v = "USER".equals(attr) ? users(oid) : "MAXI".equals(attr) ? (rq != null ? rq.max : 100) : 0;
                r = ByteBuffer.allocate(12).putInt(oid);
                r.put(pl, 4, 4).putInt(v);
                send(from, 0x09, r.array(), rf, tag, ack);
                return;
            }
            case 0x06: {               // enter object {u32 OID}: reply 0x28; other room members get a type-6 push
                if (pl.length < 4 || s == null) break;
                int oid = ByteBuffer.wrap(pl).getInt();
                Room rm = rooms.get(oid);
                if (rm != null && s.cur == oid && System.currentTimeMillis() - s.enteredAt < ENTER_REPEAT_MS) {
                    // the same join again (the client resends it until our 0x28 arrives; 0x06 is not in seenRel): the
                    // same reply, without entering twice. Before this, when the first join filled the room the repeat
                    // got a 0x27 right after the 0x28 and the client hung in "Enter Room"; otherwise the other members
                    // got the member push twice (production 22/10: 35 of 424 joins came twice, 0.1-1 s apart)
                    System.out.println(svc + ": " + s.name + " enters " + Integer.toHexString(oid) + " again (repeated join)");
                    send(from, 0x28, ByteBuffer.allocate(8).putInt(type).putInt(0).array(), rf, tag, ack);
                    return;
                }
                if (rm == null && oid >= 0x1000) {
                    // a room that was closed meanwhile (the list was stale): same refusal as a full room
                    System.out.println(svc + ": " + s.name + " cannot enter room " + Integer.toHexString(oid) + " (gone)");
                    send(from, 0x27, ByteBuffer.allocate(12).putInt(0x06).putInt(JOIN_REFUSED).putInt(0).array(), rf, tag, ack);
                    fixListAfterRefusal(s, oid, null);
                    return;
                }
                if (rm != null && (members(oid).size() >= rm.max || rm.started || rm.hostGone || rm.afterGame)) {
                    // 0x27 {0x06, code}: BIN_3 callback 0x587A10 shows "You couldn't enter the room" (code != 0x0F)
                    System.out.println(svc + ": " + s.name + " cannot enter room " + Integer.toHexString(oid) + " (full or started)");
                    send(from, 0x27, ByteBuffer.allocate(12).putInt(0x06).putInt(JOIN_REFUSED).putInt(0).array(), rf, tag, ack);
                    fixListAfterRefusal(s, oid, rm);
                    return;
                }
                if (rm != null && !rm.password.isEmpty()) {
                    // room join (API 0x1E2C2C, 0x18 bytes): {OID, u32, pw[16] at +8}; API 0x1E2A24 (0x14 bytes): pw at +4
                    String pw = pl.length >= 0x18 ? cstr(pl, 8, 16) : pl.length >= 0x14 ? cstr(pl, 4, 16) : "";
                    if (!pw.equals(rm.password)) {
                        System.out.println(svc + ": " + s.name + " wrong password for room " + Integer.toHexString(oid));
                        // 0x27 {0x06, 0x0F}: callback 0x587A10 shows "Incorrect password. Entry forbidden." and
                        // goes back to the room list (a 0x28 {6, x} has status 0: the client thought it had entered)
                        send(from, 0x27, ByteBuffer.allocate(12).putInt(0x06).putInt(JOIN_BAD_PASSWORD).putInt(0).array(), rf, tag, ack);
                        return;
                    }
                }
                int prev = s.cur;
                s.playing = 0;
                s.cur = oid;
                s.enteredAt = System.currentTimeMillis();
                s.gameReady = false;
                synchronized (s.held) { s.held.clear(); }
                // entering without a 0x07 first (died and came back to the lobby): the old room would keep a ghost entry
                if (prev != oid && rooms.containsKey(prev)) leftRoom(s, prev);
                System.out.println(svc + ": " + s.name + " enters " + Integer.toHexString(oid));
                send(from, 0x28, ByteBuffer.allocate(8).putInt(type).putInt(0).array(), rf, tag, ack);
                if (rm != null) { for (Session o : members(oid)) if (o != s) push(o, 0x06, member(s), 0); areaPush(rm); }
                return;
            }
            case 0x07: {               // leave the current object
                if (s == null) break;
                int old = s.cur;
                boolean wasPlaying = old != 0 && s.playing == old;
                s.playing = 0;
                // out of a room the client is back in that area's room list (it asks 0x0B next); out of an area, nowhere
                Room was = rooms.get(old);
                if (was != null) System.out.println(svc + ": " + s.name + " leaves " + where(s)
                        + (was.started ? " " + (System.currentTimeMillis() - was.startedAt) / 1000 + " s after START" : ""));
                s.cur = was != null ? was.parent : 0;
                send(from, 0x28, ByteBuffer.allocate(8).putInt(type).putInt(0).array(), rf, tag, ack);
                if (was != null && was.started) peerGone(was, s);
                else leftScenario(was, s, wasPlaying, "left the room");
                if (was != null && !was.started && was.owner == s.tag && !was.hostGone && stillPlaying(was, s) > 0) {
                    // the host left the waiting room with members still in game (its own 0x0D disband went only to the
                    // ones back there, see the relay): they get the disband when they are back (orphaned())
                    was.hostGone = true;
                    System.out.println(svc + ": room " + Integer.toHexString(old) + " lost its host " + s.name + " with "
                            + stillPlaying(was, s) + " member(s) still in game");
                }
                leftRoom(s, old);
                return;
            }
            case 0x0B: {               // list the child objects (rooms) of an object {u32 OID}
                if (pl.length < 4) break;
                int oid = ByteBuffer.wrap(pl).getInt();
                // browsing an area's room list = being in that area (gets the type 4/5 list pushes)
                if (s != null && !rooms.containsKey(s.cur) && oid > OID_AREA && oid <= OID_AREA + areaCount()) s.cur = oid;
                List<Room> kids = new ArrayList<>();
                for (Room rm : rooms.values()) if (rm.parent == oid && !rm.started && !rm.hostGone && !rm.afterGame) kids.add(rm);
                kids.sort((x, y) -> Integer.compare(x.oid, y.oid));
                r = ByteBuffer.allocate(12 + 0x24 * kids.size()).putInt(0x0B).putInt(0).putInt(kids.size());
                for (Room rm : kids) r.put(roomEntry(rm));
                send(from, 0x0B, r.array(), rf, tag, ack);
                return;
            }
            case 0x04: {               // create a room in the current object: {name[16], u32 max, pw..., flags} [formato parcial]
                if (s == null || pl.length < 0x14) break;
                if ((flags & 0x8000) != 0 && seq == s.lastCreateSeq) {   // resent create: same room
                    send(from, 0x28, ByteBuffer.allocate(8).putInt(0x04).putInt(s.lastCreateOid).array(), rf, tag, ack);
                    return;
                }
                ByteBuffer q = ByteBuffer.wrap(pl);
                String name = cstr(pl, 0, 16);
                if (name.isBlank()) name = s.name.length() > 15 ? s.name.substring(0, 15) : s.name;   // empty Room Title
                String pw = pl.length >= 0x24 ? cstr(pl, 0x14, 16) : "";
                int max = q.getInt(0x10);
                if (max <= 0 || max > 4) max = 4;
                int fl = pl.length >= 0x2C ? q.getInt(0x28) : 0;
                for (Room old : new ArrayList<>(rooms.values()))   // H4: one open room per creator
                    if (old.owner == s.tag && !old.hostGone && (old.started || !deferDisband(old, s))) disband(old, s);
                String asked = name;
                name = uniqueName(s.cur, name);   // two rooms with the same title in one list look like one (report 181843a25185)
                if (!name.equals(asked)) System.out.println(svc + ": room title [" + safe(asked) + "] already used in " + Integer.toHexString(s.cur) + ": [" + safe(name) + "]");
                if (rooms.size() >= maxRooms) {
                    System.out.println(svc + ": " + s.name + " cannot create a room (server full)");
                    send(from, 0x27, ByteBuffer.allocate(12).putInt(0x04).putInt(1).putInt(0).array(), rf, tag, ack);
                    return;
                }
                int oid;
                synchronized (this) { oid = nextRoom++; }
                Room rm = new Room(oid, s.cur, name, max, fl, pl, pw);
                rm.owner = s.tag;
                rm.ownerName = s.name;
                rooms.put(oid, rm);
                s.lastCreateSeq = (flags & 0x8000) != 0 ? seq : -1; s.lastCreateOid = oid;
                s.gameReady = false;
                synchronized (s.held) { s.held.clear(); }
                s.cur = oid;           // the creator is in the room (next it asks 0x0A members and USER/MAXI of it)
                System.out.println(svc + ": " + s.name + " creates room " + Integer.toHexString(oid) + " [" + safe(name) + "] in " + Integer.toHexString(rm.parent) + (pw.isEmpty() ? "" : " (password)") + " raw " + hex(pl));
                // the create callback (slot 0x1F, BIN_3 0x5893A0) is reached through the generic reply:
                // 0x28 {u32 0x04, u32 OID of the new room}
                send(from, 0x28, ByteBuffer.allocate(8).putInt(0x04).putInt(oid).array(), rf, tag, ack);
                areaPush(rm);
                return;
            }
            case 0x0A: {               // list the members of an object {u32 OID}: n x {name[16], u32 id, u32 0}
                int oid = pl.length >= 4 ? ByteBuffer.wrap(pl).getInt() : (s != null ? s.cur : 0);
                Room back = rooms.get(oid);
                boolean orphan = back != null && back.hostGone && s != null && s.cur == oid;
                boolean wasPlaying = back != null && s != null && s.cur == oid && s.playing == oid;
                if (s != null && s.cur == oid) backFromGame(s, back);   // back in the waiting room after the scenario
                if (back != null && back.started && !back.hostGone && s != null && s.cur == oid) {
                    // after the scenario the members come back to the waiting room (0x0D, 0x0C, 0x0A {room}): listed again
                    gameStats(back, "back to waiting");
                    back.started = false;
                    back.afterGame = true;
                    back.stat &= ~0x40000000;   // File #2 lists filter on STAT (0x40000000 = in game)
                    System.out.println(svc + ": room " + Integer.toHexString(oid) + " back to waiting (members only)");
                    areaPush(back);
                }
                // the host back in the waiting room while members still play: their games must drop it and pick a new host.
                // Any other member back from the scenario too (memberBackGone): it is out of that scenario for good, and
                // without a notice the others wait for it up to the runtime's 300-s teammate timeout (production 26/10,
                // room 193d: d34n back at 22:09:54, dropped by Salamino's game by timeout at 22:14:03, protocol.md 6.13)
                if (back != null && s != null && s.cur == oid && (back.owner == s.tag || (memberBackGone && wasPlaying)))
                    leftScenario(back, s, wasPlaying, "back in the waiting room");
                List<Session> ms = members(oid);
                if (rejoinPush && back != null && s != null && s.cur == oid && s.playing != oid)
                    ms.removeIf(o -> o != s && o.playing == oid);   // still in the scenario: pushed (0x06) when back
                if (back != null && ownerFirst) ms.sort((x, y) -> Boolean.compare(y.tag == back.owner, x.tag == back.owner));   // test knob
                // A member record is 0x108 bytes (name, id, len, 0xF0 profile): 4 members do not fit in one message
                // (10-bit length). The callback (File #1 BIN_3 0x588120, File #2 0x5A08xx) appends the records of each
                // reply to the list (count 0x643D58) and only completes the request when the result has no request
                // id (+2) or the message has flag 0x2000 (+1 = 0, dispatcher 0x1DFE34): so the parts go with a non-zero
                // request id and without 0x2000, the last one with 0x2000.
                int per = membersPerPart;
                if (ms.size() <= per) {
                    java.io.ByteArrayOutputStream mb = new java.io.ByteArrayOutputStream();
                    for (Session o : ms) mb.writeBytes(member(o));
                    r = ByteBuffer.allocate(12 + mb.size()).putInt(0x0A).putInt(0).putInt(ms.size()).put(mb.toByteArray());
                    send(from, 0x0A, r.array(), rf, tag, ack);
                    if (orphan) orphaned(s, back);   // after the reply: the client is waiting for its member list
                    return;
                }
                int save = curReq;
                try {
                    for (int i = 0; i < ms.size(); i += per) {
                        List<Session> part = ms.subList(i, Math.min(ms.size(), i + per));
                        boolean last = i + per >= ms.size();
                        java.io.ByteArrayOutputStream mb = new java.io.ByteArrayOutputStream();
                        for (Session o : part) mb.writeBytes(member(o));
                        r = ByteBuffer.allocate(12 + mb.size()).putInt(0x0A).putInt(0).putInt(part.size()).put(mb.toByteArray());
                        curReq = last ? save : (save != 0 ? save : 1);
                        send(from, 0x0A, r.array(), rf | (last ? 0x2000 : 0), tag, ack);
                    }
                } finally { curReq = save; }
                System.out.println(svc + ": member list of " + Integer.toHexString(oid) + " (" + ms.size() + ") sent in " + ((ms.size() + per - 1) / per) + " parts to " + (s != null ? s.name : "?") + ": " + ms.stream().map(o -> o.name).toList());
                if (orphan) orphaned(s, back);
                return;
            }
            case 0x0C: {               // profile (0xF0 bytes, sent before create/enter): kept for member records
                if (s != null) s.profile = pl.clone();
                break;
            }
            case 0x08: {               // set attribute(s) on an object (raw, caller-defined): store and log
                if (s != null) {
                    Room rm = rooms.get(s.cur);
                    if (rm != null && pl.length >= 8) rm.attrs.put(hex(java.util.Arrays.copyOf(pl, 8)), pl);
                    if (rm != null && pl.length >= 8 && "STAT".equals(new String(pl, 0, 4, StandardCharsets.ISO_8859_1))) {
                        rm.stat = ByteBuffer.wrap(pl).getInt(4);
                        if ((rm.stat & 0x40000000) == 0) backFromGame(s, rm);   // only the waiting room (BIN_3) writes STAT
                        if (rm.started && (rm.stat & 0x40000000) == 0 && rm.owner == s.tag) {
                            gameStats(rm, "back to waiting");
                            rm.started = false;   // the host is back in the waiting room: its members can START again
                            rm.afterGame = true;
                            System.out.println(svc + ": room " + Integer.toHexString(rm.oid) + " back to waiting (STAT, members only)");
                            areaPush(rm);
                        }
                        if ((rm.stat & 0x40000000) == 0 && rm.owner == s.tag) leftScenario(rm, s, false, "back in the waiting room (STAT)");
                    }
                    if (rm != null && pl.length >= 8 && "STAT".equals(new String(pl, 0, 4, StandardCharsets.ISO_8859_1))
                            && (ByteBuffer.wrap(pl).getInt(4) & 0x40000000) != 0) {
                        // START SCENARIO: out of the room lists; every member holds game data again until it has loaded
                        for (Session o : members(rm.oid)) { o.gameReady = false; o.playing = rm.oid; o.leftGame = 0; o.strayLogged = false; synchronized (o.held) { o.held.clear(); } }
                        if (!rm.started) {
                            rm.started = true;
                            rm.goneTold.clear();
                            rm.startedAt = System.currentTimeMillis();
                            rm.gameMsgs.clear();
                            System.out.println(svc + ": room " + Integer.toHexString(rm.oid) + " started (" + members(rm.oid).size() + " players)");
                            areaPush(rm);
                        }
                    }
                    System.out.println(svc + ": " + s.name + " 0x08 " + hex(pl));
                }
                break;
            }
            case 0x0F: case 0x10: {
                // 0x0F (game data, ELF 0x1CBBC0 -> 0x1E5008): payload opaque, relayed to the other members of the
                // sender's room. 0x10 (BIN_3 room messages, API 0x1E51B0): {u32 n, n x u32 member id, data}, relayed
                // as is to the listed members (receiver parser 0x1DEC50 reads the same layout). The header tag of a
                // relayed message is the sender's id. [HIPOTESIS: tag = remitente]
                if (s == null) break;
                if (sub) {
                    if (packetDup) return;      // part of a resent packet: already relayed with the first copy
                } else if ((flags & 0x8000) != 0) {
                    boolean dup;
                    synchronized (s) {
                        dup = !s.seenRel.add(seq);
                        if (s.seenRel.size() > 1024) { var it = s.seenRel.iterator(); it.next(); it.remove(); }
                    }
                    if (dup) { packetDup = true; send(from, 0x00, new byte[0], 0x4000, tag, seq); return; }   // client resend: ack again only
                }
                // 0x0F with flag 0x0400 is the room chat (BIN_3 slots 0x574/0x57C = 0x583C20: {u8 1, u8 ?, name[16], ...,
                // u8 len, text}); only plain 0x0F is the game byte stream that waits for the scenario to load
                boolean game = type == 0x0F && (flags & 0x0400) == 0;
                Room gcur = game ? rooms.get(s.cur) : null;
                if (game && (gcur == null || gcur.oid < 0x1000)) {
                    // game data from a session that is in no room (lobby, area): the game it belonged to is gone (room
                    // disbanded under it). Relayed to the area it filled the hold queues of everybody in the lobby
                    // (production 24-25/10: 51 451 "hold queue ... full" in 20 h) and could reach a member with a stale
                    // gameReady there. Acked, not relayed; logged once per session and game.
                    if (!s.strayLogged) {
                        s.strayLogged = true;
                        System.out.println(svc + ": " + s.name + " sends game data outside a game (" + where(s) + "): not relayed");
                    }
                    if ((flags & 0x8000) != 0 && !sub) send(from, 0x00, new byte[0], 0x4000, tag, seq);
                    return;
                }
                if (game) {
                    Room gr0 = rooms.get(s.cur);
                    if (gr0 != null && gr0.started) {
                        long[] c = gr0.gameMsgs.computeIfAbsent(s.name, k -> new long[2]);
                        c[0]++;
                        c[1] += pl.length;
                        if (!gr0.gameRtt.containsKey(s.name)) {   // first sample = the last round trip before the game
                            Integer r0 = rttByAddr.get(from);
                            if (r0 != null) noteRtt(gr0, s.name, r0);
                        }
                    }
                }
                if (game && s.leftGame != 0 && s.leftGame == s.cur) {
                    // left the scenario (REOGW-LEFT): the others already dropped its slot; its stream would only
                    // confuse them (it is on the results screen). Acked, not relayed.
                    if ((flags & 0x8000) != 0 && !sub) send(from, 0x00, new byte[0], 0x4000, tag, seq);
                    return;
                }
                if (game && !s.gameReady) {
                    s.gameReady = true;
                    List<Object[]> q;
                    synchronized (s.held) { q = new ArrayList<>(s.held); s.held.clear(); }
                    for (Object[] h : q) relay(s, (Session) h[0], 0x0F, (byte[]) h[1], (Integer) h[2]);
                    Room gr = rooms.get(s.cur);
                    System.out.println(svc + ": " + s.name + " game ready" + (gr != null && gr.started ? " after "
                            + (System.currentTimeMillis() - gr.startedAt) / 100 / 10.0 + " s" : "") + ", flushed " + q.size());
                }
                List<Session> to = new ArrayList<>();
                if (type == 0x10 && pl.length >= 4) {
                    ByteBuffer q = ByteBuffer.wrap(pl);
                    int n = q.getInt();
                    for (int k = 0; k < n && q.remaining() >= 4; k++) {
                        int id = q.getInt();
                        if (id == 0) { for (Session o : members(s.cur)) if (o != s && !to.contains(o)) to.add(o); continue; }   // [HIPOTESIS] 0 = toda la sala
                        Session o = sessions.get(id);
                        if (o != null && o != s && !to.contains(o)) to.add(o);
                    }
                } else for (Session o : members(s.cur)) if (o != s) to.add(o);
                if (game && !gcur.started && s.playing == gcur.oid) {
                    // a room back to waiting: the scenario stream only to the members still in it, not to the ones (or the
                    // host) already back in the waiting room
                    int rid = gcur.oid;
                    to.removeIf(o -> o.playing != rid);
                }
                if (!game) {
                    // room messages (0x10, chat) only between members in the same phase of a room back to waiting:
                    // the waiting room (BIN_3) and the scenario read them with different handlers, and in game the
                    // lobby ones are stale callbacks (see deferDisband)
                    int rid = s.cur;
                    boolean sp = rid != 0 && s.playing == rid;
                    to.removeIf(o -> {
                        boolean drop = o.cur == rid && (o.playing == rid) != sp;
                        if (drop && logRelays) System.out.println(svc + ": relay " + Integer.toHexString(type) + " " + s.name + " -> " + o.name
                                + " dropped (" + (sp ? "in game -> waiting room" : "waiting room -> in game") + ")");
                        return drop;
                    });
                }
                for (Session o : to) {
                    if (game && !o.gameReady) {
                        // everything waits, in order: the game reads reliable and unreliable 0x0F as one byte stream,
                        // and dropping the unreliable part could lose what ends or skips the opening cutscene for a
                        // member that loads slower (it stays in it). Bare 2-byte keepalives are skipped; unreliable
                        // data is capped at HELD_LOSSY_MAX (the oldest goes first).
                        if ((flags & 0x8000) == 0 && pl.length <= 2) continue;
                        synchronized (o.held) {
                            if ((flags & 0x8000) == 0) {
                                int lossy = 0;
                                for (Object[] h : o.held) if (((Integer) h[2] & 0x8000) == 0) lossy++;
                                if (lossy >= HELD_LOSSY_MAX)
                                    for (var it = o.held.iterator(); it.hasNext(); )
                                        if (((Integer) it.next()[2] & 0x8000) == 0) { it.remove(); break; }
                            }
                            if (o.held.size() < 4096) o.held.add(new Object[] { s, pl, flags & 0x8C00 });
                            else System.out.println(svc + ": hold queue of " + o.name + " full, dropping from " + s.name);
                        }
                        continue;
                    }
                    relay(o, s, type, pl, flags & 0x8C00);
                }
                // only a bare ack (type 0, flag 0x4000, like the client acks): a 0x28 {0x0F, ..} would be handed to
                // the 0x0F slots by the generic reply (case 0x0F, 0x1E14B8) and land in the game data stream
                if ((flags & 0x8000) != 0 && !sub) send(from, 0x00, new byte[0], 0x4000, tag, seq);
                return;
            }
            case 0x02:
                if (s != null) drop(s, "left (0x02)");
                break;
            default:
                break;
        }
        if (s == null) return;   // H3: the generic reply only for sessions
        send(from, 0x28, ByteBuffer.allocate(8).putInt(type).putInt(0).array(), rf, tag, ack);
    }

    private List<Session> members(int oid) {
        List<Session> l = new ArrayList<>();
        if (oid == 0) return l;
        for (Session o : sessions.values()) if (o.cur == oid) l.add(o);
        l.sort((x, y) -> Integer.compare(x.tag, y.tag));
        return l;
    }

    /** Member record (0x0A list, BIN_3 0x588120; type 6 push, parser 0x1DDEF8): name[16], u32 id (shown as
     *  8 hex digits), u32 len, profile[len] (the member's 0x0C payload; 0xF0 bytes copied to +0x3216). */
    private static byte[] member(Session o) {
        byte[] pf = o.profile;
        return ByteBuffer.allocate(0x18 + pf.length).put(java.util.Arrays.copyOf(o.name.getBytes(StandardCharsets.ISO_8859_1), 16))
                .putInt(o.tag).putInt(pf.length).put(pf).array();
    }

    /** 0x24-byte directory entry: name[16], users, ?, flags, max, OID (BIN_3 0x5889F0 / 0x588DF0). */
    private static byte[] entry(String name, int users, int f14, int flags, int max, int oid) {
        return ByteBuffer.allocate(0x24).put(java.util.Arrays.copyOf(name.getBytes(StandardCharsets.ISO_8859_1), 16))
                .putInt(users).putInt(f14).putInt(flags).putInt(max).putInt(oid).array();
    }

    /** Where a session is, for the logs: lobby, waiting room or game (with the room number). */
    private String where(Session s) {
        Room rm = rooms.get(s.cur);
        return rm == null ? "lobby" : (rm.started || s.playing == rm.oid ? "game " : "waiting room ") + Integer.toHexString(rm.oid);
    }

    private void leftRoom(Session s, int oid) throws Exception {
        Room rm = rooms.get(oid);
        if (rm == null) return;
        List<Session> ms = members(oid);
        // not during a scenario: the lobby (BIN_3) is not loaded and the 0x07 push slot is empty; the game crashed
        // jumping to 0 (run k2, frame 31933, right when the host was dropped). The scenario notices on its own.
        // nor to a member still in the scenario of a room back to waiting (its first member back reopened it)
        if (!rm.started) for (Session o : ms) if (o.playing != oid) push(o, 0x07, ByteBuffer.allocate(4).putInt(s.tag).array(), 0);
        if (ms.isEmpty()) { rooms.remove(oid); gameStats(rm, "closed"); System.out.println(svc + ": room " + Integer.toHexString(oid) + " closed"); }
        areaPush(rm);
    }

    private byte[] roomEntry(Room rm) {
        return entry(rm.name, members(rm.oid).size(), 0, rm.entryFlags(), rm.max, rm.oid);
    }

    /** Room list refresh for the players browsing the room's area: push type 4 = the room's 0x24 entry
     *  (BIN_3 0x599F20: updates the slot with that OID or takes a free one), type 5 = {u32 OID} removed
     *  (0x59A040). Closed and started rooms are removed. */
    private void areaPush(Room rm) throws Exception {
        boolean gone = rm.started || rm.hostGone || rm.afterGame || !rooms.containsKey(rm.oid);
        byte[] pl = gone ? ByteBuffer.allocate(4).putInt(rm.oid).array() : roomEntry(rm);
        if (gone) recentGone.put(rm.oid, new long[] { rm.parent, System.currentTimeMillis() });
        if (!listPushes) return;
        for (Session o : sessions.values()) if (o.cur == rm.parent) push(o, gone ? 0x05 : 0x04, pl, 0);
    }

    /** Rooms that left the lists in the last GONE_MS: oid -> {area oid, ms}. The 0x04/0x05 list pushes are not
     *  reliable, so a lost "removed" push left a started room on a client's list (-> "cannot enter room"). */
    private final Map<Integer, long[]> recentGone = new ConcurrentHashMap<>();
    static final long LIST_SYNC_MS = 5000, GONE_MS = 60000;
    /** A join of the room the client entered less than this ago is a repeat of that join (see case 0x06). */
    static final long ENTER_REPEAT_MS = 10000;

    /** Every LIST_SYNC_MS, each client browsing an area gets that area's open rooms again (type 4 updates the slot with
     *  that OID or takes a free one) and the rooms removed lately (type 5). Both are idempotent, so repeating them
     *  only repairs a list that missed a push. Sessions sync at their own pace (no burst). */
    private void resyncLists(long now) {
        recentGone.values().removeIf(g -> now - g[1] > GONE_MS);
        if (!listPushes) return;
        for (Session s : sessions.values()) {
            int area = s.cur;
            if (area <= OID_AREA || area > OID_AREA + areaCount() || now - s.lastListSync < LIST_SYNC_MS) continue;
            s.lastListSync = now;
            try {
                for (Room rm : rooms.values()) if (rm.parent == area && !rm.started && !rm.hostGone && !rm.afterGame) push(s, 0x04, roomEntry(rm), 0);
                for (Map.Entry<Integer, long[]> g : recentGone.entrySet())
                    if (g.getValue()[0] == area && (!rooms.containsKey(g.getKey()) || rooms.get(g.getKey()).started || rooms.get(g.getKey()).afterGame))
                        push(s, 0x05, ByteBuffer.allocate(4).putInt(g.getKey()).array(), 0);
            } catch (Exception ignored) { }
        }
    }

    /** Room title not used by another listed room of the area (case-insensitive): "name", else "name 2".."name 99"
     *  (cut to the 15 characters of the 16-byte field). */
    String uniqueName(int area, String name) {
        java.util.Set<String> used = new java.util.HashSet<>();
        for (Room rm : rooms.values())
            if (rm.parent == area && !rm.hostGone) used.add(rm.name.toLowerCase(java.util.Locale.ROOT));
        if (!used.contains(name.toLowerCase(java.util.Locale.ROOT))) return name;
        for (int i = 2; i < 100; i++) {
            String suf = " " + i;
            String base = name.length() + suf.length() > 15 ? name.substring(0, 15 - suf.length()) : name;
            String c = base + suf;
            if (!used.contains(c.toLowerCase(java.util.Locale.ROOT))) return c;
        }
        return name;
    }

    /** After refusing a join, correct that client's list: the room is gone/started (remove it) or just full (refresh it). */
    private void fixListAfterRefusal(Session s, int oid, Room rm) {
        if (!listPushes) return;
        try {
            boolean gone = rm == null || rm.started || rm.hostGone || rm.afterGame;
            push(s, gone ? 0x05 : 0x04, gone ? ByteBuffer.allocate(4).putInt(oid).array() : roomEntry(rm), 0);
        } catch (Exception ignored) { }
    }

    /** Unsolicited message to a client (request id 0, tag = its session). */
    private void push(Session o, int type, byte[] payload, int flags) throws Exception {
        int save = curReq;
        curReq = 0;
        try { send(o.addr, type, payload, flags, o.tag, 0); } finally { curReq = save; }
    }

    /** One line per game with each member's game data rate: "game 1001 stats after 312 s (back to waiting):
     *  alice 24.1 msg/s 1.9 KB/s, bob 9.8 msg/s 0.7 KB/s". Once per game (the counters are cleared). */
    private void gameStats(Room rm, String why) {
        if (rm.gameMsgs.isEmpty() || rm.startedAt == 0) return;
        double secs = Math.max(1, (System.currentTimeMillis() - rm.startedAt) / 1000.0);
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, long[]> e : rm.gameMsgs.entrySet())
            b.append(b.length() == 0 ? "" : ", ").append(e.getKey().equals(rm.ownerName) ? "*" : "").append(e.getKey()).append(String.format(java.util.Locale.ROOT, " %.1f msg/s %.1f KB/s",
                e.getValue()[0] / secs, e.getValue()[1] / secs / 1024));
        rm.gameMsgs.clear();
        // lag: all game data goes through this relay, so the one-way delay from a to b ~ (rtt_a + rtt_b) / 2 (each
        // member's WebSocket round trip to the gateway; the gateway <-> lobby hop is local). Worst pair = the two
        // largest average round trips. Jitter = mean change between consecutive 15-s samples.
        List<long[]> lag = new ArrayList<>();   // {avg, max, jitter} per member with samples
        for (Map.Entry<String, long[]> e : rm.gameRtt.entrySet()) {
            long[] r = e.getValue();
            synchronized (r) {
                if (r[0] == 0) continue;
                long avg = r[1] / r[0], jit = r[0] > 1 ? r[3] / (r[0] - 1) : 0;
                lag.add(new long[] { avg, r[2], jit });
                b.append(b.length() == 0 ? "" : ", ").append(e.getKey()).append(" rtt ").append(avg).append(" ms (max ").append(r[2])
                    .append(", jitter ").append(jit).append(", ").append(r[0]).append(r[0] == 1 ? " sample)" : " samples)");
            }
        }
        rm.gameRtt.clear();
        long oneway = -1, maxRtt = 0, jitter = 0;
        if (lag.size() >= 2) {
            lag.sort((x, y) -> Long.compare(y[0], x[0]));
            oneway = (lag.get(0)[0] + lag.get(1)[0]) / 2;
            for (long[] l : lag) { maxRtt = Math.max(maxRtt, l[1]); jitter = Math.max(jitter, l[2]); }
            b.append("; one-way est. worst pair ").append(oneway).append(" ms, max jitter ").append(jitter).append(" ms");
        }
        System.out.println(svc + ": game " + Integer.toHexString(rm.oid) + String.format(java.util.Locale.ROOT, " stats after %.0f s (%s): ", secs, why) + b);
        if (oneway >= 0) reo.common.Metrics.gameLag(title == 2 ? "file2-us" : "file1-us", lag.size(), oneway, maxRtt, jitter, (long) secs);
    }

    /** The host leaves a room back to waiting (dropped, 0x07, or a new room) while members are still in its scenario
     *  (Session.playing): no disband for those now. In game the lobby (BIN_3) is not loaded: the 0x0D, the 0x07 push
     *  and the area list pushes reach stale callbacks (runtime "skipped stale netwk callback") and, moved to the
     *  area, the member drops out of the room relay; production 24/10: MarckRE/BlackKnight/Nicolini (File #2, room
     *  115f, host made a new room) and Melvo (File #1, 16d2, host dropped) all lost the game. The room is orphaned
     *  instead: members already in the waiting room get the disband now, the others when they are back there
     *  (orphaned(), from 0x0A). false = nobody in game (the caller disbands as usual). */
    private boolean deferDisband(Room rm, Session host) throws Exception {
        int playing = stillPlaying(rm, host);
        if (playing == 0) return false;
        rm.hostGone = true;
        int waiting = 0;
        for (Session o : members(rm.oid))
            if (o != host && o.playing != rm.oid) {
                o.cur = rm.parent;
                relay(o, host, 0x10, ByteBuffer.allocate(12).putInt(1).putInt(o.tag).put((byte) 0x0D).array(), 0x8000);
                waiting++;
            }
        System.out.println(svc + ": room " + Integer.toHexString(rm.oid) + " lost its host " + host.name + " with " + playing
                + " member(s) still in game: disband deferred for them (" + waiting + " back to the list)");
        return true;
    }

    /** s is in the waiting room of rm again (0x0A, USER/MAXI or STAT, only BIN_3 sends those): no longer playing its
     *  scenario. With rejoinPush the members already back there get the "member entered" push for it. */
    private void backFromGame(Session s, Room rm) {
        if (rm == null || s.playing != rm.oid) { s.playing = 0; return; }
        s.playing = 0;
        int pushed = 0;
        if (rejoinPush)
            for (Session o : members(rm.oid))
                if (o != s && o.playing != rm.oid) {
                    try { push(o, 0x06, member(s), 0); pushed++; } catch (Exception e) { System.out.println(svc + ": push 0x06 " + e); }
                }
        System.out.println(svc + ": " + s.name + " back in the waiting room of " + Integer.toHexString(rm.oid)
                + " (" + stillPlaying(rm, s) + " still in game" + (rejoinPush ? ", entered pushed to " + pushed : "") + ")");
    }

    /** s created the room it is in, and other members are still playing that room's scenario. */
    private boolean hostOfGame(Session s) {
        Room rm = rooms.get(s.cur);
        return rm != null && rm.oid >= 0x1000 && rm.owner == s.tag && stillPlaying(rm, s) > 0;
    }

    /** Members of rm other than s still playing its scenario (see Session.playing). */
    private int stillPlaying(Room rm, Session s) {
        int n = 0;
        for (Session o : members(rm.oid)) if (o != s && o.playing == rm.oid) n++;
        return n;
    }

    /** Close a waiting room for its host: members get the host's "disband" (0x10 class 0x0D) and go back to the area. */
    private void disband(Room rm, Session host) throws Exception {
        if (rooms.remove(rm.oid) == null) return;
        gameStats(rm, "disbanded");
        List<Session> ms = new ArrayList<>();
        for (Session o : members(rm.oid)) if (o != host) ms.add(o);
        for (Session o : ms) {
            o.cur = rm.parent;
            if (!rm.started) relay(o, host, 0x10, ByteBuffer.allocate(12).putInt(1).putInt(o.tag).put((byte) 0x0D).array(), 0x8000);
        }
        if (host.cur == rm.oid) host.cur = rm.parent;
        System.out.println(svc + ": room " + Integer.toHexString(rm.oid) + " disbanded (" + ms.size() + " members back to the list)");
        areaPush(rm);
    }

    /** A message with this session's tag came from another address (reconnected WebSocket): send our pending
     *  reliable messages there too (a liveness ping if nothing is pending), at most twice a second; tick() keeps
     *  resending to both addresses. The ack that comes back from the new address moves the session (H2). */
    private void offer(Session s, SocketAddress from) throws Exception {
        long now = System.currentTimeMillis();
        if (from.equals(s.alt) && now - s.altSent < 500) return;
        if (!from.equals(s.alt)) System.out.println(svc + ": " + s.name + " reconnecting from " + from + " (" + where(s) + ")");
        s.alt = from;
        s.altSent = now;
        boolean none;
        synchronized (s) { none = s.unacked.isEmpty(); }
        if (none) { s.lastPing = now; pushReliable(s, 0x28, ByteBuffer.allocate(8).putInt(0x14).putInt(0).array(), 0x1000); }
        List<byte[]> ds = new ArrayList<>();
        synchronized (s) { for (Object[] u : s.unacked.values()) ds.add((byte[]) u[0]); }
        for (byte[] d : ds) sock.send(new DatagramPacket(d, d.length, from));
    }

    /** Unsolicited reliable message: our ordered seq for this client, kept until acked (resent by tick). */
    private void pushReliable(Session o, int type, byte[] payload, int flags) throws Exception {
        int save = curReq;
        curReq = 0;
        try {
            int rs;
            synchronized (o) { rs = o.relSeq++; }
            byte[] d = sendSeq(o.addr, type, payload, flags | 0x8000, o.tag, 0, rs);
            synchronized (o) { o.unacked.put(rs, new Object[] { d, System.currentTimeMillis(), 0 }); }
        } finally { curReq = save; }
    }

    /** Message from another member: header tag = the sender's id. Reliable relays (0x8000) would need our own
     *  ordered sequence per receiver (Session.relSeq). BIN_3 handles 0x10 room messages only when reliable
     *  (slot 0x594 = 0x583440; the unreliable slot 0x59C is another handler). */
    private void relay(Session to, Session from, int type, byte[] payload, int flags) throws Exception {
        relay(to, from.tag, from.name, type, payload, flags);
    }

    /** Same, with the sender's tag only (a member that is already gone, see orphaned()). */
    private void relay(Session to, int fromTag, String fromName, int type, byte[] payload, int flags) throws Exception {
        int save = curReq;
        curReq = 0;
        try {
            if ((flags & 0x8000) != 0) {
                int rs;
                synchronized (to) { rs = to.relSeq++; }
                byte[] d = sendSeq(to.addr, type, payload, flags, fromTag, 0, rs);
                synchronized (to) { to.unacked.put(rs, new Object[] { d, System.currentTimeMillis(), 0 }); }
            } else send(to.addr, type, payload, flags, fromTag, 0);
        } finally { curReq = save; }
        trace(to, true, type, flags | 0x10000, payload.length);
        if (logRelays) System.out.println(svc + ": relay " + Integer.toHexString(type) + " " + fromName + " -> " + to.name + " " + payload.length + " bytes");
    }

    /** Types with their own case in message(), plus the ones the generic 0x28 is known to be right for: 0x00 (ack),
     *  0x0D (lobby join, 6.5), 0x0E, 0x11 (result at the end of a scenario, 9), 0x14 (presence). Others are logged once
     *  per session ("unhandled type"): a scenario event that waits for a reply we do not give would show up there. */
    static final java.util.Set<Integer> HANDLED = java.util.Set.of(0x00, 0x01, 0x02, 0x04, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C,
            0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x14, 0x25, 0x2C, 0x48, 0x49);

    /** One trace entry: ms (low 32 bits) | out (bit 32) | relayed (33) | type (7 bits at 34) | flags >> 10 (6 bits at 41)
     *  | length (10 bits at 47). */
    private static void trace(Session s, boolean out, int type, int flags, int len) {
        long e = (System.currentTimeMillis() & 0xFFFFFFFFL) | (out ? 1L << 32 : 0) | ((flags & 0x10000) != 0 ? 1L << 33 : 0)
                | (long) (type & 0x7F) << 34 | (long) ((flags >> 10) & 0x3F) << 41 | (long) Math.min(len, 0x3FF) << 47;
        synchronized (s.trace) { s.trace[s.traceN++ % TRACE_LEN] = e; }
    }

    /** "-1.2s<0f/8000+34 ..." oldest first: seconds before now, < from the client, > to it (>r = relayed from another
     *  member), type/flags, payload length. */
    static String traceDump(Session s) {
        StringBuilder b = new StringBuilder();
        long now = System.currentTimeMillis() & 0xFFFFFFFFL;
        synchronized (s.trace) {
            int n = Math.min(s.traceN, TRACE_LEN);
            for (int i = s.traceN - n; i < s.traceN; i++) {
                long e = s.trace[i % TRACE_LEN];
                if (b.length() > 0) b.append(' ');
                b.append(String.format(java.util.Locale.ROOT, "%.1fs%s%s%02x/%04x+%d", ((e & 0xFFFFFFFFL) - now) / 1000.0,
                        (e >> 32 & 1) != 0 ? ">" : "<", (e >> 33 & 1) != 0 ? "r" : "", e >> 34 & 0x7F, (e >> 41 & 0x3F) << 10, e >> 47 & 0x3FF));
            }
        }
        return b.length() == 0 ? "(none)" : b.toString();
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    private void login(SocketAddress from, byte[] pl) throws Exception {
        if (pl.length < 0x70) return;
        String name = cstr(pl, 0, 0x28);
        byte[] r = new byte[0x3C + 16];
        byte[] nb = name.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(nb, 0, r, 0, Math.min(nb.length, 0x27));
        ByteBuffer b = ByteBuffer.wrap(r);
        b.position(0x28);
        b.put(publicIp).putInt(publicPort).putShort((short) 0).putShort((short) publicPort).putInt(16).putInt(0x2A);
        byte[] ticket = new byte[16];
        rnd.nextBytes(ticket);
        b.put(ticket);
        byte[] key = keyFor(name);
        String ip = from instanceof InetSocketAddress isa ? isa.getAddress().getHostAddress() : null;
        reo.common.ApiGuard g = reo.common.ApiGuard.instance;
        if (key != null && g != null && g.isBanned(name, ip)) {
            System.out.println(svc + ": login " + safe(name) + " refused (banned)");
            key = null;
        }
        if (key != null) tickets.put(hex(ticket), new Object[] { name, System.currentTimeMillis() + TICKET_MS });
        if (key == null) {
            // unknown name and no dev password: answer with a random key, so the client fails to decrypt
            // and shows "Incorrect password" (result 0x27)
            key = new byte[16];
            new java.security.SecureRandom().nextBytes(key);
            System.out.println(svc + ": login " + safe(name) + " (not registered or refused)");
        } else System.out.println(svc + ": login " + safe(name));
        send(from, 0x2D, blowfish(r, true, key), 0x1000, 0, 0);
    }

    private void query(SocketAddress from, byte[] pl, int rf, int tag, int ack) throws Exception {
        ByteBuffer q = ByteBuffer.wrap(pl);
        int max = q.getInt(), n = q.get() & 0xFF;
        List<String[]> conds = new ArrayList<>();
        for (int k = 0; k < n && 8 + 21 * (k + 1) <= pl.length; k++) {
            int o = 8 + 21 * k;
            conds.add(new String[] { new String(pl, o, 4, StandardCharsets.ISO_8859_1), String.valueOf((char) pl[o + 4]), cstr(pl, o + 5, 16) });
        }
        List<Obj> hits = new ArrayList<>();
        if (n >= 1 && pl.length >= 17 && "OID".equals(cstr(pl, 8, 4))) {
            // condition "OID", op, u32 OID, u32 (API 0x1E677C kind 1: two BE words; MOVE after a search, BIN_3 0x58ADD8)
            int oid = q.getInt(13);
            for (Obj ob : objects().values()) if (ob.oid() == oid) { hits.add(ob); break; }
            conds.clear();
            conds.add(new String[] { "", "X", "" });   // matches nothing below
        }
        for (Obj ob : objects().values()) {
            boolean ok = true;
            for (String[] c : conds) {
                int cmp = ob.name().compareTo(c[2]);
                ok &= switch (c[1]) {
                    case "A" -> cmp == 0; case "B" -> cmp != 0; case "C" -> cmp > 0;
                    case "D" -> cmp >= 0; case "E" -> cmp < 0; case "F" -> cmp <= 0; default -> false;
                };
            }
            if (ok && hits.size() < Math.min(max, 27)) hits.add(ob);
        }
        ByteBuffer r = ByteBuffer.allocate(12 + 0x24 * hits.size()).putInt(0x48).putInt(0).putInt(hits.size());
        for (Obj ob : hits) {
            byte[] nm = java.util.Arrays.copyOf(ob.name().getBytes(StandardCharsets.ISO_8859_1), 16);
            r.put(nm).putInt(users(ob.oid())).putInt(ob.group()).putInt(ob.max()).putInt(ob.f1c()).putInt(ob.oid());
        }
        send(from, 0x48, r.array(), rf, tag, ack);
    }

    /** STAT of a room for the File #2 list filter (0x49): the 0x04 flags (0x00080000 = listed), any 0x08 "STAT" bits,
     *  0x40000000 once started. */
    private static int roomStat(Room rm) { return rm.entryFlags() | rm.stat | (rm.started ? 0x40000000 : 0); }

    private int areaOf(int oid) {
        Room rm = rooms.get(oid);
        if (rm != null) oid = rm.parent;
        return oid > OID_AREA && oid <= OID_AREA + areaCount() ? oid - OID_AREA : 0;
    }

    // ---- HTTP API --------------------------------------------------------------------------

    private final java.util.concurrent.ConcurrentLinkedQueue<String> kicks = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Moderation: drop the sessions of this name at the next tick (a banned name's login is refused). */
    public void kick(String name) { kicks.add(name); }

    /** Moderation (support): forget the account of a name so another device can register it (the credential lives
     *  only in the browser that created it). The sessions with that name are closed. Returns false if unknown. */
    public static boolean releaseName(String name) throws Exception {
        boolean had;
        synchronized (accounts) {
            had = accounts.remove(name.toLowerCase(java.util.Locale.ROOT)) != null;
            if (had) saveAccounts();
        }
        if (had) { System.out.println("us-kk: released name " + name); reo.common.Social.forget(name); }
        for (KkService kk : instances()) kk.kick(name);
        return had;
    }

    /** Moderation panel: connected names with game, area, room and connection time (no addresses). */
    public List<Map<String, Object>> players() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Session s : sessions.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name);
            m.put("game", title == 2 ? "file2-us" : "file1-us");
            int a = areaOf(s.cur);
            m.put("area", a == 0 ? null : areaName(a));
            Room rm = rooms.get(s.cur);
            m.put("room", rm == null ? null : rm.name);
            m.put("in_game", rm != null && rm.started);
            m.put("since", s.since);
            out.add(m);
        }
        return out;
    }

    /** Snapshot for /api/rooms (no passwords, only the public account names). */
    public Map<String, Object> snapshot() {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("id", title == 2 ? "file2-us" : "file1-us");
        g.put("title", title == 2 ? "Resident Evil Outbreak File #2 (US, SLUS-20984)" : "Resident Evil Outbreak (US, SLUS-20765)");
        g.put("protocol", "us-kk");
        g.put("lobby_port", port);
        g.put("ready", true);
        List<String> lobby = new ArrayList<>();
        Map<Integer, List<String>> byArea = new TreeMap<>();
        for (Session s : sessions.values()) {
            int a = areaOf(s.cur);
            if (a == 0) lobby.add(s.name);
            else byArea.computeIfAbsent(a, k -> new ArrayList<>()).add(s.name);
        }

        int inRooms = 0;
        for (Session s : sessions.values()) if (rooms.containsKey(s.cur)) inRooms++;
        g.put("players_lobby", sessions.size() - inRooms);   // connected, not in a room
        g.put("players_in_game", inRooms);
        g.put("lobby", lobby);
        List<Object> al = new ArrayList<>();
        for (int a = 1; a <= areaCount(); a++) {
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("id", a);
            am.put("name", areaName(a));
            am.put("object", String.format("%s%02d", areaPrefix, a));
            am.put("players", byArea.getOrDefault(a, List.of()));
            List<Object> rl = new ArrayList<>();
            for (Room rm : rooms.values()) {
                if (rm.parent != OID_AREA + a) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rm.oid);
                m.put("name", reo.common.ChatFilter.maskTitle(rm.name));   // owner's word list, page only (the game keeps its bytes)
                m.put("title", reo.common.ChatFilter.maskTitle(rm.name));
                m.put("max", rm.max);
                m.put("private", !rm.password.isEmpty());
                m.put("state", rm.started ? "in_game" : "waiting");
                m.put("scenario", (rm.flags >> 1) & 0xFF);   // low byte of the 0x04 flags >> 1 (protocol.md 6.8)
                List<String> pn = new ArrayList<>();
                for (Session o : members(rm.oid)) pn.add(o.name);
                m.put("players", pn);
                rl.add(m);
            }
            am.put("rooms", rl);
            al.add(am);
        }
        g.put("areas", al);
        g.put("retransmits", retransmits);
        g.put("drops", drops);
        return g;
    }
}
