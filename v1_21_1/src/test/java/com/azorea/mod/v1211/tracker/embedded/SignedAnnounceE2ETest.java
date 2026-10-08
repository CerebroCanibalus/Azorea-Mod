// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.tracker.embedded;

import com.azorea.mod.tracker.AzoreaTrackerClient;
import com.azorea.mod.tracker.TrackerProtocol;
import com.azorea.mod.v1211.identity.AzoreaId;
import com.azorea.mod.v1211.identity.AzoreaIdentity;
import com.azorea.mod.v1211.identity.HwFingerprint;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E: verificación de IDENTIDAD contra el tracker embebido REAL (§ F9 / DA-8).
 *
 * <p>Diferencia con {@code AzoreaIdSigningTest}: ahí se prueba la crypto aislada;
 * aquí se pasa el anuncio por HTTP de verdad por el {@code AnnounceHandler}, que es
 * donde vivía el bug (aceptaba cualquier announce sin comprobar quién era).
 *
 * <p>Estos tests son la prueba de que el azorea_id NO es cosmético: un anuncio con
 * la ID de otra gente no entra.
 */
final class SignedAnnounceE2ETest {

    private static AzoreaEmbeddedTracker tracker;
    private static AzoreaTrackerClient client;

    /** Par de claves + ID del "host legítimo". */
    private static final class Keys {
        final byte[] edPub;
        final byte[] edPriv;
        final byte[] xPub;
        final byte[] hwCommit;
        final String azoreaId;

        Keys() throws GeneralSecurityException {
            final AzoreaIdentity.Keys ed = AzoreaIdentity.generateSigningKeyPair();
            final AzoreaIdentity.Keys x = AzoreaIdentity.generateKeyPair();
            this.edPub = ed.publicKey();
            this.edPriv = ed.privateKey();
            this.xPub = x.publicKey();
            this.hwCommit = hw().hwCommit();
            this.azoreaId = AzoreaId.derive(edPub, xPub, hwCommit);
        }
    }

    private static HwFingerprint hw() {
        return new HwFingerprint("AA:BB:CC:DD:EE:FF", "4C1B-3F2A-TEST", "10.0", "DESKTOP-TEST");
    }

    @BeforeAll
    static void startTracker() throws IOException {
        // Puerto efímero: actualPort() lee httpServer.getAddress().getPort().
        tracker = new AzoreaEmbeddedTracker(0);
        tracker.start();
        client = new AzoreaTrackerClient(
                List.of(tracker.localUrl()),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2));
    }

    @AfterAll
    static void stopTracker() {
        if (tracker != null) {
            tracker.stop();
        }
    }

    private static TrackerProtocol.Announcement unsigned(final String azoreaId, final String xPubB64) {
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

    @Test
    @DisplayName("Anuncio firmado por su dueño ⇒ el tracker lo ACEPTA (HTTP 201)")
    void signedAnnounceAccepted() throws GeneralSecurityException {
        final Keys host = new Keys();
        final String xB64 = Base64.getEncoder().encodeToString(host.xPub);

        final TrackerProtocol.Announcement signed = TrackerProtocol.signAnnounce(
                unsigned(host.azoreaId, xB64), host.edPub, host.edPriv, host.hwCommit);

        final Optional<TrackerProtocol.AnnounceResponse> response = client.announce(signed);
        assertTrue(response.isPresent(),
                "un anuncio firmado legítimo debe ser aceptado por el tracker");
    }

    @Test
    @DisplayName("Anuncio SIN firma ⇒ el tracker lo RECHAZA (HTTP 403)")
    void unsignedAnnounceRejectedByTracker() throws GeneralSecurityException {
        final Keys host = new Keys();
        final String xB64 = Base64.getEncoder().encodeToString(host.xPub);

        final Optional<TrackerProtocol.AnnounceResponse> response =
                client.announce(unsigned(host.azoreaId, xB64));

        assertFalse(response.isPresent(),
                "un anuncio sin firma no debe entrar — era el bug original");
    }

    @Test
    @DisplayName("Atacante que reclama la ID de otro y firma con SU clave ⇒ RECHAZADO")
    void spoofedAnnounceRejectedByTracker() throws GeneralSecurityException {
        final Keys victim = new Keys();
        final Keys attacker = new Keys();

        // El atacante pone la ID de la víctima y la X25519 de la víctima (ambas son
        // públicas), pero firma con su propia Ed25519 → el ID no re-deriva.
        final TrackerProtocol.Announcement spoofed = TrackerProtocol.signAnnounce(
                unsigned(victim.azoreaId, Base64.getEncoder().encodeToString(victim.xPub)),
                attacker.edPub, attacker.edPriv, victim.hwCommit);

        final Optional<TrackerProtocol.AnnounceResponse> response = client.announce(spoofed);
        assertFalse(response.isPresent(),
                "suplantación de identidad debe ser rechazada por el tracker");
    }

    @Test
    @DisplayName("Anuncio con basura en los campos de firma ⇒ RECHAZADO (no acepta a ciegas)")
    void garbageSignatureRejectedByTracker() throws GeneralSecurityException {
        final Keys host = new Keys();
        final String xB64 = Base64.getEncoder().encodeToString(host.xPub);

        final TrackerProtocol.Announcement forged = unsigned(host.azoreaId, xB64).withSignature(
                Base64.getEncoder().encodeToString(host.edPub),
                Base64.getEncoder().encodeToString(host.hwCommit),
                Base64.getEncoder().encodeToString(new byte[64])); // firma inválida

        final Optional<TrackerProtocol.AnnounceResponse> response = client.announce(forged);
        assertFalse(response.isPresent(), "una firma inválida no debe pasar");
    }

    @Test
    @DisplayName("Dos hosts distintos ⇒ IDs distintas (el ID identifica de verdad)")
    void distinctHostsHaveDistinctIds() throws GeneralSecurityException {
        final Keys a = new Keys();
        final Keys b = new Keys();
        assertFalse(a.azoreaId.equals(b.azoreaId),
                "dos identidades distintas no pueden colisionar en este test");
        assertTrue(TrackerProtocol.isValidAzoreaId(a.azoreaId));
        assertTrue(TrackerProtocol.isValidAzoreaId(b.azoreaId));
    }
}
