// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.SecureRandom;

/**
 * Cliente STUN (RFC 5389) para descubrir la IP pública del host (ver AGENTS.md § F6.2).
 *
 * <p>§ Por qué STUN:
 *   El host crea un socket TCP en `0.0.0.0:25565` (autohost). Eso solo es reachable desde
 *   la red local. Para que un peer remoto (en otra red) pueda conectar, necesitamos la IP
 *   pública que el router NAT asignó al host. STUN Binding Request pregunta al STUN server
 *   "¿qué IP+puerto ves desde fuera?" y el server responde con la mapped address.
 *
 * <p>§ Limitaciones v1:
 * <ul>
 *   <li>Solo UDP STUN (no TCP). El resultado puede diferir del TCP socket del MC server.</li>
 *   <li>Si el router usa NAT simétrico (no endpoint-independent), la IP+puerto obtenida puede no
 *       servir para conexiones TCP entrantes. En ese caso, fallback a relay (futuro).</li>
 *   <li>Solo funciona si el host tiene acceso UDP outbound (típico).</li>
 * </ul>
 *
 * <p>§ Uso:
 * <pre>{@code
 * Optional<StunResult> result = AzoreaStunClient.query(
 *     "stun.l.google.com", 19302, Duration.ofSeconds(5));
 * result.ifPresent(r -> System.out.println("Public IP: " + r.publicIp() + ":" + r.publicPort()));
 * }</pre>
 *
 * <p>§ Referencias:
 *   RFC 5389 (Session Traversal Utilities for NAT): https://datatracker.ietf.org/doc/html/rfc5389
 *   Google's public STUN servers: stun.l.google.com:19302, stun1.l.google.com:19302
 */
public final class AzoreaStunClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzoreaStunClient.class);

    /** STUN magic cookie (RFC 5389 §6). */
    private static final int MAGIC_COOKIE = 0x2112A442;

    /** Attribute type: XOR-MAPPED-ADDRESS (RFC 5389 §15.2). */
    private static final int ATTR_XOR_MAPPED_ADDRESS = 0x0020;

    /** Attribute type: MAPPED-ADDRESS (legacy, RFC 3489). */
    private static final int ATTR_MAPPED_ADDRESS = 0x0001;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Default STUN servers to try in order (Google's are public + reliable). */
    public static final String[] DEFAULT_STUN_SERVERS = {
            "stun.l.google.com",
            "stun1.l.google.com",
            "stun2.l.google.com"
    };

    public static final int DEFAULT_STUN_PORT = 19302;

    /** Resultado del STUN query. */
    public record StunResult(String publicIp, int publicPort) {
    }

    private AzoreaStunClient() {
    }

    /**
     * Query STUN server para descubrir la IP pública.
     * Prueba varios servers en orden hasta que uno responda.
     *
     * @param timeoutMs timeout en ms para cada server
     * @return Optional con el resultado, vacío si todos fallan
     */
    public static java.util.Optional<StunResult> query(final int timeoutMs) {
        // § F9b FIX: recoger la razón por servidor. Antes se logueaba solo a DEBUG,
        // así que el usuario veía "falló en todos los servidores" SIN poder saber
        // si fue DNS, timeout o parseo — y sin eso no se diagnostica nada.
        final java.util.List<String> reasons = new java.util.ArrayList<>();
        for (final String server : DEFAULT_STUN_SERVERS) {
            try {
                final StunResult result = query(server, DEFAULT_STUN_PORT, timeoutMs);
                if (result != null) {
                    LOGGER.info("STUN ok via {} → {}:{}", server, result.publicIp(), result.publicPort());
                    return java.util.Optional.of(result);
                }
                reasons.add(server + "=sin resultado");
            } catch (Exception e) {
                reasons.add(server + "=" + e.getMessage());
                LOGGER.debug("STUN via {} falló: {}", server, e.getMessage());
            }
        }
        // § BUG 2026-10-04: "falló en todos" mentía. Si los motivos son de familia de
        // dirección, los servidores RESPONDIERON — sólo que por IPv6. Decir «falló»
        // hizo que el diagnóstico apuntara a "servidor roto" cuando era nuestra propia
        // elección de familia (ya corregido en resolveIpv4).
        final String all = String.join(" | ", reasons);
        final long v6 = reasons.stream().filter(r -> r.contains("IPv6")).count();
        if (!reasons.isEmpty() && v6 == reasons.size()) {
            LOGGER.warn("STUN: los {} servidores respondieron por IPv6 en vez de IPv4 "
                    + "⇒ no se obtuvo la IPv4 pública ⇒ el bundle saldrá sólo con LAN/IPv6. {}",
                    reasons.size(), all);
        } else {
            LOGGER.warn("STUN falló en todos los servidores públicos → {}", all);
        }
        return java.util.Optional.empty();
    }

    /**
     * Resuelve el servidor STUN <b>forzando IPv4</b>. Package-private p/ tests.
     *
     * <p>§ BUG 2026-10-04 (intermitente — por eso costó verlo):
     * {@link InetAddress#getByName(String)} devuelve la dirección que prefiera el
     * <i>resolver</i> del SO. Windows ordena IPv6 primero (default desde Vista) ⇒
     * {@code stun.l.google.com} resolvía a AAAA ⇒ la petición salía por v6 ⇒ el
     * servidor respondía con nuestra IPv6 (correcto según RFC) ⇒ {@link #parseMappedAddress}
     * — que es IPv4-only <b>por diseño</b>, porque la v6 ya la sacamos de
     * {@code AzoreaHostShared.globalIpv6Addresses()} — lanzaba excepción.
     *
     * <p><b>Se comprobó en vivo</b>: 2026-10-01 STUN devolvió {@code 203.0.113.9} (ok),
     * 2026-10-03 los 3 servidores devolvieron {@code family=2}. Intermitente ⇒ bug, no diseño:
     * el diseño («STUN descubre la IPv4 pública») se había demostrado correcto.
     *
     * <p>Consecuencia real del bug: no se obtenía la IPv4 pública ⇒ el bundle salía con el
     * host LAN ⇒ un amigo <b>sin IPv6 no podía entrar</b> aunque el port-forward estuviera
     * verificado.
     *
     * @return la primera dirección IPv4 del host
     * @throws IOException si el host no tiene ninguna A record IPv4
     */
    static InetAddress resolveIpv4(final String host) throws IOException {
        for (final InetAddress addr : InetAddress.getAllByName(host)) {
            if (addr instanceof Inet4Address) {
                return addr;
            }
        }
        throw new IOException("sin dirección IPv4 para " + host
                + " (sólo AAAA/IPv6) — STUN necesita IPv4 para obtener la IPv4 pública");
    }

    /**
     * Query un único STUN server.
     *
     * @return StunResult o null si falla
     */
    public static StunResult query(final String server, final int port, final int timeoutMs)
            throws IOException {
        final byte[] request = buildBindingRequest();

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeoutMs);
            // § BUG fix: antes InetAddress.getByName(server) — sin forzar familia, así que
            // el SO podía elegir IPv6 y entonces STUN respondía v6 (ver resolveIpv4).
            final InetAddress serverAddr = resolveIpv4(server);

            // Send request.
            final DatagramPacket sendPacket = new DatagramPacket(request, request.length, serverAddr, port);
            socket.send(sendPacket);

            // Receive response (max 1500 bytes típico para UDP).
            final byte[] buffer = new byte[1500];
            final DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
            socket.receive(responsePacket);

            return parseBindingResponse(buffer, responsePacket.getLength());
        } catch (SocketTimeoutException e) {
            throw new IOException("STUN timeout after " + timeoutMs + "ms", e);
        } catch (UnknownHostException e) {
            throw new IOException("STUN server unknown: " + server, e);
        }
    }

    /**
     * Construye un STUN Binding Request (20 bytes, sin attributes).
     * RFC 5389 §6. Package-private para tests.
     */
    static byte[] buildBindingRequest() {
        final byte[] request = new byte[20];
        // Message Type: 0x0001 (Binding Request).
        request[0] = 0x00;
        request[1] = 0x01;
        // Message Length: 0 (sin attributes).
        request[2] = 0x00;
        request[3] = 0x00;
        // Magic Cookie: 0x2112A442.
        request[4] = 0x21;
        request[5] = 0x12;
        request[6] = (byte) 0xA4;
        request[7] = 0x42;
        // Transaction ID: 12 bytes random.
        final byte[] txnId = new byte[12];
        RANDOM.nextBytes(txnId);
        System.arraycopy(txnId, 0, request, 8, 12);
        return request;
    }

    /**
     * Parsea la respuesta STUN. Busca XOR-MAPPED-ADDRESS (preferred) o MAPPED-ADDRESS (legacy).
     */
    private static StunResult parseBindingResponse(final byte[] data, final int length) throws IOException {
        if (length < 20) {
            throw new IOException("STUN response too short: " + length);
        }

        // Parse header.
        final int msgType = readUint16(data, 0);
        // 0x0101 = Binding Success.
        if (msgType != 0x0101) {
            throw new IOException("STUN response not Binding Success: 0x"
                    + Integer.toHexString(msgType));
        }
        // final int msgLen = readUint16(data, 2);
        // final int magic = readUint32(data, 4);
        // final byte[] txnId = Arrays.copyOfRange(data, 8, 20);

        // Parse attributes starting at offset 20.
        int i = 20;
        while (i + 4 <= length) {
            final int attrType = readUint16(data, i);
            final int attrLen = readUint16(data, i + 2);
            if (i + 4 + attrLen > length) {
                throw new IOException("STUN attribute truncated");
            }

            if (attrType == ATTR_XOR_MAPPED_ADDRESS || attrType == ATTR_MAPPED_ADDRESS) {
                return parseMappedAddress(data, i + 4, attrLen, attrType == ATTR_XOR_MAPPED_ADDRESS);
            }
            // Siguiente atributo (4-byte aligned).
            i += 4 + ((attrLen + 3) & ~3);
        }
        throw new IOException("STUN response sin MAPPED-ADDRESS attribute");
    }

    /**
     * Parsea MAPPED-ADADDRESS o XOR-MAPPED-ADDRESS. Package-private para tests.
     * Formato (RFC 5389 §15.2):
     *   0: reserved (1 byte, debe ser 0)
     *   1: family (1 byte; 0x01 = IPv4)
     *   2-3: port (2 bytes, XOR'd con magic cookie alto si XOR-MAPPED)
     *   4-7: IP (4 bytes IPv4, XOR'd con magic cookie si XOR-MAPPED)
     */
    static StunResult parseMappedAddress(final byte[] data, final int offset, final int attrLen,
                                                final boolean xor) throws IOException {
        if (attrLen < 8) {
            throw new IOException("MAPPED-ADDRESS attribute too short: " + attrLen);
        }
        // final int reserved = data[offset] & 0xFF;
        final int family = data[offset + 1] & 0xFF;
        if (family == 0x02) {
            // § BUG 2026-10-04: esto NO es "STUN falló". El servidor respondió bien,
            // sólo que por IPv6 — que es lo que pasa si la petición salió por v6
            // (ahora forzamos v4 en resolveIpv4, así que esto sólo puede venir del
            // servidor). El mensaje anterior ("family no es IPv4: 2") diagnosticaba
            // a ciegas: sonaba a servidor roto cuando era nuestra propia elección
            // de familia de dirección.
            throw new IOException("respondió IPv6 aunque se pidió IPv4 (family=2)");
        }
        if (family != 0x01) {
            throw new IOException("MAPPED-ADDRESS family desconocida: " + family);
        }

        int port = readUint16(data, offset + 2);
        int ip = readUint32(data, offset + 4);

        if (xor) {
            // XOR con magic cookie.
            port ^= (MAGIC_COOKIE >>> 16);
            ip ^= MAGIC_COOKIE;
        }

        final String publicIp = ((ip >>> 24) & 0xFF) + "."
                + ((ip >>> 16) & 0xFF) + "."
                + ((ip >>> 8) & 0xFF) + "."
                + (ip & 0xFF);
        return new StunResult(publicIp, port);
    }

    private static int readUint16(final byte[] data, final int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static int readUint32(final byte[] data, final int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }
}