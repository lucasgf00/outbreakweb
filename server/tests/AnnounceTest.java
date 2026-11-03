import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import reo.common.HttpApi;
import reo.common.ServerConfig;

/** Starts only the HTTP API (no games, no DB) on api.port from the config and checks /api/announce. */
public class AnnounceTest {
    static int fails = 0;
    static HttpClient c = HttpClient.newHttpClient();
    static String tok = System.getenv("REO_ADMIN_TOKEN");
    static String base;

    static HttpResponse<String> req(String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        if (method.equals("POST")) Thread.sleep(1100);   // admin rate limit: 1/s, burst 5
        if (auth != null) b.header("Authorization", "Bearer " + auth);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static void check(String what, boolean ok, Object got) {
        System.out.println((ok ? "ok   " : "FAIL ") + what + (ok ? "" : "  -> " + got));
        if (!ok) fails++;
    }

    public static void main(String[] a) throws Exception {
        ServerConfig cfg = new ServerConfig(Path.of(a[0]));
        base = "http://127.0.0.1:" + cfg.getInt("api.port", 0);
        new HttpApi(cfg, List.of()).start();

        var r = req("GET", "/api/announce", null, null);
        check("get empty", r.body().equals("{\"text\":\"\",\"until\":0,\"id\":0}"), r.body());
        check("no-store", "no-store".equals(r.headers().firstValue("Cache-Control").orElse("")), r.headers());
        r = req("POST", "/api/admin/announce", "{\"text\":\"x\"}", null);
        check("post without token = 403", r.statusCode() == 403, r.statusCode());
        r = req("POST", "/api/admin/announce", "{\"text\":\"x\"}", "wrong-token-wrong-token-wrong");
        check("post bad token = 403", r.statusCode() == 403, r.statusCode());
        r = req("POST", "/api/admin/announce", "{\"text\":\"Mantenimiento \\\"hoy\\\"\\u0001\\n ok\",\"minutes\":5}", tok);
        check("post ok", r.statusCode() == 200 && r.body().matches("\\{\"ok\":true,\"until\":\\d{13}\\}"), r.body());
        long until = Long.parseLong(r.body().replaceAll("\\D", ""));
        check("until ~ 5 min", Math.abs(until - System.currentTimeMillis() - 300_000) < 5000, until);
        r = req("GET", "/api/announce", null, null);
        check("get text sanitized, id 1", r.body().equals("{\"text\":\"Mantenimiento \\\"hoy\\\"   ok\",\"until\":" + until + ",\"id\":1}"), r.body());
        r = req("POST", "/api/admin/announce", "{\"text\":\"a\",\"minutes\":0}", tok);
        check("minutes 0 = 400", r.statusCode() == 400, r.statusCode());
        r = req("POST", "/api/admin/announce", "{\"text\":\"a\",\"minutes\":1441}", tok);
        check("minutes 1441 = 400", r.statusCode() == 400, r.statusCode());
        r = req("POST", "/api/admin/announce", "{\"text\":\"" + "z".repeat(400) + "\"}", tok);
        check("long text accepted (truncated), default 30 min", r.statusCode() == 200, r.body());
        r = req("GET", "/api/announce", null, null);
        check("300 chars", r.body().matches("\\{\"text\":\"z{300}\",\"until\":\\d+,\"id\":2\\}"), r.body());
        r = req("POST", "/api/admin/announce", "{\"clear\":true}", tok);
        check("clear", r.body().equals("{\"ok\":true,\"until\":0}"), r.body());
        r = req("GET", "/api/announce", null, null);
        check("get after clear, id 3", r.body().equals("{\"text\":\"\",\"until\":0,\"id\":3}"), r.body());
        r = req("POST", "/api/admin/announce", "{\"text\":\"b\"}", tok);
        r = req("POST", "/api/admin/announce", "{\"text\":\"\"}", tok);
        check("empty text clears", r.body().equals("{\"ok\":true,\"until\":0}"), r.body());
        r = req("GET", "/api/announce", null, null);
        check("id 5", r.body().endsWith("\"id\":5}"), r.body());
        r = req("POST", "/api/admin/announce", "{\"discord\":\"https://discord.gg/abc123\"}", tok);
        check("discord set", r.statusCode() == 200 && r.body().contains("discord.gg/abc123"), r.body());
        r = req("GET", "/api/announce", null, null);
        check("discord in get, id kept", r.body().equals("{\"text\":\"\",\"until\":0,\"id\":5,\"discord\":\"https://discord.gg/abc123\"}"), r.body());
        r = req("POST", "/api/admin/announce", "{\"discord\":\"javascript:alert(1)\"}", tok);
        check("bad discord = 400", r.statusCode() == 400, r.body());
        r = req("POST", "/api/admin/announce", "{\"discord\":\"\"}", tok);
        r = req("GET", "/api/announce", null, null);
        check("discord cleared", !r.body().contains("discord"), r.body());
        check("persisted file", Files.isRegularFile(Path.of("data/announce.properties")), "missing");
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
