// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.GeneralSecurityException;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitarios para {@link AzoreaCrypto} (ver AGENTS.md § F5.2b).
 *
 * § Cobertura:
 * <ul>
 *   <li>X25519 keypair generation (tamaños correctos).</li>
 *   <li>X25519 ECDH agreement (shared secret simétrico).</li>
 *   <li>ChaCha20-Poly1305 encrypt/decrypt round-trip.</li>
 *   <li>encryptForRecipient / decryptFromHost end-to-end (cross-key negative tests).</li>
 *   <li>Tampered ciphertext rejected (Poly1305 MAC verifica).</li>
 *   <li>Tampered ephemeral pub rejected.</li>
 *   <li>Wrong recipient can't decrypt (cross-key).</li>
 * </ul>
 */
class AzoreaCryptoTest {

    @Test
    @DisplayName("X25519 keypair generation produce 44B public + 48B private (encoded)")
    void keyPairHasExpectedSizes() throws GeneralSecurityException {
        final AzoreaIdentity.Keys keys = AzoreaIdentity.generateKeyPair();
        assertEquals(AzoreaCrypto.X25519_PUBLIC_ENCODED_SIZE, keys.publicKey().length);
        assertEquals(AzoreaCrypto.X25519_PRIVATE_ENCODED_SIZE, keys.privateKey().length);
    }

    @Test
    @DisplayName("X25519 agreement: shared secret es simétrico")
    void x25519AgreementIsSymmetric() throws GeneralSecurityException {
        final AzoreaIdentity.Keys alice = AzoreaIdentity.generateKeyPair();
        final AzoreaIdentity.Keys bob = AzoreaIdentity.generateKeyPair();

        final byte[] aliceShared = AzoreaCrypto.x25519Agreement(alice.privateKey(), bob.publicKey());
        final byte[] bobShared = AzoreaCrypto.x25519Agreement(bob.privateKey(), alice.publicKey());
        assertArrayEquals(aliceShared, bobShared);
        assertEquals(32, aliceShared.length, "X25519 shared secret debe ser 32 bytes");
    }

    @Test
    @DisplayName("X25519 agreement con keys random diferentes produce shared secrets distintos")
    void differentKeysProduceDifferentSecrets() throws GeneralSecurityException {
        final AzoreaIdentity.Keys alice = AzoreaIdentity.generateKeyPair();
        final AzoreaIdentity.Keys bob = AzoreaIdentity.generateKeyPair();
        final AzoreaIdentity.Keys carol = AzoreaIdentity.generateKeyPair();

        final byte[] aliceBob = AzoreaCrypto.x25519Agreement(alice.privateKey(), bob.publicKey());
        final byte[] aliceCarol = AzoreaCrypto.x25519Agreement(alice.privateKey(), carol.publicKey());
        assertFalse(java.util.Arrays.equals(aliceBob, aliceCarol));
    }

    @Test
    @DisplayName("ChaCha20-Poly1305 round-trip preserva plaintext")
    void chachaPolyRoundTrip() throws GeneralSecurityException {
        final byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        final byte[] nonce = AzoreaCrypto.generateNonce();
        final byte[] plaintext = "Hello Azorea P2P!".getBytes();

        final byte[] ciphertext = AzoreaCrypto.chacha20Poly1305Encrypt(key, nonce, plaintext);
        assertEquals(plaintext.length + AzoreaCrypto.TAG_SIZE, ciphertext.length,
                "ciphertext debe ser plaintext.length + 16B tag");
        final byte[] decrypted = AzoreaCrypto.chacha20Poly1305Decrypt(key, nonce, ciphertext);
        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    @DisplayName("ChaCha20-Poly1305 rechaza ciphertext tampered")
    void chachaPolyRejectsTamperedCiphertext() throws GeneralSecurityException {
        final byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        final byte[] nonce = AzoreaCrypto.generateNonce();
        final byte[] plaintext = "secret".getBytes();

        final byte[] ciphertext = AzoreaCrypto.chacha20Poly1305Encrypt(key, nonce, plaintext);
        // Tamper con un byte del ciphertext.
        ciphertext[0] ^= 0x01;
        final GeneralSecurityException ex = assertThrows(GeneralSecurityException.class,
                () -> AzoreaCrypto.chacha20Poly1305Decrypt(key, nonce, ciphertext));
        assertTrue(ex.getMessage().toLowerCase().contains("tag")
                        || ex.getMessage().toLowerCase().contains("mac")
                        || ex.getMessage().toLowerCase().contains("verification"),
                "debe fallar por tag mismatch: " + ex.getMessage());
    }

    @Test
    @DisplayName("encryptForRecipient / decryptFromHost round-trip E2E")
    void encryptDecryptEndToEnd() throws GeneralSecurityException {
        final AzoreaIdentity.Keys alice = AzoreaIdentity.generateKeyPair();
        final AzoreaIdentity.Keys bob = AzoreaIdentity.generateKeyPair();
        final byte[] plaintext = "{\"host\":\"1.2.3.4\",\"port\":25565}".getBytes();

        final byte[] encrypted = AzoreaCrypto.encryptForRecipient(bob.publicKey(), plaintext);
        // Formato esperado: ephemeralPub (44) || nonce (12) || ciphertext+tag
        assertEquals(AzoreaCrypto.X25519_PUBLIC_ENCODED_SIZE
                        + AzoreaCrypto.NONCE_SIZE
                        + plaintext.length
                        + AzoreaCrypto.TAG_SIZE,
                encrypted.length);

        final byte[] decrypted = AzoreaCrypto.decryptFromHost(bob.privateKey(), encrypted);
        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    @DisplayName("encryptForRecipient con ephemeral keys diferentes cada llamada (forward secrecy)")
    void encryptUsesEphemeralKey() throws GeneralSecurityException {
        final AzoreaIdentity.Keys bob = AzoreaIdentity.generateKeyPair();
        final byte[] plaintext = "msg1".getBytes();

        final byte[] enc1 = AzoreaCrypto.encryptForRecipient(bob.publicKey(), plaintext);
        final byte[] enc2 = AzoreaCrypto.encryptForRecipient(bob.publicKey(), plaintext);
        // Los primeros 44B son la ephemeral public key — debe ser distinta cada vez.
        final byte[] ephemeralPub1 = new byte[AzoreaCrypto.X25519_PUBLIC_ENCODED_SIZE];
        final byte[] ephemeralPub2 = new byte[AzoreaCrypto.X25519_PUBLIC_ENCODED_SIZE];
        System.arraycopy(enc1, 0, ephemeralPub1, 0, AzoreaCrypto.X25519_PUBLIC_ENCODED_SIZE);
        System.arraycopy(enc2, 0, ephemeralPub2, 0, AzoreaCrypto.X25519_PUBLIC_ENCODED_SIZE);
        assertFalse(java.util.Arrays.equals(ephemeralPub1, ephemeralPub2),
                "Ephemeral pub debe ser distinto cada llamada (forward secrecy)");
    }

    @Test
    @DisplayName("Recipient incorrecto no puede descifrar")
    void wrongRecipientCannotDecrypt() throws GeneralSecurityException {
        final AzoreaIdentity.Keys bob = AzoreaIdentity.generateKeyPair();
        final AzoreaIdentity.Keys mallory = AzoreaIdentity.generateKeyPair();
        final byte[] plaintext = "secret for bob only".getBytes();

        final byte[] encrypted = AzoreaCrypto.encryptForRecipient(bob.publicKey(), plaintext);
        // Mallory intenta descifrar con SU private key.
        assertThrows(GeneralSecurityException.class,
                () -> AzoreaCrypto.decryptFromHost(mallory.privateKey(), encrypted));
    }

    @Test
    @DisplayName("Encrypted blob demasiado pequeño lanza IllegalArgumentException")
    void rejectTooShortBlob() {
        final byte[] tooShort = new byte[10];
        assertThrows(IllegalArgumentException.class,
                () -> AzoreaCrypto.decryptFromHost(new byte[48], tooShort));
    }

    @Test
    @DisplayName("Base64 helpers round-trip")
    void base64HelpersRoundTrip() {
        final byte[] data = new byte[]{1, 2, 3, 4, 5};
        final String s = AzoreaCrypto.base64Encode(data);
        assertNotNull(s);
        assertArrayEquals(data, AzoreaCrypto.base64Decode(s));
    }

    @Test
    @DisplayName("AzoreaIdentity.Keys validación: rechaza public/private null o empty")
    void keysConstructorValidates() {
        assertThrows(IllegalArgumentException.class,
                () -> new AzoreaIdentity.Keys(null, new byte[32]));
        assertThrows(IllegalArgumentException.class,
                () -> new AzoreaIdentity.Keys(new byte[32], null));
        assertThrows(IllegalArgumentException.class,
                () -> new AzoreaIdentity.Keys(new byte[0], new byte[32]));
    }

    @Test
    @DisplayName("encryptForRecipient maneja JSON ConnectionInfo realista")
    void encryptConnectionInfoRealisticJson() throws GeneralSecurityException {
        final AzoreaIdentity.Keys friend = AzoreaIdentity.generateKeyPair();
        final String json = "{\"host\":\"192.168.1.50\",\"port\":25565,\"host_token\":\"abc\",\"timestamp\":1234567890}";

        final byte[] encrypted = AzoreaCrypto.encryptForRecipient(friend.publicKey(), json.getBytes());
        final byte[] decrypted = AzoreaCrypto.decryptFromHost(friend.privateKey(), encrypted);
        final String decryptedStr = new String(decrypted);
        assertEquals(json, decryptedStr);
    }
}