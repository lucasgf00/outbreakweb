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

import reo.common.GameConfig;

/**
 * Configuration of this game instance.
 * Originally read ./config.properties; now filled from the GameConfig given to
 * ServerMain.start() (config/server.properties + config/local.properties).
 */
public class Configuration {
    public String gs_ip;        // gameserver IP advertised in GSINFO
    public int    gs_port;      // gameserver port advertised in GSINFO
    public String db_url;
    public String db_user;
    public String db_password;

    public Configuration() {
        GameConfig c = ServerMain.cfg;
        this.gs_ip       = c.gsIp;
        this.gs_port     = c.gsPort;
        this.db_url      = c.dbUrl;
        this.db_user     = c.dbUser;
        this.db_password = c.dbPassword;
    }
}
