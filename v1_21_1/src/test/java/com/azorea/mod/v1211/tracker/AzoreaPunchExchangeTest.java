// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.tracker;

import com.azorea.mod.v1211.identity.AzoreaIdentity;
import com.azorea.mod.v1211.identity.HwFingerprint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests del intercambio de endpoints firmado (DA-12 — innovación sobre PeerCraft).
 *
 * <p>El test que importa es {@link #hostileRendezvousCannotRedirect()}: si falla,
 * un rendezvous hostil podría decirte q/ tu amigo está en otra dirección y meterte
 * en un servidor suyo. Eso es exactamente lo q/ su {@code PEER_FOUND} sin firma permite.
 */
final class AzoreaPunchExchangeTest {

    /** Identidad completa d/ un "peer" d/ prueba. */
    private static final class Peer {
        final byte[] edPub;
        final byte[] edPriv;
        final byte[] xPub;
        final byte[] hw;
        final String hwB64;
        final String xB64;
        final String edB64;

        Peer() throws Exception {
            final AzoreaIdentity.Keys ed = AzoreaIdentity.generateSigningKeyPair();
            final AzoreaIdentity.Keys x = AzoreaIdentity.generateKeyPair();
            this.edPub = ed.publicKey();
            this.edPriv = ed.privateKey();
            this.xPub = x.publicKey();
            this.hw = new HwFingerprint("AA:BB:CC:DD:EE:FF", "4C1B-3F2A", "10.0", "DESKTOP").hwCommit();
            this.hwB64 = b64(this.hw);
            this.xB64 = b64(this.xPub);
            this.edB64 = b64(this.edPub);
        }
    }

    private static String b64(final byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    private static final long NOW = 1_800_000_000_000L;

    private static AzoreaPunchExchange.Endpoint build(final Peer p, final String ip, final int port) {
        return AzoreaPunchExchange.unsigned(
                "General", ip, port, p.xB64, p.edB64, p.hwB64, NOW + 30_000L);
    }

    /** Cambia el valor d/ un campo dENTRO del canónico, conservando la firma original. */
    private static String tamper(final String json, final String field, final String value) {
        final String marker = "\"" + field + "\":";
        final int i = json.indexOf(marker);
        if (i < 0) throw new AssertionError("campo no encontrado: " + field + " en " + json);
        final int start = i + marker.length();
        final int end = json.indexOf(',', start);
        final int close = json.lastIndexOf('}');
        final int stop = end < 0 ? close : Math.min(end, close);
        return json.substring(0, start) + "\"" + value + "\"" + json.substring(stop);
    }

    // ===== 1. Round-trip =====

    @Test
    @DisplayName("sign → parseAndVerify: round-trip íntegro, id rederivado")
    void roundTrip() throws Exception {
        final Peer p = new Peer();
        final var signed = AzoreaPunchExchange.sign(build(p, "203.0.113.9", 25565), p.edPriv);

        final var back = AzoreaPunchExchange.parseAndVerify(
                AzoreaPunchExchange.toJson(signed), NOW);

        assertEquals("203.0.113.9", back.ip());
        assertEquals(25565, back.port());
        // § El id NO viene del alambre: se rederiva (DA-8). Debe cuadrar c/ la clave real.
        assertEquals("General", back.endpoint().displayName());
        assertTrue(back.azoreaId().startsWith("AZ-"), "id derivado con formato AZ-…: " + back.azoreaId());
        assertEquals(com.azorea.mod.v1211.identity.AzoreaId.derive(p.edPub, p.xPub, p.hw),
                back.azoreaId(), "id rederivado == derive(claves)");
    }

    // ===== 2. EL TEST CLAVE =====

    @Test
    @DisplayName("Rendezvous hostil altera la IP ⇒ la firma cae (no puede redirigir)")
    void hostileRendezvousCannotRedirect() throws Exception {
        final Peer p = new Peer();
        final var signed = AzoreaPunchExchange.sign(build(p, "203.0.113.9", 25565), p.edPriv);
        final String json = AzoreaPunchExchange.toJson(signed);

        // Lo q/ haría un rendezvous hostil: cambiar la dirección por la suya.
        final String forged = tamper(json, "ip", "6.6.6.6");

        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AzoreaPunchExchange.parseAndVerify(forged, NOW));
        assertTrue(e.getMessage().contains("firma"), "motivo: " + e.getMessage());
    }

    @Test
    @DisplayName("Alterar el puerto ⇒ la firma cae")
    void tamperedPortRejected() throws Exception {
        final Peer p = new Peer();
        final String json = AzoreaPunchExchange.toJson(
                AzoreaPunchExchange.sign(build(p, "203.0.113.9", 25565), p.edPriv));

        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AzoreaPunchExchange.parseAndVerify(tamper(json, "port", "31337"), NOW));
        assertTrue(e.getMessage().contains("firma"), "motivo: " + e.getMessage());
    }

    // ===== 3. Sin firma / caducado =====

    @Test
    @DisplayName("Announce sin firma ⇒ rechazado con motivo claro (tracker antiguo/hostil)")
    void unsignedRejected() {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AzoreaPunchExchange.parseAndVerify(AzoreaPunchExchange.toJson(
                        AzoreaPunchExchange.unsigned("X", "1.2.3.4", 25565,
                                "eA==", "eQ==", "aA==", NOW + 1000)), NOW));
        assertTrue(e.getMessage().contains("firma"), "motivo: " + e.getMessage());
    }

    @Test
    @DisplayName("Caducado ⇒ rechazado")
    void expiredRejected() throws Exception {
        final Peer p = new Peer();
        final var stale = AzoreaPunchExchange.unsigned(
                "General", "1.2.3.4", 25565, p.xB64, p.edB64, p.hwB64, NOW - 60_000L);
        final String json = AzoreaPunchExchange.toJson(
                AzoreaPunchExchange.sign(stale, p.edPriv));

        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AzoreaPunchExchange.parseAndVerify(json, NOW));
        assertTrue(e.getMessage().contains("caducado"), "motivo: " + e.getMessage());
    }

    // ===== 4. Suplantación =====

    @Test
    @DisplayName("Claves ajenas ⇒ el id derivado es OTRO (no se puede hacer pasar por el host)")
    void foreignKeysYieldDifferentId() throws Exception {
        final Peer victim = new Peer();
        final Peer attacker = new Peer();

        // El atacante firma un announce válido PERO c/ sus propias claves.
        final String json = AzoreaPunchExchange.toJson(AzoreaPunchExchange.sign(
                AzoreaPunchExchange.unsigned("General", "6.6.6.6", 25565,
                        attacker.xB64, attacker.edB64, attacker.hwB64, NOW + 1000),
                attacker.edPriv));

        final var back = AzoreaPunchExchange.parseAndVerify(json, NOW);

        // La firma ES válida (es suya) — por eso el caller DEBE comparar el id derivado
        // contra el q/ ya verificó d/l bundle. Ahí se detecta.
        final String victimId = com.azorea.mod.v1211.identity.AzoreaId.derive(
                victim.edPub, victim.xPub, victim.hw);
        assertNotEquals(victimId, back.azoreaId(), "la id d/ la víctima no debe aparecer");
    }

    // ===== 5. Canónico =====

    @Test
    @DisplayName("El canónico no contiene la firma (evita ciclicidad) y es determinista")
    void canonicalIsDeterministicAndUnsigned() throws Exception {
        final Peer p = new Peer();
        final var e = build(p, "1.2.3.4", 25565);
        final String a = AzoreaPunchExchange.canonical(e);
        final String b = AzoreaPunchExchange.canonical(e);

        assertEquals(a, b, "determinista");
        assertTrue(!a.contains("signature"), "la firma ⊘ se incluye en lo q/ se firma");
        assertTrue(a.startsWith(AzoreaPunchExchange.CANONICAL_VERSION), "lleva prefijo d/ versión");
        // ⊗ el id NO viaja: se rederiva.
        assertTrue(!a.contains("azorea_id"), "el id ⊘ viaja por el alambre");
    }

    @Test
    @DisplayName("Salto de línea en displayName ⊘ rompe la estructura del canónico")
    void newlineIsSanitized() throws Exception {
        final Peer p = new Peer();
        final var crafted = AzoreaPunchExchange.unsigned(
                "Dev\ninjection", "1.2.3.4", 25565, p.xB64, p.edB64, p.hwB64, NOW + 1000);
        final String json = AzoreaPunchExchange.toJson(
                AzoreaPunchExchange.sign(crafted, p.edPriv));

        final var back = AzoreaPunchExchange.parseAndVerify(json, NOW);
        assertTrue(!back.endpoint().displayName().contains("\n"), "el \\n no sobrevive");
        assertEquals(NOW + 1000L, back.endpoint().expEpochMs(),
                "exp intacto ⇒ el salto d/ línea no desplazó campos");
    }

    @Test
    @DisplayName("Basura / JSON inválido ⇒ rechazo claro, ⊘ NPE")
    void malformedRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> AzoreaPunchExchange.parseAndVerify(null, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> AzoreaPunchExchange.parseAndVerify("   ", NOW));
        assertThrows(IllegalArgumentException.class,
                () -> AzoreaPunchExchange.parseAndVerify("{no es json", NOW));
        // JSON válido pero c/ campos faltantes.
        assertThrows(IllegalArgumentException.class,
                () -> AzoreaPunchExchange.parseAndVerify("{\"ip\":\"1.2.3.4\"}", NOW));
    }

    @Test
    @DisplayName("Puerto fuera de rango ⇒ rechazado (encode y decode)")
    void portRangeValidated() throws Exception {
        final Peer p = new Peer();
        assertThrows(IllegalArgumentException.class,
                () -> AzoreaPunchExchange.unsigned("X", "1.2.3.4", 0, p.xB64, p.edB64, p.hwB64, 0));
        assertThrows(IllegalArgumentException.class,
                () -> AzoreaPunchExchange.unsigned("X", "1.2.3.4", 99999, p.xB64, p.edB64, p.hwB64, 0));

        final String json = "{\"ip\":\"1.2.3.4\",\"port\":99999,\"signature\":\"x\"}";
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AzoreaPunchExchange.parseAndVerify(json, NOW));
        assertTrue(e.getMessage().contains("rango"), "motivo: " + e.getMessage());
    }

    @Test
    @DisplayName("Canonical cubre ip y port (por eso la redirection cae)")
    void canonicalCoversEndpoint() {
        final var e = AzoreaPunchExchange.unsigned("N", "10.0.0.1", 25565, "eA==", "eQ==", "aA==", 7);
        final String c = AzoreaPunchExchange.canonical(e);
        assertTrue(c.contains("ip=10.0.0.1"), "ip firmada");
        assertTrue(c.contains("port=25565"), "port firmado");
        // UTF-8 explícito ⇒ el canónico q/ firma el emisor y el q/ verifica el receptor
        // son byte-a-byte idénticos.
        assertEquals(AzoreaPunchExchange.CANONICAL_VERSION.length() + 1,
                new String(c.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)
                        .indexOf('\n') + 1, "codificación UTF-8 estable");
    }
}
