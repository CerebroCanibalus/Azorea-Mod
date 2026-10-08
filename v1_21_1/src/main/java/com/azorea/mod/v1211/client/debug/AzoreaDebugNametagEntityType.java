// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client.debug;

import com.azorea.mod.AzoreaConstants;
import com.azorea.mod.v1211.AzoreaMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/**
 * § § § Registro del {@link EntityType} d/ la entity d/ debug (nametag fantasma).
 *
 * <p>§ § Lo registramos vía DeferredRegister en {@code modBus}. La categoría
 * MISC + createNothing son los flags correctos para una entity de "solo
 * cliente, sin IA, sin spawn natural, sin save data".
 */
@EventBusSubscriber(modid = AzoreaConstants.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class AzoreaDebugNametagEntityType {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, AzoreaConstants.MOD_ID);

    /**
     * § § § Usamos un factory q/ crea la entity vía el constructor estándar
     * (EntityType, Level). El Level q/ se pasa es el del cliente.
     */
    public static final Supplier<EntityType<AzoreaDebugNametagEntity>> DEBUG_NAMETAG =
            ENTITY_TYPES.register("debug_nametag",
                    () -> EntityType.Builder.<AzoreaDebugNametagEntity>of(
                            AzoreaDebugNametagEntity::new, MobCategory.MISC)
                            .sized(0.6F, 1.8F)
                            // § § § ANTES era 0 ⇒ la entity no se trackeaba al cliente
                            //   y nunca se renderizaba. 64 es el default d/ mobs.
                            .clientTrackingRange(64)
                            .updateInterval(Integer.MAX_VALUE)
                            .noSave()
                            .build(ResourceLocation.fromNamespaceAndPath(
                                    AzoreaConstants.MOD_ID, "debug_nametag").toString()));

    private AzoreaDebugNametagEntityType() {
    }

    /**
     * § § § Helper p/ acceder al EntityType resuelto. Cacheado en un static
     * volatile p/ q/ el cliente lo obtenga sin pasar por el Supplier
     * (q/ sólo se resuelve una vez pasado el mod loading).
     */
    private static volatile EntityType<AzoreaDebugNametagEntity> resolved;

    public static EntityType<AzoreaDebugNametagEntity> get() {
        if (resolved == null) {
            resolved = DEBUG_NAMETAG.get();
        }
        return resolved;
    }

    @SubscribeEvent
    public static void onEntityAttributeCreation(final EntityAttributeCreationEvent event) {
        // § § § No tiene atributos (es un marker entity sin IA). Sin este
        //   registro algunas versiones d/ NeoForge lanzan warning al spawnear.
    }
}
