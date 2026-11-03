/*
    reo-web bioserver - fan server for Resident Evil Outbreak File #1/#2
    (C) 2026 reo-web contributors. AGPL-3.0-or-later, see LICENSE.txt.
*/

package reo.common;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Website chat: three public channels by language ("community", rooms "es" | "en" | "pt") and one channel
 * per game room ("my game").
 *
 *   POST /api/chat/poll  {"room":"es"|"en"|"pt", "since":N, "room_since":N, "name":"..", "credential":"..",
 *                         "game_name":"..", "game_credential":".."}
 *        "room" = language room of the community channel; missing or unknown -> "es" (old pages).
 *        optional "v": the "v" of the previous answer; if nothing changed the answer is 204 with no body
 *        -> {"v":"hash", "last":N, "now":ms, "community_ttl":ms, "chat_room":"es", "community":[msg..] (only that
 *            language room), "online":n, "online_rooms":{"es":n,"en":n,"pt":n}, "me":"name"|null,
 *            "room":{"id":..,"title":..,"area":..,"players":[..]}|null, "room_last":N, "room_messages":[..],
 *            "room_reason":"no_name"|"bad_credential"|"not_in_room"|"no_lobby"}
 *        ("room" in the ANSWER is still the game room of "Mi partida", as before.) Message ids are global and
 *        increasing across every channel, so one "since" works when the page switches language room
 *        (send since 0 after switching to get the whole buffer of the new room).
 *        Reading the community channel needs no name; the room channel needs a valid name + credential.
 *   POST /api/chat/send  {"channel":"community"|"room", "room":"es"|"en"|"pt", "text":"..", "name":"..", "credential":"..",
 *                         "game_name":"..", "game_credential":".."}
 *        "room" only matters for channel "community" (missing/unknown -> "es").
 *        -> {"ok":true,"id":N,"text":"<stored text>"} | 400/401/403/429 {"error":..}
 *        errors: "bad credential" 401; "muted"|"banned" 403 (+"until"); "not in a room" 403; "empty", "too long",
 *        "bad channel", "too many links" 400; "rate limited", "repeated", "link flood" 429.
 * Automatic moderation (ChatFilter): odd characters cleaned, capitals folded, repeats and links limited,
 * owner's word list (data/badwords.txt) masked with ***.
 *
 * Identity: the player name the page registered with POST /api/kk/register; the credential is
 * checked against the same accounts file (us.kk.accounts_file, read only here, reloaded when it
 * changes). Which room a name is in comes from the kk lobby snapshot (the one /api/rooms shows).
 * game_name/game_credential (optional): the name the running game logged in with, when the player renamed
 * themselves on the page after starting; the room is looked up (and room messages are signed) with it.
 * Messages: plain text (control characters removed, at most 200 characters), never HTML: the page
 * inserts them with textContent. Kept in memory only, at most 100 per channel. Community messages last
 * api.chat.community_ttl_s seconds (default 120); a room channel ("room:<game>:<room id>") lasts while the
 * room exists in the lobby snapshot (at most 30 minutes); the page shows a room only from the moment it
 * sees the player inside it.
 * Rate limit: 1 message per second and 10 per 30 s per name, 1 per second and 20 per minute per
 * address, 20 per second overall; 5 polls per second per address. Banned or muted names and
 * addresses (ApiGuard, POST /api/admin) cannot send; blocklisted words are masked.
 */
public final class ChatService {
    static final Pattern NAME_RE = Pattern.compile("[A-Za-z0-9]{1,15}");
    static final Pattern CRED_RE = Pattern.compile("[0-9a-f]{12}");
    static final int MAX_TEXT = 200, KEEP = 100;
    static final long KEEP_MS = 30 * 60_000L, ONLINE_MS = 45_000L;   // pages with the chat pane closed poll every 30 s
    private final long communityTtlMs;
    private long lastRoomSweep = 0;

    private final Path accountsFile;
    private final Properties accounts = new Properties();
    private long accountsStamp = -1;
    private long nextId = 1;
    private final Map<String, ArrayDeque<Map<String, Object>>> channels = new LinkedHashMap<>();
    private final Map<String, long[]> sendRate = new ConcurrentHashMap<>();   // name -> [last, windowStart, count]
    private final Map<String, long[]> pollRate = new ConcurrentHashMap<>();   // ip -> [windowStart, count]
    private final Map<String, Long> seen = new ConcurrentHashMap<>();         // name or ip -> last poll
    private final Map<String, String> seenRoom = new ConcurrentHashMap<>();   // name or ip -> language room
    static final List<String> LANG_ROOMS = List.of("es", "en", "pt", "fil", "ja");   // fil, ja: many players from the Philippines and Japan (22-23/10)
    private final ChatFilter filter;
    private final String corsOrigin;
    // moderation (admin panel): the address of each stored message and of each signed-in name stay here, never in
    // an answer to the page; the panel gets opaque references (ApiGuard.ipRef). Deleted ids go to every page.
    private final Map<Long, String> msgIp = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, String> e) { return size() > 2000; }
    };
    private final Map<String, Object[]> webNames = new ConcurrentHashMap<>();  // lower name -> {name, ip, first, last}
    private final ArrayDeque<long[]> deleted = new ArrayDeque<>();             // {id, when}
    static final long DELETED_MS = 10 * 60_000L, WEB_SEEN_MS = 10 * 60_000L, AUTHOR_MS = 30 * 60_000L;
    // names that wrote in any channel in the last 30 min (moderation search): {name, ip, last, count}; no text
    private final Map<String, Object[]> authors = new ConcurrentHashMap<>();

    /** api.chat.owner_names (comma list, e.g. "admin"): messages of these accounts carry "owner":true and the page
     *  shows the name in red. The sender is checked by its credential, so a look-alike name never gets it. */
    private final java.util.Set<String> ownerNames = new java.util.HashSet<>();

    public ChatService(ServerConfig cfg) {
        for (String n : cfg.get("api.chat.owner_names", "").split(","))
            if (!n.isBlank()) ownerNames.add(n.trim().toLowerCase(Locale.ROOT));
        accountsFile = Path.of(cfg.get("us.kk.accounts_file", "data/kk-accounts.properties"));
        corsOrigin = cfg.get("api.cors_origin", "*");
        long ttl = 120;
        try { ttl = Long.parseLong(cfg.get("api.chat.community_ttl_s", "120").trim()); } catch (NumberFormatException ignored) { }
        communityTtlMs = Math.max(5, Math.min(ttl, 24 * 3600)) * 1000L;
        filter = ChatFilter.instance != null ? ChatFilter.instance : new ChatFilter(cfg);
    }

    /** Language room asked by the page: "es" | "en" | "pt"; anything else (or missing) is "es". */
    static String langRoom(String body) {
        String r = str(body, "room");
        if (r != null) r = r.trim().toLowerCase(Locale.ROOT);
        return r != null && LANG_ROOMS.contains(r) ? r : "es";
    }

    /** Channel key of a language room ("es" keeps the old key "community"). */
    static String communityKey(String lang) { return lang.equals("es") ? "community" : "community:" + lang; }

    static boolean isCommunity(String key) { return key.equals("community") || key.startsWith("community:"); }

    static String langOf(String key) { return key.startsWith("community:") ? key.substring(10) : "es"; }

    /** The name used for the room: the game login name when it is given and valid, else the page name. */
    private String roomName(String body, String name) {
        String g = str(body, "game_name");
        return g != null && checkCredential(g, str(body, "game_credential")) ? g : name;
    }

    // ---- HTTP ------------------------------------------------------------------------------

    public void handle(HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getPath();
            if ("OPTIONS".equals(ex.getRequestMethod())) {
                if (!corsOrigin.isEmpty()) {
                    ex.getResponseHeaders().set("Access-Control-Allow-Origin", corsOrigin);
                    ex.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
                    ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
                }
                ex.sendResponseHeaders(204, -1);
                return;
            }
            if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, err("method not allowed")); return; }
            String body;
            try (InputStream in = ex.getRequestBody()) { body = new String(in.readNBytes(2048), StandardCharsets.UTF_8); }
            String ip = clientIp(ex);
            prune();
            if (path.equals("/api/chat/poll")) poll(ex, body, ip);
            else if (path.equals("/api/chat/send")) sendMessage(ex, body, ip);
            else send(ex, 404, err("not found"));
        } catch (Exception e) {
            try { send(ex, 500, err(e.getClass().getSimpleName())); } catch (IOException ignored) { }
        } finally {
            ex.close();
        }
    }

    private long lastPrune = System.currentTimeMillis();

    /** Forget rate-limit entries nobody used for a minute (the maps would grow with every address). */
    private void prune() {
        long now = System.currentTimeMillis();
        if (now - lastPrune < 60_000) return;
        lastPrune = now;
        pollRate.values().removeIf(r -> now - r[0] > 60_000);
        sendRate.values().removeIf(r -> now - r[0] > 60_000 && now - r[1] > 60_000);
    }

    /** Removes every stored message of a name (moderation); returns how many. */
    public synchronized int purge(String name) {
        int n = 0;
        long now = System.currentTimeMillis();
        for (ArrayDeque<Map<String, Object>> q : channels.values())
            for (Iterator<Map<String, Object>> it = q.iterator(); it.hasNext(); ) {
                Map<String, Object> msg = it.next();
                if (name.equalsIgnoreCase(String.valueOf(msg.get("name")))) { it.remove(); markDeleted((Long) msg.get("id"), now); n++; }
            }
        return n;
    }

    /** Removes one message (moderation); every page drops it at its next poll. Returns the removed message or null. */
    public synchronized Map<String, Object> delete(long id) {
        for (ArrayDeque<Map<String, Object>> q : channels.values())
            for (Iterator<Map<String, Object>> it = q.iterator(); it.hasNext(); ) {
                Map<String, Object> msg = it.next();
                if ((Long) msg.get("id") == id) { it.remove(); markDeleted(id, System.currentTimeMillis()); return msg; }
            }
        return null;
    }

    private void markDeleted(long id, long now) {
        deleted.addLast(new long[] { id, now });
        while (deleted.size() > 500 || (!deleted.isEmpty() && now - deleted.peekFirst()[1] > DELETED_MS)) deleted.removeFirst();
    }

    private List<Object> deletedIds(long now) {
        List<Object> out = new ArrayList<>();
        for (long[] d : deleted) if (now - d[1] <= DELETED_MS) out.add(d[0]);
        return out;
    }

    /** Address of a stored message (moderation only), or null. */
    public synchronized String messageIp(long id) { return msgIp.get(id); }

    /** Address a signed-in name last used on the page (moderation only), or null. */
    public String nameIp(String name) {
        if (name == null) return null;
        Object[] w = webNames.get(name.toLowerCase(Locale.ROOT));
        if (w == null) w = authors.get(name.toLowerCase(Locale.ROOT));
        return w == null ? null : (String) w[1];
    }

    /** Names that wrote in the chat in the last 30 minutes: {name, last, count, ip_ref}; never the text. */
    public List<Map<String, Object>> recentAuthors(ApiGuard g) {
        long now = System.currentTimeMillis();
        authors.values().removeIf(a -> now - (Long) a[2] > AUTHOR_MS);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] a : authors.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", a[0]);
            m.put("last", a[2]);
            m.put("count", a[3]);
            if (g != null) m.put("ip_ref", g.ipRef((String) a[1]));
            out.add(m);
        }
        return out;
    }

    /** For the admin panel: every stored message of every channel, newest first, with its channel and an
     *  opaque reference of its address (never the address). */
    public synchronized List<Map<String, Object>> adminMessages(ApiGuard g) {
        expire(System.currentTimeMillis());
        Map<String, Map<String, Object>> rooms = new java.util.HashMap<>();
        List<Map<String, Object>> all = new ArrayList<>();
        for (Map.Entry<String, ArrayDeque<Map<String, Object>>> e : channels.entrySet()) {
            String ch = e.getKey();
            Map<String, Object> room = null;
            if (ch.startsWith("room:")) room = rooms.computeIfAbsent(ch, k -> roomByKey(k));
            for (Map<String, Object> msg : e.getValue()) {
                Map<String, Object> m = new LinkedHashMap<>(msg);
                m.put("channel", isCommunity(ch) ? "community" : "room");
                if (isCommunity(ch)) m.put("chat_room", langOf(ch));
                if (room != null) { m.put("room", room.get("title")); m.put("game", room.get("game")); m.put("area", room.get("area")); }
                else if (ch.startsWith("room:")) m.put("game", ch.split(":")[1]);
                String ip = msgIp.get((Long) msg.get("id"));
                if (g != null && ip != null) m.put("ip_ref", g.ipRef(ip));
                all.add(m);
            }
        }
        all.sort((a, b) -> Long.compare((Long) b.get("id"), (Long) a.get("id")));
        return all;
    }

    /** Last poll of a signed-in name on the page (ms), or 0 (social: friends' status). */
    public long webLast(String name) {
        Object[] w = name == null ? null : webNames.get(name.toLowerCase(Locale.ROOT));
        return w == null ? 0 : (Long) w[3];
    }

    /** The newest messages this name still has on the server (any channel), newest first: {t, text, channel}.
     *  Player reports attach them: unlike the lines the page sends, these are what the server really stored. */
    public synchronized List<Map<String, Object>> messagesBy(String name, int max) {
        expire(System.currentTimeMillis());
        List<Map<String, Object>> all = new ArrayList<>();
        for (Map.Entry<String, ArrayDeque<Map<String, Object>>> e : channels.entrySet())
            for (Map<String, Object> msg : e.getValue())
                if (name.equalsIgnoreCase(String.valueOf(msg.get("name")))) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", msg.get("id"));
                    m.put("t", msg.get("t"));
                    m.put("text", msg.get("text"));
                    m.put("channel", isCommunity(e.getKey()) ? "community" : "room");
                    all.add(m);
                }
        all.sort((a, b) -> Long.compare(((Number) b.get("id")).longValue(), ((Number) a.get("id")).longValue()));
        return all.size() > max ? new ArrayList<>(all.subList(0, max)) : all;
    }

    /** Room title/area/game of a channel key "room:<game>:<oid>", from the lobby snapshot. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> roomByKey(String key) {
        String[] p = key.split(":");
        for (reo.us.KkService kk : reo.us.KkService.instances()) {
            Map<String, Object> snap;
            try { snap = kk.snapshot(); } catch (RuntimeException e) { continue; }
            if (p.length < 3 || !p[1].equals(snap.get("id"))) continue;
            for (Object a : (List<Object>) snap.getOrDefault("areas", List.of()))
                for (Object ro : (List<Object>) ((Map<String, Object>) a).getOrDefault("rooms", List.of()))
                    if (p[2].equals(String.valueOf(((Map<String, Object>) ro).get("id")))) {
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("title", ((Map<String, Object>) ro).get("title"));
                        out.put("area", ((Map<String, Object>) a).get("name"));
                        out.put("game", p[1]);
                        return out;
                    }
        }
        return null;
    }

    /** Names signed in on the page in the last 10 minutes: {name, first, last, ip_ref}. */
    public List<Map<String, Object>> webPlayers(ApiGuard g) {
        long now = System.currentTimeMillis();
        webNames.values().removeIf(w -> now - (Long) w[3] > WEB_SEEN_MS);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] w : webNames.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", w[0]);
            m.put("first", w[2]);
            m.put("last", w[3]);
            if (g != null) m.put("ip_ref", g.ipRef((String) w[1]));
            out.add(m);
        }
        return out;
    }

    private void sawName(String name, String ip, long now) {
        Object[] w = webNames.get(name.toLowerCase(Locale.ROOT));
        if (w == null) {
            if (webNames.size() > 5000) webNames.values().removeIf(x -> now - (Long) x[3] > WEB_SEEN_MS);
            webNames.put(name.toLowerCase(Locale.ROOT), new Object[] { name, ip, now, now });
        } else { w[1] = ip; w[3] = now; }
    }

    private static String clientIp(HttpExchange ex) {
        ApiGuard g = ApiGuard.instance;
        if (g != null) return g.clientIp(ex);
        String ip = ex.getRemoteAddress().getAddress().getHostAddress();
        String fwd = ex.getRequestHeaders().getFirst("X-Forwarded-For");
        if (fwd != null && ex.getRemoteAddress().getAddress().isLoopbackAddress()) ip = fwd.split(",")[0].trim();
        return ip;
    }

    private void poll(HttpExchange ex, String body, String ip) throws Exception {
        long now = System.currentTimeMillis();
        long[] r = pollRate.computeIfAbsent(ip, k -> new long[2]);
        synchronized (r) {
            if (now - r[0] > 1000) { r[0] = now; r[1] = 0; }
            if (++r[1] > 5) { send(ex, 429, err("rate limited")); return; }
        }
        long since = num(body, "since"), roomSince = num(body, "room_since");
        String name = str(body, "name"), cred = str(body, "credential");
        boolean authed = name != null && checkCredential(name, cred);
        String lang = langRoom(body);
        String who = authed ? "n:" + name.toLowerCase(Locale.ROOT) : "ip:" + ip;
        seen.put(who, now);
        seenRoom.put(who, lang);
        if (authed) sawName(name, ip, now);
        Map<String, Object> m = new LinkedHashMap<>();
        synchronized (this) {
            expire(now);
            m.put("last", nextId - 1);
            m.put("now", now);
            m.put("community_ttl", communityTtlMs);
            m.put("chat_room", lang);
            m.put("community", since(channels.get(communityKey(lang)), since));
            m.put("deleted", deletedIds(now));
        }
        m.put("online", online(now));
        Map<String, Object> byRoom = new LinkedHashMap<>();
        for (String l : LANG_ROOMS) byRoom.put(l, 0);
        for (String k : seen.keySet()) { String l = seenRoom.get(k); if (l != null) byRoom.merge(l, 1, (a, b) -> (Integer) a + (Integer) b); }
        m.put("online_rooms", byRoom);
        m.put("me", authed ? name : null);
        if (authed) { Map<String, Object> sanction = sanction(name, ip); if (sanction != null) m.put("sanction", sanction); }
        Map<String, Object> room = null;
        String reason = null;
        if (name == null) reason = "no_name";
        else if (!authed) reason = "bad_credential";
        else {
            room = roomOf(roomName(body, name));
            if (room == null) reason = reo.us.KkService.instances().isEmpty() ? "no_lobby" : "not_in_room";
        }
        m.put("room", room);
        if (room != null) {
            synchronized (this) {
                m.put("room_last", nextId - 1);
                m.put("room_messages", since(channels.get(roomKey(room)), roomSince));
            }
        } else {
            m.put("room_reason", reason);
        }
        // "v": a hash of everything but "now". A page that sends back the same "v" gets 204 and no body
        // (nothing new); pages that do not send it get the full answer as before.
        Map<String, Object> same = new LinkedHashMap<>(m);
        same.remove("now");
        String v = version(Json.write(same));
        if (v.equals(str(body, "v"))) {
            ApiGuard.securityHeaders(ex.getResponseHeaders());
            if (!corsOrigin.isEmpty()) ex.getResponseHeaders().set("Access-Control-Allow-Origin", corsOrigin);
            ex.sendResponseHeaders(204, -1);
            return;
        }
        m.put("v", v);
        send(ex, 200, m);
    }

    private static String version(String json) throws Exception {
        byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 8; i++) b.append(String.format("%02x", d[i]));
        return b.toString();
    }

    private void sendMessage(HttpExchange ex, String body, String ip) throws Exception {
        String name = str(body, "name"), cred = str(body, "credential"), channel = str(body, "channel");
        String text = filter.sanitize(clean(str(body, "text")));
        if (name == null || !checkCredential(name, cred)) { send(ex, 401, err("bad credential")); return; }
        ApiGuard g = ApiGuard.instance;
        sawName(name, ip, System.currentTimeMillis());
        Map<String, Object> sanction = sanction(name, ip);
        if (sanction != null) {   // {"error":"muted"|"banned","until":ms (0 = permanent)}: the page says until when
            Map<String, Object> e = err(String.valueOf(sanction.get("kind")).equals("ban") ? "banned" : "muted");
            e.put("until", sanction.get("until"));
            send(ex, 403, e);
            return;
        }
        // per address (many names from one address) and overall (many addresses)
        if (g != null && (!g.take("chat-ip:" + ip, 1.0 / 3, 5) || !g.take("chat-global", 20, 40))) {
            filter.count("rate_limited");
            send(ex, 429, err("rate limited")); return;
        }
        if (text.isEmpty()) { send(ex, 400, err("empty")); return; }
        if (text.codePointCount(0, text.length()) > MAX_TEXT) { filter.count("too_long"); send(ex, 400, err("too long")); return; }
        String key, author = name;
        if ("community".equals(channel)) key = communityKey(langRoom(body));
        else if ("room".equals(channel)) {
            author = roomName(body, name);
            Map<String, Object> room = roomOf(author);
            if (room == null) { send(ex, 403, err("not in a room")); return; }
            key = roomKey(room);
        } else { send(ex, 400, err("bad channel")); return; }
        long now = System.currentTimeMillis();
        long[] r = sendRate.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new long[3]);
        synchronized (r) {
            if (now - r[0] < 1000) { filter.count("rate_limited"); send(ex, 429, err("rate limited")); return; }
            if (now - r[1] > 30_000) { r[1] = now; r[2] = 0; }
            if (r[2] >= 10) { filter.count("rate_limited"); send(ex, 429, err("rate limited")); return; }
            r[0] = now;
            r[2]++;
        }
        String spam = filter.check(name, ip, text);
        if (spam != null) { send(ex, "too many links".equals(spam) ? 400 : 429, err(spam)); return; }
        String stored = filter.maskBadwords(ApiGuard.maskText(text));
        long id;
        synchronized (this) {
            expire(now);
            id = nextId++;
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("id", id);
            msg.put("t", now);
            msg.put("name", author);
            msg.put("text", stored);
            if (ownerNames.contains(author.toLowerCase(Locale.ROOT))) msg.put("owner", true);
            ArrayDeque<Map<String, Object>> q = channels.computeIfAbsent(key, k -> new ArrayDeque<>());
            q.addLast(msg);
            msgIp.put(id, ip);
            while (q.size() > KEEP) q.removeFirst();
            Object[] a = authors.get(author.toLowerCase(Locale.ROOT));
            if (a == null) {
                if (authors.size() > 5000) authors.values().removeIf(x -> now - (Long) x[2] > AUTHOR_MS);
                authors.put(author.toLowerCase(Locale.ROOT), new Object[] { author, ip, now, 1 });
            } else { a[1] = ip; a[2] = now; a[3] = (Integer) a[3] + 1; }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("id", id);
        m.put("text", stored);
        send(ex, 200, m);
    }

    /** {"kind":"ban"|"mute","until":ms (0 = permanent)} when the name or the address may not write, else null. */
    private static Map<String, Object> sanction(String name, String ip) {
        ApiGuard g = ApiGuard.instance;
        if (g == null) return null;
        long ban = g.sanctionUntil("ban", name, ip), mute = ban >= 0 ? -1 : g.sanctionUntil("mute", name, ip);
        if (ban < 0 && mute < 0) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", ban >= 0 ? "ban" : "mute");
        m.put("until", ban >= 0 ? ban : mute);
        return m;
    }

    // ---- state -----------------------------------------------------------------------------

    private static List<Object> since(ArrayDeque<Map<String, Object>> q, long since) {
        List<Object> out = new ArrayList<>();
        if (q == null) return out;
        for (Map<String, Object> msg : q) if ((Long) msg.get("id") > since) out.add(msg);
        return out;
    }

    static String roomKey(Map<String, Object> room) { return "room:" + room.get("game") + ":" + room.get("id"); }

    /** Community messages older than the TTL go; room channels go with their room (checked every 5 s). */
    private void expire(long now) {
        java.util.Set<String> live = null;
        if (now - lastRoomSweep > 5000) {
            lastRoomSweep = now;
            live = liveRooms();
        }
        for (Iterator<Map.Entry<String, ArrayDeque<Map<String, Object>>>> it = channels.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, ArrayDeque<Map<String, Object>>> e = it.next();
            ArrayDeque<Map<String, Object>> q = e.getValue();
            long keep = isCommunity(e.getKey()) ? communityTtlMs : KEEP_MS;
            while (!q.isEmpty() && now - (Long) q.peekFirst().get("t") > keep) q.removeFirst();
            if (q.isEmpty() || (live != null && e.getKey().startsWith("room:") && !live.contains(e.getKey()))) it.remove();
        }
    }

    /** Channel keys of every room in the lobby snapshots right now. */
    @SuppressWarnings("unchecked")
    private static java.util.Set<String> liveRooms() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (reo.us.KkService kk : reo.us.KkService.instances()) {
            Map<String, Object> snap;
            try { snap = kk.snapshot(); } catch (RuntimeException e) { continue; }
            Object areas = snap.get("areas");
            if (!(areas instanceof List)) continue;
            for (Object a : (List<Object>) areas)
                for (Object ro : (List<Object>) ((Map<String, Object>) a).getOrDefault("rooms", List.of()))
                    out.add("room:" + snap.get("id") + ":" + ((Map<String, Object>) ro).get("id"));
        }
        return out;
    }

    private int online(long now) {
        seen.values().removeIf(t -> now - t > ONLINE_MS);
        if (seenRoom.size() > seen.size()) seenRoom.keySet().retainAll(seen.keySet());
        return seen.size();
    }

    /** The room a player is in, from the kk lobby snapshot (same data as /api/rooms), or null. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> roomOf(String name) {
        for (reo.us.KkService kk : reo.us.KkService.instances()) {
            Map<String, Object> r = roomOf(kk, name);
            if (r != null) return r;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> roomOf(reo.us.KkService kk, String name) {
        Map<String, Object> snap;
        try { snap = kk.snapshot(); } catch (RuntimeException e) { return null; }
        Object areas = snap.get("areas");
        if (!(areas instanceof List)) return null;
        for (Object a : (List<Object>) areas) {
            Map<String, Object> am = (Map<String, Object>) a;
            for (Object ro : (List<Object>) am.getOrDefault("rooms", List.of())) {
                Map<String, Object> rm = (Map<String, Object>) ro;
                for (Object p : (List<Object>) rm.getOrDefault("players", List.of())) {
                    if (p != null && name.equalsIgnoreCase(p.toString())) {
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("id", rm.get("id"));
                        out.put("game", snap.get("id"));
                        out.put("title", rm.get("title"));
                        out.put("area", am.get("name"));
                        out.put("players", rm.get("players"));
                        out.put("state", rm.get("state"));
                        return out;
                    }
                }
            }
        }
        return null;
    }

    /** A registered name (social: friends are added by registered name). */
    synchronized boolean accountExists(String name) {
        if (name == null || !NAME_RE.matcher(name).matches() || !reloadAccounts()) return false;
        return accounts.getProperty(name.toLowerCase(Locale.ROOT)) != null;
    }

    private boolean reloadAccounts() {
        try {
            long stamp = Files.exists(accountsFile) ? Files.getLastModifiedTime(accountsFile).toMillis() : 0;
            if (stamp != accountsStamp) {
                accounts.clear();
                if (stamp != 0) try (InputStream in = Files.newInputStream(accountsFile)) { accounts.load(in); }
                accountsStamp = stamp;
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    synchronized boolean checkCredential(String name, String cred) {
        if (name == null || cred == null || !NAME_RE.matcher(name).matches() || !CRED_RE.matcher(cred).matches()) return false;
        try {
            long stamp = Files.exists(accountsFile) ? Files.getLastModifiedTime(accountsFile).toMillis() : 0;
            if (stamp != accountsStamp) {
                accounts.clear();
                if (stamp != 0) try (InputStream in = Files.newInputStream(accountsFile)) { accounts.load(in); }
                accountsStamp = stamp;
            }
        } catch (IOException e) {
            return false;
        }
        return ApiGuard.secretEquals(cred, accounts.getProperty(name.toLowerCase(Locale.ROOT)));
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** Plain text only: control and format characters out, whitespace folded, trimmed. */
    static String clean(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        s.codePoints().forEach(c -> {
            int type = Character.getType(c);
            if (Character.isWhitespace(c)) b.append(' ');
            else if (type != Character.CONTROL && type != Character.FORMAT && type != Character.UNASSIGNED
                    && type != Character.PRIVATE_USE && type != Character.SURROGATE) b.appendCodePoint(c);
        });
        return b.toString().replaceAll(" {2,}", " ").trim();
    }

    private static Map<String, Object> err(String e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", e);
        return m;
    }

    private void send(HttpExchange ex, int code, Object body) throws IOException {
        byte[] b = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ApiGuard.securityHeaders(ex.getResponseHeaders());
        if (!corsOrigin.isEmpty()) ex.getResponseHeaders().set("Access-Control-Allow-Origin", corsOrigin);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    /** Minimal JSON string field reader with \" \\ \n \\uXXXX escapes (the bodies are tiny and flat). */
    static String str(String body, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]++|\\\\.)*+)\"").matcher(body);   // possessive: no stack overflow on long values
        if (!m.find()) return null;
        String s = m.group(1);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) { b.append(c); continue; }
            char e = s.charAt(++i);
            switch (e) {
                case 'n': case 'r': case 't': b.append(' '); break;
                case 'b': case 'f': break;
                case 'u':
                    if (i + 4 < s.length()) {
                        try { b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); } catch (NumberFormatException ignored) { }
                        i += 4;
                    }
                    break;
                default: b.append(e);
            }
        }
        return b.toString();
    }

    static long num(String body, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d{1,15})").matcher(body);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }
}
