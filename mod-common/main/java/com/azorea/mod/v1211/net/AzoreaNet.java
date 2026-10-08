// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.net;

import com.azorea.mod.v1211.AzoreaConfig;
import com.azorea.mod.v1211.AzoreaNetLog;
import com.azorea.mod.v1211.AzoreaNetLog.Category;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * {@code AzoreaNet} — fase <b>N1</b>: codec de compresión configurable + métrica d/ bytes.
 * <i>Ver AGENTS.md § DA-14.</i>
 *
 * <p>§ <b>Dónde se engancha</b>: {@code RegisterConfigurationTasksEvent} — el mismo hook
 * q/ ya usa el gate F10, así q/ <b>cero infraestructura nueva</b>.
 *
 * <p>§ <b>Por q/ ese momento es el correcto</b> (verificado en fuentes):
 * <ul>
 *   <li>vanilla instala la compresión en <b>LOGIN</b>
 *       ({@code ServerLoginPacketListenerImpl:151 → Connection.setupCompression})</li>
 *   <li>y las configuration tasks corren <b>después</b>, en <b>CONFIGURATION</b></li>
 *   ⇒ al llegar, {@code compress} <b>ya existe</b> y podemos sustituirlo.
 * </ul>
 *
 * <p>§ <b>Idempotente</b>: un {@code install} repetido no añade meter dos veces ni
 * reemplaza un codec ya nuestro.
 *
 * <p>§ <b>Hilos</b>: toda mutación del pipeline ocurre <b>en el event loop</b> del canal
 * ({@code eventLoop().execute}). Eso la hace atómica respecto a los {@code write} q/ también
 * corren allí ⇒ no puede caber un paquete entre «quito el codec» y «pongo el nuevo».
 *
 * <p>§ <b>Límite conocido (N1)</b>: el hook es <b>server-side</b>. En el escenario Azorea
 * eso cubre justo lo q/ importa — <b>el host es quien manda los chunks</b> ⇒ el tráfico
 * grande (clientbound) pasa por nuestro codec. El serverbound (movimiento/chat, mínimo)
 * se comprime con el codec vanilla. *Pendiente si se quiere N1 completo en cliente.*
 */
public final class AzoreaNet {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzoreaNet.class);

    /** Nombre del meter en el pipeline — distinto d/ todo lo vanilla. */
    private static final String METER_NAME = "azorea_net_meter";

    /** Segundos entre informes d/ bytes en {@code AzoreaNetLog/NET}. */
    private static final int REPORT_INTERVAL_SECONDS = 10;

    private static int ticksSinceReport;

    private AzoreaNet() {
    }

    /**
     * Instala el meter y, si existe, sustituye el codec d/ compresión por el nuestro.
     *
     * <p>Llamar desde {@code RegisterConfigurationTasksEvent}. No hace nada (sin error)
     * si la conexión es null, el canal está cerrado o la optimización está desactivada.
     *
     * @param connection conexión c/ pipeline hay q/ tocar
     */
    public static void install(final Connection connection) {
        if (!AzoreaConfig.isNetOptEnabled() || connection == null) {
            return;
        }
        final Channel channel = connection.channel();
        if (channel == null || !channel.isOpen()) {
            LOGGER.debug("AzoreaNet: sin canal activo; se omite la instalación");
            return;
        }

        final int level = AzoreaConfig.getCompressionLevel();
        // § En el event loop ⇒ la mutación es atómica respecto a los writes.
        //   El resultado se loguea DENTRO d/ apply(): no podemos devolverlo aquí
        //   porque fuera d/ l'event loop sería asíncrono (siempre false = mentira).
        channel.eventLoop().execute(() -> {
            try {
                apply(channel.pipeline(), level);
            } catch (final RuntimeException e) {
                // § Nunca romper la conexión por una optimización: caemos a vanilla.
                LOGGER.warn("AzoreaNet: no pude instalar el codec (se usa vanilla): {}",
                        e.toString());
                AzoreaNetLog.failure(Category.NET, "install", "pipeline",
                        e.getClass().getSimpleName());
            }
        });
    }

    /**
     * Aplica los cambios al pipeline. Corre ya dentro del event loop.
     *
     * @return true si se instaló/ya estaba nuestro codec
     */
    private static boolean apply(final ChannelPipeline pipeline, final int level) {
        // § El meter SÓLO va en conexiones TCP de verdad. La conexión local in-memory
        //   (singleplayer, `LocalChannel`) no consume ancho de banda: medirla metía 20 MB
        //   d/ chunks locales en la métrica y hacía mentir el ratio —
        //   «raw 0 B → wire 20.36 MB (100.0%)» (bug visto 2026-10-05).
        //   § El filtro es x dirección: TCP ⇒ InetSocketAddress (incluye 127.0.0.1, q/ sí
        //   mide el loopback real d/ un server d/ pruebas); LocalAddress ⇒ sin meter.
        if (!(pipeline.channel().remoteAddress() instanceof InetSocketAddress)) {
            LOGGER.debug("AzoreaNet: conexión local in-memory ⇒ sin meter (no hay red q/ medir)");
            return false;
        }

        // 1) Meter en la cabeza — cuenta bytes reales d/ socket (ver AzoreaNetMeter).
        if (pipeline.get(METER_NAME) == null) {
            pipeline.addFirst(METER_NAME, new AzoreaNetMeter(AzoreaNetStats.global()));
        }

        // 2) Codec: sólo si vanilla ya instaló la compresión.
        final ChannelHandler existing = pipeline.get("compress");
        if (existing == null) {
            // § Compresión deshabilitada (network-compression-threshold = -1).
            //   No hay q/ optimizar; el meter sigue midiendo.
            LOGGER.info("AzoreaNet: sin handler 'compress' (compresión off) — sólo métrica");
            return false;
        }
        if (existing instanceof AzoreaNetCompressionEncoder) {
            return true;   // ya instalado (idempotente)
        }
        if (!(existing instanceof CompressionEncoder vanilla)) {
            LOGGER.warn("AzoreaNet: 'compress' no es CompressionEncoder ({}); se conserva vanilla",
                    existing.getClass().getName());
            return false;
        }

        final int threshold = vanilla.getThreshold();
        // § Mismo nombre ⇒ mismo hueco d/ pipeline y el `instanceof` d/ vanilla sigue
        //   funcionando si luego llama a setupCompression otra vez.
        pipeline.replace("compress", "compress",
                new AzoreaNetCompressionEncoder(threshold, level,
                        AzoreaNetStats.global()));

        AzoreaNetLog.milestone(Category.NET,
                "codec instalado", "nivel=" + level + " threshold=" + threshold);
        LOGGER.info("AzoreaNet: codec instalado (nivel={}, threshold={})", level, threshold);
        return true;
    }

    /**
     * Emite el informe d/ bytes periódico. Llamar desde {@code ServerTickEvent.Post}.
     *
     * <p>Sólo loguea si hubo tráfico ⇒ nunca spamea líneas vacías cuando nadie juega.
     */
    public static void onServerTick() {
        final int intervalTicks = REPORT_INTERVAL_SECONDS * 20;
        if (++ticksSinceReport < intervalTicks) {
            return;
        }
        ticksSinceReport = 0;

        final AzoreaNetStats stats = AzoreaNetStats.global();
        if (!stats.hasTraffic()) {
            return;
        }
        final AzoreaNetStats.Snapshot snap = stats.snapshotAndReset();
        AzoreaNetLog.info(Category.NET,
                snap.format(AzoreaConfig.getCompressionLevel()));
    }

    /** El nivel d/ compresión efectivamente configurado (para diagnóstico). */
    public static int configuredLevel() {
        return AzoreaConfig.getCompressionLevel();
    }
}
