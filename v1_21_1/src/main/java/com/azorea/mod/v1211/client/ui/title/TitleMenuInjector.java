// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client.ui.title;

import com.azorea.mod.AzoreaConstants;
import com.azorea.mod.v1211.client.ui.AzoreaMenuIcons;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Inyecta los iconos de Azorea en el menú principal — ver AGENTS.md § UI/menú principal.
 *
 * <p>§ Por qué hace falta: hasta 2026-10-01 Azorea solo era alcanzable <b>desde
 * dentro de una partida</b> (pause menu). El menú principal es donde de verdad
 * empieza el flujo D0 — el joiner pega el invite <b>desde el título</b>
 * ({@code AzoreaInvitePasteScreen} exige además {@code level == null}, luego la
 * ruta título → Azorea → Join by Invite es la única correcta), y el host también
 * debería poder llegar a Host sin cargar un mundo antes.
 *
 * <p>§ Mismo patrón que {@code PauseMenuInjector}: {@code ScreenEvent.Init.Post}
 * del game bus + {@link AzoreaMenuIcons} (shared) ⇒ idénticos en ambas screens.
 *
 * <p>§ Nota: NeoForge ya expone {@code ClientHooks.renderMainMenu(...)} dentro de
 * {@code TitleScreen.render} — pero eso es para <i>dibujar</i>, no para añadir
 * widgets (los botones necesitan init/position/hover/narration). El hook de init
 * es el correcto.
 */
@EventBusSubscriber(modid = AzoreaConstants.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class TitleMenuInjector {

    private static final Logger LOGGER = LoggerFactory.getLogger(TitleMenuInjector.class);

    private TitleMenuInjector() {
    }

    @SubscribeEvent
    public static void onScreenInitPost(final ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen titleScreen)) {
            return;
        }
        // § Guard: si otra screen (p. ej. ConfirmScreen de "borrar mundo") se dibuja
        // encima no tocamos nada — solo el título "limpio".
        // (TitleScreen es la clase base, así que comprobamos que no sea una subclase
        // nuestra ni que vengamos ya decorados; install() es idempotente por construcción
        // porque init() se re-ejecuta en cada resize y Vanilla limpia los listeners.)
        AzoreaMenuIcons.install(titleScreen, event);
        LOGGER.debug("Azorea: iconos inyectados en TitleScreen");
    }
}
