// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * UDP hole-puncher (ver AGENTS.md § F6.3).
 *
 * <p>Cuando el host está detrás de NAT endpoint-independent, ambos peers pueden
 * enviarse UDP packets mutuamente. Los NATs ven "outgoing + incoming" en el mismo
 * puerto y crean el mapping. Tras el punch, peers pueden comunicar por TCP
 * (que es lo que usa Minecraft) sin relay.
 *
 * <p><b>§ Limitaciones v1:</b>
 * <ul>
 *   <li>Solo UDP. TCP hole-punching es mucho más difícil (stateful).</li>
 *   <li>Asume NAT endpoint-independent (80% de casos domésticos). Para NAT
 *       simétrico falla — fallback al relay.</li>
 *   <li>Coordinación: ambos peers deben punchear simultáneamente. En Azorea se
 *       coordina vía el invite: cuando el recipient acepta, ambos peers
 *       lanzan el punch al mismo tiempo.</li>
 * </ul>
 *
 * <p><b>§ Protocolo:</b>
 * <pre>{@code
 * 1. Peer A envía 5 UDP packets a peer B's public_ip:public_udp_port
 *    conteniendo "AZPUNCH\n" (o "AZPONG\n" como respuesta).
 * 2. Peer B hace lo mismo en paralelo.
 * 3. Los NATs ven incoming + outgoing, crean mapping.
 * 4. Si peer B recibe un AZPUNCH, responde AZPONG.
 * 5. Peer A recibe el AZPONG → punch exitoso.
 * }</pre>
 */
public final class AzoreaNatPuncher {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzoreaNatPuncher.class);

    /** Magic packet para iniciar punch. */
    public static final byte[] PUNCH_PACKET = "AZPUNCH\n".getBytes(StandardCharsets.UTF_8);
    /** Magic packet como respuesta. */
    public static final byte[] PONG_PACKET = "AZPONG\n".getBytes(StandardCharsets.UTF_8);

    private AzoreaNatPuncher() {
    }

    /**
     * Punch UDP desde nuestro puerto local hacia el public endpoint del friend.
     * Retorna true si recibimos un AZPONG (indicating punch exitoso).
     *
     * <p>§ Uso:
     * <pre>{@code
     * // En peer A:
     * boolean ok = AzoreaNatPuncher.punch(friendPublicHost, friendPublicPort, 5000);
     * if (ok) {
     *     // Punch exitoso: podemos conectar TCP directo
     * } else {
     *     // Punch falló: usar relay
     * }
     * }</pre>
     *
     * @param friendHost host público del friend (de STUN)
     * @param friendPort puerto UDP del friend (de STUN)
     * @param timeoutMs timeout total
     * @return true si recibimos AZPONG antes del timeout
     */
    public static boolean punch(final String friendHost, final int friendPort, final int timeoutMs) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(Math.max(100, timeoutMs / 5));
            final InetAddress addr = InetAddress.getByName(friendHost);

            // Enviar 5 punches con intervalos.
            for (int i = 0; i < 5; i++) {
                socket.send(new DatagramPacket(PUNCH_PACKET, PUNCH_PACKET.length, addr, friendPort));
                try {
                    byte[] buf = new byte[64];
                    final DatagramPacket resp = new DatagramPacket(buf, buf.length);
                    socket.receive(resp);
                    final String s = new String(buf, 0, resp.getLength(), StandardCharsets.UTF_8);
                    if (s.startsWith("AZPONG")) {
                        LOGGER.info("Punch OK: received AZPONG from {}:{}", friendHost, friendPort);
                        return true;
                    }
                } catch (SocketTimeoutException ignored) {
                    // No response yet
                }
            }
            return false;
        } catch (IOException e) {
            LOGGER.warn("Punch failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Escucha en {@code port} por UDP punches; cuando recibe uno, envía AZPONG de vuelta.
     * Útil para el friend del host (responde a los punches).
     *
     * <p>Bloquea hasta recibir un punch o timeout.
     */
    public static void listenForPunch(final int port, final int timeoutMs) {
        try (DatagramSocket socket = new DatagramSocket(port)) {
            socket.setSoTimeout(timeoutMs);
            final byte[] buf = new byte[64];
            final DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            socket.receive(pkt);
            // Verificar que es un punch.
            final String s = new String(buf, 0, pkt.getLength(), StandardCharsets.UTF_8);
            if (!s.startsWith("AZPUNCH")) {
                return;
            }
            // Responder con PONG.
            socket.send(new DatagramPacket(PONG_PACKET, PONG_PACKET.length,
                    pkt.getAddress(), pkt.getPort()));
            LOGGER.info("Pong sent a {}:{}", pkt.getAddress(), pkt.getPort());
        } catch (IOException e) {
            LOGGER.debug("listenForPunch ended: {}", e.getMessage());
        }
    }
}