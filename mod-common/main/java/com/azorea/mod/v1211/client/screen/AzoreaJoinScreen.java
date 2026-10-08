// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client.screen;

import com.azorea.mod.tracker.AzoreaJoinService;
import com.azorea.mod.tracker.TrackerProtocol;
import com.azorea.mod.v1211.AzoreaLang;
import com.azorea.mod.v1211.AzoreaMod;
import com.azorea.mod.v1211.AzoreaNetLog;
import com.azorea.mod.v1211.AzoreaNetLog.Category;
import com.azorea.mod.v1211.client.AzoreaNametag;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

import java.net.Socket;
import java.util.Optional;

/**
 * Pantalla de join (F3.0 + F5.2 + F8.x).
 *
 * <p>§ Flujo de conexión (F8.x):
 * <ol>
 *   <li>LAN discovery: si el host está en la misma red → bindAddress directo.</li>
 *   <li>Hole-punch coordinado: poll tracker el announce del host → punchTcp.</li>
 *   <li>Relay fallback: si punch falla → relay (pendiente sessionId propagation).</li>
 *   <li>Error con diagnóstico.</li>
 * </ol>
 */
public final class AzoreaJoinScreen extends Screen {

    private static final Component TITLE = AzoreaLang.text("join.title");

    private final TrackerProtocol.GameListing game;
    private StringWidget planLabel;

    /** Pantalla de la que venimos (Browse). Mantiene la cadena Back intacta. */
    private final Screen parent;

    /**
     * § § § 1.4.1: ping medido (en ms) al hacer click en la fila d/ Browse Games.
     * Se pinta con su color d/ bucket. Null ⇒ "no medido".
     */
    private final Integer preMeasuredPingMs;

    public AzoreaJoinScreen(final TrackerProtocol.GameListing game) {
        this(game, null, null);
    }

    public AzoreaJoinScreen(final TrackerProtocol.GameListing game, final Screen parent) {
        this(game, parent, null);
    }

    public AzoreaJoinScreen(final TrackerProtocol.GameListing game, final Screen parent,
                            final Integer preMeasuredPingMs) {
        super(TITLE);
        this.game = game;
        this.parent = parent;
        this.preMeasuredPingMs = preMeasuredPingMs;
    }

    @Override
    protected void init() {
        final int xCenter = (this.width - 200) / 2;
        int y = 20;

        addRenderableWidget(new StringWidget(xCenter, y, 200, 20, TITLE, this.font));
        y += 24;
        final String hostDisplay = game.hostIdentity() != null
                ? game.hostIdentity().displayName() : "?";
        final String hostId = game.hostIdentity() != null
                ? game.hostIdentity().azoreaId() : "?";
        addRenderableWidget(new StringWidget(xCenter, y, 200, 12,
                AzoreaLang.text("join.host_display", hostDisplay), this.font));
        y += 14;
        addRenderableWidget(new StringWidget(xCenter, y, 200, 12,
                AzoreaLang.text("join.host_id", hostId), this.font));
        y += 14;
        addRenderableWidget(new StringWidget(xCenter, y, 200, 12,
                AzoreaLang.text("join.world_name", game.worldName()), this.font));
        y += 14;
        addRenderableWidget(new StringWidget(xCenter, y, 200, 12,
                AzoreaLang.text("join.mc_version", game.mcVersion(),
                        net.minecraft.client.Minecraft.getInstance().getLaunchedVersion()),
                this.font));
        y += 14;
        // § § § 1.4.1: si la pantalla anterior (Browse) midió un ping, lo mostramos
        //   aquí con su color (verde/amarillo/rojo).
        if (preMeasuredPingMs != null) {
            final String pingBucket = AzoreaNametag.pingBucket(preMeasuredPingMs);
            addRenderableWidget(new StringWidget(xCenter, y, 200, 12,
                    AzoreaLang.text("join.ping_" + pingBucket, preMeasuredPingMs), this.font));
            y += 14;
        }
        if (game.inviteCode() != null) {
            addRenderableWidget(new StringWidget(xCenter, y, 200, 12,
                    AzoreaLang.text("join.invite_code", game.inviteCode()),
                    this.font));
            y += 14;
        }
        if (!game.currentPlayersIdentity().isEmpty()) {
            final String playersList = game.currentPlayersIdentity().stream()
                    .map(TrackerProtocol.Identity::displayName)
                    .reduce((a, b) -> a + ", " + b).orElse("");
            addRenderableWidget(new StringWidget(xCenter, y, 200, 12,
                    AzoreaLang.text("join.players", playersList), this.font));
            y += 14;
        }

        y += 6;
        final Button connectButton = Button.builder(
                        AzoreaLang.text("join.button_connect"),
                        btn -> onConnect())
                .bounds(xCenter, y, 200, 20)
                .build();
        addRenderableWidget(connectButton);
        y += 22;

        final Button lookupButton = Button.builder(
                        AzoreaLang.text("join.button_lookup"),
                        btn -> onLookupDetails())
                .bounds(xCenter, y, 200, 20)
                .build();
        addRenderableWidget(lookupButton);
        y += 22;

        final Button backButton = Button.builder(
                        AzoreaLang.text("common.back"),
                        // § parent = la instancia de Discovery que nos abrió (conserva
                        // a su vez el parent de Main). Con parent nulo caemos a una
                        // Discovery nueva — nunca a null, que saltaría al título.
                        btn -> this.minecraft.setScreen(
                                this.parent != null ? this.parent : new AzoreaDiscoveryScreen()))
                .bounds(xCenter, y, 200, 20)
                .build();
        addRenderableWidget(backButton);
        y += 28;

        planLabel = new StringWidget(xCenter, y, 200, 12, Component.literal(""), this.font);
        addRenderableWidget(planLabel);
    }

    private void onConnect() {
        final String hostAzoreaId = game.hostIdentity() != null
                ? game.hostIdentity().azoreaId() : null;
        if (hostAzoreaId == null) {
            planLabel.setMessage(Component.literal(
                    "§cGame listing sin host identity. ¿Tracker corrupto?"));
            return;
        }
        // § F8.x: intentar LAN discovery primero.
        // § F9 bug#4: además del bindAddress, necesitamos el trackerUrl del host —
        // su announce de punch vive en SU tracker, no en el nuestro.
        String hostTrackerUrl = null;
        final var lanPeer = com.azorea.mod.v1211.tracker.lan.AzoreaLanDiscovery.get();
        if (lanPeer != null) {
            final var peer = lanPeer.getPeer(hostAzoreaId);
            if (peer != null) {
                hostTrackerUrl = peer.trackerUrl();
                if (peer.isOnline() && peer.isHosting()) {
                    AzoreaNetLog.milestone(Category.CONNECT, "lan-direct",
                            "peer=" + hostAzoreaId + " bind=" + peer.bindAddress());
                    planLabel.setMessage(Component.literal(
                            "§aConectando via LAN discovery a " + peer.bindAddress() + "..."));
                    connectTo(peer.bindAddress());
                    return;
                }
            }
        }
        AzoreaNetLog.attempt(Category.CONNECT, "join-flow",
                "LAN miss para " + hostAzoreaId + " → punch fallback"
                        + " (host tracker=" + (hostTrackerUrl != null ? hostTrackerUrl : "desconocido") + ")");
        planLabel.setMessage(Component.literal(
                "§eLAN discovery no encontró al host. Intentando hole-punch..."));
        attemptPunchAndConnect(hostAzoreaId, hostTrackerUrl);
    }

    /**
     * § DA-12: hole-punch TCP — arma la <b>lista</b> d/ trackers c/ los q/ intentar.
     *
     * <p>§ F9 bug#4: el announce del host vive en el tracker DEL HOST. Antes se elegía
     * <b>1 sola</b> URL — o la d/ LAN discovery o la local — y en WAN ambas valen cero:
     * LAN discovery ⊘ cruza internet y la local es `127.0.0.1`. Ahora:
     *
     * <pre>
     *   config `trackers.urls`  → WAN (DA-10.1 permite usar 1 d/ tercero gratuito)
     *   URL d/ AZ_HELLO         → LAN (el tracker del host, sólo alcanzable en red local)
     *   embebido propio         → último recurso — y el punch lo AVISA si da loopback
     * </pre>
     * Se anuncia en <b>TODOS</b> y se pollea en todos ⇒ basta q/ el peer tenga 1 d/ ellos.
     *
     * @param hostTrackerUrl URL del tracker del host (null si LAN discovery no la dio)
     */
    private void attemptPunchAndConnect(final String hostAzoreaId, final String hostTrackerUrl) {
        final java.util.List<String> urls = new java.util.ArrayList<>(
                com.azorea.mod.v1211.AzoreaMod.effectiveTrackerUrls());
        if (hostTrackerUrl != null && !hostTrackerUrl.isBlank() && !urls.contains(hostTrackerUrl)) {
            urls.add(hostTrackerUrl);
        }
        if (urls.isEmpty()) {
            AzoreaNetLog.failure(Category.PUNCH, "poll-peer", hostAzoreaId,
                    "s/ tracker: config vacía y tracker embebido no arrancó");
            planLabel.setMessage(Component.literal(
                    "§cSin tracker disponible — pon `trackers.urls` en azorea.toml."));
            return;
        }
        // § ¿Sólo tenemos trackers locales? Entonces en WAN no hay dónde encontrarse.
        final boolean isFallback = urls.stream().allMatch(u -> u.contains("127.0.0.1")
                || u.contains("localhost"));
        planLabel.setMessage(Component.literal(
                "§7Punch v/ " + urls.size() + " tracker(s): " + String.join(", ", urls)));
        Thread.startVirtualThread(() -> {
            // § Identidad: sin ella no hay firma ⇒ el tracker rechaza el announce (DA-9).
            final var me = com.azorea.mod.v1211.client.AzoreaPunchManager.Signer.fromMod();
            if (me == null) {
                this.minecraft.execute(() -> planLabel.setMessage(Component.literal(
                        "§cSin identidad todavía — no puedo firmar mi announce.")));
                return;
            }
            this.minecraft.execute(() -> planLabel.setMessage(Component.literal(
                    "§7Hole-punch TCP (observe → announce → punch)...")));

            // § Cadena completa: 1 puerto p/ las 3 fases. El viejo hacía announce c/ port=0
            //   (⇒ unsigned() lanzaba y TODO el bloque moría) y punchTcp(0,…) (⇒ efímero
            //   inalcanzable, el socket puncheado se tiraba a continuación).
            final Optional<Socket> punched = com.azorea.mod.v1211.client.AzoreaPunchManager
                    .exchange(urls, me, hostAzoreaId, 8000);

            if (punched.isEmpty()) {
                AzoreaNetLog.failure(Category.CONNECT, "punch", hostAzoreaId,
                        "sin ruta directa v/ " + String.join(", ", urls)
                                + (isFallback ? " (SÓLO trackers locales ⇒ en WAN imposible)"
                                        : "")
                                + " — probando relay fallback");
                this.minecraft.execute(() -> {
                    planLabel.setMessage(Component.literal("§cPunch falló. Probando relay..."));
                    attemptRelayFallback();
                });
                return;
            }

            // § MC no habla "socket puncheado": habla host:puerto. El proxy local le
            //   da un 127.0.0.1 al q/ conectarse y puentea byte a byte.
            final int localPort = com.azorea.mod.v1211.client.AzoreaLocalProxy
                    .startJoinerProxy(punched.get(), hostAzoreaId);
            if (localPort <= 0) {
                AzoreaNetLog.failure(Category.CONNECT, "joiner-proxy", hostAzoreaId,
                        "no pude abrir el proxy local");
                this.minecraft.execute(() -> planLabel.setMessage(Component.literal(
                        "§cPunch OK pero el proxy local no arrancó.")));
                return;
            }
            final String address = "127.0.0.1:" + localPort;
            AzoreaNetLog.milestone(Category.CONNECT, "punch-success",
                    address + (isFallback ? " (tracker local compartido)" : ""));
            this.minecraft.execute(() -> {
                planLabel.setMessage(Component.literal("§aPunch exitoso → " + address));
                connectTo(address);
            });
        });
    }

    /**
     * § F8.x: relay fallback. Usa sessionId compartido (propagado via punch announce en F8.1).
     * Por ahora: si el tracker local tiene relay, informa al user.
     */
    private void attemptRelayFallback() {
        final var tracker = com.azorea.mod.v1211.tracker.embedded.AzoreaAutoTracker.get();
        if (tracker == null || tracker.tracker() == null || tracker.tracker().relay() == null) {
            AzoreaNetLog.failure(Category.RELAY, "fallback", game.gameId(),
                    "relay no disponible en tracker local");
            this.minecraft.execute(() -> planLabel.setMessage(Component.literal(
                    "§cRelay no disponible.")));
            return;
        }
        final int relayPort = tracker.tracker().relayPort();
        AzoreaNetLog.info(Category.RELAY, "relay disponible en puerto " + relayPort
                + " — sessionId propagation pendiente (F8.1)");
        this.minecraft.execute(() -> planLabel.setMessage(Component.literal(
                "§eRelay disponible en puerto " + relayPort + ".\n"
                        + "§7Pero el sessionId del host no está propagado aún (F8.1).\n"
                        + "§7Alternativa: Direct Connect a <host>:<port> si lo tienes.")));
    }

    private void onLookupDetails() {
        final AzoreaJoinService joinService = AzoreaMod.get().services().join();
        if (joinService == null) {
            planLabel.setMessage(Component.literal("§cServicios no disponibles."));
            return;
        }
        final Optional<AzoreaJoinService.JoinResult> result = joinService.joinById(game.gameId());
        planLabel.setMessage(Component.literal(
                result.map(r -> "§aLookup OK: invite_code=" + r.inviteCode()
                                + ", host_id=" + (r.hostIdentity() != null ? r.hostIdentity().azoreaId() : "?"))
                        .orElse("§cLookup falló (game_id no encontrado o trackers caídos).")));
    }

    private void connectTo(final String address) {
        final String name = "Azorea: " + (game.hostIdentity() != null
                ? game.hostIdentity().displayName() : "?");
        final ServerData data = new ServerData(name, address, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(this, this.minecraft,
                ServerAddress.parseString(address), data, false, (TransferState) null);
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
}
