// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client;

import com.azorea.mod.v1211.AzoreaNetLog;
import com.azorea.mod.v1211.AzoreaNetLog.Category;

import com.azorea.mod.upnp.Gateway;
import com.azorea.mod.upnp.GatewayFinder;
import com.azorea.mod.upnp.UPnPErrors;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Servicio UPnP para port-forwarding automático (ver AGENTS.md § DA-7 + F8.x).
 *
 * <p>§ Por qué UPnP:
 * <ul>
 *   <li>Resuelve el problema "necesito port forwarding manual" — el router lo hace solo.</li>
 *   <li>Conexión directa sin relay cuando funciona (~80% de routers domésticos).</li>
 *   <li>Integrado con el autohost: al empezar a hostear, abre el puerto automáticamente.</li>
 * </ul>
 *
 * <p>§ Flujo:
 * <pre>{@code
 * 1. AzoreaUpnpService.getOrCreate() → GatewayFinder escanea (async, ~2s)
 * 2. Host inicia → upnpService.openPort(port, ttlSeconds) → externalIP
 * 3. Announce usa externalIP:port como bindAddress
 * 4. Host para → upnpService.closePort(port)
 * }</pre>
 *
 * <p>§ Fallbacks si UPnP falla:
 * <ol>
 *   <li>STUN (ya implementado en AzoreaStunClient)</li>
 *   <li>LAN IP (ya detectada en AzoreaHostShared.detectLocalBindAddress)</li>
 *   <li>Relay via tracker (ya existe RelayServer)</li>
 * </ol>
 *
 * <p>§ Seguridad:
 * <ul>
 *   <li>Lease duration limitado (default 3600s) — el mapping expira si olvidamos cerrar.
 *       § D0/T1: además <b>se renueva</b> cada lease/2 mientras hosteamos; sin eso,
 *       a la 1h el router borra la regla y nadie más puede unirse.</li>
 *   <li>closePort() en shutdown — best-effort.</li>
 *   <li>Sin credenciales (UPnP no las usa) — solo funciona en LAN.</li>
 * </ul>
 */
public final class AzoreaUpnpService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzoreaUpnpService.class);

    /** Lease duration: 1 hora. Si el mod crashea, el mapping expira. */
    private static final int DEFAULT_LEASE_SECONDS = 3600;

    private static volatile AzoreaUpnpService instance;

    private final AtomicReference<Gateway> gateway = new AtomicReference<>();
    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private final AtomicBoolean scanStarted = new AtomicBoolean(false);

    /** § AZOREA (a): el finder del scan vigente (p/ saber si el SSDP contestó). */
    private volatile com.azorea.mod.upnp.GatewayFinder finder;
    /** § AZOREA (a): motivo real por el que el último scan no produjo gateway. */
    private volatile String lastScanProblem;

    /** § D0/T1: hilo dedicado a renovar el lease del mapping. */
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                final Thread t = new Thread(r, "azorea-upnp-renew");
                t.setDaemon(true);
                return t;
            });
    /** § D0/T1: tarea de renovación activa (null si no hay mapping abierto). */
    private volatile ScheduledFuture<?> renewal;

    private AzoreaUpnpService() {
    }

    public static AzoreaUpnpService get() {
        AzoreaUpnpService local = instance;
        if (local != null) return local;
        synchronized (AzoreaUpnpService.class) {
            local = instance;
            if (local != null) return local;
            local = new AzoreaUpnpService();
            instance = local;
            return local;
        }
    }

    /** Inicia el scan async (una sola vez). Llamar al cargar el mod. */
    public void startScan() {
        if (!scanStarted.compareAndSet(false, true)) return;
        runScan();
    }

    /**
     * § D0/T1: reintenta el scan SSDP.
     *
     * <p>El original era de **un solo tiro** (`scanStarted` es CAS) con timeout
     * fijo de 5 s y sin reintento ⇒ si el router contesta tarde, o se reinicia,
     * o cambias de red (VPN), `gateway` quedaba null **para siempre** y caíamos a
     * IP LAN en silencio sin reintentar nunca.
     */
    public void rescan() {
        if (gateway.get() != null || scanning.get()) return;
        runScan();
    }

    /**
     * § D0/T1: espera activa a que el scan encuentre el gateway.
     *
     * <p>Antes `onStartClicked` se rendía al instante: si el scan aún corría,
     * `isAvailable()` era false ⇒ el host anunciaba IP LAN sin esperar siquiera.
     *
     * @param timeoutMs máximo a esperar
     * @return true si hay gateway al salir
     */
    public boolean awaitGateway(final long timeoutMs) {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (gateway.get() != null) return true;
            if (!scanning.get()) break;   // scan terminó sin gateway → no hay nada q/ esperar
            try {
                Thread.sleep(100);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return gateway.get() != null;
    }

    private void runScan() {
        scanning.set(true);
        lastScanProblem = null;
        AzoreaNetLog.attempt(Category.UPnP, "ssdp-scan",
                "Buscando gateway v/ SSDP (multicast + broadcast + unicast al gateway)");
        final GatewayFinder finder = new GatewayFinder(found -> {
            gateway.set(found);
            scanning.set(false);
            lastScanProblem = null;
            AzoreaNetLog.milestone(Category.UPnP, "gateway-found",
                    found.getGatewayIP() + " external=" + found.getExternalIP());
        }, problem -> {
            // § AZOREA: el router CONTESTÓ al SSDP pero no se pudo montar el
            // gateway — guardamos el motivo exacto p/ decir la verdad después.
            lastScanProblem = problem;
            AzoreaNetLog.failure(Category.UPnP, "ssdp-ok-control-caido", "-", problem);
        });
        this.finder = finder;

        // § AZOREA: watchdog con dos velocidades.
        //  · Sin respuesta SSDP → 5 s y listo (es lo normal cuando UPnP está off).
        //  · PERO si el router sí contestó, esperamos 10 s: el fallback puede estar
        //    barriendo puertos buscando el daemon real, y abortarlo a los 5 s
        //    nos dejaría sin poder explicar qué pasa de verdad.
        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(5000);
            } catch (final InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (scanning.get() && finder.ssdpAnswered()) {
                try {
                    Thread.sleep(5000);
                } catch (final InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            if (scanning.get()) {
                scanning.set(false);
                reportScanFailure(finder);
            }
        });
    }

    /**
     * § AZOREA (a): decir la verdad según lo que de verdad pasó.
     *
     * <p>Antes todo caía en un único <i>"¿router no soporta UPnP?"</i> que era
     * <b>falso</b> en el caso más engañoso: el router sí soporta UPnP, sí
     * contesta al SSDP, pero su servicio de control está caído. Culpar al
     * firmware del router por "no soportar" lleva al usuario a mirar una
     * casilla que ya está activada.
     */
    private void reportScanFailure(final GatewayFinder f) {
        final String problem = lastScanProblem;
        if (problem != null && !problem.isBlank()) {
            AzoreaNetLog.failure(Category.UPnP, "scan", "gateway",
                    "SSDP contestó pero el control UPnP no responde — "
                            + "es el FIRMWARE del router, no la falta de soporte. Motivo: " + problem);
            return;
        }
        if (f != null && f.ssdpAnswered()) {
            AzoreaNetLog.failure(Category.UPnP, "scan", "gateway",
                    "SSDP contestó pero no se pudo construir el gateway");
            return;
        }
        AzoreaNetLog.info(Category.UPnP,
                "sin respuesta SSDP — ¿UPnP desactivado en el router, o multicast bloqueado?");
    }

    /** § AZOREA (a): motivo real del último scan fallido (null = sin SSDP o scan OK). */
    public String lastScanProblem() {
        return lastScanProblem;
    }

    public boolean isScanning() {
        return scanning.get();
    }

    public boolean isAvailable() {
        return gateway.get() != null;
    }

    /** IP pública del router (null si UPnP no disponible). */
    public String getExternalIp() {
        final Gateway gw = gateway.get();
        if (gw == null) return null;
        final String ip = gw.getExternalIP();
        if (ip == null || ip.isBlank() || "0.0.0.0".equals(ip)) return null;
        return ip;
    }

    /**
     * Abre el port-forward en el router.
     *
     * @param port puerto MC (ej. 25565)
     * @param leaseSeconds duración del mapping (0 = permanente hasta closePort)
     * @return external IP si OK, null si falla
     */
    public String openPort(final int port, final int leaseSeconds) {
        final Gateway gw = gateway.get();
        if (gw == null) {
            AzoreaNetLog.debug(Category.UPnP, "openPort skipped (gateway=null) port=" + port);
            return null;
        }
        try {
            final int lease = leaseSeconds > 0 ? leaseSeconds : DEFAULT_LEASE_SECONDS;
            final UPnPErrors.AddPortMappingErrors error = gw.openPort(port, lease, false /* TCP */);
            if (error == null) {
                final String extIp = getExternalIp();
                AzoreaNetLog.milestone(Category.UPnP, "openPort", "port=" + port + " lease=" + lease + "s external=" + extIp);
                // § D0/T1: renovar antes de que caduque (sin esto, a la 1 h el
                // mapeo expira y los SYN entrantes nuevos caen).
                scheduleRenewal(port, lease);
                return extIp;
            } else {
                AzoreaNetLog.failure(Category.UPnP, "openPort", "port=" + port, "error=" + error);
                return null;
            }
        } catch (final Exception e) {
            AzoreaNetLog.failure(Category.UPnP, "openPort", "port=" + port, e.toString());
            return null;
        }
    }

    /** Cierra el port-forward. Best-effort. */
    public void closePort(final int port) {
        cancelRenewal();
        final Gateway gw = gateway.get();
        if (gw == null) return;
        try {
            gw.closePort(port, false);
            AzoreaNetLog.info(Category.UPnP, "closePort port=" + port);
        } catch (final Exception e) {
            AzoreaNetLog.debug(Category.UPnP, "closePort failed port=" + port + " " + e.getMessage());
        }
    }

    /** Convenience: intenta abrir y devuelve externalIP:port, o null si falla. */
    public String tryOpenAndGetEndpoint(final int port) {
        final String extIp = openPort(port, DEFAULT_LEASE_SECONDS);
        if (extIp == null) return null;
        return extIp + ":" + port;
    }

    // ===== § D0/T1: Renovación del lease =====

    /**
     * Renueva el mapping a la mitad de su lease, mientras hosteamos.
     *
     * <p>Por qué importa: `openPort` escribe en el router un lease de 1 h. Sin
     * renovación, a la hora el router borra la regla y **nadie más puede unirse** —
     * las conexiones vivas aguantan (el NAT las mantiene por tráfico), pero los
     * SYN entrantes nuevos caen. El host queda "vivo" pero inútil en partidas largas.
     *
     * <p>⚠ Toda excepción se captura <b>dentro</b> del lambda: si escapa,
     * `scheduleAtFixedRate` <b>cancela silenciosamente</b> las ejecuciones futuras
     * y la renovación moriría sin avisar (fallo fantasma).
     */
    private void scheduleRenewal(final int port, final int leaseSeconds) {
        cancelRenewal();
        final long periodSec = Math.max(30L, leaseSeconds / 2L);
        renewal = scheduler.scheduleAtFixedRate(() -> {
            final Gateway gw = gateway.get();
            if (gw == null) return;
            try {
                final UPnPErrors.AddPortMappingErrors err = gw.openPort(port, leaseSeconds, false);
                if (err == null) {
                    AzoreaNetLog.debug(Category.UPnP, "renew OK port=" + port + " lease=" + leaseSeconds + "s");
                } else {
                    AzoreaNetLog.failure(Category.UPnP, "renew", "port=" + port, "error=" + err);
                }
            } catch (final Exception e) {
                AzoreaNetLog.failure(Category.UPnP, "renew", "port=" + port, e.toString());
            }
        }, periodSec, periodSec, TimeUnit.SECONDS);
        AzoreaNetLog.info(Category.UPnP, "renew programado cada " + periodSec + "s (port=" + port + ")");
    }

    private void cancelRenewal() {
        final ScheduledFuture<?> task = renewal;
        renewal = null;
        if (task != null) task.cancel(false);
    }

    // ===== § D0/T2: Diagnóstico de red =====

    /**
     * ¿El external IP del router indica NAT aguas arriba (CGNAT / double-NAT)?
     *
     * <p>Si `GetExternalIPAddress` devuelve una dirección <b>no pública</b>, el
     * router está detrás de otro NAT ⇒ abrir el puerto en ÉL no lo expone a
     * internet y <b>ninguna cantidad de UPnP lo arregla</b>. El mensaje correcto
     * entonces es "pide IP pública a tu ISP / usa IPv6", no "revisa UPnP".
     *
     * <p>Rangos: `10/8`, `172.16/12`, `192.168/16`, `169.254/16` (link-local) y
     * sobre todo **`100.64/10` = RFC 6598 Shared Address Space = el rango típico
     * de CGNAT de ISP**.
     *
     * @return true solo si se pudo parsear Y es no-pública. False si es pública o
     *         no parseable — no alarmamos sin datos.
     */
    public static boolean isUpstreamNat(final String externalIp) {
        if (externalIp == null || externalIp.isBlank()) return false;
        final String[] o = externalIp.split("\\.");
        if (o.length != 4) return false;            // no es IPv4 parseable → ⊘ afirmar
        final int a;
        final int b;
        try {
            a = Integer.parseInt(o[0].trim());
            b = Integer.parseInt(o[1].trim());
        } catch (final NumberFormatException e) {
            return false;
        }
        if (a < 0 || a > 255 || b < 0 || b > 255) return false;
        if (a == 10) return true;                            // 10.0.0.0/8
        if (a == 172 && b >= 16 && b <= 31) return true;     // 172.16.0.0/12
        if (a == 192 && b == 168) return true;               // 192.168.0.0/16
        if (a == 169 && b == 254) return true;               // link-local
        if (a == 100 && b >= 64 && b <= 127) return true;    // 100.64.0.0/10 = CGNAT (RFC 6598)
        if (a == 127 || a == 0) return true;                 // loopback / sin dirección
        return false;
    }

    /**
     * § D0: ¿Esta dirección es privada/no enrutable? ⇒ <b>no la alcanza nadie
     * fuera de tu red</b>.
     *
     * <p>Misma comprobación de rangos que {@link #isUpstreamNat(String)} pero con
     * el significado que tiene al revisar el endpoint de un invite: una IP
     * privada (RFC1918), link-local, CGNAT (RFC6598) o loopback no sirve para
     * que un amigo remoto se conecte.
     */
    public static boolean isNonPublicIp(final String address) {
        return isUpstreamNat(address);
    }

    /** § D0/T2: veredicto corto de alcanzabilidad p/ la UI (null = no evaluado). */
    private volatile Component reachabilityHint;

    public Component reachabilityHint() {
        return reachabilityHint;
    }

    public void setReachabilityHint(final Component hint) {
        this.reachabilityHint = hint;
    }

    public static void shutdown() {
        synchronized (AzoreaUpnpService.class) {
            if (instance != null) {
                // No cerramos puertos aquí — el hostService lo hace al parar.
                instance = null;
            }
        }
    }
}
