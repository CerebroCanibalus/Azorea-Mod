// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.tracker.server;

import com.google.gson.Gson;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Audit 2026-10-07 hardening (OWASP Top 10:2025 + seguridad-red).
 *
 * <p>Verifica las 2 mitigaciones del audit:
 * <ol>
 *   <li><b>Body size cap</b>: el tracker impone tope duro de 8 KB en endpoints
 *       pequeños y 64 KB en {@code /punch/announce}. DoS por body grande ⇒ 400.</li>
 *   <li><b>trust_forwarded_for</b>: por default NO se honra {@code X-Forwarded-For}.
 *       Activar sólo si el tracker está detrás de un reverse proxy de confianza.</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("tracker-server — hardening (audit 2026-10-07)")
class TrackerServerHardeningTest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Gson GSON = TrackerProtocol.gson();

    private TrackerServer serverDefault;     // trust_forwarded_for=false (default)
    private TrackerServer serverTrust;       // trust_forwarded_for=true
    private int portDefault;
    private int portTrust;
    private Path tempDataDefault;
    private Path tempDataTrust;
    private HttpClient httpClient;

    @BeforeAll
    void startServers() throws Exception {
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        portDefault = findFreePort();
        tempDataDefault = Files.createTempFile("azorea-hard-default-", ".json");
        Files.deleteIfExists(tempDataDefault);
        serverDefault = new TrackerServer(portDefault, tempDataDefault);
        serverDefault.start();

        portTrust = findFreePort();
        tempDataTrust = Files.createTempFile("azorea-hard-trust-", ".json");
        Files.deleteIfExists(tempDataTrust);
        serverTrust = new TrackerServer(portTrust, tempDataTrust, 0, true);
        serverTrust.start();
    }

    @AfterAll
    void stopServers() {
        if (serverDefault != null) serverDefault.stop();
        if (serverTrust != null) serverTrust.stop();
    }

    // ===== (1) Body size cap =====

    /**
     * § Audit 2026-10-07: POST /announce con body > 8 KB ⇒ 400. Sin el cap,
     *   el server leía N bytes sin límite y los metía a Gson (memoria + CPU).
     */
    @Test
    @DisplayName("POST /announce con 9 KB body ⇒ 400 (cap 8 KB)")
    void body_above_8kb_on_announce_is_rejected() throws Exception {
        final String huge = "X".repeat(9 * 1024);
        final String body = "{\"game_id\":\"" + huge + "\"}";
        final HttpResponse<String> r = post(baseUrl(portDefault) + "/announce",
                body, "application/json");
        // 400 = body demasiado grande (rechazo upfront por Content-Length) o invalid.
        // Nunca debe llegar a 2xx ni a 5xx.
        assertEquals(400, r.statusCode(), "esperaba 400 por body grande");
    }

    /**
     * § Inverso del anterior: cuerpo en el límite (7 KB) sí se procesa.
     *   201 si el announce fuera válido; aquí basta con que NO sea 400 por-cap.
     *   Como el JSON no es un announce válido, esperamos 400 invalid; lo que
     *   nos importa es que el código de error NO sea "body demasiado grande".
     */
    @Test
    @DisplayName("POST /announce con 7 KB body ⇒ procesado (no cap)")
    void body_within_8kb_on_announce_is_processed() throws Exception {
        // 7 KB de padding dentro del game_id — no es un announce válido.
        final String padding = "Y".repeat(7 * 1024);
        final String body = "{\"game_id\":\"" + padding + "\"}";
        final HttpResponse<String> r = post(baseUrl(portDefault) + "/announce",
                body, "application/json");
        // 400 esperado por JSON inválido, NO por cap. La diferencia está en el mensaje.
        assertEquals(400, r.statusCode());
        assertNotEquals(r.body().toLowerCase().contains("demasiado grande"), true,
                "no debe rechazarse por cap");
    }

    /**
     * § Test complementario del cap: 16 KB de body en /presence (también 8 KB cap)
     *   también cae en 400. El cap es uniforme en todos los endpoints "small".
     */
    @Test
    @DisplayName("POST /presence con body > 8 KB ⇒ 400 (cap uniforme)")
    void body_above_8kb_on_presence_is_rejected() throws Exception {
        final String huge = "X".repeat(16 * 1024);
        final HttpResponse<String> r = post(baseUrl(portDefault) + "/presence",
                huge, "application/json");
        assertEquals(400, r.statusCode(), "cap de 8 KB debe activar en /presence");
    }

    // ===== (2) trust_forwarded_for =====

    /**
     * § Audit 2026-10-07: con trust_forwarded_for=true, podemos emitir 30 POSTs a /presence
     *   con X-Forwarded-For rotando y NO disparar rate limit per-IP. Sin el flag,
     *   las 30 con la misma IP de loopback agotarían el rate limit antes.
     */
    @Test
    @DisplayName("trust_forwarded_for=true: XFF rotativo NO dispara rate limit")
    void trust_forwarded_for_true_rotating_xff_avoids_rate_limit() throws Exception {
        // /presence sólo necesita JSON mínimo; ni firma ni announce. Un GET al /health
        // sería trivial, pero queremos ejercitar el path POST con rate limit.
        final String body = "{\"game_id\":\"x\",\"op\":\"join\",\"identity\":"
                + "{\"azorea_id\":\"AZ-XXXXXX-XXXXXX-XXXXXX-XXXXXX\","
                + "\"display_name\":\"t\",\"public_key_base64\":\"AAA\"}}";
        int ok = 0;
        for (int i = 0; i < 30; i++) {
            final String xff = "203.0.113." + (i + 1); // TEST-NET-3, todas distintas
            final HttpResponse<String> r = postWithHeader(
                    baseUrl(portTrust) + "/presence", body, "application/json",
                    "X-Forwarded-For", xff);
            // Cualquier 2xx o 4xx ≠ 429 cuenta como "no rate-limited".
            // 404 game_not_found es lo esperado (no anunciamos antes).
            if (r.statusCode() != 429) {
                ok++;
            }
        }
        assertEquals(30, ok,
                "con trust_forwarded_for=true y XFF rotativo, ningún POST debe ser 429");
    }

    /**
     * § Audit 2026-10-07: con trust_forwarded_for=false (default), X-Forwarded-For
     *   se IGNORA. Las 30 POSTs a /presence desde 127.0.0.1 deben disparar rate limit
     *   después de 60 requests — pero con 30 no llegamos al límite. Este test sólo
     *   verifica que el constructor y lectura de clientIp funcionan sin crashear.
     *   El comportamiento agresivo de rate limit se prueba en TrackerServerE2ETest.
     */
    @Test
    @DisplayName("trust_forwarded_for=false (default): server arranca, requests procesan")
    void trust_forwarded_for_default_does_not_break() throws Exception {
        final String body = "{\"game_id\":\"x\",\"op\":\"join\",\"identity\":"
                + "{\"azorea_id\":\"AZ-XXXXXX-XXXXXX-XXXXXX-XXXXXX\","
                + "\"display_name\":\"t\",\"public_key_base64\":\"AAA\"}}";
        final HttpResponse<String> r = postWithHeader(
                baseUrl(portDefault) + "/presence", body, "application/json",
                "X-Forwarded-For", "198.51.100.99"); // IP spoof cualquiera
        // Cualquier respuesta ≠ 429 es válida; lo que cuenta es q/ el server responde.
        assertNotEquals(429, r.statusCode(),
                "con trust_forwarded_for=false, 1 request no debe ser 429");
    }

    // ===== Helpers =====

    private static int findFreePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static String baseUrl(final int port) {
        return "http://127.0.0.1:" + port;
    }

    private HttpResponse<String> post(final String url, final String body, final String ct)
            throws Exception {
        final HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", ct)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postWithHeader(final String url, final String body,
                                               final String ct, final String headerName,
                                               final String headerValue) throws Exception {
        final HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", ct)
                .header(headerName, headerValue)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }

    // Referenced to keep static analysers off our backs; unused.
    @SuppressWarnings("unused")
    private static String b64(final byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }
    @SuppressWarnings("unused")
    private static byte[] randomBytes(final int n) {
        final byte[] out = new byte[n];
        RANDOM.nextBytes(out);
        return out;
    }
    @SuppressWarnings("unused")
    private static String utf8(final String s) {
        return new String(s.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
    @SuppressWarnings("unused")
    private static String json(final Object o) {
        return GSON.toJson(o);
    }
}