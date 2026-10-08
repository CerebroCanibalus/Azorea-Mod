// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.net;

import com.azorea.mod.AzoreaConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Payload smoke test: ping del cliente al servidor (con nonce + timestamp).
 * El servidor responde con {@link PongPayload} eco del nonce.
 *
 * § Seguridad (AGENTS.md): tamaño fijo (16 bytes), campos validados al recibir.
 */
public record PingPayload(long nonce, long timestamp) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PingPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(AzoreaConstants.MOD_ID, "ping"));

    public static final StreamCodec<FriendlyByteBuf, PingPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeLong(payload.nonce);
                        buf.writeLong(payload.timestamp);
                    },
                    buf -> new PingPayload(buf.readLong(), buf.readLong())
            );

    @Override
    public Type<PingPayload> type() {
        return TYPE;
    }
}
