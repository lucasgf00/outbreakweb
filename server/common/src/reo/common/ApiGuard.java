/*
    reo-web bioserver - fan server for Resident Evil Outbreak File #1/#2
    (C) 2026 reo-web contributors. AGPL-3.0-or-later, see LICENSE.txt.
*/

package reo.common;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Abuse protection shared by the HTTP API, the website chat and (later) the kk lobby:
 *
 *  - client address: the TCP peer, or the rightmost X-Forwarded-For entry (api.real_ip_header) when
 *    the peer is loopback / api.trusted_proxies (the page server or nginx on the same machine);
 *  - rate limits: global (api.rate_global req/s) and per address (api.rate_per_ip req/s), token
 *    buckets with one second of burst, plus named buckets for registrations and chat;
 *  - body size: api.max_body bytes (413 above it);
 *  - security headers on every API answer;
 *  - player names: charset [A-Za-z0-9]{1,15} and a basic blocklist (impersonation of staff, slurs),
 *    after folding case and digit look-alikes ("Adm1n" = "admin"); extra words, one per line, in
 *    api.blocked_names_file (default data/blocked-names.txt);
 *  - moderation: bans and mutes of a name or an address, with expiry, kept in
 *    api.moderation_file (default data/moderation.properties). Driven by POST /api/admin (HttpApi).
 */
public final class ApiGuard {
    public static volatile ApiGuard instance;

    private final Set<String> trustedProxies = new HashSet<>();
    private final String realIpHeader;
    public final int maxBody;
    private final Bucket global;
    private final double perIpRate;
    private final Map<String, Bucket> perIp = new ConcurrentHashMap<>();
    private final Map<String, Bucket> named = new ConcurrentHashMap<>();
    private final Path moderationFile, blockedFile;
    private final Properties moderation = new Properties();
    // owner's list of names that pass for staff, the owner or a developer (api.blocked_names_file, one per line, # for
    // comments; a name containing one is refused): read again when the file changes, no restart needed
    private volatile List<String> extraBlocked = List.of();
    private volatile long blockedStamp = -1;
    private long lastSweep = System.currentTimeMillis();

    public ApiGuard(ServerConfig cfg) {
        for (String p : cfg.getList("api.trusted_proxies", "127.0.0.1,::1,0:0:0:0:0:0:0:1")) trustedProxies.add(p.trim());
        realIpHeader = cfg.get("api.real_ip_header", "X-Forwarded-For");
        maxBody = cfg.getInt("api.max_body", 4096);
        global = new Bucket(cfg.getInt("api.rate_global", 200), cfg.getInt("api.rate_global", 200));
        perIpRate = cfg.getInt("api.rate_per_ip", 20);
        moderationFile = Path.of(cfg.get("api.moderation_file", "data/moderation.properties"));
        blockedFile = Path.of(cfg.get("api.blocked_names_file", "data/blocked-names.txt"));
        try {
            if (Files.exists(moderationFile)) try (InputStream in = Files.newInputStream(moderationFile)) { moderation.load(in); }
        } catch (IOException e) {
            System.out.println("api: moderation/blocklist: " + e);
        }
        reloadBlocked();
        instance = this;
    }

    private synchronized void reloadBlocked() {
        try {
            long stamp = Files.exists(blockedFile) ? Files.getLastModifiedTime(blockedFile).toMillis() : 0;
            if (stamp == blockedStamp) return;
            List<String> fresh = new ArrayList<>();
            if (stamp != 0)
                for (String l : Files.readAllLines(blockedFile, StandardCharsets.UTF_8)) {
                    String w = fold(l.trim());
                    if (!w.isEmpty() && !l.trim().startsWith("#")) fresh.add(w);
                }
            extraBlocked = List.copyOf(fresh);
            blockedStamp = stamp;
            System.out.println("api: blocked names: " + fresh.size());
        } catch (IOException e) {
            System.out.println("api: blocked names: " + e);
        }
    }

    // ---- token bucket ------------------------------------------------------------------------

    /** rate tokens per second, burst tokens at most. */
    public static final class Bucket {
        final double rate, burst;
        double tokens;
        long last = System.nanoTime();
        public Bucket(double rate, double burst) { this.rate = rate; this.burst = burst; this.tokens = burst; }
        public synchronized boolean take() {
            if (rate <= 0) return true;
            long now = System.nanoTime();
            tokens = Math.min(burst, tokens + (now - last) / 1e9 * rate);
            last = now;
            if (tokens < 1) return false;
            tokens -= 1;
            return true;
        }
        synchronized boolean full() {
            return tokens + (System.nanoTime() - last) / 1e9 * rate >= burst;
        }
    }

    /** A named limit: e.g. take("chat-ip:" + ip, 1.0/3, 5) = one per 3 s, bursts of 5. */
    public boolean take(String key, double rate, double burst) {
        sweep();
        return named.computeIfAbsent(key, k -> new Bucket(rate, burst)).take();
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        if (now - lastSweep < 60_000) return;
        lastSweep = now;
        perIp.values().removeIf(Bucket::full);
        named.values().removeIf(Bucket::full);
        expireModeration(now);
    }

    // ---- request gate ------------------------------------------------------------------------

    public String clientIp(HttpExchange ex) {
        InetAddress peer = ex.getRemoteAddress().getAddress();
        String ip = peer.getHostAddress();
        if (!trustedProxies.contains(ip) && !(peer.isLoopbackAddress() && trustedProxies.contains("127.0.0.1"))) return ip;
        String h = ex.getRequestHeaders().getFirst(realIpHeader);
        if (h == null || h.isBlank()) return ip;
        String[] parts = h.split(",");
        for (int i = parts.length - 1; i >= 0; i--) {   // rightmost entry not added by one of our proxies
            String p = parts[i].trim();
            if (!p.matches("[0-9A-Fa-f.:]{2,45}")) return ip;
            if (!trustedProxies.contains(p) || i == 0) return p;
        }
        return ip;
    }

    /** Rate limit + body size; on refusal it answers (429/413) and returns false. */
    public boolean admit(HttpExchange ex) throws IOException {
        sweep();
        String ip = clientIp(ex);
        if (!global.take() || !perIp.computeIfAbsent(ip, k -> new Bucket(perIpRate, perIpRate * 2)).take()) {
            ex.getResponseHeaders().set("Retry-After", "1");
            reply(ex, 429, error("rate limited"));
            return false;
        }
        String cl = ex.getRequestHeaders().getFirst("Content-Length");
        if (cl != null) {
            long n;
            try { n = Long.parseLong(cl.trim()); } catch (NumberFormatException e) { n = Long.MAX_VALUE; }
            if (n > limitFor(ex)) { reply(ex, 413, error("body too large")); return false; }
        }
        if (ex.getRequestHeaders().containsKey("Transfer-Encoding") && cl == null && !"GET".equals(ex.getRequestMethod())) {
            reply(ex, 411, error("length required"));
            return false;
        }
        return true;
    }

    /** Bug reports carry technical data and may be larger; every other endpoint keeps maxBody. */
    private int limitFor(HttpExchange ex) {
        String p = ex.getRequestURI().getPath();
        return "/api/report".equals(p) || "/api/social/report".equals(p) ? Math.max(maxBody, 16384) : maxBody;
    }

    /** At most maxBody bytes of the request body (16 KiB for /api/report). */
    public String body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) { return new String(in.readNBytes(limitFor(ex)), StandardCharsets.UTF_8); }
    }

    public static void securityHeaders(Headers h) {
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        h.set("Cross-Origin-Resource-Policy", "same-site");
        h.set("Cache-Control", "no-store");
    }

    public static Map<String, Object> error(String e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", e);
        return m;
    }

    public static void reply(HttpExchange ex, int code, Object body) throws IOException {
        byte[] b = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        securityHeaders(ex.getResponseHeaders());
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    // ---- names -------------------------------------------------------------------------------

    /** Staff / service impersonation: refused when the folded name contains them. */
    static final String[] RESERVED = { "admin", "administrator", "moderator", "capcom", "staff", "official",
        "support", "system", "server", "reoweb", "outbreakweb", "owner", "developer", "sysop", "webmaster" };
    /** Refused when the folded name equals them. */
    static final String[] RESERVED_EXACT = { "mod", "mods", "gm", "root", "dev", "null", "undefined", "anonymous", "guest" };
    /** Slurs and obvious insults (substring match after folding). Short on purpose: extend it with
     *  api.blocked_names_file rather than here; substring matching of short words hits real names. */
    static final String[] OFFENSIVE = { "nigger", "nigga", "faggot", "fagot", "retard", "hitler", "nazi", "kkk",
        "putamadre", "hijodeputa", "maricon", "sudaca", "pedofil", "pedophil", "violad", "whore", "pendej",
        "culiao", "mierda", "chupala", "fuck", "motherf" };
    /** Short words that are also parts of normal words ("computadora", "grapes"): whole name / word only. */
    static final String[] OFFENSIVE_WORD = { "puta", "puto", "rape", "cunt", "shit", "slut", "verga", "pija", "pete",
        "concha", "culo", "fag", "nig", "cum", "dick", "cock", "pussy", "bitch", "orto", "trolo" };

    static boolean offensiveWord(String f) {
        String sq = f.replaceAll("(.)\\1+", "$1");
        for (String w : OFFENSIVE) if (f.contains(w) || sq.contains(w)) return true;
        for (String w : OFFENSIVE_WORD)
            if (f.equals(w) || sq.equals(w) || f.equals(w + "s") || f.equals(w + "o") || f.equals(w + "a")) return true;
        return false;
    }

    /** Lower case, digit and symbol look-alikes to letters, everything else out. */
    static String fold(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            switch (c) {
                case '0': b.append('o'); break;
                case '1': case '!': case '|': b.append('i'); break;
                case '3': b.append('e'); break;
                case '4': case '@': b.append('a'); break;
                case '5': case '$': b.append('s'); break;
                case '7': b.append('t'); break;
                case '8': b.append('b'); break;
                case '9': b.append('g'); break;
                default: if (c >= 'a' && c <= 'z') b.append(c);
            }
        }
        return b.toString();
    }

    /** null if the name may be registered, else the reason. */
    public String checkName(String name) {
        reloadBlocked();
        if (name == null || !name.matches("[A-Za-z0-9]{1,15}")) return "bad name";
        String f = fold(name);
        // also with repeated letters squeezed ("aaadmin")
        String sq = f.replaceAll("(.)\\1+", "$1");
        for (String w : RESERVED) if (f.contains(w) || sq.contains(w)) return "name not allowed";
        String bare = fold(name.replaceAll("[0-9]+$", ""));
        for (String w : RESERVED_EXACT) if (f.equals(w) || sq.equals(w) || bare.equals(w)) return "name not allowed";
        if (offensiveWord(f) || offensiveWord(fold(name.replaceAll("[0-9]+$", "")))) return "name not allowed";
        for (String w : extraBlocked) if (f.contains(w) || sq.contains(w)) return "name not allowed";
        ChatFilter cf = ChatFilter.instance;   // owner's word list (data/badwords.txt), reloaded on change
        if (cf != null && cf.nameHasBadword(name)) { cf.count("name_refused"); return "name not allowed"; }
        if (isBanned(name, null)) return "name not allowed";
        return null;
    }

    /** Chat text with blocklisted words masked (whole words only, folded the same way). */
    public static String maskText(String text) {
        StringBuilder out = new StringBuilder();
        for (String tok : text.split("(?<= )|(?= )")) {
            String f = fold(tok);
            boolean bad = false;
            if (f.length() >= 3) bad = offensiveWord(f);
            out.append(bad ? "*".repeat(Math.min(tok.length(), 8)) : tok);
        }
        return out.toString();
    }

    // ---- moderation --------------------------------------------------------------------------

    private static String key(String kind, String what, String target) {
        return kind + "." + what + "." + target.toLowerCase(Locale.ROOT);
    }

    private synchronized boolean active(String k) {
        String v = moderation.getProperty(k);
        if (v == null) return false;
        long until;
        try { until = Long.parseLong(v.split("\\|", 2)[0]); } catch (NumberFormatException e) { return true; }
        return until == 0 || until > System.currentTimeMillis();
    }

    public boolean isBanned(String name, String ip) {
        return (name != null && active(key("ban", "name", name))) || (ip != null && active(key("ban", "ip", ip)));
    }

    public boolean isMuted(String name, String ip) {
        return isBanned(name, ip) || (name != null && active(key("mute", "name", name))) || (ip != null && active(key("mute", "ip", ip)));
    }

    /** kind = ban|mute, what = name|ip, minutes 0 = permanent. */
    public void set(String kind, String what, String target, long minutes, String reason) throws IOException {
        set(kind, what, target, minutes, reason, "");
    }

    /** Same, with who did it (the admin panel's basic-auth user). Value: until|since|reason|by. */
    public synchronized void set(String kind, String what, String target, long minutes, String reason, String by) throws IOException {
        long until = minutes <= 0 ? 0 : System.currentTimeMillis() + minutes * 60_000L;
        String r = reason == null ? "" : reason.replaceAll("[\\r\\n|]", " ");
        String b = by == null ? "" : by.replaceAll("[^A-Za-z0-9_.@-]", "");
        moderation.setProperty(key(kind, what, target), until + "|" + System.currentTimeMillis() + "|" + r + "|" + b);
        save();
    }

    /** -1 if neither the name nor the address has this sanction; else its end in ms (0 = permanent). */
    public synchronized long sanctionUntil(String kind, String name, String ip) {
        long best = -1;
        for (String k : new String[] { name == null ? null : key(kind, "name", name), ip == null ? null : key(kind, "ip", ip) }) {
            if (k == null || !active(k)) continue;
            long u;
            try { u = Long.parseLong(moderation.getProperty(k).split("\\|", 2)[0]); } catch (NumberFormatException e) { u = 0; }
            if (u == 0) return 0;
            best = Math.max(best, u);
        }
        return best;
    }

    // ---- opaque address references: the admin panel never sees an address -----------------------

    private volatile byte[] refKey = new java.security.SecureRandom().generateSeed(32);
    private final Map<String, String> refs = new ConcurrentHashMap<>();   // ref -> address

    /** A stable secret for the references (derived from the admin token), so they survive restarts. */
    public void setRefKey(String secret) {
        if (secret != null && !secret.isEmpty()) refKey = ("reo-ipref:" + secret).getBytes(StandardCharsets.UTF_8);
    }

    /** 12 hex characters of HMAC-SHA256(address); the address is remembered so the server can resolve it. */
    public String ipRef(String ip) {
        if (ip == null) return null;
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(refKey, "HmacSHA256"));
            byte[] h = mac.doFinal(ip.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 6; i++) b.append(String.format("%02x", h[i]));
            String r = b.toString();
            if (refs.size() > 50_000) refs.clear();
            refs.put(r, ip);
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    /** The address behind a reference handed out before (or behind a moderation entry), or null. */
    public String resolveRef(String ref) {
        if (ref == null || !ref.matches("[0-9a-f]{12}")) return null;
        String ip = refs.get(ref);
        if (ip != null) return ip;
        for (String k : list().keySet()) {
            String[] p = k.split("\\.", 3);
            if (p.length == 3 && p[1].equals("ip") && ref.equals(ipRef(p[2]))) return p[2];
        }
        return null;
    }

    /** "203.0.x.x" / "2001:db8:...": enough to tell two entries apart, not to identify anyone. */
    public static String maskIp(String ip) {
        if (ip == null) return "";
        if (ip.contains(":")) { String[] p = ip.split(":"); return (p.length > 1 ? p[0] + ":" + p[1] : p[0]) + ":..."; }
        String[] p = ip.split("\\.");
        return p.length == 4 ? p[0] + "." + p[1] + ".x.x" : "x";
    }

    /** Active sanctions for the panel: kind, name or masked address + ref, reason, by, since, until. */
    public synchronized List<Map<String, Object>> sanctions() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String k : moderation.stringPropertyNames()) {
            if (!active(k)) continue;
            String[] p = k.split("\\.", 3), v = moderation.getProperty(k).split("\\|", 4);
            if (p.length != 3) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", p[0]);
            m.put("what", p[1]);
            if (p[1].equals("ip")) { m.put("ip_ref", ipRef(p[2])); m.put("ip_masked", maskIp(p[2])); }
            else m.put("name", p[2]);
            long until = 0, since = 0;
            try { until = Long.parseLong(v[0]); since = v.length > 1 ? Long.parseLong(v[1]) : 0; } catch (NumberFormatException ignored) { }
            m.put("until", until);
            m.put("since", since);
            m.put("reason", v.length > 2 ? v[2] : "");
            m.put("by", v.length > 3 ? v[3] : "");
            out.add(m);
        }
        out.sort((a, b) -> Long.compare((Long) b.get("since"), (Long) a.get("since")));
        return out;
    }

    // ---- moderation log (who did what, when): JSON lines, append only, 0600 ---------------------

    private Path logFile() { return moderationFile.resolveSibling("moderation-log.jsonl"); }

    public synchronized void logAction(Map<String, Object> entry) {
        Path f = logFile();
        try {
            if (f.getParent() != null) Files.createDirectories(f.getParent());
            boolean fresh = !Files.exists(f);
            Files.writeString(f, Json.write(entry) + "\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            if (fresh) privateFile(f);
        } catch (Exception e) {
            System.out.println("api: moderation log: " + e);
        }
    }

    /** The last n entries of the log, newest first (raw JSON lines). */
    public synchronized List<String> logTail(int n) {
        Path f = logFile();
        try {
            if (!Files.exists(f)) return List.of();
            List<String> all = Files.readAllLines(f, StandardCharsets.UTF_8);
            List<String> out = new ArrayList<>();
            for (int i = all.size() - 1; i >= 0 && out.size() < n; i--) if (all.get(i).startsWith("{")) out.add(all.get(i));
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }

    private static void privateFile(Path f) {
        try { Files.setPosixFilePermissions(f, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
        catch (Exception ignored) { }
    }

    public synchronized boolean clear(String kind, String what, String target) throws IOException {
        boolean had = moderation.remove(key(kind, what, target)) != null;
        if (had) save();
        return had;
    }

    public synchronized Map<String, String> list() {
        Map<String, String> m = new TreeMap<>();
        for (String k : moderation.stringPropertyNames()) m.put(k, moderation.getProperty(k));
        return m;
    }

    private synchronized void expireModeration(long now) {
        boolean changed = false;
        for (String k : moderation.stringPropertyNames()) if (!active(k)) { moderation.remove(k); changed = true; }
        if (changed) try { save(); } catch (IOException ignored) { }
    }

    private void save() throws IOException {
        if (moderationFile.getParent() != null) Files.createDirectories(moderationFile.getParent());
        Path tmp = moderationFile.resolveSibling(moderationFile.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) { moderation.store(out, "reo-web moderation: kind.what.target=until|since|reason|by"); }
        privateFile(tmp);
        Files.move(tmp, moderationFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Constant-time comparison of two secrets. */
    public static boolean secretEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
