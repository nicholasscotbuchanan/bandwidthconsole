package com.bwtest.console.net;

import java.net.ServerSocket;
import java.net.Socket;

/** Accepts agent connections. Agents dial in (so they can sit behind NAT and
 *  still reach the nexus); each gets an {@link AgentConnection} on its own thread. */
public class ControlServer {
    private final int port;
    private final ControlListener listener;
    private volatile boolean running;
    private ServerSocket server;

    public ControlServer(int port, ControlListener listener) {
        this.port = port;
        this.listener = listener;
    }

    public int port() { return port; }

    public void start() throws Exception {
        server = new ServerSocket(port);
        running = true;
        Thread accept = new Thread(this::acceptLoop, "control-accept");
        accept.setDaemon(true);
        accept.start();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                AgentConnection conn = new AgentConnection(s, listener);
                Thread t = new Thread(conn::run, "agent-conn");
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                if (running) {
                    try { Thread.sleep(100); } catch (InterruptedException ignore) {}
                }
            }
        }
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignore) {}
    }
}
