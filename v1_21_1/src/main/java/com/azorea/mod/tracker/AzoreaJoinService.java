// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.tracker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;

/**
 * Servicio de join Azorea (ver AGENTS.md § DA-5 + DA-6 + F5.2).
 *
 * <p>Valida invite codes y construye JoinResult. En F5.2 ya NO expone
 * {@code Connection} (IP/port) — solo {@code hostIdentity} + {@code inviteCode}.
 * El recipient necesita pedir un invite cifrado al host (F5.2b) para obtener
 * la IP real.
 */
public final class AzoreaJoinService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzoreaJoinService.class);

    private final AzoreaDiscoveryService discoveryService;

    public AzoreaJoinService(final AzoreaDiscoveryService discoveryService) {
        this.discoveryService = Objects.requireNonNull(discoveryService, "discoveryService");
    }

    /**
     * Valida un invite_code sin contactar al tracker.
     */
    public boolean isValidInviteCode(final String code) {
        return TrackerProtocol.isValidInviteCode(code);
    }

    /**
     * Prepara un JoinResult a partir de un invite_code.
     *
     * <p>Por ahora solo valida el formato y prepara metadata; el lookup en tracker
     * (lookup-by-invite-code) se añadirá en F5.2b cuando llegue el endpoint
     * correspondiente en TrackerServer.
     */
    public Optional<JoinResult> prepareJoinByInviteCode(final String inviteCode) {
        if (!isValidInviteCode(inviteCode)) {
            LOGGER.warn("invite_code inválido: {}", inviteCode);
            return Optional.empty();
        }
        return Optional.of(new JoinResult(
                null,            // gameId desconocido sin lookup
                inviteCode,
                null,            // hostIdentity desconocido sin lookup
                "Lookup-by-invite-code se implementa en F5.2b (TODO)"));
    }

    /**
     * Busca un juego por game_id en los trackers y devuelve JoinResult.
     *
     * <p>Implementación: hace listGames y filtra por gameId. v2 ya NO devuelve
     * Connection — solo hostIdentity + inviteCode.
     */
    public Optional<JoinResult> joinById(final String gameId) {
        Objects.requireNonNull(gameId, "gameId");
        if (!TrackerProtocol.isValidGameId(gameId)) {
            LOGGER.warn("game_id inválido: {}", gameId);
            return Optional.empty();
        }
        for (final TrackerProtocol.GameListing g : discoveryService.listGames(
                new TrackerProtocol.Filters(null, "azorea", 50))) {
            if (gameId.equals(g.gameId())) {
                return Optional.of(new JoinResult(
                        gameId,
                        g.inviteCode(),
                        g.hostIdentity(),
                        null));
            }
        }
        return Optional.empty();
    }

    /**
     * Resultado de un join. En v2 (F5.2) NO contiene Connection — solo metadata
     * pública. El recipient que quiera la IP real debe pedir un invite cifrado
     * al host vía POST /invite (F5.2b).
     */
    public record JoinResult(
            String gameId,
            String inviteCode,
            TrackerProtocol.Identity hostIdentity,
            String statusMessage
    ) {
    }
}