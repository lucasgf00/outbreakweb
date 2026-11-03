/*
    reo-web bioserver - fan server for Resident Evil Outbreak File #1/#2
    (C) 2026 reo-web contributors. AGPL-3.0-or-later, see LICENSE.txt.
*/

package reo.common;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Capture-only TCP listener for ports we do not understand yet (e.g. whatever
 * the US client opens towards app01/snap01). Accepts, logs every received byte
 * through PacketLog (forced to at least header mode) and never answers.
 * Config: sniff.ports=1234,5678 (empty = disabled).
 */
public final class RawSniffer implements Runnable {
    private final int port;
    private final String bind;

    public RawSniffer(String bind, int port) {
        this.bind = bind;
        this.port = port;
    }

    @Override
    public void run() {
        try (ServerSocket ss = new ServerSocket()) {
            InetAddress a = ("0.0.0.0".equals(bind) || bind.isEmpty()) ? null : InetAddress.getByName(bind);
            ss.bind(new InetSocketAddress(a, port));
            System.out.println("sniffer listening on " + bind + ":" + port);
            while (true) {
                Socket s = ss.accept();
                Thread t = new Thread(() -> drain(s), "sniff-" + port);
                t.setDaemon(true);
                t.start();
            }
        } catch (IOException e) {
            System.out.println("sniffer on port " + port + " failed: " + e);
        }
    }

    private void drain(Socket s) {
        String peer = String.valueOf(s.getRemoteSocketAddress());
        String ch = "sniff:" + port;
        PacketLog.log(ch, true, peer + " (connected)", new byte[0], 0, 0);
        System.out.println(ch + " connection from " + peer);
        byte[] buf = new byte[8192];
        try (Socket sock = s; InputStream in = sock.getInputStream()) {
            sock.setSoTimeout(120_000);
            int n;
            while ((n = in.read(buf)) > 0) PacketLog.log(ch, true, peer, buf, 0, n);
        } catch (IOException e) {
            // closed / timeout
        }
        System.out.println(ch + " closed " + peer);
    }
}
