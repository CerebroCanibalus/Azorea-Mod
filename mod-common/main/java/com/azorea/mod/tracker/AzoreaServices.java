// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.tracker;

import com.azorea.mod.v1211.identity.AzoreaIdentityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Fachada para acceder a los servicios de Azorea (Host, Discovery, Join, Presence, Invite).
 *
 * <p>§ DA-6 (UI in-game propia): la UI consumirá estos servicios directamente,
 * sin pasar por comandos Brigadier.
 *
 * <p>§ F5.2b: incluye PresenceService e InviteService (con crypto X25519+ChaCha20).
 *
 * <p>§ Seguridad: validar args en cada servicio; no loguear host_token ni tokens privados.
 */
public final class AzoreaServices {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzoreaServices.class);

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final AzoreaTrackerClient trackerClient;
    private final AzoreaHostService hostService;
    private final AzoreaDiscoveryService discoveryService;
    private final AzoreaJoinService joinService;
    private final AzoreaPresenceService presenceService;
    private final AzoreaInviteService inviteService;

    /** Constructor con defaults (5s connect / 10s request). Identity puede ser null (modo no-op invite). */
    public AzoreaServices(final List<String> trackerUrls, final AzoreaIdentityService identityService) {
        this(trackerUrls, identityService, DEFAULT_CONNECT_TIMEOUT, DEFAULT_REQUEST_TIMEOUT);
    }

    /** Constructor con timeouts configurables. */
    public AzoreaServices(final List<String> trackerUrls,
                          final AzoreaIdentityService identityService,
                          final Duration connectTimeout,
                          final Duration requestTimeout) {
        Objects.requireNonNull(trackerUrls, "trackerUrls");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (trackerUrls.isEmpty()) {
            this.trackerClient = null;
            this.hostService = null;
            this.discoveryService = null;
            this.joinService = null;
            this.presenceService = null;
            this.inviteService = null;
            LOGGER.warn("AzoreaServices inicializado SIN trackers (modo no-op). Configurar trackers en F2.6.");
        } else {
            this.trackerClient = new AzoreaTrackerClient(trackerUrls, connectTimeout, requestTimeout);
            this.hostService = new AzoreaHostService(trackerClient, identityService);
            this.discoveryService = new AzoreaDiscoveryService(trackerClient);
            this.joinService = new AzoreaJoinService(discoveryService);
            this.presenceService = new AzoreaPresenceService(trackerClient);
            this.inviteService = identityService != null
                    ? new AzoreaInviteService(trackerClient, identityService)
                    : null;
            LOGGER.info("AzoreaServices inicializado c/ {} tracker(s) (connect={}s, request={}s): {}",
                    trackerUrls.size(), connectTimeout.toSeconds(), requestTimeout.toSeconds(), trackerUrls);
        }
    }

    /** Backward-compat: sin identity service. */
    public AzoreaServices(final List<String> trackerUrls,
                          final Duration connectTimeout,
                          final Duration requestTimeout) {
        this(trackerUrls, null, connectTimeout, requestTimeout);
    }

    public boolean hasTrackers() {
        return trackerClient != null;
    }

    public AzoreaTrackerClient trackerClient() {
        return trackerClient;
    }

    public AzoreaHostService host() {
        return hostService;
    }

    public AzoreaDiscoveryService discovery() {
        return discoveryService;
    }

    public AzoreaJoinService join() {
        return joinService;
    }

    public AzoreaPresenceService presence() {
        return presenceService;
    }

    public AzoreaInviteService invite() {
        return inviteService;
    }

    public void shutdown() {
        if (hostService != null) hostService.shutdown();
        if (trackerClient != null) trackerClient.shutdown();
    }
}
