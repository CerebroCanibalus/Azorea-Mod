// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

/**
 * Cuenta los bytes que <b>de verdad cruzan el socket</b> (§ DA-14, fase N1).
 *
 * <p>§ <b>Dónde se instala</b>: {@code addFirst} — lo primero q/ toca el canal, antes
 * incluso d/ {@code timeout}. Eso es deliberado:
 *
 * <pre>
 *   OUT: … → encoder → compress → prepender → encrypt → [METER] → socket
 *   IN : socket → [METER] → timeout → decrypt → splitter → decompress → …
 * </pre>
 *
 * ⇒ mide <b>post-cifrado</b> en salida y <b>pre-descifrado</b> en entrada = los bytes
 * reales d/ la red. Si midiéramos dentro del pipeline veríamos bytes sin cifrar y
 * sin el prefijo d/ longitud ⇒ <b>no</b> es el ancho de banda q/ paga el amigo.
 *
 * <p>§ <b>Transparencia</b>: sólo <i>lee</i> {@code readableBytes()} (no consume) y
 * reenvía el mensaje intacto. No retiene, no libera, no reordena.
 *
 * <p>§ <b>Sin estado por conexión</b>: un handler por canal, pero todo va a los
 * contadores globales d/ {@link AzoreaNetStats} (atómicos ⇒ seguro desde cualquier
 * event loop). No necesita {@code @Sharable}.
 */
public final class AzoreaNetMeter extends ChannelDuplexHandler {

    private final AzoreaNetStats stats;

    public AzoreaNetMeter(final AzoreaNetStats stats) {
        this.stats = stats;
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
        if (msg instanceof ByteBuf buf) {
            stats.addWireIn(buf.readableBytes());
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void write(final ChannelHandlerContext ctx, final Object msg,
                      final ChannelPromise promise) {
        if (msg instanceof ByteBuf buf) {
            stats.addWireOut(buf.readableBytes());
        }
        ctx.write(msg, promise);
    }
}
