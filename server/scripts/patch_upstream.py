#!/usr/bin/env python3
"""One-shot patcher used to derive file1/ and file2/ from the verbatim upstream import.

Kept in the repo to document exactly what was changed mechanically (the commit diff
shows the result). Not meant to be re-run on already patched sources.
Usage: scripts/patch_upstream.py <tree> <N>   e.g. file1/src/bioserver1 1
"""
import re
import sys
from pathlib import Path

tree = Path(sys.argv[1])
n = sys.argv[2]
pkg = f"bioserver{n}"


def edit(name, fn):
    p = tree / name
    raw = p.read_bytes().decode("latin-1")
    crlf = "\r\n" in raw
    txt = raw.replace("\r\n", "\n")
    new = fn(txt)
    if new == txt:
        raise SystemExit(f"{name}: no change")
    if crlf:
        new = new.replace("\n", "\r\n")
    p.write_bytes(new.encode("latin-1"))


def must(txt, old, new, count=1):
    c = txt.count(old)
    if c != count:
        raise SystemExit(f"expected {count} x {old!r}, found {c}")
    return txt.replace(old, new)


HEADER_NOTE = ("\n    Modified 2026 by reo-web contributors (see README.md / git history).\n")

# 1. package rename + modification notice in every upstream file
for p in sorted(tree.glob("*.java")):
    def f(t):
        t = must(t, "package bioserver;", f"package {pkg};")
        t = re.sub(r"(Copyright \(C\) 2013-2019 obsrv\.org[^\n]*\n)", lambda m: m.group(1) + HEADER_NOTE, t, count=1)
        return t
    edit(p.name, f)

# 2. Configuration: values come from config/server.properties via reo.common.GameConfig
def conf(t):
    i = t.index("package ")
    return t[:i] + f"""package {pkg};

import reo.common.GameConfig;

/**
 * Configuration of this game instance.
 * Originally read ./config.properties; now filled from the GameConfig given to
 * ServerMain.start() (config/server.properties + config/local.properties).
 */
public class Configuration {{
    public String gs_ip;        // gameserver IP advertised in GSINFO
    public int    gs_port;      // gameserver port advertised in GSINFO
    public String db_url;
    public String db_user;
    public String db_password;

    public Configuration() {{
        GameConfig c = ServerMain.cfg;
        this.gs_ip       = c.gsIp;
        this.gs_port     = c.gsPort;
        this.db_url      = c.dbUrl;
        this.db_user     = c.dbUser;
        this.db_password = c.dbPassword;
    }}
}}
"""
edit("Configuration.java", conf)

# 3. Database: java.sql types, configurable URL, prepared statements (no string-built SQL)
def db(t):
    t = must(t, "import com.mysql.jdbc.Connection;\nimport com.mysql.jdbc.PreparedStatement;\n",
             "import java.sql.Connection;\nimport java.sql.PreparedStatement;\n")
    t = re.sub(r'private final String url = "jdbc:mysql://localhost:3306/bioserver2?"\n\s*\+"\?useUnicode=true&characterEncoding=UTF-8";',
               "private final String url = ServerMain.cfg.dbUrl;   // was hardcoded jdbc:mysql://localhost:3306/...", t)
    t = t.replace("(PreparedStatement) con.prepareStatement", "con.prepareStatement")
    t = t.replace("con = (Connection) DriverManager", "con = DriverManager")
    t = t.replace("this.con = (Connection) DriverManager", "this.con = DriverManager")
    reps = [
        ('con.prepareStatement(String.format("select userid from sessions where sessid=\'%s\'", sessid));',
         'con.prepareStatement("select userid from sessions where sessid=?");\n            pst.setString(1, sessid);'),
        ('con.prepareStatement(String.format("select handle,nickname from hnpairs where userid=\'%s\' limit 0,3", userid));',
         'con.prepareStatement("select handle,nickname from hnpairs where userid=? limit 0,3");\n            pst.setString(1, userid);'),
        ('con.prepareStatement(String.format("select count(*) as cnt from hnpairs where handle=\'%s\'", handle));',
         'con.prepareStatement("select count(*) as cnt from hnpairs where handle=?");\n            pst.setString(1, handle);'),
        ('con.prepareStatement(String.format("insert into hnpairs (userid,handle,nickname) values (\'%s\',\'%s\',\'%s\')", \n                    cl.getUserID(), new String(cl.getHNPair().getHandle()), nickname));',
         'con.prepareStatement("insert into hnpairs (userid,handle,nickname) values (?,?,?)");\n            pst.setString(1, cl.getUserID());\n            pst.setString(2, new String(cl.getHNPair().getHandle()));\n            pst.setString(3, nickname);'),
        ('con.prepareStatement(String.format("update hnpairs set nickname=\'%s\' where userid=\'%s\' and handle=\'%s\'", \n                    nickname, cl.getUserID(), new String(cl.getHNPair().getHandle())));',
         'con.prepareStatement("update hnpairs set nickname=? where userid=? and handle=?");\n            pst.setString(1, nickname);\n            pst.setString(2, cl.getUserID());\n            pst.setString(3, new String(cl.getHNPair().getHandle()));'),
        ('con.prepareStatement(String.format("update sessions set gamesess=\'%s\' where userid=\'%s\'", gamenum, userid));',
         'con.prepareStatement("update sessions set gamesess=? where userid=?");\n            pst.setString(1, gamenum);\n            pst.setString(2, userid);'),
        ('con.prepareStatement(String.format("select gamesess from sessions where userid=\'%s\'", userid));',
         'con.prepareStatement("select gamesess from sessions where userid=?");\n            pst.setString(1, userid);'),
    ]
    for a, b in reps:
        t = must(t, a, b)
    t = t.replace("(new Integer(gamenumber)).toString()", "Integer.toString(gamenumber)")
    if "String.format(\"" in t and "prepareStatement(String.format" in t:
        raise SystemExit("Database: string-built SQL left")
    return t
edit("Database.java", db)

# 4. ClientList: thread-safe list so the HTTP API can iterate while the lobby mutates it
def cl(t):
    t = must(t, "        clients = new LinkedList();", "        clients = new java.util.concurrent.CopyOnWriteArrayList();   // was LinkedList (HTTP API reads it concurrently)")
    return t
edit("ClientList.java", cl)

# 5. Socket threads: packet log hooks + report bind failures
for name, chan in (("ServerThread.java", "lobby"), ("GameServerThread.java", "game")):
    def th(t, chan=chan):
        t = must(t, "        if (numRead == -1) {",
                 f"        if (numRead > 0 && reo.common.PacketLog.enabled()) {{\n"
                 f"            reo.common.PacketLog.log(ServerMain.cfg.id + \"/{chan}\", true, socketChannel,\n"
                 f"                    this.readBuffer.buf.array(), this.readBuffer.buf.position() - numRead, numRead);\n"
                 f"        }}\n\n        if (numRead == -1) {{")
        t = re.sub(r"(    public void send\(SocketChannel socket, byte\[\] data\) \{\n)",
                   lambda m: m.group(1) + f"        if (reo.common.PacketLog.enabled()) reo.common.PacketLog.log(ServerMain.cfg.id + \"/{chan}\", false, socket, data, 0, data.length);\n",
                   t, count=1)
        t = re.sub(r'(Logging\.println\("(?:Lobbyserver|Gameserver|GameServer) constructor exception caught!)"\);',
                   lambda m: m.group(1) + ' " + ex + " (port " + port + ")");', t, count=1)
        return t
    edit(name, th)

# 6. PacketHandler: advertised gameserver port from config, accessors for the status view
def ph(t):
    t = must(t, "    private byte[] gs_ip;", "    private byte[] gs_ip;\n    private int gs_port;    // advertised gameserver port (config)")
    t = must(t, "            tmp_ip = InetAddress.getByName(conf.gs_ip);",
             "            this.gs_port = conf.gs_port;\n            tmp_ip = InetAddress.getByName(conf.gs_ip);")
    t = must(t, "        gsinfo[5] = this.gs_ip[3];",
             "        gsinfo[5] = this.gs_ip[3];\n        gsinfo[8] = (byte) ((this.gs_port >> 8) & 0xff);   // port no longer hardcoded\n        gsinfo[9] = (byte) (this.gs_port & 0xff);")
    t = must(t, "    // set the corresponding packethandler of gameserver\n",
             "    // accessors for the read-only status view (HTTP API)\n"
             "    Areas getAreas() { return areas; }\n"
             "    Rooms getRooms() { return rooms; }\n"
             "    Slots getSlots() { return slots; }\n"
             "    GameServerPacketHandler getGameServerPacketHandler() { return gameserverpackethandler; }\n\n"
             "    // set the corresponding packethandler of gameserver\n")
    return t
edit("PacketHandler.java", ph)

if n == "1":
    # 7. Patch: the 1.01 patch data is game-derived and not shipped; do not crash without it
    def pa(t):
        t = must(t, "        Logging.println(\"Patch loaded: \" + this.patchData.length + \" bytes\");",
                 "        if (this.patchData == null) this.patchData = new byte[0];   // patch.raw not shipped\n"
                 "        Logging.println(\"Patch loaded: \" + this.patchData.length + \" bytes\");")
        t = must(t, "            Logger.getLogger(Patch.class.getName()).log(Level.SEVERE, null, ex);",
                 "            Logging.println(\"Patch: patch.raw not found, update to 1.01 disabled\");")
        return t
    edit("Patch.java", pa)

print("patched", tree)
