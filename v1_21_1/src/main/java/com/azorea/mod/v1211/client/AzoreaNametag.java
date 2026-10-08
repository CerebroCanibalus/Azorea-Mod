// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client;

/**
 * § § Lógica pura del nametag Azorea — separable del resto de la clase para
 * testear sin cargar {@code RenderNameTagEvent} (q/ no está en compileTestJava).
 *
 * <p>Sólo dos métodos sin estado — se podría llamar "Service" o "Util", pero
 * "Nametag" deja claro q/ esto pertenece al subsistema del mismo nombre.
 */
public final class AzoreaNametag {

    private AzoreaNametag() {
    }

    // § § Umbrales en ms — sincronizados con vanilla F3 debug.
    public static final int PING_GOOD_MS = 80;
    public static final int PING_OK_MS = 400;

    /** § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § §
     * § Decide el bucket de color del dot de ping.
     *
     * <p>§ § § INPUT: rango de ping medido por vanilla ({@code getLatency}).
     *   OUTPUT: {@code "good"} / {@code "ok"} / {@code "bad"} (vanilla debug).
     */
    public static String pingBucket(final int latencyMs) {
        if (latencyMs <= PING_GOOD_MS) {
            return "good";
        }
        if (latencyMs <= PING_OK_MS) {
            return "ok";
        }
        return "bad";
    }

    /** § § § Tamaño d/ la cabeza en píxeles nativos (8x8). */
    public static final int HEAD_PIXELS = 8;
    /** § § Separación entre cabeza y nombre — 4 px es lo q/ usa vanilla. */
    public static final int HEAD_TEXT_GAP = 4;
}