// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import com.azorea.mod.v1211.client.ui.AzoreaGui;

/**
 * Botón que renderiza una textura única (no sprite sheet).
 *
 * <p>§ Por qué custom:
 * <ul>
 *   <li>vanilla {@link net.minecraft.client.gui.components.ImageButton} usa
 *       {@link WidgetSprites} que espera 3 frames (enabled/disabled/hovered).</li>
 *   <li>Nuestros iconos son PNGs single-frame (host.png, friends.png).</li>
 *   <li>Renderizamos la textura directamente con {@code GuiGraphics.blit}.</li>
 * </ul>
 */
public final class AzoreaTextureButton extends AbstractButton {

    private final ResourceLocation texture;
    private final int textureWidth;
    private final int textureHeight;
    private final OnPress onPress;

    public AzoreaTextureButton(final int x, final int y, final int width, final int height,
                               final ResourceLocation texture,
                               final int textureWidth, final int textureHeight,
                               final OnPress onPress) {
        super(x, y, width, height, Component.empty());
        this.texture = texture;
        this.textureWidth = textureWidth;
        this.textureHeight = textureHeight;
        this.onPress = onPress;
    }

    @Override
    public void onPress() {
        onPress.onPress(this);
    }

    @Override
    protected void renderWidget(final GuiGraphics gui, final int mouseX, final int mouseY,
                                final float partialTicks) {
        // § Tint: hovered/disabled usan tints diferentes para feedback.
        int tint = 0xFFFFFFFF;
        if (!this.active) {
            tint = 0xFFA0A0A0;  // gris
        } else if (this.isHoveredOrFocused()) {
            tint = 0xFFFFFFA0;  // amarillo claro
        }
        // § FIX 2026-10-01 — tint en escala 0..1, ⊘ 0..255 (255.0f saturaba a
        //   blanco puro y mataba el feedback de estado). El tint se aplica dentro
        //   de AzoreaGui (setColor en 1.21.1, parámetro de color en 1.21.2+).
        AzoreaGui.blitTextureTinted(gui, texture, this.getX(), this.getY(),
                this.width, this.height, textureWidth, textureHeight, tint, this.alpha);
    }

    @Override
    protected void updateWidgetNarration(final NarrationElementOutput output) {
        // no narration (botón decorativo)
    }

    @FunctionalInterface
    public interface OnPress {
        void onPress(AzoreaTextureButton button);
    }
}
