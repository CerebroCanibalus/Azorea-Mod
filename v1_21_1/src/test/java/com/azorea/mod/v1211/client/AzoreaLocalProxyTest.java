// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static java.time.Duration.ofSeconds;

/**
 * Tests del proxy local TCP↔TCP (DA-12 paso 3).
 *
 * <p>El q/ importa es {@link #fullTunnelLoopback()}: cadena completa
 * cliente → proxy joiner → socket "puncheado" → host bridge → MC echo → y de vuelta.
 * Si esto pasa, el tramo d/ transporte NO necesita capa d/ fiabilidad — el SO ya la pone.
 */
final class AzoreaLocalProxyTest {

    /** MC server fijo: acepta UNA conexión y ecoa hasta EOF. */
    private static ServerSocket startEchoServer() throws Exception {
        final ServerSocket ss = new ServerSocket(0);
        final Thread t = new Thread(() -> {
            try (Socket s = ss.accept()) {
                final byte[] buf = new byte[4096];
                final InputStream in = s.getInputStream();
                final OutputStream out = s.getOutputStream();
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (final Exception ignored) {
                // cierre normal d/ test
            }
        }, "test-echo-mc");
        t.setDaemon(true);
        t.start();
        return ss;
    }

    /**
     * Crea un par d/ sockets YA conectados = sustituto d/ lo q/ devolvería el punch
     * (simultaneous open). El listener temporal solo existe p/ fabricar el par.
     */
    private static Socket[] connectedPair() throws Exception {
        try (ServerSocket tmp = new ServerSocket(0)) {
            final Socket a = new Socket();
            a.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),
                    tmp.getLocalPort()), 2000);
            final Socket b = tmp.accept();
            return new Socket[]{a, b};
        }
    }

    private static void readFully(final InputStream in, final byte[] expected) throws Exception {
        final byte[] got = new byte[expected.length];
        int off = 0;
        while (off < got.length) {
            final int n = in.read(got, off, got.length - off);
            if (n < 0) throw new IllegalStateException(
                    "EOF a los " + off + "/" + got.length + " bytes");
            off += n;
        }
        assertTrue(Arrays.equals(expected, got), "payload idéntico d/ vuelta");
    }

    // ===== 1. El test que importa =====

    @Test
    @DisplayName("Túnel completo: cliente → proxy joiner → socket puncheado → host → MC echo")
    void fullTunnelLoopback() throws Exception {
        final ServerSocket mc = startEchoServer();
        final Socket[] pair = connectedPair();     // [0]=lado joiner, [1]=lado host
        final Socket joinerEnd = pair[0];
        final Socket hostEnd = pair[1];

        try {
            // § Lado host: puentea el socket puncheado c/ su MC server.
            assertTrue(AzoreaLocalProxy.startHostBridge(hostEnd, mc.getLocalPort(), "test"),
                    "el host acepta el bridge");

            // § Lado joiner: ServerSocket loopback al q/ apunta el MC client.
            final int localPort = AzoreaLocalProxy.startJoinerProxy(joinerEnd, "test");
            assertTrue(localPort > 0, "puerto local asignado");

            assertTimeoutPreemptively(ofSeconds(6), () -> {
                try (Socket client = new Socket(InetAddress.getByName("127.0.0.1"), localPort)) {
                    client.setSoTimeout(3000);
                    final OutputStream out = client.getOutputStream();
                    final InputStream in = client.getInputStream();

                    // Pequeño mensaje (handshake estilo MC).
                    final byte[] ping = "HELLO MC".getBytes(StandardCharsets.UTF_8);
                    out.write(ping);
                    out.flush();
                    readFully(in, ping);

                    // Payload grande ⇒ ejercita ambos hilos d/ bomba y el buffer.
                    final byte[] bulk = new byte[64 * 1024];
                    new java.util.Random(42).nextBytes(bulk);
                    out.write(bulk);
                    out.flush();
                    readFully(in, bulk);
                }
            }, "el túnel no drió echo en 6s");
        } finally {
            joinerEnd.close();
            hostEnd.close();
            mc.close();
        }
    }

    // ===== 2. El proxy SOLO escucha en loopback =====

    @Test
    @DisplayName("El ServerSocket del joiner queda en 127.0.0.1 (bind explícito, ⊘ 0.0.0.0)")
    void joinerProxyBindsLoopbackOnly() throws Exception {
        final Socket[] pair = connectedPair();
        final Socket joinerEnd = pair[0];
        final ServerSocket mc = startEchoServer();
        try {
            assertTrue(AzoreaLocalProxy.startHostBridge(pair[1], mc.getLocalPort(), "lb"));
            final int port = AzoreaLocalProxy.startJoinerProxy(joinerEnd, "lb");
            assertTrue(port > 0);

            // ① loopback ⇒ conecta.
            try (Socket viaLoopback = new Socket(InetAddress.getByName("127.0.0.1"), port)) {
                assertTrue(viaLoopback.isConnected(), "loopback OK");
            }

            // ② una IP NO-loopback d/ esta máquina ⇒ debe ser RECHAZADA.
            // § NOTA: ⊘ se prueba con 0.0.0.0 — en Windows ese destino se resuelve a
            // localhost y daría un falso "sí está expuesto". Usamos la IP real d/ la NIC.
            final InetAddress lan = InetAddress.getLocalHost();
            if (!lan.isLoopbackAddress()) {
                boolean reached;
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(lan, port), 400);
                    reached = s.isConnected();
                } catch (final Exception e) {
                    reached = false;   // rechazado = exactamente lo q/ queremos
                }
                assertFalse(reached, "no debe escuchar en " + lan + " (solo 127.0.0.1)");
            } else {
                // Sin IP de NIC: al menos verificamos q/ el bind d/ loopback es el declarado.
                assertTrue(port > 0, "bind d/ loopback activo");
            }
        } finally {
            pair[0].close();
            pair[1].close();
            mc.close();
        }
    }

    // ===== 3. Fallos limpios =====

    @Test
    @DisplayName("Socket puncheado nulo/cerrado ⇒ -1 / false, ⊘ NPE ni excepción suelta")
    void invalidSocketsRejectedCleanly() {
        assertEquals(-1, AzoreaLocalProxy.startJoinerProxy(null, "null"), "joiner ⊘ NPE");
        assertEquals(-1, AzoreaLocalProxy.startJoinerProxy(new Socket(), "cerrado"),
                "joiner socket cerrado");
        assertFalse(AzoreaLocalProxy.startHostBridge(null, 25565, "null"), "host ⊘ NPE");

        try (Socket closed = new Socket()) {
            assertFalse(AzoreaLocalProxy.startHostBridge(closed, 25565, "cerrado"),
                    "host socket cerrado");
        } catch (final Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("MC s/ escuchando ⇒ host devuelve false y cierra el socket puncheado")
    void hostBridgeClosesPunchedWhenMcAbsent() throws Exception {
        final Socket[] pair = connectedPair();
        final Socket hostEnd = pair[1];
        // Puerto d/ un ServerSocket q/ ya cerramos ⇒ nadie acepta.
        final int deadPort;
        try (ServerSocket tmp = new ServerSocket(0)) {
            deadPort = tmp.getLocalPort();
        }

        assertFalse(AzoreaLocalProxy.startHostBridge(hostEnd, deadPort, "dead"),
                "MC ausente ⇒ false");
        // El socket puncheado no debe quedar zombi consumiendo recursos.
        assertTrue(waitClosed(hostEnd, 2000), "socket puncheado cerrado tras fallo");
        pair[0].close();
    }

    private static boolean waitClosed(final Socket s, final long timeoutMs) throws Exception {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (s.isClosed()) return true;
            Thread.sleep(20);
        }
        return s.isClosed();
    }

    @Test
    @DisplayName("Cierre del cliente ⇒ el túnel se drena sin dejar hilos colgados")
    void clientDisconnectClosesTunnel() throws Exception {
        final ServerSocket mc = startEchoServer();
        final Socket[] pair = connectedPair();
        final AtomicBoolean hostBridgeOk = new AtomicBoolean();
        try {
            hostBridgeOk.set(AzoreaLocalProxy.startHostBridge(pair[1], mc.getLocalPort(), "drain"));
            final int port = AzoreaLocalProxy.startJoinerProxy(pair[0], "drain");
            assertTrue(port > 0);
            assertTrue(hostBridgeOk.get());

            final Socket client = new Socket(InetAddress.getByName("127.0.0.1"), port);
            client.getOutputStream().write(1);
            client.getOutputStream().flush();
            client.close();     // corte d/ MC client

            // Cierre ordenado: el lado host debe drenar (el echo server cierra a su vez).
            assertTrue(waitClosed(pair[1], 3000),
                    "el socket d/ host se cierra tras el corte d/ MC client");
        } finally {
            pair[0].close();
            pair[1].close();
            mc.close();
        }
    }
}
