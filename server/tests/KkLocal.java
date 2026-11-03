import java.nio.file.Path;
import java.util.List;
import reo.common.HttpApi;
import reo.common.ServerConfig;
import reo.us.KkService;

/** LOCAL ONLY: the HTTP API and the kk lobby (no games, no DB) for the python protocol tests in tools/. */
public class KkLocal {
    public static void main(String[] a) throws Exception {
        ServerConfig cfg = new ServerConfig(Path.of(a[0]));
        new HttpApi(cfg, List.of()).start();
        KkService.startFor(cfg);
        System.out.println("kklocal: up");
        Thread.sleep(Long.MAX_VALUE);
    }
}