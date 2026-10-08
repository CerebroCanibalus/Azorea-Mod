// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client.screen;

import com.azorea.mod.v1211.AzoreaLang;
import com.azorea.mod.v1211.AzoreaMod;
import com.azorea.mod.v1211.AzoreaNetLog;
import com.azorea.mod.v1211.AzoreaNetLog.Category;
import com.azorea.mod.v1211.client.AzoreaAutohostService;
import com.azorea.mod.v1211.client.AzoreaFirewall;
import com.azorea.mod.v1211.client.AzoreaInviteBundle;
import com.azorea.mod.v1211.client.AzoreaPortForwardGuide;
import com.azorea.mod.v1211.client.AzoreaUpnpService;
import com.azorea.mod.v1211.client.friends.AzoreaFriendListScreen;
import com.azorea.mod.v1211.identity.AzoreaStunClient;
import com.azorea.mod.tracker.AzoreaHostService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Pantalla de sesión activa (F6.1).
 *
 * <p>Segunda pantalla del flow de host. Se abre tras pulsar Start en
 * {@link AzoreaHostConfigScreen}. Muestra el estado de la sesión: game_id,
 * invite_code, bind address, número de jugadores conectados, uptime.
 *
 * <p>Botones:
 * <ul>
 *   <li><b>Copy Invite</b>: copia game_id + invite_code + bind + azorea_id al clipboard del sistema.</li>
 *   <li><b>Invite Friend</b>: abre {@link AzoreaFriendListScreen} (F5.2b).</li>
 *   <li><b>Stop</b>: detiene sesión y vuelve a {@link AzoreaHostConfigScreen}.</li>
 *   <li><b>Restart</b>: stop + start con mismo config → nueva session screen.</li>
 *   <li><b>Back to Game</b>: cierra esta screen; sesión sigue corriendo en background.</li>
 * </ul>
 */
public final class AzoreaHostSessionScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzoreaHostSessionScreen.class);
    private static final Component TITLE = AzoreaLang.text("host.session.title");

    private final Screen parent;
    private final AzoreaHostConfigScreen configScreen;
    private AzoreaHostService.HostSession session;
    private AzoreaAutohostService.HostHandle handle;
    private final long startTimeMs;
    /** § F6.2: STUN result cacheado (Async) — null hasta que llegue. */
    private final AtomicReference<AzoreaStunClient.StunResult> stunResult = new AtomicReference<>();

    private StringWidget statusLabel;

    /** § § § 1.4.3: cuando true, init() pinta el panel de confirmación d/
     *   Stop Hosting en vez del panel principal. Sin esto, no podríamos
     *   cambiar el panel dinámicamente sin recargar la screen completa. */
    private boolean confirmStopOpen = false;

    private StringWidget gameIdLabel;
    private StringWidget inviteCodeLabel;
    private StringWidget bindLabel;
    private StringWidget playersLabel;
    private StringWidget uptimeLabel;

    public AzoreaHostSessionScreen(final AzoreaHostConfigScreen configScreen,
                                   final AzoreaHostService.HostSession session,
                                   final AzoreaAutohostService.HostHandle handle) {
        super(TITLE);
        this.configScreen = configScreen;
        this.parent = configScreen; // Para "Back to Game" devolvemos a la session screen no — al config o pause.
        this.session = session;
        this.handle = handle;
        this.startTimeMs = System.currentTimeMillis();
    }

    @Override
    protected void init() {
        // § § § 1.4.3: si el user pidió Stop Hosting, mostramos el panel de
        //   confirmación en lugar del panel principal. La confirmación es
        //   obligatoria porque el flujo real (disconnect+openWorld) cierra
        //   el SP world y lo reabre — si lo hiciéramos sin avisar, perdería
        //   lo q/ estuviera haciendo.
        if (this.confirmStopOpen) {
            initConfirmStopPanel();
            return;
        }

        // § F8.x: kick off UPnP + STUN warm-up en background para descubrir IP pública.
        // El resultado se cachea en AzoreaHostShared y lo usa el friend list al invitar.
        AzoreaHostShared.warmUpPublicAddress(
                AzoreaHostShared.parsePort(handle.bindAddress(), 25565));

        final int xCenter = (this.width - 200) / 2;
        int y = 24;

        addRenderableWidget(new StringWidget(xCenter, y, 200, 20,
                AzoreaLang.text("host.session.heading_active"), this.font));
        y += 24;

        gameIdLabel = new StringWidget(xCenter, y, 200, 10,
                AzoreaLang.text("host.session.label_game_id", session.gameId()), this.font);
        addRenderableWidget(gameIdLabel);
        y += 12;

        inviteCodeLabel = new StringWidget(xCenter, y, 200, 10,
                AzoreaLang.text("host.session.label_invite_code", session.inviteCode()), this.font);
        addRenderableWidget(inviteCodeLabel);
        y += 12;

        bindLabel = new StringWidget(xCenter, y, 200, 10,
                AzoreaLang.text("host.session.label_bind", handle.bindAddress()), this.font);
        addRenderableWidget(bindLabel);
        y += 12;

        // § D0/T2: veredicto de alcanzabilidad (UPnP / CGNAT / STUN / solo-LAN).
        // El host necesita VER si su partida es alcanzable por internet mientras
        // hostea — antes este diagnóstico solo iba al log y el user lo descubría
        // cuando su amigo no podía entrar.
        final Component reach = com.azorea.mod.v1211.client.AzoreaUpnpService.get().reachabilityHint();
        if (reach != null) {
            addRenderableWidget(new StringWidget(xCenter, y, 200, 10, reach, this.font));
            y += 14;
        } else {
            y += 2;
        }

        playersLabel = new StringWidget(xCenter, y, 200, 10,
                AzoreaLang.text("host.session.label_players", currentPlayers(), handle.allowCheats()),
                this.font);
        addRenderableWidget(playersLabel);
        y += 12;

        uptimeLabel = new StringWidget(xCenter, y, 200, 10,
                AzoreaLang.text("host.session.label_uptime", formatUptime()), this.font);
        addRenderableWidget(uptimeLabel);
        y += 22;

        // Botones de acción.
        final Button copyButton = Button.builder(
                        AzoreaLang.text("host.session.copy_invite"),
                        btn -> onCopyInviteClicked())
                .bounds(xCenter, y, 200, 20)
                .build();
        addRenderableWidget(copyButton);
        y += 22;

        // § F8.x: "Invite Friend" eliminado (redundante con Friends del pause menu).
        // § D0.3: en su hueco, la GUÍA DE PORT-FORWARD. Es justo el caso en que el
        // usuario llega aquí: UPnP no abrió y no hay CGNAT ⇒ SÍ puede reenviar a
        // mano, pero no sabe a qué dirección entrar ni con qué IP destino.
        final Button pfHelpButton = Button.builder(
                        AzoreaLang.text("host.session.port_forward_help"),
                        btn -> onPortForwardHelpClicked())
                .bounds(xCenter, y, 200, 20)
                .build();
        addRenderableWidget(pfHelpButton);
        y += 22;

        final Button stopButton = Button.builder(
                        AzoreaLang.text("host.session.stop_button"),
                        btn -> onStopClicked())
                .bounds(xCenter, y, 200, 20)
                .build();
        addRenderableWidget(stopButton);
        y += 22;

        final Button restartButton = Button.builder(
                        AzoreaLang.text("host.session.restart_button"),
                        btn -> onRestartClicked())
                .bounds(xCenter, y, 200, 20)
                .build();
        addRenderableWidget(restartButton);
        y += 22;

        final Button backToGameButton = Button.builder(
                        AzoreaLang.text("host.session.back_button"),
                        btn -> onBackToGameClicked())
                .bounds(xCenter, y, 200, 20)
                .build();
        addRenderableWidget(backToGameButton);
        y += 24;

        statusLabel = new StringWidget(xCenter, y, 200, 12, AzoreaLang.text(""), this.font);
        addRenderableWidget(statusLabel);
    }

        /**
     * § F8.x: devuelve el mejor bind address conocido (UPnP > STUN > LAN).
     * Usa el cache compartido de AzoreaHostShared (kick off en init).
     */
    public String getPublicBindAddress() {
        return AzoreaHostShared.getBestBindAddress(handle.bindAddress());
    }
    /**
     * § D0 (DA-10): copia el invite <b>bundle firmado</b> al clipboard.
     *
     * <p>Antes copiaba un bloque de texto legible que (a) obligaba al amigo a
     * confiar a ciegas y (b) solo servía si ambos compartían tracker — el joiner
     * tenía que ir a Browse y mirar el `invite_code` en el tracker de él. El
     * bundle lleva identidad + endpoint público + firma Ed25519 ⇒ el amigo lo
     * verifica por su cuenta (auto-certificación DA-8) y conecta directo,
     * **sin ningún tracker ni config**.
     *
     * <p>Endpoint: UPnP > STUN > LAN vía el cache compartido
     * ({@link #getPublicBindAddress()}), el mismo que decidimos en la config screen.
     *
     * <p>Nota caducidad: uso el TTL del anuncio, ⊘ {@code expiresAt} — en los
     * caminos de éxito y de fallback de {@code startHost()} se guarda en unidades
     * distintas (millis vs segundos), así que no es fiable.
     */
    private void onCopyInviteClicked() {
        final var idSvc = AzoreaMod.get().identityService();
        if (idSvc == null || idSvc.getIdentity() == null) {
            setStatus("host.session.identity_unavailable");
            return;
        }
        final var id = idSvc.getIdentity();

        final String bind = getPublicBindAddress();
        final String host = AzoreaHostShared.parseHost(bind);
        final int port = AzoreaHostShared.parsePort(bind, 25565);

        final var ann = session.announcement();
        final int ttl = ann != null ? Math.max(60, ann.ttlSeconds()) : 3600;

        // § D0/T3: candidatos en orden de utilidad para el joiner.
        // Públicos primero, privados al final — un joiner remoto no puede alcanzar
        // una IP privada y solo le haría perder el timeout del sondeo.
        //   1) v4 pública (UPnP/NAT-PMP/STUN) si la hay
        //   2) IPv6 globales (lo único q/ no necesita tocar el router)
        //   3) v4 privada → sigue funcionando p/ quien esté en tu misma red
        final java.util.List<String> v6s = AzoreaHostShared.globalIpv6Addresses();
        final boolean v4Private = AzoreaUpnpService.isNonPublicIp(host);
        final java.util.List<String> candidates = new java.util.ArrayList<>();
        if (!v4Private) candidates.add(host);
        for (final String v6 : v6s) {
            if (!candidates.contains(v6)) candidates.add(v6);
        }
        if (v4Private && !candidates.contains(host)) candidates.add(host);
        if (candidates.isEmpty()) candidates.add(host);

        // § Vía 2 (2026-10-02): el bundle lleva los trackers c/ los q/ anuncio, para
        //   q/ el joiner sepa DÓNDE encontrarse s/ pedirle q/ edite su config.
        // § ⊘ loopback: meter `http://127.0.0.1:8765` sería contraproducente — el
        //   amigo discaría a SU propio loopback. Si no queda ninguno alcanzable, el
        //   campo sale vacío y `trackersLine()` lo OMITE (degrada a sólo directo).
        final java.util.List<String> rendezvous =
                com.azorea.mod.v1211.AzoreaMod.effectiveTrackerUrls().stream()
                        .filter(AzoreaHostSessionScreen::reachableTracker)
                        .toList();

        final var bundle = new AzoreaInviteBundle.Bundle(
                id.azoreaId(),
                id.displayName(),
                id.x25519PublicKey(),
                idSvc.signingPublicKey(),
                idSvc.hwCommit(),
                host, port,
                session.gameId(),
                ann != null ? ann.worldName() : "",
                ann != null ? ann.mcVersion() : "1.21.1",
                System.currentTimeMillis() / 1000L + ttl,
                candidates,
                rendezvous);

        try {
            final String wire = AzoreaInviteBundle.encode(bundle, idSvc.ed25519PrivateKey());
            this.minecraft.keyboardHandler.setClipboard(wire);
            AzoreaNetLog.milestone(Category.INVITE, "bundle-copiado",
                    "host=" + host + ":" + port + " chars=" + wire.length() + " exp=+" + ttl + "s");

            // § D0: ¿este invite sirve para internet? Si el endpoint es privado,
            // la firma del bundle VALIDA (el joiner verifica bien) pero luego no
            // conecta — el fallo se descubriría en el extremo equivocado y tarde.
            //
            // § D0/T3: PERO si hay IPv6 global NO es "solo red local" — la v6 va
            // dentro de `hosts` y es justo la que el joiner probará primero si la
            // v4 es privada. Avisar de "no podrán entrar" sería mentir.
            // (v4Private ya está calculado al construir `candidates`.)
            final boolean hasGlobalV6 = candidates.stream().anyMatch(
                    c -> c.indexOf(':') >= 0 && !AzoreaUpnpService.isNonPublicIp(c));

            if (v4Private && !hasGlobalV6) {
                AzoreaNetLog.failure(Category.INVITE, "bundle-local", host + ":" + port,
                        "endpoint privado s/ IPv6 ⇒ invite no alcanzable desde internet");
                setStatus("host.session.invite_local_only");
                this.minecraft.gui.getChat().addMessage(Component.literal(
                        "§e§l[Azorea] §eEste invite solo funciona en tu red local§r\n"
                                + "§7Tu endpoint es §f" + host + ":" + port + " §7(IP privada) y\n"
                                + "§7no tienes IPv6 global ⇒ tus amigos remotos §o§cno podrán "
                                + "entrar§r§7.\n"
                                + "§7Cómo arreglarlo:"));
                postReachabilityHelp(false);
                return;
            }

            if (v4Private) {
                // Hay IPv6 global: el invite SÍ sirve para internet por esa vía.
                AzoreaNetLog.milestone(Category.INVITE, "bundle-v6",
                        "v4 local (" + host + ":" + port + ") + IPv6 global en candidates");
                setStatus("host.session.invite_v6_ok");
                this.minecraft.gui.getChat().addMessage(Component.literal(
                        "§a[Azorea] Invite copiado (" + wire.length() + " chars).§r\n"
                                + "§7Tu IPv4 (§f" + host + ":" + port + "§7) es de red local,\n"
                                + "§7pero el bundle lleva §aIPv6 global§7 ⇒ tu amigo entra por\n"
                                + "§7ahí §osin configurar nada§r§7. Si a él le falla, aquí tienes\n"
                                + "§7las opciones: §fPort-forward help§7."));
                return;
            }

            setStatus("host.session.invite_ok");
            this.minecraft.gui.getChat().addMessage(Component.literal(
                    "§a[Azorea] Invite copiado (" + wire.length() + " chars). "
                            + "§7Tu amigo: Azorea → Join by Invite → pegar."));
        } catch (final Exception e) {
            AzoreaNetLog.failure(Category.INVITE, "bundle-copiar", "-", e.getMessage());
            setStatus("host.session.invite_sign_failed");
        }
    }

    /**
     * § D0.3: vuelca al chat la guía de port-forward con los valores de ESTA máquina.
     * (Botón de la pantalla.)
     */
    private void onPortForwardHelpClicked() {
        // Clic explícito ⇒ sí se le permite intentar arreglar el firewall (UAC).
        postReachabilityHelp(true);
    }

    /**
     * § D0.3: diagnóstico + guía en chat.
     *
     * <p>Compartido por el botón <i>Port-forward help</i> <b>y</b> por
     * {@link #onCopyInviteClicked()} cuando el endpoint resulta privado sin IPv6
     * — así el host no tiene que ir a buscar el botón para entender por qué su
     * invite no sirve.
     *
     * @param mayFixFirewall true solo desde el botón: solo entonces se intenta
     *                       crear la regla de firewall (eso dispara un UAC, que
     *                       no debe salir de forma sorpresiva al copiar un invite)
     */
    private void postReachabilityHelp(final boolean mayFixFirewall) {
        final var upnp = AzoreaUpnpService.get();
        final String routerWan = upnp != null ? upnp.getExternalIp() : null;

        if (AzoreaUpnpService.isUpstreamNat(routerWan)) {
            this.minecraft.gui.getChat().addMessage(Component.literal(
                    "§c§l[Azorea] §cNo hay port-forward posible — CGNAT§r\n"
                            + "§7Tu router tiene IP privada (§f" + routerWan
                            + "§7) ⇒ hay otro NAT aguas arriba,\n"
                            + "§7así que cualquier regla que hagas aquí muere en tu router.\n"
                            + "\n"
                            + "§7Opciones reales:\n"
                            + "  §f1.§7 Pide a tu ISP una IP pública (suele ser gratis o muy barato)\n"
                            + "  §f2.§7 Usa IPv6 si tu ISP lo da — en IPv6 casi nunca hay CGNAT\n"
                            + "  §f3.§7 Si ninguna aplica, esta red no puede hostear\n"
                            + "\n"
                            + "§8Nota: con una IP pública el resto ya lo hacemos solos (UPnP)."));
            setStatus("host.session.cgnat_no_use");
            AzoreaNetLog.milestone(Category.UPnP, "guia-portforward", "CGNAT → sin guía, aviso honesto");
            return;
        }

        setStatus("host.session.generating_guide");
        final int port = AzoreaHostShared.parsePort(handle.bindAddress(), 25565);
        final String localIp = AzoreaHostShared.detectLocalBindAddress();
        final String publicIp = AzoreaHostShared.cachedStunIp();

        Thread.startVirtualThread(() -> {
            final String gateway = AzoreaPortForwardGuide.detectDefaultGateway();

            // § D0: estado del firewall (PowerShell ~70 ms) — corta IPv4 E IPv6.
            final AzoreaFirewall.Status fw = AzoreaFirewall.check(port);
            AzoreaNetLog.info(Category.UPnP, "firewall (ayuda): " + fw);
            boolean fixedFirewall = false;
            if (fw == AzoreaFirewall.Status.BLOCKED) {
                if (mayFixFirewall) {
                    this.minecraft.execute(() -> this.minecraft.gui.getChat().addMessage(
                            Component.literal(
                                    "§e[Azorea] Intentando abrir el puerto " + port
                                            + " en el firewall…§7 (aparecerá un diálogo\n"
                                    + "§7de administrador — dale a §fSí§7)")));
                    // Eleva y crea la regla; el UAC lo decide el usuario.
                    fixedFirewall = AzoreaFirewall.openPort(port);
                    AzoreaNetLog.milestone(Category.UPnP, "firewall-open",
                            "port=" + port + " → " + (fixedFirewall ? "creada" : "no se pudo"));
                }
            }

            final String text = AzoreaPortForwardGuide.buildText(gateway, localIp, port, publicIp);
            final String fwLine = firewallLine(fw, fixedFirewall, port);
            final String v6Line = v6Line();
            // Copia efectivamente final p/ el lambda (fixedFirewall se reasigna arriba).
            final boolean firewallFixed = fixedFirewall;

            this.minecraft.execute(() -> {
                this.minecraft.gui.getChat().addMessage(
                        Component.literal(text + "\n" + fwLine + v6Line));
                setStatus(firewallFixed
                        ? "host.session.guide_sent_firewall_ok"
                        : "host.session.guide_sent");
            });
            AzoreaNetLog.milestone(Category.UPnP, "guia-portforward",
                    "gateway=" + gateway + " local=" + localIp + " port=" + port
                            + " public=" + (publicIp != null ? publicIp : "?")
                            + " firewall=" + fw + (fixedFirewall ? " (arreglado)" : ""));
        });
    }

    /** Línea de chat con el estado del firewall y, si aplica, cómo abrirlo. */
    private static String firewallLine(final AzoreaFirewall.Status fw,
                                       final boolean fixed, final int port) {
        if (fixed) {
            return "§aFirewall Windows: puerto " + port + " ABIERTO (regla creada).\n";
        }
        return switch (fw) {
            case ALLOWED -> "§aFirewall Windows: permitido (regla inbound d/ Java, v4+v6).\n";
            case BLOCKED -> "§cFirewall Windows: BLOQUESA el puerto " + port + " — sin regla\n"
                    + "§7no entra nada, ni IPv4 ni IPv6. Pulsa §fPort-forward help§7 de nuevo\n"
                    + "§7para que el mod intente crearla, o ejecuta como admin:\n"
                    + "§f  netsh advfirewall firewall add rule name=\"Azorea MC " + port
                    + "\" dir=in action=allow protocol=TCP localport=" + port + "§r\n";
            case UNKNOWN -> "§8Firewall Windows: no se pudo determinar en este SO.\n";
        };
    }

    /** Si hay IPv6 global, decirlo: es el camino que no pide configurar nada. */
    private static String v6Line() {
        final java.util.List<String> v6 = AzoreaHostShared.globalIpv6Addresses();
        if (v6.isEmpty()) {
            return "§8IPv6 global: ninguno (sin esa vía, el port-forward es lo único).\n";
        }
        return "§aIPv6 global: §f" + v6.get(0)
                + (v6.size() > 1 ? " §7(+" + (v6.size() - 1) + ")" : "")
                + "§a — automático, s/ tocar el router§r\n";
    }

    /**
     * § § § Pinta el panel de confirmación de Stop Hosting. Lo q/ el user ve:
     *   - Título: "Detener hosting"
     *   - Aviso: "Detener el hosting cerrará tu mundo y lo reabrirá. Vas a
     *     perder el progreso NO guardado."
     *   - 2 botones: Cancelar / Detener y reiniciar
     *
     *   § § § Razón: vanilla no expone unpublish sin cerrar el SP world, así
     *   q/ la única forma d/ cerrar el puerto TCP es disconnect+openWorld.
     *   Eso reinicia el mundo, y el user tiene q/ saberlo antes de pulsar.
     */
    private void initConfirmStopPanel() {
        final int xCenter = (this.width - 280) / 2;
        int y = (this.height - 200) / 2;

        addRenderableWidget(new StringWidget(xCenter, y, 280, 20,
                AzoreaLang.text("host.session.confirm_title"), this.font));
        y += 26;

        addRenderableWidget(new StringWidget(xCenter, y, 280, 11,
                AzoreaLang.text("host.session.confirm_line1"), this.font));
        y += 14;
        addRenderableWidget(new StringWidget(xCenter, y, 280, 11,
                AzoreaLang.text("host.session.confirm_line2"), this.font));
        y += 14;
        addRenderableWidget(new StringWidget(xCenter, y, 280, 11,
                AzoreaLang.text("host.session.confirm_line3"), this.font));
        y += 26;

        // § § § 2 botones lado a lado.
        final int btnW = 130;
        final int btnGap = 20;
        final int xLeft = xCenter + (280 - (btnW * 2 + btnGap)) / 2;
        final int xRight = xLeft + btnW + btnGap;

        addRenderableWidget(Button.builder(
                        AzoreaLang.text("host.session.confirm_cancel"),
                        btn -> {
                            this.confirmStopOpen = false;
                            this.init();  // § § § repinta el panel principal
                        })
                .bounds(xLeft, y, btnW, 20)
                .build());
        addRenderableWidget(Button.builder(
                        AzoreaLang.text("host.session.confirm_stop"),
                        btn -> onConfirmStopAndRestart())
                .bounds(xRight, y, btnW, 20)
                .build());
    }

    private void onStopClicked() {
        //   (en lugar de dejar el puerto abierto). Mostramos una confirmación
        //   con el aviso — el user debe saber que el mundo se va a cerrar y
        //   reabrir (vanilla no expone unpublish sin tirar el SP world).
        this.confirmStopOpen = true;
        // § § § Botones se reconstruyen en init() al re-llamar a la screen
        //   (passamos por aquí desde un addRenderableWidget.onPress). El
        //   approach minimal: una flag + cambio del label d/ status.
        setStatus("host.session.confirm_restart");
    }

    /**
     * § § § Llamado si el user confirma. Cierra el mundo y lo reabre
     *   automáticamente — eso es lo único q/ cierra el puerto TCP
     *   (limitación de vanilla, auditada). El IntegratedServer nuevo
     *   NO se republica a LAN, q/ es justo lo q/ queremos.
     *
     *   § § § El modo d/ acceso (premium / no-premium) persiste en
     *   {@code <mundo>/azorea/access.json} y se reaplica en
     *   {@code AzoreaMod.onServerStarted} cuando arranca el nuevo server —
     *   no hay q/ hacer nada extra.
     */
    private void onConfirmStopAndRestart() {
        // § § § Cerrar port-forward d/ UPnP / NAT-PMP / PCP si los abrimos
        //   (el nuevo mundo no va a heredar el mapeo, mejor cerrarlo).
        closeUpnpIfOpen();
        com.azorea.mod.v1211.client.NatPmpClient.stopRenewal();
        com.azorea.mod.v1211.client.PcpClient.stopRenewal();

        // § § § Limpiar el state interno d/ Azorea (tracker announce, LAN
        //   discovery, relay session) — lo mismo q/ hacía el viejo
        //   stopAutohost(). El mundo se va a cerrar así q/ todo esto se
        //   queda muerto igualmente, pero limpiamos ya p/ no dejar basura.
        final AzoreaHostService hostService = AzoreaMod.get().services() != null
                ? AzoreaMod.get().services().host() : null;
        if (hostService != null) {
            hostService.stopHost();
        }
        AzoreaMod.get().autohostService().stopAutohost();
        AzoreaHostShared.resetPublicAddressCache();
        final var lan = com.azorea.mod.v1211.tracker.lan.AzoreaLanDiscovery.get();
        if (lan != null) lan.clearHostingState();
        com.azorea.mod.v1211.client.screen.AzoreaHostConfigScreen.clearRelaySession();

        // § § § Antes de cerrar, captura el nombre del mundo p/ reabrirlo.
        final net.minecraft.client.server.IntegratedServer current =
                this.minecraft.getSingleplayerServer();
        if (current == null) {
            // § § § Ya no estamos en SP — user ya salió por su cuenta.
            //   Reabrir el menú principal sin más.
            this.minecraft.setScreen(null);
            return;
        }
        final String worldName = current.getWorldData().getLevelName();

        // § § § Mensaje al chat antes d/ cerrar — explica al user lo q/ pasa.
        this.minecraft.gui.getChat().addMessage(
                com.azorea.mod.v1211.AzoreaLang.text("host.session.restarting_chat", worldName));

        // § § § Reabrir: vanilla lo hace en 2 pasos (quit + openWorld).
        //   Como doWorldLoad() llama a disconnect() internamente, podemos
        //   llamar disconnect() antes sin problema.
        final net.minecraft.client.Minecraft mc = this.minecraft;
        mc.disconnect();
        // § § § Tras el disconnect, estamos en TitleScreen. WorldOpenFlows
        //   abre el mundo por nombre y corre todas las validaciones
        //   (versión, backup, datapacks). El nuevo IntegratedServer arranca
        //   con isPublished()==false (vanilla no auto-publica en world load).
        if (mc.getLevelSource().levelExists(worldName)) {
            mc.createWorldOpenFlows().openWorld(worldName,
                    () -> mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen()));
        }
    }

    private void onCancelStop() {
        this.confirmStopOpen = false;
        setStatus("host.session.heading_active");
    }

    /**
     * § § § Comprueba si el IntegratedServer SIGUE publicando LAN (puerto abierto
     * y multicast pinger activo) tras un stopAutohost. Vanilla no despublica
     * sin cerrar el SP world ⇒ siempre devuelve true en singleplayer, pero
     * la dejamos como hook para tests / casos futuros.
     */
    private boolean isServerStillPublished() {
        final net.minecraft.client.server.IntegratedServer server =
                this.minecraft.getSingleplayerServer();
        if (server == null) return false;
        try {
            // § § § getPort() devuelve publishedPort (-1 si no publicado). Como
            //   stopAutohost() no lo resetea, sigue siendo el puerto original.
            return server.getPort() > 0;
        } catch (final Exception e) {
            return true;   // § § § fail-safe: si no podemos comprobar, asumimos q/ sí.
        }
    }



    private void closeUpnpIfOpen() {
        try {
            final var upnp = com.azorea.mod.v1211.client.AzoreaUpnpService.get();
            if (upnp != null && upnp.isAvailable()) {
                final int port = parsePortFromHandle();
                if (port > 0) upnp.closePort(port);
            }
        } catch (final Exception ignored) {
            // best-effort
        }
    }

    private int parsePortFromHandle() {
        try {
            final String bind = handle.bindAddress();
            final int colon = bind.lastIndexOf(':');
            if (colon > 0) {
                return Integer.parseInt(bind.substring(colon + 1));
            }
        } catch (final Exception ignored) {
        }
        return 25565;  // default
    }

    private void onRestartClicked() {
        // § Stop everything y volver a config (que recuerda lastConfig).
        final AzoreaHostService hostService = AzoreaMod.get().services() != null
                ? AzoreaMod.get().services().host() : null;
        if (hostService != null) hostService.stopHost();
        AzoreaMod.get().autohostService().stopAutohost();
        // § F8.x: limpiar cache de public address (UPnP/STUN) al parar.
        AzoreaHostShared.resetPublicAddressCache();
        // Reabrir config screen (que ya tiene lastConfig) y disparar start.
        // Simplificación: cerramos esta screen con parent=ConfigScreen;
        // el user pulsa Start otra vez con el mismo form pre-rellenado.
        // (Para v1 esto es OK; v2 podría automatizar el restart).
        this.minecraft.setScreen(configScreen);
    }

    private void onBackToGameClicked() {
        // Cierra esta screen, sesión sigue corriendo. Volvemos al juego.
        this.minecraft.setScreen(null);
    }

    /** Current players via Minecraft connection (count of online players). */
    private int currentPlayers() {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return 1; // al menos el host
        return mc.getConnection().getOnlinePlayers().size();
    }

    private String formatUptime() {
        final long elapsed = (System.currentTimeMillis() - startTimeMs) / 1000L;
        final long h = elapsed / 3600;
        final long m = (elapsed % 3600) / 60;
        final long s = elapsed % 60;
        return String.format("%d:%02d:%02d", h, m, s);
    }

    /** § ¿Esta URL d/ tracker sería útil p/ un peer REMOTO? (≐ `rankUrl` d/ punch). */
    private static boolean reachableTracker(final String url) {
        try {
            final String host = java.net.URI.create(url).getHost();
            return host != null
                    && !host.equalsIgnoreCase("localhost")
                    && !host.startsWith("127.")
                    && !host.equals("::1");
        } catch (final IllegalArgumentException e) {
            return false;   // URL rota ⇒ no la mandes
        }
    }

    private void setStatus(final String key) {
        statusLabel.setMessage(AzoreaLang.text(key));
    }

    @Override
    public void tick() {
        super.tick();
        // Refresca labels dinámicos.
        if (playersLabel != null) {
            playersLabel.setMessage(AzoreaLang.text(
                    "host.session.label_players", currentPlayers(), session.announcement().maxPlayers()));
        }
        if (uptimeLabel != null) {
            uptimeLabel.setMessage(AzoreaLang.text("host.session.label_uptime", formatUptime()));
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
}
