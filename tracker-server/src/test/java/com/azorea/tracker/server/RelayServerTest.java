// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.tracker.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests E2E del RelayServer (ver AGENTS.md § F6.2 + F7).
 *
 * <p>Verifica el bridge bidireccional entre 2 peers via TCP.
 *
 * <p>§ Notas:
 * <ul>
 *   <li>Usa puertos efímeros para evitar conflictos.</li>
 *   <li>Cada test crea su propio RelayServer (no shared).</li>
 *   <li>Las conexiones TCP se hacen en threads separadas para evitar deadlock.</li>
 * </ul>
 */
class RelayServerTest {

    private RelayServer relayServer;

    @AfterEach
    void cleanup() {
        if (relayServer != null) {
            relayServer.stop();
        }
    }

    @Test
    @DisplayName("RelayServer: 2 peers pueden enviar y recibir mensajes bidireccionalmente")
    void relayBridgesTwoPeers() throws Exception {
        final int relayPort = findFreePort();
        relayServer = new RelayServer(relayPort);
        relayServer.start();

        final String sessionId = relayServer.createSession();

        // Peers conectan via TCP.
        final Socket peer1 = new Socket("127.0.0.1", relayServer.port());
        final Socket peer2 = new Socket("127.0.0.1", relayServer.port());

        // Cada peer envía su session_id como primera línea.
        final byte[] helloBytes = (sessionId + "\n").getBytes(StandardCharsets.UTF_8);
        peer1.getOutputStream().write(helloBytes);
        peer1.getOutputStream().flush();
        peer2.getOutputStream().write(helloBytes);
        peer2.getOutputStream().flush();

        // Cada peer debería recibir "OK\n" cuando el otro haya conectado.
        final BufferedReader r1 = new BufferedReader(
                new InputStreamReader(peer1.getInputStream(), StandardCharsets.UTF_8));
        final BufferedReader r2 = new BufferedReader(
                new InputStreamReader(peer2.getInputStream(), StandardCharsets.UTF_8));

        final String line1 = r1.readLine();
        final String line2 = r2.readLine();
        assertNotNull(line1, "peer1 debe recibir respuesta");
        assertNotNull(line2, "peer2 debe recibir respuesta");
        assertEquals("OK", line1);
        assertEquals("OK", line2);

        // § Bridge: peer1 → peer2.
        peer1.getOutputStream().write("Hola desde peer1\n".getBytes(StandardCharsets.UTF_8));
        peer1.getOutputStream().flush();
        final String received2 = r2.readLine();
        assertEquals("Hola desde peer1", received2);

        // § Bridge: peer2 → peer1.
        peer2.getOutputStream().write("Respuesta de peer2\n".getBytes(StandardCharsets.UTF_8));
        peer2.getOutputStream().flush();
        final String received1 = r1.readLine();
        assertEquals("Respuesta de peer2", received1);

        peer1.close();
        peer2.close();
    }

    @Test
    @DisplayName("RelayServer: session_id desconocido → conexión rechazada")
    void relayRejectsUnknownSession() throws Exception {
        final int relayPort = findFreePort();
        relayServer = new RelayServer(relayPort);
        relayServer.start();

        final Socket peer = new Socket("127.0.0.1", relayServer.port());
        peer.getOutputStream().write("relay-no-existe\n".getBytes(StandardCharsets.UTF_8));
        peer.getOutputStream().flush();

        final BufferedReader r = new BufferedReader(
                new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
        final String response = r.readLine();
        assertNotNull(response);
        assertTrue(response.startsWith("ERR"), "debe empezar con ERR: " + response);

        peer.close();
    }

    @Test
    @DisplayName("RelayServer: solo 2 peers por session — el 3ro es rechazado")
    void relayRejectsThirdPeer() throws Exception {
        final int relayPort = findFreePort();
        relayServer = new RelayServer(relayPort);
        relayServer.start();

        final String sessionId = relayServer.createSession();

        // § Conectar y registrar peer1; esperar a OK antes de conectar peer2 (evita races).
        final Socket p1 = new Socket("127.0.0.1", relayServer.port());
        p1.getOutputStream().write((sessionId + "\n").getBytes(StandardCharsets.UTF_8));
        p1.getOutputStream().flush();
        final BufferedReader r1 = new BufferedReader(
                new InputStreamReader(p1.getInputStream(), StandardCharsets.UTF_8));
        // p1 no recibirá OK hasta que p2 conecte (notifyReady se llama en handlePeer de p2).
        // Esperar un poco para que p1 quede registrado como peer1.
        Thread.sleep(50);

        final Socket p2 = new Socket("127.0.0.1", relayServer.port());
        p2.getOutputStream().write((sessionId + "\n").getBytes(StandardCharsets.UTF_8));
        p2.getOutputStream().flush();
        final BufferedReader r2 = new BufferedReader(
                new InputStreamReader(p2.getInputStream(), StandardCharsets.UTF_8));

        // p1 y p2 deben recibir OK.
        assertEquals("OK", r1.readLine());
        assertEquals("OK", r2.readLine());

        // Ahora p3 — debe ser rechazado.
        final Socket p3 = new Socket("127.0.0.1", relayServer.port());
        p3.getOutputStream().write((sessionId + "\n").getBytes(StandardCharsets.UTF_8));
        p3.getOutputStream().flush();
        final BufferedReader r3 = new BufferedReader(
                new InputStreamReader(p3.getInputStream(), StandardCharsets.UTF_8));
        final String p3resp = r3.readLine();
        assertNotNull(p3resp);
        assertTrue(p3resp.startsWith("ERR"), "p3 debe ser rechazado: " + p3resp);

        p1.close();
        p2.close();
        p3.close();
    }

    @Test
    @DisplayName("RelayServer: createSession devuelve IDs únicos")
    void relaySessionIdsAreUnique() throws Exception {
        final int relayPort = findFreePort();
        relayServer = new RelayServer(relayPort);
        relayServer.start();

        final String id1 = relayServer.createSession();
        final String id2 = relayServer.createSession();
        final String id3 = relayServer.createSession();

        assertNotEquals(id1, id2);
        assertNotEquals(id2, id3);
        assertNotEquals(id1, id3);
        assertEquals(3, relayServer.activeSessions());
    }

    @Test
    @DisplayName("TrackerServer con relay habilitado expone /relay/session")
    void trackerServerRelayEndpoint() throws Exception {
        final int httpPort = findFreePort();
        final int relayPort = findFreePort();
        final TrackerServer tracker = new TrackerServer(httpPort, tempDataFile(), relayPort);
        tracker.start();
        try {
            final java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            final java.net.http.HttpResponse<String> resp = client.send(
                    java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create("http://127.0.0.1:" + httpPort + "/relay/session"))
                            .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                            .build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(201, resp.statusCode());
            final com.google.gson.JsonObject obj =
                    com.google.gson.JsonParser.parseString(resp.body()).getAsJsonObject();
            assertTrue(obj.has("sessionId"));
            assertTrue(obj.has("relayPort"));
            assertEquals(relayPort, obj.get("relayPort").getAsInt());
        } finally {
            tracker.stop();
        }
    }

    private static int findFreePort() throws IOException {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static java.nio.file.Path tempDataFile() throws IOException {
        final java.nio.file.Path p = java.nio.file.Files.createTempFile("azorea-relay-test-", ".json");
        java.nio.file.Files.deleteIfExists(p);
        return p;
    }
}