// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client;

import com.azorea.mod.v1211.AzoreaCommand;
import com.azorea.mod.v1211.AzoreaLang;
import com.azorea.mod.v1211.client.debug.AzoreaDebugNametagEntity;
import com.azorea.mod.v1211.client.debug.AzoreaDebugNametagEntityType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import org.slf4j.LoggerFactory;

/**
 * § PORTEO — Facade del feature d/ debug (nametag fantasma).
 *
 * <p>El registro del {@code EntityType} y el manejo del comando {@code /azorea nametag}
 * viven aquí en vez de en {@link AzoreaMod}/{@link AzoreaClient} para que el resto
 * del mod NO dependa d/ las clases d/ debug. En 1.21.2+ {@code EntityRenderer} pasó
 * al modelo d/ <i>render state</i> (rework real) ⇒ la versión d/ 1.21.3 aporta un
 * stub no-op d/ esta clase y excluye el paquete {@code client/debug}.
 */
public final class AzoreaDebug {

    private AzoreaDebug() {
    }

    /** Registra el EntityType d/ debug en el mod bus. */
    public static void register(final IEventBus modBus) {
        AzoreaDebugNametagEntityType.ENTITY_TYPES.register(modBus);
    }

    /**
     * Maneja la pending request del comando {@code /azorea nametag}.
     * Singleplayer only — en multiplayer dedicated el comando no aplica.
     */
    public static void handleRequest(final Minecraft mc, final AzoreaCommand.PendingNametagRequest req) {
        if (mc.getSingleplayerServer() == null) {
            mc.gui.getChat().addMessage(
                    AzoreaLang.text("command.nametag_spawn_failed", "singleplayer only"));
            return;
        }
        final Level level = mc.level;
        if (level == null) {
            mc.gui.getChat().addMessage(
                    AzoreaLang.text("command.nametag_spawn_failed", "no world loaded"));
            return;
        }
        final EntityType<AzoreaDebugNametagEntity> type = AzoreaDebugNametagEntityType.get();
        if (type == null) {
            mc.gui.getChat().addMessage(
                    AzoreaLang.text("command.nametag_spawn_failed", "entity type not registered"));
            LoggerFactory.getLogger(AzoreaDebug.class)
                    .warn("[azorea/debug] EntityType es null — DeferredRegister no resolvió");
            return;
        }
        LoggerFactory.getLogger(AzoreaDebug.class)
                .info("[azorea/debug] handleRequest op={} name={} latency={}ms sneaking={} clientLevel={}",
                        req.op, req.name, req.latency, req.sneaking, level.getClass().getSimpleName());
        switch (req.op) {
            case SPAWN -> {
                final var existing = findExisting(level);
                if (existing != null) {
                    existing.azorea$setData(req.name, req.latency, req.sneaking);
                    mc.gui.getChat().addMessage(
                            AzoreaLang.text("command.nametag_configured", req.latency, req.sneaking));
                } else {
                    final var e = AzoreaDebugNametagEntity.spawnInFrontOf(mc.player, type);
                    if (e == null) {
                        mc.gui.getChat().addMessage(
                                AzoreaLang.text("command.nametag_spawn_failed", "spawn returned null"));
                        return;
                    }
                    e.azorea$setData(req.name, req.latency, req.sneaking);
                    // § La entity va al ServerLevel del SP world, NO al ClientLevel
                    //   (ClientLevel.addEntity es void y no añade nada).
                    final IntegratedServer server = mc.getSingleplayerServer();
                    final ServerLevel serverLevel =
                            server != null ? server.getLevel(mc.level.dimension()) : null;
                    final boolean added;
                    if (serverLevel != null) {
                        added = serverLevel.addFreshEntity(e);
                    } else {
                        added = level.addFreshEntity(e);
                    }
                    LoggerFactory.getLogger(AzoreaDebug.class)
                            .info("[azorea/debug] spawn '{}' @ {} → addFreshEntity={} serverLevel={}",
                                    req.name, e.position(), added,
                                    serverLevel != null ? serverLevel.getClass().getSimpleName() : "null");
                }
            }
            case KILL -> {
                final var existing = findExisting(level);
                if (existing != null) {
                    existing.discard();
                } else {
                    mc.gui.getChat().addMessage(AzoreaLang.text("command.nametag_none"));
                }
            }
            case CONFIG -> {
                final var existing = findExisting(level);
                if (existing != null) {
                    existing.azorea$setLatency(req.latency);
                    mc.gui.getChat().addMessage(
                            AzoreaLang.text("command.nametag_configured",
                                    req.latency, existing.isSneaking()));
                } else {
                    mc.gui.getChat().addMessage(AzoreaLang.text("command.nametag_none"));
                }
            }
        }
    }

    private static AzoreaDebugNametagEntity findExisting(final Level level) {
        // § Buscar en el ServerLevel real (donde la añadimos), NO en el ClientLevel.
        final Minecraft mc = Minecraft.getInstance();
        final IntegratedServer server = mc.getSingleplayerServer();
        final ServerLevel serverLevel = server != null ? server.getLevel(level.dimension()) : null;
        if (serverLevel == null) return null;
        final var et = AzoreaDebugNametagEntityType.get();
        if (et == null) return null;
        final var aabb = new AABB(
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        final var list = serverLevel.getEntities(et, aabb, e -> true);
        return list.isEmpty() ? null : (AzoreaDebugNametagEntity) list.get(0);
    }
}
