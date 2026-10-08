// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client.screen;

import com.azorea.mod.v1211.AzoreaMod;
import com.azorea.mod.v1211.identity.AzoreaIdentityService;
import com.azorea.mod.tracker.TrackerProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Utilidades compartidas entre AzoreaHostConfigScreen y AzoreaHostSessionScreen (ver AGENTS.md § F6.1).
 */
public final class AzoreaHostShared {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzoreaHostShared.class);

    private AzoreaHostShared() {
    }

    /**
     * § F5.2: construye la Identity del host a partir del AzoreaIdentityService + display name.
     */
    public static TrackerProtocol.Identity buildHostIdentity(final String displayName) {
        final AzoreaIdentityService identitySvc = AzoreaMod.get().identityService();
        if (identitySvc != null && identitySvc.getIdentity() != null) {
            final var id = identitySvc.getIdentity();
            return new TrackerProtocol.Identity(
                    id.azoreaId(),
                    displayName,
                    Base64.getEncoder().encodeToString(id.x25519PublicKey()));
        }
        LOGGER.warn("AzoreaIdentityService no inicializado al construir HostIdentity. "
                + "El announce al tracker será rechazado.");
        return new TrackerProtocol.Identity(
                "AZ-AAAAAA-BBBBBB-CCCCCC-DDDDDD",
                displayName,
                "");
    }

    /**
     * § F4.4: auto-detecta la IP local del PC para el bind address default.
     */
    public static String detectLocalBindAddress() {
        try {
            final var interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return "0.0.0.0";
            String fallback = null;
            for (final var iface : java.util.Collections.list(interfaces)) {
                if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) continue;
                for (final var addr : java.util.Collections.list(iface.getInetAddresses())) {
                    if (addr.isLoopbackAddress()) continue;
                    if (addr instanceof java.net.Inet4Address) {
                        final String host = addr.getHostAddress();
                        if (host.startsWith("169.254.")) {
                            if (fallback == null) fallback = host;
                            continue;
                        }
                        return host;
                    }
                }
            }
            if (fallback != null) return fallback;
        } catch (Exception e) {
            // ignore
        }
        return "0.0.0.0";
    }

    // ===== § F8.x: Shared public address cache (UPnP + STUN) =====
    // Antes vivía en AzoreaHostSessionScreen (AtomicReference<StunResult>).
    // Movido aquí para que cualquier screen (friend list, etc.) pueda usarlo
    // sin depender de la session screen como parent.

    private static volatile String cachedUpnpEndpoint;    // "publicIp:port" si UPnP OK
    private static volatile String cachedStunIp;          // IP pública vía STUN
    private static volatile boolean stunQueried;
    private static volatile int cachedPort = 25565;

    /**
     * Kick off background STUN query (idempotente). Llamar al abrir session screen.
     */
    public static void warmUpPublicAddress(final int port) {
        cachedPort = port;
        // UPnP primero (más rápido y preciso).
        try {
            final var upnp = com.azorea.mod.v1211.client.AzoreaUpnpService.get();
            if (upnp != null && upnp.isAvailable()) {
                final String extIp = upnp.getExternalIp();
                if (extIp != null) {
                    cachedUpnpEndpoint = extIp + ":" + port;
                    LOGGER.info("Public address via UPnP: {}", cachedUpnpEndpoint);
                }
            }
        } catch (final Exception ignored) {
        }
        // § D0/T2: STUN SIEMPRE (idempotente vía stunQueried, 1 paquete UDP).
        // Además de fallback, su IP es el lado que se compara con la WAN del router
        // para diagnosticar CGNAT, así que interesa tenerla cacheada aunque UPnP
        // haya funcionado.
        if (!stunQueried) {
            stunQueried = true;
            Thread.startVirtualThread(() -> {
                try {
                    final var opt = com.azorea.mod.v1211.identity.AzoreaStunClient.query(3000);
                    opt.ifPresent(r -> {
                        cachedStunIp = r.publicIp();
                        LOGGER.info("Public address via STUN: {} (asumiendo puerto {})", r.publicIp(), port);
                    });
                } catch (final Exception e) {
                    LOGGER.debug("STUN warm-up falló: {}", e.getMessage());
                }
            });
        }
    }

    /**
     * § D0/T2: IP pública cacheada vía STUN (null si aún no resolvió). ⊘ bloquea.
     */
    public static String cachedStunIp() {
        return cachedStunIp;
    }

    /**
     * Devuelve el mejor bind address conocido: UPnP > STUN > LAN.
     * Usa el cache; si está vacío, kick off warm-up (async) y devuelve fallback LAN.
     */
    public static String getBestBindAddress(final String lanBindAddress) {
        if (cachedUpnpEndpoint != null) return cachedUpnpEndpoint;
        if (cachedStunIp != null) return cachedStunIp + ":" + cachedPort;
        // Kick off warm-up async para la próxima vez.
        warmUpPublicAddress(cachedPort > 0 ? cachedPort : parsePort(lanBindAddress, 25565));
        return lanBindAddress;
    }

    /** Limpia el cache (llamar al parar host). */
    public static void resetPublicAddressCache() {
        cachedUpnpEndpoint = null;
        cachedStunIp = null;
        stunQueried = false;
    }

    // ===== § D0/T3: direcciones IPv6 globales =====

    /**
     * Direcciones IPv6 globales (2000::/3) de esta máquina, listas para publicar.
     *
     * <p>§ Por qué: es el único eslabón que <b>no necesita tocar el router</b>.
     * Con UPnP y NAT-PMP cerrados (caso verificado en la máquina del General),
     * y con IPv6 saliente funcionando, publicar la dirección v6 permite que el
     * amigo conecte sin port-forward ni mapeo alguno — siempre que el router
     * deje el inbound, que es justo lo que el joiner comprueba al probar.
     *
     * <p>§ Criterios:
     * <ul>
     *   <li>Solo <b>global unicast</b> 2000::/3 ⇒ ⊘ link-local fe80, ⊘
     *       unique-local fc00, ⊘ loopback, ⊘ multicast.</li>
     *   <li><b>/128 primero</b>: una dirección asignada explícitamente (DHCP o
     *       estática) es más estable y más probable que el router la admita de
     *       entrada que un IID dentro del /64 que reparte el RA.</li>
     *   <li>Máximo 3 — el bundle no debe crecer sin límite.</li>
     * </ul>
     *
     * <p>§ Sobre las temporales de privacidad: rotan en <i>horas</i> y el invite
     * caduca en <i>minutos</i>, así que dentro de la vida útil del bundle son
     * estables. Además el joiner las descarta solo si no conectan.
     */
    public static List<String> globalIpv6Addresses() {
        final List<String> explicit = new ArrayList<>();   // /128
        final List<String> inPrefix = new ArrayList<>();   // /64 u otro
        try {
            final var ifaces = java.net.NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) return List.of();
            for (final var iface : java.util.Collections.list(ifaces)) {
                if (!iface.isUp() || iface.isLoopback()) continue;
                for (final var ia : iface.getInterfaceAddresses()) {
                    final java.net.InetAddress a = ia.getAddress();
                    if (!(a instanceof java.net.Inet6Address)) continue;
                    final byte[] b = a.getAddress();
                    // 2000::/3 → primer byte 0b0010_xxxx
                    if ((b[0] & 0xE0) != 0x20) continue;
                    final String ip = a.getHostAddress();
                    final int idx = ip.indexOf('%');       // descarta %scope si acaso
                    final String clean = idx >= 0 ? ip.substring(0, idx) : ip;
                    if (ia.getNetworkPrefixLength() >= 128) {
                        explicit.add(clean);
                    } else {
                        inPrefix.add(clean);
                    }
                }
            }
        } catch (final Exception e) {
            LOGGER.debug("No pude enumerar IPv6: {}", e.getMessage());
        }
        explicit.addAll(inPrefix);
        return explicit.size() > 3 ? new ArrayList<>(explicit.subList(0, 3)) : explicit;
    }

    /** Extrae la parte host de un "host:port" string. */
    public static String parseHost(final String hostPort) {
        if (hostPort == null || hostPort.isBlank()) return "127.0.0.1";
        final int colon = hostPort.lastIndexOf(':');
        if (colon <= 0) return hostPort;
        return hostPort.substring(0, colon);
    }

    /** Extrae el puerto de un "host:port" string. Default 25565. */
    public static int parsePort(final String hostPort, final int defaultPort) {
        if (hostPort == null) return defaultPort;
        final int colon = hostPort.lastIndexOf(':');
        if (colon < 0 || colon == hostPort.length() - 1) return defaultPort;
        try {
            return Integer.parseInt(hostPort.substring(colon + 1));
        } catch (NumberFormatException e) {
            return defaultPort;
        }
    }
}