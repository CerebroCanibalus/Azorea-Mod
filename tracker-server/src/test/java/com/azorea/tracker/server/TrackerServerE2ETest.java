// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.tracker.server;

import com.google.gson.Gson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests E2E del TrackerServer v2 (ver AGENTS.md § F2.5 + F5.2).
 *
 * Arranca un TrackerServer real en puerto efímero y verifica:
 * <ul>
 *   <li>v1 endpoints: /announce, /list, /health, DELETE /announce</li>
 *   <li>v2 endpoints (F5.2): /presence, /invite, /invites/{id}, /friend/{id}</li>
 *   <li>PRIVACIDAD CRÍTICA: /list NO expone host/port del host (sin IP leak)</li>
 * </ul>
 *
 * Diseño: cada test es self-contained. Anuncia su propio juego y verifica
 * SOLO la presencia (o ausencia) de ese gameId específico, no cuenta global.
 * Limpieza via {@link #cleanupGame(String, String)} en {@code @AfterEach}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrackerServerE2ETest {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TrackerServer server;
    private int port;
    private Path tempDataFile;
    private HttpClient httpClient;
    private final Gson gson = TrackerProtocol.gson();

    /** Cleanup pendientes para @AfterEach. */
    private final java.util.List<String[]> cleanups = new java.util.ArrayList<>();

    @BeforeAll
    void startServer() throws Exception {
        port = findFreePort();
        tempDataFile = Files.createTempFile("azorea-tracker-test-", ".json");
        Files.deleteIfExists(tempDataFile);
        server = new TrackerServer(port, tempDataFile);
        server.start();
        httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build();
    }

    @AfterEach
    void cleanupEach() {
        for (final String[] c : cleanups) {
            try {
                deleteAnnouncement(c[0], c[1]);
            } catch (Exception ignored) {
                // best effort
            }
        }
        cleanups.clear();
    }

    @org.junit.jupiter.api.AfterAll
    void stopServer() {
        if (server != null) {
            server.stop();
        }
        if (tempDataFile != null) {
            try {
                Files.deleteIfExists(tempDataFile);
            } catch (Exception ignored) {
            }
        }
    }

    // ===== v1 endpoints (existentes) =====

    @Test
    @DisplayName("Health endpoint responde 200 con status ok y version 2")
    void healthEndpointWorks() throws Exception {
        final HttpResponse<String> response = get("/health");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"status\": \"ok\""));
        assertTrue(response.body().contains("\"version\": 2"), "debe reportar v2: " + response.body());
    }

    @Test
    @DisplayName("Announce válido devuelve 201 con expiresAt")
    void announceSucceeds() throws Exception {
        final TrackerProtocol.Announcement a = makeValidAnnouncement();
        registerCleanup(a);
        final TrackerProtocol.AnnounceResponse parsed = postAnnouncement(a, 201);
        assertTrue(parsed.expiresAt() > 0);
    }

    @Test
    @DisplayName("List después de announce contiene el juego")
    void listReturnsAnnouncedGame() throws Exception {
        final TrackerProtocol.Announcement a = makeValidAnnouncement();
        registerCleanup(a);
        postAnnouncement(a, 201);
        final TrackerProtocol.ListResponse list = getList(200);
        assertTrue(containsGame(list, a.gameId()),
                "list debe contener gameId " + a.gameId());
    }

    @Test
    @DisplayName("Refresh con mismo game_id funciona (idempotente)")
    void refreshWorks() throws Exception {
        final TrackerProtocol.Announcement a = makeValidAnnouncement();
        registerCleanup(a);
        postAnnouncement(a, 201);
        final TrackerProtocol.Announcement refreshed = withTimestamp(a,
                System.currentTimeMillis() / 1000L);
        postAnnouncement(refreshed, 201);
        final TrackerProtocol.ListResponse list = getList(200);
        long count = list.games().stream().filter(g -> a.gameId().equals(g.gameId())).count();
        assertEquals(1, count, "refresh debe mantener 1 sola entrada por gameId");
    }

    @Test
    @DisplayName("Refresh con host_token incorrecto devuelve 403")
    void refreshWithWrongTokenRejected() throws Exception {
        final TrackerProtocol.Announcement a = makeValidAnnouncement();
        registerCleanup(a);
        postAnnouncement(a, 201);
        final TrackerProtocol.Announcement bad = withHostToken(a, "0".repeat(64));
        final HttpResponse<String> response = postRawAnnouncement(bad);
        assertEquals(403, response.statusCode());
        assertTrue(response.body().contains("\"error\": \"forbidden\""));
    }

    @Test
    @DisplayName("Announce sin mod 'azorea' como required es rechazado")
    void announceWithoutRequiredAzoreaRejected() throws Exception {
        final TrackerProtocol.Announcement a = newAnnouncement(
                UUID.randomUUID().toString(),
                List.of(new TrackerProtocol.ModEntry("someother", "1.0.0", false)));
        final HttpResponse<String> response = postRawAnnouncement(a);
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("azorea"));
    }

    @Test
    @DisplayName("Announce con TTL fuera de rango es rechazado")
    void announceWithBadTtlRejected() throws Exception {
        final TrackerProtocol.Announcement a = newAnnouncement(UUID.randomUUID().toString(),
                List.of(new TrackerProtocol.ModEntry("azorea", "0.1.0", true)));
        final TrackerProtocol.Announcement bad = withTtl(a, 30); // <60
        final HttpResponse<String> response = postRawAnnouncement(bad);
        assertEquals(400, response.statusCode());
    }

    @Test
    @DisplayName("Identity validation rechaza azoreaId mal formado")
    void identityWithInvalidAzoreaIdRejected() {
        // El compact constructor del record debe lanzar IllegalArgumentException para
        // formato inválido. Null checks lanzan NPE (no testeamos aquí).
        assertThrows(IllegalArgumentException.class, () ->
                new TrackerProtocol.Identity("INVALID", "Test", "key"));
        assertThrows(IllegalArgumentException.class, () ->
                new TrackerProtocol.Identity("", "Test", "key"));
        assertThrows(IllegalArgumentException.class, () ->
                new TrackerProtocol.Identity("AZ-short", "Test", "key"));
    }

    @Test
    @DisplayName("DELETE válido elimina el juego (solo el gameId específico)")
    void deleteRemovesGame() throws Exception {
        final TrackerProtocol.Announcement a = makeValidAnnouncement();
        postAnnouncement(a, 201); // borramos via DELETE en el test
        final HttpResponse<String> deleteResp = deleteAnnouncement(a.gameId(), a.hostToken());
        assertEquals(204, deleteResp.statusCode());
        final TrackerProtocol.ListResponse list = getList(200);
        assertFalse(containsGame(list, a.gameId()));
    }

    @Test
    @DisplayName("DELETE con token incorrecto devuelve 403")
    void deleteWithWrongTokenRejected() throws Exception {
        final TrackerProtocol.Announcement a = makeValidAnnouncement();
        registerCleanup(a);
        postAnnouncement(a, 201);
        final HttpResponse<String> resp = deleteAnnouncement(a.gameId(), "0".repeat(64));
        assertEquals(403, resp.statusCode());
    }

    @Test
    @DisplayName("DELETE de game_id inexistente devuelve 404")
    void deleteNonexistentReturns404() throws Exception {
        final HttpResponse<String> resp = deleteAnnouncement(UUID.randomUUID().toString(), randomToken());
        assertEquals(404, resp.statusCode());
    }

    @Test
    @DisplayName("Filtro mc_version en GET /list funciona")
    void filterByMcVersionWorks() throws Exception {
        final TrackerProtocol.Announcement a21 = newAnnouncement(
                UUID.randomUUID().toString(),
                List.of(new TrackerProtocol.ModEntry("azorea", "0.1.0", true)),
                "1.21.1");
        final TrackerProtocol.Announcement a20 = newAnnouncement(
                UUID.randomUUID().toString(),
                List.of(new TrackerProtocol.ModEntry("azorea", "0.1.0", true)),
                "1.20.1");
        registerCleanup(a21);
        registerCleanup(a20);
        postAnnouncement(a21, 201);
        postAnnouncement(a20, 201);
        final TrackerProtocol.ListResponse filtered = getListQuery("?mc_version=1.21.1", 200);
        assertTrue(containsGame(filtered, a21.gameId()));
        assertFalse(containsGame(filtered, a20.gameId()));
    }

    @Test
    @DisplayName("Filtro mod_id en GET /list funciona")
    void filterByModIdWorks() throws Exception {
        final TrackerProtocol.Announcement withAzorea = newAnnouncement(
                UUID.randomUUID().toString(),
                List.of(new TrackerProtocol.ModEntry("azorea", "0.1.0", true),
                        new TrackerProtocol.ModEntry("somemod", "1.0.0", false)));
        registerCleanup(withAzorea);
        postAnnouncement(withAzorea, 201);
        final TrackerProtocol.ListResponse filtered = getListQuery("?mod_id=azorea", 200);
        assertTrue(containsGame(filtered, withAzorea.gameId()));
    }

    // ===== F5.2 — PRIVACIDAD: /list NO expone IP =====

    @Test
    @DisplayName("CRÍTICO: GET /list NO contiene IP/port del host (GameListing v2)")
    void listDoesNotExposeHostIp() throws Exception {
        final TrackerProtocol.Announcement a = makeValidAnnouncement();
        registerCleanup(a);
        postAnnouncement(a, 201);

        // Capturamos el JSON crudo para inspección byte-a-byte.
        final HttpResponse<String> resp = get("/list");
        assertEquals(200, resp.statusCode());
        final String body = resp.body();

        // La respuesta NO debe contener el prefijo "address" ni el formato host:port del host.
        assertFalse(body.contains("\"address\""),
                "El JSON no debe contener campo 'address': " + body);
        assertFalse(body.contains("\"connection\""),
                "El JSON no debe contener campo 'connection' (v1 leak): " + body);
        assertFalse(body.contains("127.0.0.1:25565"),
                "El JSON no debe contener '127.0.0.1:25565' (test fixture bind): " + body);
        // Verificación positiva: hostIdentity está presente y contiene la azorea_id.
        assertTrue(body.contains("\"hostIdentity\""),
                "Debe contener hostIdentity: " + body);
        assertTrue(body.contains(a.hostIdentity().azoreaId()),
                "Debe contener la azorea_id del host: " + body);
    }

    // ===== F5.2 — Presence =====

    @Test
    @DisplayName("POST /presence join añade player al game")
    void presenceJoinAddsPlayer() throws Exception {
        final TrackerProtocol.Announcement a = makeValidAnnouncement();
        registerCleanup(a);
        postAnnouncement(a, 201);

        final String playerId = randomAzoreaId();
        final TrackerProtocol.Identity player = new TrackerProtocol.Identity(
                playerId, "Player1", randomPublicKey());

        final TrackerProtocol.PresenceResponse resp = postPresence(
                new TrackerProtocol.PresenceUpdate(a.gameId(), player, "join",
                        System.currentTimeMillis()), 200);
        assertEquals(1, resp.currentPlayers());

        // Verificar que aparece en /list como currentPlayer.
        final TrackerProtocol.ListResponse list = getList(200);
        final TrackerProtocol.GameListing game = findGame(list, a.gameId()).orElseThrow();
        assertEquals(1, game.currentPlayers());
        assertEquals(1, game.currentPlayersIdentity().size());
        assertEquals(playerId, game.currentPlayersIdentity().get(0).azoreaId());
    }

    @Test
    @DisplayName("POST /presence leave elimina player del game")
    void presenceLeaveRemovesPlayer() throws Exception {
        final TrackerProtocol.Announcement a = makeValidAnnouncement();
        registerCleanup(a);
        postAnnouncement(a, 201);

        final String playerId = randomAzoreaId();
        final TrackerProtocol.Identity player = new TrackerProtocol.Identity(
                playerId, "Player1", randomPublicKey());

        postPresence(new TrackerProtocol.PresenceUpdate(a.gameId(), player, "join",
                System.currentTimeMillis()), 200);
        postPresence(new TrackerProtocol.PresenceUpdate(a.gameId(), player, "leave",
                System.currentTimeMillis()), 200);

        final TrackerProtocol.ListResponse list = getList(200);
        final TrackerProtocol.GameListing game = findGame(list, a.gameId()).orElseThrow();
        assertEquals(0, game.currentPlayers());
        assertTrue(game.currentPlayersIdentity().isEmpty());
    }

    @Test
    @DisplayName("POST /presence a game inexistente devuelve 404")
    void presenceForUnknownGameReturns404() throws Exception {
        final String fakeGameId = UUID.randomUUID().toString();
        final TrackerProtocol.Identity player = new TrackerProtocol.Identity(
                randomAzoreaId(), "P", randomPublicKey());
        final HttpResponse<String> resp = postRawPresence(
                new TrackerProtocol.PresenceUpdate(fakeGameId, player, "join",
                        System.currentTimeMillis()));
        assertEquals(404, resp.statusCode());
    }

    // ===== F5.2 — Invite flow =====

    @Test
    @DisplayName("POST /invite + GET /invites/<id> drena invites pendientes")
    void inviteFlowEndToEnd() throws Exception {
        final TrackerProtocol.Announcement a = makeValidAnnouncement();
        registerCleanup(a);
        postAnnouncement(a, 201);

        final String friendId = randomAzoreaId();
        final TrackerProtocol.InviteRequest req = new TrackerProtocol.InviteRequest(
                a.hostIdentity(), friendId, a.gameId(),
                randomEncryptedBlob(), System.currentTimeMillis(), "game");

        // Send.
        final TrackerProtocol.InviteAck ack = postInvite(req, 201);
        assertTrue(ack.queued());

        // Poll (primera vez — recibe el invite).
        final TrackerProtocol.InviteList poll1 = getInvites(friendId, 200);
        assertEquals(1, poll1.invites().size());
        assertEquals(a.hostIdentity().azoreaId(), poll1.invites().get(0).fromIdentity().azoreaId());
        assertEquals(a.gameId(), poll1.invites().get(0).gameId());
        assertEquals(req.encryptedBlobBase64(), poll1.invites().get(0).encryptedBlobBase64());

        // Poll (segunda vez — vacío, drain semantics).
        final TrackerProtocol.InviteList poll2 = getInvites(friendId, 200);
        assertTrue(poll2.invites().isEmpty(), "segundo poll debe estar vacío (drain)");
    }

    @Test
    @DisplayName("POST /invite con from que no es host devuelve 403")
    void inviteFromNonHostForbidden() throws Exception {
        final TrackerProtocol.Announcement a = makeValidAnnouncement();
        registerCleanup(a);
        postAnnouncement(a, 201);

        final TrackerProtocol.Identity notHost = new TrackerProtocol.Identity(
                randomAzoreaId(), "NotHost", randomPublicKey());
        final TrackerProtocol.InviteRequest req = new TrackerProtocol.InviteRequest(
                notHost, randomAzoreaId(), a.gameId(),
                randomEncryptedBlob(), System.currentTimeMillis(), "game");
        final HttpResponse<String> resp = postRawInvite(req);
        assertEquals(403, resp.statusCode());
        assertTrue(resp.body().contains("not_host"));
    }

    @Test
    @DisplayName("POST /invite con game inexistente devuelve 404")
    void inviteForUnknownGameReturns404() throws Exception {
        final TrackerProtocol.InviteRequest req = new TrackerProtocol.InviteRequest(
                new TrackerProtocol.Identity(randomAzoreaId(), "H", randomPublicKey()),
                randomAzoreaId(), UUID.randomUUID().toString(),
                randomEncryptedBlob(), System.currentTimeMillis(), "game");
        final HttpResponse<String> resp = postRawInvite(req);
        assertEquals(404, resp.statusCode());
    }

    @Test
    @DisplayName("GET /invites/<invalid> devuelve 400")
    void invitesForInvalidAzoreaIdReturns400() throws Exception {
        final HttpResponse<String> resp = get("/invites/INVALID");
        assertEquals(400, resp.statusCode());
    }

    // ===== F5.2 — Friend lookup =====

    @Test
    @DisplayName("GET /friend/<id> devuelve online status cuando player está en game")
    void friendLookupFindsPlayer() throws Exception {
        final TrackerProtocol.Announcement a = makeValidAnnouncement();
        registerCleanup(a);
        postAnnouncement(a, 201);

        final String playerId = randomAzoreaId();
        final TrackerProtocol.Identity player = new TrackerProtocol.Identity(
                playerId, "Friend", randomPublicKey());
        postPresence(new TrackerProtocol.PresenceUpdate(a.gameId(), player, "join",
                System.currentTimeMillis()), 200);

        final TrackerProtocol.FriendStatus status = getFriend(playerId, 200);
        assertEquals(playerId, status.azoreaId());
        assertEquals("Friend", status.displayName());
        assertEquals(player.publicKeyBase64(), status.publicKeyBase64());
        assertTrue(status.online());
        assertEquals(a.gameId(), status.currentGameId());
    }

    @Test
    @DisplayName("GET /friend/<id> encuentra al host aunque no esté como player")
    void friendLookupFindsHost() throws Exception {
        final TrackerProtocol.Announcement a = makeValidAnnouncement();
        registerCleanup(a);
        postAnnouncement(a, 201);

        final TrackerProtocol.FriendStatus status = getFriend(a.hostIdentity().azoreaId(), 200);
        assertEquals(a.hostIdentity().azoreaId(), status.azoreaId());
        assertTrue(status.online());
        assertEquals(a.gameId(), status.currentGameId());
    }

    @Test
    @DisplayName("GET /friend/<id> devuelve 404 si nunca estuvo online")
    void friendLookupForUnknownReturns404() throws Exception {
        final HttpResponse<String> resp = get("/friend/" + randomAzoreaId());
        assertEquals(404, resp.statusCode());
    }

    @Test
    @DisplayName("GET /friend/<invalid> devuelve 400")
    void friendLookupInvalidAzoreaIdReturns400() throws Exception {
        final HttpResponse<String> resp = get("/friend/INVALID");
        assertEquals(400, resp.statusCode());
    }

    // ===== Helpers HTTP =====

    private void registerCleanup(final TrackerProtocol.Announcement a) {
        cleanups.add(new String[]{a.gameId(), a.hostToken()});
    }

    private HttpResponse<String> get(final String path) throws Exception {
        final HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .GET()
                .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private TrackerProtocol.ListResponse getList(final int expectedStatus) throws Exception {
        return getListQuery("", expectedStatus);
    }

    private TrackerProtocol.ListResponse getListQuery(final String query, final int expectedStatus) throws Exception {
        final HttpResponse<String> response = get("/list" + query);
        assertEquals(expectedStatus, response.statusCode(),
                "GET /list esperaba " + expectedStatus + " body: " + response.body());
        return gson.fromJson(response.body(), TrackerProtocol.ListResponse.class);
    }

    private TrackerProtocol.AnnounceResponse postAnnouncement(
            final TrackerProtocol.Announcement a, final int expectedStatus) throws Exception {
        final HttpResponse<String> response = postRawAnnouncement(a);
        assertEquals(expectedStatus, response.statusCode(),
                "POST /announce esperaba " + expectedStatus + " body: " + response.body());
        return gson.fromJson(response.body(), TrackerProtocol.AnnounceResponse.class);
    }

    private HttpResponse<String> postRawAnnouncement(final TrackerProtocol.Announcement a) throws Exception {
        final String body = gson.toJson(a);
        final HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/announce"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> deleteAnnouncement(final String gameId, final String token) throws Exception {
        final HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/announce?game_id="
                        + URLEncoder.encode(gameId, StandardCharsets.UTF_8)
                        + "&token=" + URLEncoder.encode(token, StandardCharsets.UTF_8)))
                .DELETE()
                .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private TrackerProtocol.PresenceResponse postPresence(
            final TrackerProtocol.PresenceUpdate u, final int expectedStatus) throws Exception {
        final HttpResponse<String> resp = postRawPresence(u);
        assertEquals(expectedStatus, resp.statusCode(),
                "POST /presence esperaba " + expectedStatus + " body: " + resp.body());
        return gson.fromJson(resp.body(), TrackerProtocol.PresenceResponse.class);
    }

    private HttpResponse<String> postRawPresence(final TrackerProtocol.PresenceUpdate u) throws Exception {
        final String body = gson.toJson(u);
        final HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/presence"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private TrackerProtocol.InviteAck postInvite(
            final TrackerProtocol.InviteRequest r, final int expectedStatus) throws Exception {
        final HttpResponse<String> resp = postRawInvite(r);
        assertEquals(expectedStatus, resp.statusCode(),
                "POST /invite esperaba " + expectedStatus + " body: " + resp.body());
        return gson.fromJson(resp.body(), TrackerProtocol.InviteAck.class);
    }

    private HttpResponse<String> postRawInvite(final TrackerProtocol.InviteRequest r) throws Exception {
        final String body = gson.toJson(r);
        final HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/invite"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private TrackerProtocol.InviteList getInvites(final String azoreaId,
                                                  final int expectedStatus) throws Exception {
        final HttpResponse<String> resp = get("/invites/" + URLEncoder.encode(azoreaId, StandardCharsets.UTF_8));
        assertEquals(expectedStatus, resp.statusCode(),
                "GET /invites esperaba " + expectedStatus + " body: " + resp.body());
        return gson.fromJson(resp.body(), TrackerProtocol.InviteList.class);
    }

    private TrackerProtocol.FriendStatus getFriend(final String azoreaId,
                                                   final int expectedStatus) throws Exception {
        final HttpResponse<String> resp = get("/friend/" + URLEncoder.encode(azoreaId, StandardCharsets.UTF_8));
        assertEquals(expectedStatus, resp.statusCode(),
                "GET /friend esperaba " + expectedStatus + " body: " + resp.body());
        return gson.fromJson(resp.body(), TrackerProtocol.FriendStatus.class);
    }

    private static boolean containsGame(final TrackerProtocol.ListResponse list, final String gameId) {
        return list.games() != null && list.games().stream().anyMatch(g -> gameId.equals(g.gameId()));
    }

    private static java.util.Optional<TrackerProtocol.GameListing> findGame(
            final TrackerProtocol.ListResponse list, final String gameId) {
        return list.games() == null ? java.util.Optional.empty()
                : list.games().stream().filter(g -> gameId.equals(g.gameId())).findFirst();
    }

    // ===== Fixtures =====

    private TrackerProtocol.Announcement makeValidAnnouncement() {
        return newAnnouncement(UUID.randomUUID().toString(),
                List.of(new TrackerProtocol.ModEntry("azorea", "0.1.0", true)),
                "1.21.1");
    }

    private TrackerProtocol.Announcement newAnnouncement(final String gameId,
                                                        final List<TrackerProtocol.ModEntry> mods) {
        return newAnnouncement(gameId, mods, "1.21.1");
    }

    private TrackerProtocol.Announcement newAnnouncement(final String gameId,
                                                        final List<TrackerProtocol.ModEntry> mods,
                                                        final String mcVersion) {
        final TrackerProtocol.Identity host = new TrackerProtocol.Identity(
                randomAzoreaId(), "test-host", randomPublicKey());
        return new TrackerProtocol.Announcement(
                TrackerProtocol.AZOREA_PROTOCOL_VERSION,
                "0.1.0",
                gameId, randomToken(),
                host,
                mcVersion,
                "21.1.250",
                mods,
                8, 1, "Test world",
                "AZ-AAAAAA-BBBBBB-CCCCCC-DDDDDD",
                System.currentTimeMillis() / 1000L,
                300);
    }

    private static TrackerProtocol.Announcement withTimestamp(
            final TrackerProtocol.Announcement a, final long ts) {
        return new TrackerProtocol.Announcement(
                a.azoreaProtocol(), a.azoreaVersion(),
                a.gameId(), a.hostToken(),
                a.hostIdentity(),
                a.mcVersion(), a.neoForgeVersion(),
                a.mods(),
                a.maxPlayers(), a.currentPlayers(),
                a.worldName(),
                a.inviteCode(),
                ts, a.ttlSeconds());
    }

    private static TrackerProtocol.Announcement withHostToken(
            final TrackerProtocol.Announcement a, final String token) {
        return new TrackerProtocol.Announcement(
                a.azoreaProtocol(), a.azoreaVersion(),
                a.gameId(), token,
                a.hostIdentity(),
                a.mcVersion(), a.neoForgeVersion(),
                a.mods(),
                a.maxPlayers(), a.currentPlayers(),
                a.worldName(),
                a.inviteCode(),
                System.currentTimeMillis() / 1000L,
                a.ttlSeconds());
    }

    private static TrackerProtocol.Announcement withHostIdentity(
            final TrackerProtocol.Announcement a, final TrackerProtocol.Identity host) {
        return new TrackerProtocol.Announcement(
                a.azoreaProtocol(), a.azoreaVersion(),
                a.gameId(), a.hostToken(),
                host,
                a.mcVersion(), a.neoForgeVersion(),
                a.mods(),
                a.maxPlayers(), a.currentPlayers(),
                a.worldName(),
                a.inviteCode(),
                a.timestamp(), a.ttlSeconds());
    }

    private static TrackerProtocol.Announcement withTtl(
            final TrackerProtocol.Announcement a, final int ttl) {
        return new TrackerProtocol.Announcement(
                a.azoreaProtocol(), a.azoreaVersion(),
                a.gameId(), a.hostToken(),
                a.hostIdentity(),
                a.mcVersion(), a.neoForgeVersion(),
                a.mods(),
                a.maxPlayers(), a.currentPlayers(),
                a.worldName(),
                a.inviteCode(),
                System.currentTimeMillis() / 1000L, ttl);
    }

    private static String randomToken() {
        final byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        final StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    private static String randomAzoreaId() {
        // 4 grupos de 6 chars base32-ish = 24 chars tras prefijo "AZ-".
        final String alphabet = "0123456789ABCDEFGHJKMNPQRSTUVWXYZ";
        final StringBuilder sb = new StringBuilder("AZ-");
        for (int g = 0; g < 4; g++) {
            if (g > 0) sb.append('-');
            for (int i = 0; i < 6; i++) {
                sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
            }
        }
        return sb.toString();
    }

    private static String randomPublicKey() {
        // 44 bytes typical for X.509-encoded Ed25519 public key.
        final byte[] bytes = new byte[44];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String randomEncryptedBlob() {
        final byte[] bytes = new byte[128];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static int findFreePort() throws Exception {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }
}