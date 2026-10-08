// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client;

import com.azorea.mod.v1211.identity.AzoreaId;
import com.azorea.mod.v1211.identity.AzoreaIdentity;
import com.azorea.mod.v1211.identity.HwFingerprint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests del invite bundle copiable (§ DA-10 / D0 serverless).
 *
 * <p>Esto es lo que permite que 2 PCs remotas se conecten **sin ningún tracker**,
 * así que los tests de ataque son los que importan: si se puede alterar el
 * endpoint sin romper la verificación, el diseño no sirve.
 */
final class AzoreaInviteBundleTest {

    // ===== Fixtures =====

    /** Identidad completa de un "host" de prueba. */
    private static final class Host {
        final byte[] edPub;
        final byte[] edPriv;
        final byte[] xPub;
        final byte[] hw;
        final String id;

        Host() throws GeneralSecurityException {
            final AzoreaIdentity.Keys ed = AzoreaIdentity.generateSigningKeyPair();
            final AzoreaIdentity.Keys x = AzoreaIdentity.generateKeyPair();
            this.edPub = ed.publicKey();
            this.edPriv = ed.privateKey();
            this.xPub = x.publicKey();
            this.hw = new HwFingerprint("AA:BB:CC:DD:EE:FF", "4C1B-3F2A", "10.0", "DESKTOP").hwCommit();
            this.id = AzoreaId.derive(edPub, xPub, hw);
        }
    }

    private static AzoreaInviteBundle.Bundle bundle(final Host h, final String host, final int port) {
        return new AzoreaInviteBundle.Bundle(
                h.id, "Dev", h.xPub, h.edPub, h.hw,
                host, port, "11111111-2222-4333-8444-555555555555",
                "My World", "1.21.1",
                4_000_000_000L);
    }

    private static final long NOW = 1_800_000_000L;   // dentro de la ventana válida

    // ===== 1. Round-trip =====

    @Test
    @DisplayName("encode → decodeAndVerify: round-trip íntegro")
    void roundTrip() throws GeneralSecurityException {
        final Host h = new Host();
        final String wire = AzoreaInviteBundle.encode(bundle(h, "203.0.113.10", 25565), h.edPriv);

        assertTrue(wire.startsWith(AzoreaInviteBundle.PREFIX_COMPRESSED + "."),
                "prefijo de versión (AZB2 = comprimido): " + wire.substring(0, 6));
        assertEquals(3, wire.split("\\.").length, "3 segmentos");

        final AzoreaInviteBundle.Bundle back = AzoreaInviteBundle.decodeAndVerify(wire, NOW);
        assertEquals(h.id, back.azoreaId());
        assertEquals("203.0.113.10", back.host());
        assertEquals(25565, back.port());
        assertEquals("Dev", back.displayName());
        assertEquals("My World", back.worldName());
        assertEquals("1.21.1", back.mcVersion());
        // § v2 (2026-10-01): `game` ya no viaja en el alambre — en el bundle solo se
        // logueaba y costaba 42 B (56 chars de invite). El game_id de verdad vive en
        // TrackerProtocol, tipo aparte.
        assertNull(back.gameId(), "v2 no emite `game`");
    }

    @Test
    @DisplayName("El bundle trae la identidad entera p/ guardarla como amigo (D0: card+invite en 1)")
    void bundleCarriesFullIdentity() throws GeneralSecurityException {
        final Host h = new Host();
        final AzoreaInviteBundle.Bundle back = AzoreaInviteBundle.decodeAndVerify(
                AzoreaInviteBundle.encode(bundle(h, "198.51.100.7", 25565), h.edPriv), NOW);

        // Todo lo que un "Azorea card" necesitaría, ya viene dentro.
        assertTrue(AzoreaId.verify(back.azoreaId(), back.ed25519Pub(), back.x25519Pub(), back.hwCommit()),
                "el joiner puede re-verificar la ID por su cuenta");
    }

    // ===== 2. EL ATAQUE CLAVE: alterar el endpoint =====

    @Test
    @DisplayName("Alterar el endpoint ⇒ la firma cae (impide redirigir a un servidor malicioso)")
    void tamperedHostRejected() throws GeneralSecurityException {
        final Host h = new Host();
        final String wire = AzoreaInviteBundle.encode(bundle(h, "203.0.113.10", 25565), h.edPriv);

        // § v2: el endpoint ya no viaja en `host` sino en `hosts` — que es lo que
        // queda firmado. El test protege la PROPIEDAD (no se puede redirigir), no
        // el nombre del campo.
        final String forged = tamperField(wire, "hosts", "evil.example.com");

        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AzoreaInviteBundle.decodeAndVerify(forged, NOW));
        assertTrue(e.getMessage().contains("firma"), "motivo: " + e.getMessage());
    }

    @Test
    @DisplayName("Alterar el puerto ⇒ la firma cae")
    void tamperedPortRejected() throws GeneralSecurityException {
        final Host h = new Host();
        final String wire = AzoreaInviteBundle.encode(bundle(h, "203.0.113.10", 25565), h.edPriv);

        final String forged = tamperField(wire, "port", "31337");

        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AzoreaInviteBundle.decodeAndVerify(forged, NOW));
        assertTrue(e.getMessage().contains("firma"), "motivo: " + e.getMessage());
    }

    // ===== 3. Suplantación de identidad =====

    @Test
    @DisplayName("v1: ID de la víctima + claves ajenas ⇒ falla la auto-certificación")
    void swappedKeysRejectedInLegacyV1() throws GeneralSecurityException {
        final Host victim = new Host();
        final Host attacker = new Host();

        // § Canónico v1 construido a mano: es el ÚNICO formato en que el `id` viene
        // del alambre, y por tanto el único donde un atacante podría intentar colar
        // la id de la víctima. Se conserva esta ruta de lectura ⇒ se conserva este test.
        final String canonical = "azorea-invite/v1\n"
                + "id=" + victim.id + "\n"
                + "name=Dev\n"
                + "x25519=" + Base64.getEncoder().encodeToString(attacker.xPub) + "\n"
                + "ed25519=" + Base64.getEncoder().encodeToString(attacker.edPub) + "\n"
                + "hw=" + Base64.getEncoder().encodeToString(victim.hw) + "\n"
                + "host=evil.example.com\n"
                + "port=25565\n"
                + "world=My World\n"
                + "mc=1.21.1\n"
                + "exp=4000000000\n";
        final byte[] msg = canonical.getBytes(StandardCharsets.UTF_8);
        final String wire = "AZB1."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(msg) + "."
                + Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(AzoreaIdentity.sign(attacker.edPriv, msg));

        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AzoreaInviteBundle.decodeAndVerify(wire, NOW));
        assertTrue(e.getMessage().contains("no corresponde"),
                "debe fallar la auto-certificación, motivo: " + e.getMessage());
    }

    @Test
    @DisplayName("v2: la ID no viaja ⇒ la suplantación id-de-víctima es imposible por construcción")
    void v2IdIsDerivedSoSpoofingIsStructurallyImpossible() throws GeneralSecurityException {
        final Host victim = new Host();
        final Host attacker = new Host();

        // Mismo intento que en v1, con el formato actual (v2 no emite `id`).
        final AzoreaInviteBundle.Bundle spoofed = new AzoreaInviteBundle.Bundle(
                victim.id, "Dev", attacker.xPub, attacker.edPub, victim.hw,
                "evil.example.com", 25565, null, "My World", "1.21.1", 4_000_000_000L);

        final AzoreaInviteBundle.Bundle back = AzoreaInviteBundle.decodeAndVerify(
                AzoreaInviteBundle.encode(spoofed, attacker.edPriv), NOW);

        // El decoder deriva el id de las claves que viajan (las del atacante) ⇒ la id
        // de la víctima no puede aparecer jamás. Acortar el invite no solo ahorra
        // 45 chars: elimina el campo por el que se expresaba el ataque.
        assertNotEquals(victim.id, back.azoreaId(), "la id de la víctima no debe aparecer");
        assertEquals(AzoreaId.derive(attacker.edPub, attacker.xPub, victim.hw), back.azoreaId(),
                "el id es exactamente f(claves)");
    }

    @Test
    @DisplayName("Firma de otra clave sobre claves de la víctima ⇒ falla la firma")
    void foreignSignatureRejected() throws GeneralSecurityException {
        final Host victim = new Host();
        final Host attacker = new Host();

        // Payload con las claves de la víctima (ID cuadra), firmado con la del atacante.
        final AzoreaInviteBundle.Bundle b = bundle(victim, "203.0.113.10", 25565);
        final String wire = AzoreaInviteBundle.encode(b, attacker.edPriv);

        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AzoreaInviteBundle.decodeAndVerify(wire, NOW));
        assertTrue(e.getMessage().contains("firma"), "motivo: " + e.getMessage());
    }

    // ===== 4. Robustez ante texto de chat =====

    @Test
    @DisplayName("Sobrevive a saltos de línea y espacios (Discord/WhatsApp envuelven texto)")
    void survivesChatLineWrapping() throws GeneralSecurityException {
        final Host h = new Host();
        final String wire = AzoreaInviteBundle.encode(bundle(h, "203.0.113.10", 25565), h.edPriv);

        // Simula wrap cada 32 chars + espacios aleatorios.
        final StringBuilder wrapped = new StringBuilder();
        for (int i = 0; i < wire.length(); i++) {
            wrapped.append(wire.charAt(i));
            if (i % 32 == 31) wrapped.append('\n');
            if (i % 32 == 16) wrapped.append(' ');
        }

        final AzoreaInviteBundle.Bundle back =
                AzoreaInviteBundle.decodeAndVerify(wrapped.toString(), NOW);
        assertEquals(h.id, back.azoreaId());
        assertEquals(25565, back.port());
    }

    @Test
    @DisplayName("displayName con salto de línea no rompe el parseo (se sanea al codificar)")
    void newlineInDisplayNameIsSanitized() throws GeneralSecurityException {
        final Host h = new Host();
        final AzoreaInviteBundle.Bundle b = new AzoreaInviteBundle.Bundle(
                h.id, "Dev\nINYECCION", h.xPub, h.edPub, h.hw,
                "203.0.113.10", 25565, "g", "W\nname=x", "1.21.1", 4_000_000_000L);

        final AzoreaInviteBundle.Bundle back =
                AzoreaInviteBundle.decodeAndVerify(AzoreaInviteBundle.encode(b, h.edPriv), NOW);

        assertFalseWithNewline(back.displayName(), "el \n no debe sobrevivir");
        assertEquals(h.id, back.azoreaId());
        // § El test de "no desplazó campos": `mc` viene DESPUÉS de `world` en el
        // canónico, así que si el \n de "W\nname=x" se colara como salto de línea
        // real, `name=x` se comería el resto de la estructura y mc quedaría mal.
        assertEquals("1.21.1", back.mcVersion(), "mc intacto ⇒ el salto no desplazó campos");
    }

    private static void assertFalseWithNewline(final String s, final String msg) {
        if (s != null && (s.contains("\n") || s.contains("\r"))) {
            throw new AssertionError(msg + ": " + s);
        }
    }

    // ===== 5. Rechazos =====

    @Test
    @DisplayName("Caducado ⇒ rechazado")
    void expiredRejected() throws GeneralSecurityException {
        final Host h = new Host();
        final AzoreaInviteBundle.Bundle expiring = new AzoreaInviteBundle.Bundle(
                h.id, "Dev", h.xPub, h.edPub, h.hw,
                "203.0.113.10", 25565, "g", "W", "1.21.1", NOW - 60);
        final String wire = AzoreaInviteBundle.encode(expiring, h.edPriv);

        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AzoreaInviteBundle.decodeAndVerify(wire, NOW));
        assertTrue(e.getMessage().contains("caducado"), "motivo: " + e.getMessage());
    }

    @Test
    @DisplayName("Basura y entradas malformadas ⇒ rechazo claro, ⊘ NPE")
    void malformedRejected() throws GeneralSecurityException {
        final Host h = new Host();
        final String valid = AzoreaInviteBundle.encode(bundle(h, "203.0.113.10", 25565), h.edPriv);

        // Vacío / null
        assertThrows(IllegalArgumentException.class, () -> AzoreaInviteBundle.decodeAndVerify(null, NOW));
        assertThrows(IllegalArgumentException.class, () -> AzoreaInviteBundle.decodeAndVerify("   ", NOW));
        // Prefijo equivocado
        assertThrows(IllegalArgumentException.class,
                () -> AzoreaInviteBundle.decodeAndVerify("ZZZ9.aa.bb", NOW));
        // No 3 partes
        assertThrows(IllegalArgumentException.class,
                () -> AzoreaInviteBundle.decodeAndVerify("AZB1.solouna", NOW));
        // Payload que no es base64
        assertThrows(IllegalArgumentException.class,
                () -> AzoreaInviteBundle.decodeAndVerify("AZB1.!!!.???", NOW));
        // Payload válido pero con campos faltantes
        final String noKeys = "AZB1."
                + Base64.getUrlEncoder().withoutPadding()
                .encodeToString("azorea-invite/v1\nid=AZ-AAAAAA-BBBBBB-CCCCCC-DDDDDD\nhost=1.2.3.4\nport=1\n"
                        .getBytes(StandardCharsets.UTF_8))
                + ".AAAA";
        assertThrows(IllegalArgumentException.class,
                () -> AzoreaInviteBundle.decodeAndVerify(noKeys, NOW));
        // Campo base64 corrupto dentro del canónico
        assertThrows(IllegalArgumentException.class,
                () -> AzoreaInviteBundle.decodeAndVerify(
                        tamperField(valid, "x25519", "@@@no-base64@@@"), NOW));
        // Puerto alterado sin re-firmar ⇒ cae en la verificación de firma
        // (el caso "host que firma un puerto inválido" no puede ocurrir:
        //  encode() ya rechaza puertos fuera de rango).
        assertThrows(IllegalArgumentException.class,
                () -> AzoreaInviteBundle.decodeAndVerify(tamperField(valid, "port", "99999"), NOW));
    }

    @Test
    @DisplayName("encode rechaza puerto/endpoint inválido")
    void encodeValidatesInput() throws GeneralSecurityException {
        final Host h = new Host();
        final AzoreaInviteBundle.Bundle noPort = new AzoreaInviteBundle.Bundle(
                h.id, "Dev", h.xPub, h.edPub, h.hw, "1.2.3.4", 0, "g", "W", "1.21.1", 0);
        assertThrows(IllegalArgumentException.class,
                () -> AzoreaInviteBundle.encode(noPort, h.edPriv));

        final AzoreaInviteBundle.Bundle noHost = new AzoreaInviteBundle.Bundle(
                h.id, "Dev", h.xPub, h.edPub, h.hw, "  ", 25565, "g", "W", "1.21.1", 0);
        assertThrows(IllegalArgumentException.class,
                () -> AzoreaInviteBundle.encode(noHost, h.edPriv));
    }

    @Test
    @DisplayName("§ D0/T3: varios endpoints ⇒ se conservan el orden y todos llegan al joiner")
    void multiEndpointRoundTrip() throws GeneralSecurityException {
        final Host h = new Host();
        final java.util.List<String> endpoints = java.util.List.of(
                "203.0.113.9", "2001:db8::4", "192.168.1.50");
        final AzoreaInviteBundle.Bundle b = new AzoreaInviteBundle.Bundle(
                h.id, "Dev", h.xPub, h.edPub, h.hw,
                "203.0.113.9", 25565, "g", "W", "1.21.1", 4_000_000_000L,
                endpoints);

        final AzoreaInviteBundle.Bundle back = AzoreaInviteBundle.decodeAndVerify(
                AzoreaInviteBundle.encode(b, h.edPriv), NOW);

        assertEquals(endpoints, back.candidates(), "orden preservado");
        assertTrue(back.candidates().stream().anyMatch(c -> c.contains(":")),
                "la IPv6 debe llegar al joiner");
    }

    @Test
    @DisplayName("Compatibilidad: bundle antiguo SIN campo `hosts` ⇒ verifica y cae a [host]")
    void legacyBundleWithoutHostsStillVerifies() throws GeneralSecurityException {
        final Host h = new Host();

        // Canónico del formato ANTERIOR (sin la línea `hosts=`), firmado a mano —
        // exactamente lo que generaba el build previo a Tier 3.
        final String canonical = "azorea-invite/v1\n"
                + "id=" + h.id + "\n"
                + "name=Dev\n"
                + "x25519=" + Base64.getEncoder().encodeToString(h.xPub) + "\n"
                + "ed25519=" + Base64.getEncoder().encodeToString(h.edPub) + "\n"
                + "hw=" + Base64.getEncoder().encodeToString(h.hw) + "\n"
                + "host=203.0.113.10\n"
                + "port=25565\n"
                + "game=11111111-2222-4333-8444-555555555555\n"
                + "world=My World\n"
                + "mc=1.21.1\n"
                + "exp=4000000000\n";
        final byte[] msg = canonical.getBytes(StandardCharsets.UTF_8);
        final byte[] sig = AzoreaIdentity.sign(h.edPriv, msg);
        final String wire = "AZB1."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(msg) + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(sig);

        final AzoreaInviteBundle.Bundle back = AzoreaInviteBundle.decodeAndVerify(wire, NOW);

        assertEquals("203.0.113.10", back.host());
        assertEquals("25565", String.valueOf(back.port()));
        assertEquals(java.util.List.of("203.0.113.10"), back.candidates(),
                "sin `hosts` debe caer al único endpoint conocido");
    }

    @Test
    @DisplayName("candidates() nunca devuelve vacío si hay host (y sí vacío si no lo hay)")
    void candidatesFallback() throws GeneralSecurityException {
        final Host h = new Host();
        final AzoreaInviteBundle.Bundle conHost = new AzoreaInviteBundle.Bundle(
                h.id, "Dev", h.xPub, h.edPub, h.hw,
                "10.0.0.5", 25565, "g", "W", "1.21.1", 0L,
                java.util.List.of());
        assertEquals(java.util.List.of("10.0.0.5"), conHost.candidates());

        final AzoreaInviteBundle.Bundle sinHost = new AzoreaInviteBundle.Bundle(
                h.id, "Dev", h.xPub, h.edPub, h.hw,
                null, 25565, "g", "W", "1.21.1", 0L, null);
        assertTrue(sinHost.candidates().isEmpty());
    }

    // ===== Helper: simula a un atacante que edita un campo del payload =====

    /**
     * Compat AZB1: un bundle emitido <b>antes</b> d' AZB2 (payload sin comprimir)
     * tiene q/ seguir verificándose.
     *
     * <p>§ Sin éste test, "mantener compat" sería sólo una intención escrita en un
     * comentario — y el día q/ alguien toque el parser, nadie se entera.
     */
    @Test
    @DisplayName("compat: un bundle AZB1 legacy (payload s/ comprimir) sigue decodificándose")
    void legacyAzb1StillDecodes() throws Exception {
        final Host h = new Host();
        final AzoreaInviteBundle.Bundle b = bundle(h, "203.0.113.10", 25565);

        // § Construimos el alambre AZB1 A MANO — `encode()` ya sólo emite AZB2.
        final byte[] msg = AzoreaInviteBundle.canonicalForSigning(b)
                .getBytes(StandardCharsets.UTF_8);
        final byte[] sig = AzoreaIdentity.sign(h.edPriv, msg);
        final String wire = "AZB1."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(msg) + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(sig);

        final AzoreaInviteBundle.Bundle back = AzoreaInviteBundle.decodeAndVerify(wire, NOW);
        assertEquals(b.azoreaId(), back.azoreaId(), "id d'errer");
        assertTrue(back.candidates().contains("203.0.113.10"),
                "endpoint perdido: " + back.candidates());
        assertTrue(back.trackers().isEmpty(),
                "bundle viejo ⇒ trackers vacío, no null (compact ctor)");
    }

    /**
     * Decodifica el payload, cambia una línea por su clave y lo vuelve a codificar
     * <b>conservando la firma original</b> — exactamente lo que haría alguien que
     * interceptara un invite y quisiera redirigirlo.
     */
    private static String tamperField(final String wire, final String key, final String value) {
        final String[] parts = wire.split("\\.");
        // § AZB2: el payload va comprimido ⇒ hay q/ descomprimir p/ ver los campos y
        //   recomprimir al salir. Si no, el test fallaría con "campo no encontrado"
        //   en vez d/ con la firma — un FALSO negativo q/ haría creer q/ todo va bien.
        final boolean compressed = AzoreaInviteBundle.PREFIX_COMPRESSED.equals(parts[0]);
        byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
        final byte[] canonicalBytes = compressed
                ? AzoreaInviteBundle.inflate(payload) : payload;
        final String[] lines = new String(canonicalBytes, StandardCharsets.UTF_8)
                .split("\n", -1);
        boolean found = false;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith(key + "=")) {
                lines[i] = key + "=" + value;
                found = true;
            }
        }
        if (!found) throw new AssertionError("campo no encontrado: " + key);
        final String forged = String.join("\n", lines);
        payload = compressed
                ? AzoreaInviteBundle.deflate(forged.getBytes(StandardCharsets.UTF_8))
                : forged.getBytes(StandardCharsets.UTF_8);
        return parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
                + "." + parts[2];   // ← firma original, sin tocar
    }
}
