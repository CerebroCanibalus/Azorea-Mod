// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client.screen;

import com.azorea.mod.tracker.AzoreaInviteService;
import com.azorea.mod.v1211.AzoreaLang;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pantalla de accept de un invite descifrado (ver AGENTS.md § F7.2).
 *
 * <p>Flujo al pulsar Connect:
 * <ol>
 *   <li>Test TCP connect directo a host:port con timeout 5s.</li>
 *   <li>Si funciona → ConnectScreen directo a host:port.</li>
 *   <li>Si falla → mostrar error + sugerencia (NAT/firewall/relay).</li>
 * </ol>
 *
 * <p>§ Limitaciones:
 * <ul>
 *   <li>No usa relay todavía: el host no conecta al relay cuando envía invite.
 *       Workaround: port forwarding en el router del host.</li>
 *   <li>UDP hole-punch tampoco auto-wireado: requiere coordinación simultánea.</li>
 *   <li>Para v2: implementar host-side relay connector + friend-side proxy.</li>
 * </ul>
 */
public final class AzoreaInviteAcceptScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzoreaInviteAcceptScreen.class);
    private static final Component TITLE = AzoreaLang.text("invite.accept_title");

    private static final int DIRECT_CONNECT_TIMEOUT_MS = 5000;

    private final Screen parent;
    private final AzoreaInviteService.DecryptedInvite invite;

    private final AtomicReference<ConnectionState> state = new AtomicReference<>(ConnectionState.IDLE);
    private StringWidget statusLabel;
    private Button connectButton;

    public AzoreaInviteAcceptScreen(final Screen parent,
                                    final AzoreaInviteService.DecryptedInvite invite) {
        super(TITLE);
        this.parent = parent;
        this.invite = invite;
    }

    @Override
    protected void init() {
        final int xCenter = (this.width - 220) / 2;
        int y = 30;

        addRenderableWidget(new StringWidget(xCenter, y, 220, 20,
                AzoreaLang.text("invite.accept_heading"), this.font));
        y += 26;

        final AzoreaInviteService.ConnectionInfo info = invite.info();
        final String fromDisplay = invite.raw().fromIdentity().displayName();
        final String fromId = invite.raw().fromIdentity().azoreaId();

        addRenderableWidget(new StringWidget(xCenter, y, 220, 12,
                AzoreaLang.text("invite.accept_from", fromDisplay, fromId), this.font));
        y += 14;
        addRenderableWidget(new StringWidget(xCenter, y, 220, 12,
                AzoreaLang.text("invite.accept_game", invite.raw().gameId()), this.font));
        y += 14;
        addRenderableWidget(new StringWidget(xCenter, y, 220, 12,
                AzoreaLang.text("invite.accept_host", info.host(), info.port()), this.font));
        y += 14;
        addRenderableWidget(new StringWidget(xCenter, y, 220, 12,
                AzoreaLang.text("invite.accept_host_token", redactToken(info.hostToken())), this.font));
        y += 24;

        connectButton = Button.builder(
                        AzoreaLang.text("invite.accept_button_connect"),
                        btn -> onConnectClicked())
                .bounds(xCenter, y, 220, 20)
                .build();
        addRenderableWidget(connectButton);
        y += 22;

        final Button declineButton = Button.builder(
                        AzoreaLang.text("invite.accept_button_decline"),
                        btn -> onDeclineClicked())
                .bounds(xCenter, y, 220, 20)
                .build();
        addRenderableWidget(declineButton);
        y += 22;

        final Button backButton = Button.builder(
                        AzoreaLang.text("common.back"),
                        btn -> this.minecraft.setScreen(parent))
                .bounds(xCenter, y, 220, 20)
                .build();
        addRenderableWidget(backButton);
        y += 22;

        statusLabel = new StringWidget(xCenter, y, 220, 12, AzoreaLang.text(""), this.font);
        addRenderableWidget(statusLabel);
    }

    private void onConnectClicked() {
        final AzoreaInviteService.ConnectionInfo info = invite.info();
        final String address = info.host() + ":" + info.port();
        LOGGER.info("Intentando conectar a {} via invite de {}...", address,
                invite.raw().fromIdentity().azoreaId());

        setStatus("invite.accept_probing");
        connectButton.active = false;

        Thread.startVirtualThread(() -> {
            if (tryDirectConnect(info.host(), info.port(), DIRECT_CONNECT_TIMEOUT_MS)) {
                LOGGER.info("Direct TCP OK — Conectando");
                setStatus("invite.accept_direct_ok");
                doConnectTo(address);
            } else {
                LOGGER.warn("Direct connect falló: {}:{}", info.host(), info.port());
                setStatus("invite.accept_direct_failed");
                connectButton.active = true;
            }
        });
    }

    private boolean tryDirectConnect(final String host, final int port, final int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void doConnectTo(final String address) {
        // ServerData "name" aparece en el server list.
        final String name = "Azorea: " + invite.raw().fromIdentity().displayName();
        final ServerData data = new ServerData(name, address, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(this, this.minecraft,
                ServerAddress.parseString(address), data, false, (TransferState) null);
    }

    private void onDeclineClicked() {
        this.minecraft.setScreen(parent);
    }

    private void setStatus(final String key) {
        if (statusLabel != null) {
            statusLabel.setMessage(AzoreaLang.text(key));
        }
    }

    /** Redacta el host_token para mostrar solo primeros/últimos chars. */
    private static String redactToken(final String token) {
        if (token == null || token.length() < 12) return "***";
        return token.substring(0, 6) + "..." + token.substring(token.length() - 6);
    }

    @Override
    public void render(final GuiGraphics gui, final int mouseX, final int mouseY, final float partialTicks) {
        gui.fill(0, 0, this.width, this.height, 0xCC000000);
        super.render(gui, mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private enum ConnectionState {
        IDLE, CONNECTING, CONNECTED, FAILED
    }
}