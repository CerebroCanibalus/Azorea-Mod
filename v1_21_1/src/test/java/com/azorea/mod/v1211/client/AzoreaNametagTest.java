// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * § Tests para la lógica pura del nametag Azorea — viven en {@link AzoreaNametag}
 * (separada d/ {@code AzoreaNametagRenderer} para q/ la clase q/ depende de
 * {@code RenderNameTagEvent} no se cargue en el classpath d/ tests).
 *
 * <p>Lo q/ <b>no</b> se testea aquí: el blit d/ la cabeza con {@code VertexConsumer}
 * — requiere las clases d/ MC q/ no están en compileTestJava.
 */
class AzoreaNametagTest {

    @Test
    @DisplayName("umbral good = 80 ms (inclusive)")
    void thresholdGood() {
        assertEquals("good", AzoreaNametag.pingBucket(0));
        assertEquals("good", AzoreaNametag.pingBucket(80));
    }

    @Test
    @DisplayName("umbral ok = 400 ms (inclusive)")
    void thresholdOk() {
        assertEquals("ok", AzoreaNametag.pingBucket(81));
        assertEquals("ok", AzoreaNametag.pingBucket(400));
    }

    @Test
    @DisplayName("> 400 ms ⇒ bad (rojo)")
    void thresholdBad() {
        assertEquals("bad", AzoreaNametag.pingBucket(401));
        assertEquals("bad", AzoreaNametag.pingBucket(1000));
        assertEquals("bad", AzoreaNametag.pingBucket(50000));
    }

    @Test
    @DisplayName("§ § Las constantes públicas son las q/ el código quiere ⇒ sin magic numbers")
    void publicConstantsMatch() {
        assertEquals(80, AzoreaNametag.PING_GOOD_MS);
        assertEquals(400, AzoreaNametag.PING_OK_MS);
        assertEquals(8, AzoreaNametag.HEAD_PIXELS);
        assertTrue(AzoreaNametag.HEAD_TEXT_GAP > 0);
    }
}