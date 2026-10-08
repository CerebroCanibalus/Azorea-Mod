// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.VarInt;

import java.util.zip.Deflater;

/**
 * {@link CompressionEncoder} con nivel de compresión configurable (§ DA-14, fase N1).
 *
 * <p>§ <b>Qué cambia respecto a vanilla</b> — medido en {@code CompressionEncoder.java}
 * de las fuentes 1.21.1:
 * <pre>
 *   vanilla:  new Deflater()      → nivel 6 (DEFAULT_COMPRESSION)
 *   Azorea:   new Deflater(level) → nivel configurable, 9 por defecto
 * </pre>
 * <b>Nada más.</b> El formato de trama es <b>idéntico</b> ⇒ cualquier cliente (¡incluso
 * vanilla!) lo infla igual. <b>Cero negociación.</b>
 *
 * <p>§ <b>Por qué hay que reimplementar {@code encode}</b>: el {@code Deflater} d/ vanilla
 * es {@code private final} — no hay setter d/ nivel (por eso EnhancedPacketCompression
 * existe como mod aparte). Subclass + override es la única vía sin reflexión frágil.
 *
 * <p>§ <b>Por qué extiende {@code CompressionEncoder} y conserva el nombre {@code compress}</b>:
 * {@code Connection.setupCompression} hace
 * {@code pipeline.get("compress") instanceof CompressionEncoder → setThreshold(...)}.
 * Si fuéramos una clase ajena, ese {@code instanceof} fallaría y vanilla intentaría
 * {@code addAfter("prepender","compress",…)} ⇒ <b>nombre duplicado ⇒ excepción</b>.
 * Al extenderlo, un re-{@code setupCompression} posterior sigue funcionando sobre
 * nuestra instancia.
 *
 * <p>§ <b>Hilos</b>: Netty garantiza que {@code encode} corre en el event loop del canal
 * (uno por conexión) ⇒ el {@link Deflater}, que <b>no es thread-safe</b>, está a salvo.
 * Idéntico al razonamiento d/ vanilla.
 *
 * <p>§ <b>Nota menor</b>: {@code super(threshold)} crea también el {@code Deflater} d/
 * vanilla, que queda <b>sin usar</b> (uno huérfano por conexión, liberado por el Cleaner
 * al hacer GC). Es el precio d/ usar el constructor público d/ la superclase.
 */
public final class AzoreaNetCompressionEncoder extends CompressionEncoder {

    private static final int ENCODE_BUF_SIZE = 8192;
    /** Límite duro d/ protocolo — el mismo q/ comprueba {@code CompressionEncoder} vanilla. */
    private static final int MAX_PACKET_SIZE = CompressionDecoder.MAXIMUM_UNCOMPRESSED_LENGTH;

    private final Deflater deflater;
    private final byte[] encodeBuf = new byte[ENCODE_BUF_SIZE];
    private final int level;
    private final AzoreaNetStats stats;

    /**
     * @param threshold umbral inicial (vanilla lo reajusta v/ {@code setThreshold})
     * @param level     nivel deflate 0..9 (6 = comportamiento vanilla)
     * @param stats     contadores; nunca null
     */
    public AzoreaNetCompressionEncoder(final int threshold, final int level,
                                       final AzoreaNetStats stats) {
        super(threshold);
        this.level = level;
        this.deflater = new Deflater(level);
        this.stats = stats;
    }

    @Override
    protected void encode(final ChannelHandlerContext context, final ByteBuf in,
                          final ByteBuf out) {
        final int raw = in.readableBytes();
        stats.addRawOut(raw);
        stats.incPacketsOut();

        if (raw > MAX_PACKET_SIZE) {
            throw new IllegalArgumentException("Packet too big (is " + raw
                    + ", should be less than " + MAX_PACKET_SIZE + ")");
        }

        // § Bajo el umbral ⇒ crudo, igual q/ vanilla (el prefijo 0 lo dice al decoder).
        final int threshold = getThreshold();
        if (raw < threshold) {
            VarInt.write(out, 0);
            out.writeBytes(in);
            return;
        }

        final byte[] data = new byte[raw];
        in.readBytes(data);
        VarInt.write(out, raw);

        final long started = System.nanoTime();
        long compressed = 0L;
        try {
            deflater.setInput(data);
            deflater.finish();
            while (!deflater.finished()) {
                final int written = deflater.deflate(encodeBuf);
                out.writeBytes(encodeBuf, 0, written);
                compressed += written;
            }
        } finally {
            // § Siempre resetear: un Deflater sin reset deja estado viejo y el SIGUIENTE
            //   paquete saldría corrupto (o el inflate d/ l'otro lado fallaría).
            deflater.reset();
        }
        stats.addEncodeNanos(System.nanoTime() - started);
        stats.addCompressedOut(compressed);
    }

    /** Nivel deflate configurado en esta conexión (para el informe). */
    public int azoreaLevel() {
        return level;
    }
}
