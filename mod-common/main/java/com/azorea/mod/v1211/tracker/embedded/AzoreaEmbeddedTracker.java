// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.tracker.embedded;

import com.azorea.mod.tracker.TrackerProtocol;
import com.azorea.mod.v1211.AzoreaNetLog;
import com.azorea.mod.v1211.AzoreaNetLog.Category;
import com.azorea.mod.v1211.tracker.AzoreaPunchExchange;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracker Azorea embebido en proceso (in-process) — para uso LAN y fallback local
 * (ver AGENTS.md § Diseño SEAMLESS F7+).
 *
 * <p>§ Por qué embebido:
 * <ul>
 *   <li>El usuario NO quiere configurar URLs manualmente.</li>
 *   <li>Cada instancia del mod arranca su propio tracker en un puerto aleatorio.</li>
 *   <li>Para LAN: cada par cliente↔tracker descubren por UDP multicast.</li>
 *   <li>Para WAN: el embedded tracker puede actuar como relay entre los 2 mods.</li>
 * </ul>
 *
 * <p>§ Endpoints implementados (mismo protocolo que tracker-server/):
 * <ul>
 *   <li>POST /announce — registra/refresh anuncio</li>
 *   <li>GET /list — lista juegos activos</li>
 *   <li>POST /presence — jugador join/leave</li>
 *   <li>POST /invite — envía invite cifrado</li>
 *   <li>GET /invites/{azorea_id} — poll pending invites (drain)</li>
 *   <li>GET /friend/{azorea_id} — lookup online status</li>
 *   <li>GET /health — estado</li>
 * </ul>
 *
 * <p>§ Limitaciones v1:
 * <ul>
 *   <li>Sin persistencia (in-memory only). Adecuado para sesión actual.</li>
 *   <li>Sin rate limiting (asumimos LAN trusted).</li>
 *   <li>Sin relay TCP server (solo HTTP). v2 podría añadir relay.</li>
 * </ul>
 */
public final class AzoreaEmbeddedTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzoreaEmbeddedTracker.class);
    private static final Gson GSON = TrackerProtocol.gson();

    private static final int SWEEPER_PERIOD_SECONDS = 30;
    private static final int DEFAULT_BACKLOG = 0;
    private static final int MAX_BODY_BYTES = 64 * 1024;  // 64 KB max payload

    // Estado en memoria.
    private final Map<String, Entry> games = new ConcurrentHashMap<>();
    private final Map<String, Map<String, TrackerProtocol.Identity>> presences = new ConcurrentHashMap<>();
    private final Map<String, List<TrackerProtocol.Invite>> pendingInvites = new ConcurrentHashMap<>();
    private final AtomicLong inviteCounter = new AtomicLong(0);

    // § F8.x: punch coordination state.
    private final Map<String, PunchAnnounce> punchAnnounces = new ConcurrentHashMap<>();

    /**
     * Buzón d/ punch — envoltorio q/ el tracker almacena y devuelve <b>verbatim</b>.
     *
     * <p>§ {@code payload} = un {@code AzoreaPunchExchange.Endpoint} <b>firmado</b>.
     * El tracker no lo entiende ni lo modifica: valida firma + ata id↔clave antes d/
     * guardar, y luego los bytes salen tal cual entraron.
     *
     * <p>§ ⊘ se reescribe la IP con la source d/ la petición (como hacía antes). Reescribir
     * rompería la firma, y <b>la firma es lo q/ impide q/ un rendezvous hostil redirija</b>
     * — un tracker q/ puede alterar el payload justamente tendría poder d/ redirection.
     */
    public record PunchAnnounce(
            String azoreaId,
            String payload,
            long timestampMs,
            /**
             * A QUIÉN va dirigido — <b>routing, ⊘ identidad</b>: no entra en la firma.
             *
             * <p>§ Por q/ existe: el <b>joiner</b> sabe el id del host (lo trae el bundle),
             * pero el <b>host</b> no sabe quién va a unirse ⇒ ⊘ puede pollear "por id del
             * peer". El joiner pone {@code target=hostId} y el host consulta
             * {@code GET /punch/poll?target=<su id>} ⇒ encuentra al joiner <b>sin listar
             * el buzón</b> (que sería un leak d/ ids a cualquiera q/ llegue).
             *
             * <p>Un rendezvous hostil ⊘ puede explotarlo: solo decide a quién le llega un
             * announce — no puede alterar el payload firmado ni suplantar al emisor.
             */
            String target) {
        /** Compat: announce sin destinatario (los tests y el flujo joiner→host). */
        public PunchAnnounce(final String azoreaId, final String payload, final long timestampMs) {
            this(azoreaId, payload, timestampMs, null);
        }
    }

    /**
     * GET /punch/observe — el endpoint público <b>TCP</b> q/ tu conexión acaba d/ crear.
     *
     * <p>§ Por qué hace falta (y por qué STUN <b>no</b> sirve): STUN mide el mapeo
     * <b>UDP</b>, y el punch d'Azorea es <b>TCP</b>. En un NAT cónico el mapeo depende
     * (protocolo, puerto local) ⇒ medir uno ⊘ da el otro.
     *
     * <p>§ El truco: conectar <b>desde el propio puerto del punch</b> al tracker ⇒ el
     * tracker ve tu dirección <b>ya traducida por tu NAT</b> ⇒ eso es exactamente lo q/
     * el peer tiene q/ discar. Y de paso <b>crea el mapeo</b> (q/ si no, no existe hasta
     * q/ el primer paquete sale).
     *
     * @param ip    tu IP pública tal q/ la ve el tracker
     * @param port  tu puerto público <b>externo</b> (no el local)
     */
    public record Observed(String ip, int port) {
    }

    private static String normalizeIpv4(final String s) {
        // § Un socket IPv4 mapeado llega como "::ffff:1.2.3.4" — devolver la forma IPv4,
        //   q/ es la q/ el peer usará d/ todos modos al discar.
        if (s != null && s.startsWith("::ffff:") && s.indexOf('.') >= 0) {
            return s.substring(7);
        }
        return s;
    }

    private final class PunchObserveHandler implements HttpHandler {
        @Override
        public void handle(final HttpExchange ex) throws IOException {
            try {
                if (!"GET".equals(ex.getRequestMethod())) {
                    ex.sendResponseHeaders(405, -1);
                    ex.close();
                    return;
                }
                final InetSocketAddress remote = ex.getRemoteAddress();
                writeJson(ex, 200, new Observed(
                        normalizeIpv4(remote.getAddress().getHostAddress()), remote.getPort()));
            } catch (final Exception e) {
                LOGGER.warn("PunchObserveHandler error", e);
                writeJson(ex, 500, new TrackerProtocol.ErrorResponse("internal", e.getMessage()));
            }
        }
    }

    private final int port;
    private HttpServer httpServer;
    private ScheduledExecutorService sweeper;
    private EmbeddedRelayServer relayServer;

    /** Anuncio registrado. */
    public record Entry(
            TrackerProtocol.Announcement announcement,
            String hostToken,
            long expiresAtMs) {
    }

    public AzoreaEmbeddedTracker(final int port) {
        this.port = port;
    }

    /** Arranca el servidor HTTP. Idempotente. */
    public void start() throws IOException {
        if (httpServer != null) {
            LOGGER.warn("Embedded tracker ya estaba arrancado; ignorando start()");
            return;
        }
        httpServer = HttpServer.create(new InetSocketAddress(port), DEFAULT_BACKLOG);
        httpServer.createContext("/announce", new AnnounceHandler());
        httpServer.createContext("/list", new ListHandler());
        httpServer.createContext("/health", new HealthHandler());
        httpServer.createContext("/presence", new PresenceHandler());
        httpServer.createContext("/invite", new SendInviteHandler());
        httpServer.createContext("/invites/", new PollInvitesHandler());
        httpServer.createContext("/friend/", new FriendLookupHandler());
        // § F8.x: punch coordination (hole-punch TCP coordinado).
        httpServer.createContext("/punch/announce", new PunchAnnounceHandler());
        httpServer.createContext("/punch/poll", new PunchPollHandler());
        httpServer.createContext("/punch/clear", new PunchClearHandler());
        httpServer.createContext("/punch/observe", new PunchObserveHandler());
        httpServer.setExecutor(Executors.newFixedThreadPool(4));
        httpServer.start();
        LOGGER.info("Azorea embedded tracker arrancado en puerto {}", actualPort());

        sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "azorea-embedded-sweeper");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleAtFixedRate(this::sweep, SWEEPER_PERIOD_SECONDS,
                SWEEPER_PERIOD_SECONDS, TimeUnit.SECONDS);

        // § F8.x: arrancar relay TCP en puerto = trackerPort + 1 (si está libre).
        // Si falla, el relay queda deshabilitado (no fatal).
        try {
            final int relayPort = actualPort() + 1;
            relayServer = new EmbeddedRelayServer(relayPort);
            relayServer.start();
            LOGGER.info("Embedded relay arrancado en puerto {}", relayPort);
        } catch (final IOException e) {
            LOGGER.warn("Embedded relay no arrancó (puerto ocupado?): {}", e.getMessage());
            relayServer = null;
        }
    }

    public void stop() {
        if (relayServer != null) {
            relayServer.stop();
            relayServer = null;
        }
        if (sweeper != null) {
            sweeper.shutdownNow();
            sweeper = null;
        }
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        LOGGER.info("Azorea embedded tracker detenido");
    }

    /** Relay TCP embebido (null si no arrancó). */
    public EmbeddedRelayServer relay() {
        return relayServer;
    }

    /** Puerto del relay (0 si no disponible). */
    public int relayPort() {
        return relayServer != null ? relayServer.port() : 0;
    }

    public int actualPort() {
        return httpServer == null ? port : httpServer.getAddress().getPort();
    }

    public String localUrl() {
        return "http://127.0.0.1:" + actualPort();
    }

    /** Sweeper: purga entries expiradas. */
    private void sweep() {
        final long now = System.currentTimeMillis();
        int removed = 0;
        for (final Map.Entry<String, Entry> e : games.entrySet()) {
            if (e.getValue().expiresAtMs() < now) {
                games.remove(e.getKey());
                presences.remove(e.getKey());
                removed++;
            }
        }
        for (final Map.Entry<String, List<TrackerProtocol.Invite>> e : pendingInvites.entrySet()) {
            final int before = e.getValue().size();
            e.getValue().removeIf(inv -> (now - inv.sentAt() * 1000L) > TrackerProtocol.INVITE_TTL_SECONDS * 1000L);
            if (e.getValue().isEmpty()) {
                pendingInvites.remove(e.getKey());
            } else if (e.getValue().size() != before) {
                removed++;
            }
        }
        if (removed > 0) {
            LOGGER.debug("Sweeper: {} entries expiradas purgadas", removed);
        }
    }

    // ===== HTTP Handlers =====

    private final class AnnounceHandler implements HttpHandler {
        @Override
        public void handle(final HttpExchange ex) throws IOException {
            try {
                if ("POST".equals(ex.getRequestMethod())) {
                    final TrackerProtocol.Announcement ann = readJson(ex, TrackerProtocol.Announcement.class);
                    TrackerProtocol.validate(ann);
                    if (!ann.mods().stream().anyMatch(m -> "azorea".equals(m.modid()))) {
                        writeJson(ex, 400, new TrackerProtocol.ErrorResponse("missing_mod",
                                "mod 'azorea' es obligatorio"));
                        return;
                    }
                    // § F9 (DA-8): verificar IDENTIDAD antes de aceptar.
                    // (1) azorea_id re-deriva de las claves publicadas + hwCommit
                    // (2) firma Ed25519 válida sobre el contenido canónico
                    // Sin esto cualquiera podría anunciar fingiendo ser otra ID.
                    try {
                        TrackerProtocol.verifyAnnounce(ann);
                    } catch (final IllegalArgumentException e) {
                        AzoreaNetLog.failure(Category.TRACKER, "announce-verify",
                                "game_id=" + ann.gameId(), e.getMessage());
                        writeJson(ex, 403, new TrackerProtocol.ErrorResponse(
                                "identity_unverified", e.getMessage()));
                        return;
                    }
                    final long ttlMs = ann.ttlSeconds() * 1000L;
                    final long expiresAt = System.currentTimeMillis() + ttlMs;
                    games.put(ann.gameId(), new Entry(ann, ann.hostToken(), expiresAt));
                    LOGGER.info("Announce registrado: game_id={} ({} mods, ttl={}s)",
                            ann.gameId(), ann.mods().size(), ann.ttlSeconds());
                    writeJson(ex, 201, new TrackerProtocol.AnnounceResponse(expiresAt));
                } else if ("DELETE".equals(ex.getRequestMethod())) {
                    final String query = ex.getRequestURI().getQuery();
                    final String gameId = param(query, "game_id");
                    final String token = param(query, "token");
                    if (gameId == null || token == null) {
                        writeJson(ex, 400, new TrackerProtocol.ErrorResponse("missing_param",
                                "game_id y token requeridos"));
                        return;
                    }
                    final Entry entry = games.get(gameId);
                    if (entry == null) {
                        writeJson(ex, 404, new TrackerProtocol.ErrorResponse("not_found", "game_id no existe"));
                        return;
                    }
                    if (!entry.hostToken().equals(token)) {
                        writeJson(ex, 403, new TrackerProtocol.ErrorResponse("forbidden", "token inválido"));
                        return;
                    }
                    games.remove(gameId);
                    presences.remove(gameId);
                    LOGGER.info("Announce eliminado: game_id={}", gameId);
                    ex.sendResponseHeaders(204, -1);
                    ex.close();
                } else {
                    ex.sendResponseHeaders(405, -1);
                    ex.close();
                }
            } catch (final IllegalArgumentException e) {
                writeJson(ex, 400, new TrackerProtocol.ErrorResponse("invalid", e.getMessage()));
            } catch (final Exception e) {
                LOGGER.warn("AnnounceHandler error", e);
                writeJson(ex, 500, new TrackerProtocol.ErrorResponse("internal", e.getMessage()));
            }
        }
    }

    private final class ListHandler implements HttpHandler {
        @Override
        public void handle(final HttpExchange ex) throws IOException {
            try {
                final String query = ex.getRequestURI().getQuery();
                final String mcVersion = param(query, "mc_version");
                final String modId = param(query, "mod_id");
                final int maxResults = parseIntOrDefault(param(query, "max_results"), TrackerProtocol.MAX_LIST_RESULTS);

                final long now = System.currentTimeMillis();
                final List<TrackerProtocol.GameListing> out = new ArrayList<>();
                for (final Entry e : games.values()) {
                    if (e.expiresAtMs() < now) continue;
                    final var a = e.announcement();
                    if (mcVersion != null && !mcVersion.equals(a.mcVersion())) continue;
                    if (modId != null && a.mods().stream().noneMatch(m -> modId.equals(m.modid()))) continue;
                    final var players = presences.getOrDefault(a.gameId(), Map.of());
                    final List<TrackerProtocol.Identity> playerList = players.values().stream().toList();
                    out.add(new TrackerProtocol.GameListing(
                            a.gameId(),
                            a.hostIdentity(),
                            a.mcVersion(),
                            a.neoForgeVersion(),
                            a.mods(),
                            a.maxPlayers(),
                            playerList.size(),
                            playerList,
                            a.worldName(),
                            a.inviteCode(),
                            a.timestamp(),
                            now,
                            e.expiresAtMs()));
                    if (out.size() >= maxResults) break;
                }
                writeJson(ex, 200, new TrackerProtocol.ListResponse(out));
            } catch (final Exception e) {
                LOGGER.warn("ListHandler error", e);
                writeJson(ex, 500, new TrackerProtocol.ErrorResponse("internal", e.getMessage()));
            }
        }
    }

    private final class HealthHandler implements HttpHandler {
        @Override
        public void handle(final HttpExchange ex) throws IOException {
            final String body = String.format(
                    "{\"status\":\"ok\",\"version\":%d,\"games\":%d,\"embedded\":true,\"port\":%d}",
                    TrackerProtocol.AZOREA_PROTOCOL_VERSION, games.size(), actualPort());
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private final class PresenceHandler implements HttpHandler {
        @Override
        public void handle(final HttpExchange ex) throws IOException {
            try {
                final TrackerProtocol.PresenceUpdate up = readJson(ex, TrackerProtocol.PresenceUpdate.class);
                final TrackerProtocol.Identity who = up.identity();
                final Map<String, TrackerProtocol.Identity> players = presences.computeIfAbsent(
                        up.gameId(), k -> new ConcurrentHashMap<>());
                if ("join".equals(up.op())) {
                    players.put(who.azoreaId(), who);
                } else if ("leave".equals(up.op())) {
                    players.remove(who.azoreaId());
                }
                writeJson(ex, 200, new TrackerProtocol.PresenceResponse(players.size()));
            } catch (final Exception e) {
                LOGGER.warn("PresenceHandler error", e);
                writeJson(ex, 500, new TrackerProtocol.ErrorResponse("internal", e.getMessage()));
            }
        }
    }

    private final class SendInviteHandler implements HttpHandler {
        @Override
        public void handle(final HttpExchange ex) throws IOException {
            try {
                final TrackerProtocol.InviteRequest req = readJson(ex, TrackerProtocol.InviteRequest.class);
                final Entry entry = games.get(req.gameId());
                if (entry == null) {
                    writeJson(ex, 404, new TrackerProtocol.ErrorResponse("game_not_found", "game_id no existe"));
                    return;
                }
                if (!entry.announcement().hostIdentity().azoreaId().equals(req.fromIdentity().azoreaId())) {
                    writeJson(ex, 403, new TrackerProtocol.ErrorResponse("not_host",
                            "solo el host puede enviar invites"));
                    return;
                }
                final long now = System.currentTimeMillis() / 1000L;
                final TrackerProtocol.Invite inv = new TrackerProtocol.Invite(
                        req.fromIdentity(),
                        req.gameId(),
                        req.encryptedBlobBase64(),
                        now);
                pendingInvites.computeIfAbsent(req.toAzoreaId(), k -> new ArrayList<>()).add(inv);
                LOGGER.info("Invite queued: game={} → to={} (type={})",
                        req.gameId(), req.toAzoreaId(), req.typeOrDefault());
                writeJson(ex, 201, new TrackerProtocol.InviteAck(true, now));
            } catch (final Exception e) {
                LOGGER.warn("SendInviteHandler error", e);
                writeJson(ex, 500, new TrackerProtocol.ErrorResponse("internal", e.getMessage()));
            }
        }
    }

    private final class PollInvitesHandler implements HttpHandler {
        @Override
        public void handle(final HttpExchange ex) throws IOException {
            try {
                final String path = ex.getRequestURI().getPath();
                final String prefix = "/invites/";
                if (!path.startsWith(prefix)) {
                    writeJson(ex, 400, new TrackerProtocol.ErrorResponse("bad_path", "expected /invites/<id>"));
                    return;
                }
                final String azoreaId = path.substring(prefix.length());
                if (!TrackerProtocol.isValidAzoreaId(azoreaId)) {
                    writeJson(ex, 400, new TrackerProtocol.ErrorResponse("invalid_id", "azoreaId inválido"));
                    return;
                }
                final List<TrackerProtocol.Invite> drained = pendingInvites.remove(azoreaId);
                writeJson(ex, 200, new TrackerProtocol.InviteList(drained == null ? List.of() : drained));
            } catch (final Exception e) {
                LOGGER.warn("PollInvitesHandler error", e);
                writeJson(ex, 500, new TrackerProtocol.ErrorResponse("internal", e.getMessage()));
            }
        }
    }

    private final class FriendLookupHandler implements HttpHandler {
        @Override
        public void handle(final HttpExchange ex) throws IOException {
            try {
                final String path = ex.getRequestURI().getPath();
                final String prefix = "/friend/";
                if (!path.startsWith(prefix)) {
                    writeJson(ex, 400, new TrackerProtocol.ErrorResponse("bad_path", "expected /friend/<id>"));
                    return;
                }
                final String azoreaId = path.substring(prefix.length());
                if (!TrackerProtocol.isValidAzoreaId(azoreaId)) {
                    writeJson(ex, 400, new TrackerProtocol.ErrorResponse("invalid_id", "azoreaId inválido"));
                    return;
                }
                final long now = System.currentTimeMillis();
                for (final var gameEntry : presences.entrySet()) {
                    final var players = gameEntry.getValue();
                    if (players.containsKey(azoreaId)) {
                        final var id = players.get(azoreaId);
                        writeJson(ex, 200, new TrackerProtocol.FriendStatus(
                                id.azoreaId(),
                                id.displayName(),
                                id.publicKeyBase64(),
                                true,
                                gameEntry.getKey(),
                                now / 1000L));
                        return;
                    }
                }
                for (final Entry e : games.values()) {
                    if (e.announcement().hostIdentity().azoreaId().equals(azoreaId)) {
                        final var id = e.announcement().hostIdentity();
                        writeJson(ex, 200, new TrackerProtocol.FriendStatus(
                                id.azoreaId(),
                                id.displayName(),
                                id.publicKeyBase64(),
                                true,
                                e.announcement().gameId(),
                                now / 1000L));
                        return;
                    }
                }
                writeJson(ex, 404, new TrackerProtocol.ErrorResponse("not_online", "friend no está online"));
            } catch (final Exception e) {
                LOGGER.warn("FriendLookupHandler error", e);
                writeJson(ex, 500, new TrackerProtocol.ErrorResponse("internal", e.getMessage()));
            }
        }
    }

    // ===== F8.x: Punch coordination handlers =====

    /**
     * POST /punch/announce — publica un endpoint <b>firmado</b>.
     *
     * <p>§ El tracker es aquí un <b>buzón ciego al contenido</b>: guarda y devuelve bytes
     * q/ no puede alterar. Verifica antes d/ almacenar (⊕ basura en memoria) y comprueba
     * q/ el id <b>derivado</b> d/ las claves del payload sea la MISMA clave por la q/ se
     * guarda — si no, un peer podría publicar bajo la id d/ otro.
     */
    private final class PunchAnnounceHandler implements HttpHandler {
        @Override
        public void handle(final HttpExchange ex) throws IOException {
            try {
                if (!"POST".equals(ex.getRequestMethod())) {
                    ex.sendResponseHeaders(405, -1);
                    ex.close();
                    return;
                }
                final PunchAnnounce announce = readJson(ex, PunchAnnounce.class);
                if (announce == null || announce.azoreaId() == null
                        || !TrackerProtocol.isValidAzoreaId(announce.azoreaId())) {
                    writeJson(ex, 400, new TrackerProtocol.ErrorResponse("invalid", "azoreaId inválido"));
                    return;
                }
                if (announce.payload() == null || announce.payload().isBlank()) {
                    writeJson(ex, 400, new TrackerProtocol.ErrorResponse(
                            "unsigned", "announce sin firma — Azorea ≥ 2026-10-01 no acepta announce plano"));
                    return;
                }

                // Verificar ANTES d/ guardar.
                final AzoreaPunchExchange.Verified verified;
                try {
                    verified = AzoreaPunchExchange.parseAndVerify(
                            announce.payload(), System.currentTimeMillis());
                } catch (final IllegalArgumentException e) {
                    writeJson(ex, 400, new TrackerProtocol.ErrorResponse("invalid", e.getMessage()));
                    return;
                }
                // Ata id↔clave: el id rederivado debe ser la clave del buzón/polleo.
                if (!announce.azoreaId().equals(verified.azoreaId())) {
                    writeJson(ex, 400, new TrackerProtocol.ErrorResponse(
                            "id_mismatch",
                            "el id del payload (" + verified.azoreaId() + ") no es la clave ("
                                    + announce.azoreaId() + ")"));
                    return;
                }

                // § Solo informativo: si la source IP ≠ la IP firmada, algo raro hay
                // (multi-homed o STUN por otra salida). NO se reescribe — eso rompería
                // la firma. El punch fallará honestamente después si no cuadra.
                final String sourceIp = ex.getRemoteAddress().getAddress().getHostAddress();
                if (!sourceIp.equals(verified.ip())) {
                    LOGGER.warn("Punch announce: source IP {} ≠ IP firmada {} ({}) — posible NAT asimétrico o multi-homed",
                            sourceIp, verified.ip(), announce.azoreaId());
                }

                // § Routing: a QUIÉN va dirigido. Opcional (⊥ la firma) y validado — si
                // viniera basura, pollear `?target=` barre todas las claves.
                final String target = announce.target() == null || announce.target().isBlank()
                        ? null : announce.target().trim();
                if (target != null && !TrackerProtocol.isValidAzoreaId(target)) {
                    writeJson(ex, 400, new TrackerProtocol.ErrorResponse("invalid", "target inválido"));
                    return;
                }

                final PunchAnnounce stored = new PunchAnnounce(
                        announce.azoreaId(), announce.payload(), System.currentTimeMillis(), target);
                punchAnnounces.put(stored.azoreaId(), stored);
                LOGGER.debug("Punch announce: {} ip={} port={} target={}",
                        stored.azoreaId(), verified.ip(), verified.port(), target);
                writeJson(ex, 200, stored);
            } catch (final Exception e) {
                LOGGER.warn("PunchAnnounceHandler error", e);
                writeJson(ex, 500, new TrackerProtocol.ErrorResponse("internal", e.getMessage()));
            }
        }
    }

    /**
     * GET /punch/poll — dos modos:
     * <ul>
     *   <li><b>{@code ?azoreaId=X}</b> → el announce <b>de X</b> (joiner → host, q/ ya
     *       conoce el id d/ l'host x el bundle).</li>
     *   <li><b>{@code ?target=X}</b> → el primer announce <b>dirigido a X</b> y q/ no sea
     *       de X (host → joiner). Ése es el hueco q/ cerraba W-1: el host ⊘ sabe quién va
     *       a unirse, así q/ sin esto tendría q/ <b>listar todo el buzón</b>.</li>
     * </ul>
     */
    private final class PunchPollHandler implements HttpHandler {
        @Override
        public void handle(final HttpExchange ex) throws IOException {
            try {
                final String query = ex.getRequestURI().getQuery();

                // § Modo `target` — el host buscando a quien le habla.
                final String target = param(query, "target");
                if (target != null) {
                    if (!TrackerProtocol.isValidAzoreaId(target)) {
                        writeJson(ex, 400, new TrackerProtocol.ErrorResponse("invalid", "target inválido"));
                        return;
                    }
                    final long now = System.currentTimeMillis();
                    final PunchAnnounce addressed = punchAnnounces.values().stream()
                            .filter(a -> target.equals(a.target()))          // dirigido a mí
                            .filter(a -> !target.equals(a.azoreaId()))       // ⊘ el mío propio
                            .filter(a -> (now - a.timestampMs()) <= 30_000L) // sin caducar
                            .findFirst().orElse(null);
                    if (addressed == null) {
                        writeJson(ex, 404, new TrackerProtocol.ErrorResponse("not_found", "sin announce dirigido"));
                        return;
                    }
                    writeJson(ex, 200, addressed);
                    return;
                }

                final String azoreaId = param(query, "azoreaId");
                if (azoreaId == null || !TrackerProtocol.isValidAzoreaId(azoreaId)) {
                    writeJson(ex, 400, new TrackerProtocol.ErrorResponse("invalid", "azoreaId inválido"));
                    return;
                }
                final PunchAnnounce announce = punchAnnounces.get(azoreaId);
                if (announce == null || (System.currentTimeMillis() - announce.timestampMs()) > 30_000L) {
                    // No existe o expiró (30s).
                    writeJson(ex, 404, new TrackerProtocol.ErrorResponse("not_found", "sin announce"));
                    return;
                }
                // § VERBATIM: payload firmado q/ el tracker no puede alterar.
                writeJson(ex, 200, announce);
            } catch (final Exception e) {
                LOGGER.warn("PunchPollHandler error", e);
                writeJson(ex, 500, new TrackerProtocol.ErrorResponse("internal", e.getMessage()));
            }
        }
    }

    /** POST /punch/clear — elimina announce del peer (llamar tras punch completado/fallido). */
    private final class PunchClearHandler implements HttpHandler {
        @Override
        public void handle(final HttpExchange ex) throws IOException {
            try {
                if (!"POST".equals(ex.getRequestMethod())) {
                    ex.sendResponseHeaders(405, -1);
                    ex.close();
                    return;
                }
                final PunchAnnounce announce = readJson(ex, PunchAnnounce.class);
                if (announce != null && announce.azoreaId() != null) {
                    punchAnnounces.remove(announce.azoreaId());
                    LOGGER.debug("Punch announce cleared: {}", announce.azoreaId());
                }
                ex.sendResponseHeaders(204, -1);
                ex.close();
            } catch (final Exception e) {
                LOGGER.warn("PunchClearHandler error", e);
                writeJson(ex, 500, new TrackerProtocol.ErrorResponse("internal", e.getMessage()));
            }
        }
    }

    // ===== Utilidades HTTP =====

    private static <T> T readJson(final HttpExchange ex, final Class<T> type) throws IOException {
        final byte[] buf = new byte[MAX_BODY_BYTES];
        try (InputStream in = ex.getRequestBody()) {
            final int n = in.readNBytes(buf, 0, buf.length);
            if (n == 0) {
                throw new IllegalArgumentException("body vacío");
            }
            return GSON.fromJson(new String(buf, 0, n, StandardCharsets.UTF_8), type);
        }
    }

    private static void writeJson(final HttpExchange ex, final int status, final Object body) throws IOException {
        final byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String param(final String query, final String key) {
        if (query == null) return null;
        for (final String pair : query.split("&")) {
            final int eq = pair.indexOf('=');
            if (eq < 0) continue;
            if (pair.substring(0, eq).equals(key)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static int parseIntOrDefault(final String s, final int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s);
        } catch (final NumberFormatException e) {
            return def;
        }
    }

    @SuppressWarnings("unused")
    private static String b64(final byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }
}
