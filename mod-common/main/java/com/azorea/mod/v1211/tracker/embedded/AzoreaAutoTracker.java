// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.tracker.embedded;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * Gestor del tracker embebido (ver AGENTS.md § Diseño SEAMLESS).
 *
 * <p>§ Responsabilidad:
 * <ul>
 *   <li>Encontrar un puerto libre.</li>
 *   <li>Arrancar {@link AzoreaEmbeddedTracker} en ese puerto.</li>
 *   <li>Exponer la URL local (http://127.0.0.1:PORT) para auto-config.</li>
 *   <li>Detener en shutdown.</li>
 * </ul>
 *
 * <p>§ Por qué singleton:
 * Un tracker por instancia de Minecraft. Reusar entre FMLClientSetupEvent
 * y cualquier screen que quiera consultarlo.
 */
public final class AzoreaAutoTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzoreaAutoTracker.class);
    private static final int PORT_RANGE_START = 8765;
    private static final int PORT_RANGE_END = 8865;
    private static final int FALLBACK_PORT = 0;  // OS-assigned

    private static volatile AzoreaAutoTracker instance;

    private final AzoreaEmbeddedTracker tracker;
    private final int actualPort;

    private AzoreaAutoTracker(final int port) throws IOException {
        this.actualPort = port;
        this.tracker = new AzoreaEmbeddedTracker(port);
        this.tracker.start();
    }

    /** Inicializa el tracker en un puerto libre (idempotente). */
    public static AzoreaAutoTracker getOrCreate() {
        AzoreaAutoTracker local = instance;
        if (local != null) return local;
        synchronized (AzoreaAutoTracker.class) {
            local = instance;
            if (local != null) return local;
            final int port = findFreePort();
            try {
                local = new AzoreaAutoTracker(port);
                instance = local;
                LOGGER.info("AzoreaAutoTracker inicializado en puerto {} (URL={})",
                        local.actualPort, local.localUrl());
                return local;
            } catch (final IOException e) {
                LOGGER.error("No pude arrancar el tracker embebido en puerto {}: {}",
                        port, e.getMessage());
                return null;
            }
        }
    }

    public static AzoreaAutoTracker get() {
        return instance;
    }

    public static void shutdown() {
        synchronized (AzoreaAutoTracker.class) {
            if (instance != null) {
                instance.tracker.stop();
                instance = null;
                LOGGER.info("AzoreaAutoTracker detenido");
            }
        }
    }

    /** URL local del tracker (http://127.0.0.1:PORT). */
    public String localUrl() {
        return tracker.localUrl();
    }

    /** Acceso al tracker embebido (para casos avanzados). */
    public AzoreaEmbeddedTracker tracker() {
        return tracker;
    }

    public int port() {
        return actualPort;
    }

    private static int findFreePort() {
        // Probar rango conocido primero.
        for (int p = PORT_RANGE_START; p <= PORT_RANGE_END; p++) {
            if (isFree(p)) return p;
        }
        // Fallback: dejar al OS asignar.
        LOGGER.warn("No encontré puerto libre en [{}, {}]; usando OS-assigned",
                PORT_RANGE_START, PORT_RANGE_END);
        return FALLBACK_PORT;
    }

    private static boolean isFree(final int port) {
        try (ServerSocket s = new ServerSocket(port)) {
            return true;
        } catch (final IOException e) {
            return false;
        }
    }
}
