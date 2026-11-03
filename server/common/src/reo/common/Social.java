/*
    reo-web bioserver - fan server for Resident Evil Outbreak File #1/#2
    (C) 2026 reo-web contributors. AGPL-3.0-or-later, see LICENSE.txt.
*/

package reo.common;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Social features of the website (docs/notes/social.md): player reports and friends. Muting is only on the page.
 * Every call is signed with the player's registered name + credential (the same pair as the chat).
 *
 *   POST /api/social/report  {name, credential, target, reason: insult|spam|cheat|name|other, note (<= 300),
 *                            seen: [{t, text}..] (<= 10 lines the reporter saw on the page, <= 200 each)}
 *        -> 200 {"ok":true,"id":N} | 400 {"error":"bad target"|"bad reason"|"self"} | 401 bad credential
 *           | 403 banned | 404 {"error":"unknown name"} | 429 {"error":"rate limited"|"already reported","retry_after":s}
 *        The server adds the target's messages it still holds (ChatService.messagesBy: what really was said).
 *        Limits: per reporter 1 every api.preports_gap_s (20 s) and 10 per hour; per address 15 per hour; the same
 *        target once every 6 h per reporter; 300 per hour overall. No address is stored, only ip_ref.
 *        If a Discord webhook is set (env REO_REPORT_WEBHOOK, or that line in api.alerts_env_file, default
 *        /etc/reo/alerts.env) a short notice goes to it: report id, reported name, reason, a short extract and
 *        the link to the admin panel (api.public_url + /admin). Never the reporter or an address.
 *   GET  /api/admin/player-reports  admin token, ?limit=N -> {"total","open","reports":[..newest first]}
 *   POST /api/admin/player-reports  admin token, {"id":N,"done":true|false} -> {"ok":true}
 *
 *   POST /api/social/friends {name, credential, op, target?, share?}
 *        op "list"   -> {"ok":true,"share":bool,"max":100,"friends":[{name, since, status|hidden}],
 *                        "sent":[{name, since}], "incoming":[{name, since}]}
 *        op "add"    target: a registered name. If the target had already added me, we are friends now.
 *        op "remove" target: drop my side (friendship or a request I sent).
 *        op "reject" target: drop the request the target sent me; its later requests stay hidden.
 *        op "share"  share: true|false, whether my friends see where I am (off by default).
 *        Friends = both added each other (a request needs the other side to add back). A friend's status
 *        (online, File #1/#2, area, room, in game, or only on the page) shows only when that friend turned "share"
 *        on. At most 100 names added per player, 30 adds per hour. Kept in api.social_file (default
 *        data/social/friends.json, 0600), rewritten atomically on every change; a released name is forgotten.
 */
public final class Social {
    public static volatile Social instance;
    static final List<String> REASONS = List.of("insult", "spam", "cheat", "name", "other");
    static final int MAX_FRIENDS = 100, MAX_IGNORE = 300, MAX_NOTE = 300, MAX_SEEN = 10, MAX_LINE = 200;
    static final long HOUR = 3_600_000L, ONLINE_WEB_MS = 90_000L;

    private final ApiGuard guard;
    private final ChatService chat;
    private final String adminToken, publicUrl, alertsFile;
    private final Path friendsFile, reportsFile;
    private final int reportsMax;
    private final long gapMs;

    // ---- friends ----
    private static final class User {
        String disp;
        boolean share;
        final LinkedHashMap<String, Long> add = new LinkedHashMap<>();   // lower name -> when I added it
        final LinkedHashSet<String> ignore = new LinkedHashSet<>();      // rejected requesters
    }
    private final Map<String, User> users = new HashMap<>();
    private final Map<String, Set<String>> incoming = new HashMap<>();   // lower name -> who added it

    // ---- reports ----
    private final ArrayDeque<Map<String, Object>> reports = new ArrayDeque<>();
    private long nextId = 1;
    private int fileLines = 0;
    private final Map<String, ArrayDeque<Long>> byKey = new ConcurrentHashMap<>();
    private final Map<String, Long> pairs = new ConcurrentHashMap<>();   // reporter|target -> when
    private final ArrayDeque<Long> global = new ArrayDeque<>();
    private final ArrayDeque<Long> hookTimes = new ArrayDeque<>();
    private final ExecutorService hookPool = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "reports-webhook"); t.setDaemon(true); return t; });
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private String hookCache = null;
    private long hookStamp = -2;

    public Social(ServerConfig cfg, ApiGuard guard, ChatService chat, String adminToken) {
        this.guard = guard;
        this.chat = chat;
        this.adminToken = adminToken;
        this.publicUrl = cfg.get("api.public_url", "").replaceAll("/+$", "");
        this.alertsFile = cfg.get("api.alerts_env_file", "/etc/reo/alerts.env");
        this.friendsFile = Path.of(cfg.get("api.social_file", "data/social/friends.json"));
        this.reportsFile = Path.of(cfg.get("api.player_reports_file", "data/social/player-reports.jsonl"));
        this.reportsMax = Math.max(50, cfg.getInt("api.player_reports_max", 5000));
        this.gapMs = Math.max(1, cfg.getInt("api.preports_gap_s", 20)) * 1000L;
        loadFriends();
        loadReports();
        instance = this;
    }

    // ================================================================ helpers

    private static Map<String, Object> obj(String body) {
        try {
            Object o = Metrics.MiniJson.parse(body, 1200);
            if (o instanceof Map) {
                @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
                return m;
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    private static String s(Map<String, Object> m, String k) { Object v = m.get(k); return v instanceof String ? (String) v : null; }

    private static long l(Object v) { return v instanceof Number ? ((Number) v).longValue() : 0; }

    private static Map<String, Object> err(String e, long retryMs) {
        Map<String, Object> m = ApiGuard.error(e);
        if (retryMs > 0) m.put("retry_after", (retryMs + 999) / 1000);
        return m;
    }

    private static Map<String, Object> ok() { Map<String, Object> m = new LinkedHashMap<>(); m.put("ok", true); return m; }

    private boolean adminOk(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization"), x = ex.getRequestHeaders().getFirst("X-Admin-Token");
        String given = x != null ? x.trim() : auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : null;
        return !adminToken.isEmpty() && given != null && ApiGuard.secretEquals(given, adminToken);
    }

    /** The signed-in name of the request (lowercase is up to the caller), or null after answering the error. */
    private String signedIn(HttpExchange ex, Map<String, Object> b) throws IOException {
        String name = s(b, "name");
        if (name == null || !chat.checkCredential(name, s(b, "credential"))) { ApiGuard.reply(ex, 401, ApiGuard.error("bad credential")); return null; }
        if (guard.sanctionUntil("ban", name, null) >= 0) { ApiGuard.reply(ex, 403, ApiGuard.error("banned")); return null; }
        return name;
    }

    // ================================================================ HTTP

    public void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (!"POST".equals(ex.getRequestMethod())) { ApiGuard.reply(ex, 405, ApiGuard.error("method not allowed")); return; }
        String ip = guard.clientIp(ex);
        if (!guard.take("social-ip:" + ip, 2, 20)) { ApiGuard.reply(ex, 429, err("rate limited", 1000)); return; }
        Map<String, Object> b = obj(guard.body(ex));
        if (b == null) { ApiGuard.reply(ex, 400, ApiGuard.error("bad json")); return; }
        if (path.equals("/api/social/friends")) friends(ex, b, ip);
        else if (path.equals("/api/social/report")) report(ex, b, ip);
        else ApiGuard.reply(ex, 404, ApiGuard.error("not found"));
    }

    // ================================================================ friends

    private void friends(HttpExchange ex, Map<String, Object> b, String ip) throws IOException {
        String name = signedIn(ex, b);
        if (name == null) return;
        String me = name.toLowerCase(Locale.ROOT), op = s(b, "op");
        String target = s(b, "target");
        String tk = target == null ? null : target.toLowerCase(Locale.ROOT);
        if (op == null) op = "list";
        switch (op) {
            case "list": break;
            case "add": {
                if (tk == null || !ChatService.NAME_RE.matcher(target).matches()) { ApiGuard.reply(ex, 400, ApiGuard.error("bad target")); return; }
                if (tk.equals(me)) { ApiGuard.reply(ex, 400, ApiGuard.error("self")); return; }
                if (!chat.accountExists(target)) { ApiGuard.reply(ex, 404, ApiGuard.error("unknown name")); return; }
                synchronized (this) {
                    User u = user(me, name);
                    if (!u.add.containsKey(tk)) {
                        if (u.add.size() >= MAX_FRIENDS) { ApiGuard.reply(ex, 409, ApiGuard.error("full")); return; }
                        if (!guard.take("social-add:" + me, 30.0 / 3600, 30)) { ApiGuard.reply(ex, 429, err("rate limited", 60_000)); return; }
                        u.add.put(tk, System.currentTimeMillis());
                        u.ignore.remove(tk);
                        incoming.computeIfAbsent(tk, k -> new HashSet<>()).add(me);
                        saveFriends();
                    }
                }
                break;
            }
            case "remove": case "reject": {
                if (tk == null) { ApiGuard.reply(ex, 400, ApiGuard.error("bad target")); return; }
                synchronized (this) {
                    if (op.equals("remove")) unlink(me, tk);
                    else {
                        User u = user(me, name);
                        unlink(tk, me);
                        u.ignore.add(tk);
                        while (u.ignore.size() > MAX_IGNORE) { Iterator<String> it = u.ignore.iterator(); it.next(); it.remove(); }
                    }
                    saveFriends();
                }
                break;
            }
            case "share": {
                synchronized (this) {
                    user(me, name).share = Boolean.TRUE.equals(b.get("share"));
                    saveFriends();
                }
                break;
            }
            default: ApiGuard.reply(ex, 400, ApiGuard.error("bad op")); return;
        }
        ApiGuard.reply(ex, 200, list(me, name));
    }

    private User user(String key, String disp) {
        User u = users.computeIfAbsent(key, k -> new User());
        if (disp != null) u.disp = disp;
        return u;
    }

    private void unlink(String from, String to) {
        User u = users.get(from);
        if (u != null) u.add.remove(to);
        Set<String> in = incoming.get(to);
        if (in != null) { in.remove(from); if (in.isEmpty()) incoming.remove(to); }
    }

    /** kk sessions of both titles by lower name: {game, area, room, in_game}. */
    private static Map<String, Map<String, Object>> kkPlayers() {
        Map<String, Map<String, Object>> m = new HashMap<>();
        for (reo.us.KkService kk : reo.us.KkService.instances())
            try { for (Map<String, Object> p : kk.players()) m.put(String.valueOf(p.get("name")).toLowerCase(Locale.ROOT), p); }
            catch (RuntimeException ignored) { }
        return m;
    }

    private synchronized Map<String, Object> list(String me, String disp) {
        User u = user(me, disp);
        Map<String, Map<String, Object>> kk = kkPlayers();
        long now = System.currentTimeMillis();
        List<Object> friends = new ArrayList<>(), sent = new ArrayList<>(), inc = new ArrayList<>();
        for (Map.Entry<String, Long> e : u.add.entrySet()) {
            String k = e.getKey();
            User f = users.get(k);
            Map<String, Object> item = new LinkedHashMap<>();
            Map<String, Object> live = kk.get(k);
            item.put("name", live != null ? live.get("name") : f != null && f.disp != null ? f.disp : k);
            item.put("since", e.getValue());
            boolean mutual = f != null && f.add.containsKey(me);
            if (!mutual) { sent.add(item); continue; }
            if (!f.share) item.put("hidden", true);
            else {
                Map<String, Object> st = new LinkedHashMap<>();
                if (live != null) {
                    st.put("online", true);
                    st.put("game", "file2-us".equals(live.get("game")) ? "file2" : "file1");
                    st.put("area", live.get("area"));
                    st.put("room", live.get("room"));
                    st.put("in_game", live.get("in_game"));
                } else {
                    long w = chat.webLast(k);
                    st.put("online", w > 0 && now - w <= ONLINE_WEB_MS);
                    if (w > 0 && now - w <= ONLINE_WEB_MS) st.put("web", true);
                }
                item.put("status", st);
            }
            friends.add(item);
        }
        Set<String> in = incoming.getOrDefault(me, Collections.emptySet());
        List<String[]> pend = new ArrayList<>();
        for (String k : in) {
            if (u.add.containsKey(k) || u.ignore.contains(k)) continue;
            User f = users.get(k);
            if (f == null || !f.add.containsKey(me)) continue;
            pend.add(new String[] { k, f.disp != null ? f.disp : k, Long.toString(f.add.get(me)) });
        }
        pend.sort((a, c) -> Long.compare(Long.parseLong(c[2]), Long.parseLong(a[2])));
        for (String[] p : pend.subList(0, Math.min(50, pend.size()))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", p[1]);
            item.put("since", Long.parseLong(p[2]));
            inc.add(item);
        }
        Map<String, Object> m = ok();
        m.put("share", u.share);
        m.put("max", MAX_FRIENDS);
        m.put("friends", friends);
        m.put("sent", sent);
        m.put("incoming", inc);
        m.put("incoming_total", pend.size());
        return m;
    }

    /** A released name (admin, KkService.release/releaseName) can be registered by someone else: drop its friends. */
    public static void forget(String name) {
        Social s = instance;
        if (s == null || name == null) return;
        String k = name.toLowerCase(Locale.ROOT);
        synchronized (s) {
            User u = s.users.remove(k);
            if (u != null) for (String to : u.add.keySet()) { Set<String> in = s.incoming.get(to); if (in != null) in.remove(k); }
            Set<String> in = s.incoming.remove(k);
            if (in != null) for (String from : in) { User f = s.users.get(from); if (f != null) f.add.remove(k); }
            for (User o : s.users.values()) o.ignore.remove(k);
            if (u != null || in != null) s.saveFriends();
        }
    }

    @SuppressWarnings("unchecked")
    private void loadFriends() {
        try {
            if (!Files.isRegularFile(friendsFile)) return;
            Object o = Metrics.MiniJson.parse(Files.readString(friendsFile, StandardCharsets.UTF_8), 64, Integer.MAX_VALUE, Integer.MAX_VALUE);
            if (!(o instanceof Map)) return;
            Object us = ((Map<String, Object>) o).get("users");
            if (!(us instanceof Map)) return;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) us).entrySet()) {
                if (!(e.getValue() instanceof Map) || !ChatService.NAME_RE.matcher(e.getKey()).matches()) continue;
                Map<String, Object> m = (Map<String, Object>) e.getValue();
                String k = e.getKey().toLowerCase(Locale.ROOT);
                User u = user(k, s(m, "disp"));
                u.share = Boolean.TRUE.equals(m.get("share"));
                if (m.get("add") instanceof Map)
                    for (Map.Entry<String, Object> a : ((Map<String, Object>) m.get("add")).entrySet()) {
                        String t = a.getKey().toLowerCase(Locale.ROOT);
                        if (u.add.size() >= MAX_FRIENDS || t.equals(k)) continue;
                        u.add.put(t, l(a.getValue()));
                        incoming.computeIfAbsent(t, x -> new HashSet<>()).add(k);
                    }
                if (m.get("ignore") instanceof List)
                    for (Object i : (List<Object>) m.get("ignore")) if (i instanceof String) u.ignore.add(((String) i).toLowerCase(Locale.ROOT));
            }
            System.out.println("social: " + users.size() + " players with friends from " + friendsFile);
        } catch (IOException | RuntimeException e) {
            System.out.println("social: cannot read " + friendsFile + " (" + e + "); starting empty, the file is kept until the next change");
        }
    }

    private void saveFriends() {
        Map<String, Object> us = new LinkedHashMap<>();
        for (Map.Entry<String, User> e : users.entrySet()) {
            User u = e.getValue();
            if (u.add.isEmpty() && u.ignore.isEmpty() && !u.share) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            if (u.disp != null) m.put("disp", u.disp);
            m.put("share", u.share);
            m.put("add", new LinkedHashMap<>(u.add));
            if (!u.ignore.isEmpty()) m.put("ignore", new ArrayList<>(u.ignore));
            us.put(e.getKey(), m);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("v", 1);
        root.put("users", us);
        writeAtomic(friendsFile, Json.write(root) + "\n");
    }

    private static void writeAtomic(Path file, String text) {
        try {
            Path dir = file.toAbsolutePath().getParent();
            if (dir != null) Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "." + file.getFileName(), ".tmp");
            try {
                try { Files.setPosixFilePermissions(tmp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
                catch (UnsupportedOperationException ignored) { }
                Files.writeString(tmp, text, StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(tmp); }
        } catch (IOException e) {
            System.out.println("social: cannot save " + file + ": " + e);
        }
    }

    // ================================================================ reports

    /** 0 if allowed (and recorded), else ms to wait. */
    private synchronized long admitReport(String reporter, String ip, String target, long now) {
        if (byKey.size() > 20_000) byKey.values().removeIf(q -> q.isEmpty() || now - q.peekLast() > HOUR);
        if (pairs.size() > 20_000) pairs.values().removeIf(t -> now - t > 6 * HOUR);
        ArrayDeque<Long> qn = byKey.computeIfAbsent("n:" + reporter, k -> new ArrayDeque<>());
        ArrayDeque<Long> qi = byKey.computeIfAbsent("i:" + ip, k -> new ArrayDeque<>());
        for (ArrayDeque<Long> q : List.of(qn, qi, global)) while (!q.isEmpty() && now - q.peekFirst() > HOUR) q.removeFirst();
        Long last = pairs.get(reporter + "|" + target);
        if (last != null && now - last < 6 * HOUR) return -(6 * HOUR - (now - last));   // negative: already reported
        if (!qn.isEmpty() && now - qn.peekLast() < gapMs) return gapMs - (now - qn.peekLast());
        if (qn.size() >= 10) return HOUR - (now - qn.peekFirst());
        if (qi.size() >= 15) return HOUR - (now - qi.peekFirst());
        if (global.size() >= 300) return HOUR - (now - global.peekFirst());
        qn.addLast(now); qi.addLast(now); global.addLast(now);
        pairs.put(reporter + "|" + target, now);
        return 0;
    }

    private void report(HttpExchange ex, Map<String, Object> b, String ip) throws IOException {
        String name = signedIn(ex, b);
        if (name == null) return;
        String target = s(b, "target"), reason = s(b, "reason");
        if (target == null || !ChatService.NAME_RE.matcher(target).matches()) { ApiGuard.reply(ex, 400, ApiGuard.error("bad target")); return; }
        if (target.equalsIgnoreCase(name)) { ApiGuard.reply(ex, 400, ApiGuard.error("self")); return; }
        if (reason == null || !REASONS.contains(reason)) { ApiGuard.reply(ex, 400, ApiGuard.error("bad reason")); return; }
        if (!chat.accountExists(target)) { ApiGuard.reply(ex, 404, ApiGuard.error("unknown name")); return; }
        long now = System.currentTimeMillis();
        String me = name.toLowerCase(Locale.ROOT), tk = target.toLowerCase(Locale.ROOT);
        long wait = admitReport(me, ip, tk, now);
        if (wait != 0) {
            if (ChatFilter.instance != null) ChatFilter.instance.count("player_report_limited");
            ApiGuard.reply(ex, 429, err(wait < 0 ? "already reported" : "rate limited", Math.abs(wait)));
            return;
        }
        ChatFilter f = ChatFilter.instance;
        String note = ChatService.clean(s(b, "note"));
        if (f != null) note = f.sanitize(note);
        note = Reports.cut(note, MAX_NOTE).trim();
        List<Object> seen = new ArrayList<>();
        if (b.get("seen") instanceof List)
            for (Object o : (List<?>) b.get("seen")) {
                if (seen.size() >= MAX_SEEN) break;
                String text = o instanceof String ? (String) o : o instanceof Map ? s(cast(o), "text") : null;
                if (text == null) continue;
                text = ChatService.clean(text);
                if (f != null) text = f.sanitize(text);
                text = Reports.cut(text, MAX_LINE);
                if (text.isEmpty()) continue;
                Map<String, Object> line = new LinkedHashMap<>();
                long t = o instanceof Map ? l(cast(o).get("t")) : 0;
                if (t > 0) line.put("t", t);
                line.put("text", text);
                seen.add(line);
            }
        List<Map<String, Object>> server = chat.messagesBy(target, MAX_SEEN);
        for (Map<String, Object> m : server) m.remove("id");
        Map<String, Object> r = new LinkedHashMap<>();
        synchronized (this) {
            r.put("id", nextId++);
            r.put("t", now);
            r.put("target", target);
            r.put("reason", reason);
            if (!note.isEmpty()) r.put("note", note);
            r.put("reporter", name);
            r.put("ip_ref", guard.ipRef(ip));
            String game = s(b, "game");
            if (game != null && game.matches("[a-z0-9_-]{1,24}")) r.put("game", game);
            r.put("server", server);
            r.put("seen", seen);
            r.put("done", false);
            reports.addLast(r);
            while (reports.size() > reportsMax) reports.removeFirst();
            appendReport(r);
        }
        Metrics.inc("player_report", reason);
        System.out.println("api: player report #" + r.get("id") + " (" + reason + ", " + server.size() + " server lines, " + seen.size() + " seen)");
        notifyStaff(r);
        Map<String, Object> o = ok();
        o.put("id", r.get("id"));
        ApiGuard.reply(ex, 200, o);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) { return (Map<String, Object>) o; }

    @SuppressWarnings("unchecked")
    private void loadReports() {
        try {
            if (!Files.isRegularFile(reportsFile)) return;
            for (String line : Files.readAllLines(reportsFile, StandardCharsets.UTF_8)) {
                fileLines++;
                try {
                    Object o = Metrics.MiniJson.parse(line, 1200, 64, 64);
                    if (!(o instanceof Map)) continue;
                    Map<String, Object> m = (Map<String, Object>) o;
                    for (Map.Entry<String, Object> e : m.entrySet()) if (e.getValue() instanceof Double) e.setValue(((Double) e.getValue()).longValue());
                    if (m.get("done_of") != null) {   // a later "done" line of an earlier report
                        long id = l(m.get("done_of"));
                        for (Map<String, Object> x : reports) if (l(x.get("id")) == id) x.put("done", Boolean.TRUE.equals(m.get("done")));
                        continue;
                    }
                    reports.addLast(m);
                    nextId = Math.max(nextId, l(m.get("id")) + 1);
                } catch (RuntimeException ignored) { }
            }
            while (reports.size() > reportsMax) reports.removeFirst();
        } catch (IOException e) {
            System.out.println("social: cannot read " + reportsFile + ": " + e);
        }
    }

    /** Appends one line; when the file holds 25 % more lines than kept it is rewritten. */
    private void appendReport(Map<String, Object> line) {
        try {
            if (fileLines + 1 > reportsMax + reportsMax / 4) {
                StringBuilder sb = new StringBuilder();
                for (Map<String, Object> m : reports) sb.append(Json.write(m)).append('\n');
                writeAtomic(reportsFile, sb.toString());
                fileLines = reports.size();
                return;
            }
            Path dir = reportsFile.toAbsolutePath().getParent();
            if (dir != null) Files.createDirectories(dir);
            boolean fresh = !Files.exists(reportsFile);
            Files.writeString(reportsFile, Json.write(line) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (fresh) try { Files.setPosixFilePermissions(reportsFile, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
                       catch (UnsupportedOperationException ignored) { }
            fileLines++;
        } catch (IOException e) {
            System.out.println("social: cannot save " + reportsFile + ": " + e);
        }
    }

    /** GET/POST /api/admin/player-reports (admin token). */
    public void admin(HttpExchange ex) throws IOException {
        String ip = guard.clientIp(ex);
        if (!guard.take("admin-ip:" + ip, 2, 20)) { ApiGuard.reply(ex, 429, ApiGuard.error("rate limited")); return; }
        if (!adminOk(ex)) { System.out.println("api: admin player reports: bad token"); ApiGuard.reply(ex, 403, ApiGuard.error("forbidden")); return; }
        if ("POST".equals(ex.getRequestMethod())) {
            Map<String, Object> b = obj(guard.body(ex));
            if (b == null || !(b.get("id") instanceof Number)) { ApiGuard.reply(ex, 400, ApiGuard.error("id")); return; }
            long id = l(b.get("id"));
            boolean done = Boolean.TRUE.equals(b.get("done"));
            synchronized (this) {
                Map<String, Object> hit = null;
                for (Map<String, Object> m : reports) if (l(m.get("id")) == id) hit = m;
                if (hit == null) { ApiGuard.reply(ex, 404, ApiGuard.error("unknown id")); return; }
                hit.put("done", done);
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("done_of", id);
                line.put("done", done);
                line.put("t", System.currentTimeMillis());
                appendReport(line);
            }
            ApiGuard.reply(ex, 200, ok());
            return;
        }
        if (!"GET".equals(ex.getRequestMethod())) { ApiGuard.reply(ex, 405, ApiGuard.error("use GET or POST")); return; }
        int limit = 300;
        String q = ex.getRequestURI().getRawQuery();
        if (q != null && q.matches("limit=\\d{1,4}")) limit = Math.max(1, Math.min(2000, Integer.parseInt(q.substring(6))));
        long now = System.currentTimeMillis();
        Map<String, Integer> day = new HashMap<>();
        List<Object> out = new ArrayList<>();
        int open = 0;
        synchronized (this) {
            for (Map<String, Object> m : reports) {
                if (now - l(m.get("t")) <= 24 * HOUR) day.merge(String.valueOf(m.get("target")).toLowerCase(Locale.ROOT), 1, Integer::sum);
                if (!Boolean.TRUE.equals(m.get("done"))) open++;
            }
            for (Iterator<Map<String, Object>> it = reports.descendingIterator(); it.hasNext() && out.size() < limit; ) {
                Map<String, Object> m = new LinkedHashMap<>(it.next());
                m.put("target_24h", day.getOrDefault(String.valueOf(m.get("target")).toLowerCase(Locale.ROOT), 0));
                out.add(m);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", reports.size());
        m.put("open", open);
        m.put("reports", out);
        ApiGuard.reply(ex, 200, m);
    }

    // ================================================================ Discord webhook

    /** REO_REPORT_WEBHOOK from the environment, else from the alerts env file (re-read when it changes). */
    synchronized String webhook() {
        String env = System.getenv("REO_REPORT_WEBHOOK");
        if (env != null && !env.isBlank()) return valid(env.trim());
        Path p = Path.of(alertsFile);
        long stamp;
        try { stamp = Files.isReadable(p) ? Files.getLastModifiedTime(p).toMillis() : -1; } catch (IOException e) { stamp = -1; }
        if (stamp != hookStamp) {
            hookStamp = stamp;
            hookCache = null;
            if (stamp >= 0) try {
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    line = line.trim();
                    if (line.startsWith("export ")) line = line.substring(7).trim();
                    if (!line.startsWith("REO_REPORT_WEBHOOK=")) continue;
                    String v = line.substring(19).trim();
                    if (v.length() >= 2 && (v.charAt(0) == '"' || v.charAt(0) == '\'') && v.charAt(v.length() - 1) == v.charAt(0)) v = v.substring(1, v.length() - 1);
                    hookCache = valid(v);
                }
            } catch (IOException e) { System.out.println("social: cannot read " + p + ": " + e); }
        }
        return hookCache;
    }

    /** Only Discord webhooks (or a loopback address, for the tests). */
    static String valid(String u) {
        if (u.matches("https://(discord\\.com|discordapp\\.com|ptb\\.discord\\.com|canary\\.discord\\.com)/api/webhooks/[0-9]+/[A-Za-z0-9_-]+")) return u;
        if (u.matches("http://127\\.0\\.0\\.1:[0-9]{2,5}/[A-Za-z0-9/_-]*")) return u;
        return null;
    }

    /** Discord markdown and mentions out: the text is shown as typed, never pings anyone. */
    static String plain(String s, int max) {
        s = Reports.cut(ChatService.clean(s == null ? "" : s), max);
        return s.replaceAll("([\\\\*_~`|>#\\[\\]])", "\\\\$1").replace("@", "@​").replace("://", ":​//");
    }

    private void notifyStaff(Map<String, Object> r) {
        String url = webhook();
        if (url == null) return;
        long now = System.currentTimeMillis();
        synchronized (hookTimes) {
            while (!hookTimes.isEmpty() && now - hookTimes.peekFirst() > HOUR) hookTimes.removeFirst();
            if (hookTimes.size() >= 30) return;   // a flood: the queue in /admin has them all
            hookTimes.addLast(now);
        }
        String extract = null;
        @SuppressWarnings("unchecked") List<Map<String, Object>> sv = (List<Map<String, Object>>) r.get("server");
        @SuppressWarnings("unchecked") List<Map<String, Object>> se = (List<Map<String, Object>>) (List<?>) r.get("seen");
        if (sv != null && !sv.isEmpty()) extract = String.valueOf(sv.get(0).get("text"));
        else if (se != null && !se.isEmpty()) extract = String.valueOf(se.get(0).get("text"));
        Map<String, String> label = Map.of("insult", "insultos / insults", "spam", "spam", "cheat", "trampas / cheating",
                "name", "nombre inapropiado / inappropriate name", "other", "otro / other");
        StringBuilder msg = new StringBuilder("Reporte de jugador / Player report #").append(r.get("id")).append(": **")
                .append(plain(String.valueOf(r.get("target")), 15)).append("** - ").append(label.get(String.valueOf(r.get("reason"))));
        if (extract != null) msg.append("\n> ").append(plain(extract, 120));
        if (!publicUrl.isEmpty()) msg.append("\n").append(publicUrl).append("/admin");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", msg.toString());
        Map<String, Object> am = new LinkedHashMap<>();
        am.put("parse", new ArrayList<>());
        body.put("allowed_mentions", am);
        String json = Json.write(body);
        hookPool.submit(() -> {
            try {
                HttpResponse<Void> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                        HttpResponse.BodyHandlers.discarding());
                if (res.statusCode() >= 300) System.out.println("social: reports webhook answered " + res.statusCode());
            } catch (Exception e) {
                System.out.println("social: reports webhook failed: " + e.getClass().getSimpleName());
            }
        });
    }
}
