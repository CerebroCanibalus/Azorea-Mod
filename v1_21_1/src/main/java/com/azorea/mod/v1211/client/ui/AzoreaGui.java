// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * § PORTEO — Shim d/ compatibilidad para el dibujado d/ texturas d/ GUI.
 *
 * <p>La firma d/ {@code GuiGraphics.blit} cambió en 1.21.2: ahora exige un
 * {@code Function<ResourceLocation, RenderType>} y el tamaño del PNG, y
 * {@code setColor} desapareció (el color pasó a ser un parámetro d/ {@code blit}).
 *
 * <p>Esta clase aísla esa diferencia para q/ {@link AzoreaTextureButton} y las
 * pantallas NO tengan q/ duplicarse por versión: llaman a estos métodos y cada
 * versión aporta su implementación (ésta es la d/ 1.21.1).
 */
public final class AzoreaGui {

    private AzoreaGui() {
    }

    /** Pinta una textura completa (u=v=0, sin tint) en (x,y) con tamaño (w,h). */
    public static void blitTexture(final GuiGraphics gui, final ResourceLocation texture,
                                   final int x, final int y, final int w, final int h,
                                   final int texW, final int texH) {
        gui.blit(texture, x, y, 0, 0, w, h, texW, texH);
    }

    /**
     * Pinta una textura con tint ARGB (0xAARRGGBB) escalado por {@code alphaScale}
     * (el fade d/ los widgets d/ vanilla).
     */
    public static void blitTextureTinted(final GuiGraphics gui, final ResourceLocation texture,
                                         final int x, final int y, final int w, final int h,
                                         final int texW, final int texH,
                                         final int argb, final float alphaScale) {
        final float a = ((argb >> 24) & 0xFF) / 255.0f * alphaScale;
        final float r = ((argb >> 16) & 0xFF) / 255.0f * alphaScale;
        final float g = ((argb >> 8) & 0xFF) / 255.0f * alphaScale;
        final float b = (argb & 0xFF) / 255.0f * alphaScale;
        gui.setColor(r, g, b, a);
        try {
            gui.blit(texture, x, y, 0, 0, w, h, texW, texH);
        } finally {
            // § setShaderColor es global — restaurar SIEMPRE.
            gui.setColor(1.0f, 1.0f, 1.0f, 1.0f);
        }
    }
}
