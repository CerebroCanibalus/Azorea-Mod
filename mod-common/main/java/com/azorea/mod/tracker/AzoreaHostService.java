// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.tracker;

import com.azorea.mod.AzoreaConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Servicio de hosting Azorea (ver AGENTS.md § DA-5 + DA-6 + F5.2).
 *
 * <p>Programa y mantiene un anuncio en los trackers configurados.
 * Refresca automáticamente cada TTL/2. Limpia en stop().
 *
 * <p><b>F5.2:</b> el Announcement ya NO contiene Connection (host/port). Solo
 * {@link TrackerProtocol.Identity} (azorea_id + display_name + public_key).
 * La IP/port del host se entrega al recipient solo en encrypted blobs vía
 * POST /invite (F5.2b con crypto).
 *
 * <p>§ Seguridad:
 * <ul>
 *   <li>Tokens generados con SecureRandom.</li>
 *   <li>host_token nunca se loguea.</li>
 *   <li>Errores de red no crashean; se loguean y el servicio sigue intentando.</li>
 *   <li>Tracker nunca ve la IP/port del host.</li>
 * </ul>
 */
public final class AzoreaHostService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzoreaHostService.class);

    private final AzoreaTrackerClient client;
    /** § F9: identidad con la que se firman los anuncios. Null = anuncios sin firma. */
    private final com.azorea.mod.v1211.identity.AzoreaIdentityService identity;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<HostSession> currentSession = new AtomicReference<>();

    public AzoreaHostService(final AzoreaTrackerClient client) {
        this(client, null, defaultScheduler());
    }

    /** § F9: constructor con identidad (para firmar anuncios). */
    public AzoreaHostService(final AzoreaTrackerClient client,
                             final com.azorea.mod.v1211.identity.AzoreaIdentityService identity) {
        this(client, identity, defaultScheduler());
    }

    /** Constructor para tests (inyecta scheduler, sin identidad → anuncios sin firma). */
    public AzoreaHostService(final AzoreaTrackerClient client, final ScheduledExecutorService scheduler) {
        this(client, null, scheduler);
    }

    /** Constructor completo. */
    public AzoreaHostService(final AzoreaTrackerClient client,
                             final com.azorea.mod.v1211.identity.AzoreaIdentityService identity,
                             final ScheduledExecutorService scheduler) {
        this.client = Objects.requireNonNull(client, "client");
        this.identity = identity;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    private static ScheduledExecutorService defaultScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "azorea-host-refresh");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * § F9: sella un anuncio con la Ed25519 de la identidad.
     *
     * <p>Si no hay identidad disponible (tests, setup temprano) devuelve el anuncio
     * tal cual — el tracker lo rechazará, pero eso es correcto: sin identidad no hay
     * hosting anunciado.
     */
    private TrackerProtocol.Announcement sign(final TrackerProtocol.Announcement a) {
        if (identity == null || identity.getIdentity() == null) {
            LOGGER.warn("Anuncio sin firmar: identidad no inicializada (game_id={}). "
                    + "El tracker F9 lo rechazará.", a.gameId());
            return a;
        }
        try {
            return TrackerProtocol.signAnnounce(
                    a,
                    identity.signingPublicKey(),
                    identity.ed25519PrivateKey(),
                    identity.hwCommit());
        } catch (final Exception e) {
            LOGGER.error("No pude firmar el anuncio (game_id={}): {}", a.gameId(), e.getMessage());
            return a;
        }
    }

    /**
     * Inicia un nuevo host session.
     *
     * @param config configuración del juego (host identity, mc version, max players, etc.)
     * @return sesión creada, o vacío si ya hay una activa
     */
    public Optional<HostSession> startHost(final HostConfig config) {
        Objects.requireNonNull(config, "config");
        if (currentSession.get() != null) {
            LOGGER.warn("Ya hay un host session activo. Llama stopHost() primero.");
            return Optional.empty();
        }

        final String gameId = TrackerProtocol.newGameId();
        final String hostToken = TrackerProtocol.newHostToken();
        final String inviteCode = TrackerProtocol.newInviteCode();

        final TrackerProtocol.Announcement announcement = new TrackerProtocol.Announcement(
                TrackerProtocol.AZOREA_PROTOCOL_VERSION,
                TrackerProtocol.AZOREA_VERSION,
                gameId,
                hostToken,
                config.hostIdentity(),
                config.mcVersion(),
                config.neoForgeVersion(),
                List.of(new TrackerProtocol.ModEntry(AzoreaConstants.MOD_ID,
                        TrackerProtocol.AZOREA_VERSION, true)),
                config.maxPlayers(),
                1, // currentPlayers al iniciar (host)
                config.worldName(),
                inviteCode,
                System.currentTimeMillis() / 1000L,
                config.ttlSeconds());

        // § F9: sellar con Ed25519 antes de anunciar — el tracker verifica identidad.
        final TrackerProtocol.Announcement signed = sign(announcement);

        final Optional<TrackerProtocol.AnnounceResponse> response = client.announce(signed);
        // § F5.2: anuncia presence (host se une a su propio game).
        client.presence(new TrackerProtocol.PresenceUpdate(
                gameId, config.hostIdentity(), "join", System.currentTimeMillis()));
        if (response.isEmpty()) {
            // § F4.1: announce fallido NO debe bloquear el hosting local.
            // El puerto TCP queda abierto y el juego es jugable; lo que se pierde
            // es solo la discoverability via tracker. Log warning + continuar.
            LOGGER.warn("Announce falló en todos los trackers. El juego queda hosteado en {} pero NO será descubrible vía tracker.",
                    config.bindAddress());
            // Devolvemos HostSession con expiresAt = TTL local (sin refresh).
            final long fallbackExpiry = (System.currentTimeMillis() / 1000L) + signed.ttlSeconds();
            final HostSession session = new HostSession(
                    gameId, hostToken, inviteCode, signed, fallbackExpiry);
            currentSession.set(session);
            LOGGER.warn("Host session LOCAL (sin tracker): game_id={}, invite_code={}",
                    gameId, inviteCode);
            return Optional.of(session);
        }

        final HostSession session = new HostSession(
                gameId, hostToken, inviteCode,
                signed,
                response.get().expiresAt());
        currentSession.set(session);

        // Programar refresh cada TTL/2.
        final long refreshInterval = Math.max(30, announcement.ttlSeconds() / 2);
        scheduler.scheduleAtFixedRate(
                () -> refreshSession(session),
                refreshInterval, refreshInterval, TimeUnit.SECONDS);

        LOGGER.info("Host session iniciado: game_id={}, invite_code={} (refresca cada {}s)",
                gameId, inviteCode, refreshInterval);
        return Optional.of(session);
    }

    /**
     * Detiene el host session activo y elimina el anuncio de todos los trackers.
     */
    public boolean stopHost() {
        final HostSession session = currentSession.getAndSet(null);
        if (session == null) {
            return false;
        }
        // § Bug fix (2026-09-26): NO shutdown el scheduler global aquí.
        // Si lo terminamos, un startHost() posterior lanza RejectedExecutionException.
        // El scheduler vive tanto como el servicio. stopHost() solo cancela las tareas
        // pendientes vía purge() (no shutdown).
        if (scheduler instanceof final java.util.concurrent.ScheduledThreadPoolExecutor stpe) {
            stpe.purge();
        }
        // § F5.2: anuncia leave antes de delete.
        if (session.announcement() != null && session.announcement().hostIdentity() != null) {
            client.presence(new TrackerProtocol.PresenceUpdate(
                    session.gameId(), session.announcement().hostIdentity(), "leave",
                    System.currentTimeMillis()));
        }
        final boolean deleted = client.delete(session.gameId(), session.hostToken());
        LOGGER.info("Host session detenido: game_id={} (delete trackers={})",
                session.gameId(), deleted ? "ok" : "fail");
        return deleted;
    }

    public Optional<HostSession> currentSession() {
        return Optional.ofNullable(currentSession.get());
    }

    private void refreshSession(final HostSession session) {
        if (currentSession.get() != session) {
            return; // Sesión reemplazada; no refrescar.
        }
        LOGGER.debug("Refrescando anuncio game_id={}", session.gameId());
        // Reutilizamos la misma game_id + host_token + host_identity, solo actualizamos timestamp.
        final TrackerProtocol.Announcement refreshed = new TrackerProtocol.Announcement(
                session.announcement().azoreaProtocol(),
                session.announcement().azoreaVersion(),
                session.announcement().gameId(),
                session.announcement().hostToken(),
                session.announcement().hostIdentity(),
                session.announcement().mcVersion(),
                session.announcement().neoForgeVersion(),
                session.announcement().mods(),
                session.announcement().maxPlayers(),
                session.announcement().currentPlayers(),
                session.announcement().worldName(),
                session.announcement().inviteCode(),
                System.currentTimeMillis() / 1000L,
                session.announcement().ttlSeconds());
        // § F9: el timestamp cambió → la firma debe recalcularse.
        client.announce(sign(refreshed));
    }

    public void shutdown() {
        stopHost();
        scheduler.shutdownNow();
    }

    // ===== Tipos =====

    /**
     * Configuración para iniciar un host session (F5.2).
     *
     * <p>{@code hostIdentity} reemplaza el antiguo {@code hostDisplayName} — ahora
     * la identidad del host es completa (azorea_id + display_name + public_key) para
     * que el recipient pueda cifrar invites dirigidos.
     *
     * <p>{@code bindAddress} sigue siendo local al host (no se publica).
     */
    public record HostConfig(
            TrackerProtocol.Identity hostIdentity,
            String mcVersion,
            String neoForgeVersion,
            String bindAddress,
            int maxPlayers,
            String worldName,
            int ttlSeconds
    ) {
        public HostConfig {
            if (hostIdentity == null) {
                throw new IllegalArgumentException("hostIdentity no puede ser null");
            }
            if (maxPlayers < 1) maxPlayers = 8;
            if (ttlSeconds <= 0) ttlSeconds = TrackerProtocol.DEFAULT_TTL_SECONDS;
        }
    }

    public record HostSession(
            String gameId,
            String hostToken,    // NUNCA loguear
            String inviteCode,
            TrackerProtocol.Announcement announcement,
            long expiresAt
    ) {
    }
}