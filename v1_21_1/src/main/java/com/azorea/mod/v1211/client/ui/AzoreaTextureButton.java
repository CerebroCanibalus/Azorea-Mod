// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

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
        // § FIX 2026-10-01 — tint en escala 0..1, ⊘ 0..255.
        //
        // GuiGraphics tiene UNA sola sobrecarga:
        //     public void setColor(float, float, float, float)
        //         → RenderSystem.setShaderColor(r, g, b, a)
        // Pasarle (tint >> 16) & 0xFF = 255 ensanchaba a 255.0f ⇒ el fragment
        // shader calculaba texel × 255 ⇒ TODO saturaba a blanco puro (el alfa se
        // salvaba: 0 × 255 = 0, por eso quedaba una silueta blanca recortada).
        // Síntoma real: "los íconos están totalmente blancos, no se ve su textura".
        //
        // Con 255.0f el disabled (0xA0) y el hover (0xA0 azul) también salían
        // blancos — 0.63 × 255 = 160 → clamp 1.0 — con lo que desaparecía
        // cualquier feedback de estado.
        gui.setColor(
                ((tint >> 16) & 0xFF) / 255.0f * this.alpha,
                ((tint >> 8) & 0xFF) / 255.0f * this.alpha,
                (tint & 0xFF) / 255.0f * this.alpha,
                ((tint >> 24) & 0xFF) / 255.0f * this.alpha);
        gui.blit(texture, this.getX(), this.getY(),
                this.width, this.height,
                0, 0, textureWidth, textureHeight, textureWidth, textureHeight);
        // § Restaurar SIEMPRE: setShaderColor es global y se filtra al resto del
        // GUI si se deja tocado (p. ej. si blit lanzara).
        gui.setColor(1.0f, 1.0f, 1.0f, 1.0f);
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
