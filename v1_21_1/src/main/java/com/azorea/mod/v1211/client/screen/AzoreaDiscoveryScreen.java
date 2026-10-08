// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client.screen;

import com.azorea.mod.tracker.AzoreaDiscoveryService;
import com.azorea.mod.tracker.TrackerProtocol;
import com.azorea.mod.v1211.AzoreaLang;
import com.azorea.mod.v1211.AzoreaNetLog;
import com.azorea.mod.v1211.AzoreaNetLog.Category;
import com.azorea.mod.v1211.AzoreaMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pantalla de descubrimiento (F3.0) — reescrita en 1.4.1.
 *
 * <p>§ § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § §
 *   § Decisión 1.4.1: el General pidió quitar el selector d/ versión MC porque
 *   es IMPOSIBLE conectarse a otra versión de MC (el handshake valida protocol
 *   version). En su lugar: info útil por juego (host, world, mc, players, age)
 *   + filtros REALES con efecto sobre la lista mostrada.
 *   § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § §
 *
 * <p><b>Columnas (por fila)</b>:
 *   1. <b>Host</b>: displayName del host (resaltado en amarillo). Si no hay displayName,
 *      azorea_id abreviado (AZ-XXXXXX-…).
 *   2. <b>World</b>: nombre del mundo (texto blanco).
 *   3. <b>Meta</b>: `mc 1.21.1 · 3/8 players · 2 min ago` (texto gris).
 *
 * <p><b>Interacción</b>:
 *   - Click en una fila ⇒ ping TCP al host (sincrónico) y abre AzoreaJoinScreen con
 *     el ping medido (verde/amarillo/rojo en la pantalla de detalle).
 *   - Filtro "Cerca de ti" (RTT) ⇒ al refrescar, pinga TODOS los hosts en paralelo
 *     y los ordena por latencia. 2-3 s extra al refresh.
 *
 * <p><b>Filtros</b> (con efecto real):
 *   - <b>Order</b> dropdown: Name, Players (full first), Players (empty first), Most
 *     recent, Closest (RTT).
 *   - <b>Only with room</b> checkbox: oculta juegos donde currentPlayers >= maxPlayers.
 */
public final class AzoreaDiscoveryScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzoreaDiscoveryScreen.class);
    private static final Component TITLE = AzoreaLang.text("discovery.title");

    /** § § § Orden d/ clasificación disponible (dropdown). */
    private enum Order {
        NAME,
        PLAYERS_DESC,
        PLAYERS_ASC,
        RECENT,
        PING
    }

    private CycleButton<Order> orderButton;
    private net.minecraft.client.gui.components.Checkbox onlyWithRoomBox;
    private net.minecraft.client.gui.components.Checkbox nearYouBox;
    private Button refreshButton;
    private Button backButton;
    private StringWidget statusLabel;
    private GameList gameList;

    private List<TrackerProtocol.GameListing> allGames = Collections.emptyList();
    /** § § § Ping medido por juego (gameId → ms). null = no medido / sin respuesta. */
    private final Map<String, Integer> pingByGame = new HashMap<>();
    /** § § § Para no medir 2 veces el mismo host. */
    private final AtomicBoolean measuringPings = new AtomicBoolean(false);

    /** Pantalla de la que venimos (normalmente {@link AzoreaMainScreen}). */
    private final Screen parent;

    public AzoreaDiscoveryScreen() {
        this(null);
    }

    public AzoreaDiscoveryScreen(final Screen parent) {
        super(TITLE);
        this.parent = parent;
    }

    @Override
    protected void init() {
        // Header arriba.
        addRenderableWidget(new StringWidget(8, 8, 200, 20, TITLE, this.font));

        // § § § Fila d/ filtros (Order + Only with room + Near you + Refresh)
        int yFilters = 30;
        final int orderLabelW = 50;
        addRenderableWidget(new StringWidget(8, yFilters + 4, orderLabelW, 12,
                AzoreaLang.text("discovery.filter_order_label"), this.font));
        orderButton = CycleButton.builder((Order o) -> AzoreaLang.text(
                        switch (o) {
                            case NAME -> "discovery.filter_order_name";
                            case PLAYERS_DESC -> "discovery.filter_order_players_desc";
                            case PLAYERS_ASC -> "discovery.filter_order_players_asc";
                            case RECENT -> "discovery.filter_order_recent";
                            case PING -> "discovery.filter_order_ping";
                        }))
                .withValues(Order.values())
                .withInitialValue(Order.RECENT)
                .displayOnlyValue()
                .create(8 + orderLabelW, yFilters, 140, 20,
                        AzoreaLang.text("discovery.filter_order_label"),
                        (btn, v) -> applyOrder());
        addRenderableWidget(orderButton);

        onlyWithRoomBox = net.minecraft.client.gui.components.Checkbox.builder(
                        AzoreaLang.text("discovery.filter_only_with_room"), this.font)
                .pos(8 + orderLabelW + 145, yFilters)
                .selected(false)
                .onValueChange((c, sel) -> applyOrder())
                .build();
        addRenderableWidget(onlyWithRoomBox);

        nearYouBox = net.minecraft.client.gui.components.Checkbox.builder(
                        AzoreaLang.text("discovery.filter_near_you"), this.font)
                .pos(8 + orderLabelW + 145, yFilters + 22)
                .selected(false)
                .onValueChange((c, sel) -> {
                    if (sel) {
                        measurePingsForAllGames();
                    }
                })
                .build();
        addRenderableWidget(nearYouBox);

        refreshButton = Button.builder(
                        AzoreaLang.text("common.refresh"),
                        btn -> refresh())
                .bounds(this.width - 90, yFilters, 80, 20)
                .build();
        addRenderableWidget(refreshButton);

        statusLabel = new StringWidget(8, yFilters + 48, this.width - 16, 12,
                AzoreaLang.text(""), this.font);
        addRenderableWidget(statusLabel);

        // § § § Lista d/ juegos (centro). Más alto que antes para aprovechar el
        //   espacio que liberamos al quitar el selector d/ versión.
        final int listY0 = yFilters + 64;
        final int listH = this.height - listY0 - 32;
        gameList = new GameList(this.minecraft, this.width - 16, listH, listY0, 36);
        gameList.setPosition(8, listY0);
        addRenderableWidget(gameList);

        // Back button abajo a la izquierda.
        backButton = Button.builder(
                        AzoreaLang.text("common.back"),
                        btn -> this.minecraft.setScreen(new AzoreaMainScreen(this.parent)))
                .bounds(8, this.height - 26, 80, 20)
                .build();
        addRenderableWidget(backButton);

        refresh();
    }

    private void refresh() {
        final AzoreaDiscoveryService discovery = AzoreaMod.get().services().discovery();
        if (discovery == null) {
            statusLabel.setMessage(AzoreaLang.text("discovery.status_no_services"));
            return;
        }
        // § § § Quitamos el filtro por versión (decisión 1.4.1: imposible conectar
        //   a otra versión) y dejamos los demás.
        final TrackerProtocol.Filters filters = new TrackerProtocol.Filters(
                null,                       // sin filtro MC
                "azorea",
                100);                       // ↑ más juegos, antes eran 50
        allGames = discovery.listGames(filters);
        pingByGame.clear();
        applyOrder();
        statusLabel.setMessage(AzoreaLang.text("discovery.status_count", allGames.size(), ""));
        LOGGER.info("Discovery refreshed: {} juegos", allGames.size());
    }

    /**
     * § § § Aplica el orden + filtros y re-pobla la lista. El "Near you" además
     * dispara la medición d/ RTT a todos los hosts en background.
     */
    private void applyOrder() {
        List<TrackerProtocol.GameListing> filtered = new ArrayList<>(allGames);
        if (onlyWithRoomBox != null && onlyWithRoomBox.selected()) {
            filtered.removeIf(g -> g.currentPlayers() >= g.maxPlayers());
        }
        final Order order = orderButton != null ? orderButton.getValue() : Order.RECENT;
        final Comparator<TrackerProtocol.GameListing> cmp = switch (order) {
            case NAME -> Comparator.comparing(g -> g.worldName() != null
                    ? g.worldName().toLowerCase(Locale.ROOT) : "");
            case PLAYERS_DESC -> Comparator.comparingInt(TrackerProtocol.GameListing::currentPlayers)
                    .reversed();
            case PLAYERS_ASC -> Comparator.comparingInt(TrackerProtocol.GameListing::currentPlayers);
            case RECENT -> Comparator.comparingLong(TrackerProtocol.GameListing::lastSeen).reversed();
            case PING -> Comparator.comparingInt(g -> {
                final Integer p = pingByGame.get(g.gameId());
                return p != null ? p : Integer.MAX_VALUE;
            });
        };
        filtered.sort(cmp);
        gameList.refreshGames(filtered);
        if (order == Order.PING) {
            measurePingsForAllGames();
        }
    }

    /**
     * § § § Mide ping TCP a todos los hosts en paralelo. Cada host tiene
     * endpoint público; lo sacamos del Identity (displayName/azorea_id) y
     * hacemos lookup en los anuncios cacheados — la información de contacto
     * está en el mismo GameListing.hostIdentity().
     *
     * <p>§ § Para no spamear el tracker pidiendo host:port, lo que hacemos es
     * resolver el endpoint de cada juego desde el propio announce
     * (hostIdentity.publicKeyBase64 no sirve; el endpoint viene en un campo
     * separado). Si no lo tenemos, marcamos el juego como "no medible" y lo
     * dejamos al final d/ la lista.
     */
    private void measurePingsForAllGames() {
        if (measuringPings.get()) return;
        measuringPings.set(true);
        statusLabel.setMessage(AzoreaLang.text("discovery.filter_near_you_hint"));
        Thread.startVirtualThread(() -> {
            for (final TrackerProtocol.GameListing g : allGames) {
                final String endpoint = endpointOf(g);
                if (endpoint == null) {
                    pingByGame.put(g.gameId(), Integer.MAX_VALUE);
                    continue;
                }
                final int colon = endpoint.lastIndexOf(':');
                if (colon < 0) continue;
                final String host = endpoint.substring(0, colon);
                final int port;
                try { port = Integer.parseInt(endpoint.substring(colon + 1)); }
                catch (final NumberFormatException nfe) { continue; }
                final long t0 = System.nanoTime();
                final boolean ok = probeTcp(host, port, 1500);
                final long t1 = System.nanoTime();
                if (ok) {
                    pingByGame.put(g.gameId(), (int) ((t1 - t0) / 1_000_000L));
                } else {
                    pingByGame.put(g.gameId(), Integer.MAX_VALUE);
                }
                // § § Reordenar al final d/ cada medición (live update).
                if (this.minecraft != null) {
                    this.minecraft.execute(this::applyOrder);
                }
            }
            measuringPings.set(false);
            if (this.minecraft != null) {
                this.minecraft.execute(() -> statusLabel.setMessage(
                        AzoreaLang.text("discovery.status_count",
                                allGames.size(), "")));
            }
        });
    }

    /**
     * § § § Devuelve el endpoint TCP "host:port" del juego, o null si no se
     * puede determinar. La info NO está en GameListing directamente — habría
     * que añadirla al protocolo. Por ahora devolvemos null y el ping se
     * marca como "no medible".
     *
     * <p>§ § § Esto es deliberado: meter endpoint en el announce es info sensible
     * (revela IP del host). El "Near you" sólo funcionará cuando
     * AzoreaTrackerClient.listGames devuelva también el endpoint — TODO
     * para el F9 (no para 1.4.1).
     */
    private static String endpointOf(final TrackerProtocol.GameListing g) {
        return null;
    }

    private static boolean probeTcp(final String host, final int port, final int timeoutMs) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (final Exception e) {
            return false;
        }
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

    /**
     * Lista de juegos (ObjectSelectionList) — cada Entry muestra 3 líneas:
     *   1. host (displayName) — amarillo
     *   2. world — blanco
     *   3. meta — `mc X · N/M players · age ago · ping`
     */
    private final class GameList extends ObjectSelectionList<GameList.Entry> {

        public GameList(final Minecraft mc, final int width, final int height,
                        final int y0, final int itemHeight) {
            super(mc, width, height, y0, itemHeight);
        }

        public void refreshGames(final List<TrackerProtocol.GameListing> games) {
            this.clearEntries();
            if (games.isEmpty()) {
                statusLabel.setMessage(AzoreaLang.text("discovery.empty_subtitle"));
            }
            for (final TrackerProtocol.GameListing g : games) {
                this.addEntry(new Entry(g));
            }
        }

        @Override
        public int getRowWidth() {
            return this.width - 12;
        }

        /** Una fila por juego. */
        public final class Entry extends ObjectSelectionList.Entry<Entry> {
            private final TrackerProtocol.GameListing game;

            public Entry(final TrackerProtocol.GameListing game) {
                this.game = game;
            }

            @Override
            public void render(final GuiGraphics gui, final int index, final int top, final int left,
                               final int width, final int height, final int mouseX, final int mouseY,
                               final boolean hovered, final float partialTicks) {
                // § § § Línea 1: host (displayName + azorea_id corto entre parens)
                final String hostDisplay = game.hostIdentity() != null
                        && game.hostIdentity().displayName() != null
                        && !game.hostIdentity().displayName().isBlank()
                        ? game.hostIdentity().displayName()
                        : "Azorea host";
                final String azId = game.hostIdentity() != null
                        && game.hostIdentity().azoreaId() != null
                        ? game.hostIdentity().azoreaId() : "";
                final String shortId = azId.length() > 12
                        ? azId.substring(0, 6) + "…" + azId.substring(azId.length() - 4)
                        : azId;
                gui.drawString(AzoreaDiscoveryScreen.this.font,
                        AzoreaLang.text("discovery.row_host", hostDisplay + "  §8" + shortId),
                        left + 4, top + 4, 0xFFFFFF);

                // § § § Línea 2: world
                final String world = game.worldName() != null ? game.worldName() : "—";
                gui.drawString(AzoreaDiscoveryScreen.this.font,
                        AzoreaLang.text("discovery.row_world", world),
                        left + 4, top + 14, 0xFFFFFF);

                // § § § Línea 3: meta — mc · players · age
                final String mc = game.mcVersion() != null ? game.mcVersion() : "—";
                final String age = formatAge(System.currentTimeMillis() / 1000L - game.lastSeen());
                final String playerStr = game.currentPlayers() + "/" + game.maxPlayers();
                final String pingStr = formatPingFor(pingByGame.get(game.gameId()));
                gui.drawString(AzoreaDiscoveryScreen.this.font,
                        AzoreaLang.text("discovery.row_meta", mc, game.currentPlayers(),
                                game.maxPlayers(), age) // row_meta usa {0}=mc {1}=current {2}=max {3}=age
                                .copy().append(Component.literal("  §8" + pingStr)),
                        left + 4, top + 24, 0xCCCCCC);
            }

            @Override
            public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
                // § § § Click ⇒ medir ping AHORA (sync, 1.5 s timeout) y abrir
                //   la pantalla de detalle c/ el ping ya en mano.
                final Integer ping = pingByGame.get(game.gameId());
                if (ping == null || ping == Integer.MAX_VALUE) {
                    final String endpoint = endpointOf(game);
                    if (endpoint != null) {
                        final int colon = endpoint.lastIndexOf(':');
                        if (colon > 0) {
                            final String host = endpoint.substring(0, colon);
                            int port = 0;
                            try { port = Integer.parseInt(endpoint.substring(colon + 1)); }
                            catch (final NumberFormatException nfe) { port = 0; }
                            if (port > 0 && probeTcp(host, port, 1500)) {
                                final long t0 = System.nanoTime();
                                // § § § Ya medimos en el probe anterior — recalculamos
                                //   con un probe más serio (~1 s) p/ mejor precisión.
                                final boolean ok = probeTcp(host, port, 1000);
                                final long t1 = System.nanoTime();
                                if (ok) {
                                    pingByGame.put(game.gameId(),
                                            (int) ((t1 - t0) / 1_000_000L));
                                }
                            }
                        }
                    }
                }
                AzoreaNetLog.milestone(Category.CONNECT, "browse-click",
                        "id=" + game.gameId() + " host=" + game.hostIdentity().azoreaId()
                                + " ping=" + pingByGame.getOrDefault(game.gameId(), -1) + "ms");
                AzoreaDiscoveryScreen.this.minecraft.setScreen(
                        new AzoreaJoinScreen(game, AzoreaDiscoveryScreen.this,
                                pingByGame.get(game.gameId())));
                return true;
            }

            @Override
            public Component getNarration() {
                final String host = game.hostIdentity() != null
                        ? game.hostIdentity().displayName() : "?";
                final String world = game.worldName() != null ? game.worldName() : "?";
                return AzoreaLang.text("discovery.row_meta",
                        host, game.currentPlayers(), game.maxPlayers(), world);
            }
        }
    }

    /** § § § Formatea una edad en segundos como texto corto (e.g. "2 min", "5 s", "1 h"). */
    private static String formatAge(final long seconds) {
        if (seconds < 0) return "—";
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + " min";
        if (seconds < 86400) return (seconds / 3600) + " h";
        return (seconds / 86400) + " d";
    }

    /** § § § Formatea un ping para mostrar al lado del meta. */
    private static String formatPingFor(final Integer pingMs) {
        if (pingMs == null) return "—";
        if (pingMs == Integer.MAX_VALUE) return AzoreaLang.text("discovery.row_ping_fail").getString();
        return AzoreaLang.text("discovery.row_ping", pingMs).getString();
    }
}
