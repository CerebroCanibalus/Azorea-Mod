// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client.debug;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * § § § Entity cliente-única para el debug de nametag (sin amigo).
 *
 * <p>§ § El General pidió "spawnear y matar al player fantasma" para ver el
 * rendering del nametag Azorea (cabeza + nombre + dot de ping) sin tener que
 * esperar a un amigo en línea.
 *
 * <p>§ § Esta entity existe SÓLO en el cliente — el server no la conoce.
 * La entidad se añade vía {@code Minecraft.getInstance().level.addEntity(...)}
 * desde un comando cliente. Cuando el mundo se cierra o el server sale,
 * desaparece sola.
 *
 * <p>§ § NO extiende {@code Player} — extender Player obligaría a registrar
 * el tipo también en el server, exponer texture, etc. Entity normal + renderer
 * custom (cabeza Steve + nametag) es lo más simple y suficiente.
 */
public class AzoreaDebugNametagEntity extends Entity {

    public static final EntityDataAccessor<String> DATA_DISPLAY_NAME =
            SynchedEntityData.defineId(AzoreaDebugNametagEntity.class, EntityDataSerializers.STRING);
    public static final EntityDataAccessor<Integer> DATA_LATENCY_MS =
            SynchedEntityData.defineId(AzoreaDebugNametagEntity.class, EntityDataSerializers.INT);
    public static final EntityDataAccessor<Boolean> DATA_SNEAKING =
            SynchedEntityData.defineId(AzoreaDebugNametagEntity.class, EntityDataSerializers.BOOLEAN);

    public AzoreaDebugNametagEntity(final EntityType<? extends AzoreaDebugNametagEntity> type,
                                    final Level level) {
        super(type, level);
        this.noCulling = true;
        this.setNoGravity(true);
        // § § § FIX 1.4.4: UUID único POR spawn. Sin esto, el segundo spawn
        //   falla con "UUID of added entity already exists" (addFreshEntity
        //   → false) y la entity nunca llega al mundo. PersistentEntitySectionManager
        //   cachea UUIDs; cada nueva entity necesita uno nuevo.
        this.setUUID(java.util.UUID.randomUUID());
    }

    /** § § § Setters convenientes (se aplican también a la entityData). */
    public void azorea$setData(final String displayName, final int latencyMs, final boolean sneaking) {
        this.entityData.set(DATA_DISPLAY_NAME, displayName != null ? displayName : "DebugPlayer");
        this.entityData.set(DATA_LATENCY_MS, latencyMs);
        this.entityData.set(DATA_SNEAKING, sneaking);
    }

    /**
     * § § § Nombre a mostrar en el nametag. NO se llama {@code getDisplayName}
     * porque colisiona con {@code Nameable.getDisplayName()} de la entity
     * base (devuelve Component). Lo nombramos con el sufijo Azorea para
     * distinguir.
     */
    public String getAzoreaNametagName() { return this.entityData.get(DATA_DISPLAY_NAME); }
    public int getLatencyMs() { return this.entityData.get(DATA_LATENCY_MS); }
    public boolean isSneaking() { return this.entityData.get(DATA_SNEAKING); }

    /** § § § Setter p/ actualizar el ping dinámicamente (vía /azorea nametag config). */
    public void azorea$setLatency(final int latencyMs) {
        this.entityData.set(DATA_LATENCY_MS, latencyMs);
    }

    @Override
    protected void defineSynchedData(final SynchedEntityData.Builder builder) {
        builder.define(DATA_DISPLAY_NAME, "DebugPlayer");
        builder.define(DATA_LATENCY_MS, 80);
        builder.define(DATA_SNEAKING, false);
    }

    /**
     * § § § Spawnea la entity 3 bloques delante del jugador mirando hacia delante.
     * Llamado desde el comando cliente.
     */
    public static AzoreaDebugNametagEntity spawnInFrontOf(
            final net.minecraft.client.player.LocalPlayer player,
            final EntityType<AzoreaDebugNametagEntity> type) {
        if (player.level() == null) return null;
        // § § § Dirección horizontal: la rotación d/ la cámara (yaw), no la del cuerpo.
        //   3 bloques adelante a la altura d/ los ojos.
        final float yaw = player.getYRot();
        final double rad = Math.toRadians(yaw);
        final Vec3 forward = new Vec3(-Math.sin(rad), 0.0, Math.cos(rad));
        final Vec3 eye = player.getEyePosition();
        final Vec3 pos = eye.add(forward.scale(3.0));
        final AzoreaDebugNametagEntity e = new AzoreaDebugNametagEntity(type, player.level());
        e.setPos(pos.x, pos.y, pos.z);
        e.setYRot(yaw);
        e.setXRot(0.0F);
        return e;
    }

    // ---- métodos obligatorios d/ Entity ----

    @Override
    protected void readAdditionalSaveData(final CompoundTag tag) {
        // § § § Entity cliente-only: no se serializa. Si por alguna razón
        //   MC intenta deserializar (p.ej. al recargar el mundo), nos quedamos
        //   con los defaults.
    }

    @Override
    protected void addAdditionalSaveData(final CompoundTag tag) {
        // § § § Idem.
    }

    @Override
    public boolean isPickable() { return false; }

    @Override
    public boolean isPushable() { return false; }

    @Override
    public boolean canBeCollidedWith() { return false; }

    @Override
    public void tick() {
        // § § § Entity estática — sólo se renderiza, no se mueve ni aplica física.
        //   Mantenemos la posición fija en el sitio donde se spawneó.
        this.setDeltaMovement(Vec3.ZERO);
        super.tick();
    }
}
