/*
    reo-web bioserver - fan server for Resident Evil Outbreak File #1/#2
    (C) 2026 reo-web contributors. AGPL-3.0-or-later, see LICENSE.txt.

    Stand-in for the US web login, http://regweb.reo.capcom.sf.yav4.com/reweb/index.jsp.
    The US netwk.bin keeps the JP "MMBB" browser and its <CSV> parser, so this answers with the
    same pages upstream's www/ served for KDDI (CRS-top.jsp -> login -> startsession.php ->
    enterareas.html), with the hosts moved to the US names.

      GET  /reweb/index.jsp        login form (or, with regweb.dev_user set, an immediate game start;
                                   regweb.index_file serves a fixed page instead, for protocol work)
      POST /reweb/login            userid/password -> CRS-game-start CSV with the session id
      GET  /reweb/enterareas.html  CRS-lbs-info-get CSV: lobby host:port per area
    Every request is written to the packet log (channel "regweb") with its headers and body.
*/

package reo.common;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public final class RegWeb {
    private final ServerConfig cfg;
    private final SessionStore sessions;
    private final GameConfig game;
    private final String host;      // regweb host name the game asks for
    private final String lobbyHost; // what the area list points the game at
    private HttpServer http;

    public RegWeb(ServerConfig cfg, GameConfig game) {
        this.cfg = cfg;
        this.game = game;
        this.sessions = new SessionStore(game);
        this.host = cfg.get("hosts.regweb", "regweb.reo.capcom.sf.yav4.com");
        this.lobbyHost = cfg.get("regweb.lobby_host", cfg.get("hosts.lobby", "app01.reo.capcom.sf.yav4.com"));
    }

    public void start() throws IOException {
        String bind = cfg.get("regweb.bind", "127.0.0.1");
        int port = cfg.getInt("regweb.port", 8080);
        http = HttpServer.create(new InetSocketAddress(bind, port), 16);
        http.setExecutor(Executors.newFixedThreadPool(2));
        http.createContext("/", ex -> {
            try {
                handle(ex);
            } catch (Exception e) {
                System.out.println("regweb: " + e);
            } finally {
                ex.close();
            }
        });
        http.start();
        System.out.println("regweb on http://" + bind + ":" + port + "/reweb/index.jsp (" + game.id + ", lobby " + lobbyHost + ":" + game.lobbyPort + ")");
    }

    private void handle(HttpExchange ex) throws Exception {
        byte[] body = readAll(ex.getRequestBody());
        StringBuilder req = new StringBuilder();
        req.append(ex.getRequestMethod()).append(' ').append(ex.getRequestURI()).append(' ').append(ex.getProtocol()).append("\r\n");
        ex.getRequestHeaders().forEach((k, v) -> v.forEach(x -> req.append(k).append(": ").append(x).append("\r\n")));
        req.append("\r\n").append(new String(body, StandardCharsets.ISO_8859_1));
        byte[] reqBytes = req.toString().getBytes(StandardCharsets.ISO_8859_1);
        PacketLog.log("regweb", true, ex.getRemoteAddress(), reqBytes, 0, reqBytes.length);
        System.out.println("regweb: " + ex.getRequestMethod() + " " + ex.getRequestURI());

        String path = ex.getRequestURI().getPath();
        Map<String, String> form = parseForm(ex.getRequestURI().getRawQuery());
        form.putAll(parseForm(new String(body, StandardCharsets.ISO_8859_1)));
        String page;
        if (path.endsWith("/enterareas.html")) {
            page = areas();
        } else if (path.endsWith("/login")) {
            page = startOrError(form.get("username"), form.get("password"), ex);
        } else if (path.endsWith("/index.jsp") || path.equals("/reweb/") || path.equals("/")) {
            String devUser = cfg.get("regweb.dev_user", "");
            String indexFile = cfg.get("regweb.index_file", "");
            page = !indexFile.isEmpty() ? new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(indexFile)), StandardCharsets.ISO_8859_1)
                 : devUser.isEmpty() ? loginForm() : startOrError(devUser, cfg.get("regweb.dev_password", devUser), ex);
        } else {
            reply(ex, 404, "<html><body>not found</body></html>");
            return;
        }
        reply(ex, 200, page);
    }

    private String startOrError(String user, String password, HttpExchange ex) throws Exception {
        String sessid = sessions.login(user, password, ex.getRemoteAddress().getAddress().getHostAddress(), ex.getRemoteAddress().getPort());
        if (sessid == null) return "<html><body>Wrong ID or password.<br><a href=\"index.jsp\">Back</a></body></html>";
        System.out.println("regweb: session " + sessid + " for " + user);
        String base = "http://" + host + "/reweb/";
        return "<html><head><!--CRS-game-start-->"
             + "<META HTTP-EQUIV=Content-Type CONTENT=text/html;CHARSET=ISO-8859-1></head>"
             + "<!--result--><!--connection id--><!--start the game url--><!--exit game url-->"
             + "<!--<CSV>\"OK\",\"" + sessid + "\",\"" + base + "enterareas.html\",\"" + base + "index.jsp\",</CSV>--></html>";
    }

    private String areas() {
        String lbs = lobbyHost + ":" + game.lobbyPort;
        return "<html>\n<head>\n<!--CRS-lbs-info-get-->\n<META HTTP-EQUIV=Content-Type CONTENT=text/html;CHARSET=ISO-8859-1>\n</head>\n<!--\n<CSV>\n"
             + "\"OK\",\n"
             + "\"" + lbs + "\",\n\"0\",\n\"999\",\n\"0ad601082008,WEST TOWN,2\",\n"
             + "\"<BODY><SIZE=4>Free AREA<BR><BODY>Create your own<BR><BODY>games<END>\",\n"
             + "\"<BODY><SIZE=4>reo-web test server<END>\",\n"
             + "\"" + lbs + "\",\n\"0\",\n\"999\",\n\"0ad601082008,EAST TOWN,1\",\n"
             + "\"<BODY><SIZE=4>Scenario Mode<END>\",\n"
             + "\"<BODY><SIZE=4>reo-web test server<END>\",\n"
             + "</CSV>\n-->\n</html>\n";
    }

    private String loginForm() {
        return "<html><head><title>reo-web</title></head><body>"
             + "<form method=\"post\" action=\"login\">ID: <input type=\"text\" name=\"username\"><br>"
             + "Password: <input type=\"password\" name=\"password\"><br>"
             + "<input type=\"submit\" value=\"LOGIN\"></form></body></html>";
    }

    private void reply(HttpExchange ex, int code, String page) throws IOException {
        byte[] out = page.getBytes(StandardCharsets.ISO_8859_1);
        ex.getResponseHeaders().set("Content-Type", "text/html");
        ex.sendResponseHeaders(code, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
        PacketLog.log("regweb", false, ex.getRemoteAddress(), out, 0, out.length);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while (b.size() < 65536 && (n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    private static Map<String, String> parseForm(String s) {
        Map<String, String> m = new HashMap<>();
        if (s == null || s.isEmpty()) return m;
        for (String kv : s.split("&")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            m.put(URLDecoder.decode(kv.substring(0, eq), StandardCharsets.ISO_8859_1),
                  URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.ISO_8859_1));
        }
        return m;
    }

    /** Starts one regweb for the first configured game whose id is in regweb.games (default file1). */
    public static void startFor(ServerConfig cfg, List<LobbyView> views) throws IOException {
        if (!cfg.getBool("regweb.enabled", true)) return;
        String want = cfg.get("regweb.game", "file1");
        for (LobbyView v : views)
            if (v.config().id.equals(want)) {
                new RegWeb(cfg, v.config()).start();
                return;
            }
    }
}
