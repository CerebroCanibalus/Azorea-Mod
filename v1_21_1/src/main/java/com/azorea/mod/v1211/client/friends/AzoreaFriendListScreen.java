// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client.friends;

import com.azorea.mod.v1211.AzoreaMod;
import com.azorea.mod.v1211.identity.AzoreaIdentityService;
import com.azorea.mod.tracker.AzoreaInviteService;
import com.azorea.mod.tracker.TrackerProtocol;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.List;

/**
 * Pantalla de friend list v2 (ver AGENTS.md § F4.5 + F5.2).
 *
 * <p>v2: añade azorea_id al agregar, muestra online indicator, click-to-invite
 * realmente envía invite cifrado al friend.
 *
 * <p>§ Acciones:
 * <ul>
 *   <li>Add (typed): displayName → addByDisplayName (legacy) o addByAzoreaId (si empieza con "AZ-").</li>
 *   <li>Click en entry: selecciona.</li>
 *   <li>"Invite to play": envía invite al friend online.</li>
 *   <li>"Remove": elimina el friend.</li>
 * </ul>
 */
public final class AzoreaFriendListScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzoreaFriendListScreen.class);
    private static final Component TITLE = Component.literal("Azorea — Friends");

    private final Screen parent;
    private EditBox addBox;
    private Button addButton;
    private StringWidget statusLabel;
    private FriendList friendListWidget;
    private Button inviteButton;
    private Button removeButton;

    public AzoreaFriendListScreen(final Screen parent) {
        super(TITLE);
        this.parent = parent;
    }

    @Override
    protected void init() {
        final AzoreaFriendsService svc = AzoreaMod.get().friendsService();
        if (svc == null) {
            LOGGER.error("FriendListScreen: AzoreaFriendsService no inicializado");
            return;
        }

        final int xCenter = (this.width - 200) / 2;
        int y = 20;
        addRenderableWidget(new StringWidget(xCenter, y, 200, 20,
                Component.literal("§6§lAzorea Friends List"), this.font));
        y += 22;

        // § F5.2b: muestra tu propio Azorea ID prominentemente con Copy.
        // Es la info que compartes con amigos para que te añadan.
        final String myAzoreaId = AzoreaMod.get().identityService() != null
                && AzoreaMod.get().identityService().getIdentity() != null
                ? AzoreaMod.get().identityService().getIdentity().azoreaId()
                : "AZ-UNKNOWN";
        addRenderableWidget(new StringWidget(xCenter, y, 200, 12,
                Component.literal("§7Your Azorea ID:"), this.font));
        y += 12;
        addRenderableWidget(new StringWidget(xCenter, y, 200, 12,
                Component.literal("§e" + myAzoreaId), this.font));
        y += 14;
        final Button copyMyIdButton = Button.builder(
                        Component.literal("§eCopy My Azorea ID"),
                        btn -> onCopyMyIdClicked())
                .bounds(xCenter, y, 200, 18)
                .build();
        addRenderableWidget(copyMyIdButton);
        y += 22;

        // Input box para agregar.
        addBox = new EditBox(this.font, xCenter, y, 140, 20,
                Component.literal("Friend Azorea ID (preferred) or MC name"));
        addBox.setMaxLength(40);
        addRenderableWidget(addBox);

        addButton = Button.builder(
                        Component.literal("Add"),
                        btn -> onAddClicked())
                .bounds(xCenter + 144, y, 56, 20)
                .build();
        addRenderableWidget(addButton);
        y += 26;

        statusLabel = new StringWidget(xCenter, y, 200, 12, Component.literal(""), this.font);
        addRenderableWidget(statusLabel);
        y += 14;

        friendListWidget = new FriendList(svc, this.minecraft, this.width - 40, this.height - y - 70, y, this.height - 64);
        friendListWidget.setPosition(20, y);
        addRenderableWidget(friendListWidget);
        y = this.height - 56;

        inviteButton = Button.builder(
                        Component.literal("§aInvite to play"),
                        btn -> onInviteClicked())
                .bounds(xCenter - 102, y, 100, 20)
                .build();
        addRenderableWidget(inviteButton);

        removeButton = Button.builder(
                        Component.literal("§cRemove"),
                        btn -> onRemoveClicked())
                .bounds(xCenter + 2, y, 100, 20)
                .build();
        addRenderableWidget(removeButton);
        y += 22;

        final Button backButton = Button.builder(
                        Component.literal("Done"),
                        btn -> this.minecraft.setScreen(parent))
                .bounds(xCenter, y, 200, 20)
                .build();
        addRenderableWidget(backButton);

        updateActionButtons();
        refreshStatus();

        // § Diseño SEAMLESS: auto-poll online status al abrir la pantalla.
        autoPollOnlineStatus();
    }

    private long lastPollTick = 0;
    private static final long POLL_INTERVAL_MS = 30_000L;  // 30s

    /** § SEAMLESS: refresca estado online cada 30s mientras la screen está abierta. */
    private void autoPollOnlineStatus() {
        final AzoreaFriendsService svc = AzoreaMod.get().friendsService();
        if (svc == null) return;
        // Fire-and-forget: cada friend lookup es async.
        Thread.startVirtualThread(() -> {
            for (final var friend : svc.getAll()) {
                if (friend.azoreaId() != null) {
                    svc.refreshOnlineStatus(friend);
                }
            }
            // Refrescar lista UI cuando terminen las llamadas (best effort).
            if (this.minecraft != null) {
                this.minecraft.execute(() -> friendListWidget.refresh());
            }
        });
    }

    @Override
    public void tick() {
        super.tick();
        final long now = System.currentTimeMillis();
        if (now - lastPollTick > POLL_INTERVAL_MS) {
            lastPollTick = now;
            autoPollOnlineStatus();
        }
    }

    /**
     * § F5.2b: copia el Azorea ID propio al clipboard.
     */
    private void onCopyMyIdClicked() {
        final String myAzoreaId = AzoreaMod.get().identityService() != null
                && AzoreaMod.get().identityService().getIdentity() != null
                ? AzoreaMod.get().identityService().getIdentity().azoreaId()
                : null;
        if (myAzoreaId == null) {
            setStatus("§cIdentity no inicializada.");
            return;
        }
        this.minecraft.keyboardHandler.setClipboard(myAzoreaId);
        setStatus("§aTu Azorea ID copiado al clipboard.");
        this.minecraft.gui.getChat().addMessage(
                Component.literal("§a[Azorea] Tu Azorea ID copiado: §e" + myAzoreaId));
    }

    private void onAddClicked() {
        final String input = addBox.getValue().trim();
        if (input.isEmpty()) {
            setStatus("§cName vacío.");
            return;
        }
        final AzoreaFriendsService svc = AzoreaMod.get().friendsService();
        if (svc == null) return;
        // Si empieza con "AZ-" + 24 chars, es un azorea_id.
        if (TrackerProtocol.isValidAzoreaId(input)) {
            // § F8.x: lookup es best-effort. Si falla (friend offline o en otro tracker),
            // guardar el friend de todas formas con displayName placeholder.
            // Cuando el friend aparezca online (LAN discovery o shared tracker), se actualiza.
            final var services = AzoreaMod.get().services();
            if (services != null && services.trackerClient() != null) {
                services.trackerClient().lookupFriend(input).ifPresentOrElse(
                        status -> {
                            if (svc.addByAzoreaId(input, status.displayName(), status.publicKeyBase64())) {
                                setStatus("§aAdded: " + status.displayName());
                                addBox.setValue("");
                                friendListWidget.refresh();
                            } else {
                                setStatus("§cAlready in list.");
                            }
                        },
                        () -> {
                            // § F8.x: lookup falló pero guardamos de todas formas.
                            // El friend puede estar en LAN (que el polling refrescará).
                            final String placeholderName = input.substring(0, 8) + "...";
                            if (svc.addByAzoreaId(input, placeholderName, null)) {
                                setStatus("§aAdded §7(offline): " + placeholderName
                                        + " §7— aparecerá online cuando esté en LAN.");
                                addBox.setValue("");
                                friendListWidget.refresh();
                            } else {
                                setStatus("§cAlready in list.");
                            }
                        });
            } else {
                // Sin servicios — guardar offline igual.
                final String placeholderName = input.substring(0, 8) + "...";
                if (svc.addByAzoreaId(input, placeholderName, null)) {
                    setStatus("§aAdded §7(offline): " + placeholderName);
                    addBox.setValue("");
                    friendListWidget.refresh();
                } else {
                    setStatus("§cAlready in list.");
                }
            }
        } else {
            // Legacy: add por displayName.
            if (svc.addByDisplayName(input)) {
                setStatus("§aAdded: " + input);
                addBox.setValue("");
                friendListWidget.refresh();
            } else {
                setStatus("§cAlready in list: " + input);
            }
        }
    }

    private void onInviteClicked() {
        final AzoreaFriend selected = getSelectedFriend();
        if (selected == null) return;
        final var services = AzoreaMod.get().services();
        if (services == null || services.host() == null) {
            setStatus("§cServicios no disponibles.");
            return;
        }
        final var hostSession = services.host().currentSession();
        if (hostSession.isEmpty()) {
            setStatus("§cNo estás hosteando. Inicia host primero.");
            return;
        }
        final AzoreaIdentityService identitySvc = AzoreaMod.get().identityService();
        if (identitySvc == null || identitySvc.getIdentity() == null) {
            setStatus("§cIdentity no inicializada.");
            return;
        }
        // Construir ConnectionInfo desde el autohost + hostSession.
        final var autohost = AzoreaMod.get().autohostService();
        // § F8.x: usar el mejor bind address del cache compartido (UPnP > STUN > LAN).
        // No depende de la pantalla padre — funciona igual desde pause menu o session screen.
        final String lanBind = autohost != null && autohost.currentHandle().isPresent()
                ? autohost.currentHandle().get().bindAddress() : "127.0.0.1:25565";
        final String bindAddress = com.azorea.mod.v1211.client.screen.AzoreaHostShared
                .getBestBindAddress(lanBind);
        final String host = bindAddress.substring(0, bindAddress.lastIndexOf(':'));
        final int port = Integer.parseInt(bindAddress.substring(bindAddress.lastIndexOf(':') + 1));
        // § F8.x: incluir relay info en ConnectionInfo (fallback si direct/punch fallan).
        final String relaySession = com.azorea.mod.v1211.client.screen.AzoreaHostConfigScreen.relaySessionId();
        final int relayPort = com.azorea.mod.v1211.client.screen.AzoreaHostConfigScreen.relayPort();
        final AzoreaInviteService.ConnectionInfo info = new AzoreaInviteService.ConnectionInfo(
                host, port, hostSession.get().hostToken(), System.currentTimeMillis(),
                relaySession, relayPort);
        // Identidad para fromIdentity.
        final TrackerProtocol.Identity fromIdentity = new TrackerProtocol.Identity(
                identitySvc.getIdentity().azoreaId(),
                identitySvc.getIdentity().displayName(),
                Base64.getEncoder().encodeToString(identitySvc.getIdentity().x25519PublicKey()));
        // Send via friends service.
        final AzoreaFriendsService friendsSvc = AzoreaMod.get().friendsService();
        final var ack = friendsSvc.sendInvite(selected, fromIdentity, hostSession.get().gameId(), info);
        if (ack.isPresent()) {
            setStatus("§aInvite enviado a " + selected.displayName());
            this.minecraft.gui.getChat().addMessage(
                    Component.literal("§a[Azorea] Invite enviado a " + selected.displayName()));
        } else {
            setStatus("§cNo se pudo enviar invite. ¿Friend online?");
        }
    }

    private void onRemoveClicked() {
        final AzoreaFriend selected = getSelectedFriend();
        if (selected == null) return;
        final AzoreaFriendsService svc = AzoreaMod.get().friendsService();
        if (svc == null) return;
        final String key = selected.azoreaId() != null ? selected.azoreaId() : selected.displayName();
        if (svc.remove(key)) {
            setStatus("§aRemoved: " + selected.displayName());
            friendListWidget.refresh();
            updateActionButtons();
        }
    }

    private AzoreaFriend getSelectedFriend() {
        if (friendListWidget == null) return null;
        final FriendList.Entry sel = friendListWidget.getSelected();
        return sel != null ? sel.friend : null;
    }

    private void updateActionButtons() {
        final AzoreaFriend sel = getSelectedFriend();
        final boolean has = sel != null;
        if (inviteButton != null) inviteButton.active = has;
        if (removeButton != null) removeButton.active = has;
    }

    private void setStatus(final String text) {
        statusLabel.setMessage(Component.literal(text));
    }

    private void refreshStatus() {
        final AzoreaFriendsService svc = AzoreaMod.get().friendsService();
        if (svc == null) return;
        setStatus("§7" + svc.size() + " friend(s)");
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

    /** Lista interna scrollable. */
    private final class FriendList extends net.minecraft.client.gui.components.ObjectSelectionList<FriendList.Entry> {

        public FriendList(final AzoreaFriendsService svc,
                          final net.minecraft.client.Minecraft mc,
                          final int width, final int height,
                          final int y0, final int itemHeight) {
            super(mc, width, height, y0, itemHeight);
            refresh();
        }

        public void refresh() {
            this.clearEntries();
            final AzoreaFriendsService svc = AzoreaMod.get().friendsService();
            if (svc == null) return;
            final List<AzoreaFriend> all = svc.getAll();
            if (all.isEmpty()) {
                this.addEntry(new Entry(AzoreaFriend.legacy("§7(no friends yet)")));
                this.setSelected(null);
            } else {
                for (final AzoreaFriend f : all) {
                    this.addEntry(new Entry(f));
                }
            }
        }

        @Override
        public int getRowWidth() {
            return this.width - 12;
        }

        @Override
        public void setSelected(final Entry entry) {
            super.setSelected(entry);
            AzoreaFriendListScreen.this.updateActionButtons();
        }

        private final class Entry extends net.minecraft.client.gui.components.ObjectSelectionList.Entry<FriendList.Entry> {
            private final AzoreaFriend friend;

            public Entry(final AzoreaFriend f) {
                this.friend = f;
            }

            @Override
            public void render(final GuiGraphics gui, final int index, final int top, final int left,
                               final int width, final int height, final int mouseX, final int mouseY,
                               final boolean hovered, final float partialTicks) {
                final String onlineDot = friend.online() ? "§a●" : "§8●";
                final String name = friend.displayName();
                gui.drawString(AzoreaFriendListScreen.this.font,
                        Component.literal(onlineDot + " §f" + name), left + 4, top + 4, 0xFFFFFF);
                final String idStr = friend.azoreaId() != null ? friend.azoreaId() : "§7legacy";
                final String statusStr = friend.online() ? "§aonline" : "§8offline";
                gui.drawString(AzoreaFriendListScreen.this.font,
                        Component.literal("§8" + idStr + " §7- " + statusStr),
                        left + 4, top + 14, 0x888888);
            }

            @Override
            public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
                if (button == 0) {
                    this.list.setSelected(this);
                    AzoreaFriendListScreen.this.updateActionButtons();
                    return true;
                }
                return false;
            }

            @Override
            public Component getNarration() {
                return Component.literal(friend.displayName());
            }
        }
    }
}