/*
    reo-web bioserver - fan server for Resident Evil Outbreak File #1/#2
    (C) 2026 reo-web contributors. AGPL-3.0-or-later, see LICENSE.txt.
*/

package reo.common;

import com.sun.net.httpserver.HttpExchange;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Owner's metrics panel: small aggregated counters, no IP addresses stored anywhere.
 *
 *   POST /api/metrics/event   {"vid":"<16 hex, random per day>", "page":"picker|file1|file2", "lang":"es", "br":"chrome",
 *                              "mob":false, "gpu":true, "ev":[{"t":"page_view"}, {"t":"start_online","game":"file1","scale":"2"},
 *                              {"t":"session_end","game":"file1","dur":600,"online":true}, {"t":"net_error","kind":"busy"}]}
 *                             whitelisted types and values, at most 20 events, ApiGuard body limit, per-address rate limit
 *                             (the address is only used in memory for that). Country: CF-IPCountry header (Cloudflare), else XX.
 *   GET  /api/admin/metrics?range=today|7d|30d|90d   Authorization: Bearer <REO_ADMIN_TOKEN>, or X-Admin-Token: <token>
 *
 * Server side: the US lobby snapshots (the same data as /api/rooms) are sampled every 2 s: logins, players, rooms
 * created, games started (game, scenario, players) and ended (START until the room is back to waiting or gone),
 * peak concurrent players, dropped sessions; plus new accounts and API errors from HttpApi.
 *
 * Storage (metrics.dir, default data/metrics): daily.tsv (aggregates, kept forever), hourly.tsv (kept metrics.keep_days,
 * default 90) and events-YYYY-MM-DD.jsonl (detail, kept metrics.keep_days). Days use metrics.tz (default: system zone).
 * Keys are "metric|dimension"; metrics starting with "peak" keep the maximum, the rest add up.
 */
public final class Metrics {
    public static volatile Metrics instance;

    private static final Pattern VID = Pattern.compile("[0-9a-f]{16}");
    private static final Set<String> PAGES = Set.of("picker", "file1", "file2");
    private static final Set<String> LANGS = Set.of("es", "en", "pt", "fr", "de", "it", "fil");
    private static final Set<String> BROWSERS = Set.of("chrome", "edge", "firefox", "safari", "opera", "samsung", "other");
    private static final Set<String> GAMES = Set.of("file1", "file2");
    private static final Set<String> SCALES = Set.of("1", "2", "3");
    private static final Set<String> NET_ERRORS = Set.of("busy", "off", "refused");
    static final String[] F1_SCENARIOS = { "Outbreak", "Below Freezing Point", "The Hive", "Hellfire", "Decisions, Decisions" };
    static final String[] F2_SCENARIOS = { "Wild Things", "Underbelly", "Flashback", "Desperate Times", "End of the Road",
            "Elimination 1", "Elimination 2", "Elimination 3", "Showdown 1", "Showdown 2", "Showdown 3" };
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH");

    private final Path dir;
    private final ZoneId zone;
    private final int keepDays;
    private final ApiGuard guard;
    private final String adminToken;
    private final Map<String, Long> daily = new ConcurrentHashMap<>();    // "yyyy-MM-dd\tmetric|dim" -> n
    private final Map<String, Long> hourly = new ConcurrentHashMap<>();   // "yyyy-MM-ddTHH\tmetric|dim" -> n
    private final Object dayLock = new Object();
    private String today = "";
    private final Map<String, Integer> visitorsToday = new HashMap<>();   // daily random id -> events today (cap)
    private final Set<String> playersToday = new HashSet<>();             // game|lower(name), memory only
    private BufferedWriter detail;
    private volatile boolean dirty;

    // sampler state
    private boolean baseline;
    private final Map<String, Set<String>> prevNames = new HashMap<>();
    private final Map<String, String> prevNameRoom = new HashMap<>();   // game|name -> room key it was in (in game)
    private final Map<String, RoomState> prevRooms = new HashMap<>();
    private final Map<String, Long> prevDrops = new HashMap<>();
    private final Map<String, Integer> nowPlayers = new ConcurrentHashMap<>();

    private static final class RoomState {
        String game; boolean inGame; long started; int players; int scenario;
    }

    public Metrics(ServerConfig cfg, ApiGuard guard, String adminToken) {
        this.dir = Path.of(cfg.get("metrics.dir", "data/metrics"));
        String tz = cfg.get("metrics.tz", "");
        this.zone = tz.isBlank() ? ZoneId.systemDefault() : ZoneId.of(tz.trim());
        this.keepDays = cfg.getInt("metrics.keep_days", 90);
        this.guard = guard;
        this.adminToken = adminToken;
    }

    public void start() {
        try {
            Files.createDirectories(dir);
            load(dir.resolve("daily.tsv"), daily);
            load(dir.resolve("hourly.tsv"), hourly);
            rollDay();
        } catch (IOException e) {
            System.out.println("metrics: " + e);
        }
        instance = this;
        Thread t = new Thread(() -> {
            long lastFlush = System.currentTimeMillis();
            while (true) {
                try {
                    Thread.sleep(2000);
                    sample();
                    if (System.currentTimeMillis() - lastFlush > 60_000) { flush(); lastFlush = System.currentTimeMillis(); }
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    System.out.println("metrics: sample: " + e);
                }
            }
        }, "metrics");
        t.setDaemon(true);
        t.start();
        Runtime.getRuntime().addShutdownHook(new Thread(this::flush));
        System.out.println("metrics: " + dir.toAbsolutePath() + " (zone " + zone + ", detail kept " + keepDays + " days)");
    }

    // ---- counters ------------------------------------------------------------------------------

    private String day() { return LocalDate.now(zone).toString(); }

    /** Adds n to metric|dim for today and this hour (peak* metrics keep the maximum). */
    public void count(String metric, String dim, long n) {
        rollDay();
        ZonedDateTime now = ZonedDateTime.now(zone);
        String key = metric + "|" + dim;
        boolean peak = metric.startsWith("peak");
        merge(daily, now.toLocalDate() + "\t" + key, n, peak);
        merge(hourly, now.format(HOUR) + "\t" + key, n, peak);
        dirty = true;
    }

    public void count(String metric, String dim) { count(metric, dim, 1); }

    public static void inc(String metric, String dim) {
        Metrics m = instance;
        if (m != null) m.count(metric, dim, 1);
    }

    /** One game's lag (KkService.gameStats, from the gateway round trips): estimated one-way delay of the worst pair
     *  of members, their largest round trip and jitter, ms. Counters lag_n / lag_ms (sum) / lag_b (bucket) per game,
     *  plus a "game_lag" detail event. */
    public static void gameLag(String game, int members, long oneway, long maxRtt, long jitter, long secs) {
        Metrics m = instance;
        if (m == null) return;
        m.count("lag_n", game);
        m.count("lag_ms", game, oneway);
        m.count("lag_b", lagBucket(oneway));
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("t", "game_lag"); d.put("game", game); d.put("players", members); d.put("oneway", oneway);
        d.put("rtt_max", maxRtt); d.put("jitter", jitter); d.put("dur", secs);
        m.detail(d);
    }

    static String lagBucket(long ms) {
        return ms < 50 ? "a <50 ms" : ms < 100 ? "b 50-100 ms" : ms < 150 ? "c 100-150 ms" : ms < 250 ? "d 150-250 ms" : "e >250 ms";
    }

    private static void merge(Map<String, Long> map, String k, long n, boolean peak) {
        if (peak) map.merge(k, n, Math::max); else map.merge(k, n, Long::sum);
    }

    /** New day: reset the per-day sets, open the day's detail file, apply retention. */
    private void rollDay() {
        String d = day();
        if (d.equals(today)) return;
        synchronized (dayLock) {
            if (d.equals(today)) return;
            today = d;
            visitorsToday.clear();
            playersToday.clear();
            try {
                if (detail != null) detail.close();
                Path f = dir.resolve("events-" + d + ".jsonl");
                if (Files.exists(f))   // restart during the day: the visitor ids seen today come back from the detail
                    for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                        java.util.regex.Matcher m = Pattern.compile("\"vid\":\"([0-9a-f]{16})\"").matcher(line);
                        if (m.find()) visitorsToday.merge(m.group(1), 1, Integer::sum);
                    }
                detail = Files.newBufferedWriter(f, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                String cutoff = LocalDate.now(zone).minusDays(keepDays).toString();
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "events-*.jsonl")) {
                    for (Path p : ds) {
                        String n = p.getFileName().toString();
                        if (n.substring(7, 17).compareTo(cutoff) < 0) Files.deleteIfExists(p);
                    }
                }
                hourly.keySet().removeIf(k -> k.substring(0, 10).compareTo(cutoff) < 0);
            } catch (IOException e) {
                System.out.println("metrics: day " + d + ": " + e);
            }
        }
    }

    private void detail(Map<String, Object> ev) {
        ev.put("ts", Instant.now().getEpochSecond());
        synchronized (dayLock) {
            try { if (detail != null) { detail.write(Json.write(ev)); detail.newLine(); } } catch (IOException ignored) { }
        }
    }

    public void flush() {
        synchronized (dayLock) {
            try { if (detail != null) detail.flush(); } catch (IOException ignored) { }
        }
        if (!dirty) return;
        dirty = false;
        try {
            save(dir.resolve("daily.tsv"), daily);
            save(dir.resolve("hourly.tsv"), hourly);
        } catch (IOException e) {
            System.out.println("metrics: save: " + e);
        }
    }

    private static void load(Path f, Map<String, Long> into) throws IOException {
        if (!Files.exists(f)) return;
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            int i = line.lastIndexOf('\t');
            if (i <= 0 || line.startsWith("#")) continue;
            try { into.put(line.substring(0, i), Long.parseLong(line.substring(i + 1))); } catch (NumberFormatException ignored) { }
        }
    }

    private static void save(Path f, Map<String, Long> from) throws IOException {
        StringBuilder sb = new StringBuilder("# period\tmetric|dim\tvalue\n");
        for (Map.Entry<String, Long> e : new TreeMap<>(from).entrySet()) sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.writeString(tmp, sb, StandardCharsets.UTF_8);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // ---- web events ------------------------------------------------------------------------------

    /** POST /api/metrics/event (called through HttpApi.safe: ApiGuard.admit already ran). */
    public void event(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { ApiGuard.reply(ex, 405, ApiGuard.error("use POST")); return; }
        if (!guard.take("metrics-ip:" + guard.clientIp(ex), 1, 20)) { ApiGuard.reply(ex, 429, ApiGuard.error("rate limited")); return; }
        Object parsed;
        try { parsed = MiniJson.parse(guard.body(ex)); } catch (RuntimeException e) { parsed = null; }
        if (!(parsed instanceof Map)) { ApiGuard.reply(ex, 400, ApiGuard.error("bad json")); return; }
        Map<?, ?> b = (Map<?, ?>) parsed;
        String vid = str(b.get("vid"));
        Object evs = b.get("ev");
        if (vid == null || !VID.matcher(vid).matches() || !(evs instanceof List) || ((List<?>) evs).size() > 20) {
            ApiGuard.reply(ex, 400, ApiGuard.error("bad event")); return;
        }
        String page = pick(b.get("page"), PAGES, "other"), lang = pick(b.get("lang"), LANGS, "other"),
               br = pick(b.get("br"), BROWSERS, "other");
        String cc = ex.getRequestHeaders().getFirst("CF-IPCountry");
        cc = cc != null && cc.trim().matches("[A-Z][A-Z0-9]") ? cc.trim() : "XX";
        rollDay();
        boolean firstToday;
        synchronized (dayLock) {
            int n = visitorsToday.getOrDefault(vid, 0);
            if (n >= 400) { ApiGuard.reply(ex, 429, ApiGuard.error("rate limited")); return; }   // per visitor and day
            firstToday = n == 0;
            if (visitorsToday.size() > 200_000 && firstToday) { ApiGuard.reply(ex, 429, ApiGuard.error("rate limited")); return; }
            visitorsToday.put(vid, n + ((List<?>) evs).size());
        }
        if (firstToday) {   // visitor dimensions, once per visitor and day
            count("uv", "all");
            count("uv_country", cc);
            count("uv_lang", lang);
            count("uv_browser", br);
            count("uv_mobile", Boolean.TRUE.equals(b.get("mob")) ? "mobile" : "desktop");
            if (b.get("gpu") instanceof Boolean) count("uv_webgpu", (Boolean) b.get("gpu") ? "yes" : "no");
        }
        int accepted = 0;
        for (Object o : (List<?>) evs) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> e = (Map<?, ?>) o;
            String t = str(e.get("t"));
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("t", t);
            d.put("vid", vid);
            d.put("page", page);
            if (t == null) continue;
            switch (t) {
                case "page_view":
                    count("pv", page);
                    count("pv_lang", lang);
                    count("pv_country", cc);
                    d.put("lang", lang); d.put("br", br); d.put("cc", cc);
                    break;
                case "start_offline": case "start_online": {
                    String game = pick(e.get("game"), GAMES, null), scale = pick(e.get("scale"), SCALES, null);
                    if (game == null) continue;
                    count("start", t.substring(6));
                    count("start_game", game);
                    count("start_mode", game + " " + t.substring(6));
                    if (scale != null) count("start_scale", scale);
                    d.put("game", game); d.put("scale", scale);
                    break;
                }
                case "session_end": {
                    String game = pick(e.get("game"), GAMES, null);
                    Object dur = e.get("dur");
                    if (game == null || !(dur instanceof Number)) continue;
                    long s = Math.max(0, Math.min(86_400, ((Number) dur).longValue()));
                    count("sess_n", game);
                    count("sess_s", game, s);
                    count("sess_b", bucket(s));
                    d.put("game", game); d.put("dur", s); d.put("online", Boolean.TRUE.equals(e.get("online")));
                    break;
                }
                case "perf": {
                    // every 60 s while a game runs (desync measurements, 23/10): emulation fps of that minute (average,
                    // 10th percentile, seconds under 50) and, online, the gateway link (frames in/out, longest gap
                    // between incoming frames, gaps over 250 ms)
                    String game = pick(e.get("game"), GAMES, null);
                    if (game == null || !(e.get("fps") instanceof Number)) continue;
                    long fps = clamp(e.get("fps"), 0, 120), p10 = clamp(e.get("fps_p10"), 0, 120), slow = clamp(e.get("slow"), 0, 60);
                    boolean online = Boolean.TRUE.equals(e.get("online"));
                    String fb = fps >= 58 ? "58+" : fps >= 50 ? "50-57" : fps >= 40 ? "40-49" : "<40";
                    count("perf_n", game);
                    count("perf_fps", fb);
                    count("perf_fps_cc", cc + " " + fb);
                    d.put("game", game); d.put("cc", cc); d.put("br", br); d.put("fps", fps); d.put("fps_p10", p10); d.put("slow", slow);
                    d.put("online", online);
                    if (online) {
                        long gap = clamp(e.get("gap"), 0, 600_000), gaps = clamp(e.get("gaps"), 0, 100_000);
                        String gb = gap < 100 ? "<100" : gap < 250 ? "100-249" : gap < 500 ? "250-499" : gap < 1000 ? "500-999" : "1000+";
                        count("perf_gap", gb);
                        count("perf_gap_cc", cc + " " + gb);
                        d.put("rx", clamp(e.get("rx"), 0, 10_000_000)); d.put("tx", clamp(e.get("tx"), 0, 10_000_000));
                        d.put("gap", gap); d.put("gaps", gaps);
                    }
                    break;
                }
                case "bg": {
                    // the page came back after >= 10 s hidden (page 478f215, network.md 16): how long, the emulation fps
                    // while hidden (a host frozen in a background tab stops arbitrating items and enemies), the frames
                    // received per second and whether it had reached the online lobby
                    String game = pick(e.get("game"), GAMES, null);
                    if (game == null || !(e.get("dur") instanceof Number) || !(e.get("fps") instanceof Number)) continue;
                    long dur = clamp(e.get("dur"), 0, 86_400), fps = clamp(e.get("fps"), 0, 120);
                    double rx = e.get("rx_s") instanceof Number ? Math.max(0, Math.min(1000, ((Number) e.get("rx_s")).doubleValue())) : 0;
                    boolean online = Boolean.TRUE.equals(e.get("online"));
                    String mode = online ? "online" : "offline";
                    String fb = fps >= 58 ? "58+" : fps >= 50 ? "50-57" : fps >= 40 ? "40-49" : "<40";
                    count("bg_n", game + " " + mode);
                    count("bg_s", game + " " + mode, dur);
                    count("bg_fps", mode + " " + fb);
                    d.put("game", game); d.put("cc", cc); d.put("br", br); d.put("dur", dur); d.put("fps", fps);
                    d.put("rx_s", Math.round(rx * 10) / 10.0); d.put("online", online);
                    break;
                }
                case "net_error": {
                    String kind = pick(e.get("kind"), NET_ERRORS, null);
                    if (kind == null) continue;
                    count("err", "web " + kind);
                    d.put("kind", kind);
                    break;
                }
                default:
                    continue;
            }
            accepted++;
            detail(d);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("accepted", accepted);
        ApiGuard.reply(ex, 200, r);
    }

    private static String str(Object o) { return o instanceof String ? (String) o : null; }
    private static long clamp(Object o, long lo, long hi) { return o instanceof Number ? Math.max(lo, Math.min(hi, ((Number) o).longValue())) : lo; }

    private static String pick(Object o, Set<String> allowed, String dflt) {
        String s = str(o);
        return s != null && allowed.contains(s) ? s : dflt;
    }

    static String bucket(long s) {
        return s < 300 ? "a <5 min" : s < 900 ? "b 5-15 min" : s < 1800 ? "c 15-30 min" : s < 3600 ? "d 30-60 min" : "e >60 min";
    }

    // ---- server sampler ------------------------------------------------------------------------

    static String scenarioName(String game, int i) {
        String[] t = game.startsWith("file2") ? F2_SCENARIOS : F1_SCENARIOS;
        return i >= 0 && i < t.length ? t[i] : "#" + i;
    }

    @SuppressWarnings("unchecked")
    private void sample() {
        long now = System.currentTimeMillis();
        int total = 0;
        Map<String, RoomState> rooms = new HashMap<>();
        for (reo.us.KkService kk : reo.us.KkService.instances()) {
            Map<String, Object> snap = kk.snapshot();
            String game = String.valueOf(snap.get("id"));
            Set<String> names = new HashSet<>();
            for (Object n : (List<Object>) snap.getOrDefault("lobby", List.of())) names.add(String.valueOf(n));
            Map<String, String> nameRoom = new HashMap<>();
            for (Object ao : (List<Object>) snap.getOrDefault("areas", List.of())) {
                Map<String, Object> a = (Map<String, Object>) ao;
                for (Object n : (List<Object>) a.getOrDefault("players", List.of())) names.add(String.valueOf(n));
                for (Object ro : (List<Object>) a.getOrDefault("rooms", List.of())) {
                    Map<String, Object> r = (Map<String, Object>) ro;
                    String key = game + "|" + r.get("id") + "|" + r.get("name");
                    RoomState st = new RoomState();
                    st.game = game;
                    st.inGame = "in_game".equals(r.get("state"));
                    List<Object> pl = (List<Object>) r.getOrDefault("players", List.of());
                    st.players = pl.size();
                    st.scenario = r.get("scenario") instanceof Number ? ((Number) r.get("scenario")).intValue() : -1;
                    for (Object n : pl) { names.add(String.valueOf(n)); if (st.inGame) nameRoom.put(game + "|" + n, key); }
                    rooms.put(key, st);
                }
            }
            int connected = names.size();
            if (snap.get("players_lobby") instanceof Number && snap.get("players_in_game") instanceof Number)
                connected = ((Number) snap.get("players_lobby")).intValue() + ((Number) snap.get("players_in_game")).intValue();
            nowPlayers.put(game, connected);
            total += connected;
            count("peak", game, connected);
            long drops = snap.get("drops") instanceof Number ? ((Number) snap.get("drops")).longValue() : 0;
            Set<String> prev = prevNames.getOrDefault(game, Set.of());
            if (baseline) {
                long dd = drops - prevDrops.getOrDefault(game, drops);
                if (dd > 0) count("err", game + " timeout", dd);
                for (String n : names) {
                    if (prev.contains(n)) continue;
                    count("login", game);
                    synchronized (dayLock) { if (playersToday.add(game + "|" + n.toLowerCase())) count("players", game); }
                }
                for (String n : prev) {   // left while its room was playing: a disconnect in game
                    if (names.contains(n)) continue;
                    String rk = prevNameRoom.get(game + "|" + n);
                    if (rk != null) count("err", game + " left in game");
                }
            }
            prevDrops.put(game, drops);
            prevNames.put(game, names);
            prevNameRoom.keySet().removeIf(k -> k.startsWith(game + "|"));
            prevNameRoom.putAll(nameRoom);
        }
        count("peak", "all", total);
        if (baseline) {
            for (Map.Entry<String, RoomState> e : rooms.entrySet()) {
                RoomState st = e.getValue(), old = prevRooms.get(e.getKey());
                if (old == null) count("room", st.game);
                if (st.inGame && (old == null || !old.inGame)) {
                    st.started = now;
                    count("gstart", st.game);
                    count("gstart_scn", st.game + " " + scenarioName(st.game, st.scenario));
                    count("gstart_pl", st.players + "P");
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("t", "game_start"); d.put("game", st.game); d.put("scenario", scenarioName(st.game, st.scenario)); d.put("players", st.players);
                    detail(d);
                } else if (st.inGame) {
                    st.started = old.started;
                    st.players = Math.max(st.players, old.players);
                } else if (old != null && old.inGame) {
                    gameEnd(old, now, "back to waiting");
                }
            }
            for (Map.Entry<String, RoomState> e : prevRooms.entrySet())
                if (!rooms.containsKey(e.getKey()) && e.getValue().inGame) gameEnd(e.getValue(), now, "room closed");
        } else {
            for (RoomState st : rooms.values()) if (st.inGame) st.started = now;
        }
        prevRooms.clear();
        prevRooms.putAll(rooms);
        baseline = true;
    }

    private void gameEnd(RoomState st, long now, String why) {
        if (st.started == 0) return;
        long s = (now - st.started) / 1000;
        count("gend", st.game);
        count("gdur_n", st.game);
        count("gdur_s", st.game, s);
        count("gdur_b", bucket(s));
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("t", "game_end"); d.put("game", st.game); d.put("scenario", scenarioName(st.game, st.scenario));
        d.put("players", st.players); d.put("dur", s); d.put("why", why);
        detail(d);
    }

    // ---- admin -----------------------------------------------------------------------------------

    /** GET /api/admin/metrics?range=today|7d|30d|90d (Bearer admin token). */
    public void admin(HttpExchange ex) throws IOException {
        String ip = guard.clientIp(ex);
        if (!"GET".equals(ex.getRequestMethod())) { ApiGuard.reply(ex, 405, ApiGuard.error("use GET")); return; }
        if (!guard.take("admin-ip:" + ip, 1, 10)) { ApiGuard.reply(ex, 429, ApiGuard.error("rate limited")); return; }
        // Bearer, or X-Admin-Token (the panel uses it, so that HTTP basic auth in front of /admin keeps the Authorization header)
        String auth = ex.getRequestHeaders().getFirst("Authorization"), x = ex.getRequestHeaders().getFirst("X-Admin-Token");
        String given = x != null ? x.trim() : auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : null;
        if (adminToken.isEmpty() || given == null || !ApiGuard.secretEquals(given, adminToken)) {
            System.out.println("api: admin metrics: bad token");
            ApiGuard.reply(ex, 403, ApiGuard.error("forbidden")); return;
        }
        String q = ex.getRequestURI().getRawQuery();
        String range = q != null && q.matches("range=(today|7d|30d|90d)") ? q.substring(6) : "7d";
        flush();
        List<String> periods = new ArrayList<>();
        Map<String, Long> src;
        ZonedDateTime now = ZonedDateTime.now(zone);
        if (range.equals("today")) {
            src = hourly;
            for (int h = 0; h <= now.getHour(); h++) periods.add(now.toLocalDate() + "T" + String.format("%02d", h));
        } else {
            src = daily;
            int n = Integer.parseInt(range.substring(0, range.length() - 1));
            for (int i = n - 1; i >= 0; i--) periods.add(now.toLocalDate().minusDays(i).toString());
        }
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < periods.size(); i++) idx.put(periods.get(i), i);
        Map<String, long[]> series = new TreeMap<>();
        Map<String, Long> totals = new TreeMap<>();
        for (Map.Entry<String, Long> e : src.entrySet()) {
            int t = e.getKey().indexOf('\t');
            Integer i = idx.get(e.getKey().substring(0, t));
            if (i == null) continue;
            String key = e.getKey().substring(t + 1);
            series.computeIfAbsent(key, k -> new long[periods.size()])[i] = e.getValue();
            totals.merge(key, e.getValue(), key.startsWith("peak") ? Math::max : Long::sum);
        }
        Map<String, Object> live = new LinkedHashMap<>();
        int rooms = 0, playing = 0;
        for (RoomState st : prevRooms.values()) { rooms++; if (st.inGame) playing++; }
        live.put("players", new TreeMap<>(nowPlayers));
        live.put("rooms", rooms);
        live.put("rooms_in_game", playing);
        live.put("accounts", reo.us.KkService.instance != null ? reo.us.KkService.accountCount() : 0);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("range", range);
        r.put("zone", zone.toString());
        r.put("generated", now.toOffsetDateTime().toString());
        r.put("live", live);
        r.put("periods", periods);
        r.put("totals", totals);
        Map<String, Object> s2 = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> e : series.entrySet()) {
            List<Long> l = new ArrayList<>();
            for (long v : e.getValue()) l.add(v);
            s2.put(e.getKey(), l);
        }
        r.put("series", s2);
        if (ChatFilter.instance != null) r.put("chat_filter", ChatFilter.instance.counters());   // since start
        ApiGuard.reply(ex, 200, r);
    }

    // ---- tiny JSON reader (objects, arrays, strings, numbers, booleans, null) --------------------

    static final class MiniJson {
        private final String s;
        private int i, depth, maxStr = 256, maxObj = 32, maxArr = 64;
        private MiniJson(String s) { this.s = s; }

        static Object parse(String s) { return parse(s, 256); }

        /** maxStr: longest string accepted (Social: report notes and chat lines). */
        static Object parse(String s, int maxStr) { return parse(s, maxStr, 32, 64); }

        /** maxObj/maxArr: most keys of an object / items of an array (Social: the friends file). */
        static Object parse(String s, int maxStr, int maxObj, int maxArr) {
            MiniJson p = new MiniJson(s);
            p.maxStr = maxStr;
            p.maxObj = maxObj;
            p.maxArr = maxArr;
            Object v = p.value();
            p.ws();
            if (p.i != s.length()) throw new IllegalArgumentException("trailing");
            return v;
        }

        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        private Object value() {
            ws();
            if (i >= s.length()) throw new IllegalArgumentException("eof");
            char c = s.charAt(i);
            if (c == '{' || c == '[') {
                if (++depth > 4) throw new IllegalArgumentException("deep");
                Object o = c == '{' ? obj() : arr();
                depth--;
                return o;
            }
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i || i - st > 20) throw new IllegalArgumentException("number");
            return Double.parseDouble(s.substring(st, i));
        }

        private Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                expect(':');
                m.put(k, value());
                if (m.size() > maxObj) throw new IllegalArgumentException("big");
                ws();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                expect('}');
                return m;
            }
        }

        private List<Object> arr() {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(value());
                if (l.size() > maxArr) throw new IllegalArgumentException("big");
                ws();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                expect(']');
                return l;
            }
        }

        private String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    if (i >= s.length()) break;
                    char e = s.charAt(i++);
                    if (e == 'u') {
                        if (i + 4 > s.length()) break;
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    } else b.append(e == 'n' ? '\n' : e == 't' ? '\t' : e == 'r' ? '\r' : e == 'b' ? '\b' : e == 'f' ? '\f' : e);
                } else b.append(c);
                if (b.length() > maxStr) throw new IllegalArgumentException("long");
            }
            throw new IllegalArgumentException("string");
        }

        private void expect(char c) {
            if (i >= s.length() || s.charAt(i) != c) throw new IllegalArgumentException("expected " + c);
            i++;
        }
    }
}
