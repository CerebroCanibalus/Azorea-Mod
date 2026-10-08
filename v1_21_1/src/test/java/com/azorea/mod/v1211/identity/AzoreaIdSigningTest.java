// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.identity;

import com.azorea.mod.tracker.TrackerProtocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de la identidad auto-certificante + firma de anuncios (§ F9 / DA-8).
 *
 * <p>Estos tests son los que demuestran que el azorea_id NO es cosmético:
 * cubren el spoofing de identidad (el bug original) y la integridad del anuncio.
 */
final class AzoreaIdSigningTest {

    // ===== Fixtures =====

    /** Fingerprint de prueba determinista. */
    private static HwFingerprint hw() {
        return new HwFingerprint("AA:BB:CC:DD:EE:FF", "4C1B-3F2A-TEST", "10.0", "DESKTOP-TEST");
    }

    /** Genera un par Ed25519 + X25519 + commit, como lo haría la identidad real. */
    private static final class IdFixture {
        final byte[] edPub;
        final byte[] edPriv;
        final byte[] xPub;
        final byte[] xPriv;
        final byte[] hwCommit;
        final String azoreaId;

        IdFixture() throws GeneralSecurityException {
            final AzoreaIdentity.Keys ed = AzoreaIdentity.generateSigningKeyPair();
            final AzoreaIdentity.Keys x = AzoreaIdentity.generateKeyPair();
            this.edPub = ed.publicKey();
            this.edPriv = ed.privateKey();
            this.xPub = x.publicKey();
            this.xPriv = x.privateKey();
            this.hwCommit = hw().hwCommit();
            this.azoreaId = AzoreaId.derive(edPub, xPub, hwCommit);
        }
    }

    /** Anuncio de prueba válido (pasa {@link TrackerProtocol#validate}). */
    private static TrackerProtocol.Announcement unsignedAnnounce(final String azoreaId,
                                                                 final String xPubB64) {
        return new TrackerProtocol.Announcement(
                TrackerProtocol.AZOREA_PROTOCOL_VERSION,
                TrackerProtocol.AZOREA_VERSION,
                TrackerProtocol.newGameId(),
                TrackerProtocol.newHostToken(),
                new TrackerProtocol.Identity(azoreaId, "Dev", xPubB64),
                "1.21.1",
                "21.1.250",
                List.of(new TrackerProtocol.ModEntry("azorea", TrackerProtocol.AZOREA_VERSION, true)),
                8, 1,
                "My World",
                TrackerProtocol.newInviteCode(),
                System.currentTimeMillis() / 1000L,
                300);
    }

    // ===== 1. Derivación del ID =====

    @Test
    @DisplayName("derive() es determinista: mismas claves+hw ⇒ mismo ID")
    void deriveIsDeterministic() throws GeneralSecurityException {
        final byte[] commit = hw().hwCommit();
        final AzoreaIdentity.Keys ed = AzoreaIdentity.generateSigningKeyPair();
        final AzoreaIdentity.Keys x = AzoreaIdentity.generateKeyPair();

        final String a = AzoreaId.derive(ed.publicKey(), x.publicKey(), commit);
        final String b = AzoreaId.derive(ed.publicKey(), x.publicKey(), commit);

        assertEquals(a, b, "la derivación debe ser determinista");
    }

    @Test
    @DisplayName("derive() produce el formato canónico AZ-XXXXXX-XXXXXX-XXXXXX-XXXXXX")
    void deriveProducesCanonicalFormat() throws GeneralSecurityException {
        final IdFixture f = new IdFixture();
        assertTrue(TrackerProtocol.isValidAzoreaId(f.azoreaId),
                "ID generado debe pasar la validación del protocolo: " + f.azoreaId);
        assertTrue(f.azoreaId.startsWith("AZ-"), "prefijo AZ- esperado");
        assertEquals(30, f.azoreaId.length(),
                "30 chars = 'AZ-'(3) + 24 base32 + 3 dashes. ID=" + f.azoreaId);
    }

    @Test
    @DisplayName("derive() cambia si cambia la clave de FIRMA (Ed25519)")
    void deriveDependsOnSigningKey() throws GeneralSecurityException {
        final byte[] commit = hw().hwCommit();
        final AzoreaIdentity.Keys x = AzoreaIdentity.generateKeyPair();
        final AzoreaIdentity.Keys edA = AzoreaIdentity.generateSigningKeyPair();
        final AzoreaIdentity.Keys edB = AzoreaIdentity.generateSigningKeyPair();

        assertNotEquals(
                AzoreaId.derive(edA.publicKey(), x.publicKey(), commit),
                AzoreaId.derive(edB.publicKey(), x.publicKey(), commit),
                "distinta clave de firma ⇒ distinto ID (clave atada a la identidad)");
    }

    @Test
    @DisplayName("derive() cambia si cambia el hardware (semántica modelo C conservada)")
    void deriveDependsOnHardware() throws GeneralSecurityException {
        final AzoreaIdentity.Keys ed = AzoreaIdentity.generateSigningKeyPair();
        final AzoreaIdentity.Keys x = AzoreaIdentity.generateKeyPair();
        final byte[] commitA = hw().hwCommit();
        final byte[] commitB = new HwFingerprint("11:22:33:44:55:66", "OTHER", "10.0", "PC2").hwCommit();

        assertNotEquals(
                AzoreaId.derive(ed.publicKey(), x.publicKey(), commitA),
                AzoreaId.derive(ed.publicKey(), x.publicKey(), commitB),
                "distinto HW ⇒ distinto ID");
    }

    @Test
    @DisplayName("hwCommit() NO filtra los componentes crudos (es un hash)")
    void hwCommitDoesNotLeakComponents() {
        final byte[] commit = hw().hwCommit();
        final String canonical = hw().canonical();
        final String asText = new String(commit, StandardCharsets.ISO_8859_1);

        assertFalse(asText.contains("AA:BB"), "la MAC no debe aparecer en el commit");
        assertFalse(asText.contains("DESKTOP-TEST"), "el hostname no debe aparecer en el commit");
        assertEquals(32, commit.length, "SHA-256 ⇒ 32 bytes");
        assertFalse(canonical.isEmpty(), "canonical no vacío");
    }

    // ===== 2. Auto-certificación (el bug original) =====

    @Test
    @DisplayName("verify() acepta el ID real con sus propias claves")
    void verifyAcceptsGenuineId() throws GeneralSecurityException {
        final IdFixture f = new IdFixture();
        assertTrue(AzoreaId.verify(f.azoreaId, f.edPub, f.xPub, f.hwCommit));
    }

    @Test
    @DisplayName("verify() RECHAZA un ID reclamado con claves ajenas (spoofing)")
    void verifyRejectsForeignKeys() throws GeneralSecurityException {
        final IdFixture victim = new IdFixture();
        final IdFixture attacker = new IdFixture();

        // El atacante usa la ID de la víctima pero SUS claves.
        assertFalse(AzoreaId.verify(victim.azoreaId, attacker.edPub, attacker.xPub, victim.hwCommit),
                "reclamar una ID ajena con claves propias debe fallar");

        // ...o con claves de la víctima pero commit de hardware distinto.
        final byte[] attackerHw = new HwFingerprint(
                "11:22:33:44:55:66", "OTHER", "10.0", "PC2").hwCommit();
        assertFalse(AzoreaId.verify(victim.azoreaId, victim.edPub, victim.xPub, attackerHw),
                "la ID no debe validarse con el HW de otra máquina");
    }

    @Test
    @DisplayName("verify() devuelve false (no lanza) con argumentos corruptos")
    void verifyIsSafeOnGarbage() {
        assertFalse(AzoreaId.verify(null, new byte[]{1}, new byte[]{2}, new byte[]{3}));
        assertFalse(AzoreaId.verify("AZ-AAAAAA-BBBBBB-CCCCCC-DDDDDD", null, null, null));
        assertFalse(AzoreaId.verify("no-es-un-id", new byte[]{1}, new byte[]{2}, new byte[]{3}));
    }

    // ===== 3. Firma de anuncios =====

    @Test
    @DisplayName("signAnnounce → verifyAnnounce: round-trip válido")
    void signThenVerifyRoundTrip() throws GeneralSecurityException {
        final IdFixture f = new IdFixture();
        final String xB64 = Base64.getEncoder().encodeToString(f.xPub);
        final TrackerProtocol.Announcement unsigned = unsignedAnnounce(f.azoreaId, xB64);

        // Debe pasar la validación de esquema.
        TrackerProtocol.validate(unsigned);

        final TrackerProtocol.Announcement signed = TrackerProtocol.signAnnounce(
                unsigned, f.edPub, f.edPriv, f.hwCommit);

        // Sin esto el tracker rechazaría.
        TrackerProtocol.validate(signed);
        TrackerProtocol.verifyAnnounce(signed); // no lanza ⇒ OK
    }

    @Test
    @DisplayName("Anuncio SIN firma ⇒ rechazado (F9 obligatorio)")
    void unsignedAnnounceRejected() throws GeneralSecurityException {
        final IdFixture f = new IdFixture();
        final TrackerProtocol.Announcement unsigned = unsignedAnnounce(
                f.azoreaId, Base64.getEncoder().encodeToString(f.xPub));

        final IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> TrackerProtocol.verifyAnnounce(unsigned));
        assertTrue(e.getMessage().contains("signingKey"),
                "motivo esperado (campos F9 ausentes): " + e.getMessage());
    }

    @Test
    @DisplayName("Atacante firma con su propia clave pero reclama la ID de la víctima ⇒ rechazado")
    void spoofedIdentityRejected() throws GeneralSecurityException {
        final IdFixture victim = new IdFixture();
        final IdFixture attacker = new IdFixture();

        // El atacante construye un anuncio con la ID de la víctima y lo firma con SU clave.
        final TrackerProtocol.Announcement spoofed = unsignedAnnounce(
                victim.azoreaId, Base64.getEncoder().encodeToString(victim.xPub));
        final TrackerProtocol.Announcement signedByAttacker = TrackerProtocol.signAnnounce(
                spoofed, attacker.edPub, attacker.edPriv, victim.hwCommit);

        final IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> TrackerProtocol.verifyAnnounce(signedByAttacker));
        assertTrue(e.getMessage().contains("suplantación"),
                "debe detectar suplantación, no otra cosa: " + e.getMessage());
    }

    @Test
    @DisplayName("Alterar un campo tras firmar ⇒ la firma deja de validar")
    void tamperedAnnounceRejected() throws GeneralSecurityException {
        final IdFixture f = new IdFixture();
        final TrackerProtocol.Announcement signed = TrackerProtocol.signAnnounce(
                unsignedAnnounce(f.azoreaId, Base64.getEncoder().encodeToString(f.xPub)),
                f.edPub, f.edPriv, f.hwCommit);

        // Verificación inicial OK.
        TrackerProtocol.verifyAnnounce(signed);

        // Ahora un atacante legítimo (misma ID/claves) cambia el worldName.
        final TrackerProtocol.Announcement tampered = new TrackerProtocol.Announcement(
                signed.azoreaProtocol(), signed.azoreaVersion(), signed.gameId(),
                signed.hostToken(), signed.hostIdentity(), signed.mcVersion(),
                signed.neoForgeVersion(), signed.mods(), signed.maxPlayers(),
                signed.currentPlayers(),
                "MUNDO MALICIOSO",           // ← alterado
                signed.inviteCode(), signed.timestamp(), signed.ttlSeconds(),
                signed.signingKey(), signed.hwCommit(), signed.signature());

        final IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> TrackerProtocol.verifyAnnounce(tampered));
        assertTrue(e.getMessage().contains("firma Ed25519 inválida"),
                "motivo esperado: " + e.getMessage());
    }

    @Test
    @DisplayName("Firma determinista (RFC 8032): mismo anuncio ⇒ misma firma")
    void signatureIsDeterministic() throws GeneralSecurityException {
        final IdFixture f = new IdFixture();
        final String xB64 = Base64.getEncoder().encodeToString(f.xPub);
        final TrackerProtocol.Announcement a = unsignedAnnounce(f.azoreaId, xB64);

        final TrackerProtocol.Announcement s1 = TrackerProtocol.signAnnounce(
                a, f.edPub, f.edPriv, f.hwCommit);
        final TrackerProtocol.Announcement s2 = TrackerProtocol.signAnnounce(
                a, f.edPub, f.edPriv, f.hwCommit);

        assertEquals(s1.signature(), s2.signature(),
                "Ed25519 es determinista — el refresh no debe cambiar la firma");
        TrackerProtocol.verifyAnnounce(s1);
        TrackerProtocol.verifyAnnounce(s2);
    }

    @Test
    @DisplayName("canonicalForSigning NO incluye la firma (evita circularidad)")
    void canonicalExcludesSignature() throws GeneralSecurityException {
        final IdFixture f = new IdFixture();
        final TrackerProtocol.Announcement signed = TrackerProtocol.signAnnounce(
                unsignedAnnounce(f.azoreaId, Base64.getEncoder().encodeToString(f.xPub)),
                f.edPub, f.edPriv, f.hwCommit);

        final String canonical = TrackerProtocol.canonicalForSigning(signed);
        assertFalse(canonical.contains(signed.signature()),
                "la firma no puede formar parte de lo que se firma");
        assertTrue(canonical.contains("game_id=" + signed.gameId()),
                "el gameId sí debe estar cubierto");
        assertTrue(canonical.contains("world=" + signed.worldName()),
                "el worldName sí debe estar cubierto");
        assertTrue(canonical.contains("invite=" + signed.inviteCode()),
                "el inviteCode sí debe estar cubierto");
    }

    @Test
    @DisplayName("La X25519 publicada también está cubierta por la firma")
    void canonicalCoversX25519Key() throws GeneralSecurityException {
        final IdFixture f = new IdFixture();
        final String xB64 = Base64.getEncoder().encodeToString(f.xPub);
        final TrackerProtocol.Announcement signed = TrackerProtocol.signAnnounce(
                unsignedAnnounce(f.azoreaId, xB64), f.edPub, f.edPriv, f.hwCommit);

        assertTrue(TrackerProtocol.canonicalForSigning(signed).contains("x25519_key=" + xB64),
                "si alguien cambiara la clave de ECDH, la firma se rompería");
    }
}
