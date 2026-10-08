// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.tracker.embedded;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Relay TCP embebido en el tracker (ver AGENTS.md § F8.x, F6.2 + F7).
 *
 * <p>Copia adaptada de `tracker-server/RelayServer.java` para uso in-process.
 *
 * <p>§ Protocol:
 * <pre>{@code
 * Peer conecta al relay TCP port.
 * Peer envía primera línea: <session_id>\n
 * Tracker verifica session_id y espera al segundo peer.
 * Cuando ambos conectados → "OK\n" a ambos → bridge bidireccional.
 * }</pre>
 *
 * <p>§ Uso para MC (relay fallback):
 * <ol>
 *   <li>Host: conecta al relay como peer1, bridgea a localhost:25565 (su MC server).</li>
 *   <li>Joiner: conecta al relay como peer2, bridgea a su proxy local.</li>
 *   <li>Joiner's MC client → local proxy → relay → host adapter → host MC server.</li>
 * </ol>
 *
 * <p>§ Limitaciones v1 (mismas que tracker-server):
 * <ul>
 *   <li>2 peers exactos por session (3ro rechazado).</li>
 *   <li>Sin timeout de inactividad.</li>
 *   <li>Relay ve TODO el tráfico (no E2E cifrado).</li>
 * </ul>
 */
public final class EmbeddedRelayServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmbeddedRelayServer.class);

    /** Estado de una session. */
    private static final class Session {
        final String sessionId;
        volatile Socket peer1;
        volatile Socket peer2;
        final long createdAtMs;
        volatile boolean closed;

        Session(final String id) {
            this.sessionId = id;
            this.createdAtMs = System.currentTimeMillis();
        }
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicLong sessionCounter = new AtomicLong(0);
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        final Thread t = new Thread(r, "azorea-relay-bridge");
        t.setDaemon(true);
        return t;
    });
    private ServerSocket serverSocket;
    private volatile boolean running;
    private final int configuredPort;

    public EmbeddedRelayServer(final int port) {
        this.configuredPort = port;
    }

    public void start() throws IOException {
        if (running) return;
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(configuredPort));
        running = true;
        final Thread acceptor = new Thread(this::acceptLoop, "azorea-relay-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
        LOGGER.info("Embedded relay TCP escuchando en puerto {}", port());
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
        } catch (final IOException ignored) {
        }
        sessions.values().forEach(s -> {
            s.closed = true;
            try { if (s.peer1 != null) s.peer1.close(); } catch (IOException ignored) {}
            try { if (s.peer2 != null) s.peer2.close(); } catch (IOException ignored) {}
        });
        executor.shutdownNow();
    }

    public int port() {
        return serverSocket == null ? configuredPort : serverSocket.getLocalPort();
    }

    public boolean isRunning() {
        return running;
    }

    public synchronized String createSession() {
        final String sessionId = "relay-" + sessionCounter.incrementAndGet() + "-"
                + Long.toHexString(System.currentTimeMillis());
        sessions.put(sessionId, new Session(sessionId));
        LOGGER.info("Relay session creada: {} (total={})", sessionId, sessions.size());
        return sessionId;
    }

    public int activeSessions() {
        return sessions.size();
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket socket = serverSocket.accept();
                executor.submit(() -> handlePeer(socket));
            } catch (final IOException e) {
                if (running) LOGGER.debug("Relay accept falló: {}", e.getMessage());
            }
        }
    }

    private void handlePeer(final Socket socket) {
        try {
            socket.setSoTimeout(10_000);
            final BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            final String sessionId = reader.readLine();
            if (sessionId == null || sessionId.isBlank()) {
                socket.close();
                return;
            }
            final Session session = sessions.get(sessionId.trim());
            if (session == null || session.closed) {
                writeAndClose(socket, "ERR: unknown session\n");
                return;
            }
            synchronized (session) {
                if (session.peer1 == null) {
                    session.peer1 = socket;
                    LOGGER.debug("Relay peer1 unido a {}", sessionId);
                } else if (session.peer2 == null) {
                    session.peer2 = socket;
                    LOGGER.debug("Relay peer2 unido a {} — bridging", sessionId);
                    notifyReady(session);
                } else {
                    writeAndClose(socket, "ERR: session full\n");
                }
            }
        } catch (final IOException e) {
            LOGGER.debug("Relay handlePeer falló: {}", e.getMessage());
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void notifyReady(final Session session) {
        final Socket p1 = session.peer1;
        final Socket p2 = session.peer2;
        try {
            p1.getOutputStream().write("OK\n".getBytes(StandardCharsets.UTF_8));
            p1.getOutputStream().flush();
            p2.getOutputStream().write("OK\n".getBytes(StandardCharsets.UTF_8));
            p2.getOutputStream().flush();
        } catch (final IOException e) {
            LOGGER.warn("Relay notifyReady falló: {}", e.getMessage());
        }
        executor.submit(() -> bridge(p1, p2, session.sessionId));
        executor.submit(() -> bridge(p2, p1, session.sessionId));
    }

    private void bridge(final Socket from, final Socket to, final String sessionId) {
        final InputStream in;
        final OutputStream out;
        try {
            in = from.getInputStream();
            out = to.getOutputStream();
        } catch (final IOException e) {
            return;
        }
        final byte[] buf = new byte[16 * 1024];
        try {
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (final IOException ignored) {
        } finally {
            try { from.close(); } catch (IOException ignored) {}
            try { to.close(); } catch (IOException ignored) {}
            LOGGER.debug("Relay bridge cerrado: {}", sessionId);
        }
    }

    private static void writeAndClose(final Socket socket, final String msg) {
        try {
            socket.getOutputStream().write(msg.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
