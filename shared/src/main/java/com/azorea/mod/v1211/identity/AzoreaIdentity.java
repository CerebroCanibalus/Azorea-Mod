// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.identity;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * Identidad Azorea (ver AGENTS.md § F5.1 + F5.2b, modelo C).
 *
 * <p><b>v2 (F5.2b):</b> usa X25519 para ECDH (invite encryption). El tracker
 * publica el {@code x25519PublicKey} en announcements; los recipients lo usan
 * para cifrar connection info. Ed25519 de F5.1 fue deprecado (no había
 * dependencias v2 que lo necesitaran; reemplazado por X25519).
 *
 * <p>§ Componentes:
 * <ul>
 *   <li>{@code azoreaId}: derivado de SHA-256(HW fingerprint). Estable.</li>
 *   <li>{@code displayName}: nombre público (default = MC username).</li>
 *   <li>{@code x25519PublicKey}: X.509-encoded (44 bytes) — usado para ECDH.</li>
 *   <li>{@code createdAt}: timestamp de creación (ms epoch).</li>
 * </ul>
 *
 * <p>§ Migración desde F5.1 (Ed25519):
 *   Storage antiguos tienen {@code public_key_base64} (Ed25519) sin
 *   {@code x25519_*_base64}. {@link IdentityStorage} detecta y genera
 *   X25519 automáticamente en load(); el campo Ed25519 legacy queda
 *   ignorado en JSON.
 */
public record AzoreaIdentity(
        String azoreaId,
        String displayName,
        byte[] x25519PublicKey,
        long createdAt
) {

    public AzoreaIdentity {
        if (azoreaId == null || azoreaId.isBlank()) {
            throw new IllegalArgumentException("azoreaId no puede ser vacío");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName no puede ser vacío");
        }
        if (x25519PublicKey == null || x25519PublicKey.length == 0) {
            throw new IllegalArgumentException("x25519PublicKey no puede ser vacío");
        }
        if (createdAt <= 0) {
            throw new IllegalArgumentException("createdAt debe ser positivo");
        }
    }

    /** Par de claves X25519 generadas. Inmutable. */
    public record Keys(byte[] publicKey, byte[] privateKey) {
        public Keys {
            if (publicKey == null || publicKey.length == 0) {
                throw new IllegalArgumentException("publicKey vacío");
            }
            if (privateKey == null || privateKey.length == 0) {
                throw new IllegalArgumentException("privateKey vacío");
            }
        }
    }

    /**
     * Genera un nuevo par de claves X25519.
     *
     * @return Keys con publicKey (X.509, 44 bytes) y privateKey (PKCS8, 48 bytes)
     * @throws GeneralSecurityException si X25519 no está disponible
     */
    public static Keys generateKeyPair() throws GeneralSecurityException {
        final KeyPairGenerator g = KeyPairGenerator.getInstance("X25519");
        final KeyPair kp = g.generateKeyPair();
        return new Keys(
                kp.getPublic().getEncoded(),
                kp.getPrivate().getEncoded()
        );
    }

    /**
     * Decodifica una private key PKCS8-encoded a objeto PrivateKey de Java.
     */
    public static java.security.PrivateKey decodePrivateKey(final byte[] pkcs8Encoded)
            throws GeneralSecurityException {
        final KeyFactory kf = KeyFactory.getInstance("X25519");
        return kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8Encoded));
    }

    /**
     * Decodifica una public key X.509-encoded a objeto PublicKey de Java.
     */
    public static java.security.PublicKey decodePublicKey(final byte[] x509Encoded)
            throws GeneralSecurityException {
        final KeyFactory kf = KeyFactory.getInstance("X25519");
        return kf.generatePublic(new X509EncodedKeySpec(x509Encoded));
    }

    // ===== § F9: Ed25519 — clave de FIRMA (identidad) =====

    /**
     * Genera un nuevo par de claves Ed25519 para FIRMAR (ver AGENTS.md § DA-8).
     *
     * <p>Diferente de {@link #generateKeyPair()} (X25519), que es para ECDH.
     * X25519 no puede firmar; por eso la identidad mantiene DOS pares:
     * Ed25519 firma anuncios/handshake, X25519 cifra invites.
     *
     * @return Keys con publicKey (X.509) y privateKey (PKCS8)
     * @throws GeneralSecurityException si Ed25519 no está disponible (JDK 15+)
     */
    public static Keys generateSigningKeyPair() throws GeneralSecurityException {
        final KeyPairGenerator g = KeyPairGenerator.getInstance("Ed25519");
        final KeyPair kp = g.generateKeyPair();
        return new Keys(
                kp.getPublic().getEncoded(),
                kp.getPrivate().getEncoded()
        );
    }

    /**
     * Firma un mensaje con una Ed25519 private key PKCS8-encoded.
     *
     * <p>Ed25519 (RFC 8032) es <b>determinista</b>: el mismo mensaje + clave produce
     * siempre la misma firma. Útil: el refresh del anuncio (mismo contenido) firma
     * idéntico, sin necesidad de re-firmar lógicamente.
     *
     * @return firma (64 bytes, DER/RAW según el proveedor — SunEC da RAW)
     * @throws GeneralSecurityException si la clave o el algoritmo son inválidos
     */
    public static byte[] sign(final byte[] privateKeyPkcs8, final byte[] message)
            throws GeneralSecurityException {
        final java.security.PrivateKey priv = decodeEd25519PrivateKey(privateKeyPkcs8);
        final java.security.Signature s = java.security.Signature.getInstance("Ed25519");
        s.initSign(priv);
        s.update(message);
        return s.sign();
    }

    /**
     * Verifica una firma Ed25519.
     *
     * @param publicKeyX509 clave pública Ed25519 (X.509 encoded)
     * @param message       mensaje original
     * @param signature     firma a verificar
     * @return true si la firma es válida
     */
    public static boolean verifySignature(final byte[] publicKeyX509, final byte[] message,
                                          final byte[] signature) {
        if (publicKeyX509 == null || message == null || signature == null) return false;
        try {
            final java.security.PublicKey pub = decodeEd25519PublicKey(publicKeyX509);
            final java.security.Signature s = java.security.Signature.getInstance("Ed25519");
            s.initVerify(pub);
            s.update(message);
            return s.verify(signature);
        } catch (final GeneralSecurityException e) {
            return false;
        }
    }

    /**
     * Decodifica una Ed25519 private key PKCS8-encoded.
     */
    public static java.security.PrivateKey decodeEd25519PrivateKey(final byte[] pkcs8)
            throws GeneralSecurityException {
        return KeyFactory.getInstance("Ed25519")
                .generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
    }

    /**
     * Decodifica una Ed25519 public key X.509-encoded.
     */
    public static java.security.PublicKey decodeEd25519PublicKey(final byte[] x509)
            throws GeneralSecurityException {
        return KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(x509));
    }
}