// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.identity;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * Persistencia de Azorea Identity en disco (ver AGENTS.md § F5.1 + F5.2b).
 *
 * <p><b>§ Formato v2 ({@code <gameDir>/azorea/identity.json}):</b>
 * <pre>{@code
 * {
 *   "azorea_id": "AZ-XXXXXX-XXXXXX-XXXXXX-XXXXXX",
 *   "display_name": "Steve",
 *   "public_key_base64": "...",      // F5.1 Ed25519 legacy (ignorado en v2)
 *   "private_key_base64": "...",     // F5.1 Ed25519 legacy (ignorado en v2)
 *   "x25519_public_key_base64": "...",  // F5.2b — X25519 para ECDH
 *   "x25519_private_key_base64": "...", // F5.2b — X25519 para ECDH
 *   "created_at": 1234567890,
 *   "hw_fingerprint": {
 *     "mac_address": "AA:BB:CC:DD:EE:FF",
 *     "hardware_uuid": "4C1B-3F2A-...",
 *     "os_version": "10.0",
 *     "hostname": "DESKTOP-XYZ"
 *   }
 * }
 * }</pre>
 *
 * <p><b>§ Migración F5.1→F5.2b:</b> si {@code x25519_*} están ausentes en el JSON
 * existente, este storage genera nuevas claves X25519 al cargar y persiste.
 * El {@code public_key_base64} Ed25519 legacy queda en el archivo pero
 * no se usa. Backward compat 100%.
 *
 * <p>§ Seguridad: private keys en claro en disco (v1). TODO cifrar con passphrase en F5.x+.
 */
public final class IdentityStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger(IdentityStorage.class);
    private static final Gson GSON = new Gson();

    public static final String FILE_NAME = "azorea/identity.json";

    private final Path storageFile;

    public IdentityStorage(final Path gameDir) {
        this.storageFile = gameDir.resolve(FILE_NAME);
    }

    /**
     * Carga la identity desde disco. Si no existe, devuelve null.
     * Si existe pero le faltan claves X25519 (F5.1 legacy), genera y persiste.
     */
    public StoredIdentity load() {
        if (!Files.isRegularFile(storageFile)) {
            return null;
        }
        try (Reader r = Files.newBufferedReader(storageFile, StandardCharsets.UTF_8)) {
            final StoredIdentity stored = GSON.fromJson(r, StoredIdentity.class);
            if (stored == null || stored.azoreaId == null) {
                LOGGER.warn("identity.json corrupto (campos faltantes): {}", storageFile);
                return null;
            }
            // § F5.2b migration: si X25519 falta, generar y persistir.
            if (stored.x25519PublicKeyBase64 == null || stored.x25519PrivateKeyBase64 == null) {
                LOGGER.info("Migrando identity.json legacy (F5.1) → añadiendo X25519 keys...");
                try {
                    final AzoreaIdentity.Keys x25519 = AzoreaIdentity.generateKeyPair();
                    stored.x25519PublicKeyBase64 = Base64.getEncoder()
                            .encodeToString(x25519.publicKey());
                    stored.x25519PrivateKeyBase64 = Base64.getEncoder()
                            .encodeToString(x25519.privateKey());
                    save(stored);
                } catch (GeneralSecurityException e) {
                    LOGGER.error("Fallo generando X25519 keys para migración: {}", e.getMessage(), e);
                    return null;
                }
            }
            // § F9 migration: si Ed25519 (clave de FIRMA) falta, recuperar el legacy
            // F5.1 si era válido Ed25519, o generar una nueva.
            if (stored.ed25519PublicKeyBase64 == null || stored.ed25519PrivateKeyBase64 == null) {
                migrateSigningKey(stored);
            }
            return stored;
        } catch (Exception e) {
            LOGGER.error("Error cargando identity.json: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * § F9: migra la clave de firma Ed25519.
     *
     * <p>Los identity.json de F5.1 traían un par Ed25519 en
     * {@code public_key_base64}/{@code private_key_base64} que F5.2b dejó "ignorado"
     * (solo se usaba X25519). Como esa clave SÍ era Ed25519, la reutilizamos:
     * mantiene la misma identidad de firma que en F5.1. Si no existe o no decodifica
     * como Ed25519, generamos una nueva.
     *
     * <p>Persiste el resultado.
     */
    private void migrateSigningKey(final StoredIdentity stored) {
        boolean reused = false;
        if (stored.publicKeyBase64 != null && stored.privateKeyBase64 != null) {
            try {
                final byte[] priv = Base64.getDecoder().decode(stored.privateKeyBase64);
                final byte[] pub = Base64.getDecoder().decode(stored.publicKeyBase64);
                // Verificación real: decodifica como Ed25519 y firma/verifica.
                final byte[] probe = "azorea-ed25519-migration-probe".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                final byte[] sig = AzoreaIdentity.sign(priv, probe);
                if (AzoreaIdentity.verifySignature(pub, probe, sig)) {
                    stored.ed25519PrivateKeyBase64 = stored.privateKeyBase64;
                    stored.ed25519PublicKeyBase64 = stored.publicKeyBase64;
                    reused = true;
                    LOGGER.info("Ed25519 legacy (F5.1) reutilizado como clave de firma.");
                }
            } catch (final Exception e) {
                LOGGER.debug("Ed25519 legacy no reutilizable ({}), generando nueva.", e.getMessage());
            }
        }
        if (!reused) {
            try {
                final AzoreaIdentity.Keys ed = AzoreaIdentity.generateSigningKeyPair();
                stored.ed25519PublicKeyBase64 = Base64.getEncoder().encodeToString(ed.publicKey());
                stored.ed25519PrivateKeyBase64 = Base64.getEncoder().encodeToString(ed.privateKey());
                LOGGER.info("Ed25519 generada (nueva clave de firma).");
            } catch (final GeneralSecurityException e) {
                LOGGER.error("Fallo generando Ed25519: {}", e.getMessage(), e);
                return;
            }
        }
        try {
            save(stored);
        } catch (final RuntimeException e) {
            LOGGER.warn("No pude persistir la migración Ed25519: {}", e.getMessage());
        }
    }

    /**
     * Guarda la identity en disco (sobrescribe).
     */
    public void save(final StoredIdentity stored) {
        try {
            final Path parent = storageFile.getParent();
            if (parent != null && !Files.isDirectory(parent)) {
                Files.createDirectories(parent);
            }
            try (Writer w = Files.newBufferedWriter(storageFile, StandardCharsets.UTF_8)) {
                GSON.toJson(stored, w);
            }
            LOGGER.info("Identity guardada en {}: azorea_id={}", storageFile, stored.azoreaId);
        } catch (IOException e) {
            LOGGER.error("Error guardando identity.json: {}", e.getMessage(), e);
        }
    }

    public Path getStorageFile() {
        return storageFile;
    }

    // ===== DTOs =====

    public static final class StoredIdentity {
        @SerializedName("azorea_id")
        public String azoreaId;
        @SerializedName("display_name")
        public String displayName;
        /** @deprecated F5.1 Ed25519; ignorado en F5.2b. Se mantiene en JSON por compat. */
        @Deprecated
        @SerializedName("public_key_base64")
        public String publicKeyBase64;
        /** @deprecated F5.1 Ed25519; ignorado en F5.2b. Se mantiene en JSON por compat. */
        @Deprecated
        @SerializedName("private_key_base64")
        public String privateKeyBase64;
        /** F5.2b: X.509-encoded X25519 public key (44 bytes → base64). */
        @SerializedName("x25519_public_key_base64")
        public String x25519PublicKeyBase64;
        /** F5.2b: PKCS8-encoded X25519 private key (48 bytes → base64). */
        @SerializedName("x25519_private_key_base64")
        public String x25519PrivateKeyBase64;
        /** § F9: X.509-encoded Ed25519 public key — clave de FIRMA (identidad). */
        @SerializedName("ed25519_public_key_base64")
        public String ed25519PublicKeyBase64;
        /** § F9: PKCS8-encoded Ed25519 private key — clave de FIRMA (identidad). */
        @SerializedName("ed25519_private_key_base64")
        public String ed25519PrivateKeyBase64;
        @SerializedName("created_at")
        public long createdAt;
        @SerializedName("hw_fingerprint")
        public HwFingerprintDto hwFingerprint;

        public static StoredIdentity create(final String azoreaId,
                                            final String displayName,
                                            final byte[] x25519PublicKey,
                                            final byte[] x25519PrivateKey,
                                            final byte[] ed25519PublicKey,
                                            final byte[] ed25519PrivateKey,
                                            final HwFingerprint hw) {
            final StoredIdentity s = new StoredIdentity();
            s.azoreaId = azoreaId;
            s.displayName = displayName;
            s.x25519PublicKeyBase64 = Base64.getEncoder().encodeToString(x25519PublicKey);
            s.x25519PrivateKeyBase64 = Base64.getEncoder().encodeToString(x25519PrivateKey);
            s.ed25519PublicKeyBase64 = Base64.getEncoder().encodeToString(ed25519PublicKey);
            s.ed25519PrivateKeyBase64 = Base64.getEncoder().encodeToString(ed25519PrivateKey);
            s.createdAt = System.currentTimeMillis();
            s.hwFingerprint = HwFingerprintDto.from(hw);
            return s;
        }

        public byte[] decodeX25519PublicKey() {
            return Base64.getDecoder().decode(x25519PublicKeyBase64);
        }

        public byte[] decodeX25519PrivateKey() {
            return Base64.getDecoder().decode(x25519PrivateKeyBase64);
        }

        /** § F9: clave pública Ed25519 (X.509) — la que se publica y con la que se verifica. */
        public byte[] decodeEd25519PublicKey() {
            return Base64.getDecoder().decode(ed25519PublicKeyBase64);
        }

        /** § F9: clave privada Ed25519 (PKCS8) — la que firma anuncios. */
        public byte[] decodeEd25519PrivateKey() {
            return Base64.getDecoder().decode(ed25519PrivateKeyBase64);
        }
    }

    public static final class HwFingerprintDto {
        @SerializedName("mac_address")
        public String macAddress;
        @SerializedName("hardware_uuid")
        public String hardwareUuid;
        @SerializedName("os_version")
        public String osVersion;
        @SerializedName("hostname")
        public String hostname;

        public static HwFingerprintDto from(final HwFingerprint hw) {
            final HwFingerprintDto d = new HwFingerprintDto();
            d.macAddress = hw.macAddress();
            d.hardwareUuid = hw.hardwareUuid();
            d.osVersion = hw.osVersion();
            d.hostname = hw.hostname();
            return d;
        }
    }
}