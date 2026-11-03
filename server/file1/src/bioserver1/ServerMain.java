/*
    BioServer - Emulation of the long gone server for 
                Biohazard Outbreak File #1 (Playstation 2)

    Copyright (C) 2013-2019 obsrv.org (no23@deathless.net)

    Modified 2026 by reo-web contributors (see README.md / git history).

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package bioserver1;

import java.util.Date;
import reo.common.GameConfig;
import reo.common.LobbyView;

/**
 * Starts lobby server, gameserver (relay) and heartbeat for File #1.
 * Originally a standalone main(); now started by reo.Main with a GameConfig
 * so ports, gameserver address and database come from config/server.properties.
 */
public class ServerMain {
    // configuration of this instance, set by start()
    public static GameConfig cfg;

    // the server listens on these ports (upstream defaults; overridden by config)
    public static int LOBBYPORT = 8300;
    public static int GAMEPORT = 8690;

    public static LobbyView start(GameConfig config) throws InterruptedException {
        cfg = config;
        LOBBYPORT = config.lobbyPort;
        GAMEPORT = config.gamePort;

        System.out.println("------------------------------\n"+
                           "-     fanmade server for     -\n"+
                           "- Biohazard Outbreak File #1 -\n"+
                           "-                            -\n"+
                           "- (c) 2013-2019 obsrv.org    -\n"+
                           "-        no23@deathless.net  -\n"+
                           "------------------------------\n");

        // setup the packethandler in his own thread
        PacketHandler packethandler = new PacketHandler();
        new Thread(packethandler, cfg.id + "-lobby-handler").start();

        // create the lobbyserver thread
        ServerThread server = new ServerThread(config.bindAddress(), LOBBYPORT, packethandler);
        new Thread(server, cfg.id + "-lobby").start();

        // create a simple gameserver
        GameServerPacketHandler packethandler2 = new GameServerPacketHandler();
        new Thread(packethandler2, cfg.id + "-game-handler").start();
        GameServerThread gsserver = new GameServerThread(config.bindAddress(), GAMEPORT, packethandler2);
        new Thread(gsserver, cfg.id + "-game").start();

        // allow usage
        packethandler.setGameServerPacketHandler(packethandler2);

        Thread.sleep(1000);

        // last but not least
        // create a thread for the keepalivepings and cleanups
        new Thread(new HeartBeatThread(server, packethandler, gsserver, packethandler2), cfg.id + "-heartbeat").start();

        System.out.println(new Date().toString() + " " + cfg.id + " server started: " + cfg);
        return new StatusView(config, packethandler);
    }
}
