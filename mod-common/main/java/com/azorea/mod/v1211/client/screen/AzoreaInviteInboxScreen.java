// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client.screen;

import com.azorea.mod.tracker.AzoreaInviteInbox;
import com.azorea.mod.tracker.AzoreaInviteService;
import com.azorea.mod.v1211.AzoreaLang;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pantalla de inbox de invites pendientes (ver AGENTS.md § F7.2).
 *
 * <p>Muestra la lista de invites descifrados (vía {@link AzoreaInviteInbox}).
 * Click en una entry abre {@link AzoreaInviteAcceptScreen} con detalles + Connect.
 */
public final class AzoreaInviteInboxScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzoreaInviteInboxScreen.class);
    private static final Component TITLE = AzoreaLang.text("invite.inbox_title");

    private final Screen parent;
    private InviteList listWidget;
    private StringWidget statusLabel;

    public AzoreaInviteInboxScreen(final Screen parent) {
        super(TITLE);
        this.parent = parent;
    }

    @Override
    protected void init() {
        final AzoreaInviteInbox inbox = com.azorea.mod.v1211.AzoreaMod.get().inviteInbox();

        final int xCenter = (this.width - 240) / 2;
        int y = 24;

        addRenderableWidget(new StringWidget(xCenter, y, 240, 20, TITLE, this.font));
        y += 26;

        statusLabel = new StringWidget(xCenter, y, 240, 12, AzoreaLang.text(""), this.font);
        addRenderableWidget(statusLabel);
        y += 14;

        listWidget = new InviteList(this.minecraft, this.width - 40, this.height - y - 40, y, 24, inbox);
        listWidget.setPosition(20, y);
        addRenderableWidget(listWidget);
        y = this.height - 32;

        final Button refreshButton = Button.builder(
                        AzoreaLang.text("common.refresh"),
                        btn -> {
                            if (inbox != null) inbox.pollNow();
                            listWidget.refresh();
                            refreshStatus();
                        })
                .bounds(xCenter, y, 110, 20)
                .build();
        addRenderableWidget(refreshButton);

        final Button backButton = Button.builder(
                        AzoreaLang.text("common.back"),
                        btn -> this.minecraft.setScreen(parent))
                .bounds(xCenter + 120, y, 110, 20)
                .build();
        addRenderableWidget(backButton);

        refreshStatus();
    }

    private void refreshStatus() {
        final AzoreaInviteInbox inbox = com.azorea.mod.v1211.AzoreaMod.get().inviteInbox();
        if (inbox == null) {
            statusLabel.setMessage(AzoreaLang.text("invite.inbox_no_inbox"));
            return;
        }
        statusLabel.setMessage(AzoreaLang.text("invite.inbox_count", inbox.pendingCount()));
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

    /** Lista interna scrollable de invites. */
    private final class InviteList extends ObjectSelectionList<InviteList.Entry> {

        public InviteList(final net.minecraft.client.Minecraft mc,
                        final int width, final int height, final int y0,
                        final int itemHeight, final AzoreaInviteInbox inbox) {
            super(mc, width, height, y0, itemHeight);
            refresh();
        }

        public void refresh() {
            this.clearEntries();
            final AzoreaInviteInbox inbox = com.azorea.mod.v1211.AzoreaMod.get().inviteInbox();
            if (inbox == null) return;
            final java.util.List<AzoreaInviteService.DecryptedInvite> all = inbox.getPending();
            if (all.isEmpty()) {
                this.addEntry(new Entry(null));
                this.setSelected(null);
            } else {
                for (final AzoreaInviteService.DecryptedInvite inv : all) {
                    this.addEntry(new Entry(inv));
                }
            }
        }

        @Override
        public int getRowWidth() {
            return this.width - 12;
        }

        private final class Entry extends ObjectSelectionList.Entry<Entry> {
            private final AzoreaInviteService.DecryptedInvite invite;

            public Entry(final AzoreaInviteService.DecryptedInvite inv) {
                this.invite = inv;
            }

            @Override
            public void render(final GuiGraphics gui, final int index, final int top, final int left,
                               final int width, final int height, final int mouseX, final int mouseY,
                               final boolean hovered, final float partialTicks) {
                if (invite == null) {
                    gui.drawString(AzoreaInviteInboxScreen.this.font,
                            AzoreaLang.text("invite.inbox_empty"), left + 4, top + 4, 0x888888);
                    return;
                }
                final AzoreaInviteService.ConnectionInfo info = invite.info();
                final String from = invite.raw().fromIdentity().displayName();
                final String host = info.host() + ":" + info.port();
                gui.drawString(AzoreaInviteInboxScreen.this.font,
                        AzoreaLang.text("invite.inbox_from_display", from), left + 4, top + 4, 0xFFFFFF);
                gui.drawString(AzoreaInviteInboxScreen.this.font,
                        AzoreaLang.text("invite.inbox_meta", host, invite.raw().gameId().substring(0, 8)),
                        left + 4, top + 14, 0xCCCCCC);
            }

            @Override
            public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
                if (button == 0 && invite != null) {
                    final AzoreaInviteInbox inbox = com.azorea.mod.v1211.AzoreaMod.get().inviteInbox();
                    if (inbox != null) inbox.remove(invite);
                    AzoreaInviteInboxScreen.this.minecraft.setScreen(
                            new AzoreaInviteAcceptScreen(AzoreaInviteInboxScreen.this, invite));
                    return true;
                }
                return false;
            }

            @Override
            public Component getNarration() {
                if (invite == null) return AzoreaLang.text("invite.inbox_empty");
                return AzoreaLang.text("invite.inbox_from_display", invite.raw().fromIdentity().displayName());
            }
        }
    }
}