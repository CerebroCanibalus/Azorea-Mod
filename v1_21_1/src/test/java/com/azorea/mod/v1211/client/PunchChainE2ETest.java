// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client;

import com.azorea.mod.v1211.identity.AzoreaId;
import com.azorea.mod.v1211.identity.AzoreaIdentity;
import com.azorea.mod.v1211.identity.HwFingerprint;
import com.azorea.mod.v1211.tracker.embedded.AzoreaEmbeddedTracker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EL ATADO — cadena completa d/ DA-12, d/ punta a punta, c/ el código REAL:
 *
 * <pre>
 *   host                                    tracker (1 solo)                       joiner
 *     │  reservePort + observe ──────────── GET /punch/observe ──► (ip, puerto)      │
 *     │  announceSigned(target=null) ────── POST /punch/announce ◄── announce(target=hostId)
 *     │  poll  ?target=hostId ◄──────────── PunchAnnounce ────────► poll ?azoreaId=hostId
 *     │                                                                            │
 *     └────────────────── punchTcp: bind(L)+connect a la vez (simultaneous open) ───┘
 *                                          socket puncheado (TCP real)
 *     │                                                                            │
 *     └─ startHostBridge ─► 127.0.0.1:mcPort                                  startJoinerProxy
 *                           (MC "servidor")                                   ◄─ 127.0.0.1:puerto
 *                                                                             (MC cliente)
 * </pre>
 *
 * <p>§ <b>Qué aporta éste y no los otros</b>: los tests d/ cada primitivo solo prueban
 * su propio trozo. Éste es el q/ fallaría si los tres no acordaran <b>el mismo puerto</b>
 * — el error más fácil d/ cometer y el q/ nadie vería hasta probarlo en internet.
 *
 * <p>§ <b>Sobre el tiempo</b>: en loopback un RST llega en sub-ms ⇒ el socket pasa µs en
 * `SYN-SENT` ⇒ el "deber" d'overlap es bajísimo… y aun así tiene q/ cuadrar. Por eso
 * `punchTcp` reintenta a <b>5 ms</b> cuando el fallo es rápido (ver `FAST_RETRY_MS`):
 * ~960 intentos en 5 s ⇒ la probabilidad d/ q/ los 2 coincidan es prácticamente 1.
 *
 * <p>§ <b>Qué NO cubre</b>: capa 2 (¿deja un NAT real cruzar los SYN?). Loopback no
 * tiene NAT ⇒ eso va al test WAN.
 */
@DisplayName("ATADO — cadena completa: observe → announce → poll → punch → proxy → datos")
final class PunchChainE2ETest {

    private static AzoreaEmbeddedTracker tracker;
    private static String url;
    private static ServerSocket fakeMc;

    @BeforeAll
    static void setUp() throws IOException {
        tracker = new AzoreaEmbeddedTracker(0);   // efímero ⇒ sin colisión entre tests
        tracker.start();
        url = tracker.localUrl();
        fakeMc = echoServer();
    }

    @AfterAll
    static void tearDown() throws IOException {
        if (fakeMc != null) fakeMc.close();
        if (tracker != null) tracker.stop();
    }

    @Test
    @DisplayName("cadena con 1 tracker ⇒ 64 KiB ida y vuelta")
    void fullChainMovesBytes() throws Exception {
        runChain(List.of(url));
    }

    @Test
    @DisplayName("multi-tracker: 2 CAÍDOS + 1 vivo ⇒ sigue conectando (failover)")
    void deadTrackersDoNotBreakChain() throws Exception {
        // § Éste es el cambio d/ `exchange(List)`: si fuera de1 URL, el primer tracker
        //   muerto abortaría la cadena entera. Ahora se anuncia en todos y se pollea
        //   en todos ⇒ basta q/ el peer tenga1 d/ ellos vivo.
        runChain(List.of("http://127.0.0.1:1", url, "http://127.0.0.1:2"));
    }

    private void runChain(final List<String> urls) throws Exception {
        final Keys host = new Keys();
        final Keys joiner = new Keys();
        final int mcPort = fakeMc.getLocalPort();

        // § El host NO sabe quién va a unirse ⇒ peerId = null ⇒ espera `target = su id`.
        final CompletableFuture<String> hostBridge = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            final var sock = AzoreaPunchManager.exchange(urls, signer(host), null, 25_000);
            if (sock.isEmpty()) {
                hostBridge.completeExceptionally(new AssertionError("host: sin socket puncheado"));
                return;
            }
            if (!AzoreaLocalProxy.startHostBridge(sock.get(), mcPort, "chain-host")) {
                hostBridge.completeExceptionally(new AssertionError("host: bridge a MC falló"));
                return;
            }
            hostBridge.complete("ok");
        });

        // § El joiner sí conoce al host (lo trae el bundle) ⇒ anuncia c/ target y pollea por id.
        final var punched = AzoreaPunchManager.exchange(urls, signer(joiner), host.azoreaId, 25_000);
        // § `assertNotNull` NO vale: un Optional vacío no es null y `.get()` revienta
        //   c/ NoSuchElementException ⇒ el assert tiene q/ mirar `isPresent()`.
        assertTrue(punched.isPresent(),
                "joiner: sin socket puncheado — cadena rota (¿bind ocupado o tracker caído?)");
        final int localPort = AzoreaLocalProxy.startJoinerProxy(punched.get(), "chain-joiner");
        assertTrue(localPort > 0, "joiner: proxy local no arrancó");

        // § El host tiene q/ haber puenteado ANTES d/ tirar d/ datos.
        hostBridge.get(30, TimeUnit.SECONDS);

        // § 64 KiB — basta p/ obligar a varias vueltas d/ bomba (16 KiB) en c/ sentido.
        final byte[] payload = new byte[64 * 1024];
        new Random(42).nextBytes(payload);
        try (Socket client = new Socket("127.0.0.1", localPort)) {
            client.setSoTimeout(15_000);
            final OutputStream out = client.getOutputStream();
            out.write(payload);
            out.flush();
            final byte[] back = client.getInputStream().readNBytes(payload.length);
            assertArrayEquals(payload, back, "los bytes no sobrevivieron la cadena");
        }
        System.out.println("[chain] VEREDICTO: observe → announce → poll → punch → "
                + "hostBridge → joinerProxy → MC → 64 KiB ida y vuelta ✓ (proxy local "
                + localPort + ")");
    }

    // ===== Fixes d/ identidad =====

    /** Par d/claves + id DERIVADO (DA-8) — igual q/ el resto d/ tests E2E. */
    private static final class Keys {
        final byte[] edPub;
        final byte[] edPriv;
        final byte[] xPub;
        final byte[] hwCommit;
        final String azoreaId;

        Keys() throws GeneralSecurityException {
            final AzoreaIdentity.Keys ed = AzoreaIdentity.generateSigningKeyPair();
            final AzoreaIdentity.Keys x = AzoreaIdentity.generateKeyPair();
            this.edPub = ed.publicKey();
            this.edPriv = ed.privateKey();
            this.xPub = x.publicKey();
            this.hwCommit = new HwFingerprint(
                    "AA:BB:CC:DD:EE:FF", "4C1B-3F2A-TEST", "10.0", "DESKTOP-TEST").hwCommit();
            this.azoreaId = AzoreaId.derive(edPub, xPub, hwCommit);
        }
    }

    private static AzoreaPunchManager.Signer signer(final Keys k) {
        return new AzoreaPunchManager.Signer(
                "Chain-" + k.azoreaId.substring(0, 12), k.azoreaId,
                k.xPub, k.edPub, k.edPriv, k.hwCommit);
    }

    /** MC "de mentira": acepta y ecoa — lo q/ `startHostBridge` va a discar. */
    private static ServerSocket echoServer() throws IOException {
        final ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        final Thread acceptor = new Thread(() -> {
            while (!ss.isClosed()) {
                try {
                    final Socket s = ss.accept();
                    final Thread worker = new Thread(() -> {
                        try (InputStream in = s.getInputStream();
                             OutputStream out = s.getOutputStream()) {
                            final byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) >= 0) {
                                out.write(buf, 0, n);
                                out.flush();
                            }
                        } catch (final IOException ignored) {
                            // EOF d/ la sesión ⇒ normal
                        } finally {
                            try {
                                s.close();
                            } catch (final IOException ignored) {
                            }
                        }
                    }, "chain-echo");
                    worker.setDaemon(true);
                    worker.start();
                } catch (final IOException e) {
                    return;   // servidor cerrado
                }
            }
        }, "chain-echo-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        return ss;
    }
}
