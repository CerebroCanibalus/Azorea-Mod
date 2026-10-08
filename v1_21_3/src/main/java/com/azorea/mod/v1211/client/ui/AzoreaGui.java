// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * § PORTEO — Shim d/ GUI para 1.21.2/1.21.3.
 *
 * <p>En 1.21.2 {@code GuiGraphics.blit} pasó a exigir un
 * {@code Function<ResourceLocation, RenderType>} y el tamaño del PNG, y
 * {@code setColor} desapareció (el color es ahora un parámetro d/ {@code blit}).
 * Ver la versión d/ 1.21.1 en {@code v1_21_1/.../client/ui/AzoreaGui.java}.
 */
public final class AzoreaGui {

    private AzoreaGui() {
    }

    /** Pinta una textura completa (u=v=0, sin tint) en (x,y) con tamaño (w,h). */
    public static void blitTexture(final GuiGraphics gui, final ResourceLocation texture,
                                   final int x, final int y, final int w, final int h,
                                   final int texW, final int texH) {
        gui.blit(RenderType::guiTextured, texture, x, y, 0f, 0f, w, h, texW, texH, 0xFFFFFFFF);
    }

    /**
     * Pinta una textura con tint ARGB (0xAARRGGBB) escalado por {@code alphaScale}
     * (el fade d/ los widgets d/ vanilla).
     */
    public static void blitTextureTinted(final GuiGraphics gui, final ResourceLocation texture,
                                         final int x, final int y, final int w, final int h,
                                         final int texW, final int texH,
                                         final int argb, final float alphaScale) {
        final int a = clamp(((argb >> 24) & 0xFF) * alphaScale);
        final int r = clamp(((argb >> 16) & 0xFF) * alphaScale);
        final int g = clamp(((argb >> 8) & 0xFF) * alphaScale);
        final int b = clamp((argb & 0xFF) * alphaScale);
        final int color = (a << 24) | (r << 16) | (g << 8) | b;
        gui.blit(RenderType::guiTextured, texture, x, y, 0f, 0f, w, h, texW, texH, color);
    }

    private static int clamp(final float v) {
        final int i = Math.round(v);
        return i < 0 ? 0 : Math.min(i, 255);
    }
}
