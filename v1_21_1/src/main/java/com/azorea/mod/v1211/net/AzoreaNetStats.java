// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.net;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Contadores de red de {@code AzoreaNet} (§ DA-14, fase <b>N1</b>).
 *
 * <p>§ <b>Por qué existe</b>: hasta ahora el efecto de cualquier optimización era una
 * opinión. Aquí se convierte en un número — <i>«raw 13.94 MB → wire 1.42 MB»</i> — que
 * es lo único q/ permite decidir si la fase siguiente (N2/N3) merece la pena.
 *
 * <p>§ <b>Qué mide cada contador</b>:
 * <ul>
 *   <li>{@code rawOut} — bytes <b>sin comprimir</b> de los paquetes q/ entran al codec.
 *       Es el denominador d/ la comparación A/B: <b>no cambia</b> entre corridas c/ la
 *       misma escenario ⇒ si rawOut difiere entre corridas, la comparación no es válida.</li>
 *   <li>{@code compressedOut} — bytes q/ produce el deflate (sin el prefijo varint).</li>
 *   <li>{@code wireOut} — bytes <b>reales en el socket</b> (varint + deflate + cifrado).
 *       Es el número q/ importa: lo q/ consume el amigo d/ ancho de banda.</li>
 *   <li>{@code wireIn} — bytes entrantes reales.</li>
 * </ul>
 *
 * <p>§ <b>Por q/ atómicos y globales</b>: los cuenta Netty en su event loop (uno por
 * conexión), así q/ pueden llegar de hilos distintos. El agregado global basta para la
 * medición d/ N1 — típicamente hay 1 jugador remoto. <b>Limitación conocida</b>: con
 * varios jugadores los totales se mezclan; por-conexión queda p/ N2 si hace falta.
 *
 * <p>§ <b>Sin dependencias d/ MC</b> — es lógica pura y por eso es testeable bajo
 * {@code compileTestJava}, q/ no ve clases d/ Minecraft (ver DESCUBRIMIENTOS.md).
 */
public final class AzoreaNetStats {

    /** Instancia única usada por el codec y el meter. */
    private static final AzoreaNetStats GLOBAL = new AzoreaNetStats();

    private final AtomicLong wireIn = new AtomicLong();
    private final AtomicLong wireOut = new AtomicLong();
    private final AtomicLong rawOut = new AtomicLong();
    private final AtomicLong compressedOut = new AtomicLong();
    private final AtomicLong packetsOut = new AtomicLong();
    private final AtomicLong encodeNanos = new AtomicLong();

    private AzoreaNetStats() {
    }

    public static AzoreaNetStats global() {
        return GLOBAL;
    }

    public void addWireIn(final long bytes) {
        if (bytes > 0) {
            wireIn.addAndGet(bytes);
        }
    }

    public void addWireOut(final long bytes) {
        if (bytes > 0) {
            wireOut.addAndGet(bytes);
        }
    }

    public void addRawOut(final long bytes) {
        if (bytes > 0) {
            rawOut.addAndGet(bytes);
        }
    }

    public void addCompressedOut(final long bytes) {
        if (bytes > 0) {
            compressedOut.addAndGet(bytes);
        }
    }

    public void incPacketsOut() {
        packetsOut.incrementAndGet();
    }

    /** Tiempo d/ CPU gastado en comprimir, en nanosegundos. */
    public void addEncodeNanos(final long nanos) {
        if (nanos > 0) {
            encodeNanos.addAndGet(nanos);
        }
    }

    /** ¿Ha habido tráfico desde el último snapshot? Evita loguear líneas vacías. */
    public boolean hasTraffic() {
        return wireOut.get() > 0 || wireIn.get() > 0 || rawOut.get() > 0;
    }

    /** Lee y pone a cero todos los contadores. */
    public Snapshot snapshotAndReset() {
        final Snapshot s = new Snapshot(
                wireIn.getAndSet(0),
                wireOut.getAndSet(0),
                rawOut.getAndSet(0),
                compressedOut.getAndSet(0),
                packetsOut.getAndSet(0),
                encodeNanos.getAndSet(0));
        return s;
    }

    /** Instantánea inmutable d/ un intervalo d/ medición. */
    public record Snapshot(long wireIn, long wireOut, long rawOut, long compressedOut,
                           long packetsOut, long encodeNanos) {

        /**
         * Fracción d/ bytes d/ red respecto d/ lo q/ pesaban los paquetes crudos.
         *
         * @return 0..1; {@code 1} si no hay datos (evita división por cero)
         */
        public double wireRatio() {
            return rawOut <= 0 ? 1.0 : (double) wireOut / (double) rawOut;
        }

        /** Cuántas veces más pequeños son los bytes d/ red q/ el original. */
        public double reductionFactor() {
            final double r = wireRatio();
            return r <= 0.0 ? 0.0 : 1.0 / r;
        }

        /** ¿Este intervalo registró algo d/ medible? */
        public boolean isEmpty() {
            return wireOut == 0 && wireIn == 0 && rawOut == 0;
        }

        /**
         * Línea d/ informe p/ {@code AzoreaNetLog/NET}.
         *
         * <p>Ejemplo: {@code raw 13.94 MB → wire 1.42 MB (10.3% · 9.8x) · 473 pkt · in 318 KB}
         *
         * @param compressionLevel nivel deflate usado (6 = vanilla, 9 = máximo)
         */
        public String format(final int compressionLevel) {
            if (isEmpty()) {
                return "sin tráfico";
            }
            // § Sin codec instalado (compresión off / umbral −1): rawOut es 0 y el ratio
            //   saldría «100%» — q/ sería mentira, pq no se ha comprimido nada porque no
            //   había codec, no pq no hubiera nada q/ ganar. Preferimos no publicar ratio.
            if (rawOut <= 0) {
                return "wire " + human(wireOut)
                        + " · s/ codec (compresión off)"
                        + " · in " + human(wireIn);
            }
            return "raw " + human(rawOut)
                    + " → wire " + human(wireOut)
                    + String.format(Locale.ROOT, " (%.1f%% · %.1fx)",
                    wireRatio() * 100.0, reductionFactor())
                    + " · " + packetsOut + " pkt"
                    + " · in " + human(wireIn)
                    + " · lvl=" + compressionLevel
                    + " · cpu " + encodeNanos / 1_000_000L + "ms";
        }

        /** Formato legible d/ bytes (B/KB/MB/GB), 2 decimales. */
        static String human(final long bytes) {
            if (bytes < 1024L) {
                return bytes + " B";
            }
            double value = bytes;
            int unit = 0;
            final String[] units = {"B", "KB", "MB", "GB"};
            while (value >= 1024.0 && unit < units.length - 1) {
                value /= 1024.0;
                unit++;
            }
            return String.format(Locale.ROOT, "%.2f %s", value, units[unit]);
        }
    }
}
