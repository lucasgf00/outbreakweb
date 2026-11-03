/*
    reo-web bioserver - fan server for Resident Evil Outbreak File #1/#2
    (C) 2026 reo-web contributors. AGPL-3.0-or-later, see LICENSE.txt.
*/

package reo.common;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Small read-only HTTP API for the website.
 *
 *   GET  /api/health          liveness
 *   GET  /api/rooms           areas, rooms, slots and players of every running game (JSON)
 *   GET  /api/dns             hostname -> IP map for the gateway's DNS override
 *   POST /api/chat/poll|send  website chat, see ChatService. Optional "room":"es"|"en"|"pt" (language room of the
 *                             community channel; missing/unknown = "es"); the poll answers only that room
 *                             ("chat_room", "community", "online_rooms"). Automatic moderation: ChatFilter
 *                             (odd characters, capitals, repeats, links, owner's list data/badwords.txt -> ***).
 *   POST /api/kk/register     {"name": "...", "credential": "<12 hex>"} -> {"ok":true} | 409/400 {"error":..}
 *                             US (kk) account: created if the name is new, accepted if same credential
 *   POST /api/admin           moderation, Authorization: Bearer <api.admin_token | env REO_ADMIN_TOKEN>
 *                             {"action":"ban|unban|mute|unmute|list|purge|release", "name":"..", "ip":"..",
 *                              "minutes":N (0 = permanent), "reason":".."}; disabled without a token
 *                             of at least 24 characters
 *   POST /api/metrics/event   anonymous usage events from the page, see Metrics
 *   GET  /api/admin/metrics   data of the owner's metrics panel (?range=today|7d|30d|90d), same Bearer token as /api/admin
 *   GET  /api/announce        public admin notice {"text","until","id"}, no-store; see Announce
 *   POST /api/admin/announce  same Bearer token: {"text":"..","minutes":30 (1..1440)} | {"clear":true} -> {"ok":true,"until":ms}
 *   POST /api/report          public bug report {"text":"..(<=500)","game":"file1","ua":"..","website":""(honeypot)}
 *                             -> {"ok":true,"id":N} | 400 {"error":"empty"} | 429 {"error":"rate limited","retry_after":s}
 *                             1 per 30 s and 3 per hour per address; stored in data/reports/reports.jsonl (ip_ref only)
 *   GET  /api/admin/reports   admin token like /api/admin/metrics (?limit=N)
 *                             -> {"total":n,"reports":[{"id","t","game","text","ua","ip_ref"}]}; see Reports
 *   POST /api/dev/login       (only if api.dev_login=true, only from loopback, never through a proxy)
 *                             form: game=file1&user=..&password=..  -> {"sessid":"12345678"}
 *                             Stand-in for the KDDI/regweb web login until the real flow exists.
 *
 * Every endpoint goes through ApiGuard: global and per-address rate limits, body size limit,
 * security headers; registration also checks the name policy, bans and new names per address.
 */
public final class HttpApi {
    private final List<LobbyView> views;
    private final ServerConfig cfg;
    private final Map<String, SessionStore> sessionStores = new HashMap<>();
    private final boolean devLogin;
    private final String corsOrigin;
    private final ApiGuard guard;
    private final String adminToken;
    private final int namesPerIpHour;
    private final int registerPerSec;
    private ChatService chat;
    private final Map<String, Map<String, Long>> namesByIp = new java.util.concurrent.ConcurrentHashMap<>();
    private HttpServer http;

    public HttpApi(ServerConfig cfg, List<LobbyView> views) {
        this.cfg = cfg;
        this.views = views;
        this.devLogin = cfg.getBool("api.dev_login", false);
        this.corsOrigin = cfg.get("api.cors_origin", "*");
        this.guard = new ApiGuard(cfg);
        String tok = System.getenv("REO_ADMIN_TOKEN");
        if (tok == null || tok.isBlank()) tok = cfg.get("api.admin_token", "");
        this.adminToken = tok.trim().length() >= 24 ? tok.trim() : "";
        this.registerPerSec = cfg.getInt("api.register_global_per_s", 1);
        this.namesPerIpHour = cfg.getInt("api.names_per_ip_hour", 12);   // players behind one NAT (the gateway allows 12 sessions per address)
        for (LobbyView v : views) sessionStores.put(v.config().id, new SessionStore(v.config()));
    }

    public void start() throws IOException {
        String bind = cfg.get("api.bind", "127.0.0.1");
        int port = cfg.getInt("api.port", 8088);
        http = HttpServer.create(new InetSocketAddress(bind, port), 32);
        http.setExecutor(Executors.newFixedThreadPool(cfg.getInt("api.threads", 8)));
        http.createContext("/api/health", ex -> safe(ex, this::health));
        http.createContext("/api/rooms", ex -> safe(ex, this::rooms));
        http.createContext("/api/dns", ex -> safe(ex, this::dns));
        http.createContext("/api/kk/register", ex -> safe(ex, this::kkRegister));
        new ChatFilter(cfg);   // automatic moderation + owner's word list (ChatFilter.java); before ChatService
        chat = new ChatService(cfg);
        http.createContext("/api/chat/", ex -> {   // website chat (ChatService.java)
            boolean ok = false;
            try { ok = guard.admit(ex); } catch (IOException ignored) { }
            if (ok) chat.handle(ex); else ex.close();
        });
        if (devLogin) http.createContext("/api/dev/login", ex -> safe(ex, this::devLogin));
        if (!adminToken.isEmpty()) http.createContext("/api/admin", ex -> safe(ex, this::admin));
        if (!adminToken.isEmpty()) {   // moderation tab of the owner's panel (Moderation.java)
            Moderation mod = new Moderation(guard, chat, adminToken);
            http.createContext("/api/admin/mod/", ex -> safe(ex, mod::handle));
        }
        Social social = new Social(cfg, guard, chat, adminToken);   // player reports + friends (Social.java, notes/social.md)
        http.createContext("/api/social/", ex -> {
            boolean ok = false;
            try { ok = guard.admit(ex); } catch (IOException ignored) { }
            if (ok) safe(ex, social::handle); else ex.close();
        });
        if (!adminToken.isEmpty()) http.createContext("/api/admin/player-reports", ex -> safe(ex, social::admin));
        Announce announce = new Announce(cfg, guard, adminToken);   // admin notice for the website (Announce.java)
        http.createContext("/api/announce", ex -> safe(ex, announce::get));
        if (!adminToken.isEmpty()) http.createContext("/api/admin/announce", ex -> safe(ex, announce::admin));
        Reports reports = new Reports(cfg, guard, adminToken);   // bug reports (Reports.java)
        http.createContext("/api/report", ex -> safe(ex, reports::post));
        if (!adminToken.isEmpty()) http.createContext("/api/admin/reports", ex -> safe(ex, reports::admin));
        if (cfg.getBool("metrics.enabled", true)) {   // owner's metrics panel (Metrics.java); no IP is stored
            Metrics metrics = new Metrics(cfg, guard, adminToken);
            metrics.start();
            http.createContext("/api/metrics/event", ex -> safe(ex, metrics::event));
            if (!adminToken.isEmpty()) http.createContext("/api/admin/metrics", ex -> safe(ex, metrics::admin));
        }
        http.start();
        System.out.println("HTTP API on http://" + bind + ":" + port + "/api/rooms" + (devLogin ? " (dev login enabled)" : "")
                + (adminToken.isEmpty() ? "" : " (admin API enabled)"));
    }

    private interface Handler { void handle(HttpExchange ex) throws Exception; }

    private void safe(HttpExchange ex, Handler h) {
        try {
            if (!guard.admit(ex)) return;
            h.handle(ex);
        } catch (Exception e) {
            Metrics.inc("err", "api " + ex.getRequestURI().getPath().replaceAll("^/api/([a-z]+).*", "$1"));
            try {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("error", e.getClass().getSimpleName());
                send(ex, 500, m);
            } catch (IOException ignored) { }
        } finally {
            ex.close();
        }
    }

    private void send(HttpExchange ex, int code, Object body) throws IOException {
        byte[] b = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ApiGuard.securityHeaders(ex.getResponseHeaders());
        if (!corsOrigin.isEmpty()) ex.getResponseHeaders().set("Access-Control-Allow-Origin", corsOrigin);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private boolean onlyGet(HttpExchange ex) throws IOException {
        if ("GET".equals(ex.getRequestMethod()) || "HEAD".equals(ex.getRequestMethod())) return true;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", "method not allowed");
        send(ex, 405, m);
        return false;
    }

    private void health(HttpExchange ex) throws IOException {
        if (!onlyGet(ex)) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        List<String> g = new ArrayList<>();
        for (LobbyView v : views) g.add(v.config().id);
        m.put("games", g);
        send(ex, 200, m);
    }

    private void rooms(HttpExchange ex) throws IOException {
        if (!onlyGet(ex)) return;
        String only = query(ex).get("game");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("generated", Instant.now().toString());
        List<Object> games = new ArrayList<>();
        for (LobbyView v : views) {
            if (only != null && !only.equals(v.config().id)) continue;
            Map<String, Object> s;
            try {
                s = v.snapshot();
            } catch (RuntimeException e) {
                s = new LinkedHashMap<>();
                s.put("id", v.config().id);
                s.put("error", "snapshot failed: " + e.getClass().getSimpleName());
            }
            games.add(s);
        }
        for (reo.us.KkService kk : reo.us.KkService.instances()) {
            Map<String, Object> snap = kk.snapshot();
            if (only == null || only.equals(snap.get("id"))) games.add(snap);
        }
        m.put("games", games);
        send(ex, 200, m);
    }

    private static String jsonField(String body, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private void kkRegister(HttpExchange ex) throws Exception {
        if ("OPTIONS".equals(ex.getRequestMethod())) {
            if (!corsOrigin.isEmpty()) {
                ex.getResponseHeaders().set("Access-Control-Allow-Origin", corsOrigin);
                ex.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
                ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            }
            ex.sendResponseHeaders(204, -1);
            return;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        if (!"POST".equals(ex.getRequestMethod())) { m.put("error", "method not allowed"); send(ex, 405, m); return; }
        reo.us.KkService kk = reo.us.KkService.instance;
        if (kk == null) { m.put("error", "us kk service disabled"); send(ex, 503, m); return; }
        String body = guard.body(ex);
        String name = jsonField(body, "name"), cred = jsonField(body, "credential");
        // Behind the page server / reverse proxy the player address comes in X-Forwarded-For (ApiGuard.clientIp).
        String ip = guard.clientIp(ex);
        if (guard.isBanned(name, ip)) { m.put("error", "banned"); send(ex, 403, m); return; }
        String bad = guard.checkName(name);
        if (bad != null) { m.put("error", bad); send(ex, 400, m); return; }
        // new names per address per hour (the page re-registers the same name at every start: free)
        String lname = name.toLowerCase(java.util.Locale.ROOT);
        long now = System.currentTimeMillis();
        Map<String, Long> mine = namesByIp.computeIfAbsent(ip, k -> new java.util.concurrent.ConcurrentHashMap<>());
        mine.values().removeIf(t -> now - t > 3_600_000L);
        if (!mine.containsKey(lname) && (mine.size() >= namesPerIpHour || !guard.take("register-global", registerPerSec, Math.max(30, registerPerSec * 30)))) {
            m.put("error", "rate limited"); send(ex, 429, m); return;
        }
        int before = reo.us.KkService.accountCount();
        String err = kk.register(name, cred, ip);
        if (err == null && reo.us.KkService.accountCount() > before) Metrics.inc("acct_new", "all");
        if (err == null) {
            mine.put(lname, now);
            if (namesByIp.size() > 10_000) namesByIp.values().removeIf(Map::isEmpty);
            m.put("ok", true); send(ex, 200, m); return;
        }
        m.put("error", err);
        send(ex, "name taken".equals(err) ? 409 : "rate limited".equals(err) ? 429 : 400, m);
    }

    /** Moderation: ban/mute a name or an address, list, or purge a name's chat messages. */
    private void admin(HttpExchange ex) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        String ip = guard.clientIp(ex);
        if (!"POST".equals(ex.getRequestMethod())) { m.put("error", "use POST"); send(ex, 405, m); return; }
        if (!guard.take("admin-ip:" + ip, 1, 5)) { m.put("error", "rate limited"); send(ex, 429, m); return; }
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ") || !ApiGuard.secretEquals(auth.substring(7).trim(), adminToken)) {
            System.out.println("api: admin: bad token from " + ip);
            m.put("error", "forbidden"); send(ex, 403, m); return;
        }
        String body = guard.body(ex);
        String action = jsonField(body, "action"), name = jsonField(body, "name"), tip = jsonField(body, "ip"),
               reason = jsonField(body, "reason");
        java.util.regex.Matcher mm = java.util.regex.Pattern.compile("\"minutes\"\\s*:\\s*(\\d{1,9})").matcher(body);
        long minutes = mm.find() ? Long.parseLong(mm.group(1)) : 0;
        if (name != null && !name.matches("[A-Za-z0-9]{1,15}")) { m.put("error", "bad name"); send(ex, 400, m); return; }
        if (tip != null && !tip.matches("[0-9A-Fa-f.:]{2,45}")) { m.put("error", "bad ip"); send(ex, 400, m); return; }
        String what = name != null ? "name" : "ip", target = name != null ? name : tip;
        if (action == null) action = "";
        switch (action) {
            case "list":
                m.put("moderation", guard.list());
                break;
            case "ban": case "mute":
                if (target == null) { m.put("error", "name or ip"); send(ex, 400, m); return; }
                guard.set(action, what, target, minutes, reason);
                if (name != null && "ban".equals(action)) m.put("purged", chat.purge(name));
                m.put("ok", true);
                break;
            case "unban": case "unmute":
                if (target == null) { m.put("error", "name or ip"); send(ex, 400, m); return; }
                m.put("ok", guard.clear(action.substring(2), what, target));
                break;
            case "purge":
                if (name == null) { m.put("error", "name"); send(ex, 400, m); return; }
                m.put("purged", chat.purge(name));
                m.put("ok", true);
                break;
            case "release":   // free a registered name (kicked first if online), no restart
                if (name == null) { m.put("error", "name"); send(ex, 400, m); return; }
                for (reo.us.KkService kk : reo.us.KkService.instances()) kk.kick(name);
                m.put("ok", reo.us.KkService.release(name));
                break;
            default:
                m.put("error", "unknown action"); send(ex, 400, m); return;
        }
        if ("ban".equals(action) && name != null) for (reo.us.KkService kk : reo.us.KkService.instances()) kk.kick(name);
        if (!"list".equals(action)) {   // same log as the panel (Moderation.java); "cli" = admin.sh / curl
            Map<String, Object> log = new LinkedHashMap<>();
            log.put("t", System.currentTimeMillis());
            log.put("by", "cli");
            log.put("action", action);
            if (name != null) log.put("name", name);
            if (tip != null) { log.put("ip_ref", guard.ipRef(tip)); log.put("ip_masked", ApiGuard.maskIp(tip)); }
            if (action.equals("ban") || action.equals("mute")) log.put("minutes", minutes);
            if (reason != null && !reason.isEmpty()) log.put("reason", reason);
            guard.logAction(log);
        }
        System.out.println("api: admin " + action + (target != null ? " " + what + " " + target : "") + (minutes > 0 ? " " + minutes + " min" : ""));
        send(ex, 200, m);
    }

    private void dns(HttpExchange ex) throws IOException {
        if (!onlyGet(ex)) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("public_ip", cfg.get("public_ip", "127.0.0.1"));
        m.put("hosts", cfg.hosts());
        m.put("overrides", cfg.dnsOverrides());
        List<Object> ports = new ArrayList<>();
        for (LobbyView v : views) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("game", v.config().id);
            p.put("lobby_port", v.config().lobbyPort);
            p.put("game_port", v.config().gamePort);
            ports.add(p);
        }
        m.put("ports", ports);
        send(ex, 200, m);
    }

    private void devLogin(HttpExchange ex) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        // through serve.py or a reverse proxy every request is "from loopback": refuse proxied ones
        if (!ex.getRemoteAddress().getAddress().isLoopbackAddress() || ex.getRequestHeaders().containsKey("X-Forwarded-For")
                || ex.getRequestHeaders().containsKey("X-Real-IP") || ex.getRequestHeaders().containsKey("CF-Connecting-IP")
                || !guard.take("dev-login", 2, 5)) {
            m.put("error", "dev login only from loopback");
            send(ex, 403, m);
            return;
        }
        if (!"POST".equals(ex.getRequestMethod())) {
            m.put("error", "use POST");
            send(ex, 405, m);
            return;
        }
        String body;
        try (InputStream in = ex.getRequestBody()) {
            body = new String(in.readNBytes(4096), StandardCharsets.UTF_8);
        }
        Map<String, String> f = parseForm(body);
        SessionStore st = sessionStores.get(f.getOrDefault("game", "file1"));
        if (st == null) {
            m.put("error", "unknown game");
            send(ex, 400, m);
            return;
        }
        String sess = st.login(f.get("user"), f.get("password"),
                ex.getRemoteAddress().getAddress().getHostAddress(), ex.getRemoteAddress().getPort());
        if (sess == null) {
            m.put("error", "invalid credentials");
            send(ex, 401, m);
            return;
        }
        m.put("sessid", sess);
        send(ex, 200, m);
    }

    private static Map<String, String> query(HttpExchange ex) {
        String q = ex.getRequestURI().getRawQuery();
        return q == null ? new HashMap<>() : parseForm(q);
    }

    private static Map<String, String> parseForm(String s) {
        Map<String, String> m = new HashMap<>();
        for (String kv : s.split("&")) {
            int i = kv.indexOf('=');
            if (i <= 0) continue;
            m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8),
                  URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }
}
