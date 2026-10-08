// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.tracker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;

/**
 * Servicio de presence (F5.2).
 *
 * <p>Cuando un player se une o sale de un game (hosting o joined), anuncia
 * al tracker vía POST /presence. Esto permite a otros peers ver si un friend
 * está online en un game (vía GET /friend/{id}).
 *
 * <p>§ Diseño:
 * <ul>
 *   <li>Operaciones idempotentes (join twice = noop, leave twice = noop).</li>
 *   <li>Errores de red no crashean; se loguean y se devuelven empty.</li>
 *   <li>El tracker es stateless — el presence expira junto con el announcement
 *       (TTL compartido) o cuando el player hace leave.</li>
 * </ul>
 *
 * <p>§ Lifecycle:
 * <ul>
 *   <li>Creado en AzoreaServices.</li>
 *   <li>El player anuncia presence cuando:
 *     <ol>
 *       <li>Empieza a hostear (join del host a su propio game).</li>
 *       <li>Hace leave del host.</li>
 *     </ol>
 *   </li>
 * </ul>
 *
 * <p>§ Limitaciones v1: no tracking de players que se unen al host via
 *   Minecraft vanilla (sin Azorea identity). Estos players aparecen en TAB
 *   pero no en /friend/<id> lookup.
 */
public final class AzoreaPresenceService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzoreaPresenceService.class);

    private final AzoreaTrackerClient client;

    public AzoreaPresenceService(final AzoreaTrackerClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * Anuncia que la identidad dada se unió al game.
     */
    public Optional<TrackerProtocol.PresenceResponse> join(
            final String gameId, final TrackerProtocol.Identity identity) {
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(identity, "identity");
        if (!TrackerProtocol.isValidGameId(gameId)) {
            throw new IllegalArgumentException("gameId inválido");
        }
        final TrackerProtocol.PresenceUpdate update = new TrackerProtocol.PresenceUpdate(
                gameId, identity, "join", System.currentTimeMillis());
        return client.presence(update);
    }

    /**
     * Anuncia que la identidad dada salió del game.
     */
    public Optional<TrackerProtocol.PresenceResponse> leave(
            final String gameId, final TrackerProtocol.Identity identity) {
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(identity, "identity");
        if (!TrackerProtocol.isValidGameId(gameId)) {
            throw new IllegalArgumentException("gameId inválido");
        }
        final TrackerProtocol.PresenceUpdate update = new TrackerProtocol.PresenceUpdate(
                gameId, identity, "leave", System.currentTimeMillis());
        return client.presence(update);
    }
}