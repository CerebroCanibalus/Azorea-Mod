// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.net;

import com.azorea.mod.AzoreaConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Reto del server al cliente en la fase de CONFIGURATION (F10, § A2).
 *
 * <p>El server manda 32 bytes aleatorios y <b>NO finaliza la task</b>: el cliente queda
 * congelado antes de spawnear hasta que responda con {@link IdentityProofPayload} o vence
 * el timeout de 15 s.
 *
 * <p>Sólo transporte — toda la lógica (generar el reto, codificar, firmar) vive en
 * {@link com.azorea.mod.v1211.access.AzoreaAccessGate}, que es <b>puro</b> y por eso
 * testeable sin MC.
 *
 * <p>Formato: un único {@code String} Base64, igual que los DTO de identity del repo ⇒
 * usamos {@code writeUtf/readUtf} con <b>límite explícito</b> (44 chars para 32 B).
 *
 * <p><b>§ Seguridad</b>: el reto es de <b>un solo uso</b> por conexión y se firma bajo el
 * dominio {@code AzoreaAccessGate.CANONICAL_DOMAIN} ⇒ no hay replay entre sesiones.
 */
public record IdentityChallengePayload(String challengeB64) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<IdentityChallengePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    AzoreaConstants.MOD_ID, "access_challenge"));

    /** Máximo razonable de un reto Base64 de 32 B (44 chars) — margen holgado. */
    public static final int MAX_CHALLENGE_B64 = 128;

    public static final StreamCodec<FriendlyByteBuf, IdentityChallengePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeUtf(payload.challengeB64, MAX_CHALLENGE_B64),
                    buf -> new IdentityChallengePayload(buf.readUtf(MAX_CHALLENGE_B64))
            );

    @Override
    public Type<IdentityChallengePayload> type() {
        return TYPE;
    }

    /** No exponer el reto crudo en logs — sólo su longitud. */
    @Override
    public String toString() {
        final byte[] raw = com.azorea.mod.v1211.access.AzoreaAccessGate.decode(challengeB64);
        return "IdentityChallengePayload{len=" + (raw == null ? -1 : raw.length) + "}";
    }
}
