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
 *   -Dorg.slf4j.simpleLogger.defaultLogLevel=info  (SLF4J simple logger)
 */
public final class TrackerServerMain {

    private static final Logger LOGGER = Logger.getLogger(TrackerServerMain.class.getName());

    private TrackerServerMain() {
    }

    public static void main(final String[] args) throws IOException {
        final int port = Integer.parseInt(System.getProperty("tracker.port", "9090"));
        final Path dataFile = Paths.get(System.getProperty("tracker.data", "tracker-data.json"));

        LOGGER.info("Iniciando Azorea TrackerServer — port=" + port + ", data=" + dataFile.toAbsolutePath());

        final TrackerServer server = new TrackerServer(port, dataFile);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutdown hook disparado");
            server.stop();
        }, "azorea-tracker-shutdown"));

        server.start();
        LOGGER.info("TrackerServer activo en puerto " + port
                + ". Endpoints: POST /announce, GET /list, DELETE /announce, GET /health");
    }
}
