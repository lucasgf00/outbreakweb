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
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bug reports from the website.
 *
 *   POST /api/report         public. {"text":"..", "game":"file1"|"file2"|.., "ua":".." (optional),
 *                            "website":"" (honeypot: must be empty or absent)}
 *                            -> 200 {"ok":true,"id":N}
 *                            | 400 {"error":"empty"}  | 405 {"error":"method not allowed"}
 *                            | 429 {"error":"rate limited","retry_after":seconds}
 *                            text: cleaned (ChatFilter) and cut to 500 characters; game: [a-z0-9_-]{1,24} else
 *                            "unknown"; ua: cleaned, cut to 200. A filled honeypot gets {"ok":true,"id":0} and
 *                            is dropped. Limits per address: 1 every 30 s and 3 per hour; 30 per hour overall.
 *   GET  /api/admin/reports  admin token (Authorization: Bearer or X-Admin-Token), ?limit=N (1..1000, default 200)
 *                            -> {"total":n, "reports":[{"id","t","game","text","ua","ip_ref"}..]} newest first.
 *
 * Kept in api.reports_file (default data/reports/reports.jsonl), one JSON object per line, at most
 * api.reports_max (default 2000) reports: the oldest go first. No address is stored, only ip_ref
 * (HMAC of the address with a server secret, the same reference the moderation panel uses).
 */
public final class Reports {
    static final int MAX_TEXT = 500, MAX_UA = 200, MAX_DIAG = 6000;
    static final long HOUR_MS = 3_600_000L;
    private final long gapMs;                   // api.reports_gap_s (default 30)
    private final int perHour, globalPerHour;   // api.reports_per_hour (3), api.reports_global_per_hour (30)

    private final ApiGuard guard;
    private final String adminToken, cors;
    private final Path file;
    private final int max;
    private final ArrayDeque<Map<String, Object>> list = new ArrayDeque<>();
    private final Map<String, ArrayDeque<Long>> byIp = new ConcurrentHashMap<>();
    private final ArrayDeque<Long> global = new ArrayDeque<>();
    private long nextId = 1;
    private int fileLines = 0;

    public Reports(ServerConfig cfg, ApiGuard guard, String adminToken) {
        this.guard = guard;
        this.adminToken = adminToken;
        this.cors = cfg.get("api.cors_origin", "*");
        this.file = Path.of(cfg.get("api.reports_file", "data/reports/reports.jsonl"));
        this.max = Math.max(10, cfg.getInt("api.reports_max", 2000));
        this.gapMs = Math.max(1, cfg.getInt("api.reports_gap_s", 30)) * 1000L;
        this.perHour = Math.max(1, cfg.getInt("api.reports_per_hour", 3));
        this.globalPerHour = Math.max(1, cfg.getInt("api.reports_global_per_hour", 30));
        try {
            if (Files.isRegularFile(file))
                for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    fileLines++;
                    try {
                        Object o = Metrics.MiniJson.parse(l);
                        if (!(o instanceof Map)) continue;
                        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
                        for (Map.Entry<String, Object> e : m.entrySet())   // MiniJson numbers are doubles
                            if (e.getValue() instanceof Double) e.setValue(((Double) e.getValue()).longValue());
                        list.addLast(m);
                        Object id = m.get("id");
                        if (id instanceof Number) nextId = Math.max(nextId, ((Number) id).longValue() + 1);
                    } catch (RuntimeException ignored) { }
                }
            while (list.size() > max) list.removeFirst();
        } catch (IOException e) {
            System.out.println("reports: cannot read " + file + ": " + e);
        }
    }

    private void out(HttpExchange ex, int code, Object body) throws IOException {
        byte[] b = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ApiGuard.securityHeaders(ex.getResponseHeaders());
        if (!cors.isEmpty()) ex.getResponseHeaders().set("Access-Control-Allow-Origin", cors);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    static String cut(String s, int n) {
        if (s.codePointCount(0, s.length()) <= n) return s;
        return s.substring(0, s.offsetByCodePoints(0, n));
    }

    /** POST /api/report (public). */
    public void post(HttpExchange ex) throws IOException {
        if ("OPTIONS".equals(ex.getRequestMethod())) {
            if (!cors.isEmpty()) {
                ex.getResponseHeaders().set("Access-Control-Allow-Origin", cors);
                ex.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
                ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            }
            ex.sendResponseHeaders(204, -1);
            return;
        }
        if (!"POST".equals(ex.getRequestMethod())) { out(ex, 405, ApiGuard.error("method not allowed")); return; }
        String ip = guard.clientIp(ex);
        String body = guard.body(ex);
        String hp = ChatService.str(body, "website");
        ChatFilter f = ChatFilter.instance;
        String text = ChatService.clean(ChatService.str(body, "text"));
        if (f != null) text = f.sanitize(text);
        text = cut(text, MAX_TEXT).trim();
        if (text.isEmpty()) { out(ex, 400, ApiGuard.error("empty")); return; }
        long now = System.currentTimeMillis();
        long wait = admit(ip, now);
        if (wait > 0) {
            if (f != null) f.count("report_rate_limited");
            Map<String, Object> e = ApiGuard.error("rate limited");
            e.put("retry_after", (wait + 999) / 1000);
            ex.getResponseHeaders().set("Retry-After", Long.toString((wait + 999) / 1000));
            out(ex, 429, e);
            return;
        }
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("ok", true);
        if (hp != null && !hp.isEmpty()) {   // bot: looks accepted, nothing stored
            if (f != null) f.count("report_honeypot");
            ok.put("id", 0);
            out(ex, 200, ok);
            return;
        }
        String game = ChatService.str(body, "game");
        if (game == null || !game.matches("[a-z0-9_-]{1,24}")) game = "unknown";
        String ua = ChatService.str(body, "ua");
        ua = ua == null ? "" : cut(ChatService.clean(ua), MAX_UA);
        if (f != null) text = f.maskBadwords(text);
        String diag = ChatService.str(body, "diag");
        diag = diag == null ? "" : cut(ChatService.clean(diag), MAX_DIAG);
        Map<String, Object> r = new LinkedHashMap<>();
        synchronized (this) {
            r.put("id", nextId++);
            r.put("t", now);
            r.put("game", game);
            r.put("text", text);
            r.put("ua", ua);
            if (!diag.isEmpty()) r.put("diag", diag);
            r.put("ip_ref", guard.ipRef(ip));
            list.addLast(r);
            while (list.size() > max) list.removeFirst();
            save(r);
        }
        Metrics.inc("report", game);
        System.out.println("api: report #" + r.get("id") + " (" + game + ", " + text.length() + " chars)");
        ok.put("id", r.get("id"));
        out(ex, 200, ok);
    }

    /** 0 if this address may send now, else the milliseconds to wait. Records the send when allowed. */
    private synchronized long admit(String ip, long now) {
        if (byIp.size() > 20_000) byIp.values().removeIf(q -> q.isEmpty() || now - q.peekLast() > HOUR_MS);
        ArrayDeque<Long> q = byIp.computeIfAbsent(ip, k -> new ArrayDeque<>());
        while (!q.isEmpty() && now - q.peekFirst() > HOUR_MS) q.removeFirst();
        while (!global.isEmpty() && now - global.peekFirst() > HOUR_MS) global.removeFirst();
        if (!q.isEmpty() && now - q.peekLast() < gapMs) return gapMs - (now - q.peekLast());
        if (q.size() >= perHour) return HOUR_MS - (now - q.peekFirst());
        if (global.size() >= globalPerHour) return HOUR_MS - (now - global.peekFirst());
        q.addLast(now);
        global.addLast(now);
        return 0;
    }

    /** Appends; when the file holds 25 % more lines than the cap it is rewritten with the kept ones. */
    private void save(Map<String, Object> r) {
        try {
            Path dir = file.toAbsolutePath().getParent();
            if (dir != null) Files.createDirectories(dir);
            if (fileLines + 1 > max + max / 4) {
                StringBuilder b = new StringBuilder();
                for (Map<String, Object> m : list) b.append(Json.write(m)).append('\n');
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(tmp, b, StandardCharsets.UTF_8);
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                fileLines = list.size();
            } else {
                Files.writeString(file, Json.write(r) + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                fileLines++;
            }
        } catch (IOException e) {
            System.out.println("reports: cannot save " + file + ": " + e);
        }
    }

    /** GET /api/admin/reports (admin token). */
    public void admin(HttpExchange ex) throws IOException {
        String ip = guard.clientIp(ex);
        if (!"GET".equals(ex.getRequestMethod())) { out(ex, 405, ApiGuard.error("use GET")); return; }
        if (!guard.take("admin-ip:" + ip, 1, 10)) { out(ex, 429, ApiGuard.error("rate limited")); return; }
        String auth = ex.getRequestHeaders().getFirst("Authorization"), x = ex.getRequestHeaders().getFirst("X-Admin-Token");
        String given = x != null ? x.trim() : auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : null;
        if (adminToken.isEmpty() || given == null || !ApiGuard.secretEquals(given, adminToken)) {
            System.out.println("api: admin reports: bad token");
            out(ex, 403, ApiGuard.error("forbidden"));
            return;
        }
        int limit = 200;
        String q = ex.getRequestURI().getRawQuery();
        if (q != null && q.matches("limit=\\d{1,4}")) limit = Math.max(1, Math.min(1000, Integer.parseInt(q.substring(6))));
        List<Object> out = new ArrayList<>();
        Map<String, Object> m = new LinkedHashMap<>();
        synchronized (this) {
            for (Iterator<Map<String, Object>> it = list.descendingIterator(); it.hasNext() && out.size() < limit; ) out.add(it.next());
            m.put("total", list.size());
        }
        m.put("reports", out);
        out(ex, 200, m);
    }
}
