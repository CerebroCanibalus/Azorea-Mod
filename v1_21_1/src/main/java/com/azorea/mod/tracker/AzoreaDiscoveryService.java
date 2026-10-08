// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.tracker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Servicio de descubrimiento Azorea (ver AGENTS.md § DA-5 + DA-6).
 *
 * Delega al {@link AzoreaTrackerClient} y mapea la respuesta a tipos de UI.
 */
public final class AzoreaDiscoveryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzoreaDiscoveryService.class);

    private final AzoreaTrackerClient client;

    public AzoreaDiscoveryService(final AzoreaTrackerClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * Lista juegos activos aplicando los filtros dados.
     * Devuelve lista vacía si los trackers fallan (no error).
     */
    public List<TrackerProtocol.GameListing> listGames(final TrackerProtocol.Filters filters) {
        Objects.requireNonNull(filters, "filters");
        final Optional<TrackerProtocol.ListResponse> response = client.listGames(filters);
        if (response.isEmpty()) {
            LOGGER.debug("List Games devolvió vacío (todos los trackers fallaron)");
            return List.of();
        }
        final List<TrackerProtocol.GameListing> games = response.get().games();
        return games != null ? games : List.of();
    }

    /**
     * Atajo: lista todos los juegos activos c/ el mod Azorea.
     */
    public List<TrackerProtocol.GameListing> listAzoreaGames(final int maxResults) {
        return listGames(new TrackerProtocol.Filters(
                null,  // sin filtro de versión MC
                "azorea",  // requiere mod azorea
                maxResults));
    }
}
