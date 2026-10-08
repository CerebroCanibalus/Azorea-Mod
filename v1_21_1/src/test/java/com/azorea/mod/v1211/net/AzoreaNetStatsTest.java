// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests para {@link AzoreaNetStats} (ver AGENTS.md § DA-14, fase N1).
 *
 * <p>§ <b>Por qué importan</b>: este contador ES la métrica q/ decide si N2/N3 merecen
 * la pena. Si miente, decidimos a ciegas. Cubrimos sobretodo:
 * <ul>
 *   <li><b>sin división por cero</b> — un intervalo sin tráfico no debe reventar;</li>
 *   <li><b>snapshotAndReset</b> limpia TODO — si no, los deltas se acumulan y el
 *       informe miente sistemáticamente (bug q/ ya apareció en A1 con {@code blocked});</li>
 *   <li><b>ignora basura</b> — bytes ≤ 0 no deben corromper el ratio;</li>
 *   <li>el <b>ratio</b> es wire/raw, q/ es el número q/ se compara contra vanilla.</li>
 * </ul>
 *
 * <p>Sin MC ni red ⇒ siempre corren bajo {@code compileTestJava}.
 */
class AzoreaNetStatsTest {

    private static AzoreaNetStats fresh() {
        // § Los contadores son globales; cada test arranca limpio para no heredar
        //   tráfico d/ otros (si no, los asserts serían no-deterministas).
        return AzoreaNetStats.global();
    }

    private static void drain() {
        AzoreaNetStats.global().snapshotAndReset();
    }

    // ===== Acumulación =====

    @Test
    @DisplayName("acumula bytes de entrada, salida y raw por separado")
    void accumulatesCountersIndependently() {
        drain();
        final AzoreaNetStats s = fresh();

        s.addWireIn(100);
        s.addWireOut(40);
        s.addRawOut(200);
        s.addCompressedOut(35);
        s.incPacketsOut();
        s.incPacketsOut();

        final AzoreaNetStats.Snapshot snap = s.snapshotAndReset();
        assertEquals(100, snap.wireIn());
        assertEquals(40, snap.wireOut());
        assertEquals(200, snap.rawOut());
        assertEquals(35, snap.compressedOut());
        assertEquals(2, snap.packetsOut());
        drain();
    }

    @Test
    @DisplayName("snapshotAndReset pone TODOS los contadores a cero")
    void snapshotResetsEverything() {
        drain();
        final AzoreaNetStats s = fresh();
        s.addWireIn(10);
        s.addWireOut(20);
        s.addRawOut(30);
        s.addCompressedOut(40);
        s.incPacketsOut();
        s.addEncodeNanos(1_000);

        s.snapshotAndReset();                       // primer snapshot: se queda todo aquí
        final AzoreaNetStats.Snapshot second = s.snapshotAndReset();

        // § Si algo queda a cero mal, el informe acumularía Δ+Δ y mentiría.
        assertTrue(second.isEmpty(),
                "el 2º snapshot debe estar vacío, fue: " + second.format(9));
        assertEquals(0, second.wireIn());
        assertEquals(0, second.wireOut());
        assertEquals(0, second.rawOut());
        assertEquals(0, second.compressedOut());
        assertEquals(0, second.packetsOut());
        assertEquals(0, second.encodeNanos());
        drain();
    }

    @Test
    @DisplayName("ignora bytes ≤ 0 (basura no corrompe el ratio)")
    void ignoresNonPositiveBytes() {
        drain();
        final AzoreaNetStats s = fresh();
        s.addWireIn(-5);
        s.addWireOut(0);
        s.addRawOut(-1);
        s.addCompressedOut(0);
        s.addEncodeNanos(-99);

        final AzoreaNetStats.Snapshot snap = s.snapshotAndReset();
        assertEquals(0, snap.wireIn());
        assertEquals(0, snap.wireOut());
        assertEquals(0, snap.rawOut());
        assertEquals(0, snap.encodeNanos());
        assertTrue(snap.isEmpty());
        drain();
    }

    // ===== Ratio — el número q/ decide =====

    @Test
    @DisplayName("wireRatio = wireOut/rawOut y NO divide por cero")
    void wireRatioMath() {
        // 200 bytes crudos → 50 en la red = 25%
        final var s1 = new AzoreaNetStats.Snapshot(0, 50, 200, 45, 7, 0);
        assertEquals(0.25, s1.wireRatio(), 1e-9);
        assertEquals(4.0, s1.reductionFactor(), 1e-9);
        assertFalse(s1.isEmpty());

        // § Caso crítico: rawOut = 0 (sólo hubo tráfico entrante). Antes d/ la guarda
        //   esto era NaN/Infinity ⇒ el informe saldría "NaN%".
        final var s2 = new AzoreaNetStats.Snapshot(0, 10, 0, 0, 1, 0);
        assertEquals(1.0, s2.wireRatio(), 1e-9,
                "rawOut=0 debe devolver 1.0 (sin datos), no NaN");
        assertFalse(Double.isNaN(s2.wireRatio()));
    }

    @Test
    @DisplayName("compressión perfecta ⇒ ratio 0 y factor infinito, sin reventar")
    void perfectCompressionDoesNotExplode() {
        final var snap = new AzoreaNetStats.Snapshot(0, 0, 1000, 0, 5, 0);
        assertEquals(0.0, snap.wireRatio(), 1e-9);
        assertEquals(0.0, snap.reductionFactor(), 1e-9);
        assertFalse(snap.isEmpty(), "rawOut>0 cuenta como tráfico");
    }

    @Test
    @DisplayName("isEmpty: sólo cuenta wire y raw, no 'packets' aislados")
    void isEmptySemantics() {
        final var vacio = new AzoreaNetStats.Snapshot(0, 0, 0, 0, 0, 0);
        assertTrue(vacio.isEmpty());
        assertFalse(new AzoreaNetStats.Snapshot(5, 0, 0, 0, 0, 0).isEmpty());
        assertFalse(new AzoreaNetStats.Snapshot(0, 5, 0, 0, 0, 0).isEmpty());
        assertFalse(new AzoreaNetStats.Snapshot(0, 0, 5, 0, 0, 0).isEmpty());
    }

    // ===== Formato =====

    @Test
    @DisplayName("format: snapshot vacío dice 'sin tráfico' (no números)")
    void formatEmptySaysNoTraffic() {
        final var vacio = new AzoreaNetStats.Snapshot(0, 0, 0, 0, 0, 0);
        assertEquals("sin tráfico", vacio.format(9));
    }

    @Test
    @DisplayName("format incluye raw, wire, %, factor, pkt, entrada y nivel")
    void formatContainsAllFields() {
        // 13.94 MB crudos → 1.42 MB en red
        final long raw = 14_617_565L;
        final long wire = 1_489_000L;
        final var snap = new AzoreaNetStats.Snapshot(325_632L, wire, raw, wire - 500, 473, 0);

        final String out = snap.format(9);

        assertTrue(out.contains("raw "), "falta 'raw': " + out);
        assertTrue(out.contains("→ wire "), "falta 'wire': " + out);
        assertTrue(out.contains("%"), "falta ratio %: " + out);
        assertTrue(out.contains("473 pkt"), "falta pkt: " + out);
        assertTrue(out.contains("in "), "falta in: " + out);
        assertTrue(out.contains("lvl=9"), "falta nivel: " + out);
        assertTrue(out.contains("MB"), "falta unidad MB: " + out);
        // § El factor debe reflejar ~9.8x (14617565 / 1489000)
        assertTrue(out.contains("9.8x"), "fama factor: " + out);
    }

    @Test
    @DisplayName("human(): escalada de B a GB, 2 decimales, sin NaN")
    void humanScalesUnits() {
        assertEquals("512 B", AzoreaNetStats.Snapshot.human(512));
        assertEquals("1.00 KB", AzoreaNetStats.Snapshot.human(1024));
        assertEquals("1.50 KB", AzoreaNetStats.Snapshot.human(1536));
        assertEquals("1.00 MB", AzoreaNetStats.Snapshot.human(1024L * 1024L));
        assertEquals("1.00 GB", AzoreaNetStats.Snapshot.human(1024L * 1024L * 1024L));
        assertEquals("0 B", AzoreaNetStats.Snapshot.human(0));
    }

    @Test
    @DisplayName("sin codec (rawOut=0) ⇒ ⊘ ratio: un «100%» sería mentira")
    void formatWithoutCodecDoesNotClaimRatio() {
        // § Caso real 2026-10-05: conexión local s/ compresión ⇒ rawOut=0 y wireOut>0.
        //   Antes imprimía «raw 0 B → wire 20.36 MB (100.0% · 1.0x)» — el 100% no era
        //   «no hay nada q/ ganar», era «no había codec».
        final var snap = new AzoreaNetStats.Snapshot(0, 21_407_433L, 0, 0, 0, 0);
        final String out = snap.format(9);

        assertFalse(out.contains("%"), "no debe publicar ratio: " + out);
        assertFalse(out.contains("1.0x"), "no debe publicar factor: " + out);
        assertTrue(out.contains("s/ codec"), "debe explicar el porqué: " + out);
        assertTrue(out.contains("MB"), "sí debe decir los bytes d/ red: " + out);
    }

    @Test
    @DisplayName("encodeNanos se reporta como ms en el informe")
    void encodeTimeIsReported() {
        final var snap = new AzoreaNetStats.Snapshot(0, 100, 1000, 90, 3, 2_500_000L);
        assertTrue(snap.format(6).contains("cpu 2ms"),
                "2.5e6 ns = 2 ms: " + snap.format(6));
    }

    // ===== Hilos =====

    @Test
    @DisplayName("las acumulaciones concurrentes no se pierden")
    void concurrentAccumulationLosesNothing() throws Exception {
        drain();
        final AzoreaNetStats s = fresh();
        final int threads = 8;
        final int perThread = 5_000;
        final Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            workers[t] = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    s.addWireOut(1);
                    s.incPacketsOut();
                }
            });
        }
        for (final Thread w : workers) {
            w.start();
        }
        for (final Thread w : workers) {
            w.join(10_000);
        }

        final AzoreaNetStats.Snapshot snap = s.snapshotAndReset();
        final long expected = (long) threads * perThread;
        assertEquals(expected, snap.wireOut(), "wireOut perdido por condición de carrera");
        assertEquals(expected, snap.packetsOut(), "packets perdido por condición de carrera");
        drain();
    }
}
