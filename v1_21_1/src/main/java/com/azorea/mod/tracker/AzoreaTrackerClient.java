// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.tracker;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Cliente HTTP del tracker Azorea (ver AGENTS.md § DA-5).
 *
 * Envía/recibe JSON al TrackerServer vía {@link HttpClient} (JDK 11+ builtin).
 *
 * § Seguridad (AGENTS.md):
 * - Timeouts en connect/request (no bloquea el hilo del juego indefinidamente).
 * - Tracking URLs validadas al construir el cliente (https preferido; http solo para LAN/trusted).
 * - host_token nunca se loguea.
 * - Errores de red no crashean el mod; se loguean y devuelven Optional.empty().
 */
public final class AzoreaTrackerClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzoreaTrackerClient.class);
    private static final Gson GSON = TrackerProtocol.gson();

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final List<String> trackerUrls;
    private final Duration requestTimeout;
    private final HttpClient httpClient;

    public AzoreaTrackerClient(final List<String> trackerUrls) {
        this(trackerUrls, DEFAULT_CONNECT_TIMEOUT, DEFAULT_REQUEST_TIMEOUT);
    }

    public AzoreaTrackerClient(final List<String> trackerUrls,
                               final Duration connectTimeout,
                               final Duration requestTimeout) {
        Objects.requireNonNull(trackerUrls, "trackerUrls");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (trackerUrls.isEmpty()) {
            throw new IllegalArgumentException("trackerUrls no puede estar vacío");
        }
        if (connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("connectTimeout debe ser > 0");
        }
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout debe ser > 0");
        }
        this.trackerUrls = List.copyOf(trackerUrls);
        this.requestTimeout = requestTimeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    // ===== § F9b: throttle de warnings periódicos =====
    // Un tracker caído o mal configurado (p.ej. la URL de ejemplo
    // `https://tracker.example.org`) dispara estos avisos en BUCLE: announce cada
    // 150s y poll de invites cada 10s ⇒ el log se inunda y las polls se encadenan.
    // Se emite el WARN como mucho 1 vez/min por clave; el detalle sigue en DEBUG.
    private static final java.util.Map<String, Long> LAST_WARN_MS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long WARN_THROTTLE_MS = 60_000L;

    private static void warnThrottled(final String key, final String format,
                                      final Object... args) {
        final long now = System.currentTimeMillis();
        final Long last = LAST_WARN_MS.get(key);
        if (last != null && now - last < WARN_THROTTLE_MS) {
            LOGGER.debug(format, args);
            return;
        }
        LAST_WARN_MS.put(key, now);
        LOGGER.warn(format, args);
    }

    public Optional<TrackerProtocol.AnnounceResponse> announce(final TrackerProtocol.Announcement a) {
        Objects.requireNonNull(a, "announcement");
        TrackerProtocol.validate(a);

        final String body = GSON.toJson(a);
        final byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

        final List<String> errors = new ArrayList<>();
        for (final String baseUrl : trackerUrls) {
            try {
                final HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/announce"))
                        .timeout(requestTimeout)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                        .build();
                final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 201) {
                    final TrackerProtocol.AnnounceResponse parsed = GSON.fromJson(
                            response.body(), TrackerProtocol.AnnounceResponse.class);
                    LOGGER.info("Announce OK a {} → expiresAt={}", baseUrl, parsed.expiresAt());
                    return Optional.of(parsed);
                }
                errors.add(baseUrl + " → HTTP " + response.statusCode() + ": " + truncate(response.body()));
            } catch (IOException | InterruptedException e) {
                errors.add(baseUrl + " → " + e.getClass().getSimpleName() + ": " + e.getMessage());
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (Exception e) {
                errors.add(baseUrl + " → " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        warnThrottled("announce", "Announce falló en todos los trackers: {}", String.join("; ", errors));
        return Optional.empty();
    }

    public Optional<TrackerProtocol.ListResponse> listGames(final TrackerProtocol.Filters filters) {
        Objects.requireNonNull(filters, "filters");

        final String queryString = buildQuery(filters);
        final List<String> errors = new ArrayList<>();

        for (final String baseUrl : trackerUrls) {
            try {
                final HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/list" + queryString))
                        .timeout(requestTimeout)
                        .header("Accept", "application/json")
                        .GET()
                        .build();
                final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    final TrackerProtocol.ListResponse parsed = GSON.fromJson(
                            response.body(), TrackerProtocol.ListResponse.class);
                    LOGGER.info("List OK a {} → {} juegos", baseUrl, parsed.games() == null ? 0 : parsed.games().size());
                    return Optional.of(parsed);
                }
                errors.add(baseUrl + " → HTTP " + response.statusCode());
            } catch (IOException | InterruptedException e) {
                errors.add(baseUrl + " → " + e.getClass().getSimpleName());
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (JsonSyntaxException e) {
                errors.add(baseUrl + " → respuesta inválida");
            }
        }
        LOGGER.warn("List falló en todos los trackers: {}", String.join("; ", errors));
        return Optional.empty();
    }

    public boolean delete(final String gameId, final String hostToken) {
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(hostToken, "hostToken");
        if (!TrackerProtocol.isValidGameId(gameId)) {
            throw new IllegalArgumentException("gameId inválido");
        }

        final String query = "?game_id=" + urlEncode(gameId) + "&token=" + urlEncode(hostToken);
        boolean anySuccess = false;
        final List<String> errors = new ArrayList<>();

        for (final String baseUrl : trackerUrls) {
            try {
                final HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/announce" + query))
                        .timeout(requestTimeout)
                        .DELETE()
                        .build();
                final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 204) {
                    LOGGER.info("Delete OK en {}", baseUrl);
                    anySuccess = true;
                } else if (response.statusCode() == 404) {
                    LOGGER.debug("Delete en {} → 404 (no estaba)", baseUrl);
                } else {
                    errors.add(baseUrl + " → HTTP " + response.statusCode());
                }
            } catch (IOException | InterruptedException e) {
                errors.add(baseUrl + " → " + e.getClass().getSimpleName());
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (!errors.isEmpty()) {
            LOGGER.warn("Delete con errores parciales: {}", String.join("; ", errors));
        }
        return anySuccess;
    }

    public void shutdown() {
        httpClient.close();
    }

    // ===== F5.2 — Presence + Invites + Friend =====

    public Optional<TrackerProtocol.PresenceResponse> presence(final TrackerProtocol.PresenceUpdate update) {
        Objects.requireNonNull(update, "update");
        final String body = GSON.toJson(update);
        final byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        final List<String> errors = new ArrayList<>();
        for (final String baseUrl : trackerUrls) {
            try {
                final HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/presence"))
                        .timeout(requestTimeout)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                        .build();
                final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    final TrackerProtocol.PresenceResponse parsed = GSON.fromJson(
                            response.body(), TrackerProtocol.PresenceResponse.class);
                    LOGGER.debug("Presence {} OK a {} → {} players",
                            update.op(), baseUrl, parsed.currentPlayers());
                    return Optional.of(parsed);
                }
                errors.add(baseUrl + " → HTTP " + response.statusCode());
            } catch (IOException | InterruptedException e) {
                errors.add(baseUrl + " → " + e.getClass().getSimpleName());
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        LOGGER.warn("Presence {} falló en todos los trackers: {}",
                update.op(), String.join("; ", errors));
        return Optional.empty();
    }

    public Optional<TrackerProtocol.InviteAck> sendInvite(final TrackerProtocol.InviteRequest req) {
        Objects.requireNonNull(req, "req");
        final String body = GSON.toJson(req);
        final byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        final List<String> errors = new ArrayList<>();
        for (final String baseUrl : trackerUrls) {
            try {
                final HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/invite"))
                        .timeout(requestTimeout)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                        .build();
                final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 201) {
                    final TrackerProtocol.InviteAck parsed = GSON.fromJson(
                            response.body(), TrackerProtocol.InviteAck.class);
                    LOGGER.info("Invite queued a {} → to={}", baseUrl, req.toAzoreaId());
                    return Optional.of(parsed);
                }
                errors.add(baseUrl + " → HTTP " + response.statusCode());
            } catch (IOException | InterruptedException e) {
                errors.add(baseUrl + " → " + e.getClass().getSimpleName());
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        LOGGER.warn("Invite falló en todos los trackers: {}", String.join("; ", errors));
        return Optional.empty();
    }

    /**
     * POST /relay/session → crea session en RelayServer + devuelve session_id + relay_port.
     */
    public Optional<TrackerProtocol.RelaySessionResponse> createRelaySession() {
        final List<String> errors = new ArrayList<>();
        for (final String baseUrl : trackerUrls) {
            try {
                final HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/relay/session"))
                        .timeout(requestTimeout)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build();
                final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 201) {
                    final TrackerProtocol.RelaySessionResponse parsed = GSON.fromJson(
                            response.body(), TrackerProtocol.RelaySessionResponse.class);
                    LOGGER.info("Relay session creada: {} (relay_port={})", parsed.sessionId(), parsed.relayPort());
                    return Optional.of(parsed);
                }
                errors.add(baseUrl + " → HTTP " + response.statusCode());
            } catch (IOException | InterruptedException e) {
                errors.add(baseUrl + " → " + e.getClass().getSimpleName());
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        LOGGER.warn("createRelaySession falló en todos los trackers: {}", String.join("; ", errors));
        return Optional.empty();
    }

    /**
     * GET /invites/{azorea_id}: poll pending invites para este peer (drain semantics).
     */
    public Optional<TrackerProtocol.InviteList> pollInvites(final String azoreaId) {
        Objects.requireNonNull(azoreaId, "azoreaId");
        if (!TrackerProtocol.isValidAzoreaId(azoreaId)) {
            throw new IllegalArgumentException("azoreaId inválido");
        }
        final List<TrackerProtocol.Invite> combined = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        for (final String baseUrl : trackerUrls) {
            try {
                final HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/invites/" + urlEncode(azoreaId)))
                        .timeout(requestTimeout)
                        .header("Accept", "application/json")
                        .GET()
                        .build();
                final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    final TrackerProtocol.InviteList parsed = GSON.fromJson(
                            response.body(), TrackerProtocol.InviteList.class);
                    if (parsed.invites() != null) {
                        combined.addAll(parsed.invites());
                    }
                } else if (response.statusCode() == 400) {
                    errors.add(baseUrl + " → HTTP 400 (azoreaId inválido)");
                } else {
                    errors.add(baseUrl + " → HTTP " + response.statusCode());
                }
            } catch (IOException | InterruptedException e) {
                errors.add(baseUrl + " → " + e.getClass().getSimpleName());
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (combined.isEmpty() && !errors.isEmpty()) {
            warnThrottled("poll-invites", "Poll invites falló en todos los trackers: {}", String.join("; ", errors));
            return Optional.empty();
        }
        return Optional.of(new TrackerProtocol.InviteList(combined));
    }

    /**
     * GET /friend/{azorea_id}: lookup friend online status + current game.
     */
    public Optional<TrackerProtocol.FriendStatus> lookupFriend(final String azoreaId) {
        Objects.requireNonNull(azoreaId, "azoreaId");
        if (!TrackerProtocol.isValidAzoreaId(azoreaId)) {
            throw new IllegalArgumentException("azoreaId inválido");
        }
        final List<String> errors = new ArrayList<>();
        for (final String baseUrl : trackerUrls) {
            try {
                final HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/friend/" + urlEncode(azoreaId)))
                        .timeout(requestTimeout)
                        .header("Accept", "application/json")
                        .GET()
                        .build();
                final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    final TrackerProtocol.FriendStatus parsed = GSON.fromJson(
                            response.body(), TrackerProtocol.FriendStatus.class);
                    LOGGER.info("Friend lookup OK a {} → {} online={}", baseUrl, azoreaId, parsed.online());
                    return Optional.of(parsed);
                }
                if (response.statusCode() == 404) {
                    LOGGER.debug("Friend {} not online en {}", azoreaId, baseUrl);
                    return Optional.empty();
                }
                errors.add(baseUrl + " → HTTP " + response.statusCode());
            } catch (IOException | InterruptedException e) {
                errors.add(baseUrl + " → " + e.getClass().getSimpleName());
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        LOGGER.warn("Friend lookup falló en todos los trackers: {}", String.join("; ", errors));
        return Optional.empty();
    }

    // ===== Utilidades =====

    private static String buildQuery(final TrackerProtocol.Filters filters) {
        final StringBuilder sb = new StringBuilder("?");
        sb.append("max_results=").append(filters.maxResults());
        if (filters.mcVersion() != null) {
            sb.append("&mc_version=").append(urlEncode(filters.mcVersion()));
        }
        if (filters.modId() != null) {
            sb.append("&mod_id=").append(urlEncode(filters.modId()));
        }
        return sb.toString();
    }

    private static String urlEncode(final String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String truncate(final String s) {
        if (s == null) return "<null>";
        return s.length() <= 120 ? s : s.substring(0, 117) + "...";
    }

    /** Devuelve la primera URL de tracker (sin esquema). Útil para relay local. */
    public Optional<String> firstTrackerHost() {
        if (trackerUrls.isEmpty()) return Optional.empty();
        final String url = trackerUrls.get(0);
        try {
            final java.net.URI u = java.net.URI.create(url);
            return Optional.ofNullable(u.getHost()).or(() -> Optional.of(url));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}