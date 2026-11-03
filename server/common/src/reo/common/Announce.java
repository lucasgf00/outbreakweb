/*
    reo-web bioserver - fan server for Resident Evil Outbreak File #1/#2
    (C) 2026 reo-web contributors. AGPL-3.0-or-later, see LICENSE.txt.
*/

package reo.common;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Admin notice shown by the website (one at a time, in memory, persisted to api.announce_file,
 * default data/announce.properties, so it survives a restart).
 *
 *   GET  /api/announce         public: {"text","until","id"}  (text "" / until 0 when none is active)
 *   POST /api/admin/announce   Bearer admin token: {"text":"..","minutes":30} | {"clear":true} | {"text":""}
 *                              -> {"ok":true,"until":<epoch ms>}
 *
 * The GET is lock-free: a volatile immutable snapshot with the JSON pre-serialized.
 */
public final class Announce {
    private static final class State {
        final String text; final long until; final int id; final String discord; final byte[] json;
        State(String text, long until, int id, String discord) {
            this.text = text; this.until = until; this.id = id; this.discord = discord;
            this.json = body(text, until, id, discord);
        }
    }

    static final int MAX_TEXT = 300;
    private final ApiGuard guard;
    private final String adminToken;
    private final String cors;
    private final Path file;
    private volatile State st;
    private final byte[][] emptyCache = new byte[1][];
    private volatile int lastEmptyId = -1;

    public Announce(ServerConfig cfg, ApiGuard guard, String adminToken) {
        this.guard = guard;
        this.adminToken = adminToken;
        this.cors = cfg.get("api.cors_origin", "*");
        this.file = Path.of(cfg.get("api.announce_file", "data/announce.properties"));
        State s = new State("", 0, 0, "");
        try {
            if (Files.isRegularFile(file)) {
                java.util.Properties p = new java.util.Properties();
                try (var in = Files.newInputStream(file)) { p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8)); }
                int id = Integer.parseInt(p.getProperty("id", "0"));
                long until = Long.parseLong(p.getProperty("until", "0"));
                s = new State(clean(p.getProperty("text", "")), until, id, cleanDiscord(p.getProperty("discord", "")));
            }
        } catch (Exception e) {
            System.out.println("announce: cannot read " + file + ": " + e);
        }
        st = s;
    }

    static byte[] body(String text, long until, int id, String discord) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", text);
        m.put("until", until);
        m.put("id", id);
        if (!discord.isEmpty()) m.put("discord", discord);
        return Json.write(m).getBytes(StandardCharsets.UTF_8);
    }

    private static final Pattern DISCORD = Pattern.compile("^https://(discord\\.gg|discord\\.com|www\\.discord\\.com|discordapp\\.com)/[A-Za-z0-9_./-]{1,100}$");

    static String cleanDiscord(String s) {
        s = s == null ? "" : s.trim();
        return DISCORD.matcher(s).matches() ? s : "";
    }

    /** No control characters (they become spaces), trimmed, at most MAX_TEXT chars. */
    static String clean(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(Character.isISOControl(c) || c == '\u2028' || c == '\u2029' ? ' ' : c);
        }
        String r = b.toString().trim();
        if (r.length() > MAX_TEXT) r = r.substring(0, MAX_TEXT);
        if (!r.isEmpty() && Character.isHighSurrogate(r.charAt(r.length() - 1))) r = r.substring(0, r.length() - 1);
        return r;
    }

    private void out(HttpExchange ex, int code, byte[] b) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ApiGuard.securityHeaders(ex.getResponseHeaders());   // includes Cache-Control: no-store
        if (!cors.isEmpty()) ex.getResponseHeaders().set("Access-Control-Allow-Origin", cors);
        boolean head = "HEAD".equals(ex.getRequestMethod());
        ex.sendResponseHeaders(code, head ? -1 : b.length);
        if (!head) try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private void err(HttpExchange ex, int code, String e) throws IOException {
        out(ex, code, Json.write(ApiGuard.error(e)).getBytes(StandardCharsets.UTF_8));
    }

    /** GET /api/announce (public). */
    public void get(HttpExchange ex) throws IOException {
        String m = ex.getRequestMethod();
        if (!"GET".equals(m) && !"HEAD".equals(m)) { err(ex, 405, "method not allowed"); return; }
        State s = st;
        if (s.until != 0 && System.currentTimeMillis() >= s.until) {   // expired: same id, empty text
            byte[][] c = emptyCache;
            if (lastEmptyId != s.id || c[0] == null) { c[0] = body("", 0, s.id, s.discord); lastEmptyId = s.id; }
            out(ex, 200, c[0]);
            return;
        }
        out(ex, 200, s.json);
    }

    /** POST /api/admin/announce (admin token). */
    public void admin(HttpExchange ex) throws IOException {
        if ("OPTIONS".equals(ex.getRequestMethod())) {
            if (!cors.isEmpty()) {
                ex.getResponseHeaders().set("Access-Control-Allow-Origin", cors);
                ex.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
                ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
            }
            ex.sendResponseHeaders(204, -1);
            return;
        }
        String ip = guard.clientIp(ex);
        if (!"POST".equals(ex.getRequestMethod())) { err(ex, 405, "use POST"); return; }
        if (!guard.take("admin-ip:" + ip, 1, 5)) { err(ex, 429, "rate limited"); return; }
        String auth = ex.getRequestHeaders().getFirst("Authorization"), x = ex.getRequestHeaders().getFirst("X-Admin-Token");
        String given = x != null ? x.trim() : auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : null;
        if (adminToken.isEmpty() || given == null || !ApiGuard.secretEquals(given, adminToken)) {
            System.out.println("api: announce: bad token from " + ip);
            err(ex, 403, "forbidden");
            return;
        }
        String body = guard.body(ex);
        if (Pattern.compile("\"discord\"\\s*:").matcher(body).find() && jsonString(body, "text") == null) {
            String d = jsonString(body, "discord");
            String c = cleanDiscord(d);
            if (d != null && !d.trim().isEmpty() && c.isEmpty()) { err(ex, 400, "invalid discord link"); return; }
            synchronized (this) {
                State o = st;
                State n = new State(o.text, o.until, o.id, c);
                st = n;
                lastEmptyId = -1;
                save(n);
            }
            out(ex, 200, ("{\"ok\":true,\"discord\":" + Json.write(c) + "}").getBytes(StandardCharsets.UTF_8));
            return;
        }
        String raw = jsonString(body, "text");
        boolean clear = Pattern.compile("\"clear\"\\s*:\\s*true").matcher(body).find();
        String text = clean(raw);
        int minutes = 30;
        Matcher mm = Pattern.compile("\"minutes\"\\s*:\\s*(-?\\d{1,9})").matcher(body);
        if (mm.find()) minutes = Integer.parseInt(mm.group(1));
        if (raw != null && raw.length() > MAX_TEXT + 2000) { err(ex, 400, "text too long"); return; }
        if (!clear && !text.isEmpty() && (minutes < 1 || minutes > 1440)) { err(ex, 400, "minutes 1..1440"); return; }
        if (!clear && raw == null) { err(ex, 400, "text or clear"); return; }
        State s;
        synchronized (this) {
            int id = st.id + 1;
            s = clear || text.isEmpty() ? new State("", 0, id, st.discord)
                    : new State(text, System.currentTimeMillis() + minutes * 60_000L, id, st.discord);
            st = s;
            save(s);
        }
        System.out.println("api: announce " + (s.text.isEmpty() ? "cleared" : s.text.length() + " chars, " + minutes + " min") + " id " + s.id);
        out(ex, 200, ("{\"ok\":true,\"until\":" + s.until + "}").getBytes(StandardCharsets.UTF_8));
    }

    private void save(State s) {
        try {
            java.util.Properties p = new java.util.Properties();
            p.setProperty("id", Integer.toString(s.id));
            p.setProperty("until", Long.toString(s.until));
            p.setProperty("text", s.text);
            p.setProperty("discord", s.discord);
            Path dir = file.toAbsolutePath().getParent();
            if (dir != null) Files.createDirectories(dir);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (var w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) { p.store(w, "announce"); }
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            System.out.println("announce: cannot save " + file + ": " + e);
        }
    }

    /** Value of a JSON string field, with escapes decoded; null if missing. */
    static String jsonString(String body, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(body);
        if (!m.find()) return null;
        String s = m.group(1);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) { b.append(c); continue; }
            char n = s.charAt(++i);
            switch (n) {
                case 'n': case 'r': case 't': case 'b': case 'f': b.append(' '); break;
                case 'u':
                    if (i + 4 < s.length() + 0 && s.substring(i + 1, i + 5).matches("[0-9a-fA-F]{4}")) {
                        b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4;
                    }
                    break;
                default: b.append(n);
            }
        }
        return b.toString();
    }
}
