// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.identity;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * Utilidades criptográficas Azorea (ver AGENTS.md § F5.2b).
 *
 * <p>Stack crypto:
 * <ul>
 *   <li><b>X25519</b> ECDH (JDK 15+ builtin) — para acordar clave compartida efímera.</li>
 *   <li><b>ChaCha20-Poly1305</b> AEAD (JDK 11+ builtin) — para cifrar connection info.</li>
 * </ul>
 *
 * <p><b>§ Patrón "X25519 ephemeral + ChaCha20-Poly1305":</b>
 * <pre>{@code
 * HOST (envía invite cifrado al friend):
 *   1. ephemeralKeypair = X25519.generate()
 *   2. shared = X25519.ECDH(ephemeralKeypair.private, recipient.public)
 *   3. ciphertext = ChaCha20Poly1305.encrypt(shared, nonce, plaintext)
 *   4. enviar: ephemeral.public || nonce || ciphertext
 *
 * FRIEND (recibe invite y descifra):
 *   1. shared = X25519.ECDH(my.private, ephemeral.public)
 *   2. plaintext = ChaCha20Poly1305.decrypt(shared, nonce, ciphertext)
 * }</pre>
 *
 * <p>Esto es similar a libsodium {@code crypto_box_easy} pero implementado sobre
 * primitivas JDK puras (sin dependencias externas).
 *
 * <p><b>§ Tamaños:</b>
 * <ul>
 *   <li>X25519 public key: 32 bytes raw → 44 bytes X.509-encoded.</li>
 *   <li>X25519 private key: 32 bytes raw → 48 bytes PKCS8-encoded.</li>
 *   <li>Shared secret: 32 bytes (X25519 output).</li>
 *   <li>ChaCha20 nonce: 12 bytes.</li>
 *   <li>ChaCha20-Poly1305 tag: 16 bytes (suffix del ciphertext).</li>
 * </ul>
 *
 * <p><b>§ Garantías:</b>
 * <ul>
 *   <li>Forward secrecy: cada invite usa ephemeral keypair nuevo.</li>
 *   <li>Authenticity: Poly1305 MAC verifica que el blob no fue modificado.</li>
 *   <li>Confidencialidad: solo el recipient (con su private key) puede descifrar.</li>
 *   <li>Tracker NO puede descifrar (no tiene private keys).</li>
 * </ul>
 */
public final class AzoreaCrypto {

    /** Algoritmo X25519 keypair. */
    public static final String X25519_ALG = "X25519";

    /** ChaCha20-Poly1305 AEAD (JDK 11+). */
    public static final String CHACHA20_POLY1305 = "ChaCha20-Poly1305";

    /** Nonce size para ChaCha20-Poly1305 (96 bits, RFC 8439). */
    public static final int NONCE_SIZE = 12;

    /** Tag size de Poly1305 (128 bits). */
    public static final int TAG_SIZE = 16;

    /** Tamaño X.509-encoded de X25519 public key. */
    public static final int X25519_PUBLIC_ENCODED_SIZE = 44;

    /** Tamaño PKCS8-encoded de X25519 private key. */
    public static final int X25519_PRIVATE_ENCODED_SIZE = 48;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private AzoreaCrypto() {
    }

    /** Par de claves X25519 (encoded X.509 / PKCS8). */
    public record Keys(byte[] publicKeyEncoded, byte[] privateKeyEncoded) {
        public Keys {
            if (publicKeyEncoded == null || publicKeyEncoded.length != X25519_PUBLIC_ENCODED_SIZE) {
                throw new IllegalArgumentException("publicKeyEncoded inválido (esperado "
                        + X25519_PUBLIC_ENCODED_SIZE + " bytes, got " +
                        (publicKeyEncoded == null ? "null" : publicKeyEncoded.length) + ")");
            }
            if (privateKeyEncoded == null || privateKeyEncoded.length != X25519_PRIVATE_ENCODED_SIZE) {
                throw new IllegalArgumentException("privateKeyEncoded inválido (esperado "
                        + X25519_PRIVATE_ENCODED_SIZE + " bytes, got " +
                        (privateKeyEncoded == null ? "null" : privateKeyEncoded.length) + ")");
            }
        }
    }

    // ===== Keypair generation =====

    /**
     * Genera un par de claves X25519 nuevo.
     */
    public static Keys generateX25519KeyPair() throws GeneralSecurityException {
        final KeyPairGenerator g = KeyPairGenerator.getInstance(X25519_ALG);
        final KeyPair kp = g.generateKeyPair();
        return new Keys(kp.getPublic().getEncoded(), kp.getPrivate().getEncoded());
    }

    // ===== ECDH (X25519) =====

    /**
     * Computa shared secret X25519 entre nuestra private key y la public key del peer.
     *
     * @param myPrivateKeyEncoded nuestra private key (PKCS8-encoded)
     * @param theirPublicKeyEncoded public key del peer (X.509-encoded)
     * @return shared secret (32 bytes)
     */
    public static byte[] x25519Agreement(final byte[] myPrivateKeyEncoded,
                                         final byte[] theirPublicKeyEncoded)
            throws GeneralSecurityException {
        final KeyFactory kf = KeyFactory.getInstance(X25519_ALG);
        final PrivateKey myPriv = kf.generatePrivate(new PKCS8EncodedKeySpec(myPrivateKeyEncoded));
        final PublicKey theirPub = kf.generatePublic(new X509EncodedKeySpec(theirPublicKeyEncoded));
        final KeyAgreement ka = KeyAgreement.getInstance(X25519_ALG);
        ka.init(myPriv);
        ka.doPhase(theirPub, true);
        return ka.generateSecret();
    }

    // ===== ChaCha20-Poly1305 AEAD =====

    /**
     * Cifra plaintext con ChaCha20-Poly1305 usando la shared secret + nonce dados.
     *
     * @return ciphertext con 16-byte tag suffix
     */
    public static byte[] chacha20Poly1305Encrypt(final byte[] sharedSecret,
                                                 final byte[] nonce,
                                                 final byte[] plaintext)
            throws GeneralSecurityException {
        if (sharedSecret == null || sharedSecret.length != 32) {
            throw new IllegalArgumentException("sharedSecret debe ser 32 bytes");
        }
        if (nonce == null || nonce.length != NONCE_SIZE) {
            throw new IllegalArgumentException("nonce debe ser " + NONCE_SIZE + " bytes");
        }
        final SecretKey key = new SecretKeySpec(sharedSecret, "ChaCha20");
        final Cipher cipher = Cipher.getInstance(CHACHA20_POLY1305);
        cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(nonce));
        return cipher.doFinal(plaintext);
    }

    /**
     * Descifra ciphertext ChaCha20-Poly1305.
     */
    public static byte[] chacha20Poly1305Decrypt(final byte[] sharedSecret,
                                                 final byte[] nonce,
                                                 final byte[] ciphertext)
            throws GeneralSecurityException {
        if (sharedSecret == null || sharedSecret.length != 32) {
            throw new IllegalArgumentException("sharedSecret debe ser 32 bytes");
        }
        if (nonce == null || nonce.length != NONCE_SIZE) {
            throw new IllegalArgumentException("nonce debe ser " + NONCE_SIZE + " bytes");
        }
        if (ciphertext == null || ciphertext.length < TAG_SIZE) {
            throw new IllegalArgumentException("ciphertext debe ser >= " + TAG_SIZE + " bytes");
        }
        final SecretKey key = new SecretKeySpec(sharedSecret, "ChaCha20");
        final Cipher cipher = Cipher.getInstance(CHACHA20_POLY1305);
        cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(nonce));
        return cipher.doFinal(ciphertext);
    }

    // ===== High-level: encryptForRecipient / decryptFromHost =====

    /**
     * Cifra plaintext para el recipient usando un ephemeral X25519 keypair.
     *
     * <p>Output format: {@code ephemeralPub || nonce || ciphertext} donde:
     * <ul>
     *   <li>{@code ephemeralPub}: 44 bytes (X.509-encoded X25519 public key)</li>
     *   <li>{@code nonce}: 12 bytes</li>
     *   <li>{@code ciphertext}: plaintext.length + 16 bytes (tag)</li>
     * </ul>
     *
     * @param recipientPublicKeyEncoded X.509-encoded X25519 public key del recipient
     * @param plaintext datos a cifrar (típicamente JSON o struct con host/port)
     * @return blob cifrado (ephemeralPub || nonce || ciphertext)
     */
    public static byte[] encryptForRecipient(final byte[] recipientPublicKeyEncoded,
                                             final byte[] plaintext)
            throws GeneralSecurityException {
        final Keys ephemeral = generateX25519KeyPair();
        final byte[] shared = x25519Agreement(ephemeral.privateKeyEncoded(), recipientPublicKeyEncoded);
        final byte[] nonce = new byte[NONCE_SIZE];
        SECURE_RANDOM.nextBytes(nonce);
        final byte[] ciphertext = chacha20Poly1305Encrypt(shared, nonce, plaintext);

        final ByteBuffer out = ByteBuffer.allocate(
                ephemeral.publicKeyEncoded().length + NONCE_SIZE + ciphertext.length);
        out.put(ephemeral.publicKeyEncoded());
        out.put(nonce);
        out.put(ciphertext);
        return out.array();
    }

    /**
     * Descifra un blob producido por {@link #encryptForRecipient}.
     *
     * @param myPrivateKeyEncoded nuestra private key (PKCS8-encoded X25519)
     * @param encrypted blob en formato {@code ephemeralPub || nonce || ciphertext}
     * @return plaintext original
     */
    public static byte[] decryptFromHost(final byte[] myPrivateKeyEncoded,
                                         final byte[] encrypted)
            throws GeneralSecurityException {
        if (encrypted == null || encrypted.length < X25519_PUBLIC_ENCODED_SIZE + NONCE_SIZE + TAG_SIZE) {
            throw new IllegalArgumentException("encrypted blob demasiado pequeño");
        }
        final ByteBuffer in = ByteBuffer.wrap(encrypted);
        final byte[] ephemeralPub = new byte[X25519_PUBLIC_ENCODED_SIZE];
        in.get(ephemeralPub);
        final byte[] nonce = new byte[NONCE_SIZE];
        in.get(nonce);
        final byte[] ciphertext = new byte[in.remaining()];
        in.get(ciphertext);

        final byte[] shared = x25519Agreement(myPrivateKeyEncoded, ephemeralPub);
        return chacha20Poly1305Decrypt(shared, nonce, ciphertext);
    }

    // ===== Helpers =====

    /** Genera nonce aleatorio (12 bytes). */
    public static byte[] generateNonce() {
        final byte[] n = new byte[NONCE_SIZE];
        SECURE_RANDOM.nextBytes(n);
        return n;
    }

    /** Encodea bytes como base64 (URL-safe, sin padding). */
    public static String base64Encode(final byte[] bytes) {
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }

    /** Decodea base64 a bytes. */
    public static byte[] base64Decode(final String s) {
        return java.util.Base64.getDecoder().decode(s);
    }
}