// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.tracker.server;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entry point del TrackerServer standalone.
 *
 * Uso:
 *   java -jar tracker-server-0.1.0.jar
 *   # o vía Gradle:
 *   ./gradlew :tracker-server:run
 *
 * Configuración vía system properties:
 *   -Dtracker.port=9090          (default 9090)
 *   -Dtracker.data=tracker-data.json  (default tracker-data.json, ruta relativa al CWD)
 *   -Dtracker.trust_forwarded_for=false  (default false — Audit 2026-10-07)
 *       § PELIGRO: si true, se honra X-Forwarded-For para el rate limit ⇒ un atacante
 *         puede falsificar la cabecera y bypasear el cap per-IP. Activar SÓLO si el
 *         tracker está detrás de un reverse proxy de confianza que sanee/reescriba la
 *         cabecera. Documentado en SECURITY.md.
 *   -Dorg.slf4j.simpleLogger.defaultLogLevel=info  (SLF4J simple logger)
 */
public final class TrackerServerMain {

    private static final Logger LOGGER = Logger.getLogger(TrackerServerMain.class.getName());

    private TrackerServerMain() {
    }

    public static void main(final String[] args) throws IOException {
        final int port = Integer.parseInt(System.getProperty("tracker.port", "9090"));
        final Path dataFile = Paths.get(System.getProperty("tracker.data", "tracker-data.json"));
        final boolean trustForwardedFor = Boolean.parseBoolean(
                System.getProperty("tracker.trust_forwarded_for", "false"));

        LOGGER.info("Iniciando Azorea TrackerServer — port=" + port
                + ", data=" + dataFile.toAbsolutePath()
                + ", trust_forwarded_for=" + trustForwardedFor);

        final TrackerServer server = new TrackerServer(port, dataFile, 0, trustForwardedFor);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutdown hook disparado");
            server.stop();
        }, "azorea-tracker-shutdown"));

        server.start();
        LOGGER.info("TrackerServer activo en puerto " + port
                + ". Endpoints: POST /announce, GET /list, DELETE /announce, GET /health");
    }
}
