// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client;

import com.azorea.mod.v1211.AzoreaCommand;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;

/**
 * § PORTEO — Stub d/ {@code AzoreaDebug} para 1.21.3.
 *
 * <p>El feature d/ debug (nametag fantasma) se apoya en {@code EntityRenderer},
 * q/ en 1.21.2 pasó al modelo d/ <i>render state</i> ({@code createRenderState} /
 * {@code extractRenderState}) — un rework real. Además el nametag overlay era
 * experimental (default OFF) y su renderer ni siquiera estaba registrado en el bus.
 *
 * <p>Por eso esta versión excluye {@code client/debug/**} y {@code AzoreaNametagRenderer},
 * y aporta este stub: el comando {@code /azorea nametag} queda inerte aquí.
 * El resto del mod no cambia.
 */
public final class AzoreaDebug {

    private AzoreaDebug() {
    }

    /** No-op en 1.21.3 (el EntityType d/ debug no se registra). */
    public static void register(final IEventBus modBus) {
        // intencionadamente vacío
    }

    /** No-op en 1.21.3 (el comando d/ debug queda inerte). */
    public static void handleRequest(final Minecraft mc, final AzoreaCommand.PendingNametagRequest req) {
        // intencionadamente vacío
    }
}
