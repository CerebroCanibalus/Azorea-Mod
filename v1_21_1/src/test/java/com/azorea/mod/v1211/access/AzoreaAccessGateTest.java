// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.access;

import com.azorea.mod.v1211.identity.AzoreaId;
import com.azorea.mod.v1211.identity.AzoreaIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests del gate de identidad (F10, § A2) — la pieza de seguridad del modo no-premium.
 *
 * <p>Lo que tienen que probar, en orden de importancia:
 * <ol>
 *   <li><b>round-trip honesto</b>: quien tiene la clave entra;</li>
 *   <li><b>el atacante no puede ser otro</b> (ni con su propia clave reclamando la ID ajena);</li>
 *   <li><b>no hay replay</b> (un proof de otra conexión no sirve);</li>
 *   <li><b>toda basura devuelve motivo, nunca excepción</b>.</li>
 * </ol>
 *
 * <p>Sin MC ni red ⇒ siempre corren.
 */
class AzoreaAccessGateTest {

    private static final SecureRandom RNG = new SecureRandom();

    /** Un juego de claves + ID derivada, listo para fabricar proofs. */
    private record Fixture(AzoreaIdentity.Keys signing, byte[] x25519, byte[] hw, String id) {
    }

    private static Fixture fixture() throws Exception {
        final AzoreaIdentity.Keys signing = AzoreaIdentity.generateSigningKeyPair();
        final AzoreaIdentity.Keys x = AzoreaIdentity.generateKeyPair();
        final byte[] hw = new byte[32];
        RNG.nextBytes(hw);
        final String id = AzoreaId.derive(signing.publicKey(), x.publicKey(), hw);
        return new Fixture(signing, x.publicKey(), hw, id);
    }

    private static byte[] challenge() {
        return AzoreaAccessGate.newChallenge(RNG);
    }

    private static String b64(final byte[] raw) {
        return Base64.getEncoder().encodeToString(raw);
    }

    private static byte[] sign(final Fixture f, final byte[] challenge) throws Exception {
        return AzoreaIdentity.sign(f.signing.privateKey(), AzoreaAccessGate.canonic(challenge));
    }

    private static AzoreaAccessGate.Proof proof(final Fixture f, final byte[] challenge)
            throws Exception {
        return new AzoreaAccessGate.Proof(
                AzoreaAccessGate.VERSION, f.id(), "Die_Beria",
                b64(f.signing.publicKey()), b64(f.x25519()), b64(f.hw()), b64(sign(f, challenge)));
    }

    // ===== Lo que SÍ debe pasar =====

    @Test
    @DisplayName("round-trip: quien tiene la clave y firma ESTE reto ⇒ verificado")
    void roundTripHonesto() throws Exception {
        final Fixture f = fixture();
        final byte[] c = challenge();

        final AzoreaAccessGate.Verdict v = AzoreaAccessGate.verify(proof(f, c), c);

        assertTrue(v.ok(), "debe aceptarse, motivo: " + v.reason());
        assertEquals(f.id(), v.azoreaId());
        assertEquals("Die_Beria", v.displayName());
    }

    @Test
    @DisplayName("canonic() es estable y lleva el dominio — un reto de otra parte no sirve aquí")
    void canonicEsDeterministaYLlevaElDominio() {
        final byte[] c = challenge();

        final byte[] a = AzoreaAccessGate.canonic(c);
        final byte[] b = AzoreaAccessGate.canonic(c);

        assertArrayEquals(a, b, "debe ser determinista");
        assertEquals(AzoreaAccessGate.CANONICAL_DOMAIN,
                new String(a, 0, AzoreaAccessGate.CANONICAL_DOMAIN.length(),
                        StandardCharsets.UTF_8));
        assertEquals(0x00, a[AzoreaAccessGate.CANONICAL_DOMAIN.length()],
                "el dominio debe separarse con 0x00, no concatenarse a ciegas");
        assertEquals(AzoreaAccessGate.CANONICAL_DOMAIN.length() + 1 + c.length, a.length);
    }

    @Test
    @DisplayName("retos distintos ⇒ canonic() distinto (la firma no es transferible)")
    void canonicCambiaConElReto() {
        final byte[] c1 = challenge();
        final byte[] c2 = challenge();

        assertFalse(java.util.Arrays.equals(
                AzoreaAccessGate.canonic(c1), AzoreaAccessGate.canonic(c2)));
    }

    // ===== Los ataques =====

    @Test
    @DisplayName("ATAQUE: clave propia reclamando la ID de la víctima ⇒ auto-certificación cae")
    void atacanteReclamaIdAjena() throws Exception {
        final Fixture victim = fixture();
        final Fixture attacker = fixture();
        final byte[] c = challenge();

        // El atacante firma correctamente… pero con SU clave, publicando LA ID de la víctima.
        final AzoreaAccessGate.Proof spoofed = new AzoreaAccessGate.Proof(
                AzoreaAccessGate.VERSION, victim.id(), "Die_Beria",
                b64(attacker.signing.publicKey()), b64(attacker.x25519()), b64(attacker.hw()),
                b64(sign(attacker, c)));

        final AzoreaAccessGate.Verdict v = AzoreaAccessGate.verify(spoofed, c);

        assertFalse(v.ok(), "no puede hacerse pasar por la víctima");
        assertTrue(v.reason().contains("auto-certificación"),
                "el motivo debe señalar la auto-certificación: " + v.reason());
    }

    @Test
    @DisplayName("ATAQUE: firma con clave ajena sobre la ID propia ⇒ la firma cae")
    void firmaConClaveAjena() throws Exception {
        final Fixture owner = fixture();
        final Fixture other = fixture();
        final byte[] c = challenge();

        // ID y claves públicas del dueño, pero firmado con la clave del otro.
        final AzoreaAccessGate.Proof forged = new AzoreaAccessGate.Proof(
                AzoreaAccessGate.VERSION, owner.id(), "Die_Beria",
                b64(owner.signing.publicKey()), b64(owner.x25519()), b64(owner.hw()),
                b64(sign(other, c)));

        final AzoreaAccessGate.Verdict v = AzoreaAccessGate.verify(forged, c);

        assertFalse(v.ok());
        assertTrue(v.reason().contains("firma"), "motivo: " + v.reason());
    }

    @Test
    @DisplayName("ATAQUE: REPLAY — proof válido de OTRA conexión ⇒ no sirve en ésta")
    void replayDeOtraConexion() throws Exception {
        final Fixture f = fixture();
        final byte[] challengeOld = challenge();
        final byte[] challengeNew = challenge();

        // Firmado para el reto viejo…
        final AzoreaAccessGate.Proof replayed = new AzoreaAccessGate.Proof(
                AzoreaAccessGate.VERSION, f.id(), "Die_Beria",
                b64(f.signing.publicKey()), b64(f.x25519()), b64(f.hw()),
                b64(sign(f, challengeOld)));

        // …verificado contra el reto nuevo de ESTA conexión.
        final AzoreaAccessGate.Verdict v = AzoreaAccessGate.verify(replayed, challengeNew);

        assertFalse(v.ok(), "un proof de otra sesión no debe valer");
        assertTrue(v.reason().contains("firma"), "motivo: " + v.reason());
    }

    @Test
    @DisplayName("ATAQUE: mensaje alterado tras firmar ⇒ la firma cae")
    void mensajeAlterado() throws Exception {
        final Fixture f = fixture();
        final byte[] c = challenge();
        final byte[] sig = sign(f, c);

        // Se re-signa el canónico pero con un challenge distinto: es el mismo ataque que
        // el replay, visto desde el otro lado — el mensaje firmado ya no es ése.
        final byte[] other = new byte[c.length];
        RNG.nextBytes(other);
        final AzoreaAccessGate.Proof tampered = new AzoreaAccessGate.Proof(
                AzoreaAccessGate.VERSION, f.id(), "Die_Beria",
                b64(f.signing.publicKey()), b64(f.x25519()), b64(f.hw()), b64(sig));

        final AzoreaAccessGate.Verdict v = AzoreaAccessGate.verify(tampered, other);
        assertFalse(v.ok());
    }

    // ===== Validación de input — nunca excepción =====

    @Test
    @DisplayName("campos vacíos ⇒ motivo claro por campo, ⊘ NPE")
    void camposVacios() throws Exception {
        final Fixture f = fixture();
        final byte[] c = challenge();

        final AzoreaAccessGate.Verdict v = AzoreaAccessGate.verify(
                new AzoreaAccessGate.Proof(AzoreaAccessGate.VERSION, f.id(), "   ",
                        b64(f.signing.publicKey()), b64(f.x25519()), b64(f.hw()), b64(sign(f, c))),
                c);

        assertFalse(v.ok());
        assertTrue(v.reason().contains("displayName"), "motivo: " + v.reason());
    }

    @Test
    @DisplayName("versión desconocida ⇒ rechazado (no se adivina qué hay detrás)")
    void versionDesconocida() throws Exception {
        final Fixture f = fixture();
        final byte[] c = challenge();

        final AzoreaAccessGate.Verdict v = AzoreaAccessGate.verify(
                new AzoreaAccessGate.Proof("999", f.id(), "Die_Beria",
                        b64(f.signing.publicKey()), b64(f.x25519()), b64(f.hw()), b64(sign(f, c))),
                c);

        assertFalse(v.ok());
        assertTrue(v.reason().contains("versión"), "motivo: " + v.reason());
    }

    @Test
    @DisplayName("Base64 corrupto en cualquier campo binario ⇒ rechazado sin tirar excepción")
    void base64Corrupto() throws Exception {
        final Fixture f = fixture();
        final byte[] c = challenge();
        final String goodSig = b64(sign(f, c));

        final AzoreaAccessGate.Verdict v = AzoreaAccessGate.verify(
                new AzoreaAccessGate.Proof(AzoreaAccessGate.VERSION, f.id(), "Die_Beria",
                        b64(f.signing.publicKey()), "!!!no-es-base64!!!", b64(f.hw()), goodSig),
                c);

        assertFalse(v.ok());
        assertTrue(v.reason().contains("Base64"), "motivo: " + v.reason());
    }

    @Test
    @DisplayName("longitud equivocida en hwCommit o firma ⇒ rechazado con la medida esperada")
    void longitudesIncorrectas() throws Exception {
        final Fixture f = fixture();
        final byte[] c = challenge();

        // hwCommit de 16 B en vez de 32.
        final byte[] shortHw = new byte[16];
        RNG.nextBytes(shortHw);
        final AzoreaAccessGate.Verdict badHw = AzoreaAccessGate.verify(
                new AzoreaAccessGate.Proof(AzoreaAccessGate.VERSION, f.id(), "Die_Beria",
                        b64(f.signing.publicKey()), b64(f.x25519()), b64(shortHw),
                        b64(sign(f, c))),
                c);
        assertFalse(badHw.ok());
        assertTrue(badHw.reason().contains("32"), "motivo: " + badHw.reason());

        // Firma de 10 B en vez de 64.
        final AzoreaAccessGate.Verdict badSig = AzoreaAccessGate.verify(
                new AzoreaAccessGate.Proof(AzoreaAccessGate.VERSION, f.id(), "Die_Beria",
                        b64(f.signing.publicKey()), b64(f.x25519()), b64(f.hw()),
                        b64(new byte[10])),
                c);
        assertFalse(badSig.ok());
        assertTrue(badSig.reason().contains("64"), "motivo: " + badSig.reason());
    }

    @Test
    @DisplayName("azoreaId con forma inválida ⇒ rechazado ANTES de derivar (motivo legible)")
    void idConFormatoInvalido() throws Exception {
        final Fixture f = fixture();
        final byte[] c = challenge();

        final AzoreaAccessGate.Verdict v = AzoreaAccessGate.verify(
                new AzoreaAccessGate.Proof(AzoreaAccessGate.VERSION, "nada-que-ver", "Die_Beria",
                        b64(f.signing.publicKey()), b64(f.x25519()), b64(f.hw()), b64(sign(f, c))),
                c);

        assertFalse(v.ok());
        assertTrue(v.reason().contains("AZ-"), "motivo: " + v.reason());
    }

    @Test
    @DisplayName("displayName que MC no aceptaría ⇒ rechazado (no dejamos pasar basura al registro)")
    void displayNameInvalido() throws Exception {
        final Fixture f = fixture();
        final byte[] c = challenge();

        // Más largo que el límite de MC.
        final AzoreaAccessGate.Verdict tooLong = AzoreaAccessGate.verify(
                new AzoreaAccessGate.Proof(AzoreaAccessGate.VERSION, f.id(),
                        "EsteNombreEsDemasiadoLargoParaMinecraft",
                        b64(f.signing.publicKey()), b64(f.x25519()), b64(f.hw()), b64(sign(f, c))),
                c);
        assertFalse(tooLong.ok(), "debe rechazarse por longitud");

        // Caracteres que MC no permite en un nombre de jugador.
        final AzoreaAccessGate.Verdict badChars = AzoreaAccessGate.verify(
                new AzoreaAccessGate.Proof(AzoreaAccessGate.VERSION, f.id(), "mal nombre!",
                        b64(f.signing.publicKey()), b64(f.x25519()), b64(f.hw()), b64(sign(f, c))),
                c);
        assertFalse(badChars.ok(), "debe rechazarse por caracteres");
    }

    @Test
    @DisplayName("payload nulo y challenge nulo ⇒ motivo, nunca NPE")
    void entradasNulas() {
        final AzoreaAccessGate.Verdict nullPayload = AzoreaAccessGate.verify(null, challenge());
        assertFalse(nullPayload.ok());
        assertNotNull(nullPayload.reason());

        final AzoreaAccessGate.Verdict nullChallenge =
                AzoreaAccessGate.verify(new AzoreaAccessGate.Proof("1", "AZ-AAAAAA-AAAAAA-AAAAAA-AAAAAA",
                        "Die_Beria", "YQ==", "YQ==", "YQ==", "YQ=="), null);
        assertFalse(nullChallenge.ok());
        assertNotNull(nullChallenge.reason());
    }

    @Test
    @DisplayName("challenge de longitud equivoca ⇒ rechazado (no se verifica contra cualquier cosa)")
    void challengeDeLongitudErronea() throws Exception {
        final Fixture f = fixture();
        final AzoreaAccessGate.Verdict v = AzoreaAccessGate.verify(
                proof(f, challenge()), new byte[8]);
        assertFalse(v.ok());
        assertTrue(v.reason().contains("32"), "motivo: " + v.reason());
    }

    @Test
    @DisplayName("basura arbitraria en todos los campos ⇒ siempre motivo, nunca excepción")
    void fuzzLigero() {
        final String[] junk = {"", " ", "!", "AAAA", "@@@", "0", "\u0000",
                "eyJhIjoxfQ==", "AZ-", "AZ-AAAAAA-AAAAAA-AAAAAA-AAAAAA", null};
        for (final String a : junk) {
            for (final String b : junk) {
                final AzoreaAccessGate.Proof p = new AzoreaAccessGate.Proof(
                        a, b, a, b, a, b, a);
                final AzoreaAccessGate.Verdict v = AzoreaAccessGate.verify(p, challenge());
                assertFalse(v.ok(), "basura jamás debe aceptarse: a=" + a + " b=" + b);
                assertNotNull(v.reason(), "siempre debe dar motivo");
            }
        }
    }
}
