// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client.screen;

import com.azorea.mod.AzoreaConstants;
import com.azorea.mod.tracker.AzoreaInviteInbox;
import com.azorea.mod.v1211.AzoreaLang;
import com.azorea.mod.v1211.AzoreaMod;
import com.azorea.mod.v1211.client.AzoreaAutohostService;
import com.azorea.mod.tracker.AzoreaHostService;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import com.azorea.mod.v1211.client.ui.AzoreaGui;

import java.util.Optional;

/**
 * Pantalla principal de Azorea (F3.0).
 *
 * § DA-6: UI in-game propia. Desde aquí el usuario elige:
 *   - Host Game: crear un host session + anunciar en trackers
 *   - Browse Games: descubrir juegos publicados en trackers
 *   - Join by Invite: pegar un invite firmado (camino principal D0)
 *
 * § § 1.4.1: el General pidió limpieza. Quito:
 *   - Big title "Azorea — Multiplayer" — se cortaba a 2 líneas y se veía feo
 *   - Subtítulo "P2P multiplayer, secure and with no central infrastructure" —
 *     info d/ marketing q/ ya está en el `description` del mods.toml
 *   - Indicador "tracker:8765" — info técnica q/ confunde al jugador
 *   § § En su lugar: SOLO logo + botones centrados. El slogan
 *     "Azorea. Multijugador gratuito seguro y privado." vive ahora en
 *     META-INF/neoforge.mods.toml (description) y en el mod list del launcher.
 */
public final class AzoreaMainScreen extends Screen {

    /** § F7.1: Logo del mod (PNG 32x32) en el header. */
    private static final ResourceLocation LOGO =
            ResourceLocation.fromNamespaceAndPath(AzoreaConstants.MOD_ID, "textures/gui/logo.png");
    private static final int LOGO_SIZE = 32;

    /** Pantalla de la que venimos (pausa/título). Nula ⇒ Done vuelve al juego/título. */
    private final Screen parent;

    public AzoreaMainScreen() {
        this(null);
    }

    /**
     * § FIX 2026-10-01 — pantalla padre.
     */
    public AzoreaMainScreen(final Screen parent) {
        // § § 1.4.1: title vacío — el title bar d/ vanilla no pinta nada. El "título"
        //   visual lo lleva ahora el logo (32x32) encima d/ los botones.
        super(Component.empty());
        this.parent = parent;
    }

    /** Y d/ la cabecera — se calcula en {@link #init()} y lo reutiliza {@link #render}. */
    private int logoY;

    @Override
    protected void init() {
        // § Diseño SEAMLESS: tracker embebido siempre arranca. services siempre != null.
        final AzoreaInviteInbox inbox = AzoreaMod.get().inviteInbox();
        final int pendingCount = inbox != null ? inbox.pendingCount() : 0;

        // § Hostear exige un singleplayer world activo (el autohost usa el IntegratedServer,
        //   ver AzoreaAutohostService). Sin mundo, «Host Game» sólo lleva a un formulario
        //   q/ no podría arrancar nunca ⇒ no se muestra y el resto de opciones ocupan su
        //   hueco. Decisión del General (2026-10-05): «si abres en el menú, no muestra el
        //   botón de host game, solo el resto. No tiene utilidad ahí».
        //   Cubre también el caso d/ estar visitando el server d/ otro (client remoto).
        final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        final boolean canHost = mc != null && mc.getSingleplayerServer() != null;

        // Centrar verticalmente los botones. El logo va ARRIBA d/ los botones, con
        // un hueco d/ 16 px entre logo y primer botón.
        final int buttonWidth = 200;
        final int buttonHeight = 20;
        final int gap = 8;
        // host? + browse + join + done [+ invites]  (4 base; +1 si hay invites)
        final int buttonCount = (canHost ? 1 : 0) + 3 + (pendingCount > 0 ? 1 : 0);
        final int totalHeight = LOGO_SIZE + 16 + buttonCount * buttonHeight
                + (buttonCount - 1) * gap;
        final int yStart = (this.height - totalHeight) / 2;
        final int xCenter = (this.width - buttonWidth) / 2;

        this.logoY = yStart;
        int y = yStart + LOGO_SIZE + 16;

        // § Índice d/ fila ⇒ el hueco d/ «Host Game» se reasigna sólo si no se muestra.
        int slot = 0;
        if (canHost) {
            addRenderableWidget(Button.builder(
                            AzoreaLang.text("main.button_host"),
                            btn -> onHostClicked())
                    .bounds(xCenter, y + slot * (buttonHeight + gap), buttonWidth, buttonHeight)
                    .build());
            slot++;
        }
        addRenderableWidget(Button.builder(
                        AzoreaLang.text("main.button_browse"),
                        btn -> this.minecraft.setScreen(new AzoreaDiscoveryScreen(this)))
                .bounds(xCenter, y + slot * (buttonHeight + gap), buttonWidth, buttonHeight)
                .build());
        slot++;
        // § D0 (DA-10): pega un invite firmado. ES el camino principal entre
        // máquinas remotas — no necesita tracker, ni lista de juegos, ni config.
        addRenderableWidget(Button.builder(
                        AzoreaLang.text("main.button_join_invite"),
                        btn -> this.minecraft.setScreen(new AzoreaInvitePasteScreen(this)))
                .bounds(xCenter, y + slot * (buttonHeight + gap), buttonWidth, buttonHeight)
                .build());
        slot++;
        addRenderableWidget(Button.builder(
                        AzoreaLang.text("common.done"),
                        // § parent nulo ⇒ setScreen(null): vanilla lo traduce a
                        // TitleScreen si no hay nivel (Minecraft.java:1025) o al
                        // juego si lo hay. Con parent ⇒ vuelve al punto de entrada.
                        btn -> this.minecraft.setScreen(this.parent))
                .bounds(xCenter, y + slot * (buttonHeight + gap), buttonWidth, buttonHeight)
                .build());
        slot++;

        // § F7.2: botón "Pending Invites (N)" si hay invites pendientes.
        if (pendingCount > 0) {
            addRenderableWidget(Button.builder(
                            AzoreaLang.text("main.invites_pending", pendingCount),
                            btn -> this.minecraft.setScreen(new AzoreaInviteInboxScreen(this)))
                    .bounds(xCenter, y + slot * (buttonHeight + gap), buttonWidth, buttonHeight)
                    .build());
        }
        // § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § §
        //   § QUITADO en 1.4.1 (decisión del General): indicador "tracker:8765" abajo
        //   a la derecha. Es info técnica q/ confunde al jugador — el tracker
        //   embebido es interno, no necesita aparecer en la UI principal.
        // § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § §
    }

    @Override
    public void render(final GuiGraphics gui, final int mouseX, final int mouseY, final float partialTicks) {
        gui.fill(0, 0, this.width, this.height, 0xCC000000);
        // § F7.1: dibuja el logo centrado arriba (1.4.1 — antes estaba a la izquierda
        //   del título, ahora es EL título).
        if (this.minecraft.getResourceManager().getResource(LOGO).isPresent()) {
            final int xLogo = (this.width - LOGO_SIZE) / 2;
            AzoreaGui.blitTexture(gui, LOGO, xLogo, this.logoY, LOGO_SIZE, LOGO_SIZE, LOGO_SIZE, LOGO_SIZE);
        }
        super.render(gui, mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * § F6.1: si hay sesión activa, abre la session screen; si no, abre config.
     */
    private void onHostClicked() {
        final AzoreaHostService hostService = AzoreaMod.get().services() != null
                ? AzoreaMod.get().services().host() : null;
        final AzoreaAutohostService autohost = AzoreaMod.get().autohostService();
        final Optional<AzoreaHostService.HostSession> session =
                hostService != null ? hostService.currentSession() : Optional.empty();
        if (session.isPresent() && autohost != null && autohost.currentHandle().isPresent()) {
            // Sesión activa: ir directo a session screen.
            this.minecraft.setScreen(new AzoreaHostSessionScreen(
                    new AzoreaHostConfigScreen(this),
                    session.get(),
                    autohost.currentHandle().get()));
        } else {
            // Sin sesión: ir a config.
            this.minecraft.setScreen(new AzoreaHostConfigScreen(this));
        }
    }
}
