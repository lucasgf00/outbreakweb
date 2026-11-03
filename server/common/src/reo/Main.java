/*
    reo-web bioserver - fan server for Resident Evil Outbreak File #1/#2
    Based on BioServer (C) 2013-2019 obsrv.org; modifications (C) 2026 reo-web contributors.

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version. See LICENSE.txt.
*/

package reo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import reo.common.GameConfig;
import reo.common.HttpApi;
import reo.common.LobbyView;
import reo.common.PacketLog;
import reo.common.RawSniffer;
import reo.common.ServerConfig;

/**
 * Entry point: starts the File #1 and/or File #2 lobby+game servers in one JVM,
 * plus the HTTP API and optional raw sniffers.
 *
 *   java -cp build/reo-bioserver.jar:/usr/share/java/mariadb-java-client.jar reo.Main [config/server.properties] [--games file1,file2]
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        String cfgPath = "config/server.properties";
        String games = null;
        for (int i = 0; i < args.length; i++) {
            if ("--games".equals(args[i]) && i + 1 < args.length) games = args[++i];
            else if (!args[i].startsWith("--")) cfgPath = args[i];
        }
        ServerConfig cfg = new ServerConfig(Path.of(cfgPath));
        if (games != null) System.setProperty("reo.games", games);

        List<String> sniffPorts = cfg.getList("sniff.ports", "");
        String mode = cfg.get("packetlog.mode", "off");
        if ("off".equalsIgnoreCase(mode) && !sniffPorts.isEmpty()) mode = "hex";
        PacketLog.init(mode, cfg.get("packetlog.file", "logs/packets.log"), cfg.getBool("packetlog.stdout", false));

        List<LobbyView> views = new ArrayList<>();
        for (String id : cfg.games()) {
            GameConfig gc = cfg.game(id);
            System.out.println("starting " + gc);
            switch (id) {
                case "file1": views.add(bioserver1.ServerMain.start(gc)); break;
                case "file2": views.add(bioserver2.ServerMain.start(gc)); break;
                default: throw new IllegalArgumentException("unknown game id: " + id);
            }
        }
        for (String p : sniffPorts) {
            Thread t = new Thread(new RawSniffer(cfg.get("bind", "0.0.0.0"), Integer.parseInt(p)), "sniffer-" + p);
            t.start();   // non-daemon: keeps the JVM alive in sniff-only mode
        }
        if (cfg.getBool("api.enabled", true)) new HttpApi(cfg, views).start();
        reo.common.RegWeb.startFor(cfg, views);
        reo.us.UsAppService.startFor(cfg);
        reo.us.KkService.startFor(cfg);
        System.out.println("reo-bioserver ready");
    }
}
