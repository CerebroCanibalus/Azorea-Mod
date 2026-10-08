// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.tracker.server;

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
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Relay TCP server para NAT-traversal fallback (ver AGENTS.md § F6.2 + F7).
 *
 * <p>Cuando el P2P directo falla (NAT simétrico, firewall), los peers pueden
 * usar el tracker como relay. El tracker acepta 2 conexiones TCP en el mismo
 * session_id y bridge los bytes bidireccionalmente.
 *
 * <p><b>§ Protocol:</b>
 * <pre>{@code
 * Peer conecta al relay TCP port.
 * Peer envía primera línea: <session_id>\n
 * Tracker verifica session_id y espera al segundo peer.
 * Cuando ambos conectados, bytes fluyen: peer1 → tracker → peer2 y viceversa.
 * }</pre>
 *
 * <p><b>§ Limitaciones v1:</b>
 * <ul>
 *   <li>Una session = 2 peers exactos. Si 3er peer conecta, se rechaza.</li>
 *   <li>Sin timeout de inactividad (debería cerrar tras N minutos sin tráfico).</li>
 *   <li>Tracker ve TODO el tráfico (no es E2E cifrado entre peers).</li>
 * </ul>
 */
public final class RelayServer {

    private static final Logger LOGGER = Logger.getLogger(RelayServer.class.getName());

    /** Estado de una session: esperando peer1, esperando peer2, o bridged. */
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

    private final int port;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicLong sessionCounter = new AtomicLong(0);
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        final Thread t = new Thread(r, "azorea-relay-bridge");
        t.setDaemon(true);
        return t;
    });
    private ServerSocket serverSocket;
    private volatile boolean running;

    public RelayServer(final int port) {
        this.port = port;
    }

    public void start() throws IOException {
        if (running) return;
        serverSocket = new ServerSocket(port);
        running = true;
        // Thread aceptador.
        final Thread acceptor = new Thread(this::acceptLoop, "azorea-relay-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
        LOGGER.info("RelayServer TCP listening on port " + serverSocket.getLocalPort());
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error closing server socket", e);
        }
        // Cerrar todas las sessions.
        sessions.values().forEach(s -> {
            s.closed = true;
            try { if (s.peer1 != null) s.peer1.close(); } catch (IOException ignored) {}
            try { if (s.peer2 != null) s.peer2.close(); } catch (IOException ignored) {}
        });
        executor.shutdownNow();
    }

    public int port() {
        return serverSocket != null ? serverSocket.getLocalPort() : port;
    }

    /** Crea una nueva session y devuelve el session_id. */
    public synchronized String createSession() {
        final String sessionId = "relay-" + sessionCounter.incrementAndGet() + "-"
                + Long.toHexString(System.currentTimeMillis());
        sessions.put(sessionId, new Session(sessionId));
        LOGGER.info("Relay session creada: " + sessionId + " (total: " + sessions.size() + ")");
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
            } catch (IOException e) {
                if (running) {
                    LOGGER.log(Level.WARNING, "Accept failed", e);
                }
            }
        }
    }

    private void handlePeer(final Socket socket) {
        try {
            socket.setSoTimeout(10_000);  // 10s para enviar session_id
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
                    LOGGER.info("Relay peer1 joined session " + sessionId);
                } else if (session.peer2 == null) {
                    session.peer2 = socket;
                    LOGGER.info("Relay peer2 joined session " + sessionId + " — bridging");
                    // Notify peers y arrancar bridge.
                    notifyReady(session);
                } else {
                    writeAndClose(socket, "ERR: session full\n");
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "handlePeer failed", e);
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void notifyReady(final Session session) {
        // Para v1 simple: escribimos "OK\n" a ambos y arrancamos bridge bidireccional.
        final Socket p1 = session.peer1;
        final Socket p2 = session.peer2;
        try {
            p1.getOutputStream().write("OK\n".getBytes(StandardCharsets.UTF_8));
            p1.getOutputStream().flush();
            p2.getOutputStream().write("OK\n".getBytes(StandardCharsets.UTF_8));
            p2.getOutputStream().flush();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "notifyReady failed", e);
        }
        executor.submit(() -> bridge(p1, p2, session.sessionId));
        executor.submit(() -> bridge(p2, p1, session.sessionId));
    }

    /** Bridge unidireccional: lee de `from`, escribe a `to`. Termina cuando uno cierra. */
    private void bridge(final Socket from, final Socket to, final String sessionId) {
        final InputStream in;
        final OutputStream out;
        try {
            in = from.getInputStream();
            out = to.getOutputStream();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "bridge setup failed", e);
            return;
        }
        final byte[] buf = new byte[16 * 1024];
        try {
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
            // Peer disconnect.
        } finally {
            try { from.close(); } catch (IOException ignored) {}
            try { to.close(); } catch (IOException ignored) {}
            LOGGER.info("Relay bridge cerrado: " + sessionId);
        }
    }

    private static void writeAndClose(final Socket socket, final String msg) {
        try {
            socket.getOutputStream().write(msg.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            socket.close();
        } catch (IOException ignored) {
            // ignore
        }
    }
}