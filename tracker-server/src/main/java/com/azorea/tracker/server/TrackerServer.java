// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.tracker.server;

// § DA-13: del srcDir compartido — p/ verificar la firma d/ los announces d/ punch.
import com.azorea.mod.v1211.tracker.AzoreaPunchExchange;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.util.logging.Level;
import java.util.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracker HTTP server v2 (ver AGENTS.md § DA-5 + F5.2).
 *
 * <p>Endpoints v1 (F2.x):
 * <ul>
 *   <li>{@code POST /announce} → registra / refresca un anuncio (sin IP)</li>
 *   <li>{@code GET /list} → devuelve anuncios activos (sin IP)</li>
 *   <li>{@code DELETE /announce?game_id=X&token=Y} → elimina anuncio</li>
 *   <li>{@code GET /health} → estado del servidor</li>
 * </ul>
 *
 * <p>Endpoints v2 (F5.2):
 * <ul>
 *   <li>{@code POST /presence} → jugador se une/sale de un game</li>
 *   <li>{@code POST /invite} → host envía invite (encrypted blob) a un amigo</li>
 *   <li>{@code GET /invites/{azorea_id}} → poll pending invites</li>
 *   <li>{@code GET /friend/{azorea_id}} → lookup online status + current game</li>
 * </ul>
 *
 * <p>Persistencia: archivo JSON plano. ConcurrentHashMap en memoria.
 * Background: sweeper cada 30s purga juegos expirados + invites &gt;1h.
 *
 * <p><b>§ Seguridad (F5.2 — IP nunca expuesta):</b>
 * <ul>
 *   <li>Tracker NO almacena IP/port del host. {@code Announcement.hostIdentity}
 *       contiene azorea_id, display_name y public_key — nunca la connection info.</li>
 *   <li>{@code GameListing} devuelto en {@code GET /list} NO contiene IP/port.</li>
 *   <li>Connection info solo fluye como encrypted blob en POST /invite. El tracker
 *       no puede descifrarlo (X25519+ChaCha20 cifrado con public_key del recipient).</li>
 * </ul>
 */
public final class TrackerServer {

    private static final Logger LOGGER = Logger.getLogger(TrackerServer.class.getName());

    private static final int DEFAULT_PORT = 9090;
    private static final int DEFAULT_RELAY_PORT = 9999;
    private static final int DEFAULT_BACKLOG = 0;
    private static final int SWEEPER_PERIOD_SECONDS = 30;
    private static final int RATE_LIMIT_PER_HOUR = 60;

    // § Audit 2026-10-07: tope de body por handler. Lo q/ NO cabe cabe ⇒ 400, no
    //   intentar parsear basura y reventar memoria.
    private static final int MAX_BODY_SMALL = 8 * 1024;        // /announce, /presence, /invite, /punch/clear
    private static final int MAX_BODY_PUNCH = 64 * 1024;      // /punch/announce (payload firmado Ed25519)

    private final int port;
    private final int relayPort;
    private final Path dataFile;
    /**
     * § Audit 2026-10-07: si true, se honra {@code X-Forwarded-For} para clientIp().
     *   Peligro: si el tracker está expuesto SIN un reverse proxy de confianza, un
     *   atacante puede falsificar la IP y bypasear el rate limit per-IP.
     *   Por default false (sólo se usa la dirección del socket TCP real).
     */
    private final boolean trustForwardedFor;
    private final Map<String, Entry> games = new ConcurrentHashMap<>();
    /** gameId → (azoreaId → Identity) para players actualmente en el game. */
    private final Map<String, Map<String, TrackerProtocol.Identity>> presences = new ConcurrentHashMap<>();
    /** toAzoreaId → cola de invites pendientes. */
    private final Map<String, List<TrackerProtocol.Invite>> pendingInvites = new ConcurrentHashMap<>();
    /** Rate limit por IP. */
    private final Map<String, RateState> rateLimitCounters = new ConcurrentHashMap<>();
    private final Gson gson = TrackerProtocol.gson();

    private HttpServer httpServer;
    private ScheduledExecutorService sweeper;
    private RelayServer relayServer;

    public TrackerServer(final int port, final Path dataFile) {
        this(port, dataFile, 0, false); // sin relay, sin trust_forwarded_for
    }

    public TrackerServer(final int port, final Path dataFile, final int relayPort) {
        this(port, dataFile, relayPort, false);
    }

    public TrackerServer(final int port, final Path dataFile, final int relayPort,
                         final boolean trustForwardedFor) {
        this.port = port;
        this.dataFile = dataFile;
        this.relayPort = relayPort;
        this.trustForwardedFor = trustForwardedFor;
    }

    public void start() throws IOException {
        loadFromDisk();

        httpServer = HttpServer.create(new InetSocketAddress(port), DEFAULT_BACKLOG);
        // v1 endpoints.
        httpServer.createContext("/announce", new AnnounceHandler());
        httpServer.createContext("/list", new ListHandler());
        httpServer.createContext("/health", new HealthHandler());
        // v2 endpoints (F5.2).
        httpServer.createContext("/presence", new PresenceHandler());
        httpServer.createContext("/invite", new SendInviteHandler());
        httpServer.createContext("/invites/", new PollInvitesHandler());
        httpServer.createContext("/friend/", new FriendLookupHandler());
        // v2.2 endpoints (F6.2): TCP relay NAT-traversal fallback.
        httpServer.createContext("/relay/session", new RelaySessionHandler());
        // v2.3 endpoints (DA-12): hole-punch rendezvous — el q/ falta p/ WAN.
        httpServer.createContext("/punch/observe", new PunchObserveHandler());
        httpServer.createContext("/punch/announce", new PunchAnnounceHandler());
        httpServer.createContext("/punch/poll", new PunchPollHandler());
        httpServer.createContext("/punch/clear", new PunchClearHandler());
        httpServer.setExecutor(Executors.newFixedThreadPool(8));
        httpServer.start();
        LOGGER.info("Tracker HTTP server v2 listening on port " + port
                + " (endpoints: /announce /list /health /presence /invite /invites/ /friend/"
                + " /relay/session /punch/observe /punch/announce /punch/poll /punch/clear)");

        sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "azorea-tracker-sweeper");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleAtFixedRate(this::sweepExpired, SWEEPER_PERIOD_SECONDS,
                SWEEPER_PERIOD_SECONDS, TimeUnit.SECONDS);

        // § F6.2: arranca TCP relay server si relayPort > 0.
        if (relayPort > 0) {
            try {
                relayServer = new RelayServer(relayPort);
                relayServer.start();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "RelayServer no pudo arrancar en puerto " + relayPort, e);
            }
        }
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(1);
        }
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
        if (relayServer != null) {
            relayServer.stop();
        }
        try {
            saveToDisk();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error guardando datos al detener: " + e.getMessage(), e);
        }
        LOGGER.info("Tracker detenido. " + games.size() + " juegos, "
                + presences.size() + " games con presencia, "
                + pendingInvites.size() + " destinatarios con invites pendientes, "
                + (relayServer != null ? relayServer.activeSessions() + " relay sessions" : "relay off") + ".");
    }

    public int port() {
        return port;
    }

    public int activeGames() {
        return games.size();
    }

    public int totalPlayersOnline() {
        return presences.values().stream().mapToInt(Map::size).sum();
    }

    public int pendingInviteRecipients() {
        return pendingInvites.size();
    }

    public int relayPort() {
        return relayServer != null ? relayServer.port() : 0;
    }

    // ===== Sweeper =====

    private void sweepExpired() {
        sweepPunch();   // § DA-12: announces d/ punch caducan a 30 s.
        final long nowSeconds = System.currentTimeMillis() / 1000L;
        // Purga juegos expirados (sus presencias también).
        int beforeGames = games.size();
        final List<String> expired = new ArrayList<>();
        for (Map.Entry<String, Entry> e : games.entrySet()) {
            if (e.getValue().expiresAt() < nowSeconds) {
                expired.add(e.getKey());
            }
        }
        for (String gameId : expired) {
            games.remove(gameId);
            presences.remove(gameId);
        }
        // Purga invites &gt; TTL.
        long inviteCutoff = nowSeconds - TrackerProtocol.INVITE_TTL_SECONDS;
        int totalInvites = 0;
        for (Map.Entry<String, List<TrackerProtocol.Invite>> e : pendingInvites.entrySet()) {
            int sizeBefore = e.getValue().size();
            e.getValue().removeIf(inv -> inv.sentAt() / 1000L < inviteCutoff);
            int sizeAfter = e.getValue().size();
            totalInvites += sizeAfter;
            if (sizeAfter == 0) {
                pendingInvites.remove(e.getKey());
            }
            if (sizeBefore != sizeAfter) {
                LOGGER.fine("Sweep invites: " + e.getKey() + " " + (sizeBefore - sizeAfter) + " purgados");
            }
        }
        if (!expired.isEmpty()) {
            LOGGER.info("Sweep: " + expired.size() + " juegos expirados purgados ("
                    + beforeGames + " → " + games.size() + ").");
        }
    }

    // ===== Persistencia =====

    /** Entry para juegos (v1 + v2 metadata). */
    private record Entry(TrackerProtocol.Announcement announcement,
                        long firstSeen, long lastSeen, long expiresAt) {
    }

    private record PersistedState(List<Entry> entries,
                                  List<PersistedInvite> invites) {
    }

    private record PersistedInvite(String toAzoreaId, TrackerProtocol.Invite invite) {
    }

    private void loadFromDisk() {
        if (!Files.exists(dataFile)) {
            LOGGER.info("Sin archivo de datos previo (" + dataFile + "). Iniciando vacío.");
            return;
        }
        try (InputStream in = Files.newInputStream(dataFile)) {
            final String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            final PersistedState state = gson.fromJson(json, PersistedState.class);
            if (state == null) return;
            final long nowSeconds = System.currentTimeMillis() / 1000L;
            if (state.entries() != null) {
                for (Entry e : state.entries()) {
                    if (e.expiresAt() >= nowSeconds) {
                        games.put(e.announcement().gameId(), e);
                    }
                }
            }
            if (state.invites() != null) {
                for (PersistedInvite pi : state.invites()) {
                    pendingInvites
                            .computeIfAbsent(pi.toAzoreaId(), k -> Collections.synchronizedList(new ArrayList<>()))
                            .add(pi.invite());
                }
            }
            LOGGER.info("Cargados " + games.size() + " juegos y "
                    + pendingInvites.size() + " destinatarios con invites desde " + dataFile);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error cargando datos (" + dataFile + "): " + e.getMessage(), e);
        }
    }

    private void saveToDisk() throws IOException {
        final List<PersistedInvite> invites = new ArrayList<>();
        for (Map.Entry<String, List<TrackerProtocol.Invite>> e : pendingInvites.entrySet()) {
            for (TrackerProtocol.Invite inv : e.getValue()) {
                invites.add(new PersistedInvite(e.getKey(), inv));
            }
        }
        final PersistedState state = new PersistedState(new ArrayList<>(games.values()), invites);
        final byte[] json = gson.toJson(state).getBytes(StandardCharsets.UTF_8);
        Files.write(dataFile, json);
        LOGGER.fine("Datos guardados en " + dataFile);
    }

    // ===== Handlers =====

    private final class AnnounceHandler implements HttpHandler {
        @Override
        public void handle(final HttpExchange exchange) throws IOException {
            try (exchange) {
                final String method = exchange.getRequestMethod();
                if ("POST".equalsIgnoreCase(method)) {
                    handlePost(exchange);
                } else if ("DELETE".equalsIgnoreCase(method)) {
                    handleDelete(exchange);
                } else {
                    respondJson(exchange, 405,
                            new TrackerProtocol.ErrorResponse("method_not_allowed", method));
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error en AnnounceHandler", e);
                respondJson(exchange, 500,
                        new TrackerProtocol.ErrorResponse("internal_error", e.getMessage()));
            }
        }

        private void handlePost(final HttpExchange exchange) throws IOException {
            final String ip = clientIp(exchange);
            if (!checkRateLimit(ip)) {
                respondJson(exchange, 429,
                        new TrackerProtocol.ErrorResponse("rate_limit", "máx " + RATE_LIMIT_PER_HOUR + " POST/h"));
                return;
            }

            final TrackerProtocol.Announcement announcement;
            try {
                final String body = readBody(exchange);
                announcement = gson.fromJson(body, TrackerProtocol.Announcement.class);
                TrackerProtocol.validate(announcement);
            } catch (Exception e) {
                respondJson(exchange, 400,
                        new TrackerProtocol.ErrorResponse("invalid_announcement", e.getMessage()));
                return;
            }

            final long nowSeconds = System.currentTimeMillis() / 1000L;
            final long expiresAt = nowSeconds + announcement.ttlSeconds();
            final Entry existing = games.get(announcement.gameId());
            final long firstSeen = existing != null ? existing.firstSeen() : nowSeconds;

            // Verificar host_token si ya existe (prevenir takeover).
            if (existing != null && !existing.announcement().hostToken().equals(announcement.hostToken())) {
                respondJson(exchange, 403,
                        new TrackerProtocol.ErrorResponse("forbidden", "host_token inválido"));
                return;
            }

            games.put(announcement.gameId(), new Entry(announcement, firstSeen, nowSeconds, expiresAt));
            LOGGER.info("Announce OK: game_id=" + announcement.gameId()
                    + " mc=" + announcement.mcVersion()
                    + " players=" + announcement.currentPlayers() + "/" + announcement.maxPlayers()
                    + " ttl=" + announcement.ttlSeconds() + "s"
                    + " host=" + announcement.hostIdentity().azoreaId());

            respondJson(exchange, 201, new AnnounceResponsePayload(expiresAt));
        }

        private void handleDelete(final HttpExchange exchange) throws IOException {
            final Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            final String gameId = params.get("game_id");
            final String token = params.get("token");

            if (gameId == null || token == null) {
                respondJson(exchange, 400,
                        new TrackerProtocol.ErrorResponse("missing_params", "game_id y token requeridos"));
                return;
            }

            final Entry existing = games.get(gameId);
            if (existing == null) {
                respondJson(exchange, 404,
                        new TrackerProtocol.ErrorResponse("not_found", "game_id desconocido"));
                return;
            }
            if (!existing.announcement().hostToken().equals(token)) {
                respondJson(exchange, 403,
                        new TrackerProtocol.ErrorResponse("forbidden", "token inválido"));
                return;
            }

            games.remove(gameId);
            presences.remove(gameId); // Limpia presencias asociadas.
            LOGGER.info("Delete OK: game_id=" + gameId);
            exchange.sendResponseHeaders(204, -1);
        }
    }

    private final class ListHandler implements HttpHandler {
        @Override
        public void handle(final HttpExchange exchange) throws IOException {
            try (exchange) {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    respondJson(exchange, 405, new TrackerProtocol.ErrorResponse("method_not_allowed",
                            exchange.getRequestMethod()));
                    return;
                }

                final Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
                final String mcFilter = params.get("mc_version");
                final String modFilter = params.get("mod_id");
                int maxResults;
                try {
                    maxResults = Math.min(TrackerProtocol.MAX_LIST_RESULTS,
                            Integer.parseInt(params.getOrDefault("max_results", "20")));
                } catch (NumberFormatException e) {
                    maxResults = 20;
                }

                final long nowSeconds = System.currentTimeMillis() / 1000L;
                final List<TrackerProtocol.GameListing> results = new ArrayList<>();
                for (Entry e : games.values()) {
                    if (e.expiresAt() < nowSeconds) continue;
                    final TrackerProtocol.Announcement a = e.announcement();
                    if (mcFilter != null && !mcFilter.equals(a.mcVersion())) continue;
                    if (modFilter != null
                            && a.mods().stream().noneMatch(m -> modFilter.equals(m.modid()))) continue;

                    // Players en este game (de presences). Puede ser vacío si nadie ha hecho /presence.
                    final Map<String, TrackerProtocol.Identity> playersMap = presences.get(a.gameId());
                    final List<TrackerProtocol.Identity> players = playersMap == null
                            ? List.of() : new ArrayList<>(playersMap.values());

                    // IMPORTANTE: GameListing v2 NO incluye connection field (IP/port).
                    results.add(new TrackerProtocol.GameListing(
                            a.gameId(),
                            a.hostIdentity(),
                            a.mcVersion(),
                            a.neoForgeVersion(),
                            a.mods(),
                            a.maxPlayers(),
                            players.size(),
                            players,
                            a.worldName(),
                            a.inviteCode(),
                            e.firstSeen(),
                            e.lastSeen(),
                            e.expiresAt()));
                    if (results.size() >= maxResults) break;
                }

                respondJson(exchange, 200, new TrackerProtocol.ListResponse(results));
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error en ListHandler", e);
                respondJson(exchange, 500,
                        new TrackerProtocol.ErrorResponse("internal_error", e.getMessage()));
            }
        }
    }

    private final class HealthHandler implements HttpHandler {
        @Override
        public void handle(final HttpExchange exchange) throws IOException {
            try (exchange) {
                final String body = gson.toJson(Map.of(
                        "status", "ok",
                        "version", 2,
                        "active_games", games.size(),
                        "players_online", totalPlayersOnline(),
                        "pending_invite_recipients", pendingInviteRecipients(),
                        "relay_port", relayPort(),
                        "relay_sessions", relayServer != null ? relayServer.activeSessions() : 0,
                        "port", port));
                respondJson(exchange, 200, body);
            }
        }
    }

    // ===== F5.2 — Presence handler =====

    private final class PresenceHandler implements HttpHandler {
        @Override
        public void handle(final HttpExchange exchange) throws IOException {
            try (exchange) {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    respondJson(exchange, 405, new TrackerProtocol.ErrorResponse("method_not_allowed",
                            exchange.getRequestMethod()));
                    return;
                }
                if (!checkRateLimit(clientIp(exchange))) {
                    respondJson(exchange, 429, new TrackerProtocol.ErrorResponse("rate_limit",
                            "máx " + RATE_LIMIT_PER_HOUR + " POST/h"));
                    return;
                }

                final TrackerProtocol.PresenceUpdate update;
                try {
                    update = gson.fromJson(readBody(exchange), TrackerProtocol.PresenceUpdate.class);
                } catch (Exception e) {
                    respondJson(exchange, 400, new TrackerProtocol.ErrorResponse("invalid_presence",
                            e.getMessage()));
                    return;
                }

                // El game debe existir en announcements.
                if (!games.containsKey(update.gameId())) {
                    respondJson(exchange, 404, new TrackerProtocol.ErrorResponse("game_not_found",
                            "anuncia primero con POST /announce"));
                    return;
                }

                final Map<String, TrackerProtocol.Identity> players = presences.computeIfAbsent(
                        update.gameId(), k -> new ConcurrentHashMap<>());

                if ("join".equals(update.op())) {
                    players.put(update.identity().azoreaId(), update.identity());
                    LOGGER.info("Presence join: game_id=" + update.gameId()
                            + " player=" + update.identity().azoreaId());
                } else {
                    players.remove(update.identity().azoreaId());
                    LOGGER.info("Presence leave: game_id=" + update.gameId()
                            + " player=" + update.identity().azoreaId());
                }
                if (players.isEmpty()) {
                    presences.remove(update.gameId());
                }
                respondJson(exchange, 200,
                        new TrackerProtocol.PresenceResponse(
                                presences.getOrDefault(update.gameId(), Map.of()).size()));
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error en PresenceHandler", e);
                respondJson(exchange, 500,
                        new TrackerProtocol.ErrorResponse("internal_error", e.getMessage()));
            }
        }
    }

    // ===== F5.2 — Invite handlers =====

    private final class SendInviteHandler implements HttpHandler {
        @Override
        public void handle(final HttpExchange exchange) throws IOException {
            try (exchange) {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    respondJson(exchange, 405, new TrackerProtocol.ErrorResponse("method_not_allowed",
                            exchange.getRequestMethod()));
                    return;
                }
                if (!checkRateLimit(clientIp(exchange))) {
                    respondJson(exchange, 429, new TrackerProtocol.ErrorResponse("rate_limit",
                            "máx " + RATE_LIMIT_PER_HOUR + " POST/h"));
                    return;
                }

                final TrackerProtocol.InviteRequest req;
                try {
                    req = gson.fromJson(readBody(exchange), TrackerProtocol.InviteRequest.class);
                } catch (Exception e) {
                    respondJson(exchange, 400, new TrackerProtocol.ErrorResponse("invalid_invite",
                            e.getMessage()));
                    return;
                }

                // Validar que el fromIdentity es realmente el host del game.
                final Entry game = games.get(req.gameId());
                if (game == null) {
                    respondJson(exchange, 404, new TrackerProtocol.ErrorResponse("game_not_found",
                            "anuncia primero con POST /announce"));
                    return;
                }
                if (!game.announcement().hostIdentity().azoreaId()
                        .equals(req.fromIdentity().azoreaId())) {
                    respondJson(exchange, 403, new TrackerProtocol.ErrorResponse("not_host",
                            "fromIdentity no coincide con el host del game"));
                    return;
                }
                // Validar que el blob es base64 válido.
                try {
                    Base64.getDecoder().decode(req.encryptedBlobBase64());
                } catch (IllegalArgumentException e) {
                    respondJson(exchange, 400, new TrackerProtocol.ErrorResponse("invalid_blob",
                            "encryptedBlobBase64 no es base64 válido"));
                    return;
                }

                final TrackerProtocol.Invite invite = new TrackerProtocol.Invite(
                        req.fromIdentity(),
                        req.gameId(),
                        req.encryptedBlobBase64(),
                        req.sentAt());
                pendingInvites
                        .computeIfAbsent(req.toAzoreaId(), k -> Collections.synchronizedList(new ArrayList<>()))
                        .add(invite);

                LOGGER.info("Invite queued: from=" + req.fromIdentity().azoreaId()
                        + " to=" + req.toAzoreaId()
                        + " game=" + req.gameId()
                        + " blob_bytes=" + req.encryptedBlobBase64().length());
                respondJson(exchange, 201, new TrackerProtocol.InviteAck(true, req.sentAt()));
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error en SendInviteHandler", e);
                respondJson(exchange, 500,
                        new TrackerProtocol.ErrorResponse("internal_error", e.getMessage()));
            }
        }
    }

    private final class PollInvitesHandler implements HttpHandler {
        @Override
        public void handle(final HttpExchange exchange) throws IOException {
            try (exchange) {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    respondJson(exchange, 405, new TrackerProtocol.ErrorResponse("method_not_allowed",
                            exchange.getRequestMethod()));
                    return;
                }
                // Path: /invites/<azorea_id>
                final String path = exchange.getRequestURI().getPath();
                final String azoreaId = path.substring("/invites/".length());
                if (azoreaId.isEmpty() || !TrackerProtocol.isValidAzoreaId(azoreaId)) {
                    respondJson(exchange, 400, new TrackerProtocol.ErrorResponse("invalid_azorea_id",
                            "azorea_id inválido o ausente"));
                    return;
                }

                // Drain pending invites para este recipient (drain semantics — poll = consume).
                final List<TrackerProtocol.Invite> pending = pendingInvites.remove(azoreaId);
                final List<TrackerProtocol.Invite> result = pending == null
                        ? List.of() : new ArrayList<>(pending);

                respondJson(exchange, 200, new TrackerProtocol.InviteList(result));
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error en PollInvitesHandler", e);
                respondJson(exchange, 500,
                        new TrackerProtocol.ErrorResponse("internal_error", e.getMessage()));
            }
        }
    }

    // ===== F5.2 — Friend lookup handler =====

    private final class FriendLookupHandler implements HttpHandler {
        @Override
        public void handle(final HttpExchange exchange) throws IOException {
            try (exchange) {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    respondJson(exchange, 405, new TrackerProtocol.ErrorResponse("method_not_allowed",
                            exchange.getRequestMethod()));
                    return;
                }
                final String path = exchange.getRequestURI().getPath();
                final String azoreaId = path.substring("/friend/".length());
                if (azoreaId.isEmpty() || !TrackerProtocol.isValidAzoreaId(azoreaId)) {
                    respondJson(exchange, 400, new TrackerProtocol.ErrorResponse("invalid_azorea_id",
                            "azorea_id inválido o ausente"));
                    return;
                }

                // Buscar primero en presences (players en games).
                String currentGameId = null;
                long lastSeen = 0;
                TrackerProtocol.Identity identity = null;
                for (Map.Entry<String, Map<String, TrackerProtocol.Identity>> entry : presences.entrySet()) {
                    final TrackerProtocol.Identity id = entry.getValue().get(azoreaId);
                    if (id != null) {
                        currentGameId = entry.getKey();
                        identity = id;
                        lastSeen = System.currentTimeMillis() / 1000L;
                        break;
                    }
                }
                // Si no está en ningún game como player, buscar como host en announcements.
                if (identity == null) {
                    for (Entry game : games.values()) {
                        if (game.announcement().hostIdentity().azoreaId().equals(azoreaId)) {
                            identity = game.announcement().hostIdentity();
                            currentGameId = game.announcement().gameId();
                            lastSeen = game.lastSeen();
                            break;
                        }
                    }
                }

                if (identity == null) {
                    respondJson(exchange, 404, new TrackerProtocol.ErrorResponse("not_found",
                            "amigo no está online ni hosteando"));
                    return;
                }

                respondJson(exchange, 200, new TrackerProtocol.FriendStatus(
                        identity.azoreaId(),
                        identity.displayName(),
                        identity.publicKeyBase64(),
                        true,
                        currentGameId,
                        lastSeen));
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error en FriendLookupHandler", e);
                respondJson(exchange, 500,
                        new TrackerProtocol.ErrorResponse("internal_error", e.getMessage()));
            }
        }
    }

    // ===== F6.2 — Relay session handler (NAT-traversal fallback) =====

    private final class RelaySessionHandler implements HttpHandler {
        @Override
        public void handle(final HttpExchange exchange) throws IOException {
            try (exchange) {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    respondJson(exchange, 405, new TrackerProtocol.ErrorResponse("method_not_allowed",
                            exchange.getRequestMethod()));
                    return;
                }
                if (relayServer == null) {
                    respondJson(exchange, 503, new TrackerProtocol.ErrorResponse(
                            "relay_disabled", "tracker no tiene relay server"));
                    return;
                }
                // § Crear session; ambos peers reciben el mismo session_id y
                // hacen handshake TCP (envían session_id como primera línea).
                final String sessionId = relayServer.createSession();
                LOGGER.info("RelaySession creada via HTTP: " + sessionId);
                respondJson(exchange, 201,
                        new TrackerProtocol.RelaySessionResponse(sessionId, relayServer.port()));
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error en RelaySessionHandler", e);
                respondJson(exchange, 500,
                        new TrackerProtocol.ErrorResponse("internal_error", e.getMessage()));
            }
        }
    }

    // ===== DA-12 — Punch endpoints (rendezvous) =====

    /**
     * Buzón d/ punch — <b>MISMO JSON d/ alambre</b> q/ `AzoreaEmbeddedTracker`.
     *
     * <p>§ <b>Verifica de verdad</b> (audit F9: antes el standalone aceptaba cualquier
     * announce). La clave es `AzoreaPunchExchange.parseAndVerify`, q/ ahora llega v/
     * el srcDir compartido (DA-13) — sin éso habría q/ duplicar el canónico q/ se firma,
     * y una copia q/ se desincronice falla <b>en silencio</b> hasta q/ nadie conecta.
     *
     * <p>§ Qué NO puede hacer un rendezvous hostil: alterar la dirección (la firma cubre
     * ip+port) ni suplantar al emisor (el id se REderiva, DA-8). Solo puede no atenderte.
     */
    private final Map<String, PunchAnnounce> punchAnnounces = new ConcurrentHashMap<>();

    /** Cota d/ buzón — un tracker público tiene q/ acotar memoria s/ autenticación. */
    private static final int MAX_PUNCH_ENTRIES = 1000;
    /** Vida d/ un announce d/ punch (misma q/ en el embebido). */
    private static final long PUNCH_TTL_MS = 30_000L;

    public record PunchAnnounce(
            String azoreaId,
            String payload,
            long timestampMs,
            String target) {
        /** Compat: announce s/ destinatario (JSON d/ clientes antiguos). */
        public PunchAnnounce(final String azoreaId, final String payload, final long timestampMs) {
            this(azoreaId, payload, timestampMs, null);
        }
    }

    /** GET /punch/observe — tu endpoint público TCP, ya traducido por tu NAT. */
    public record Observed(String ip, int port) {
    }

    private static String normalizeIpv4(final String s) {
        if (s != null && s.startsWith("::ffff:") && s.indexOf('.') >= 0) {
            return s.substring(7);
        }
        return s;
    }

    private final class PunchObserveHandler implements HttpHandler {
        @Override
        public void handle(final HttpExchange exchange) throws IOException {
            try (exchange) {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    respondJson(exchange, 405,
                            new TrackerProtocol.ErrorResponse("method_not_allowed",
                                    exchange.getRequestMethod()));
                    return;
                }
                // § El cliente se liga al MISMO puerto del punch ⇒ lo q/ vemos aquí es
                //   lo q/ el peer ha de discar (y además crea el mapeo NAT).
                final InetSocketAddress remote = exchange.getRemoteAddress();
                respondJson(exchange, 200, new Observed(
                        normalizeIpv4(remote.getAddress().getHostAddress()), remote.getPort()));
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error en PunchObserveHandler", e);
                respondJson(exchange, 500,
                        new TrackerProtocol.ErrorResponse("internal_error", e.getMessage()));
            }
        }
    }

    private final class PunchAnnounceHandler implements HttpHandler {
        @Override
        public void handle(final HttpExchange exchange) throws IOException {
            try (exchange) {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    respondJson(exchange, 405,
                            new TrackerProtocol.ErrorResponse("method_not_allowed",
                                    exchange.getRequestMethod()));
                    return;
                }
                // § El único punto c/ coste d/ CPU (Ed25519) ⇒ acotar por IP.
                if (!checkRateLimit(clientIp(exchange))) {
                    respondJson(exchange, 429, new TrackerProtocol.ErrorResponse("rate_limit",
                            "máx " + RATE_LIMIT_PER_HOUR + " POST/h"));
                    return;
                }

                final PunchAnnounce in;
                try {
                    // § Audit 2026-10-07: cap explícito de 64 KB — el payload firmado
                    //   (signed JSON d/ AzoreaPunchExchange.Endpoint) cabe holgado.
                    in = gson.fromJson(readBody(exchange, MAX_BODY_PUNCH), PunchAnnounce.class);
                } catch (Exception e) {
                    respondJson(exchange, 400,
                            new TrackerProtocol.ErrorResponse("invalid", e.getMessage()));
                    return;
                }
                if (in == null || in.azoreaId() == null
                        || !TrackerProtocol.isValidAzoreaId(in.azoreaId())) {
                    respondJson(exchange, 400,
                            new TrackerProtocol.ErrorResponse("invalid", "azoreaId inválido"));
                    return;
                }
                if (in.payload() == null || in.payload().isBlank()) {
                    respondJson(exchange, 400, new TrackerProtocol.ErrorResponse("unsigned",
                            "announce sin firma — Azorea ≥ 2026-10-01 no acepta announce plano"));
                    return;
                }

                final AzoreaPunchExchange.Verified verified;
                try {
                    verified = AzoreaPunchExchange.parseAndVerify(
                            in.payload(), System.currentTimeMillis());
                } catch (IllegalArgumentException e) {
                    respondJson(exchange, 400,
                            new TrackerProtocol.ErrorResponse("invalid", e.getMessage()));
                    return;
                }
                if (!in.azoreaId().equals(verified.azoreaId())) {
                    respondJson(exchange, 400, new TrackerProtocol.ErrorResponse("id_mismatch",
                            "el id del payload (" + verified.azoreaId() + ") no es la clave ("
                                    + in.azoreaId() + ")"));
                    return;
                }

                final String target = in.target() == null || in.target().isBlank()
                        ? null : in.target().trim();
                if (target != null && !TrackerProtocol.isValidAzoreaId(target)) {
                    respondJson(exchange, 400,
                            new TrackerProtocol.ErrorResponse("invalid", "target inválido"));
                    return;
                }

                if (punchAnnounces.size() >= MAX_PUNCH_ENTRIES
                        && !punchAnnounces.containsKey(in.azoreaId())) {
                    respondJson(exchange, 429,
                            new TrackerProtocol.ErrorResponse("mailbox_full",
                                    "buzón d/ punch lleno (" + MAX_PUNCH_ENTRIES + ")"));
                    return;
                }

                final PunchAnnounce stored = new PunchAnnounce(
                        in.azoreaId(), in.payload(), System.currentTimeMillis(), target);
                punchAnnounces.put(stored.azoreaId(), stored);
                respondJson(exchange, 200, stored);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error en PunchAnnounceHandler", e);
                respondJson(exchange, 500,
                        new TrackerProtocol.ErrorResponse("internal_error", e.getMessage()));
            }
        }
    }

    /**
     * GET /punch/poll — dos modos:
     * <ul>
     *   <li>{@code ?azoreaId=X} → el announce <b>de X</b> (joiner → host).</li>
     *   <li>{@code ?target=X} → el primer announce <b>dirigido a X</b> y q/ no sea de X
     *       (host → joiner). Ése es el hueco q/ cerraba W-1: el host ⊘ sabe quién llega
     *       ⇒ sin ésto tendría q/ <b>listar todo el buzón</b>.</li>
     * </ul>
     */
    private final class PunchPollHandler implements HttpHandler {
        @Override
        public void handle(final HttpExchange exchange) throws IOException {
            try (exchange) {
                final Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
                final long now = System.currentTimeMillis();

                final String target = params.get("target");
                if (target != null) {
                    if (!TrackerProtocol.isValidAzoreaId(target)) {
                        respondJson(exchange, 400,
                                new TrackerProtocol.ErrorResponse("invalid", "target inválido"));
                        return;
                    }
                    final PunchAnnounce addressed = punchAnnounces.values().stream()
                            .filter(a -> target.equals(a.target()))
                            .filter(a -> !target.equals(a.azoreaId()))
                            .filter(a -> (now - a.timestampMs()) <= PUNCH_TTL_MS)
                            .findFirst().orElse(null);
                    if (addressed == null) {
                        respondJson(exchange, 404,
                                new TrackerProtocol.ErrorResponse("not_found", "sin announce dirigido"));
                        return;
                    }
                    respondJson(exchange, 200, addressed);
                    return;
                }

                final String azoreaId = params.get("azoreaId");
                if (azoreaId == null || !TrackerProtocol.isValidAzoreaId(azoreaId)) {
                    respondJson(exchange, 400,
                            new TrackerProtocol.ErrorResponse("invalid", "azoreaId inválido"));
                    return;
                }
                final PunchAnnounce announce = punchAnnounces.get(azoreaId);
                if (announce == null || (now - announce.timestampMs()) > PUNCH_TTL_MS) {
                    respondJson(exchange, 404,
                            new TrackerProtocol.ErrorResponse("not_found", "sin announce"));
                    return;
                }
                // § VERBATIM: el tracker no puede alterar un payload firmado.
                respondJson(exchange, 200, announce);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error en PunchPollHandler", e);
                respondJson(exchange, 500,
                        new TrackerProtocol.ErrorResponse("internal_error", e.getMessage()));
            }
        }
    }

    private final class PunchClearHandler implements HttpHandler {
        @Override
        public void handle(final HttpExchange exchange) throws IOException {
            try (exchange) {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    respondJson(exchange, 405,
                            new TrackerProtocol.ErrorResponse("method_not_allowed",
                                    exchange.getRequestMethod()));
                    return;
                }
                final PunchAnnounce in;
                try {
                    in = gson.fromJson(readBody(exchange), PunchAnnounce.class);
                } catch (Exception e) {
                    respondJson(exchange, 400,
                            new TrackerProtocol.ErrorResponse("invalid", e.getMessage()));
                    return;
                }
                if (in != null && in.azoreaId() != null) {
                    punchAnnounces.remove(in.azoreaId());
                }
                respondJson(exchange, 204, "");
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error en PunchClearHandler", e);
                respondJson(exchange, 500,
                        new TrackerProtocol.ErrorResponse("internal_error", e.getMessage()));
            }
        }
    }

    /** Barre announces caducados — llamado desde {@code sweepExpired}. */
    private void sweepPunch() {
        final long now = System.currentTimeMillis();
        punchAnnounces.entrySet().removeIf(e -> (now - e.getValue().timestampMs()) > PUNCH_TTL_MS);
    }

    // ===== Utilidades HTTP =====

    private void respondJson(final HttpExchange exchange, final int status, final Object body) throws IOException {
        final byte[] bytes;
        if (body instanceof String s) {
            bytes = s.getBytes(StandardCharsets.UTF_8);
        } else {
            bytes = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String readBody(final HttpExchange exchange) throws IOException {
        return readBody(exchange, MAX_BODY_SMALL);
    }

    /**
     * Lee el body c/ tope duro de bytes. Defense-in-depth contra DoS por body grande:
     * rechazar upfront si Content-Length declara más del tope, y leer como máximo
     * {@code maxBytes + 1} para detectar overflow real. Devuelve la cadena UTF-8.
     */
    private static String readBody(final HttpExchange exchange, final int maxBytes) throws IOException {
        // (1) Rechazo upfront por Content-Length — ahorra leer.
        final var cl = exchange.getRequestHeaders().getFirst("Content-Length");
        if (cl != null && !cl.isBlank()) {
            try {
                final long declared = Long.parseLong(cl.trim());
                if (declared > maxBytes) {
                    throw new IllegalArgumentException(
                            "body demasiado grande: " + declared + " > " + maxBytes + " bytes");
                }
            } catch (final NumberFormatException ignored) {
                // CL ausente o malformado ⇒ caemos al cap real en (2).
            }
        }
        // (2) Cap real — leemos un byte más del máximo; si llega entero, overflow.
        final byte[] buf = new byte[maxBytes + 1];
        int total = 0;
        try (InputStream in = exchange.getRequestBody()) {
            while (total < buf.length) {
                final int n = in.read(buf, total, buf.length - total);
                if (n < 0) break;
                total += n;
            }
        }
        if (total > maxBytes) {
            throw new IllegalArgumentException(
                    "body demasiado grande (> " + maxBytes + " bytes)");
        }
        return new String(buf, 0, total, StandardCharsets.UTF_8);
    }

    private boolean checkRateLimit(final String ip) {
        final RateState state = rateLimitCounters.computeIfAbsent(ip,
                k -> new RateState(new AtomicLong(System.currentTimeMillis()), new AtomicInteger(0)));

        final long now = System.currentTimeMillis();
        final long oneHourMs = TimeUnit.HOURS.toMillis(1);

        // Reset ventana si >1h desde el último reset (lock-free compare-and-set).
        if (now - state.windowStartMs().get() > oneHourMs) {
            if (state.windowStartMs().compareAndSet(state.windowStartMs().get(), now)) {
                state.count().set(0);
            }
        }

        return state.count().incrementAndGet() <= RATE_LIMIT_PER_HOUR;
    }

    private record RateState(AtomicLong windowStartMs, AtomicInteger count) {
    }

    private String clientIp(final HttpExchange exchange) {
        // § Audit 2026-10-07: por default NO se confía en X-Forwarded-For. Un atacante
        //   puede falsificar la cabecera y bypasear el rate limit per-IP. Sólo se
        //   honra si el operador construyó el TrackerServer con trust_forwarded_for=true
        //   (ver CLI en TrackerServerMain).
        if (trustForwardedFor) {
            final String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                final int comma = forwarded.indexOf(',');
                return comma >= 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
            }
        }
        final var remote = exchange.getRemoteAddress();
        return remote != null ? remote.getAddress().getHostAddress() : "unknown";
    }

    private static Map<String, String> parseQuery(final String query) {
        final Map<String, String> map = new java.util.HashMap<>();
        if (query == null || query.isEmpty()) return map;
        for (String pair : query.split("&")) {
            final int eq = pair.indexOf('=');
            if (eq > 0) {
                map.put(pair.substring(0, eq), pair.substring(eq + 1));
            } else if (!pair.isEmpty()) {
                map.put(pair, "");
            }
        }
        return map;
    }

    // Records internos
    private record AnnounceResponsePayload(long expiresAt) {
    }
}