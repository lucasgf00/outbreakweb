/*
    reo-web bioserver - fan server for Resident Evil Outbreak File #1/#2
    (C) 2026 reo-web contributors. AGPL-3.0-or-later, see LICENSE.txt.
*/

package reo.common;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Moderation for the owner's panel (/admin, "Moderacion" tab). Same admin token as the metrics panel
 * (X-Admin-Token, or Authorization: Bearer); behind Caddy the panel also has basic auth, whose user name is
 * recorded as "by" in the log. The panel never gets an address: messages, players and address sanctions carry
 * an opaque reference (ApiGuard.ipRef, HMAC of the address keyed with the admin token) resolved back here.
 *
 *   GET  /api/admin/mod/chat       recent messages of every channel (community + rooms), newest first:
 *                                  {"now":ms,"messages":[{id,t,name,text,channel,room?,game?,area?,ip_ref?}..]}
 *   GET  /api/admin/mod/players?q= players online now: the kk lobbies (name, game, area, room, since) merged with
 *                                  the names signed in on the page in the last 10 minutes and the names that wrote
 *                                  in the chat in the last 30 (chat_last, chat_count; never the text); q filters by name
 *   GET  /api/admin/mod/sanctions  active mutes and bans (name, or masked address + ref; reason, by, since, until)
 *                                  and the last 100 entries of the moderation log
 *   POST /api/admin/mod/action     {"action":"mute|ban|unmute|unban|delete|purge|kick|release", "name":"..", "ip_ref":"..",
 *                                   "msg_id":N, "also_ip":true, "minutes":N (0 = permanent), "reason":".."}
 *        mute/ban: the name and/or the address (ip_ref, or also_ip: the address of msg_id or the name's last one);
 *        ban of a name also removes its messages and disconnects it from the online lobbies;
 *        delete: one message (every page drops it at its next poll); purge: every message of a name; kick: closes the name's kk session;
 *        release: forgets the kk account of a name (support: the name was bound to another device) and closes its
 *        sessions; the next device that registers the name gets it ({"had":false} = there was no such account).
 *
 * Rate limit: 4 requests/s with bursts of 20 per address; bad tokens: 5, then one per 10 s.
 * Log: moderation-log.jsonl next to api.moderation_file (data/), 0600, one JSON object per action.
 */
final class Moderation {
    private final ApiGuard guard;
    private final ChatService chat;
    private final String adminToken;

    Moderation(ApiGuard guard, ChatService chat, String adminToken) {
        this.guard = guard;
        this.chat = chat;
        this.adminToken = adminToken;
        guard.setRefKey(adminToken);
    }

    void handle(HttpExchange ex) throws IOException {
        String ip = guard.clientIp(ex);
        if (!guard.take("admin-mod:" + ip, 4, 20)) { ApiGuard.reply(ex, 429, ApiGuard.error("rate limited")); return; }
        String auth = ex.getRequestHeaders().getFirst("Authorization"), x = ex.getRequestHeaders().getFirst("X-Admin-Token");
        String given = x != null ? x.trim() : auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : null;
        if (adminToken.isEmpty() || given == null || !ApiGuard.secretEquals(given, adminToken)) {
            if (!guard.take("admin-bad:" + ip, 0.1, 5)) { ApiGuard.reply(ex, 429, ApiGuard.error("rate limited")); return; }
            System.out.println("api: admin moderation: bad token");
            ApiGuard.reply(ex, 403, ApiGuard.error("forbidden"));
            return;
        }
        String path = ex.getRequestURI().getPath(), method = ex.getRequestMethod();
        if (path.equals("/api/admin/mod/action")) {
            if (!"POST".equals(method)) { ApiGuard.reply(ex, 405, ApiGuard.error("use POST")); return; }
            action(ex, who(auth));
            return;
        }
        if (!"GET".equals(method)) { ApiGuard.reply(ex, 405, ApiGuard.error("use GET")); return; }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("now", System.currentTimeMillis());
        switch (path) {
            case "/api/admin/mod/chat": m.put("messages", chat.adminMessages(guard)); break;
            case "/api/admin/mod/players": m.put("players", players(query(ex, "q"))); break;
            case "/api/admin/mod/sanctions": {
                m.put("sanctions", guard.sanctions());
                List<Object> log = new ArrayList<>();
                for (String l : guard.logTail(100)) log.add(new Json.Raw(l));
                m.put("log", log);
                break;
            }
            default: ApiGuard.reply(ex, 404, ApiGuard.error("not found")); return;
        }
        ApiGuard.reply(ex, 200, m);
    }

    /** Basic-auth user name (checked by Caddy in production), else "token". */
    private static String who(String auth) {
        if (auth != null && auth.startsWith("Basic ")) {
            try {
                String u = new String(Base64.getDecoder().decode(auth.substring(6).trim()), StandardCharsets.UTF_8);
                u = u.split(":", 2)[0].replaceAll("[^A-Za-z0-9_.@-]", "");
                if (!u.isEmpty()) return u.length() > 32 ? u.substring(0, 32) : u;
            } catch (IllegalArgumentException ignored) { }
        }
        return "token";
    }

    private static String query(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) return null;
        for (String kv : q.split("&")) if (kv.startsWith(key + "=")) return kv.substring(key.length() + 1);
        return null;
    }

    /** kk sessions (both titles) merged with the names signed in on the page. */
    private List<Map<String, Object>> players(String q) {
        String f = q == null ? "" : q.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        Map<String, Map<String, Object>> by = new LinkedHashMap<>();
        for (reo.us.KkService kk : reo.us.KkService.instances())
            for (Map<String, Object> p : kk.players()) {
                Map<String, Object> m = new LinkedHashMap<>(p);
                m.put("online", true);
                by.put(String.valueOf(p.get("name")).toLowerCase(Locale.ROOT), m);
            }
        for (Map<String, Object> w : chat.webPlayers(guard)) {
            String k = String.valueOf(w.get("name")).toLowerCase(Locale.ROOT);
            Map<String, Object> m = by.computeIfAbsent(k, kk -> {
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("name", w.get("name"));
                n.put("online", false);
                n.put("since", w.get("first"));
                return n;
            });
            m.put("web_last", w.get("last"));
            if (w.get("ip_ref") != null) m.put("ip_ref", w.get("ip_ref"));
        }
        for (Map<String, Object> a : chat.recentAuthors(guard)) {   // wrote in the last 30 min (maybe gone now)
            String k = String.valueOf(a.get("name")).toLowerCase(Locale.ROOT);
            Map<String, Object> m = by.computeIfAbsent(k, kk -> {
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("name", a.get("name"));
                n.put("online", false);
                n.put("since", a.get("last"));
                return n;
            });
            m.put("chat_last", a.get("last"));
            m.put("chat_count", a.get("count"));
            if (!m.containsKey("ip_ref") && a.get("ip_ref") != null) m.put("ip_ref", a.get("ip_ref"));
        }
        // A search also lists registered accounts that are not on the page or online (to release a name, ban it...).
        // The accounts file keeps names lowercase, so they show as stored.
        if (f.length() >= 2)
            for (String k : reo.us.KkService.accountNames(f, 25))
                by.computeIfAbsent(k, kk -> {
                    Map<String, Object> n = new LinkedHashMap<>();
                    n.put("name", k);
                    n.put("online", false);
                    n.put("registered", true);
                    return n;
                });
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : by.values()) {
            String n = String.valueOf(m.get("name"));
            if (!f.isEmpty() && !n.toLowerCase(Locale.ROOT).contains(f)) continue;
            m.put("muted", guard.sanctionUntil("mute", n, null) >= 0);
            m.put("banned", guard.sanctionUntil("ban", n, null) >= 0);
            out.add(m);
        }
        out.sort((a, b) -> Long.compare(((Number) b.getOrDefault("since", 0L)).longValue(), ((Number) a.getOrDefault("since", 0L)).longValue()));
        return out;
    }

    private void action(HttpExchange ex, String by) throws IOException {
        String body = guard.body(ex);
        String action = ChatService.str(body, "action"), name = ChatService.str(body, "name"), ref = ChatService.str(body, "ip_ref");
        String reason = ChatService.str(body, "reason");
        long minutes = ChatService.num(body, "minutes"), msgId = ChatService.num(body, "msg_id");
        boolean alsoIp = body.matches("(?s).*\"also_ip\"\\s*:\\s*true.*");
        if (action == null || !action.matches("mute|ban|unmute|unban|delete|purge|kick|release")) { ApiGuard.reply(ex, 400, ApiGuard.error("unknown action")); return; }
        if (name != null && !name.matches("[A-Za-z0-9]{1,15}")) { ApiGuard.reply(ex, 400, ApiGuard.error("bad name")); return; }
        if (reason != null) { reason = ChatService.clean(reason); if (reason.length() > 120) reason = reason.substring(0, 120); }
        if (minutes > 60L * 24 * 365) minutes = 0;
        String tip = null;
        if (ref != null) {
            tip = guard.resolveRef(ref);
            if (tip == null) { ApiGuard.reply(ex, 404, ApiGuard.error("unknown address reference")); return; }
        } else if (alsoIp) {
            tip = msgId > 0 ? chat.messageIp(msgId) : null;
            if (tip == null) tip = chat.nameIp(name);
            if (tip == null) { ApiGuard.reply(ex, 404, ApiGuard.error("address unknown (not seen on the page)")); return; }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("t", System.currentTimeMillis());
        log.put("by", by);
        log.put("action", action);
        switch (action) {
            case "mute": case "ban": {
                if (name == null && tip == null) { ApiGuard.reply(ex, 400, ApiGuard.error("name or address")); return; }
                if (name != null) guard.set(action, "name", name, minutes, reason, by);
                if (tip != null) guard.set(action, "ip", tip, minutes, reason, by);
                if (action.equals("ban") && name != null) {
                    out.put("purged", chat.purge(name));
                    for (reo.us.KkService kk : reo.us.KkService.instances()) kk.kick(name);
                }
                log.put("minutes", minutes);
                break;
            }
            case "unmute": case "unban": {
                if (name == null && tip == null) { ApiGuard.reply(ex, 400, ApiGuard.error("name or address")); return; }
                boolean had = false;
                if (name != null) had |= guard.clear(action.substring(2), "name", name);
                if (tip != null) had |= guard.clear(action.substring(2), "ip", tip);
                out.put("had", had);
                break;
            }
            case "delete": {
                Map<String, Object> msg = msgId > 0 ? chat.delete(msgId) : null;
                if (msg == null) { ApiGuard.reply(ex, 404, ApiGuard.error("no such message")); return; }
                log.put("msg_id", msgId);
                name = String.valueOf(msg.get("name"));
                log.put("text", String.valueOf(msg.get("text")));
                break;
            }
            case "purge": {   // every stored message of a name
                if (name == null) { ApiGuard.reply(ex, 400, ApiGuard.error("name")); return; }
                int n = chat.purge(name);
                out.put("purged", n);
                log.put("purged", n);
                break;
            }
            case "release": {   // the name's account: another device may register it (KkService.releaseName)
                if (name == null) { ApiGuard.reply(ex, 400, ApiGuard.error("name")); return; }
                boolean had;
                try { had = reo.us.KkService.releaseName(name); }
                catch (Exception e) { ApiGuard.reply(ex, 500, ApiGuard.error("could not save the accounts")); return; }
                out.put("had", had);
                log.put("had", had);
                break;
            }
            default: {   // kick
                if (name == null) { ApiGuard.reply(ex, 400, ApiGuard.error("name")); return; }
                for (reo.us.KkService kk : reo.us.KkService.instances()) kk.kick(name);
            }
        }
        if (name != null) log.put("name", name);
        if (tip != null) { log.put("ip_ref", guard.ipRef(tip)); log.put("ip_masked", ApiGuard.maskIp(tip)); }
        if (reason != null && !reason.isEmpty()) log.put("reason", reason);
        guard.logAction(log);
        System.out.println("api: moderation " + action + (name != null ? " name " + name : "") + (tip != null ? " address " + guard.ipRef(tip) : "")
                + (minutes > 0 ? " " + minutes + " min" : "") + " by " + by);
        out.put("ok", true);
        ApiGuard.reply(ex, 200, out);
    }
}
