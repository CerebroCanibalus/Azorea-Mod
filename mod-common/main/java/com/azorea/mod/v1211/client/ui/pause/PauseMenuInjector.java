// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client.ui.pause;

import com.azorea.mod.AzoreaConstants;
import com.azorea.mod.v1211.client.ui.AzoreaMenuIcons;
import net.minecraft.client.gui.screens.PauseScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Inyecta botones Azorea en la pause screen vanilla (ver AGENTS.md § F4.1, F4.4, F6.1).
 *
 * <p>§ Patrón (basado en Essential, FTB Pause Menu API):
 *   Suscribirse a ScreenEvent.Init.Post del game bus → cuando la screen es PauseScreen,
 *   añadir widgets custom via {@code event.addListener(...)}.
 *
 * <p>§ Desde 2026-10-01 el bloque de iconos vive en {@link AzoreaMenuIcons} (shared)
 * con el inyector del menú principal — ver {@code client.ui.title.TitleMenuInjector}.
 * Antes este archivo era el único dueño del código de pintado y la esquina superior
 * derecha solo existía en la pausa.
 *
 * <p>§ Acciones (idénticas en pausa y título):
 *   - Host icon → abre AzoreaMainScreen (sub-menú Azorea)
 *   - Friends icon → abre AzoreaFriendListScreen
 *
 * <p>§ Limitaciones actuales:
 *   - "Host Game" solo funciona si estamos en un singleplayer world.
 *     Migrar a DedicatedServer en F3.4-extension para hosting sin SP world.
 */
@EventBusSubscriber(modid = AzoreaConstants.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class PauseMenuInjector {

    private static final Logger LOGGER = LoggerFactory.getLogger(PauseMenuInjector.class);

    private PauseMenuInjector() {
    }

    /**
     * Inyecta 2 botones custom (iconos) en la esquina superior derecha del pause menu.
     */
    @SubscribeEvent
    public static void onScreenInitPost(final ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof PauseScreen pauseScreen)) {
            return;
        }
        // § Solo la pausa "limpia" — no la pantalla de muerte ni ConfirmScreen de
        // "guardar y salir", que también son PauseScreen en algunas rutas.
        if (!pauseScreen.showsPauseMenu()) {
            return;
        }
        AzoreaMenuIcons.install(pauseScreen, event);
        LOGGER.debug("Azorea: 2 iconos inyectados en PauseScreen (top-right, 32x32)");
    }
}
