// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client.ui;

import com.azorea.mod.AzoreaConstants;
import com.azorea.mod.v1211.AzoreaLang;
import com.azorea.mod.v1211.AzoreaMod;
import com.azorea.mod.v1211.client.friends.AzoreaFriendListScreen;
import com.azorea.mod.v1211.client.screen.AzoreaMainScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coloca los 2 iconos de Azorea en la esquina superior derecha de una screen.
 *
 * <p>§ Por qué un shared y no dos inyectores con el código duplicado:
 * el menú de pausa (in-game) y el menú principal (title) deben verse <b>idénticos</b>
 * — misma posición, mismos iconos, mismos tooltips. Duplicar el bloque era la
 * receta para que divergieran al primer retoque (y ya habían divergido: el de
 * pausa existía, el de title no existía en absoluto).
 *
 * <p>§ Por qué la esquina superior derecha y no la columna centrada:
 * <ul>
 *   <li><b>Es la misma posición en ambas screens</b> ⇒ consistencia visual a coste cero.</li>
 *   <li><b>La columna central de {@code TitleScreen} está llena</b>: vanilla calcula
 *       {@code l = height/4 + 32} y apila Singleplayer/Multiplayer/Realms con paso 24 px,
 *       NeoForge mete el Mods button en {@code l + 72}, y <i>después</i> hace
 *       {@code l += 22} para bajar Options/Quit. Meter ahí un 5.º botón obliga a
 *       desplazar vanilla y <b>pelea con cualquier otro mod</b> que haga lo mismo.</li>
 *   <li><b>La esquina superior derecha está libre</b>: verificado contra las fuentes
 *       de MC 1.21.1 — {@code TitleScreen} solo dibuja logo+splash arriba-izquierda,
 *       branding abajo-izquierda, copyright abajo-derecha y la columna centrada.</li>
 * </ul>
 *
 * <p>§ Transparencia: el menú principal hace <i>fade-in</i> con el panorama vía
 * {@code TitleScreen.fadeWidgets → AbstractWidget.setAlpha}. Vanilla <b>solo lo
 * consume en {@code Button.renderWidget}</b> (comprobado: {@code AbstractWidget.render}
 * no lee {@code alpha}) ⇒ {@link AzoreaTextureButton} lo aplica a mano si no,
 * nuestros iconos aparecerían de golpe a opacidad plena rompiendo el fade.
 */
public final class AzoreaMenuIcons {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzoreaMenuIcons.class);

    /** 32x32 = tamaño nativo de los PNGs (host.png, friends.png). */
    public static final int ICON_SIZE = 32;
    public static final int MARGIN = 6;

    // § F9b FIX: la ruta real de estos PNGs es `textures/gui/` (SIN `sprites/`).
    // Pidiéndolos en `sprites/` salía FileNotFoundException en cada apertura del
    // pause menu ("Failed to load texture: azorea:textures/gui/sprites/host.png").
    private static final ResourceLocation HOST_ICON =
            ResourceLocation.fromNamespaceAndPath(AzoreaConstants.MOD_ID, "textures/gui/host.png");
    private static final ResourceLocation FRIENDS_ICON =
            ResourceLocation.fromNamespaceAndPath(AzoreaConstants.MOD_ID, "textures/gui/friends.png");

    private AzoreaMenuIcons() {
    }

    /**
     * Añade los 2 iconos a {@code event}. Llamar desde un {@code ScreenEvent.Init.Post}
     * cuya screen sea la que queremos decorar.
     *
     * @param parent screen de origen — es la que reciben "Back" de las sub-screens
     *               (los botones de Azorea navegan con ella como padre)
     */
    public static void install(final Screen parent, final ScreenEvent.Init.Post event) {
        final int xRight = parent.width - ICON_SIZE - MARGIN;
        final int yTop = MARGIN;

        final AzoreaTextureButton hostButton = new AzoreaTextureButton(
                xRight, yTop, ICON_SIZE, ICON_SIZE,
                HOST_ICON, ICON_SIZE, ICON_SIZE,
                btn -> onHostClicked(parent));
        hostButton.setTooltip(Tooltip.create(
                AzoreaLang.text("menu.tooltip_host")));

        // § DA-rediseño (2026-10-07): el icono F (Friends) se quita porque la lista de
        //   amigos está inactiva — el flujo 100% por invite manual. La entrada queda
        //   accesible vía comando d/ debug (`/azorea friend*`) pero no en la UI.
        event.addListener(hostButton);

        LOGGER.debug("Azorea: icono Host instalado en {} ({}x{}, top-right)",
                parent.getClass().getSimpleName(), parent.width, parent.height);
    }

    /**
     * § F6.1: el icono Host abre el sub-menú Azorea. Si hay sesión activa, el
     * botón "Host Game" del sub-menú detecta y abre directamente la session screen.
     *
     * <p>Igual desde el título que desde la pausa: {@link AzoreaMainScreen} no
     * depende de tener un mundo cargado (Host Game muestra el aviso si no lo hay).
     */
    private static void onHostClicked(final Screen parent) {
        if (AzoreaMod.get().services() == null) {
            // § Defensive: services se construye en onCommonSetup. Si aún no existe
            // no hay nada que anunciar — avisar y no lanzar una screen muerta.
            Minecraft.getInstance().gui.getChat().addMessage(
                    Component.literal("§cAzorea: servicios aún no inicializados."));
            return;
        }
        // § parent ⇒ "Done" vuelve al punto de entrada (pausa/título) y no
        // despausa la partida, que era lo que pasaba con setScreen(null).
        Minecraft.getInstance().setScreen(new AzoreaMainScreen(parent));
    }

    // § DA-rediseño (2026-10-07): onFriendsClicked ya no se invoca desde la UI
    //   (icono Friends oculto). Dejamos el método aquí por si se reactiva la lista
    //   de amigos y vuelve a entrar vía UI — el código sigue compilando.
    @SuppressWarnings("unused")
    private static void onFriendsClicked(final Screen parent) {
        Minecraft.getInstance().setScreen(new AzoreaFriendListScreen(parent));
    }
}
