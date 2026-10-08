// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client;

import com.azorea.mod.v1211.AzoreaNetLog;
import com.azorea.mod.v1211.AzoreaNetLog.Category;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Cliente NAT-PMP (RFC 6886) — ver AGENTS.md § DA-10 (D0 serverless).
 *
 * <p>§ Por qué existe además de UPnP: son capas complementarias, no rivales.
 * UPnP-IGD necesita <b>descubrimiento</b> (SSDP multicast a 239.255.255.250:1900)
 * y es justamente ese paso el que puede fallar — en el ordenador del General el
 * router ni contesta. NAT-PMP <b>no descubre nada</b>: la dirección del gateway
 * es la puerta de enlace por defecto, que ya obtenemos con
 * {@link AzoreaPortForwardGuide#detectDefaultGateway()}. Un solo datagrama UDP
 * a {@code :5351} y listo. Firmware con UPnP desactivado suele seguir
 * soportando NAT-PMP (pfSense, OPNsense, OpenWRT, Apple AirPort…).
 *
 * <p>§ Formato de paquete verificado contra la implementación de referencia
 * ({@code libnatpmp/natpmp.c}) y no contra memoria — hubo que descartar la
 * RFC equivocada (6824 es Multipath TCP; NAT-PMP es <b>6886</b>):
 * <pre>
 *  Petición IP pública (opcode 0)      → 2 bytes:  {0x00, 0x00}
 *  Petición de mapeo (opcode 1|2)      → 12 bytes: ver, op, 0x00, 0x00,
 *                                                  puerto_int(BE16), puerto_ext(BE16),
 *                                                  lifetime(BE32)
 *  Respuesta                          → ver, op|0x80, resultado(BE16), epoch(BE32),
 *                                                  + payload (4B IP | 2B+2B+B4B puertos)
 * </pre>
 *
 * <p>§ <b>El resultado es un entero de 2 bytes en orden de red</b> (no 1 byte) —
 * error fácil de meter y que produce fallos imposibles de leer. Códigos:
 * 0=OK, 1=versión no soportada, 2=denegado/no autorizado, 3=fallo de red,
 * 4=sin recursos, 5=opcode no soportado.
 *
 * <p>§ Seguridad: solo se acepta la respuesta si viene <b>del propio gateway</b>
 * (mismo criterio que libnatpmp), para no aceptar un "mapeo" envenenado.
 *
 * <p>§ Threading: todo bloquea ⇒ llamar desde virtual thread ⊘ del main thread.
 */
public final class NatPmpClient {

    /** Puerto NAT-PMP estándar en el gateway (RFC 6886 §3). */
    public static final int NAT_PMP_PORT = 5351;

    /** Opcodes. */
    private static final int OP_EXTERNAL_ADDRESS = 0;
    private static final int OP_MAP_TCP = 2;

    private NatPmpClient() {
    }

    /** Mapeo conseguido. {@code externalPort} puede diferir del interno si el router reasigna. */
    public record Mapping(int internalPort, int externalPort, long lifetimeSeconds) {
    }

    // ===== Renovación (mismo problema de lease que UPnP) =====

    private static final ScheduledExecutorService RENEWAL_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                final Thread t = new Thread(r, "azorea-natpmp-renew");
                t.setDaemon(true);
                return t;
            });
    private static volatile ScheduledFuture<?> renewal;
    private static volatile String renewalGateway;

    // ===== API pública =====

    /**
     * Intenta conseguir un endpoint público vía NAT-PMP.
     *
     * <p>Cascada D0: UPnP (descubrimiento) → <b>NAT-PMP</b> (sin descubrimiento)
     * → STUN → solo LAN. Devolver null simplemente significa "este router no
     * soporta NAT-PMP" y seguimos con el siguiente eslabón.
     *
     * @param internalPort puerto que escucha el servidor MC
     * @param lifetimeSec  lease en segundos (renovado a la mitad)
     * @return {@code "ip:port"} público o null si no hubo suerte
     */
    public static String tryMapEndpoint(final int internalPort, final int lifetimeSec) {
        final String gateway = AzoreaPortForwardGuide.detectDefaultGateway();
        if (gateway == null) {
            AzoreaNetLog.debug(Category.NATPMP, "sin puerta de enlace por defecto → ⊘ probar");
            return null;
        }
        try {
            final String publicIp = externalAddress(gateway, 1500);
            final Mapping mapping = mapTcpPort(gateway, internalPort, lifetimeSec, 1500);
            startRenewal(gateway, internalPort, lifetimeSec);

            final String endpoint = publicIp + ":" + mapping.externalPort();
            AzoreaNetLog.milestone(Category.NATPMP, "map-ok",
                    gateway + " → " + endpoint
                            + (mapping.externalPort() == internalPort
                            ? "" : " (router reasignó el puerto: " + internalPort + ")")
                            + " lease=" + mapping.lifetimeSeconds() + "s");
            return endpoint;
        } catch (final SocketTimeoutException e) {
            AzoreaNetLog.info(Category.NATPMP, gateway + " no responde (sin NAT-PMP) → s/ cascada");
            return null;
        } catch (final IOException e) {
            // Incluye los códigos de resultado de la RFC (denegado, sin recursos…)
            AzoreaNetLog.info(Category.NATPMP, gateway + " → " + e.getMessage() + " → s/ cascada");
            return null;
        }
    }

    /** Petición de IP pública (opcode 0): exactamente 2 bytes. */
    public static String externalAddress(final String gateway, final int timeoutMs)
            throws IOException {
        final byte[] resp = exchange(gateway, buildExternalAddressRequest(), timeoutMs);
        return parseExternalAddress(resp);
    }

    /** Petición de mapeo TCP (opcode 2): 12 bytes. */
    public static Mapping mapTcpPort(final String gateway, final int internalPort,
                                     final long lifetimeSec, final int timeoutMs)
            throws IOException {
        final byte[] resp = exchange(gateway,
                buildMappingRequest(OP_MAP_TCP, internalPort, internalPort, lifetimeSec),
                timeoutMs);
        return parseMappingResponse(resp, OP_MAP_TCP, internalPort);
    }

    /** Cancela la renovación (llamar al parar de hostear). */
    public static void stopRenewal() {
        final ScheduledFuture<?> task = renewal;
        renewal = null;
        renewalGateway = null;
        if (task != null) task.cancel(false);
    }

    // ===== Renovación =====

    /**
     * Renueva el mapeo a la mitad de su lease — mismo motivo que en UPnP: si no,
     * a los N segundos el router borra la regla y los SYN entrantes nuevos caen.
     */
    private static void startRenewal(final String gateway, final int port, final long lifetimeSec) {
        stopRenewal();
        renewalGateway = gateway;
        final long period = Math.max(30L, lifetimeSec / 2L);
        renewal = RENEWAL_SCHEDULER.scheduleAtFixedRate(() -> {
            if (renewalGateway == null) return;
            try {
                final Mapping m = mapTcpPort(renewalGateway, port, lifetimeSec, 1500);
                AzoreaNetLog.debug(Category.NATPMP,
                        "renew OK port=" + port + " → " + m.externalPort()
                                + " lease=" + m.lifetimeSeconds() + "s");
            } catch (final Exception e) {
                // Capturar SIEMPRE: si escapa, scheduleAtFixedRate cancela en silencio
                // el resto de ejecuciones (fallo fantasma, igual que en UPnP).
                AzoreaNetLog.failure(Category.NATPMP, "renew", "port=" + port, e.getMessage());
            }
        }, period, period, TimeUnit.SECONDS);
        AzoreaNetLog.info(Category.NATPMP, "renew programado cada " + period + "s (port=" + port + ")");
    }

    // ===== Wire: construcción =====

    /** RFC 6886 §3.2: petición de IP pública = 2 bytes. */
    static byte[] buildExternalAddressRequest() {
        return new byte[]{0x00, (byte) OP_EXTERNAL_ADDRESS};
    }

    /**
     * RFC 6886 §3.3: petición de mapeo = 12 bytes, todo en orden de red.
     *
     * @param protocol 1 = UDP, 2 = TCP
     */
    static byte[] buildMappingRequest(final int protocol, final int internalPort,
                                      final int externalPort, final long lifetimeSec) {
        if (protocol != 1 && protocol != 2) {
            throw new IllegalArgumentException("protocolo NAT-PMP inválido: " + protocol);
        }
        if (internalPort < 1 || internalPort > 65535) {
            throw new IllegalArgumentException("puerto interno fuera de rango: " + internalPort);
        }
        if (externalPort < 0 || externalPort > 65535) {
            throw new IllegalArgumentException("puerto externo fuera de rango: " + externalPort);
        }
        if (lifetimeSec < 0) throw new IllegalArgumentException("lifetime negativo: " + lifetimeSec);

        final byte[] b = new byte[12];
        b[0] = 0;                                  // version
        b[1] = (byte) protocol;                   // 1=UDP, 2=TCP
        b[2] = 0;                                  // reserved
        b[3] = 0;                                  // reserved
        b[4] = (byte) (internalPort >> 8);         // BE16
        b[5] = (byte) internalPort;
        b[6] = (byte) (externalPort >> 8);         // BE16
        b[7] = (byte) externalPort;
        b[8] = (byte) (lifetimeSec >> 24);         // BE32
        b[9] = (byte) (lifetimeSec >> 16);
        b[10] = (byte) (lifetimeSec >> 8);
        b[11] = (byte) lifetimeSec;
        return b;
    }

    // ===== Wire: parseo =====

    /**
     * Cabecera común. El <b>resultado ocupa 2 bytes</b> (BE16) — no 1.
     *
     * @param expectedOp opcode esperado de respuesta (128 = IP pública, 130 = TCP)
     */
    private static void checkHeader(final byte[] r, final int expectedOp) throws IOException {
        if (r == null || r.length < 8) {
            throw new IOException("respuesta NAT-PMP demasiado corta: "
                    + (r == null ? 0 : r.length) + " B");
        }
        if (r[0] != 0) {
            throw new IOException("versión NAT-PMP no soportada: " + (r[0] & 0xFF));
        }
        final int op = r[1] & 0xFF;
        if (op < 128 || op > 130) {
            throw new IOException("opcode de respuesta inválido: " + op);
        }
        if (op != expectedOp) {
            throw new IOException("respuesta a otra petición (opcode " + op
                    + ", esperado " + expectedOp + ")");
        }
        final int result = ((r[2] & 0xFF) << 8) | (r[3] & 0xFF);   // BE16
        if (result != 0) {
            throw new IOException(describeResultCode(result));
        }
    }

    /** RFC 6886 §3.5. */
    static String describeResultCode(final int code) {
        return switch (code) {
            case 1 -> "versión no soportada por el gateway";
            case 2 -> "denegado (el gateway no autoriza mapeos — ¿UPnP/NAT-PMP desactivado?)";
            case 3 -> "fallo de red (¿el gateway no tiene lease DHCP?)";
            case 4 -> "sin recursos (demasiados mapeos en el gateway)";
            case 5 -> "opcode no soportado por el gateway";
            default -> "error NAT-PMP desconocido: " + code;
        };
    }

    /** Respuesta a opcode 0 → IP pública en bytes 8..11. */
    static String parseExternalAddress(final byte[] r) throws IOException {
        checkHeader(r, 128);
        if (r.length < 12) {
            throw new IOException("respuesta de IP pública incompleta: " + r.length + " B");
        }
        final byte[] ipv4 = Arrays.copyOfRange(r, 8, 12);
        return InetAddress.getByAddress(ipv4).getHostAddress();
    }

    /** Respuesta a opcode 2 → puertos en 8..9 / 10..11 y lifetime en 12..15. */
    static Mapping parseMappingResponse(final byte[] r, final int expectedOp,
                                        final int expectedInternalPort) throws IOException {
        checkHeader(r, expectedOp);
        if (r.length < 16) {
            throw new IOException("respuesta de mapeo incompleta: " + r.length + " B");
        }
        final int internalPort = ((r[8] & 0xFF) << 8) | (r[9] & 0xFF);
        if (internalPort != expectedInternalPort) {
            // Igual que libnatpmp: respuesta a una petición antigua → no usarla.
            throw new IOException("respuesta de un mapeo anterior (puerto " + internalPort
                    + " ≠ " + expectedInternalPort + ")");
        }
        final int externalPort = ((r[10] & 0xFF) << 8) | (r[11] & 0xFF);
        final long lifetime = ((long) (r[12] & 0xFF) << 24)
                | ((long) (r[13] & 0xFF) << 16)
                | ((long) (r[14] & 0xFF) << 8)
                | (r[15] & 0xFFL);
        if (externalPort < 1 || externalPort > 65535) {
            throw new IOException("puerto externo fuera de rango: " + externalPort);
        }
        return new Mapping(internalPort, externalPort, lifetime);
    }

    // ===== Transporte =====

    private static byte[] exchange(final String gateway, final byte[] request,
                                   final int timeoutMs) throws IOException {
        final InetAddress gatewayAddr = InetAddress.getByName(gateway);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(Math.max(200, timeoutMs));
            socket.send(new DatagramPacket(request, request.length, gatewayAddr, NAT_PMP_PORT));

            final byte[] buf = new byte[64];
            final DatagramPacket response = new DatagramPacket(buf, buf.length);
            socket.receive(response);

            // § Seguridad: solo del gateway (criterio de libnatpmp).
            if (!response.getAddress().equals(gatewayAddr)) {
                throw new IOException("respuesta de otra fuente: " + response.getAddress());
            }
            return Arrays.copyOf(buf, response.getLength());
        }
    }
}
