// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Debugger centralizado de red (ver AGENTS.md § F8.x).
 *
 * <p>§ Propósito: TODA operación de red del mod pasa por aquí para que el log
 * sea diagnóstico útil sin spamear. Dos niveles:
 * <ul>
 *   <li><b>Normal</b> (default): solo eventos importantes (fallos con razón,
 *       conexiones establecidas, cambios de estado). WARN/INFO.</li>
 *   <li><b>Verbose</b> (`-Dazorea.net.verbose=true` o config `debug_verbose=true`):
 *       TODO — attempts, retries, packet counts, timings. DEBUG.</li>
 * </ul>
 *
 * <p>§ Uso en código:
 * <pre>{@code
 * // Intento (solo en verbose)
 * AzoreaNetLog.attempt(Category.CONNECT, "tcp-direct", "1.2.3.4:25565 timeout=5000ms");
 * if (ok) {
 *     AzoreaNetLog.success(Category.CONNECT, "tcp-direct", "1.2.3.4:25565", elapsedMs);
 * } else {
 *     // Fallo SIEMPRE se loguea con razón
 *     AzoreaNetLog.failure(Category.CONNECT, "tcp-direct", "1.2.3.4:25565",
 *             "ConnectionTimeout tras 5000ms — NAT bloquea inbound");
 * }
 * }</pre>
 *
 * <p>§ Categorías (para filtrar en el log con grep `[Azorea/CATEGORY]`):
 * <ul>
 *   <li>{@link Category#CONNECT} — intentos de conexión TCP (direct, punch, relay)</li>
 *   <li>{@link Category#UPnP} — port-forwarding en router</li>
 *   <li>{@link Category#PUNCH} — hole-punch coordination</li>
 *   <li>{@link Category#STUN} — descubrimiento IP pública</li>
 *   <li>{@link Category#TRACKER} — HTTP al tracker (announce/list/invite/friend)</li>
 *   <li>{@link Category#LAN} — UDP multicast discovery</li>
 *   <li>{@link Category#RELAY} — relay/proxy connections</li>
 *   <li>{@link Category#INVITE} — envío/recepción de invites</li>
 *   <li>{@link Category#HOST} — ciclo de vida del host session</li>
 * </ul>
 *
 * <p>§ Para filtrar en test-server logs:
 * <pre>
 *   grep "Azorea/" logs/latest.log                    # todo
 *   grep "Azorea/CONNECT.*FAIL" logs/latest.log       # solo fallos de conexión
 *   grep "Azorea/UPnP" logs/latest.log                # solo UPnP
 * </pre>
 */
public final class AzoreaNetLog {

    /** Categorías de operación de red. */
    public enum Category {
        CONNECT, UPnP, PCP, NATPMP, PUNCH, STUN, TRACKER, LAN, RELAY, INVITE, HOST, NET
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(AzoreaNetLog.class);

    /** Verbose: si true, loguea attempts + success + debug. Si false, solo fallos + eventos clave. */
    private static volatile boolean verbose =
            Boolean.getBoolean("azorea.net.verbose");

    private AzoreaNetLog() {
    }

    public static void setVerbose(final boolean v) {
        verbose = v;
        LOGGER.info("AzoreaNetLog verbose={}", v);
    }

    public static boolean isVerbose() {
        return verbose;
    }

    /**
     * Intento de operación. Solo se loguea en verbose (para no spamear).
     * Formato: `[Azorea/CATEGORY] attempt: op — details`
     */
    public static void attempt(final Category cat, final String op, final String details) {
        if (verbose) {
            LOGGER.debug("[Azorea/{}] attempt: {} — {}", cat, op, details);
        }
    }

    /**
     * Operación exitosa. En verbose loguea con timing; si no, solo si es hito
     * (conexión establecida, UPnP abierto, etc. — usar {@link #milestone} para eso).
     */
    public static void success(final Category cat, final String op, final String details,
                               final long elapsedMs) {
        if (verbose) {
            LOGGER.debug("[Azorea/{}] success: {} — {} ({}ms)", cat, op, details, elapsedMs);
        }
    }

    /**
     * Fallo de operación. SIEMPRE se loguea (WARN) con la razón — esto es
     * lo que el developer necesita para diagnosticar sin verbose.
     * Formato: `[Azorea/CATEGORY] FAIL: op — details — reason`
     */
    public static void failure(final Category cat, final String op, final String details,
                               final String reason) {
        LOGGER.warn("[Azorea/{}] FAIL: {} — {} — reason: {}", cat, op, details, reason);
    }

    /**
     * Evento hito (conexión establecida, UPnP abierto, punch exitoso...).
     * Siempre se loguea (INFO) — son los pocos eventos importantes.
     */
    public static void milestone(final Category cat, final String op, final String details) {
        LOGGER.info("[Azorea/{}] ✓ {}: {}", cat, op, details);
    }

    /**
     * Estado informativo (cambio de estado, resultado de lookup).
     * En verbose → DEBUG; si no → INFO si es relevante.
     */
    public static void info(final Category cat, final String msg) {
        if (verbose) {
            LOGGER.debug("[Azorea/{}] {}", cat, msg);
        } else {
            LOGGER.info("[Azorea/{}] {}", cat, msg);
        }
    }

    /**
     * Detalle fino (packet counts, retries, states). Solo en verbose.
     */
    public static void debug(final Category cat, final String msg) {
        if (verbose) {
            LOGGER.debug("[Azorea/{}] {}", cat, msg);
        }
    }

    /**
     * Métrica (packet loss, latency, bandwidth). En verbose → DEBUG; si no → INFO
     * solo si la métrica es "mala" (loss > threshold, latency > threshold).
     */
    public static void metric(final Category cat, final String name, final String value,
                              final boolean isBad) {
        if (verbose) {
            LOGGER.debug("[Azorea/{}] metric: {}={}", cat, name, value);
        } else if (isBad) {
            LOGGER.warn("[Azorea/{}] metric ALERT: {}={}", cat, name, value);
        }
    }

    /**
     * Falla con exception. SIEMPRE loguea (WARN) con stack trace en verbose,
     * solo message si no.
     */
    public static void failure(final Category cat, final String op, final Exception e) {
        if (verbose) {
            LOGGER.warn("[Azorea/{}] FAIL: {}", cat, op, e);
        } else {
            LOGGER.warn("[Azorea/{}] FAIL: {} — {}", cat, op, e.toString());
        }
    }
}
