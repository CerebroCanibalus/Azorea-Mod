// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.security.GeneralSecurityException;

/**
 * Fachada singleton para Azorea Identity (ver AGENTS.md § F5.1 + F5.2b + F9/DA-8).
 *
 * <p><b>§ Modelo de claves (F9):</b> la identidad mantiene DOS pares:
 * <ul>
 *   <li><b>Ed25519</b> — FIRMA. Es la clave atada al {@code azorea_id}; con ella se
 *       prueban anuncios y (F10) el handshake P2P.</li>
 *   <li><b>X25519</b> — ECDH. Cifra la connection info de los invites.</li>
 * </ul>
 * X25519 no puede firmar, por eso son separadas. Ver {@link AzoreaId} para la
 * derivación auto-certificante del ID.
 *
 * <p>§ Lifecycle:
 * <ul>
 *   <li>Construido una vez con el gameDir.</li>
 *   <li>{@link #getOrCreate(String)} carga del disco o genera nueva (idempotente).</li>
 *   <li>Las private keys se mantienen en memoria solo el tiempo nec.</li>
 * </ul>
 *
 * <p>§ Thread-safety: {@code getOrCreate} está sincronizado; resto read-only tras init.
 *
 * <p>§ Limitaciones v1:
 * <ul>
 *   <li>Display name no se puede cambiar tras la creación.</li>
 *   <li>Sin rotación de claves (compromiso = regenerar identity).</li>
 * </ul>
 */
public final class AzoreaIdentityService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzoreaIdentityService.class);

    private final IdentityStorage storage;
    private volatile AzoreaIdentity identity;
    /** Private key X25519 (PKCS8 encoded) en memoria. Null hasta {@link #getOrCreate}. */
    private volatile byte[] privateKeyEncoded;
    /** § F9: private key Ed25519 (PKCS8) — la que firma anuncios. */
    private volatile byte[] ed25519PrivateKeyEncoded;
    /** § F9: public key Ed25519 (X.509) — la que se publica y con la que se verifica. */
    private volatile byte[] signingPublicKey;
    /** § F9: SHA-256(canonical(hw)) — componente del ID y publicada en anuncios. */
    private volatile byte[] hwCommit;

    public AzoreaIdentityService(final Path gameDir) {
        this.storage = new IdentityStorage(gameDir);
    }

    /**
     * Carga la identity desde disco, o genera una nueva si no existe / está corrupta.
     *
     * <p>Idempotente: llamadas posteriores devuelven la misma identity sin regenerar.
     *
     * <p>§ F9: el {@code azorea_id} se (re)deriva SIEMPRE de las claves + HW local
     * usando {@link AzoreaId#derive}. Si el valor persistido no cuadra (identity.json
     * v1, o el archivo llegó de otra máquina), se recalcula y se persiste.
     *
     * @param defaultDisplayName nombre a usar si hay que crear (ej. MC username)
     * @return identity actual
     */
    public synchronized AzoreaIdentity getOrCreate(final String defaultDisplayName) {
        if (identity != null) {
            return identity;
        }

        // § F9: HW local SIEMPRE — la ID sigue a la máquina (semántica v1) y el
        // commit entra en la derivación auto-certificante.
        final HwFingerprint hw = HwFingerprint.extract();
        final byte[] commit = hw.hwCommit();

        final IdentityStorage.StoredIdentity stored = storage.load();
        if (stored != null && stored.x25519PrivateKeyBase64 != null
                && stored.ed25519PrivateKeyBase64 != null) {
            try {
                final byte[] xPub = stored.decodeX25519PublicKey();
                final byte[] edPub = stored.decodeEd25519PublicKey();

                // § F9: derivar ID v2 desde claves + HW local.
                final String derived = AzoreaId.derive(edPub, xPub, commit);
                if (!derived.equals(stored.azoreaId)) {
                    LOGGER.info("azorea_id migrado v1→v2: {} → {}", stored.azoreaId, derived);
                    stored.azoreaId = derived;
                    storage.save(stored);
                }

                this.identity = new AzoreaIdentity(
                        derived, stored.displayName, xPub, stored.createdAt);
                this.privateKeyEncoded = stored.decodeX25519PrivateKey();
                this.ed25519PrivateKeyEncoded = stored.decodeEd25519PrivateKey();
                this.signingPublicKey = edPub;
                this.hwCommit = commit;

                LOGGER.info("Identity cargada: azorea_id={}, display_name={}, creada {}",
                        identity.azoreaId(),
                        identity.displayName(),
                        java.time.Instant.ofEpochMilli(identity.createdAt()));
                return identity;
            } catch (final Exception e) {
                LOGGER.warn("StoredIdentity inválida, regenerando: {}", e.getMessage());
            }
        }

        // Generar nueva.
        try {
            final AzoreaIdentity.Keys xKeys = AzoreaIdentity.generateKeyPair();
            final AzoreaIdentity.Keys edKeys = AzoreaIdentity.generateSigningKeyPair();

            // § F9: ID derivado de las claves (no solo del HW) → auto-certificante.
            final String azoreaId = AzoreaId.derive(edKeys.publicKey(), xKeys.publicKey(), commit);

            final IdentityStorage.StoredIdentity created = IdentityStorage.StoredIdentity.create(
                    azoreaId, defaultDisplayName,
                    xKeys.publicKey(), xKeys.privateKey(),
                    edKeys.publicKey(), edKeys.privateKey(),
                    hw);
            storage.save(created);

            this.identity = new AzoreaIdentity(
                    created.azoreaId, created.displayName,
                    created.decodeX25519PublicKey(), created.createdAt);
            this.privateKeyEncoded = created.decodeX25519PrivateKey();
            this.ed25519PrivateKeyEncoded = created.decodeEd25519PrivateKey();
            this.signingPublicKey = edKeys.publicKey();
            this.hwCommit = commit;

            LOGGER.info("Identity creada (v2 auto-certificante): azorea_id={}, display_name={}",
                    identity.azoreaId(), identity.displayName());
            LOGGER.debug("HW fingerprint: mac={}, uuid_present={}",
                    hw.macAddress(), hw.hardwareUuid() != null);

            return identity;
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException("Error generando keypairs (X25519/Ed25519)", e);
        }
    }

    /** Identity actual o null si aún no se inicializó. */
    public AzoreaIdentity getIdentity() {
        return identity;
    }

    /**
     * Devuelve la X25519 private key (PKCS8 encoded) para operaciones de ECDH.
     *
     * @throws IllegalStateException si la identidad no está inicializada
     */
    public byte[] x25519PrivateKey() {
        if (privateKeyEncoded == null) {
            throw new IllegalStateException("Identity no inicializada; llama getOrCreate primero");
        }
        return privateKeyEncoded;
    }

    /**
     * § F9: clave privada Ed25519 (PKCS8) para FIRMAR anuncios/handshake.
     *
     * @throws IllegalStateException si la identidad no está inicializada
     */
    public byte[] ed25519PrivateKey() {
        if (ed25519PrivateKeyEncoded == null) {
            throw new IllegalStateException("Identity no inicializada; llama getOrCreate primero");
        }
        return ed25519PrivateKeyEncoded;
    }

    /**
     * § F9: clave pública Ed25519 (X.509) — se publica en anuncios para verificación.
     *
     * @throws IllegalStateException si la identidad no está inicializada
     */
    public byte[] signingPublicKey() {
        if (signingPublicKey == null) {
            throw new IllegalStateException("Identity no inicializada; llama getOrCreate primero");
        }
        return signingPublicKey;
    }

    /**
     * § F9: commit del HW (SHA-256(canonical)) — componente del ID; se publica
     * en anuncios para que el tracker pueda re-derivar y verificar.
     *
     * @throws IllegalStateException si la identidad no está inicializada
     */
    public byte[] hwCommit() {
        if (hwCommit == null) {
            throw new IllegalStateException("Identity no inicializada; llama getOrCreate primero");
        }
        return hwCommit;
    }

    /**
     * § F9: firma un mensaje con la Ed25519 de la identidad.
     *
     * @throws IllegalStateException si la identidad no está inicializada
     * @throws GeneralSecurityException si la firma falla
     */
    public byte[] sign(final byte[] message) throws GeneralSecurityException {
        return AzoreaIdentity.sign(ed25519PrivateKey(), message);
    }

    public Path getStorageFile() {
        return storage.getStorageFile();
    }
}
