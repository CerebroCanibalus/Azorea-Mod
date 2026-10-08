// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.tracker;

import com.azorea.mod.v1211.AzoreaNetLog;
import com.azorea.mod.v1211.AzoreaNetLog.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cliente TCP para el relay del tracker (ver AGENTS.md § F8.x, F6.2 + F7).
 *
 * <p>§ Dos modos:
 * <ul>
 *   <li><b>Joiner (startLocalProxy)</b>: abre ServerSocket local → MC client conecta
 *       aquí → bridgea al relay → host adapter → host MC server.</li>
 *   <li><b>Host (connectAsHost)</b>: conecta al relay → bridgea a localhost:mcPort
 *       (su MC server via autohost).</li>
 * </ul>
 *
 * <p>§ Protocol con relay:
 * <ol>
 *   <li>Conectar TCP a relay.</li>
 *   <li>Enviar {@code session_id + "\n"}.</li>
 *   <li>Esperar respuesta {@code "OK\n"} (significa que el peer ya está y bridge está activo).</li>
 *   <li>Bridge bytes bidireccional.</li>
 * </ol>
 *
 * <p>§ Limitación: funciona solo si AMBOS peers alcanzan el tracker (LAN o bootstrap público).
 */
public final class AzoreaRelayClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzoreaRelayClient.class);

    private AzoreaRelayClient() {
    }

    // ===== Joiner mode: local proxy → relay =====

    /**
     * Arranca proxy local que espera la conexión de MC client y la bridgea al relay.
     *
     * @return puerto local donde MC debe conectar, o -1 si falló
     */
    public static int startLocalProxy(final String sessionId,
                                      final String relayHost, final int relayPort) {
        try {
            final ServerSocket server = new ServerSocket(0);
            final int localPort = server.getLocalPort();
            final Thread acceptThread = new Thread(
                    () -> handleJoinerClient(server, sessionId, relayHost, relayPort),
                    "azorea-relay-joiner-" + localPort);
            acceptThread.setDaemon(true);
            acceptThread.start();
            AzoreaNetLog.milestone(Category.RELAY, "joiner-proxy",
                    "localPort=" + localPort + " → relay=" + relayHost + ":" + relayPort
                            + " session=" + sessionId);
            return localPort;
        } catch (final IOException e) {
            AzoreaNetLog.failure(Category.RELAY, "joiner-proxy",
                    "session=" + sessionId, "no pude abrir ServerSocket: " + e.getMessage());
            return -1;
        }
    }

    private static void handleJoinerClient(final ServerSocket server,
                                           final String sessionId,
                                           final String relayHost, final int relayPort) {
        while (!server.isClosed()) {
            try {
                final Socket mcSocket = server.accept();
                AzoreaNetLog.attempt(Category.RELAY, "joiner-connect",
                        "MC client conectado → abriendo tunnel al relay");
                final Socket relaySocket = connectAndHandshake(sessionId, relayHost, relayPort);
                if (relaySocket == null) {
                    mcSocket.close();
                    continue;
                }
                AzoreaNetLog.milestone(Category.RELAY, "joiner-bridged",
                        "session=" + sessionId);
                bridge(mcSocket, relaySocket, "joiner");
            } catch (final IOException e) {
                if (!server.isClosed()) {
                    AzoreaNetLog.failure(Category.RELAY, "joiner-proxy", "session=" + sessionId,
                            e.toString());
                }
            }
        }
        try {
            server.close();
        } catch (final IOException ignored) {
        }
    }

    // ===== Host mode: relay → localhost:mcPort =====

    /**
     * Conecta al relay como host y bridgea a {@code localhost:mcPort} (su MC server).
     * Corre en background (daemon thread).
     *
     * @return true si la conexión al relay se estableció
     */
    public static boolean connectAsHost(final String sessionId,
                                        final String relayHost, final int relayPort,
                                        final int mcPort) {
        AzoreaNetLog.attempt(Category.RELAY, "host-connect",
                "relay=" + relayHost + ":" + relayPort + " session=" + sessionId
                        + " → localhost:" + mcPort);
        final Thread t = new Thread(() -> {
            final Socket relaySocket = connectAndHandshake(sessionId, relayHost, relayPort);
            if (relaySocket == null) return;
            // Conectar al MC server local.
            try {
                final Socket mcSocket = new Socket();
                mcSocket.connect(new InetSocketAddress("127.0.0.1", mcPort), 5000);
                AzoreaNetLog.milestone(Category.RELAY, "host-bridged",
                        "session=" + sessionId + " mc=localhost:" + mcPort);
                bridge(relaySocket, mcSocket, "host");
            } catch (final IOException e) {
                AzoreaNetLog.failure(Category.RELAY, "host-connect",
                        "localhost:" + mcPort, "no pude conectar al MC server: " + e.getMessage());
                try {
                    relaySocket.close();
                } catch (final IOException ignored) {
                }
            }
        }, "azorea-relay-host-" + sessionId);
        t.setDaemon(true);
        t.start();
        return true;  // conexión async — éxito = thread lanzado
    }

    // ===== Shared =====

    /** Conecta al relay + handshake (session_id → espera "OK"). Devuelve socket o null. */
    private static Socket connectAndHandshake(final String sessionId,
                                              final String relayHost, final int relayPort) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(relayHost, relayPort), 5000);
            final OutputStream out = socket.getOutputStream();
            final InputStream in = socket.getInputStream();
            out.write((sessionId + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            // Leer respuesta (esperamos "OK\n").
            final byte[] buf = new byte[64];
            final int n = in.read(buf);
            if (n <= 0) {
                AzoreaNetLog.failure(Category.RELAY, "handshake",
                        "session=" + sessionId, "relay cerró conexión sin responder");
                socket.close();
                return null;
            }
            final String response = new String(buf, 0, n, StandardCharsets.UTF_8).trim();
            if (!response.startsWith("OK")) {
                AzoreaNetLog.failure(Category.RELAY, "handshake",
                        "session=" + sessionId, "respuesta inesperada: " + response);
                socket.close();
                return null;
            }
            AzoreaNetLog.success(Category.RELAY, "handshake", "session=" + sessionId, 0);
            return socket;
        } catch (final IOException e) {
            AzoreaNetLog.failure(Category.RELAY, "handshake",
                    relayHost + ":" + relayPort + " session=" + sessionId, e.toString());
            if (socket != null) {
                try {
                    socket.close();
                } catch (final IOException ignored) {
                }
            }
            return null;
        }
    }

    /** Bridge bidireccional entre 2 sockets (daemon threads). */
    private static void bridge(final Socket a, final Socket b, final String tag) {
        final AtomicBoolean closed = new AtomicBoolean(false);
        final Thread t1 = new Thread(() -> {
            try {
                pipe(a.getInputStream(), b.getOutputStream(), closed, tag + "-a2b");
            } catch (final IOException e) {
                AzoreaNetLog.debug(Category.RELAY, tag + "-a2b setup failed: " + e.getMessage());
            }
        }, "azorea-relay-" + tag + "-a2b");
        final Thread t2 = new Thread(() -> {
            try {
                pipe(b.getInputStream(), a.getOutputStream(), closed, tag + "-b2a");
            } catch (final IOException e) {
                AzoreaNetLog.debug(Category.RELAY, tag + "-b2a setup failed: " + e.getMessage());
            }
        }, "azorea-relay-" + tag + "-b2a");
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();
    }

    private static void pipe(final InputStream in, final OutputStream out,
                             final AtomicBoolean closed, final String tag) {
        long bytes = 0;
        try {
            final byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                out.flush();
                bytes += n;
            }
        } catch (final IOException ignored) {
        } finally {
            closed.set(true);
            try { in.close(); } catch (IOException ignored) {}
            try { out.close(); } catch (IOException ignored) {}
            AzoreaNetLog.debug(Category.RELAY, "pipe " + tag + " cerrado, bytes=" + bytes);
        }
    }
}
