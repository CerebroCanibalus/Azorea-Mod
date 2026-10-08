// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Optional;

/**
 * Hole-punch <b>UDP</b> — el primitivo q/ faltaba (DA-12, paso 2).
 *
 * <p>§ Por qué UDP y ⊘ TCP (y por qué esto sí funciona en Windows):
 * <pre>
 *   TCP: necesitas un LISTENER en puerto P y además DISCAR desde P
 *        ⇒ Windows prohíbe listener+outbound en el MISMO puerto local
 *        ⇒ la Vía-1 quedó REFUTADA (4 variantes, 3 ❌ y la ✅ mataba el mapeo)
 *
 *   UDP: UN SOLO DatagramSocket envía Y recibe
 *        ⇒ ⊘ listener separado, ⊘ bind conflictivo, ⊘ mixin  ⇒ esquivamos la Vía-1
 * </pre>
 * Ése es exactamente el porqué PeerCraft punchea UDP y nos pone un proxy local
 * por delante en vez d/ enchufar MC directo.
 *
 * <p>§ Cómo se prueba q/ el punch SIRVE: en UDP no hay handshake. Mandamos
 * {@code HELLO} con un <b>token aleatorio</b> y devolvemos éxito solo cuando
 * recibimos un {@code HELLO} d/ ESE token:
 * <ul>
 *   <li>Recibirlo ⇒ el paquete d/ B nos llegó ⇒ su NAT dejó pasar lo entrante.</li>
 *   <li>B lo recibe también (ambos mandan) ⇒ el nuestro le llegó ⇒ el suyo dejó pasar.</li>
 *   ∴ <b>direccionalidad doble confirmada</b> — no es un probe d/ alcance.</li>
 * </ul>
 * El endpoint real d/ B se aprende del <b>source address</b> del datagrama recibido
 * (puede differir d/ lo anunciado si su NAT reescribe puertos ⇒ es el q/ hay q/ usar).
 *
 * <p>§ Fuera d/ alcance d/ esta clase: <b>fiabilidad</b>. UDP no ordena ni retransmite ⇒
 * llevar el tráfico d/ MC por aquí exige un transporte confiable encima (paso 3 —
 * proxy local TCP↔UDP). Esto solo abre y verifica el agujero.
 *
 * <p>§ ⊘ dependencias d/ MC ⇒ testeable en loopback p/ JUnit.
 */
public final class AzoreaUdpPuncher {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzoreaUdpPuncher.class);

    /** {@code AZP1} — cambio si cambia el formato d/ alambre. */
    public static final String MAGIC = "AZP1";
    /** 16 B d/ token ⇒ 2^128 p/ adivinar; solo d/ emparejar HELLO, ⊘ seguridad. */
    public static final int TOKEN_BYTES = 16;
    /** {@code AZP1} + token = 4 + 16 = 20 bytes. */
    public static final int HELLO_BYTES = 4 + TOKEN_BYTES;

    private static final SecureRandom RANDOM = new SecureRandom();
    /** Reenvío d/ HELLO mientras esperamos — el punch es una RÁFAGA, ⊘ 1 paquete. */
    private static final int RESEND_MS = 40;

    private AzoreaUdpPuncher() {
    }

    /** Endpoint observado d/ un peer (del source d/ su datagrama). */
    public record PeerSeen(InetAddress address, int port) {

        public String ip() {
            return address.getHostAddress();
        }

        @Override
        public String toString() {
            return ip() + ":" + port;
        }
    }

    /** Resultado d/ un punch exitoso: socket vivo + endpoint real d/ B. */
    public record PunchResult(DatagramSocket socket, PeerSeen peer) {
    }

    // ===== Fábricas =====

    /** Socket UDP efímero — envía y recibe, ⊘ listener aparte. */
    public static DatagramSocket open() throws SocketException {
        return new DatagramSocket();
    }

    /** Token aleatorio p/ emparejar nuestros HELLO (lo comparte el caller c/ ambos lados). */
    public static byte[] newToken() {
        final byte[] token = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(token);
        return token;
    }

    // ===== Alambre =====

    /** {@code AZP1 | token}. */
    public static byte[] buildHello(final byte[] token) {
        if (token == null || token.length != TOKEN_BYTES)
            throw new IllegalArgumentException("token debe ser " + TOKEN_BYTES + " bytes");
        final byte[] out = new byte[HELLO_BYTES];
        out[0] = (byte) MAGIC.charAt(0);
        out[1] = (byte) MAGIC.charAt(1);
        out[2] = (byte) MAGIC.charAt(2);
        out[3] = (byte) MAGIC.charAt(3);
        System.arraycopy(token, 0, out, 4, TOKEN_BYTES);
        return out;
    }

    /**
     * ¿Este datagrama es un HELLO c/ {@code token}?
     *
     * @return true solo si longitud y magia cuadran y el token es idéntico
     *         ⇒ paquetes ajenos o adivinados ⊘ nos distraen.
     */
    public static boolean isHelloFrom(final byte[] data, final int length, final byte[] token) {
        if (data == null || token == null) return false;
        if (length != HELLO_BYTES || token.length != TOKEN_BYTES) return false;
        if (data[0] != MAGIC.charAt(0) || data[1] != MAGIC.charAt(1)
                || data[2] != MAGIC.charAt(2) || data[3] != MAGIC.charAt(3)) {
            return false;
        }
        return Arrays.equals(Arrays.copyOfRange(data, 4, HELLO_BYTES), token);
    }

    // ===== El punch =====

    /**
     * Disco simultáneo UDP: manda {@code HELLO} en ráfaga hasta recibir el d/ B.
     *
     * <p>Bloqueante — llamar de un hilo/virtual thread.
     *
     * @param sock    socket ya abierto (q/ es d/ donde saldrá y donde entrará)
     * @param peerIp  dirección anunciada por B
     * @param peerPort puerto anunciado por B (su mapeo externo d/ STUN)
     * @param token   token compartido (ambos lados, idéntico)
     * @param timeoutMs deadline total
     * @return peer visto ⇒ el agujero está ABIERTO en ambas direcciones; vacío si timeout
     */
    public static Optional<PunchResult> punch(final DatagramSocket sock,
                                              final String peerIp, final int peerPort,
                                              final byte[] token, final long timeoutMs) {
        if (peerIp == null || peerIp.isBlank())
            throw new IllegalArgumentException("falta peerIp");
        if (peerPort < 1 || peerPort > 65535)
            throw new IllegalArgumentException("peerPort fuera de rango: " + peerPort);

        final byte[] hello = buildHello(token);
        final byte[] buf = new byte[512];
        final long deadline = System.currentTimeMillis() + timeoutMs;
        final InetAddress target;

        try {
            target = InetAddress.getByName(peerIp);
        } catch (final Exception e) {
            LOGGER.warn("punch: peerIp ilegible {}: {}", peerIp, e.getMessage());
            return Optional.empty();
        }

        long nextSend = 0;
        while (System.currentTimeMillis() < deadline) {
            // § Ráfaga: reenviar periódicamente p/ q/ ambos SYNs se crucen.
            if (System.currentTimeMillis() >= nextSend) {
                try {
                    sock.send(new DatagramPacket(hello, hello.length, target, peerPort));
                    nextSend = System.currentTimeMillis() + RESEND_MS;
                } catch (final Exception e) {
                    LOGGER.debug("punch: send falló ({}): {}", peerIp, e.getMessage());
                }
            }

            // § Espera corta p/ poder reenviar; el SoTimeout d/ llamada.
            try {
                sock.setSoTimeout(RESEND_MS);
            } catch (final SocketException e) {
                LOGGER.warn("punch: ⊘ se pudo fijar SoTimeout: {}", e.getMessage());
                return Optional.empty();
            }

            try {
                final DatagramPacket in = new DatagramPacket(buf, buf.length);
                sock.receive(in);
                if (isHelloFrom(buf, in.getLength(), token)) {
                    // § ÉXITO: lo recibimos ⇒ SU paquete nos llegó. Y como él tmb
                    // está escuchando y nosotros mandamos, el nuestro llegó ⇒ doble.
                    final PeerSeen peer = new PeerSeen(in.getAddress(), in.getPort());
                    LOGGER.info("punch UDP OK → {} (socket local {})",
                            peer, sock.getLocalPort());
                    return Optional.of(new PunchResult(sock, peer));
                }
                // Paquete ajeno (otro tráfico UDP) — seguir esperando el nuestro.
            } catch (final SocketTimeoutException ignored) {
                // esperamos al siguiente ciclo (volveremos a mandar HELLO).
            } catch (final Exception e) {
                LOGGER.warn("punch: receive falló: {}", e.getMessage());
                return Optional.empty();
            }
        }

        LOGGER.info("punch UDP sin respuesta en {} ms ({}:{} — NAT simétrico, CGNAT, o firewalled)",
                timeoutMs, peerIp, peerPort);
        return Optional.empty();
    }

    /**
     * ⚠ ⊘ existe un {@code openAndPunch()} d/ un solo paso: con try-with-resources
     * el socket se cierra al salir d/ la sucesión y el resultado devuelto sale MUERTO.
     * Ciclo d/ vida d/ 3 pasos, d/ el caller: {@code open()} → {@code punch()} → usar →
     * {@code close()}. (Bug real: su propia versión d/ este método devolvía socket cerrado.)
     */
}
