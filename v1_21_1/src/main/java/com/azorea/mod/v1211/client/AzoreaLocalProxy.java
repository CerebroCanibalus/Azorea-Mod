// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client;

import com.azorea.mod.v1211.AzoreaNetLog;
import com.azorea.mod.v1211.AzoreaNetLog.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Proxy local TCP↔TCP — el paso 3 (DA-12). Puentea el socket puncheado c/ MC.
 *
 * <p>§ Por qué MC no ve directamente el socket puncheado:
 * <pre>
 *   MC client ──► 127.0.0.1:PUERTO_LOCAL  (este proxy)
 *                      │  byte pump puro
 *                      ▼
 *                socket puncheado  ── NAT ──►  peer
 * </pre>
 * Vanilla exige q/ el usuario escriba host:puerto ⇒ por eso el joiner necesita un
 * <b>ServerSocket en loopback</b> al q/ apuntar (Pattern PeerCraft: su `127.0.0.1:25566`).
 *
 * <p>§ <b>Sin capa d/ fiabilidad</b> — y ése es el punto. El tramo puncheado es un
 * <b>TCP real</b> (simultaneous open), así q/ orden y retransmisión ya los pone el SO.
 * Con un túnel UDP habría q/ implementar un mini-TCP encima (secuencias, ACKs,
 * reordenado, congestión). Por eso este componente es un <b>byte pump</b> d/ ~80 líneas
 * y no un protocolo.
 *
 * <p>§ Los dos roles:
 * <ul>
 *   <li><b>Joiner</b> {@link #startJoinerProxy} — escucha en loopback, c/ conexión aceptada
 *       la bridgea al socket puncheado q/ ya existe.</li>
 *   <li><b>Host</b> {@link #startHostBridge} — bridgea el socket puncheado c/ su
 *       `localhost:25565` (el integrated server, q/ ya escucha).</li>
 * </ul>
 */
public final class AzoreaLocalProxy {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzoreaLocalProxy.class);
    /** Buffer d/ bomba — 16 KiB ⇒ 1 vuelta p/ un paquete d/ chunks típico. */
    private static final int BUFFER = 16 * 1024;

    private AzoreaLocalProxy() {
    }

    /**
     * ¿El socket sirve p/ ser puenteado? Tiene q/ existir, estar <b>conectado</b> y vivo.
     *
     * <p>§ `isClosed()` solo es true tras `close()` — un `new Socket()` recién creado no
     * está cerrado, ⊘ conectado. S/ `isConnected()` un socket sin handshaker pasaría el
     * guard y dejaríamos un proxy q/ nunca drenaría.
     */
    private static boolean isUsable(final Socket s) {
        return s != null && !s.isClosed() && s.isConnected();
    }

    // ===== Joiner =====

    /**
     * Abre un ServerSocket en loopback al q/ se conecta el MC client del joiner.
     *
     * @param punched socket TCP ya puncheado (o directo) hacia el host
     * @param label   p/ los logs
     * @return puerto local p/ `ConnectScreen`, o -1 si ⊘ se pudo abrir
     */
    public static int startJoinerProxy(final Socket punched, final String label) {
        if (!isUsable(punched)) {
            AzoreaNetLog.failure(Category.RELAY, "joiner-proxy", label,
                    "socket puncheado nulo/cerrado/sin-conectar");
            return -1;
        }
        try {
            // § SOLO loopback: nunca exponer esto a la red — es un puente interno.
            final ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            final int localPort = server.getLocalPort();

            final Thread acceptor = new Thread(() -> {
                while (!server.isClosed()) {
                    try {
                        final Socket mc = server.accept();
                        // § CERRAR aquí — era un bug real. MC sólo reconecta una vez y el
                        //   ServerSocket se quedaba abierto para SIEMPRE: un puerto retenido
                        //   de más. En Windows encima es tramposo — con SO_REUSEADDR el
                        //   `bind(0)` del DÉCIMO proceso puede devolver ese mismo puerto y
                        //   el punch falla con `BindException` en un test q/ ni lo usa.
                        try {
                            server.close();
                        } catch (final IOException ignored) {
                        }
                        AzoreaNetLog.milestone(Category.RELAY, "joiner-bridge",
                                label + " MC → " + punched.getRemoteSocketAddress());
                        bridge(mc, punched, label);
                        return;   // § 1 conexión p/ sesión: MC no re-conecta
                    } catch (final IOException e) {
                        if (!server.isClosed()) {
                            AzoreaNetLog.failure(Category.RELAY, "joiner-proxy", label, e.toString());
                        }
                    }
                }
            }, "azorea-proxy-accept-" + localPort);
            acceptor.setDaemon(true);
            acceptor.start();

            AzoreaNetLog.milestone(Category.RELAY, "joiner-proxy",
                    label + " escuchando 127.0.0.1:" + localPort);
            return localPort;
        } catch (final IOException e) {
            AzoreaNetLog.failure(Category.RELAY, "joiner-proxy", label,
                    "no pude abrir ServerSocket loopback: " + e.getMessage());
            return -1;
        }
    }

    // ===== Host =====

    /**
     * Bridgea el socket puncheado c/ {@code 127.0.0.1:mcPort} en background.
     *
     * <p>Corre inmediatamente (el integrated server ya escucha) — no espera conexión.
     *
     * @param punched socket TCP puncheado c/ el joiner
     * @param mcPort  puerto d/ su MC server
     * @param label   p/ logs
     * @return false si el socket estaba cerrado o el MC s/ acepta
     */
    public static boolean startHostBridge(final Socket punched, final int mcPort,
                                          final String label) {
        if (!isUsable(punched)) {
            AzoreaNetLog.failure(Category.RELAY, "host-bridge", label,
                    "socket puncheado nulo/cerrado/sin-conectar");
            return false;
        }
        try {
            final Socket mc = new Socket();
            mc.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), mcPort), 3000);
            AzoreaNetLog.milestone(Category.RELAY, "host-bridge",
                    label + " " + punched.getRemoteSocketAddress() + " → 127.0.0.1:" + mcPort);
            final Thread t = new Thread(() -> bridge(punched, mc, label), "azorea-proxy-host-" + label);
            t.setDaemon(true);
            t.start();
            return true;
        } catch (final IOException e) {
            AzoreaNetLog.failure(Category.RELAY, "host-bridge", label,
                    "MC s/ acepta en 127.0.0.1:" + mcPort + " → " + e.getMessage());
            try {
                punched.close();
            } catch (final IOException ignored) {
            }
            return false;
        }
    }

    // ===== Bomba =====

    /**
     * Pump bidireccional: dos hilos, cada uno copia un sentido.
     *
     * <p>Cuando un lado da EOF o falla, se cierran <b>ambos</b> — MC detecta el corte
     * por socket y d/ forma ordenada. ⊘ lazos: los dos cierres son idempotentes.
     */
    static void bridge(final Socket a, final Socket b, final String label) {
        AzoreaNetLog.info(Category.RELAY, label + " tunnel abierto ("
                + a.getLocalSocketAddress() + " ↔ " + b.getLocalSocketAddress() + ")");
        final Thread t1 = pump(a, b, label + "/1");
        final Thread t2 = pump(b, a, label + "/2");
        // Esperar a q/ alguno termine ⇒ cerrar todo.
        try {
            t1.join();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        closeQuietly(a);
        closeQuietly(b);
        AzoreaNetLog.info(Category.RELAY, label + " tunnel cerrado");
        if (t2.isAlive()) {
            // El 2.º sentido se desbloquea al cerrar `a` (su destino).
            try {
                t2.join(1000);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Copia {@code from} → {@code to} hasta EOF/fallo. Daemon. */
    private static Thread pump(final Socket from, final Socket to, final String name) {
        final Thread t = new Thread(() -> {
            final byte[] buf = new byte[BUFFER];
            try (InputStream in = from.getInputStream();
                 OutputStream out = to.getOutputStream()) {
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (final IOException e) {
                LOGGER.debug("{}: pump terminó: {}", name, e.getMessage());
            } finally {
                // ⊘ c/ EOF d/ un sentido, cerrar el otro ⇒ drena el 2.º hilo.
                closeQuietly(to);
                closeQuietly(from);
            }
        }, "azorea-pump-" + name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void closeQuietly(final Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (final IOException ignored) {
        }
    }
}
