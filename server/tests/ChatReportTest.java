import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import reo.common.ApiGuard;
import reo.common.ChatFilter;
import reo.common.HttpApi;
import reo.common.ServerConfig;

/**
 * Starts only the HTTP API on api.port and checks the community chat rooms by language, the automatic
 * moderation (ChatFilter: characters, caps, repeats, links, owner badwords) and the bug reports.
 * Needs data/kk-accounts.properties and data/badwords.txt in the working directory (run-tests.sh writes them).
 * Every request carries its own X-Forwarded-For (loopback is a trusted proxy) to keep the per-IP limits apart.
 */
public class ChatReportTest {
    static int fails = 0, ipSeq = 1;
    static HttpClient c = HttpClient.newHttpClient();
    static String tok = System.getenv("REO_ADMIN_TOKEN");
    static String base;
    static final String ALICE = "\"name\":\"Alice\",\"credential\":\"aaaaaaaaaaaa\"";
    static final String BOB = "\"name\":\"Bob\",\"credential\":\"bbbbbbbbbbbb\"";
    static final String CARL = "\"name\":\"Carl\",\"credential\":\"cccccccccccc\"";

    static HttpResponse<String> req(String method, String path, String body, String auth, String ip) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        b.header("X-Forwarded-For", ip == null ? "10.0.0." + (ipSeq++) : ip);
        if (auth != null) b.header("Authorization", "Bearer " + auth);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> post(String path, String body) throws Exception { return req("POST", path, body, null, null); }

    /** Sends to the community channel; waits 1.1 s first (per-name limit 1/s). */
    static HttpResponse<String> say(String who, String room, String text) throws Exception {
        Thread.sleep(1100);
        String r = room == null ? "" : ",\"room\":\"" + room + "\"";
        return post("/api/chat/send", "{\"channel\":\"community\"" + r + ",\"text\":\"" + text + "\"," + who + "}");
    }

    static String poll(String room) throws Exception {
        return post("/api/chat/poll", room == null ? "{}" : "{\"room\":\"" + room + "\"}").body();
    }

    static String textOf(String body) {
        int i = body.indexOf("\"text\":\"");
        if (i < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int j = i + 8; j < body.length(); j++) {
            char ch = body.charAt(j);
            if (ch == '"') break;
            if (ch == '\\' && j + 1 < body.length()) {
                char n = body.charAt(++j);
                if (n == 'u') { sb.append((char) Integer.parseInt(body.substring(j + 1, j + 5), 16)); j += 4; }
                else sb.append(n == 'n' ? '\n' : n);
            } else sb.append(ch);
        }
        return sb.toString();
    }

    static void check(String what, boolean ok, Object got) {
        System.out.println((ok ? "ok   " : "FAIL ") + what + (ok ? "" : "  -> " + got));
        if (!ok) fails++;
    }

    public static void main(String[] a) throws Exception {
        ServerConfig cfg = new ServerConfig(Path.of(a[0]));
        base = "http://127.0.0.1:" + cfg.getInt("api.port", 0);
        new HttpApi(cfg, List.of()).start();

        // ---- rooms by language ----
        String p = poll(null);
        check("poll without room = es", p.contains("\"chat_room\":\"es\"") && p.contains("\"online_rooms\":{"), p);
        var r = say(ALICE, null, "hola a todos");
        check("old client send (no room) ok", r.statusCode() == 200 && r.body().matches("\\{\"ok\":true,\"id\":\\d+,\"text\":\"hola a todos\"\\}"), r.body());
        r = say(BOB, "en", "hello everyone");
        check("send en ok", r.statusCode() == 200, r.body());
        r = say(CARL, "pt", "ola pessoal");
        check("send pt ok", r.statusCode() == 200, r.body());
        p = poll(null);
        check("es: own message only", p.contains("hola a todos") && !p.contains("hello everyone") && !p.contains("ola pessoal"), p);
        p = poll("es");
        check("explicit es = default", p.contains("hola a todos") && !p.contains("hello"), p);
        p = poll("en");
        check("en isolated", p.contains("\"chat_room\":\"en\"") && p.contains("hello everyone") && !p.contains("hola a todos") && !p.contains("ola pessoal"), p);
        p = poll("pt");
        check("pt isolated", p.contains("\"chat_room\":\"pt\"") && p.contains("ola pessoal") && !p.contains("hello"), p);
        p = poll("xx");
        check("invalid room -> es", p.contains("\"chat_room\":\"es\"") && p.contains("hola a todos"), p);
        check("online_rooms counts", p.matches(".*\"online_rooms\":\\{\"es\":\\d+,\"en\":\\d+,\"pt\":\\d+,\"fil\":\\d+,\"ja\":\\d+\\}.*"), p);
        p = post("/api/chat/poll", "{\"room\":\"en\"," + BOB + "}").body();
        check("room channel still 'Mi partida' field", p.contains("\"room\":null") || p.contains("\"room_reason\""), p);

        // ---- characters, caps ----
        r = say(ALICE, null, "ho\\u200bla\\u202e mundo\\u0000 z\\u0301\\u0302\\u0303\\u0304\\u0305o");
        String t = textOf(r.body());
        check("zero-width/bidi/control removed, combining capped at 2",
              r.statusCode() == 200 && t != null && t.startsWith("hola mundo") && t.indexOf('\u200b') < 0 && t.indexOf('\u202e') < 0
              && t.chars().filter(ch -> ch >= 0x300 && ch <= 0x36f).count() == 2, r.body());
        r = say(BOB, "en", "THIS IS ALL IN UPPERCASE LETTERS");
        t = textOf(r.body());
        check("sustained caps lowered", t != null && t.equals(t.toLowerCase()) && t.contains("uppercase"), r.body());
        r = say(CARL, null, "OK GG");
        check("short caps kept", "OK GG".equals(textOf(r.body())), r.body());

        // ---- repeats, links ----
        r = say(ALICE, null, "alguien para jugar?");
        check("new text ok", r.statusCode() == 200, r.body());
        r = say(ALICE, null, "ALGUIEN para jugaaar??");
        check("repeat (folded) = 429 repeated", r.statusCode() == 429 && r.body().contains("repeated"), r.body());
        r = say(BOB, "en", "see http://a.example and www.b.example");
        check("two links = 400", r.statusCode() == 400 && r.body().contains("too many links"), r.body());
        r = say(CARL, null, "mira http://a.example/1");
        check("one link ok", r.statusCode() == 200, r.body());
        r = say(CARL, null, "y esto http://a.example/2");
        check("second link ok", r.statusCode() == 200, r.body());
        r = say(CARL, null, "y esto otro http://a.example/3");
        check("third link in 5 min = 429 link flood", r.statusCode() == 429 && r.body().contains("link flood"), r.body());

        // ---- owner badwords, re-read while running ----
        r = say(BOB, "en", "you are a tonto");
        check("before list: unmasked", "you are a tonto".equals(textOf(r.body())), r.body());
        Files.writeString(Path.of("data/badwords.txt"), "# owner list\ntonto\nfeo*\n*caca*\n");
        Thread.sleep(1500);   // api.badwords_reload_s=1 in the test config
        r = say(ALICE, null, "sos un TONTO y t.o.n.t.o, feisimo no, feote si, popocacapo, tontos");
        t = textOf(r.body());
        check("badwords masked", t != null && t.equals("sos un *** y ***, feisimo no, *** si, ***, ***"), r.body());
        p = poll(null);
        check("poll shows masked text", p.contains("sos un *** y") && !p.toLowerCase().contains("tonto y"), p);
        check("name with badword refused", ApiGuard.instance.checkName("ElTonto") != null && ApiGuard.instance.checkName("Tontin") == null,
              ApiGuard.instance.checkName("ElTonto"));
        check("room title masked", ChatFilter.maskTitle("sala tonto").equals("sala ***"), ChatFilter.maskTitle("sala tonto"));
        var m = req("GET", "/api/admin/metrics", null, tok, "127.0.0.1").body();
        check("metrics chat_filter counters", m.contains("\"chat_filter\"") && m.contains("\"badword_masked\"") && m.contains("\"repeated\""), m);

        // ---- reports ----
        String ip = "10.9.9.1";
        r = req("POST", "/api/report", "{\"text\":\"  \",\"game\":\"file1\"}", null, ip);
        check("empty report = 400", r.statusCode() == 400, r.body());
        r = req("POST", "/api/report", "{\"text\":\"Se cuelga en el lobby\\u202e\",\"game\":\"file1\",\"ua\":\"Mozilla/5.0 test\"}", null, ip);
        check("report ok id 1", r.statusCode() == 200 && r.body().equals("{\"ok\":true,\"id\":1}"), r.body());
        r = req("POST", "/api/report", "{\"text\":\"otra vez\",\"game\":\"file1\"}", null, ip);
        check("second within gap = 429 + retry_after", r.statusCode() == 429 && r.body().matches("\\{\"error\":\"rate limited\",\"retry_after\":\\d+\\}")
              && r.headers().firstValue("Retry-After").isPresent(), r.statusCode() + " " + r.body());
        r = req("POST", "/api/report", "{\"text\":\"spam\",\"game\":\"file1\",\"website\":\"http://spam\"}", null, "10.9.9.2");
        check("honeypot: fake ok, id 0", r.statusCode() == 200 && r.body().equals("{\"ok\":true,\"id\":0}"), r.body());
        r = req("POST", "/api/report", "{\"text\":\"" + "abcdefghij".repeat(80) + "\",\"game\":\"BAD GAME!\",\"diag\":\"{\\\"net\\\":\\\"on\\\",\\\"log\\\":[\\\"" + "x".repeat(8000) + "\\\"]}\"}", null, "10.9.9.3");
        check("long report accepted id 2", r.body().equals("{\"ok\":true,\"id\":2}"), r.body());
        String hip = "10.9.9.4";
        for (int i = 0; i < 3; i++) {
            Thread.sleep(1100);   // api.reports_gap_s=1 in the test config
            r = req("POST", "/api/report", "{\"text\":\"n" + i + "\",\"game\":\"file2\"}", null, hip);
        }
        check("3rd in the hour ok", r.statusCode() == 200, r.body());
        Thread.sleep(1100);
        r = req("POST", "/api/report", "{\"text\":\"n4\",\"game\":\"file2\"}", null, hip);
        check("4th in the hour = 429", r.statusCode() == 429, r.body());
        r = req("GET", "/api/report", null, null, null);
        check("GET /api/report = 405", r.statusCode() == 405, r.statusCode());
        r = req("GET", "/api/admin/reports", null, null, "127.0.0.1");
        check("admin reports without token = 403", r.statusCode() == 403, r.statusCode());
        Thread.sleep(1100);
        r = req("GET", "/api/admin/reports?limit=10", null, tok, "127.0.0.1");
        String b = r.body();
        check("admin reports list", r.statusCode() == 200 && b.startsWith("{\"total\":5,\"reports\":[{\"id\":5,"), b);
        check("text truncated to 500, game unknown", b.contains("\"" + "abcdefghij".repeat(50) + "\"") && b.contains("\"game\":\"unknown\""), b.length());
        check("diag stored, cut to 6000", b.contains("\"diag\":\"{\\\"net\\\":\\\"on\\\"") && !b.contains("x".repeat(6001)), b.length());
        check("bidi removed, ua kept", b.contains("\"text\":\"Se cuelga en el lobby\"") && b.contains("\"ua\":\"Mozilla/5.0 test\""), b);
        check("no raw ip stored", !b.contains("10.9.9") && b.contains("\"ip_ref\":\""), b);
        String file = Files.readString(Path.of("data/reports/reports.jsonl"));
        check("jsonl has 5 lines, no ip", file.lines().count() == 5 && !file.contains("10.9.9"), file.lines().count());

        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
