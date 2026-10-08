// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.tracker.embedded;

import com.azorea.mod.v1211.identity.AzoreaId;
import com.azorea.mod.v1211.identity.AzoreaIdentity;
import com.azorea.mod.v1211.identity.HwFingerprint;
import com.azorea.mod.v1211.tracker.AzoreaPunchExchange;
import com.google.gson.Gson;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E del <b>buzón de punch</b> — DA-12 paso 4 enchufado al tracker.
 *
 * <p>La frontera d/ seguridad: el tracker deja d/ reescribir el announce (antes metía su
 * source-IP y tiraba el resto) y pasa a ser un <b>buzón ciego al contenido</b> q/:
 * <ol>
 *   <li>rechaza announcements <b>sin firma</b> ⇒ un client antiguo ⊘ inyecta direcciones</li>
 *   <li>verifica la firma ⇒ un <b>emisor</b> hostil ⊘ altera ip/port</li>
 *   <li>ata el id <b>rederivado</b> a la clave d/ almacenaje ⇒ ⊘ publicar b/ la id d/ otro</li>
 *   <li>devuelve el payload <b>verbatim</b> ⇒ la firma sobrevive el viaje</li>
 * </ol>
 * El receptor vuelve a verificar (y re-atá el id) — ⊘ se fía del tracker.
 */
final class PunchMailboxE2ETest {

    private static AzoreaEmbeddedTracker tracker;
    private static String url;
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Gson GSON = new Gson();

    /** Peer d/ prueba: identidad completa (DA-8) + claves. */
    private static final class Peer {
        final byte[] edPub;
        final byte[] edPriv;
        final byte[] xPub;
        final byte[] hw;
        final String id;
        final String edB64;
        final String xB64;
        final String hwB64;

        Peer() throws GeneralSecurityException {
            final AzoreaIdentity.Keys ed = AzoreaIdentity.generateSigningKeyPair();
            final AzoreaIdentity.Keys x = AzoreaIdentity.generateKeyPair();
            this.edPub = ed.publicKey();
            this.edPriv = ed.privateKey();
            this.xPub = x.publicKey();
            this.hw = new HwFingerprint("AA:BB:CC:DD:EE:FF", "4C1B-3F2A-TEST", "10.0", "DESK").hwCommit();
            this.id = AzoreaId.derive(edPub, xPub, hw);
            this.edB64 = Base64.getEncoder().encodeToString(edPub);
            this.xB64 = Base64.getEncoder().encodeToString(xPub);
            this.hwB64 = Base64.getEncoder().encodeToString(hw);
        }

        String signedPayload(final String ip, final int port) throws GeneralSecurityException {
            final AzoreaPunchExchange.Endpoint unsigned = AzoreaPunchExchange.unsigned(
                    "Dev", ip, port, xB64, edB64, hwB64,
                    System.currentTimeMillis() + AzoreaPunchExchange.DEFAULT_TTL_MS);
            return AzoreaPunchExchange.toJson(
                    AzoreaPunchExchange.sign(unsigned, edPriv));
        }
    }

    // ===== Setup =====

    @BeforeAll
    static void startTracker() throws IOException {
        tracker = new AzoreaEmbeddedTracker(0);   // efímero ⇒ sin colisión entre tests
        tracker.start();
        url = tracker.localUrl();
    }

    @AfterAll
    static void stopTracker() {
        if (tracker != null) tracker.stop();
    }

    // ===== HTTP helpers =====

    /** Envoltorio q/ viaja por la red — Gson escapa el payload (JSON dentro d/ JSON). */
    private static String envelope(final String azoreaId, final String payload) {
        final Map<String, String> m = new HashMap<>();
        m.put("azoreaId", azoreaId);
        if (payload != null) m.put("payload", payload);
        return GSON.toJson(m);
    }

    private static HttpResponse<String> post(final String path, final String body)
            throws Exception {
        return HTTP.send(HttpRequest.newBuilder()
                        .uri(URI.create(url + path))
                        .timeout(Duration.ofSeconds(2))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(final String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder()
                        .uri(URI.create(url + path))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ===== 1. Camino feliz =====

    @Test
    @DisplayName("Announce firmado ⇒ 200, y el poll devuelve el payload VERBATIM (la firma sobrevive)")
    void signedAnnounceRoundTrip() throws Exception {
        final Peer p = new Peer();
        final String payload = p.signedPayload("203.0.113.9", 25565);

        final HttpResponse<String> announced = post("/punch/announce",
                envelope(p.id, payload));
        assertEquals(200, announced.statusCode(), "el tracker lo acepta: " + announced.body());

        final HttpResponse<String> polled = get("/punch/poll?azoreaId=" + p.id);
        assertEquals(200, polled.statusCode(), "poll OK: " + polled.body());

        // § VERBATIM: bytes idénticos ⇒ la firma sigue siendo válida al otro lado.
        final Map<?, ?> env = GSON.fromJson(polled.body(), Map.class);
        assertEquals(p.id, env.get("azoreaId"), "clave d/ buzón intacta");
        assertEquals(payload, env.get("payload"),
                "el tracker NO reescribe el payload (rompería la firma)");

        // § Y el receptor lo verifica de nuevo p/ su cuenta.
        final var verified = AzoreaPunchExchange.parseAndVerify(
                (String) env.get("payload"), System.currentTimeMillis());
        assertEquals(p.id, verified.azoreaId(), "id rederivado ⇒ correcto");
        assertEquals("203.0.113.9", verified.ip());
        assertEquals(25565, verified.port());
    }

    // ===== 2. Frontera d/ seguridad =====

    @Test
    @DisplayName("Announce SIN firma ⇒ 400 (un client antiguo ⊘ inyecta direcciones)")
    void unsignedAnnounceRejected() throws Exception {
        final Peer p = new Peer();
        // Sin campo `payload` — lo q/ mandaba el código anterior.
        final HttpResponse<String> r = post("/punch/announce", envelope(p.id, null));

        assertEquals(400, r.statusCode(), "rechazado: " + r.body());
        assertTrue(r.body().contains("firma"), "motivo visible: " + r.body());
    }

    @Test
    @DisplayName("Payload alterado (ip cambiada) ⇒ 400 — el EMISOR no puede mentir")
    void tamperedPayloadRejected() throws Exception {
        final Peer p = new Peer();
        final String good = p.signedPayload("203.0.113.9", 25565);
        final String forged = good.replace("203.0.113.9", "6.6.6.6");

        final HttpResponse<String> r = post("/punch/announce", envelope(p.id, forged));

        assertEquals(400, r.statusCode(), "rechazado: " + r.body());
        assertTrue(r.body().contains("firma"), "motivo visible: " + r.body());
    }

    @Test
    @DisplayName("Firmado c/ claves ajenas pero publicado b/ la id d/ otro ⇒ 400 (ata id↔clave)")
    void cannotPublishUnderForeignId() throws Exception {
        final Peer victim = new Peer();
        final Peer attacker = new Peer();

        // El atacante firma con SUS claves (firma válida) pero lo publica b/ la id d/ la víctima.
        final String payload = attacker.signedPayload("6.6.6.6", 25565);
        final HttpResponse<String> r = post("/punch/announce", envelope(victim.id, payload));

        assertEquals(400, r.statusCode(), "rechazado: " + r.body());
        assertTrue(r.body().contains("id_mismatch") || r.body().contains("no es la clave"),
                "motivo visible: " + r.body());
    }

    @Test
    @DisplayName("Poll d/ una id q/ no anunció ⇒ 404 (no hay nada q/ robar)")
    void pollUnknownIdIs404() throws Exception {
        final Peer p = new Peer();
        final HttpResponse<String> r = get("/punch/poll?azoreaId=" + p.id);

        assertEquals(404, r.statusCode(), "nada almacenado: " + r.body());
    }

    @Test
    @DisplayName("Clear d/ nuestro announce ⇒ el poll posterior vuelve a 404")
    void clearThenGone() throws Exception {
        final Peer p = new Peer();
        post("/punch/announce", envelope(p.id, p.signedPayload("1.2.3.4", 25565)));
        assertEquals(200, get("/punch/poll?azoreaId=" + p.id).statusCode(), "presente");

        post("/punch/clear", envelope(p.id, null));
        assertEquals(404, get("/punch/poll?azoreaId=" + p.id).statusCode(), "limpiado");
    }
}
