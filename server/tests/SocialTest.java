import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import reo.common.HttpApi;
import reo.common.ServerConfig;
import reo.common.Social;

/**
 * Social features (notes/social.md): friends (request + add back, share opt-in, reject, remove, forget on release)
 * and player reports (limits, server lines, admin queue, Discord webhook read from an alerts env file).
 * Needs data/kk-accounts.properties with alice/bob/carl (run-tests.sh writes it) and "alerts.env" is written here.
 */
public class SocialTest {
    static int fails = 0, ipSeq = 1;
    static HttpClient c = HttpClient.newHttpClient();
    static String tok = System.getenv("REO_ADMIN_TOKEN");
    static String base;
    static final String A = "\"name\":\"Alice\",\"credential\":\"aaaaaaaaaaaa\"";
    static final String B = "\"name\":\"Bob\",\"credential\":\"bbbbbbbbbbbb\"";
    static final String C = "\"name\":\"Carl\",\"credential\":\"cccccccccccc\"";

    static HttpResponse<String> req(String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        b.header("X-Forwarded-For", "10.1.0." + (ipSeq++ % 250 + 1));
        if (auth != null) b.header("Authorization", "Bearer " + auth);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> fr(String who, String rest) throws Exception {
        return req("POST", "/api/social/friends", "{" + who + (rest.isEmpty() ? "" : "," + rest) + "}", null);
    }

    static HttpResponse<String> rep(String who, String rest) throws Exception {
        return req("POST", "/api/social/report", "{" + who + "," + rest + "}", null);
    }

    static String section(String body, String key) {   // the JSON array after "key":
        int i = body.indexOf("\"" + key + "\":[");
        if (i < 0) return "";
        int d = 0;
        for (int j = i + key.length() + 3; j < body.length(); j++) {
            char ch = body.charAt(j);
            if (ch == '[') d++;
            if (ch == ']' && --d == 0) return body.substring(i, j + 1);
        }
        return body.substring(i);
    }

    static void check(String what, boolean ok, Object got) {
        System.out.println((ok ? "ok   " : "FAIL ") + what + (ok ? "" : "  -> " + got));
        if (!ok) fails++;
    }

    public static void main(String[] a) throws Exception {
        LinkedBlockingQueue<String> hook = new LinkedBlockingQueue<>();
        HttpServer hs = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        hs.createContext("/hook", ex -> {
            hook.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        hs.start();
        Files.writeString(Path.of("alerts.env"), "# test\nOTHER=1\nREO_REPORT_WEBHOOK=\"http://127.0.0.1:" + hs.getAddress().getPort() + "/hook\"\n");

        // a friends file from an earlier run, with more users than MiniJson's default limits (32 keys)
        StringBuilder pre = new StringBuilder("{\"v\":1,\"users\":{");
        for (int i = 1; i <= 40; i++) pre.append(i > 1 ? "," : "").append("\"u").append(i).append("\":{\"share\":true,\"add\":{\"carl\":").append(1000 + i).append("}}");
        Files.createDirectories(Path.of("data/social"));
        Files.writeString(Path.of("data/social/friends.json"), pre.append("}}\n").toString());

        ServerConfig cfg = new ServerConfig(Path.of(a[0]));
        base = "http://127.0.0.1:" + cfg.getInt("api.port", 0);
        new HttpApi(cfg, List.of()).start();

        // ---- friends ----
        var r = req("POST", "/api/social/friends", "{\"name\":\"Alice\",\"credential\":\"bbbbbbbbbbbb\",\"op\":\"list\"}", null);
        check("bad credential = 401", r.statusCode() == 401, r.statusCode());
        r = req("GET", "/api/social/friends", null, null);
        check("GET = 405", r.statusCode() == 405, r.statusCode());
        r = fr(A, "\"op\":\"list\"");
        check("empty list, share off by default", r.statusCode() == 200 && r.body().contains("\"share\":false") && r.body().contains("\"friends\":[]")
              && r.body().contains("\"max\":100"), r.body());
        r = fr(C, "\"op\":\"list\"");
        check("file with 40 users loaded: carl has 40 requests, newest first", r.body().contains("\"incoming_total\":40") && r.body().contains("\"incoming\":[{\"name\":\"u40\""), r.body());
        r = fr(A, "\"op\":\"add\",\"target\":\"nobody\"");
        check("add unknown = 404", r.statusCode() == 404, r.body());
        r = fr(A, "\"op\":\"add\",\"target\":\"ALICE\"");
        check("add self = 400", r.statusCode() == 400 && r.body().contains("self"), r.body());
        r = fr(A, "\"op\":\"add\",\"target\":\"bob\"");
        check("alice adds bob: pending in sent", r.statusCode() == 200 && section(r.body(), "sent").contains("\"bob\"") && r.body().contains("\"friends\":[]"), r.body());
        r = fr(B, "\"op\":\"list\"");
        check("bob sees alice's request", section(r.body(), "incoming").contains("\"Alice\"") && r.body().contains("\"incoming_total\":1"), r.body());
        r = fr(B, "\"op\":\"add\",\"target\":\"Alice\"");
        check("bob adds back: friends, alice's status hidden", section(r.body(), "friends").contains("\"Alice\"") && section(r.body(), "friends").contains("\"hidden\":true")
              && r.body().contains("\"incoming\":[]"), r.body());
        r = fr(A, "\"op\":\"list\"");
        check("alice: bob is a friend now (shown as Bob), hidden", section(r.body(), "friends").contains("\"Bob\"") && section(r.body(), "friends").contains("\"hidden\":true")
              && r.body().contains("\"sent\":[]"), r.body());
        r = fr(B, "\"op\":\"share\",\"share\":true");
        check("bob turns share on", r.body().contains("\"share\":true"), r.body());
        r = fr(A, "\"op\":\"list\"");
        check("alice sees bob offline", section(r.body(), "friends").contains("\"status\":{\"online\":false}"), r.body());
        req("POST", "/api/chat/poll", "{" + B + "}", null);   // bob's page is open
        r = fr(A, "\"op\":\"list\"");
        check("bob online on the page", section(r.body(), "friends").contains("\"status\":{\"online\":true,\"web\":true}"), r.body());
        r = fr(C, "\"op\":\"add\",\"target\":\"bob\"");
        r = fr(B, "\"op\":\"reject\",\"target\":\"carl\"");
        check("bob rejects carl", r.statusCode() == 200 && r.body().contains("\"incoming\":[]"), r.body());
        r = fr(C, "\"op\":\"list\"");
        check("carl's request is gone", r.body().contains("\"sent\":[]"), r.body());
        fr(C, "\"op\":\"add\",\"target\":\"bob\"");
        r = fr(B, "\"op\":\"list\"");
        check("carl's new request stays hidden for bob", r.body().contains("\"incoming\":[]"), r.body());
        String file = Files.readString(Path.of("data/social/friends.json"));
        check("friends.json written", file.contains("\"alice\":{") && file.contains("\"bob\":") && file.contains("\"ignore\":[\"carl\"]"), file);
        r = fr(A, "\"op\":\"remove\",\"target\":\"bob\"");
        check("alice removes bob", r.body().contains("\"friends\":[]") && r.body().contains("\"sent\":[]"), r.body());
        r = fr(B, "\"op\":\"list\"");
        check("bob: alice back to a sent request", section(r.body(), "sent").contains("\"Alice\"") && r.body().contains("\"friends\":[]"), r.body());
        fr(A, "\"op\":\"add\",\"target\":\"bob\"");
        Social.forget("Bob");
        file = Files.readString(Path.of("data/social/friends.json"));
        check("released name forgotten everywhere", !file.contains("bob") && file.contains("\"u40\":{"), file);
        r = fr(A, "\"op\":\"list\"");
        check("alice's list without bob", r.body().contains("\"friends\":[]") && r.body().contains("\"sent\":[]"), r.body());

        // ---- player reports ----
        Thread.sleep(1100);
        r = req("POST", "/api/chat/send", "{\"channel\":\"community\",\"text\":\"sos un idiota\"," + A + "}", null);
        check("alice writes in the chat", r.statusCode() == 200, r.body());
        r = req("POST", "/api/social/report", "{\"target\":\"Alice\",\"reason\":\"insult\"}", null);
        check("report without name = 401", r.statusCode() == 401, r.statusCode());
        r = rep(B, "\"target\":\"Alice\",\"reason\":\"rude\"");
        check("bad reason = 400", r.statusCode() == 400, r.body());
        r = rep(B, "\"target\":\"bob\",\"reason\":\"spam\"");
        check("self = 400", r.statusCode() == 400, r.body());
        r = rep(B, "\"target\":\"ghost\",\"reason\":\"spam\"");
        check("unknown = 404", r.statusCode() == 404, r.body());
        r = rep(B, "\"target\":\"Alice\",\"reason\":\"insult\",\"note\":\"me insulta\\u202e\",\"game\":\"file1\",\"seen\":[{\"t\":1,\"text\":\"sos un idiota\"},{\"text\":\"" + "abcdefghij".repeat(30) + "\"}," + "\"y\",".repeat(12) + "\"z\"]");
        check("report ok id 1", r.statusCode() == 200 && r.body().equals("{\"ok\":true,\"id\":1}"), r.body());
        r = rep(B, "\"target\":\"Alice\",\"reason\":\"spam\"");
        check("same target again = 429 already reported", r.statusCode() == 429 && r.body().contains("already reported"), r.body());
        r = rep(B, "\"target\":\"carl\",\"reason\":\"name\"");
        check("within the gap = 429 rate limited", r.statusCode() == 429 && r.body().contains("rate limited") && r.body().contains("retry_after"), r.body());
        Thread.sleep(1100);   // api.preports_gap_s=1 in the test config
        r = rep(B, "\"target\":\"carl\",\"reason\":\"name\"");
        check("after the gap ok id 2", r.body().equals("{\"ok\":true,\"id\":2}"), r.body());

        String h1 = hook.poll(5, TimeUnit.SECONDS), h2 = hook.poll(5, TimeUnit.SECONDS);
        check("webhook got report 1", h1 != null && h1.contains("#1") && h1.contains("**Alice**") && h1.contains("insultos")
              && h1.contains("> sos un idiota") && h1.contains("https://example.test/admin") && h1.contains("\"allowed_mentions\":{\"parse\":[]}"), h1);
        check("webhook without reporter or address", h1 != null && !h1.contains("Bob") && !h1.contains("10.1.") && !h1.contains("me insulta"), h1);
        check("webhook got report 2 (no extract)", h2 != null && h2.contains("#2") && h2.contains("**carl**") && !h2.contains("> "), h2);

        r = req("GET", "/api/admin/player-reports", null, null);
        check("admin list without token = 403", r.statusCode() == 403, r.statusCode());
        r = req("GET", "/api/admin/player-reports?limit=10", null, tok);
        String b = r.body();
        check("admin list: 2 open, newest first", r.statusCode() == 200 && b.startsWith("{\"total\":2,\"open\":2,\"reports\":[{\"id\":2,"), b);
        check("server lines attached", b.contains("\"server\":[{\"t\":") && b.contains("\"text\":\"sos un idiota\",\"channel\":\"community\""), b);
        check("seen lines cut: 10 at most, 200 chars", b.contains("\"" + "abcdefghij".repeat(20) + "\"") && !b.contains("abcdefghij".repeat(20) + "a")
              && b.split("\\{\"text\":\"y\"\\}", -1).length - 1 == 8, b);
        check("note cleaned, reporter kept, no address", b.contains("\"note\":\"me insulta\"") && b.contains("\"reporter\":\"Bob\"") && !b.contains("10.1.")
              && b.contains("\"ip_ref\":\"") && b.contains("\"target_24h\":1"), b);
        r = req("POST", "/api/admin/player-reports", "{\"id\":1,\"done\":true}", tok);
        check("mark done", r.statusCode() == 200, r.body());
        r = req("GET", "/api/admin/player-reports", null, tok);
        check("open = 1", r.body().startsWith("{\"total\":2,\"open\":1,"), r.body());
        String jl = Files.readString(Path.of("data/social/player-reports.jsonl"));
        check("jsonl: 2 reports + 1 done line, no address", jl.lines().count() == 3 && jl.contains("\"done_of\":1") && !jl.contains("10.1."), jl);

        hs.stop(0);
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
