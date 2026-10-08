// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client.screen;

import com.azorea.mod.v1211.AzoreaLang;
import com.azorea.mod.v1211.AzoreaMod;
import com.azorea.mod.v1211.AzoreaNetLog;
import com.azorea.mod.v1211.AzoreaNetLog.Category;
import com.azorea.mod.v1211.client.AzoreaInviteBundle;
import com.azorea.mod.v1211.client.friends.AzoreaFriendsService;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/**
 * Pantalla "Join by Invite" — ver AGENTS.md § DA-10 (D0 serverless).
 *
 * <p>§ Qué hace: pegas el invite que te pasó tu amigo (Discord, WhatsApp, etc.),
 * se verifica <b>localmente</b> y se conecta directo a su endpoint público.
 *
 * <p>§ Por qué no necesita tracker: el bundle lleva dentro la identidad del host
 * (id + claves + firma) y su {@code host:port}. La verificación es
 * {@link AzoreaInviteBundle#decodeAndVerify} — auto-certificación (DA-8) + firma
 * Ed25519 (DA-9) —, o sea que <b>sin autoridad</b> sabes con quién hablas y a dónde
 * vas. Esto es lo que sustituye a "Browse Games" en el MVP.
 *
 * <p>§ Flujo: pegar → verificar → {@link ConnectScreen#startConnecting}.
 * Si la verificación falla, se muestra el motivo concreto (suplantación, firma
 * inválida, caducado…); ⊘ se intenta conectar nunca.
 */
public final class AzoreaInvitePasteScreen extends Screen {

    private static final Component TITLE = AzoreaLang.text("invite.paste_title");

    private final Screen parent;
    private EditBox pasteBox;
    private StringWidget statusLabel;
    private StringWidget previewLabel;

    public AzoreaInvitePasteScreen(final Screen parent) {
        super(TITLE);
        this.parent = parent;
    }

    @Override
    protected void init() {
        final int panelW = 280;
        final int xCenter = (this.width - panelW) / 2;
        int y = 20;

        // § § § 1.4.1: title va arriba con logo, sin subtítulo
        addRenderableWidget(new StringWidget(xCenter, y, panelW, 16, TITLE, this.font));
        y += 20;
        addRenderableWidget(new StringWidget(xCenter, y, panelW, 10,
                AzoreaLang.text("invite.paste_hint"), this.font));
        y += 14;

        // § § § EditBox d/ pegado — ancho cómodo (panelW) y placeHolder traducido
        pasteBox = new EditBox(this.font, xCenter, y, panelW, 20,
                AzoreaLang.text("invite.paste_placeholder"));
        pasteBox.setMaxLength(4096);
        // § § § Cuando el usuario escribe/borra, refrescamos el preview en vivo
        // (debounce ligero: cada cambio ⇒ reintentar parse; el parse es rápido
        // porque sólo decodifica Base64+verifica firma). Si falla ⇒ mostrar error.
        pasteBox.setResponder(this::onInviteTextChanged);
        addRenderableWidget(pasteBox);
        y += 24;

        // § § § Área d/ preview — muestra lo q/ sabemos del invite (o un error
        //   legible si no es válido). 6 líneas × 12 px ≈ 72 px d/ alto.
        final int previewH = 78;
        previewLabel = new StringWidget(xCenter, y, panelW, previewH,
                AzoreaLang.text("invite.preview_empty"), this.font);
        addRenderableWidget(previewLabel);
        y += previewH + 4;

        // § § § Status errores — área más grande (32 px) p/ q/ no se corte.
        final int statusH = 32;
        statusLabel = new StringWidget(xCenter, y, panelW, statusH,
                AzoreaLang.text(""), this.font);
        addRenderableWidget(statusLabel);
        y += statusH + 6;

        // § § § Botones: Connect + Back
        addRenderableWidget(Button.builder(
                        AzoreaLang.text("invite.paste_button_connect"), btn -> onConnectClicked())
                .bounds(xCenter, y, panelW, 20)
                .build());
        y += 22;

        addRenderableWidget(Button.builder(
                        AzoreaLang.text("common.back"), btn -> this.minecraft.setScreen(parent))
                .bounds(xCenter, y, panelW, 20)
                .build());
    }

    /** EditBox responder: reintenta parsear el invite y refresca el preview. */
    private void onInviteTextChanged(final String raw) {
        if (raw == null || raw.isBlank()) {
            previewLabel.setMessage(AzoreaLang.text("invite.preview_empty"));
            setStatus("");
            return;
        }
        try {
            final AzoreaInviteBundle.Bundle b = AzoreaInviteBundle.decodeAndVerify(
                    raw, System.currentTimeMillis() / 1000L);
            previewLabel.setMessage(renderPreview(b));
            setStatus("");
        } catch (final IllegalArgumentException e) {
            final String reason = friendlyReason(e.getMessage());
            previewLabel.setMessage(AzoreaLang.text("invite.preview_invalid", reason));
            setStatus("");
        }
    }

    /** § § § Convierte el motivo crudo d/ la excepción en una clave i18n legible. */
    private static String friendlyReason(final String rawMsg) {
        if (rawMsg == null) return "—";
        final String m = rawMsg.toLowerCase();
        if (m.contains("formato") || m.contains("format") || m.contains("partes")) {
            return "§7(" + AzoreaLang.text("invite.error_format").getString() + ")";
        }
        if (m.contains("firma") || m.contains("signature") || m.contains("verify")
                || m.contains("firma no")) {
            return "§7(" + AzoreaLang.text("invite.error_signature").getString() + ")";
        }
        if (m.contains("expir") || m.contains("expirado") || m.contains("expired")) {
            return "§7(" + AzoreaLang.text("invite.error_expired").getString() + ")";
        }
        if (m.contains("id_mismatch") || m.contains("id no coincide")
                || m.contains("id ajena") || m.contains("id ajen")) {
            return "§7(" + AzoreaLang.text("invite.error_id_mismatch").getString() + ")";
        }
        return "§7" + abbreviate(rawMsg);
    }

    /**
     * § § § Renderiza el preview d/ un invite válido: host, azorea_id, mundo, MC y nº
     * d/ candidatos. SIN mostrar IPs literales — sólo el tipo (público / IPv6 / local)
     * y el conteo.
     */
    private Component renderPreview(final AzoreaInviteBundle.Bundle b) {
        final java.util.List<String> candidates = b.candidates();
        int publicCount = 0;
        int ipv6Count = 0;
        int localCount = 0;
        for (final String c : candidates) {
            if (c == null) continue;
            if (c.contains(":")) ipv6Count++;
            else if (c.startsWith("10.") || c.startsWith("192.168.") || c.startsWith("172.")
                    || c.equals("127.0.0.1")) localCount++;
            else publicCount++;
        }
        final String hostDisplay = (b.displayName() != null && !b.displayName().isBlank())
                ? b.displayName() : b.azoreaId();
        final String azoreaShort = b.azoreaId() != null && b.azoreaId().length() > 8
                ? b.azoreaId().substring(0, 4) + "…" + b.azoreaId().substring(b.azoreaId().length() - 4)
                : b.azoreaId();
        final String worldName = b.worldName() != null ? b.worldName() : "—";
        final String mcVersion = b.mcVersion() != null ? b.mcVersion() : "—";
        final String yourMc = net.minecraft.client.Minecraft.getInstance().getLaunchedVersion();

        // § § § Componemos el preview con saltos de línea (en MC, "\n" se respeta
        //   en StringWidget). 6 líneas caben en los 78 px d/ alto.
        final StringBuilder sb = new StringBuilder();
        sb.append(AzoreaLang.text("invite.preview_host", hostDisplay).getString()).append('\n');
        sb.append(AzoreaLang.text("invite.preview_azorea_id", azoreaShort).getString()).append('\n');
        sb.append(AzoreaLang.text("invite.preview_world", worldName).getString()).append('\n');
        sb.append(AzoreaLang.text("invite.preview_mc", mcVersion, yourMc).getString()).append('\n');
        if (candidates.isEmpty()) {
            sb.append(AzoreaLang.text("invite.paste_probing").getString());
        } else {
            sb.append(AzoreaLang.text("invite.preview_candidate_types",
                    publicCount, ipv6Count, localCount).getString());
        }
        return Component.literal(sb.toString());
    }

    private void onConnectClicked() {
        final String raw = pasteBox.getValue();
        if (raw == null || raw.isBlank()) {
            setStatus("invite.paste_empty");
            return;
        }

        // § D0: verificación local — auto-certificación (DA-8) + firma (DA-9).
        final AzoreaInviteBundle.Bundle b;
        try {
            b = AzoreaInviteBundle.decodeAndVerify(raw, System.currentTimeMillis() / 1000L);
        } catch (final IllegalArgumentException e) {
            // § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § §
            //   § FIX 1.4.1: el preview YA muestra el motivo legible (friendlyReason).
            //   No hace falta duplicarlo en statusLabel — el preview es la fuente
            //   de verdad para errores de verificación. Status queda vacío.
            // § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § §
            AzoreaNetLog.failure(Category.INVITE, "bundle-verify", "-", e.getMessage());
            return;
        }

        // ===== § D0.1 (hecho 2026-10-01): guardar al host como AMIGO =====
        //
        // El bundle ES la card (DA-10): trae la identidad COMPLETA del host — id,
        // nombre, claves y endpoint — verificada por auto-certificación + firma.
        // Antes se verificaba y se tiraba: el joiner conectaba UNA vez y después
        // no tenía ninguna forma de volver a encontrar al host (el item "Pendiente
        // menor D0.1" de AGENTS.md). Con esto el D0 queda cerrado de verdad.
        //
        // § Clave correcta = X25519, NO Ed25519 — y es MUY fácil liarlo porque el
        // bundle trae AMBAS:
        //   - friend.publicKeyBase64 se consume en AzoreaInviteService →
        //     AzoreaCrypto.encryptForRecipient() que hace ECDH **X25519**. Meter la
        //     Ed25519 (la de firmar) haría que sendInvite fallara al descifrar.
        //   - Es el MISMO formato que trae TrackerProtocol.Identity.publicKeyBase64
        //     (se serializa literalmente como "x25519_key=" en canonicalForSigning),
        //     así que announce→lookup→friend y bundle→friend quedan idénticos.
        final AzoreaFriendsService friendsSvc = AzoreaMod.get().friendsService();
        String friendNote = "";
        if (friendsSvc != null && b.x25519Pub() != null && b.x25519Pub().length > 0) {
            final String hostName = (b.displayName() != null && !b.displayName().isBlank())
                    ? b.displayName() : b.azoreaId();
            final String x25519B64 = java.util.Base64.getEncoder().encodeToString(b.x25519Pub());
            final boolean added = friendsSvc.addByAzoreaId(b.azoreaId(), hostName, x25519B64);
            // addByAzoreaId devuelve false si YA existía (aunque haya completado la
            // clave) — para el mensaje lo que importa es si AHORA es amigo.
            final boolean isFriend = friendsSvc.findByAzoreaId(b.azoreaId()).isPresent();
            AzoreaNetLog.milestone(Category.INVITE,
                    added ? "friend-add" : "friend-sync",
                    "id=" + b.azoreaId() + " name=" + hostName
                            + " claveX25519=" + (x25519B64.length() > 0)
                            + " previo=" + !added);
            if (isFriend) {
                friendNote = " §8· amigo ✓";
            }
        }

        // § D0/T3: puede traer varios endpoints (v4 pública, IPv6, LAN). En vez de
        // adivinar, SONDEAMOS cada uno con un connect TCP corto y nos quedamos con
        // el primero que responda. El sondeo corre en el joiner ⇒ de verdad mide
        // la alcanzabilidad desde fuera (desde el host sería engañoso).
        final java.util.List<String> candidates = b.candidates();

        AzoreaNetLog.milestone(Category.CONNECT, "invite-verify",
                "id=" + b.azoreaId() + " name=" + b.displayName()
                        + " port=" + b.port() + " candidatos=" + candidates
                        // § `game` no viaja desde v2 ⇒ puede ser null en bundles nuevos.
                        + " game=" + (b.gameId() != null ? b.gameId() : "-")
                        + " world=" + b.worldName()
                        + " mc=" + b.mcVersion());

        // ===== § FIX crash 2026-09-30: no conectar con un mundo cargado =====
        //
        // Vanilla NUNCA llama a ConnectScreen.startConnecting() con level != null:
        // sus 3 únicos llamantes (JoinMultiplayerScreen, QuickPlay,
        // ClientCommonPacketListenerImpl) corren todos desde el menú principal.
        //
        // startConnecting() → Minecraft.disconnect() → Minecraft.java:2174
        //     while (!integratedserver.isShutdown()) this.runTick(false);
        // Espera SIN límite a que el servidor integrado muera, y startConnecting
        // NO llama antes a singleplayerServer.halt(true) — eso solo lo hace
        // emergencySave() (Minecraft.java:1370).
        //
        // Síntoma real (2026-09-30, cliente del General): el log se cortó justo
        // tras nuestro "sondeo:", JAMÁS llegó "Connecting to …" (que
        // ConnectScreen:88 loguea siempre), sin crash report y sin hs_err ⇒
        // congelación, no excepción. Los .mca siguieron escribiéndose +105 s
        // después = servidor integrado aún corriendo con el render thread parado.
        //
        // Restauramos el invariante de vanilla: conectar SOLO desde el título.
        if (this.minecraft.level != null
                || this.minecraft.getSingleplayerServer() != null) {
            AzoreaNetLog.failure(Category.CONNECT, "join-blocked", "-",
                    "level != null / integrated server vivo — startConnecting prohibido");
            setStatus("invite.paste_blocked_in_world");
            this.minecraft.gui.getChat().addMessage(Component.literal(
                    "§c§l[Azorea] §cNo puedes unirte estando dentro de tu mundo§r\n"
                            + "§7Minecraft solo permite conectar desde el menú principal y\n"
                            + "§7forzarlo desde dentro congela el cliente (bug confirmado).\n"
                            + "§7Arreglo: §fEsc → Guardar y salir al título§7 →\n"
                            + "§7§fAzorea → Join by Invite§7 → pegar el invite."));
            return;
        }

        setStatus("invite.paste_probing");

        Thread.startVirtualThread(() -> {
            String chosen = null;
            final StringBuilder trace = new StringBuilder();
            for (final String c : candidates) {
                final boolean ok = isReachable(c, b.port(), 1000);
                if (trace.length() > 0) trace.append(", ");
                trace.append(c).append("=").append(ok ? "OK" : "no");
                if (ok) {
                    chosen = c;
                    break;
                }
            }

            // ===== Fallback: NINGÚN candidato directo responde ⇒ hole-punch =====
            //
            // § Vía 2: los trackers salen del PROPIO bundle (firmados) ⇒ el amigo no
            //   configura nada. Sin ellos no hay rendezvous compartido y el punch es
            //   imposible — se dice, ⊘ se falla en silencio.
            String punchedAddr = null;
            if (chosen == null) {
                punchedAddr = tryPunch(b);
            }

            final String target = chosen != null ? chosen : candidates.get(0);
            final boolean probed = chosen != null || punchedAddr != null;
            final String addr;
            final String origin;
            if (punchedAddr != null) {
                addr = punchedAddr;
                origin = "punch v/ " + b.trackers().size() + " tracker(s) del invite";
            } else {
                addr = (target.contains(":") ? "[" + target + "]" : target) + ":" + b.port();
                origin = probed ? "sondeo OK" : "sin respuesta, se prueba igual";
            }
            AzoreaNetLog.info(Category.CONNECT, "sondeo: " + trace
                    + (punchedAddr != null ? " → directo caído → " + punchedAddr
                            : probed ? " → " + target : " → ninguno, se prueba " + target));

            this.minecraft.execute(() -> {
                setStatus(probed
                        ? "invite.paste_connecting"
                        : "invite.paste_probing_fallback");
                final String name = "Azorea: " + (b.displayName() != null
                        && !b.displayName().isBlank() ? b.displayName() : b.azoreaId());
                final ServerData data = new ServerData(name, addr, ServerData.Type.OTHER);
                ConnectScreen.startConnecting(this, this.minecraft,
                        ServerAddress.parseString(addr), data, false, (TransferState) null);
            });
        });
    }

    /**
     * Fallback d/ punch c/ los trackers q/ <b>trajo el bundle</b>.
     *
     * <p>§ La cadena completa (observe → announce → punch → proxy) la hace
     * {@code AzoreaPunchManager.exchange}; aquí sólo se decide <b>contra qué
     * trackers</b>: primero los d/ l'invite (Vía 2, firmados), luego los nuestros.
     *
     * @return {@code 127.0.0.1:<puerto del proxy>} o {@code null} si no hubo ruta
     */
    private String tryPunch(final AzoreaInviteBundle.Bundle b) {
        final var me = com.azorea.mod.v1211.client.AzoreaPunchManager.Signer.fromMod();
        if (me == null) {
            AzoreaNetLog.failure(Category.CONNECT, "punch-fallback", b.azoreaId(), "sin identidad");
            return null;
        }
        final java.util.List<String> urls = new java.util.ArrayList<>();
        for (final String u : b.trackers()) {
            if (!urls.contains(u)) urls.add(u);           // § los del invite mandan
        }
        for (final String u : AzoreaMod.effectiveTrackerUrls()) {
            if (!urls.contains(u)) urls.add(u);
        }
        if (urls.isEmpty()) {
            AzoreaNetLog.failure(Category.CONNECT, "punch-fallback", b.azoreaId(),
                    "sin rendezvous: el invite no trae `trackers` y el local es loopback");
            return null;
        }
        AzoreaNetLog.info(Category.CONNECT, "directo caído ⇒ punch contra "
                + urls.size() + " tracker(s): " + String.join(", ", urls));

        final var punched = com.azorea.mod.v1211.client.AzoreaPunchManager
                .exchange(urls, me, b.azoreaId(), 15_000);
        if (punched.isEmpty()) {
            AzoreaNetLog.failure(Category.CONNECT, "punch-fallback", b.azoreaId(),
                    "sin ruta directa ⇒ queda sólo relay (DA-11)");
            return null;
        }
        final int localPort = com.azorea.mod.v1211.client.AzoreaLocalProxy
                .startJoinerProxy(punched.get(), "invite");
        if (localPort <= 0) return null;
        AzoreaNetLog.milestone(Category.CONNECT, "punch-success", "127.0.0.1:" + localPort);
        return "127.0.0.1:" + localPort;
    }

    /**
     * Sondeo TCP corto: ¿alguien escucha en esa dirección:puerto?
     *
     * <p>Nota: si responde, el servidor MC registrará una conexión que cerramos
     * enseguida (misma categoría que los escáneos de internet que recibe
     * cualquier servidor; inofensivo). A cambio el joiner deja de adivinar y
     * se conecta a la dirección que de verdad está viva.
     */
    private static boolean isReachable(final String host, final int port, final int timeoutMs) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (final Exception e) {
            return false;
        }
    }

    /** El statusLabel no hace wrap (220 px) — acortar el motivo para que se vea. */
    private static String abbreviate(final String msg) {
        if (msg == null) return "invite inválido";
        final String oneLine = msg.replace('\n', ' ');
        return oneLine.length() <= 42 ? oneLine : oneLine.substring(0, 39) + "…";
    }

    private void setStatus(final String key) {
        if (key == null || key.isEmpty()) {
            statusLabel.setMessage(Component.empty());
        } else {
            statusLabel.setMessage(AzoreaLang.text(key));
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
