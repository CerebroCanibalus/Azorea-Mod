// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client;

import com.azorea.mod.v1211.AzoreaConfig;
import com.azorea.mod.v1211.AzoreaLang;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;
import net.neoforged.neoforge.common.util.TriState;

/**
 * § Nametag Azorea — overlay con cabeza + nombre + dot de ping.
 *
 * <p><b>Aspecto</b> (cuando {@code [ui] nametag_overlay = true}):
 * <pre>
 *   [HEAD 8x8]   [Nombre del jugador]   [●]
 *                                ↑
 *                       verde ≤80 ms · amarillo ≤400 · rojo &gt;400
 * </pre>
 *
 * <p><b>Sneak ⇒ todo desaparece</b> (decisión d/ General 2026-10-07 — comportamiento
 * Quake / competitivo). Ni cabeza, ni nombre, ni dot.
 *
 * <p><b>Cómo encaja con vanilla</b>: cancelamos el nametag vanilla
 * ({@code setCanRender(FALSE)}) y dibujamos el nuestro sobre el mismo
 * {@link PoseStack} d/ el evento:
 * <ol>
 *   <li>un quad 8x8 d/ la skin del jugador vía {@link VertexConsumer};</li>
 *   <li>nombre + dot d/ ping vía {@link Font#drawInBatch} con formatting codes
 *       d/ color (verde/amarillo/rojo).</li>
 * </ol>
 *
 * <p><b>Scope</b>: sólo jugadores remotos. El jugador local no tiene
 * {@code PlayerInfo} en su propio ClientPacketListener (vanilla lo omite).
 *
 * <p>§ § <b>Estado</b>: la lógica pura (el bucket d/ color según ping) vive en
 * {@link AzoreaNametag} para q/ se pueda testear bajo {@code compileTestJava}
 * (ésta clase no se puede cargar en tests — depende d/ {@code RenderNameTagEvent}).
 */
public final class AzoreaNametagRenderer {

    private AzoreaNametagRenderer() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderNameTag(final RenderNameTagEvent event) {
        if (!AzoreaConfig.UI_NAMETAG_OVERLAY.get()) {
            return;
        }
        final var entity = event.getEntity();
        if (!(entity instanceof Player player)) {
            return;
        }
        // § § Sólo remotos — el jugador local no tiene PlayerInfo en su propio
        //   ClientPacketListener (vanilla lo omite en el TAB list).
        final var conn = Minecraft.getInstance().getConnection();
        if (conn == null) {
            return;
        }
        final var info = conn.getPlayerInfo(player.getUUID());
        if (info == null) {
            return;
        }

        // § § § Sneak ⇒ NADA. Decisión d/ General — comportamiento Quake / competitivo.
        if (player.isCrouching()) {
            event.setCanRender(TriState.FALSE);
            return;
        }

        // § § § Cancela el vanilla — nosotros pintamos todo.
        event.setCanRender(TriState.FALSE);

        final PoseStack pose = event.getPoseStack();
        final MultiBufferSource buffers = event.getMultiBufferSource();
        final int packedLight = event.getPackedLight();
        final Font font = Minecraft.getInstance().font;
        // § § PlayerSkin es un record ⇒ getSkin().texture() para llegar a la ResourceLocation.
        final ResourceLocation skinTexture = info.getSkin().texture();
        final Font.DisplayMode textDisplay = Font.DisplayMode.NORMAL;

        // § § § § 1) Cabeza: blit quad 8x8 d/ la skin d/ head.
        pose.pushPose();
        pose.translate(0.0D, 0.25D, 0.0D);
        pose.scale(-1.0F, -1.0F, 1.0F);   // § invierte el mirror q/ aplica vanilla al texto
        blitHead(pose, buffers, packedLight, skinTexture);
        // § § Tras dibujar la cabeza, movemos el cursor a la derecha d/ la misma.
        pose.translate(-AzoreaNametag.HEAD_PIXELS - AzoreaNametag.HEAD_TEXT_GAP, 0.0D, 0.0D);
        // § § § § 2) Nombre + dot: render manual con Font.drawInBatch + RenderType.text.
        // § § § El renderNameTag d/ vanilla es protected, así q/ lo replicamos con
        //   el Font directamente. El formatting code (§a/§c/§4) del dot sale gratis.
        final String bodyStr = player.getGameProfile().getName() + "  "
                + dotChar(AzoreaNametag.pingBucket(info.getLatency()));
        // § § drawInBatch pinta d/ izquierda a derecha; queremos q/ la cola d/ la
        //   cabeza sea el inicio ⇒ un offset d/ 0 px (ya estamos a HEAD_PIXELS+GAP).
        // § § El scale -1,-1,-1 ya invirtió la matriz; el Font pintará "del revés" si
        //   no compensamos. Deshacemos el scale SOLO para el texto.
        pose.scale(-1.0F, -1.0F, 1.0F);   // § deshace el mirror — el texto va en escala 1
        // § Necesita Matrix4f actualizada tras deshacer el scale.
        final var matrix4fText = pose.last().pose();
        font.drawInBatch(
                bodyStr,
                0.0F, 0.0F,
                0xFFFFFFFF,
                /*dropShadow=*/false,
                matrix4fText,
                buffers,
                textDisplay,
                /*backgroundColor=*/0,
                packedLight);
        pose.popPose();
    }

    /**
     * § § § Construye el carácter del dot coloreado.
     *
     * <p>Aquí usamos {@code String} con formatting code en vez d/ pasar por
     * {@link AzoreaLang} — el dot es literal (§a/§e/§c + ●) y NO cambia entre
     * idiomas; convertirlo en clave de i18n sólo añadiría indirección.
     */
    private static String dotChar(final String bucket) {
        return switch (bucket) {
            case "good" -> "§a●";
            case "ok" -> "§e●";
            default -> "§c●";
        };
    }

    /**
     * § § Dibuja la cabeza d/ 8x8 d/ la skin d/ head del jugador.
     *
     * <p>§ § Técnica: el quad se construye con {@link VertexConsumer} usando el
     * {@link RenderType#entityCutout} q/ vanilla usa para el cuerpo. Las UV son
     * las 8x8 d/ la cabeza dentro de la skin completa (64x64).
     */
    private static void blitHead(final PoseStack pose, final MultiBufferSource buffers,
                                 final int packedLight, final ResourceLocation skin) {
        // § § UV en la skin: la cara es la región (8,8)→(16,16) en una skin 64x64.
        final float u0 = 8.0f / 64.0f;
        final float v0 = 8.0f / 64.0f;
        final float u1 = 16.0f / 64.0f;
        final float v1 = 16.0f / 64.0f;
        final VertexConsumer vc = buffers.getBuffer(RenderType.entityCutout(skin));
        // § § § API 1.21.1: VertexConsumer usa addVertex + setColor/setUv/setUv1.
        //   La transformación va por el PoseStack, no por la Matrix4f.
        vc.addVertex(0.0f, AzoreaNametag.HEAD_PIXELS, 0.0f).setColor(255, 255, 255, 255)
                .setUv(u0, v1).setUv1(packedLight, packedLight);
        vc.addVertex(AzoreaNametag.HEAD_PIXELS, AzoreaNametag.HEAD_PIXELS, 0.0f).setColor(255, 255, 255, 255)
                .setUv(u1, v1).setUv1(packedLight, packedLight);
        vc.addVertex(AzoreaNametag.HEAD_PIXELS, 0.0f, 0.0f).setColor(255, 255, 255, 255)
                .setUv(u1, v0).setUv1(packedLight, packedLight);
        vc.addVertex(0.0f, 0.0f, 0.0f).setColor(255, 255, 255, 255)
                .setUv(u0, v0).setUv1(packedLight, packedLight);
    }

    // § § § Constantes delegadas a {@link AzoreaNametag} (pura, testeable).
    public static final int PING_GOOD_THRESHOLD_MS = AzoreaNametag.PING_GOOD_MS;
    public static final int PING_OK_THRESHOLD_MS = AzoreaNametag.PING_OK_MS;
    public static final int HEAD_SIZE = AzoreaNametag.HEAD_PIXELS;
    public static final int GAP = AzoreaNametag.HEAD_TEXT_GAP;
}