// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.tracker.server;

import com.azorea.mod.v1211.identity.AzoreaId;
import com.azorea.mod.v1211.identity.AzoreaIdentity;
import com.azorea.mod.v1211.tracker.AzoreaPunchExchange;
import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E d/ los endpoints d/ punch d/ `tracker-server` (DA-12 + cierre d/ seguridad F9).
 *
 * <p>§ <b>Por qué éste test existe</b>: el audit F9 encontró que el mirror standalone
 * <b>aceptaba cualquier announce sin verificar identidad</b>. El embebido sí verificaba
 * ⇒ los dos trackers se comportaban DISTINTO c/ el mismo alambre, y la diferencia era
 * exactamente la capa d/ seguridad. Éste suite deja escrito q/ el standalone ya cierra:
 *
 * <pre>
 *   observe     → te devuelve tu endpoint TCP visto desde internet   (capa nueva)
 *   announce    → exige firma Ed25519 ⊕ id rederivado == clave        (F9)
 *   poll        → VERBATIM: el tracker ⊘ puede alterar un payload firmado
 *   target      → routing host←joiner s/ listar el buzón             (W-1)
 *   clear       → el announce muere al terminar el punch
 * </pre>
 *
 * <p>§ Nota: aquí NO se usa `HwFingerprint` (sigue en `v1_21_1`) — `AzoreaId.derive`
 * solo necesita los 3 bytes, así q/ va un hwCommit fijo d'otros tests.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("tracker-server — endpoints d/ punch (rendezvous DA-12)")
class PunchEndpointsE2ETest {

    private TrackerServer server;
    private int port;
    private Path tempDataFile;
    private HttpClient http;
    private final Gson gson = TrackerProtocol.gson();

    @BeforeAll
    void startServer() throws Exception {
        port = findFreePort();
        tempDataFile = Files.createTempFile("azorea-punch-test-", ".json");
        Files.deleteIfExists(tempDataFile);
        server = new TrackerServer(port, tempDataFile);
        server.start();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    // ===== 1 · observe =====

    @Test
    @DisplayName("observe ⇒ devuelve ip + puerto TCP vistos por el tracker")
    void observeReturnsEndpoint() throws Exception {
        final HttpResponse<String> r = get("/punch/observe");
        assertEquals(200, r.statusCode(), r.body());
        final TrackerServer.Observed o = gson.fromJson(r.body(), TrackerServer.Observed.class);
        assertFalse(o.ip() == null || o.ip().isBlank(), "sin ip: " + r.body());
        assertTrue(o.port() >= 1 && o.port() <= 65535, "puerto inválido: " + o.port());
        // § Un ::ffff:a.b.c.d llegaría malformado p/ discar ⇒ hay q/ normalizarlo.
        assertFalse(o.ip().startsWith("::ffff:"), "ip mapeada sin normalizar: " + o.ip());
    }

    // ===== 2 · announce firmado ✓ =====

    @Test
    @DisplayName("announce firmado ⇒ 200 y poll lo devuelve VERBATIM")
    void signedAnnounceRoundTrip() throws Exception {
        final Keys k = new Keys();
        final String payload = signedPayload(k, "127.0.0.1", 51234);

        final HttpResponse<String> posted = post("/punch/announce",
                envelope(k.azoreaId, payload, null));
        assertEquals(200, posted.statusCode(), posted.body());

        final HttpResponse<String> polled = get("/punch/poll?azoreaId=" + k.azoreaId);
        assertEquals(200, polled.statusCode(), polled.body());
        final String returned =
                gson.fromJson(polled.body(), TrackerServer.PunchAnnounce.class).payload();
        assertEquals(payload, returned,
                "el tracker alteró un payload firmado — tendría q/ salir byte a byte");
    }

    @Test
    @DisplayName("announce c/ target ⇒ el host lo encuentra por ?target= sin listar el buzón")
    void targetRouting() throws Exception {
        final Keys host = new Keys();
        final Keys joiner = new Keys();

        // El joiner sabe el id del host ⇒ lo pone como destinatario.
        final HttpResponse<String> posted = post("/punch/announce",
                envelope(joiner.azoreaId, signedPayload(joiner, "127.0.0.1", 40000), host.azoreaId));
        assertEquals(200, posted.statusCode(), posted.body());

        final HttpResponse<String> found = get("/punch/poll?target=" + host.azoreaId);
        assertEquals(200, found.statusCode(), found.body());
        final TrackerServer.PunchAnnounce a =
                gson.fromJson(found.body(), TrackerServer.PunchAnnounce.class);
        assertEquals(joiner.azoreaId, a.azoreaId(), "devolvió al revés");
        assertEquals(host.azoreaId, a.target());
    }

    // ===== 3 · rechazos (cierre d/ F9) =====

    @Test
    @DisplayName("F9: announce SIN firma ⇒ 400 — el standalone ya no acepta a cualquiera")
    void unsignedAnnounceRejected() throws Exception {
        final Keys k = new Keys();
        final HttpResponse<String> r = post("/punch/announce", envelope(k.azoreaId, "", null));
        assertEquals(400, r.statusCode(), r.body());
        assertEquals("unsigned", errorOf(r), r.body());
    }

    @Test
    @DisplayName("F9: payload q/ promete firma pero no la trae ⇒ 400")
    void payloadWithoutSignatureRejected() throws Exception {
        final Keys k = new Keys();
        final String fake = gson.toJson(new AzoreaPunchExchange.Endpoint(
                "Mallory", "1.2.3.4", 1234,
                b64(k.xPub), b64(k.edPub), b64(k.hw),
                System.currentTimeMillis() + 60_000, null));
        final HttpResponse<String> r = post("/punch/announce", envelope(k.azoreaId, fake, null));
        assertEquals(400, r.statusCode(), r.body());
        assertTrue(errorOf(r) != null && "invalid".equals(errorOf(r)),
                "esperaba invalid: " + r.body());
        assertTrue(r.body().contains("sin firma"), r.body());
    }

    @Test
    @DisplayName("F9: payload ALTERADO (otra ip) ⇒ 400 firma inválida")
    void tamperedPayloadRejected() throws Exception {
        final Keys k = new Keys();
        final String good = signedPayload(k, "127.0.0.1", 51234);
        final String tampered = good.replace("127.0.0.1", "6.6.6.6");
        assertFalse(good.equals(tampered), "el replace no hizo nada — el test probaría nada");
        final HttpResponse<String> r = post("/punch/announce",
                envelope(k.azoreaId, tampered, null));
        assertEquals(400, r.statusCode(), r.body());
        assertTrue(r.body().contains("firma"), "esperaba queja d/ firma: " + r.body());
    }

    @Test
    @DisplayName("F9: publicar con el id d/ OTRO ⇒ 400 id_mismatch")
    void idMismatchRejected() throws Exception {
        final Keys victim = new Keys();
        final Keys attacker = new Keys();
        // Payload firmado por el atacante pero guardado con la id de la víctima.
        final String payload = signedPayload(attacker, "127.0.0.1", 51234);
        final HttpResponse<String> r = post("/punch/announce",
                envelope(victim.azoreaId, payload, null));
        assertEquals(400, r.statusCode(), r.body());
        assertEquals("id_mismatch", errorOf(r), r.body());
    }

    // ===== 4 · clear =====

    @Test
    @DisplayName("clear ⇒ el announce muere (poll devuelve 404)")
    void clearRemovesAnnounce() throws Exception {
        final Keys k = new Keys();
        final String payload = signedPayload(k, "127.0.0.1", 51234);
        assertEquals(200, post("/punch/announce", envelope(k.azoreaId, payload, null)).statusCode());

        final HttpResponse<String> cleared = post("/punch/clear",
                envelope(k.azoreaId, payload, null));
        assertTrue(cleared.statusCode() == 204 || cleared.statusCode() == 200,
                "clear devolvió " + cleared.statusCode());

        final HttpResponse<String> after = get("/punch/poll?azoreaId=" + k.azoreaId);
        assertEquals(404, after.statusCode(), after.body());
    }

    // ===== Fixes =====

    /** Par d'claves + id DERIVADA (DA-8) — hwCommit fijo p/ no arrastrar `HwFingerprint`. */
    private static final class Keys {
        final byte[] edPub;
        final byte[] edPriv;
        final byte[] xPub;
        final byte[] hw;
        final String azoreaId;

        Keys() throws GeneralSecurityException {
            final AzoreaIdentity.Keys ed = AzoreaIdentity.generateSigningKeyPair();
            final AzoreaIdentity.Keys x = AzoreaIdentity.generateKeyPair();
            this.edPub = ed.publicKey();
            this.edPriv = ed.privateKey();
            this.xPub = x.publicKey();
            this.hw = "punch-test-hw-commit".getBytes(StandardCharsets.UTF_8);
            this.azoreaId = AzoreaId.derive(edPub, xPub, hw);
        }
    }

    private String signedPayload(final Keys k, final String ip, final int port)
            throws GeneralSecurityException {
        final AzoreaPunchExchange.Endpoint unsigned = AzoreaPunchExchange.unsigned(
                "TrackerTester", ip, port, b64(k.xPub), b64(k.edPub), b64(k.hw),
                System.currentTimeMillis() + 60_000L);
        return AzoreaPunchExchange.toJson(AzoreaPunchExchange.sign(unsigned, k.edPriv));
    }

    /** Envoltorio idéntico al q/ manda el mod (`AzoreaPunchManager.Envelope`). */
    private String envelope(final String azoreaId, final String payload, final String target) {
        return gson.toJson(new Envelope(azoreaId, payload, target));
    }

    private record Envelope(String azoreaId, String payload, String target) {
    }

    private String errorOf(final HttpResponse<String> r) {
        final TrackerProtocol.ErrorResponse e =
                gson.fromJson(r.body(), TrackerProtocol.ErrorResponse.class);
        return e == null ? null : e.error();
    }

    private static String b64(final byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    // ===== HTTP =====

    private HttpResponse<String> get(final String path) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(final String path, final String body) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int findFreePort() throws Exception {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
