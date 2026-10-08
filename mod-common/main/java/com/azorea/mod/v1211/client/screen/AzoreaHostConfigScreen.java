// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client.screen;

import com.azorea.mod.v1211.AzoreaLang;
import com.azorea.mod.v1211.AzoreaMod;
import com.azorea.mod.v1211.AzoreaNetLog;
import com.azorea.mod.v1211.AzoreaNetLog.Category;
import com.azorea.mod.v1211.access.AzoreaAccessState;
import com.azorea.mod.v1211.access.AzoreaWorldAccess;
import com.azorea.mod.v1211.client.AzoreaAutohostService;
import com.azorea.mod.v1211.client.AzoreaFirewall;
import com.azorea.mod.v1211.client.AzoreaHostPermissions;
import com.azorea.mod.v1211.client.AzoreaUpnpService;
import com.azorea.mod.tracker.AzoreaHostService;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pantalla de configuración de host — <b>rediseño (DA-rediseño de pantalla d/ hosteo)</b>.
 *
 * <p>§ <b>Qué cambió respecto al formulario plano anterior</b> (que era «toda pitera»):
 * <ul>
 *   <li><b>Secciones con cabecera</b> agrupadas en 2 columnas ⇒ jerarquía visual en vez
 *       d/ 4 EditBoxes apilados sin contexto;</li>
 *   <li><b>ajustes REALES d/ server</b> (modo d/ juego, dificultad, PvP, vuelo, trampas)
 *       q/ antes estaban hardcodeados — el modo d/ juego ni siquiera existía, iba siempre
 *       a {@code SURVIVAL};</li>
 *   <li><b>selector d/ modo de acceso</b> (premium / no-premium) — A3b, antes sólo por
 *       comando;</li>
 *   <li><b>red en vivo</b>: lo q/ sólo se veía <i>después</i> d/ pulsar Start se muestra
 *       antes, actualizándose a medida q/ avanzan los sondeos;</li>
 *   <li><b>defaults d/ verdad</b>: antes decía «Host» y «My World» siempre — {@code lastConfig}
 *       <b>nunca</b> tenía valor (la screen se recrea en cada visita) ⇒ era código muerto.
 *       Ahora precarga el nombre real d/ MC, el nombre real d/ mundo, la dificultad y el
 *       PvP/vuelo actuales d/ server.</li>
 * </ul>
 *
 * <p>§ <b>Layout</b>: 2 columnas, Start y Back en la misma fila (ahorra 20 px d/ alto),
 * y el subtítulo se omite en ventanas bajas — el conjunto cabe en <b>256 px</b> (q/ es la
 * altura lógica d/ 1366×768 a escala 3).
 *
 * <p>§ <b>El cascade d/ red d/ {@link #onStartClicked()} NO se tocó</b> — es lo q/ acaba d/
 * validarse en WAN real. Esta pantalla sólo lo invoca igual y añade <i>lectura</i> antes.
 *
 * <p>Back → pantalla padre. Si ya hay sesión activa se redirige al session screen (igual
 * que antes).
 */
public final class AzoreaHostConfigScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzoreaHostConfigScreen.class);
    private static final Component TITLE = AzoreaLang.text("host.title");

    // ===== Geometría (ver javadoc: todo cabe en 256 px d/ alto) =====
    private static final int SIDE_MARGIN = 30;
    private static final int GUTTER = 18;
    private static final int LABEL_W = 76;
    private static final int ROW_PITCH = 16;
    private static final int WIDGET_H = 16;
    private static final int SECTION_HEADER = 14;
    private static final int SECTION_PAD = 6;
    private static final int SECTION_GAP = 8;

    private final Screen parent;

    // --- widgets d/ formulario
    private EditBox maxPlayersBox;
    private EditBox portBox;
    private EditBox nameBox;
    private EditBox motdBox;
    private CycleButton<GameType> gameTypeButton;
    private CycleButton<Difficulty> difficultyButton;
    private CycleButton<Boolean> pvpButton;
    private CycleButton<Boolean> flightButton;
    /** § 3 estados: nadie / sólo host / todos (ver {@link AzoreaHostPermissions}). */
    private CycleButton<com.azorea.mod.v1211.client.AzoreaHostPermissions.Mode> cheatsButton;
    /** § A3b — selector premium / no-premium. */
    private CycleButton<Boolean> accessButton;
    private StringWidget accessHint;

    private StringWidget statusLabel;
    private StringWidget netLabel;
    private Button startButton;
    private Button backButton;

    /** Geometría d/ las secciones, p/ pintarles panel y cabecera en {@link #render}. */
    private final List<Section> sections = new ArrayList<>();

    private record Section(int x, int y, int width, int height, String title) {
    }

    /** Cooldown p/ refrescar la línea d/ red (sólo lectura d/ caches ⇒ barato). */
    private int netRefreshTick;

    /** IP v6 global — se enumera UNA vez (cuesta syscalls; ⊘ cada tick). */
    private List<String> ipv6Cache = List.of();

    /** Almacena el último config usado (para Restart desde session screen). */
    private AzoreaHostService.HostConfig lastConfig;

    public AzoreaHostConfigScreen(final Screen parent) {
        super(TITLE);
        this.parent = parent;
    }

    private static int sectionHeight(final int rows) {
        return SECTION_HEADER + rows * ROW_PITCH + SECTION_PAD;
    }

    /** Sección sin líneas extra d/ ayuda. */
    private int beginSection(final String title, final int x, final int y,
                             final int width, final int rows) {
        return beginSection(title, x, y, width, rows, 0);
    }

    /** Sobrecarga con Component translatable — el título viaja por el catálogo. */
    private int beginSection(final Component title, final int x, final int y,
                             final int width, final int rows) {
        return beginSection(title.getString(), x, y, width, rows, 0);
    }

    /** Sobrecarga con Component translatable y altura extra. */
    private int beginSection(final Component title, final int x, final int y,
                             final int width, final int rows, final int extraHeight) {
        return beginSection(title.getString(), x, y, width, rows, extraHeight);
    }

    /**
     * Registra la sección y devuelve el Y d/ su primera fila d/ contenido.
     *
     * <p>§ El título se pinta en {@link #render}, <b>lo último</b> — con banda d/ cabecera
     * y/o antes d/ {@code super.render()} salía <b>oscuro</b> (capturas 2026-10-05, dos
     * intentos fallidos: {@code drawString} c/ {@code flush} y {@code StringWidget}).
     */
    private int beginSection(final String title, final int x, final int y,
                             final int width, final int rows, final int extraHeight) {
        sections.add(new Section(x, y, width, sectionHeight(rows) + extraHeight, title));
        return y + SECTION_HEADER;
    }

    /** Etiqueta d/ fila (izquierda). El widget va a la derecha, en {@code x + LABEL_W}. */
    private void label(final String text, final int x, final int y) {
        addRenderableWidget(new StringWidget(x, y, LABEL_W, WIDGET_H,
                Component.literal("§7" + text), this.font));
    }

    /** Sobrecarga con Component translatable — usa el formateo d/ Component, no string crudo. */
    private void label(final Component comp, final int x, final int y) {
        addRenderableWidget(new StringWidget(x, y, LABEL_W, WIDGET_H,
                comp.copy().withStyle(s -> s.withColor(net.minecraft.network.chat.TextColor.fromRgb(0xAAAAAA))),
                this.font));
    }

    private int widgetX(final int colX) {
        return colX + LABEL_W;
    }

    private int widgetW(final int colW) {
        return colW - LABEL_W;
    }

    @Override
    protected void init() {
        sections.clear();

        // § F8.x fix: si ya hay sesión activa, redirigir a session screen.
        final AzoreaHostService hostSvc = AzoreaMod.get().services() != null
                ? AzoreaMod.get().services().host() : null;
        final var autohost = AzoreaMod.get().autohostService();
        if (hostSvc != null && autohost != null) {
            final var session = hostSvc.currentSession();
            final var handle = autohost.currentHandle();
            if (session.isPresent() && handle.isPresent()) {
                LOGGER.info("Sesión activa detectada al abrir config screen → redirigiendo a session screen");
                this.minecraft.setScreen(new AzoreaHostSessionScreen(this, session.get(), handle.get()));
                return;
            }
        }

        final IntegratedServer server = this.minecraft.getSingleplayerServer();
        final AzoreaWorldAccess world = AzoreaAccessState.world();

        // ===== Cabecera =====
        final int available = this.width - SIDE_MARGIN * 2;
        final int colW = Math.max(170, Math.min(250, (available - GUTTER) / 2));
        final int total = colW * 2 + GUTTER;
        final int x0 = (this.width - total) / 2;
        final int xR = x0 + colW + GUTTER;

        int y = 9;
        addRenderableWidget(new StringWidget(x0, y, total, 13, TITLE, this.font));
        y += 13;
        // § En ventanas bajas (1366×768 a escala 3 = 256 px lógicos) el subtítulo se
        //   cae: es lo q/ hace q/ el conjunto quepa sin recortar.
        if (this.height >= 300) {
            addRenderableWidget(new StringWidget(x0, y, total, 10,
                    Component.literal("§7Publica tu mundo y juega con quien tú quieras."), this.font));
            y += 10;
        }
        final int top = y + 8;

        // ===== Defaults d/ verdad (antes: siempre «Host» y «My World») =====
        // § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § §
        //   § 1.4.1: autodetectamos GameType, Difficulty, PvP y Flight del mundo
        //   en curso. El jugador puede cambiarlos si quiere, pero el valor inicial
        //   refleja la realidad d/ la partida. — Antes: GameType=hardcoded SURVIVAL,
        //   la pantalla decía «Survival» aunque el mundo fuera creativo.
        // § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § §
        final String defaultName = this.minecraft.getUser().getName();
        final String defaultWorld = server != null
                ? server.getWorldData().getLevelName() : "My World";
        final int defaultMax = 8;
        final int defaultPort = 25565;
        final String defaultMotd = server != null ? server.getMotd() : "";
        final Difficulty defaultDifficulty = server != null
                ? server.getWorldData().getDifficulty() : Difficulty.NORMAL;
        final boolean defaultPvp = server == null || server.isPvpAllowed();
        final boolean defaultFlight = server != null && server.isFlightAllowed();
        // § § § § § Autodetect GameType del mundo (antes hardcoded a SURVIVAL).
        //   En 1.21.1 los valores públicos son sólo SURVIVAL/CREATIVE/ADVENTURE/
        //   SPECTATOR — no hay NOT_SET público. Si el server está inicializado
        //   tiene un GameType concreto, no null.
        GameType defaultGameType = GameType.SURVIVAL;
        if (server != null) {
            final GameType worldGt = server.getWorldData().getGameType();
            defaultGameType = worldGt != null ? worldGt : server.getDefaultGameType();
            if (defaultGameType == null) {
                defaultGameType = GameType.SURVIVAL;
            }
        }

        // ===== COLUMNA IZQUIERDA =====

        // --- ACCESO (A3b) — arriba-izq: es lo q/ el General pidió, va destacado.
        final boolean haveWorld = server != null && world != null;
        final AzoreaAccessState.Mode currentMode = haveWorld
                ? world.mode() : AzoreaAccessState.Mode.PREMIUM;
        int row = beginSection(AzoreaLang.text("host.section_access"), x0, top, colW, 1, 12);
        label(AzoreaLang.text("host.label_access_mode"), x0, row);
        accessButton = CycleButton.builder((Boolean noPrem) -> AzoreaLang.text(
                        noPrem ? "host.access_no_premium" : "host.access_premium"))
                .withValues(Boolean.FALSE, Boolean.TRUE)
                .withInitialValue(currentMode == AzoreaAccessState.Mode.NO_PREMIUM)
                .displayOnlyValue()
                .create(widgetX(x0), row, widgetW(colW), WIDGET_H,
                        AzoreaLang.text("host.placeholder_access_mode"),
                        (btn, value) -> onAccessModeChanged(value));
        accessButton.active = haveWorld;
        addRenderableWidget(accessButton);
        row += ROW_PITCH;
        // § ⚠ El ancho es el d/ la COLUMNA, ⊘ el d/ las dos: con `total` el hint se salía
        //   y pisaba la sección JUEGO d/ la derecha (bug visible en captura 2026-10-05).
        accessHint = new StringWidget(x0 + 4, row, colW - 8, 11, AzoreaLang.text(""), this.font);
        addRenderableWidget(accessHint);

        // --- PARTIDA
        int yLeft = top + sectionHeight(1) + 12 + SECTION_GAP;
        row = beginSection(AzoreaLang.text("host.section_session"), x0, yLeft, colW, 3);

        // § Sólo LECTURA (decisión d/ General 2026-10-05): el nombre d/ mundo vive en el
        //   mundo y hostear NO lo configura — es config específica d/ la partida. Se
        //   muestra p/ contexto y se pasa al announce d/ tracker, pero ⊘ EditBox: nadie
        //   puede escribir otro. (Antes era editable ⇒ el announce podía decir «My World»
        //   mientras el mundo real era «New World».)
        label(AzoreaLang.text("host.label_world"), x0, row);
        addRenderableWidget(new StringWidget(widgetX(x0), row,
                Math.max(1, this.font.width(defaultWorld)), WIDGET_H,
                AzoreaLang.text("host.world_value", defaultWorld),
                this.font));
        row += ROW_PITCH;

        label(AzoreaLang.text("host.label_max_players"), x0, row);
        maxPlayersBox = new EditBox(this.font, widgetX(x0), row, widgetW(colW), WIDGET_H,
                AzoreaLang.text("host.placeholder_max_players"));
        maxPlayersBox.setValue(String.valueOf(defaultMax));
        maxPlayersBox.setMaxLength(3);
        addRenderableWidget(maxPlayersBox);
        row += ROW_PITCH;

        label(AzoreaLang.text("host.label_port"), x0, row);
        portBox = new EditBox(this.font, widgetX(x0), row, widgetW(colW), WIDGET_H,
                AzoreaLang.text("host.placeholder_port"));
        portBox.setValue(String.valueOf(defaultPort));
        portBox.setMaxLength(5);
        addRenderableWidget(portBox);

        final int leftBottom = yLeft + sectionHeight(3);

        // ===== COLUMNA DERECHA =====

        // --- JUEGO (los ajustes REALES q/ antes estaban hardcodeados)
        row = beginSection(AzoreaLang.text("host.section_game"), xR, top, colW, 5);

        label(AzoreaLang.text("host.label_game_type"), xR, row);
        // § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § §
        //   § FIX 1.4.1: antes se mostraba `{0}` literal. Causa: envolver un
        //   String en otro `Component.translatable` con key `host.net_public_ip_value`
        //   cuyo template es `§f{0}` ⇒ el placeholder NO se rellenaba porque MC usa
        //   MessageFormat (no String.format) y el String "survival" se
        //   trataba como args, pero algo en la cadena de builders lo dejaba sin
        //   sustituir. La fix correcta: usar el `Component` ya localizado que
        //   GameType expone vía getLongDisplayName() (forma canónica 1.21.1).
        //   Mismo cambio para Difficulty (getDisplayName() ya devuelve Component).
        // § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § §
        gameTypeButton = CycleButton.builder((GameType gt) -> gt.getLongDisplayName())
                .withValues(List.of(GameType.SURVIVAL, GameType.CREATIVE,
                        GameType.ADVENTURE, GameType.SPECTATOR))
                .withInitialValue(defaultGameType)
                .displayOnlyValue()
                .create(widgetX(xR), row, widgetW(colW), WIDGET_H,
                        AzoreaLang.text("host.placeholder_game_type"), (b, v) -> { });
        addRenderableWidget(gameTypeButton);
        row += ROW_PITCH;

        label(AzoreaLang.text("host.label_difficulty"), xR, row);
        difficultyButton = CycleButton.builder(
                        (Difficulty d) -> d.getDisplayName())
                .withValues(List.of(Difficulty.PEACEFUL, Difficulty.EASY,
                        Difficulty.NORMAL, Difficulty.HARD))
                .withInitialValue(defaultDifficulty)
                .displayOnlyValue()
                .create(widgetX(xR), row, widgetW(colW), WIDGET_H,
                        AzoreaLang.text("host.placeholder_difficulty"), (b, v) -> { });
        addRenderableWidget(difficultyButton);
        row += ROW_PITCH;

        // § Los toggles d/ JUEGO sólo SE LEEN al pulsar Start (se aplican ahí). El único
        //   q/ se aplica en caliente es ACCESO — porque persiste en el mundo y cambia el
        //   `online-mode` del server; el resto son propiedades d/ la sesión.
        label("PvP", xR, row);
        pvpButton = booleanToggle(widgetX(xR), row, widgetW(colW), defaultPvp);
        addRenderableWidget(pvpButton);
        row += ROW_PITCH;

        label("Flight", xR, row);
        flightButton = booleanToggle(widgetX(xR), row, widgetW(colW), defaultFlight);
        addRenderableWidget(flightButton);
        row += ROW_PITCH;

        label(AzoreaLang.text("host.label_cheats").getString(), xR, row);
        // § 3 estados (DA-rediseño): nadie / sólo host / todos. La semántica y el motivo
        //   d/ usar la op-list viven en AzoreaHostPermissions.
        cheatsButton = CycleButton.builder(
                        (AzoreaHostPermissions.Mode m) -> AzoreaLang.text(switch (m) {
                            case OFF -> "host.cheats_off";
                            case HOST_ONLY -> "host.cheats_host_only";
                            case ALL -> "host.cheats_all";
                        }))
                .withValues(AzoreaHostPermissions.Mode.OFF,
                        AzoreaHostPermissions.Mode.HOST_ONLY,
                        AzoreaHostPermissions.Mode.ALL)
                .withInitialValue(AzoreaHostPermissions.Mode.OFF)
                .displayOnlyValue()
                .create(widgetX(xR), row, widgetW(colW), WIDGET_H,
                        AzoreaLang.text("host.placeholder_cheats"), (b, v) -> { });
        addRenderableWidget(cheatsButton);

        // --- ANUNCIO (lo q/ ven los demases)
        int yRight2 = top + sectionHeight(5) + SECTION_GAP;
        row = beginSection(AzoreaLang.text("host.section_advertise"), xR, yRight2, colW, 2);

        label(AzoreaLang.text("host.label_display_name").getString(), xR, row);
        nameBox = new EditBox(this.font, widgetX(xR), row, widgetW(colW), WIDGET_H,
                AzoreaLang.text("host.placeholder_display_name"));
        nameBox.setValue(defaultName);
        nameBox.setMaxLength(32);
        addRenderableWidget(nameBox);
        row += ROW_PITCH;

        label(AzoreaLang.text("host.label_motd").getString(), xR, row);
        motdBox = new EditBox(this.font, widgetX(xR), row, widgetW(colW), WIDGET_H,
                AzoreaLang.text("host.placeholder_motd"));
        motdBox.setValue(defaultMotd);
        motdBox.setMaxLength(60);
        addRenderableWidget(motdBox);

        final int rightBottom = yRight2 + sectionHeight(2);

        // ===== Red en vivo + acciones (cabecera d/ fila ⇒ Start y Back en la MISMA fila) =====
        final int colBottom = Math.max(leftBottom, rightBottom);
        int yFooter = colBottom + SECTION_GAP;

        final int backW = 88;
        startButton = Button.builder(AzoreaLang.text("host.start_button"), btn -> onStartClicked())
                .bounds(x0, yFooter, total - backW - 6, WIDGET_H)
                .build();
        addRenderableWidget(startButton);

        backButton = Button.builder(AzoreaLang.text("common.back"),
                        btn -> this.minecraft.setScreen(parent))
                .bounds(x0 + total - backW, yFooter, backW, WIDGET_H)
                .build();
        addRenderableWidget(backButton);
        yFooter += WIDGET_H + 6;

        netLabel = new StringWidget(x0, yFooter, total, 11, AzoreaLang.text(""), this.font);
        addRenderableWidget(netLabel);
        yFooter += 11 + 4;

        statusLabel = new StringWidget(x0, yFooter, total, 12, AzoreaLang.text(""), this.font);
        addRenderableWidget(statusLabel);

        // ===== § D0/T2: calentar UPnP+STUN al abrir, p/ q/ la línea d/ red se llene
        //      mientras el usuario rellena el formulario (⊥ al pulsar Start).
        AzoreaHostShared.warmUpPublicAddress(defaultPort);
        this.ipv6Cache = AzoreaHostShared.globalIpv6Addresses();
        refreshAccessHint();
        refreshNetLabel();
    }

    /**
     * Sí/no como CycleButton, p/ que todos los toggles se vean igual.
     *
     * <p>§ Los bounds van en {@code create(x,y,w,h,…)} — {@code CycleButton} (1.21.1)
     * <b>no tiene {@code setBounds}</b>, la posición es inmutable tras crearla.
     */
    private CycleButton<Boolean> booleanToggle(final int x, final int y, final int width,
                                               final boolean initial) {
        return CycleButton.builder((Boolean v) -> AzoreaLang.text(v ? "host.value_yes" : "host.value_no"))
                .withValues(Boolean.FALSE, Boolean.TRUE)
                .withInitialValue(initial)
                .displayOnlyValue()
                .create(x, y, width, WIDGET_H,
                        AzoreaLang.text("common.ok"), (b, v) -> { });
    }

    // ===== § A3b — selector d/ modo de acceso =====

    /**
     * Aplica el modo premium/no-premium <b>en caliente</b>, v/ el <b>único</b> punto d/
     * decisión q/ ya existe ({@code AzoreaMod.applyAccessMode}), igual q/ el arranque d/ mundo
     * y el comando d/ debug ⇒ ⊘ dos caminos.
     *
     * <p>Persiste en {@code <mundo>/azorea/access.json} ⇒ <b>sobrevive a reinicios</b>, y
     * sólo cambia el {@code online-mode} d/ este server. <b>⊥ expulsa</b> a quien ya esté.
     */
    private void onAccessModeChanged(final boolean noPremium) {
        final IntegratedServer server = this.minecraft.getSingleplayerServer();
        final AzoreaWorldAccess world = AzoreaAccessState.world();
        if (server == null || world == null) {
            // No debería pasar (Host Game sólo aparece c/ mundo), pero no dejamos estado ambiguo.
            setStatus("azorea.host.access_mode_no_world");
            refreshAccessHint();
            return;
        }
        final AzoreaAccessState.Mode mode = noPremium
                ? AzoreaAccessState.Mode.NO_PREMIUM : AzoreaAccessState.Mode.PREMIUM;
        AzoreaMod.get().applyAccessMode(server, world, mode);
        setStatus(noPremium
                ? "azorea.host.access_mode_changed_no_premium"
                : "azorea.host.access_mode_changed_premium");
        refreshAccessHint();
    }

    private void refreshAccessHint() {
        if (accessHint == null || accessButton == null) {
            return;
        }
        // § Corto a propósito: el ancho real d/ la columna puede bajar a ~170 px
        //   (ventana estrecha / escala 2) y un texto largo se saldría d/ la sección.
        accessHint.setMessage(accessButton.getValue()
                ? AzoreaLang.text("host.access_hint_no_premium")
                : AzoreaLang.text("host.access_hint_premium"));
    }

    // ===== § Red en vivo =====

    /**
     * Estado d/ red <b>antes</b> d/ hostear. Sólo lee caches ya calentadas por
     * {@code warmUpPublicAddress} ⇒ ⊘ sondeos nuevos, seguro d/ llamar seguido.
     */
    private void refreshNetLabel() {
        if (netLabel == null) {
            return;
        }
        final AzoreaUpnpService upnp = AzoreaUpnpService.get();
        final Component upnpComp;
        if (upnp != null && upnp.isAvailable()) {
            upnpComp = AzoreaLang.text("host.net_upnp_ok");
        } else if (upnp != null && upnp.isScanning()) {
            upnpComp = AzoreaLang.text("host.net_upnp_scanning");
        } else {
            upnpComp = AzoreaLang.text("host.net_upnp_unknown");
        }

        final String stun = AzoreaHostShared.cachedStunIp();
        final Component stunComp = stun != null
                ? AzoreaLang.text("host.net_public_ip_value", stun)
                : AzoreaLang.text("host.net_public_ip_pending");

        final Component ipv6Comp = ipv6Cache.isEmpty()
                ? AzoreaLang.text("host.net_ipv6_none")
                : AzoreaLang.text("host.net_ipv6_count", ipv6Cache.size());

        netLabel.setMessage(AzoreaLang.text("host.net_label", upnpComp, stunComp, ipv6Comp));
    }

    @Override
    public void tick() {
        super.tick();
        // 2 veces/s basta p/ ver cómo se llena la IP vía STUN (q/ tarda ~1-3 s).
        if (++netRefreshTick >= 10) {
            netRefreshTick = 0;
            refreshNetLabel();
        }
    }

    // ===== Arranque =====

    private void onStartClicked() {
        final String displayName = nameBox.getValue().trim();
        if (displayName.isEmpty()) {
            setStatus("azorea.host.error_display_name_empty");
            return;
        }
        final int maxPlayers;
        try {
            maxPlayers = Integer.parseInt(maxPlayersBox.getValue().trim());
        } catch (NumberFormatException e) {
            setStatus("azorea.host.error_max_players_nan");
            return;
        }
        if (maxPlayers < 1 || maxPlayers > 1000) {
            setStatus("azorea.host.error_max_players_range");
            return;
        }
        final int port;
        try {
            port = Integer.parseInt(portBox.getValue().trim());
        } catch (NumberFormatException e) {
            setStatus("azorea.host.error_port_nan");
            return;
        }
        if (port < 1 || port > 65535) {
            setStatus("azorea.host.error_port_range");
            return;
        }
        final com.azorea.mod.v1211.client.AzoreaHostPermissions.Mode cheatsMode = cheatsButton.getValue();
        final GameType gameType = gameTypeButton.getValue();
        final Difficulty difficulty = difficultyButton.getValue();
        final boolean pvp = pvpButton.getValue();
        final boolean flight = flightButton.getValue();
        final String motd = motdBox.getValue().trim();

        final AzoreaHostService hostService = AzoreaMod.get().services() != null
                ? AzoreaMod.get().services().host() : null;
        if (hostService == null) {
            setStatus("azorea.host.error_services_unavailable");
            return;
        }

        final String detectedHost = AzoreaHostShared.detectLocalBindAddress();

        // ===== § Ajustes REALES del server — ANTES d/ publicar =====
        // § El MOTD va antes a propósito: publishServer() hace
        //   `new LanServerPinger(this.getMotd(), …)` ⇒ si lo cambias después, el
        //   anuncio LAN sigue llevando el viejo.
        final IntegratedServer integrated = this.minecraft.getSingleplayerServer();
        if (integrated != null) {
            integrated.setMotd(motd);
            integrated.setPvpAllowed(pvp);
            integrated.setFlightAllowed(flight);
            integrated.setDifficulty(difficulty, false);
        }

        // § Autohost primero (requiere SP world activo) — abre el puerto TCP.
        final AzoreaAutohostService autohost = AzoreaMod.get().autohostService();
        final Optional<AzoreaAutohostService.HostHandle> autoResult =
                autohost.startAutohost(port, cheatsMode, detectedHost, gameType);
        if (autoResult.isEmpty()) {
            setStatus("azorea.host.error_autohost_failed");
            return;
        }

        // § F8.x + D0/T1 + D0/T2: decidir qué endpoint público anunciar.
        String effectiveHost = detectedHost;
        String upnpStatus;
        String reachHint;
        boolean cgnat = false;
        final var upnp = com.azorea.mod.v1211.client.AzoreaUpnpService.get();

        // § D0/T1: el scan original es de 1 sola vez c/ timeout de 5s. Si aún corre,
        // ESPERAR (antes nos rendíamos al instante y anunciábamos IP LAN); si ya
        // falló, REINTENTAR una vez.
        if (!upnp.isAvailable() && !upnp.isScanning()) {
            upnp.rescan();
        }
        if (upnp.isScanning()) {
            // ⚠ Corto a propósito: esto corre en el main thread y no hay que congelar MC.
            upnp.awaitGateway(1500);
        }

        if (upnp.isAvailable()) {
            String extIp = upnp.openPort(port, 3600);
            if (extIp == null) {
                // § D0/T1: reintento único (router ocupado/saturado al 1.er intento).
                AzoreaNetLog.attempt(Category.UPnP, "openPort-retry", "port=" + port);
                extIp = upnp.openPort(port, 3600);
            }
            if (extIp == null) {
                upnpStatus = "host.session.upnp_rejected";
                reachHint = "host.session.upnp_rejected_reach";
                AzoreaNetLog.failure(Category.UPnP, "openPort", "port=" + port,
                        "rechazado en 2 intentos");
            } else if (com.azorea.mod.v1211.client.AzoreaUpnpService.isUpstreamNat(extIp)) {
                // § D0/T2: el router ACEPTÓ el mapeo, pero su WAN no es pública ⇒ hay
                // otro NAT aguas arriba (CGNAT/double-NAT). El mapeo NO llega a
                // internet y ninguna cantidad de UPnP lo arregla.
                cgnat = true;
                upnpStatus = "host.session.upnp_cgnat";
                reachHint = "host.session.upnp_cgnat_reach";
                AzoreaNetLog.milestone(Category.UPnP, "cgnat-detectado",
                        "WAN del router=" + extIp + " (no pública) ⇒ NAT aguas arriba");
            } else {
                effectiveHost = extIp;
                upnpStatus = "host.session.upnp_ok";
                reachHint = "host.session.upnp_ok_reach";
            }
        } else if (upnp.isScanning()) {
            upnpStatus = "host.session.upnp_scanning";
            reachHint = "host.session.upnp_scanning_reach";
            AzoreaNetLog.failure(Category.UPnP, "disponible", "gateway",
                    "null (scan aún en curso tras esperar)");
        } else {
            // § AZOREA (a): antes decía "No encontré tu router (UPnP deshabilitado)"
            // — FALSO en el caso más engañoso: el router sí soporta UPnP y sí
            // contesta al SSDP, pero su servicio de control está caído. Culpar de
            // "deshabilitado" manda al usuario a mirar una casilla que ya está ✅.
            final String problem = upnp.lastScanProblem();
            if (problem != null && !problem.isBlank()) {
                upnpStatus = "host.session.upnp_firmware";
                reachHint = "host.session.upnp_firmware_reach";
                AzoreaNetLog.failure(Category.UPnP, "disponible", "gateway",
                        "null tras scan + rescan — " + problem);
            } else {
                upnpStatus = "host.session.upnp_disabled";
                reachHint = "host.session.upnp_disabled_reach";
                AzoreaNetLog.failure(Category.UPnP, "disponible", "gateway",
                        "null tras scan + rescan (sin respuesta SSDP)");
            }
        }

        // ===== § D0/DA-11 F-B: cascada de endpoint público =====
        // UPnP (SSDP) → PCP (RFC 6887) → NAT-PMP (RFC 6886) → STUN → solo LAN.
        //
        // § PCP va ANTES de NAT-PMP porque comparten el puerto UDP 5351 pero son
        // protocolos distintos: PCP = versión 2, NAT-PMP = versión 0. Firmware
        // moderno (OpenWRT reciente, routers de operadora, pfSense/OPNsense)
        // suele hablar PCP y NO NAT-PMP ⇒ sin este eslabón ese router perdía el
        // mapeo y caía a STUN.
        //
        // § Guarda anti-estrambótico (bug real de NetBird: "PCP discovery starves
        // the UPnP/NAT-PMP fallback"): si PCP falla de CUALQUIER forma devuelve
        // null y probamos NAT-PMP igual que antes. Nunca puede cortar el relevo.
        int bindPort = port;
        if (!cgnat && effectiveHost.equals(detectedHost)) {
            final String pcp = com.azorea.mod.v1211.client.PcpClient
                    .tryMapEndpoint(port, 3600);
            if (pcp != null) {
                final int colon = pcp.lastIndexOf(':');
                effectiveHost = pcp.substring(0, colon);
                bindPort = Integer.parseInt(pcp.substring(colon + 1));
                upnpStatus = "host.session.pcp_ok";
                reachHint = "host.session.pcp_ok_reach";
            } else {
                // § D0: NAT-PMP no necesita SSDP — la dirección es la puerta de enlace
                // por defecto, que ya detectamos. Es el eslabón que cubre routers c/
                // UPnP apagado (justo el caso del ordenador del General: M-SEARCH sin
                // respuesta). Si no lo soporta, falla rápido y seguimos.
                final String nmp = com.azorea.mod.v1211.client.NatPmpClient
                        .tryMapEndpoint(port, 3600);
                if (nmp != null) {
                    final int colon = nmp.lastIndexOf(':');
                    effectiveHost = nmp.substring(0, colon);
                    bindPort = Integer.parseInt(nmp.substring(colon + 1));
                    upnpStatus = "host.session.natpmp_ok";
                    reachHint = "host.session.natpmp_ok_reach";
                }
            }
        }

        // § D0/T2: si nada resolvió, la IP vía STUN es mejor que una IP LAN (la LAN
        // jamás cruza internet). Solo funciona si además hay port-forward manual —
        // y lo decimos explícito.
        if (!cgnat && effectiveHost.equals(detectedHost)) {
            final String stunIp = AzoreaHostShared.cachedStunIp();
            if (stunIp != null) {
                effectiveHost = stunIp;
                AzoreaNetLog.milestone(Category.STUN, "bind-fallback", stunIp + ":" + port);
                upnpStatus = "host.session.stun_fallback";
                reachHint = "host.session.stun_fallback_reach";
            } else {
                upnpStatus = "host.session.only_local";
                reachHint = "host.session.only_local_reach";
            }
        }

        // ===== § D0/T3: veredicto final =====
        // Si no hay v4 pública pero SÍ IPv6 global, NO es "solo red local": es el
        // único camino que el mod consigue él solo, sin tocar el router. Decir lo
        // contrario sería falso (el bundle ya lleva la v6 en `hosts`).
        final boolean v4Public = !effectiveHost.equals(detectedHost)
                && !AzoreaUpnpService.isNonPublicIp(effectiveHost);
        final java.util.List<String> v6s = AzoreaHostShared.globalIpv6Addresses();
        if (!v4Public && !v6s.isEmpty()) {
            upnpStatus = "host.session.ipv6_global";
            reachHint = "host.session.ipv6_global_reach";
        }

        upnp.setReachabilityHint(AzoreaLang.text(reachHint, port, bindPort));
        final String bindAddress = effectiveHost + ":" + bindPort;
        LOGGER.info("Host bindAddress={} ({})", bindAddress, upnpStatus);
        AzoreaNetLog.info(Category.HOST, "bindAddress=" + bindAddress + " — " + reachHint);

        // § D0: el firewall de Windows corta la entrada por defecto — y lo haría
        // TAMBIÉN por IPv6, así que hay que mirarlo aunque tengamos v6. Consulta
        // async (PowerShell) ⇒ nunca en el main thread; solo avisamos si bloquea.
        Thread.startVirtualThread(() -> {
            final AzoreaFirewall.Status fw = AzoreaFirewall.check(port);
            AzoreaNetLog.info(Category.HOST, "firewall Windows: " + fw + " (port=" + port + ")");
            if (fw != AzoreaFirewall.Status.BLOCKED) return;
            this.minecraft.execute(() -> this.minecraft.gui.getChat().addMessage(
                    Component.literal(
                            "§c§l[Azorea] §cEl firewall de Windows bloquea la entrada al puerto "
                                    + port + "§r\n"
                                    + "§7Eso corta el acceso §otambién por IPv6§r§7: nadie podrá\n"
                                    + "§7conectarse aunque el endpoint sea público.\n"
                                    + "§7Solución: pulsa §fPort-forward help§7 → §fFirewall§7, o\n"
                                    + "§7ejecuta en una terminal (como admin):\n"
                                    + "§f  netsh advfirewall firewall add rule name=\"Azorea MC "
                                    + port + "\" dir=in action=allow protocol=TCP localport=" + port)));
        });

        final AzoreaHostService.HostConfig config = new AzoreaHostService.HostConfig(
                AzoreaHostShared.buildHostIdentity(displayName),
                "1.21.1", "21.1.250",
                bindAddress, maxPlayers,
                // § El nombre d/ mundo NUNCA sale d/ un campo editable (decisión d/ General
                //   2026-10-05): va el d/ la partida real, p/ q/ el announce d/ tracker no
                //   pueda decir algo distinto d/ lo q/ el joiner va a ver.
                integrated != null ? integrated.getWorldData().getLevelName() : "",
                300);
        this.lastConfig = config;

        // § Announce al tracker.
        final Optional<AzoreaHostService.HostSession> result = hostService.startHost(config);
        if (result.isEmpty()) {
            setStatus("§cAnnounce falló tras autohost OK. El juego es local pero no discoverable.");
            // Cerrar UPnP si falló announce (no tiene sentido mantener abierto).
            if (upnp.isAvailable()) upnp.closePort(port);
            return;
        }

        // § OK → abrir session screen.
        // § DA-7: actualizar LAN discovery con el bindAddress + gameId para que otros mods en LAN
        // puedan encontrar este host automáticamente.
        final var lan = com.azorea.mod.v1211.tracker.lan.AzoreaLanDiscovery.get();
        if (lan != null) {
            lan.setHostingState(bindAddress, result.get().gameId());
        }
        // § DA-12: el host NO sabe quién va a unirse ⇒ anuncia SIN destinatario y espera a
        // q/ alguien publique c/ `target=nuestro id`. El announce caduca a 30 s y
        // `exchange` lo refresca cada 10 s mientras espera.
        final int hostedPort = port;
        Thread.startVirtualThread(() -> {
            final var me = com.azorea.mod.v1211.client.AzoreaPunchManager.Signer.fromMod();
            if (me == null) return;
            // § Antes: `tracker.localUrl()` HARDCODEADO ⇒ en WAN el host anunciaba a su
            //   propio loopback y el joiner polleaba el SUYO ⇒ nunca se encontraban.
            final var urls = AzoreaMod.effectiveTrackerUrls();
            if (urls.isEmpty()) {
                LOGGER.warn("Punch: sin tracker efectivo (config vacía y embebido no arrancó)");
                return;
            }
            final var punched = com.azorea.mod.v1211.client.AzoreaPunchManager
                    .exchange(urls, me, null, HOST_PUNCH_WAIT_MS);
            if (punched.isEmpty()) return;
            // § Puente: socket puncheado ↔ integrated server. Si éste ya no escucha
            //   (se dejó d/ hostear), `startHostBridge` lo dice y cierra el socket.
            com.azorea.mod.v1211.client.AzoreaLocalProxy.startHostBridge(
                    punched.get(), hostedPort, "host");
        });

        // § F8.x: crear relay session + conectar como host adapter (relay fallback).
        // Si el joiner no puede conectar directo (UPnP/punch fallan), usa relay.
        final String relaySessionId;
        final int relayPort;
        final var embedded = com.azorea.mod.v1211.tracker.embedded.AzoreaAutoTracker.get();
        if (embedded != null && embedded.tracker() != null
                && embedded.tracker().relay() != null) {
            relaySessionId = embedded.tracker().relay().createSession();
            relayPort = embedded.tracker().relayPort();
            com.azorea.mod.tracker.AzoreaRelayClient.connectAsHost(
                    relaySessionId, "127.0.0.1", relayPort, port);
            LOGGER.info("Relay session creada: {} (port={})", relaySessionId, relayPort);
        } else {
            relaySessionId = null;
            relayPort = 0;
        }
        // Guardar para que el invite flow lo incluya en ConnectionInfo.
        currentRelaySessionId = relaySessionId;
        currentRelayPort = relayPort;

        setStatus(upnpStatus);
        this.minecraft.setScreen(new AzoreaHostSessionScreen(this, result.get(), autoResult.get()));
    }

    /**
     * Cuánto espera el host a q/ alguien le hable desde el momento d/ hostear.
     *
     * <p>Largo a propósito: el joiner puede llegar 10 minutos después, y mientras tanto
     * `exchange` re-anuncia cada 10 s (TTL 30 s) ⇒ el buzón nunca se queda sin su entrada.
     */
    private static final int HOST_PUNCH_WAIT_MS = 30 * 60 * 1000;

    /** § F8.x: relay session del host actual (null si relay no disponible). */
    private static volatile String currentRelaySessionId;
    private static volatile int currentRelayPort;

    /**
     * § F9 FIX: invalida la relay session al parar de hostear.
     * Sin esto, {@link #relaySessionId()} seguía devolviendo una sesión ya muerta
     * y el invite flow la incluía en el ConnectionInfo.
     */
    public static void clearRelaySession() {
        currentRelaySessionId = null;
        currentRelayPort = 0;
    }

    public static String relaySessionId() {
        return currentRelaySessionId;
    }

    public static int relayPort() {
        return currentRelayPort;
    }

    /** Para Restart desde session screen. */
    public AzoreaHostService.HostConfig getLastConfig() {
        return lastConfig;
    }

    private void setStatus(final String text) {
        if (statusLabel != null) {
            statusLabel.setMessage(Component.literal(text));
        }
    }

    @Override
    public void render(final GuiGraphics gui, final int mouseX, final int mouseY,
                       final float partialTicks) {
        gui.fill(0, 0, this.width, this.height, 0xCC000000);

        // § Paneles d/ sección — lo q/ da estructura al formulario (antes: nada).
        //   § SIN banda d/ cabecera: con ella los títulos salían oscuros (dos intentos:
        //     drawString c/ flush y StringWidget). Ahora el título se pinta al FINAL,
        //     tras super.render(), q/ es cuando ya no hay nada d/ encima.
        for (final Section s : sections) {
            gui.fill(s.x(), s.y(), s.x() + s.width(), s.y() + s.height(), 0x55000000);
            gui.fill(s.x(), s.y(), s.x() + s.width(), s.y() + 2, 0xFF3C8CFF);
        }

        super.render(gui, mouseX, mouseY, partialTicks);

        // § Títulos: lo último ⇒ por encima d/ todo lo demás.
        for (final Section s : sections) {
            gui.drawString(this.font, "§f" + s.title(), s.x() + 5, s.y() + 5, 0xFFFFFF, true);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
