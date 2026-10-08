// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.net;

import com.azorea.mod.AzoreaConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Respuesta del cliente al reto de identidad, en fase de CONFIGURATION (F10, § A2).
 *
 * <p>Lleva la identidad auto-certificante completa (DA-8) + la firma Ed25519 del challenge.
 * El server verifica con {@link com.azorea.mod.v1211.access.AzoreaAccessGate}.
 *
 * <p><b>§ Campos</b> todos como String (Base64 para los binarios) — mismo patrón que los DTO
 * de {@code identity.json} del repo, y así usamos {@code writeUtf/readUtf} con <b>límite
 * explícito</b> en cada campo (regla: todo payload con tamaño máximo).
 *
 * <p>Tamaño total estimado &lt; 400 B ⇒ muy por debajo de los 32 KiB que NeoForge permite
 * en payloads hacia el server.
 *
 * @param version        versión del formato — hoy {@code "1"}; cambia si cambia el cómputo
 * @param azoreaId       ID auto-certificante {@code AZ-XXXXXX-…}
 * @param displayName    nombre de MC que declara el cliente (si miente con el nombre de
 *                       otro, {@code AzoreaAccessGate} lo detecta y lo flaggea)
 * @param ed25519PubB64  clave pública de FIRMA (X.509, Base64)
 * @param x25519PubB64   clave pública X25519 (X.509, Base64)
 * @param hwCommitB64    huella de hardware, 32 B (Base64)
 * @param signatureB64   firma Ed25519 sobre el canónico del challenge (64 B, Base64)
 */
public record IdentityProofPayload(String version,
                                   String azoreaId,
                                   String displayName,
                                   String ed25519PubB64,
                                   String x25519PubB64,
                                   String hwCommitB64,
                                   String signatureB64) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<IdentityProofPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    AzoreaConstants.MOD_ID, "access_proof"));

    /**
     * Versión soportada — el valor canónico vive en el gate para que transporte y
     * verificación no puedan desincronizarse.
     */
    public static final String VERSION = com.azorea.mod.v1211.access.AzoreaAccessGate.VERSION;

    // Límites por campo — todo payload se valida, nunca se acepta basura ilimitada.
    // Se delegan en el gate: los límites y la verificación son la misma decisión.
    public static final int MAX_VERSION = com.azorea.mod.v1211.access.AzoreaAccessGate.MAX_VERSION;
    public static final int MAX_ID = com.azorea.mod.v1211.access.AzoreaAccessGate.MAX_ID;
    public static final int MAX_NAME = com.azorea.mod.v1211.access.AzoreaAccessGate.MAX_NAME;
    public static final int MAX_KEY_B64 = com.azorea.mod.v1211.access.AzoreaAccessGate.MAX_KEY_B64;
    public static final int MAX_SIG_B64 = com.azorea.mod.v1211.access.AzoreaAccessGate.MAX_SIG_B64;

    /** Mapeo transporte → verificación. Ése es todo el acoplamiento con el gate. */
    public com.azorea.mod.v1211.access.AzoreaAccessGate.Proof toProof() {
        return new com.azorea.mod.v1211.access.AzoreaAccessGate.Proof(
                version, azoreaId, displayName,
                ed25519PubB64, x25519PubB64, hwCommitB64, signatureB64);
    }

    public static final StreamCodec<FriendlyByteBuf, IdentityProofPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeUtf(p.version, MAX_VERSION);
                        buf.writeUtf(p.azoreaId, MAX_ID);
                        buf.writeUtf(p.displayName, MAX_NAME);
                        buf.writeUtf(p.ed25519PubB64, MAX_KEY_B64);
                        buf.writeUtf(p.x25519PubB64, MAX_KEY_B64);
                        buf.writeUtf(p.hwCommitB64, MAX_KEY_B64);
                        buf.writeUtf(p.signatureB64, MAX_SIG_B64);
                    },
                    buf -> new IdentityProofPayload(
                            buf.readUtf(MAX_VERSION),
                            buf.readUtf(MAX_ID),
                            buf.readUtf(MAX_NAME),
                            buf.readUtf(MAX_KEY_B64),
                            buf.readUtf(MAX_KEY_B64),
                            buf.readUtf(MAX_KEY_B64),
                            buf.readUtf(MAX_SIG_B64))
            );

    @Override
    public Type<IdentityProofPayload> type() {
        return TYPE;
    }

    /** No volcar claves ni firmas en logs. */
    @Override
    public String toString() {
        return "IdentityProofPayload{v=" + version
                + ", id=" + azoreaId
                + ", name=" + displayName + "}";
    }
}
