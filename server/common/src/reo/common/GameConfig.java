/*
    reo-web bioserver - fan server for Resident Evil Outbreak File #1/#2
    Based on BioServer (C) 2013-2019 obsrv.org; modifications (C) 2026 reo-web contributors.

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version. See LICENSE.txt.
*/

package reo.common;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Settings of one game instance (File #1 or File #2).
 * Built by ServerConfig from config/server.properties (+ config/local.properties).
 */
public final class GameConfig {
    public String id;            // "file1" / "file2"
    public String title;         // human readable
    public String bind;          // address to bind lobby/game sockets ("0.0.0.0" = all)
    public int lobbyPort;        // lobby TCP port we listen on
    public int gamePort;         // gameserver (relay) TCP port we listen on
    public String gsIp;          // gameserver IP advertised to clients in GSINFO (0x6916)
    public int gsPort;           // gameserver port advertised in GSINFO
    public String dbUrl;         // JDBC url
    public String dbName;
    public String dbUser;
    public String dbPassword;
    public String protocol;      // "jp" (Bioserver protocol). Placeholder for a future "us" profile.

    public InetAddress bindAddress() {
        if (bind == null || bind.isEmpty() || "0.0.0.0".equals(bind) || "*".equals(bind)) return null;
        try {
            return InetAddress.getByName(bind);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("invalid bind address: " + bind, e);
        }
    }

    @Override
    public String toString() {
        return id + " (" + title + ") lobby=" + bind + ":" + lobbyPort + " game=" + bind + ":" + gamePort
                + " advertised-gs=" + gsIp + ":" + gsPort + " db=" + dbName + " protocol=" + protocol;
    }
}
