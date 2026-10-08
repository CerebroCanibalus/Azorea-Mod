// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client.debug;

import com.azorea.mod.AzoreaConstants;
import com.azorea.mod.v1211.client.AzoreaNametag;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * § § § Renderer minimalista p/ el {@link AzoreaDebugNametagEntity}.
 *
 * <p>§ § ANTES (1.4.1 inicial) tenía blit d/ cabeza Steve 3D + nametag c/
 * rotaciones complejas. No se veía nada en el cliente ⇒ sospechoso de
 * `clientTrackingRange(0)` (la entity no se trackeaba).
 *
 * <p>§ § AHORA (1.4.1 hotfix): dibujo SÓLO el nametag como billboard (siempre
 * mirando a la cámara), sin modelo 3D. Si funciona ⇒ el bug era de tracking;
 * si no funciona ⇒ el bug es del spawn en sí.
 */
@EventBusSubscriber(modid = AzoreaConstants.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class AzoreaDebugNametagRenderer extends EntityRenderer<AzoreaDebugNametagEntity> {

    /** § § § Skin Steve — la mantenemos para el siguiente paso (blit d/ cabeza). */
    private static final ResourceLocation STEVE_SKIN =
            ResourceLocation.withDefaultNamespace("textures/entity/player/wide/steve.png");

    public AzoreaDebugNametagRenderer(final EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(final AzoreaDebugNametagEntity entity) {
        return STEVE_SKIN;
    }

    @Override
    public void render(final AzoreaDebugNametagEntity entity, final float entityYaw,
                      final float partialTick, final PoseStack poseStack,
                      final MultiBufferSource bufferSource, final int packedLight) {
        // § § § 1) Cabeza: blit 8x8 d/ Steve en el frame d/ la entity.
        // § § § FIX 1.4.8: el `scale(-1, -1, 1)` hacía q/ el quad quedara
        //   mirando -Z (espalda al camera) ⇒ backface culling lo ocultaba
        //   cuando mirabas d/ frente. Quito el flip — el quad queda mirando
        //   +Z, q/ es hacia el camera en el render d/ MC.
        poseStack.pushPose();
        poseStack.translate(0.0D, 1.9D, 0.0D);
        drawHeadQuad(poseStack, bufferSource, packedLight);
        poseStack.popPose();

        // § § § 2) Nametag encima — billboard.
        if (entity.isSneaking()) return;

        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(-entityYaw));
        poseStack.translate(0.0D, 0.6D, 0.0D);
        // § § § FIX 1.4.8: el segundo `scale(-1, -1, 1)` creaba un mirror
        //   fantasma. vanilla NO aplicó un mirror implícito en este contexto
        //   (EntityRenderer d/ entity genérico no flipa), así q/ el segundo
        //   flip estaba de más ⇒ el text salía al revés (letras espejadas,
        //   artefactos `¡§<`). Quito el flip extra — sólo el scale d/ tamaño.
        poseStack.scale(-0.025F, -0.025F, 0.025F);

        final String bucket = AzoreaNametag.pingBucket(entity.getLatencyMs());
        final String dot = switch (bucket) {
            case "good" -> "§a●";
            case "ok" -> "§e●";
            default -> "§c●";
        };
        final String bodyStr = entity.getAzoreaNametagName() + "  " + dot;
        final Font font = Minecraft.getInstance().font;

        // § § § NO MÁS poseStack.scale(-1, -1, 1) aquí. Lo quito en 1.4.8.
        final var matrix4f = poseStack.last().pose();
        font.drawInBatch(
                bodyStr,
                -font.width(bodyStr) / 2.0F,
                0.0F,
                0xFFFFFFFF,
                /*dropShadow=*/true,
                matrix4f,
                bufferSource,
                Font.DisplayMode.NORMAL,
                /*backgroundColor=*/0,
                packedLight);
        poseStack.popPose();
    }

    /**
     * § § § FIX 1.4.7 (hotfix d/ 1.4.5): el blit d/ la cabeza Steve crasheaba
     *   con "Missing elements in vertex: UV2, Normal" pq/ el VertexConsumer
     *   1.21.1 con {@link RenderType#entityCutout} usa el formato
     *   {@code DefaultVertexFormat.NEW_ENTITY} (POSITION, COLOR, UV0, UV1,
     *   UV2, NORMAL). Yo pasaba sólo los primeros 4 ⇒ BufferBuilder.endLastVertex
     *   lanza al cerrar el batch.
     *
     *   <p>Solución: usar {@code addVertex(Matrix4f, ...)} (transforma con la
     *   matrix d/ pose), y llamar también {@code setUv2(0, 0)} (overlay — sin
     *   daño flash u otros efectos) y {@code setNormal(Matrix3f, 0, 0, 1)} (la
     *   cara está en el plano XY ⇒ normal local +Z).
     *
     *   <p>Para UV1, MC ya pasaba packedLight como {@code setUv1(blockPos, skyPos)}
     *   antes, pero en 1.21.1 toma shorts — aquí lo divido para estar seguros.
     */
    private static void drawHeadQuad(final PoseStack pose, final MultiBufferSource buffers,
                                     final int packedLight) {
        final float u0 = 8.0f / 64.0f;
        final float v0 = 8.0f / 64.0f;
        final float u1 = 16.0f / 64.0f;
        final float v1 = 16.0f / 64.0f;
        final VertexConsumer vc = buffers.getBuffer(RenderType.entityCutout(STEVE_SKIN));
        // § § § FIX 1.4.8: head quad usaba h=8 (pixels) en world-units ⇒ salía
        //   un cuadrado de 8 BLOQUES d/ ancho, por eso veías un bloque gigante
        //   ocupando la pantalla. 8 px @ 16 px/block = 0.5 blocks.
        final float h = 0.5f;
        final var m4f = pose.last().pose();
        // § § § packedLight viene como (skyLight << 16) | blockLight ⇒ los dos
        //   canales son shorts. UV1 los toma (blockLight, skyLight) — orden
        //   importante para que la iluminación del block coincida con la del
        //   chunk donde está la entity.
        final int blockLight = packedLight & 0xFFFF;
        final int skyLight = (packedLight >> 16) & 0xFFFF;
        vc.addVertex(m4f, -h / 2.0F, h, 0.0F).setColor(255, 255, 255, 255)
                .setUv(u0, v1).setUv1(blockLight, skyLight).setUv2(0, 0)
                .setNormal(pose.last(), 0.0F, 0.0F, 1.0F);
        vc.addVertex(m4f, h / 2.0F, h, 0.0F).setColor(255, 255, 255, 255)
                .setUv(u1, v1).setUv1(blockLight, skyLight).setUv2(0, 0)
                .setNormal(pose.last(), 0.0F, 0.0F, 1.0F);
        vc.addVertex(m4f, h / 2.0F, 0.0F, 0.0F).setColor(255, 255, 255, 255)
                .setUv(u1, v0).setUv1(blockLight, skyLight).setUv2(0, 0)
                .setNormal(pose.last(), 0.0F, 0.0F, 1.0F);
        vc.addVertex(m4f, -h / 2.0F, 0.0F, 0.0F).setColor(255, 255, 255, 255)
                .setUv(u0, v0).setUv1(blockLight, skyLight).setUv2(0, 0)
                .setNormal(pose.last(), 0.0F, 0.0F, 1.0F);
    }

    @SubscribeEvent
    public static void registerEntityRenderers(final EntityRenderersEvent.RegisterRenderers event) {
        // § § § 1.4.10: el debug entity SIEMPRE se registra. Lo experimental
        //   es el AzoreaNametagRenderer (over real players), controlado por
        //   [ui].nametag_overlay en azorea.toml. Este renderer es para
        //   el comando /azorea nametag spawn (debug), q/ queda público.
        //   Bugs conocidos del renderer (ver AGENTS.md): head quad mis-sized
        //   desde ciertos ángulos, texto espejado. Cuando se arregle, este
        //   código sigue cableado.
        event.registerEntityRenderer(
                AzoreaDebugNametagEntityType.DEBUG_NAMETAG.get(),
                AzoreaDebugNametagRenderer::new);
    }
}
