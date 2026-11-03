/*
    reo-web bioserver - fan server for Resident Evil Outbreak File #1/#2
    Based on BioServer (C) 2013-2019 obsrv.org; modifications (C) 2026 reo-web contributors.

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version. See LICENSE.txt.
*/

package reo.common;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Loads config/server.properties and, if present, config/local.properties
 * (machine specific overrides such as the DB password; not committed).
 */
public final class ServerConfig {
    private final Properties p = new Properties();
    public final Path baseFile;

    public ServerConfig(Path file) throws IOException {
        this.baseFile = file;
        load(file, true);
        Path local = file.resolveSibling("local.properties");
        load(local, false);
    }

    private void load(Path f, boolean required) throws IOException {
        if (!Files.exists(f)) {
            if (required) throw new IOException("config file not found: " + f.toAbsolutePath());
            return;
        }
        try (InputStream in = new FileInputStream(f.toFile())) {
            p.load(in);
        }
    }

    public String get(String key, String def) {
        String sys = System.getProperty("reo." + key);      // -Dreo.key=value overrides files
        if (sys != null) return sys.trim();
        String v = p.getProperty(key);
        return v == null ? def : v.trim();
    }

    public int getInt(String key, int def) {
        String v = get(key, null);
        return (v == null || v.isEmpty()) ? def : Integer.parseInt(v);
    }

    public boolean getBool(String key, boolean def) {
        String v = get(key, null);
        return (v == null || v.isEmpty()) ? def : ("true".equalsIgnoreCase(v) || "1".equals(v) || "yes".equalsIgnoreCase(v));
    }

    public List<String> getList(String key, String def) {
        List<String> out = new ArrayList<>();
        for (String s : get(key, def).split(",")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    /** Games to start: "file1,file2". */
    public List<String> games() {
        return getList("games", "file1,file2");
    }

    public GameConfig game(String id) {
        GameConfig g = new GameConfig();
        g.id = id;
        boolean f1 = "file1".equals(id);
        g.title = get(id + ".title", f1 ? "Resident Evil Outbreak File #1" : "Resident Evil Outbreak File #2");
        g.bind = get(id + ".bind", get("bind", "0.0.0.0"));
        g.lobbyPort = getInt(id + ".lobby_port", f1 ? 8300 : 8200);
        g.gamePort = getInt(id + ".game_port", f1 ? 8690 : 8590);
        g.gsIp = get(id + ".gs_ip", get("public_ip", "127.0.0.1"));
        g.gsPort = getInt(id + ".gs_port", g.gamePort);
        g.dbName = get(id + ".db.name", f1 ? "bioserver1" : "bioserver2");
        String host = get("db.host", "localhost");
        int port = getInt("db.port", 3306);
        g.dbUrl = get(id + ".db.url", "jdbc:mariadb://" + host + ":" + port + "/" + g.dbName
                + "?useUnicode=true&characterEncoding=UTF-8");
        g.dbUser = get(id + ".db.user", get("db.user", "bioserver"));
        g.dbPassword = get(id + ".db.password", get("db.password", ""));
        g.protocol = get(id + ".protocol", "jp");
        return g;
    }

    /**
     * Hostnames the PS2 client resolves, mapped to the IP that the DNS override
     * (net/gateway) must answer with. Keys: hosts.<name>=<fqdn>.
     */
    public Map<String, String> dnsOverrides() {
        String ip = get("public_ip", "127.0.0.1");
        Map<String, String> m = new LinkedHashMap<>();
        for (String name : p.stringPropertyNames()) {
            if (name.startsWith("hosts.")) m.put(get(name, ""), ip);
        }
        return m;
    }

    /** Named hostnames (hosts.lobby -> app01...). */
    public Map<String, String> hosts() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String name : p.stringPropertyNames()) {
            if (name.startsWith("hosts.")) m.put(name.substring(6), get(name, ""));
        }
        return m;
    }
}
