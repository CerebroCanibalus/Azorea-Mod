// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client;

import com.azorea.mod.v1211.AzoreaNetLog;
import com.azorea.mod.v1211.AzoreaNetLog.Category;
import com.azorea.mod.v1211.client.screen.AzoreaHostShared;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Cliente PCP — Port Control Protocol, RFC 6887 (§ DA-11, fase F-B).
 *
 * <p>§ Por qué existe <b>además</b> de NAT-PMP: <b>comparten el mismo puerto</b>
 * (UDP 5351, RFC 6887 §9) pero son protocolos distintos — NAT-PMP usa versión 0,
 * PCP versión 2. Firmware moderno (OpenWRT reciente, routers de operadora,
 * pfSense/OPNsense) soporta PCP <b>y no NAT-PMP</b>. Sin esto, ese router cae
 * directo a STUN y pierde el mapeo.
 *
 * <p>§ OJO con el orden: NetBird tuvo el bug inverso — <i>"PCP discovery starves
 * the UPnP/NAT-PMP fallback"</i>. Aquí PCP se intenta <b>antes</b> de NAT-PMP y
 * cualquier fallo (timeout, UNSUPP_VERSION…) devuelve <b>null</b> ⇒ la cascada
 * sigue con NAT-PMP igual que antes. Nunca puede estrangular el eslabón
 * siguiente.
 *
 * <p>§ <b>Todos los formatos verificados contra la RFC 6887 descargada</b>
 * (§7.1 petición, §7.2 respuesta, §7.4 códigos, §11.1 MAP), ⊘ contra memoria:
 * <pre>
 *  Petición (60 B total):
 *    0        Version = 2
 *    1        R=0 | Opcode (MAP = 1)
 *    2-3      Reserved = 0
 *    4-7      Requested Lifetime            BE32
 *    8-23     PCP Client's IP (IPv4-mapped) 16 B
 *    24-35    Mapping Nonce                 12 B aleatorios
 *    36       Protocol (TCP = 6, IANA)
 *    37-39    Reserved = 0
 *    40-41    Internal Port                 BE16
 *    42-43    Suggested External Port       BE16 (0 = sin preferencia)
 *    44-59    Suggested External IP         16 B (all-zeros = sin preferencia)
 *
 *  Respuesta (60 B):
 *    0        Version = 2
 *    1        R=1 | Opcode
 *    2        Reserved
 *    3        Result Code                   ← 1 byte (¡no 2 como en NAT-PMP!)
 *    4-7      Lifetime                      BE32
 *    8-11     Epoch Time                    BE32
 *    12-23    Reserved (96 b)
 *    24-35    Mapping Nonce                 ← debe ser EL NUESTRO
 *    36       Protocol
 *    37-39    Reserved
 *    40-41    Internal Port                 BE16
 *    42-43    Assigned External Port        BE16
 *    44-59    Assigned External IP          16 B (IPv4-mapped)
 * </pre>
 *
 * <p>§ <b>Dos trampas reales</b> que la RFC advierte y este código respeta:
 * <ul>
 *   <li><b>§8.1 / result code 12</b>: el campo "PCP Client's IP" <b>MUST</b> ser
 *       la IP de origen real del datagrama; si no coincide → ADDRESS_MISMATCH.
 *       Por eso el socket se <b>ata</b> a la IP local detectada, no se manda
 *       desde una dirección cualquiera.</li>
 *   <li><b>§9 / result code 1</b>: si el router contesta UNSUPP_VERSION es que
 *       habla NAT-PMP (versión 0) ⇒ devolvemos null y deja paso a
 *       {@link NatPmpClient}.</li>
 * </ul>
 *
 * <p>§ Seguridad: solo se acepta la respuesta viniendo del propio gateway.
 *
 * <p>§ Threading: bloquea ⇒ llamar desde virtual thread ⊘ del main thread.
 */
public final class PcpClient {

    /** UDP 5351 — el MISMO puerto que NAT-PMP (RFC 6887 §9). */
    public static final int PCP_PORT = 5351;

    private static final int VERSION = 2;
    private static final int OPCODE_MAP = 1;
    /** IANA protocol numbers (RFC 6887 §11.1: "6 (TCP)", "17 (UDP)"). */
    private static final int PROTO_TCP = 6;
    private static final int REQUEST_LEN = 60;
    private static final int RESPONSE_LEN = 60;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PcpClient() {
    }

    /** Mapeo conseguido. {@code externalPort} puede diferir del interno si el router reasigna. */
    public record Mapping(int internalPort, int externalPort, String externalIp,
                          long lifetimeSeconds) {
    }

    // ===== Renovación (mismo problema de lease que UPnP y NAT-PMP) =====

    private static final ScheduledExecutorService RENEWAL_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                final Thread t = new Thread(r, "azorea-pcp-renew");
                t.setDaemon(true);
                return t;
            });
    private static volatile ScheduledFuture<?> renewal;
    private static volatile String renewalGateway;
    private static volatile String renewalLocalIp;

    // ===== API pública =====

    /**
     * Intenta conseguir un endpoint público vía PCP.
     *
     * <p>Cascada D0: UPnP (descubrimiento) → <b>PCP</b> → NAT-PMP → STUN → solo
     * LAN. Cualquier fallo devuelve null y el eslabón siguiente toma el relevo.
     *
     * @param internalPort puerto que escucha el servidor MC
     * @param lifetimeSec  lease en segundos (renovado a la mitad)
     * @return {@code "ip:port"} público, o null si este router no habla PCP
     */
    public static String tryMapEndpoint(final int internalPort, final int lifetimeSec) {
        final String gateway = AzoreaPortForwardGuide.detectDefaultGateway();
        if (gateway == null) {
            AzoreaNetLog.debug(Category.PCP, "sin puerta de enlace por defecto → ⊘ probar PCP");
            return null;
        }
        // §8.1: el campo "PCP Client's IP" debe ser nuestra IP de origen REAL.
        final String localIp = AzoreaHostShared.detectLocalBindAddress();
        if (localIp == null || localIp.isBlank()) {
            AzoreaNetLog.debug(Category.PCP, "sin IP local detectable → ⊘ probar PCP");
            return null;
        }
        try {
            final Mapping mapping = mapTcpPort(gateway, localIp, internalPort, lifetimeSec, 1500);
            startRenewal(gateway, localIp, internalPort, lifetimeSec);

            final String endpoint = mapping.externalIp() + ":" + mapping.externalPort();
            AzoreaNetLog.milestone(Category.PCP, "map-ok",
                    gateway + " → " + endpoint
                            + (mapping.externalPort() == internalPort
                            ? "" : " (router reasignó el puerto: " + internalPort + ")")
                            + " lease=" + mapping.lifetimeSeconds() + "s");
            return endpoint;
        } catch (final SocketTimeoutException e) {
            AzoreaNetLog.info(Category.PCP, gateway + " no responde a PCP → s/ cascada (NAT-PMP)");
            return null;
        } catch (final IOException e) {
            AzoreaNetLog.info(Category.PCP, gateway + " → " + e.getMessage() + " → s/ cascada");
            return null;
        }
    }

    /** MAP TCP:40+16 = 60 bytes. */
    public static Mapping mapTcpPort(final String gateway, final String localIp,
                                     final int internalPort, final long lifetimeSec,
                                     final int timeoutMs) throws IOException {
        final InetAddress gatewayAddr = InetAddress.getByName(gateway);
        final InetAddress clientAddr = InetAddress.getByName(localIp);
        final byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);

        // §8.1: atar el socket a la IP local ⇒ la IP de origen coincide con el
        // campo "PCP Client's IP" y no disparamos ADDRESS_MISMATCH (código 12).
        try (DatagramSocket socket = new DatagramSocket(
                new InetSocketAddress(clientAddr, 0))) {
            socket.setSoTimeout(Math.max(200, timeoutMs));
            final byte[] request = buildMapRequest(clientAddr, internalPort, 0, lifetimeSec, nonce);
            socket.send(new DatagramPacket(request, request.length, gatewayAddr, PCP_PORT));

            final byte[] buf = new byte[256];
            final DatagramPacket response = new DatagramPacket(buf, buf.length);
            socket.receive(response);

            // § Seguridad: solo del gateway (mismo criterio que libnatpmp).
            if (!response.getAddress().equals(gatewayAddr)) {
                throw new IOException("respuesta de otra fuente: " + response.getAddress());
            }
            return parseMapResponse(Arrays.copyOf(buf, response.getLength()),
                    nonce, internalPort);
        }
    }

    /** Cancela la renovación (llamar al parar de hostear). */
    public static void stopRenewal() {
        final ScheduledFuture<?> task = renewal;
        renewal = null;
        renewalGateway = null;
        renewalLocalIp = null;
        if (task != null) task.cancel(false);
    }

    // ===== Renovación =====

    /**
     * Renueva a la mitad del lease — mismo motivo que UPnP/NAT-PMP: si no, el
     * router borra la regla y los SYN entrantes nuevos caen en seco.
     *
     * <p>Capturamos SIEMPRE: si la excepción escapa, {@code scheduleAtFixedRate}
     * cancela en silencio el resto de ejecuciones (fallo fantasma, ya visto en
     * UPnP y NAT-PMP).
     */
    private static void startRenewal(final String gateway, final String localIp,
                                     final int port, final long lifetimeSec) {
        stopRenewal();
        renewalGateway = gateway;
        renewalLocalIp = localIp;
        final long period = Math.max(30L, lifetimeSec / 2L);
        renewal = RENEWAL_SCHEDULER.scheduleAtFixedRate(() -> {
            final String gw = renewalGateway;
            final String lip = renewalLocalIp;
            if (gw == null || lip == null) return;
            try {
                final Mapping m = mapTcpPort(gw, lip, port, lifetimeSec, 1500);
                AzoreaNetLog.debug(Category.PCP,
                        "renew OK port=" + port + " → " + m.externalPort()
                                + " lease=" + m.lifetimeSeconds() + "s");
            } catch (final Exception e) {
                AzoreaNetLog.failure(Category.PCP, "renew", "port=" + port, e.getMessage());
            }
        }, period, period, TimeUnit.SECONDS);
        AzoreaNetLog.info(Category.PCP, "renew programado cada " + period + "s (port=" + port + ")");
    }

    // ===== Wire: construcción (RFC 6887 §7.1 + §11.1 Figure 9) =====

    static byte[] buildMapRequest(final InetAddress clientIp, final int internalPort,
                                  final int suggestedExternalPort, final long lifetimeSec,
                                  final byte[] nonce) {
        if (internalPort < 1 || internalPort > 65535) {
            throw new IllegalArgumentException("puerto interno fuera de rango: " + internalPort);
        }
        if (suggestedExternalPort < 0 || suggestedExternalPort > 65535) {
            throw new IllegalArgumentException("puerto externo fuera de rango: " + suggestedExternalPort);
        }
        if (lifetimeSec < 0) throw new IllegalArgumentException("lifetime negativo: " + lifetimeSec);
        if (nonce == null || nonce.length != 12) {
            throw new IllegalArgumentException("nonce PCP debe tener 12 bytes");
        }

        final byte[] v4 = clientIp.getAddress();
        if (v4.length != 4) {
            // Suficiente p/ nuestro caso (cascada IPv4). IPv6 vendrá en F-doble.
            throw new IllegalArgumentException("se esperaba IPv4, llegó: " + clientIp);
        }

        final byte[] b = new byte[REQUEST_LEN];
        b[0] = (byte) VERSION;                       // §7.1 Version = 2
        b[1] = (byte) (OPCODE_MAP & 0x7F);           // R=0 (bit7) | Opcode=1
        b[2] = 0;                                    // Reserved 16b
        b[3] = 0;
        putB32(b, 4, lifetimeSec);                   // Requested Lifetime

        // §7.1 "IPv4 address is represented using an IPv4-mapped IPv6 address"
        b[8 + 10] = (byte) 0xFF;                     // ::ffff:a.b.c.d
        b[8 + 11] = (byte) 0xFF;
        System.arraycopy(v4, 0, b, 8 + 12, 4);

        System.arraycopy(nonce, 0, b, 24, 12);       // §11.1 Mapping Nonce (96 b)
        b[36] = (byte) PROTO_TCP;                    // §11.1 Protocol = 6 (TCP)
        b[37] = 0;                                   // Reserved 24b
        b[38] = 0;
        b[39] = 0;
        putB16(b, 40, internalPort);                 // Internal Port
        putB16(b, 42, suggestedExternalPort);        // Suggested External Port (0 = sin pref.)
        // 44..59 = Suggested External IP → all-zeros = "sin preferencia" (§11.1)
        return b;
    }

    // ===== Wire: parseo (RFC 6887 §7.2 + §11.1 Figure 10) =====

    /**
     * @param r                    respuesta cruda
     * @param expectedNonce        el nonce que NOSOTROS enviamos
     * @param expectedInternalPort el puerto interno que pedimos
     */
    static Mapping parseMapResponse(final byte[] r, final byte[] expectedNonce,
                                    final int expectedInternalPort) throws IOException {
        if (r == null || r.length < RESPONSE_LEN) {
            throw new IOException("respuesta PCP demasiado corta: " + (r == null ? 0 : r.length) + " B");
        }
        if ((r[0] & 0xFF) != VERSION) {
            throw new IOException("versión PCP no esperada: " + (r[0] & 0xFF));
        }
        if ((r[1] & 0x80) == 0) {
            throw new IOException("el paquete no es una respuesta (bit R = 0)");
        }
        final int opcode = r[1] & 0x7F;
        if (opcode != OPCODE_MAP) {
            throw new IOException("opcode de respuesta inesperado: " + opcode);
        }

        final int result = r[3] & 0xFF;              // §7.2: 1 BYTE (¡no 2 como NAT-PMP!)
        if (result != 0) {
            throw new IOException(describeResultCode(result));
        }

        final long lifetime = b32(r, 4);
        if (!Arrays.equals(expectedNonce, Arrays.copyOfRange(r, 24, 36))) {
            // Respuesta a una petición ajena o router mal implementado → no usarla.
            throw new IOException("nonce distinto al enviado (respuesta a otra petición)");
        }
        final int protocol = r[36] & 0xFF;
        if (protocol != PROTO_TCP) {
            throw new IOException("protocolo de la respuesta no es TCP: " + protocol);
        }
        final int internalPort = b16(r, 40);
        if (internalPort != expectedInternalPort) {
            throw new IOException("respuesta de un mapeo anterior (puerto " + internalPort
                    + " ≠ " + expectedInternalPort + ")");
        }
        final int externalPort = b16(r, 42);
        if (externalPort < 1 || externalPort > 65535) {
            throw new IOException("puerto externo fuera de rango: " + externalPort);
        }
        final String externalIp = parseAddress(r, 44);
        if (externalIp == null) {
            // §11.1: all-zeros = sin NAT de por medio (modo firewall). No aporta
            // IP pública ⇒ STUN del eslabón siguiente tendrá que darla.
            throw new IOException("router sin NAT (IP externa all-zeros) → s/ cascada a STUN");
        }
        return new Mapping(internalPort, externalPort, externalIp, lifetime);
    }

    /**
     * Dirección de 16 bytes → texto. Devuelve null si es all-zeros
     * (sin preferencia / sin NAT).
     *
     * <p>IPv4 viaja como IPv4-mapped (::ffff:a.b.c.d) — §5.
     */
    static String parseAddress(final byte[] r, final int off) {
        final byte[] raw = Arrays.copyOfRange(r, off, off + 16);
        boolean allZero = true;
        for (final byte x : raw) {
            if (x != 0) {
                allZero = false;
                break;
            }
        }
        if (allZero) return null;
        try {
            final boolean v4Mapped = raw[10] == (byte) 0xFF && raw[11] == (byte) 0xFF;
            if (v4Mapped) {
                return InetAddress.getByAddress(Arrays.copyOfRange(raw, 12, 16)).getHostAddress();
            }
            return InetAddress.getByAddress(raw).getHostAddress();
        } catch (final Exception e) {
            return null;
        }
    }

    /** §7.4 — códigos leídos literalmente de la RFC, ⊘ de memoria. */
    static String describeResultCode(final int code) {
        return switch (code) {
            case 1 -> "UNSUPP_VERSION: el router no habla PCP (¿solo NAT-PMP?) → s/ cascada";
            case 2 -> "NOT_AUTHORIZED: PCP deshabilitado por política del router";
            case 3 -> "MALFORMED_REQUEST: la petición no se pudo parsear";
            case 4 -> "UNSUPP_OPCODE: MAP no soportado por el router";
            case 5 -> "UNSUPP_OPTION: opción no soportada";
            case 6 -> "MALFORMED_OPTION: opción mal formada";
            case 7 -> "NETWORK_FAILURE: el router aún no tiene IP externa";
            case 8 -> "NO_RESOURCES: el router sin recursos p/ más mapeos";
            case 9 -> "UNSUPP_PROTOCOL: el router no soporta TCP (solo UDP)";
            case 10 -> "USER_EX_QUOTA: cuota de puertos agotada";
            case 11 -> "CANNOT_PROVIDE_EXTERNAL: no puede dar el puerto sugerido";
            case 12 -> "ADDRESS_MISMATCH: la IP de origen no coincide con el campo cliente";
            case 13 -> "EXCESSIVE_REMOTE_PEERS: demasiados peers (filtro)";
            default -> "error PCP desconocido: " + code;
        };
    }

    // ===== Utilidades binarias =====

    private static void putB16(final byte[] b, final int off, final int v) {
        b[off] = (byte) (v >> 8);
        b[off + 1] = (byte) v;
    }

    private static void putB32(final byte[] b, final int off, final long v) {
        b[off] = (byte) (v >> 24);
        b[off + 1] = (byte) (v >> 16);
        b[off + 2] = (byte) (v >> 8);
        b[off + 3] = (byte) v;
    }

    private static int b16(final byte[] b, final int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static long b32(final byte[] b, final int off) {
        return ((long) (b[off] & 0xFF) << 24)
                | ((long) (b[off + 1] & 0xFF) << 16)
                | ((long) (b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFFL);
    }
}
