// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.tracker;

import com.azorea.mod.v1211.identity.AzoreaIdentityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Inbox de invites Azorea (ver AGENTS.md § F7.2).
 *
 * <p>Pollea el tracker cada N segundos, descifra los invites pendientes usando
 * la private key del identity, y los almacena para que la UI los presente.
 *
 * <p>§ Lifecycle:
 * <ul>
 *   <li>{@link #start(int)}: arranca el polling. Una sola vez.</li>
 *   <li>{@link #stop()}: para el polling.</li>
 *   <li>Notifica a listeners (UI) cuando llegan invites nuevos.</li>
 * </ul>
 *
 * <p>§ Threading: el poll corre en un ScheduledExecutorService daemon thread.
 * El callback se invoca desde ese thread; los listeners deben ser thread-safe
 * (usar {@link CopyOnWriteArrayList} o similar).
 */
public final class AzoreaInviteInbox {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzoreaInviteInbox.class);

    private final AzoreaInviteService inviteService;
    private final AzoreaIdentityService identityService;
    private final AtomicReference<ScheduledFuture<?>> pollingTask = new AtomicReference<>();
    private final ScheduledExecutorService scheduler;
    private final List<AzoreaInviteService.DecryptedInvite> pending = new CopyOnWriteArrayList<>();
    private final List<NewInviteListener> listeners = new CopyOnWriteArrayList<>();

    public AzoreaInviteInbox(final AzoreaInviteService inviteService,
                             final AzoreaIdentityService identityService) {
        this.inviteService = inviteService;
        this.identityService = identityService;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "azorea-invite-poll");
            t.setDaemon(true);
            return t;
        });
    }

    /** Arranca el polling si no estaba ya activo. Idempotente. */
    public synchronized void start(final int intervalSeconds) {
        if (pollingTask.get() != null) {
            LOGGER.debug("InviteInbox ya está corriendo.");
            return;
        }
        final ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(
                this::pollOnce, 5, intervalSeconds, TimeUnit.SECONDS);
        pollingTask.set(task);
        LOGGER.info("InviteInbox polling cada {}s", intervalSeconds);
    }

    /** Para el polling. No espera a que termine la task actual. */
    public synchronized void stop() {
        final ScheduledFuture<?> task = pollingTask.getAndSet(null);
        if (task != null) {
            task.cancel(false);
        }
        scheduler.shutdownNow();
    }

    /** Ejecuta una poll inmediata (además del schedule). Útil al abrir inbox screen. */
    public void pollNow() {
        scheduler.execute(this::pollOnce);
    }

    /** Lista actual de invites pendientes (thread-safe). */
    public List<AzoreaInviteService.DecryptedInvite> getPending() {
        return List.copyOf(pending);
    }

    /** Cantidad de invites pendientes. */
    public int pendingCount() {
        return pending.size();
    }

    /** Elimina un invite (cuando el user lo acepta o rechaza). */
    public void remove(final AzoreaInviteService.DecryptedInvite invite) {
        pending.remove(invite);
    }

    /** Añade listener para cuando llegan invites nuevos. */
    public void addListener(final NewInviteListener listener) {
        listeners.add(listener);
    }

    private void pollOnce() {
        if (identityService == null || identityService.getIdentity() == null) {
            return; // identity no inicializada; skip
        }
        final List<AzoreaInviteService.DecryptedInvite> decrypted;
        try {
            decrypted = inviteService.receiveInvites();
        } catch (Exception e) {
            LOGGER.warn("Error en poll invites: {}", e.getMessage());
            return;
        }
        for (final AzoreaInviteService.DecryptedInvite d : decrypted) {
            // Deduplica por game_id + from azorea_id + timestamp (re-poll no duplica).
            final boolean already = pending.stream().anyMatch(p ->
                    p.raw().gameId().equals(d.raw().gameId())
                            && p.raw().fromIdentity().azoreaId().equals(d.raw().fromIdentity().azoreaId())
                            && p.raw().sentAt() == d.raw().sentAt());
            if (already) continue;
            pending.add(d);
            LOGGER.info("Invite recibido de {} (game={})",
                    d.raw().fromIdentity().azoreaId(), d.raw().gameId());
            for (final NewInviteListener l : listeners) {
                try {
                    l.onNewInvite(d);
                } catch (Exception e) {
                    LOGGER.warn("Listener failed: {}", e.getMessage());
                }
            }
        }
    }

    /** Callback para nuevos invites. */
    @FunctionalInterface
    public interface NewInviteListener {
        void onNewInvite(AzoreaInviteService.DecryptedInvite invite);
    }
}