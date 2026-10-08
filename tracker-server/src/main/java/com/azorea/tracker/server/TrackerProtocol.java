// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.tracker.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.security.SecureRandom;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Protocolo del tracker Azorea (ver AGENTS.md ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â§ DA-5).
 *
 * Define los tipos JSON que el mod envÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â­a y recibe del TrackerServer.
 * El TrackerServer (app standalone) implementa sus propios tipos paralelos
 * y serializa vÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â­a la misma forma JSON; este mÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â³dulo NO se comparte entre
 * procesos por simplicidad.
 *
 * ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â§ Historia de versiones:
 *   v1: Announcement + GameListing con {@code Connection} que exponÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â­a host/port del host.
 *       Esto filtraba la IP del host en cada listing pÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Âºblico.
 *   v2 (F5.2): se elimina {@code Connection} del Announcement y del GameListing pÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Âºblico.
 *       Se aÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â±ade {@link Identity} (azorea_id + display_name + public_key) que identifica al
 *       host y a cada jugador. La IP/port del host solo se entrega cifrada vÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â­a
 *       POST /invite (X25519+ChaCha20, decryptable solo por el recipient).
 *       Tracker NUNCA almacena ni transmite la IP en claro.
 *
 * ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â§ Seguridad:
 * - {@link Announcement#hostToken()} generado con {@link SecureRandom} (256 bits).
 * - {@link Announcement#gameId()} es un UUID v4 (122 bits entropÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â­a).
 * - {@link Announcement#inviteCode()} (~40 bits efectivos) complementa game_id.
 * - ValidaciÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â³n de campos (rangos, charset, formato) antes de aceptar anuncios.
 */
public final class TrackerProtocol {

    /** VersiÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â³n del protocolo. Sincronizada con el mod. */
    public static final int AZOREA_PROTOCOL_VERSION = 2;

    /** VersiÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â³n actual del mod Azorea. Sincronizada con gradle.properties. */
    public static final String AZOREA_VERSION = "0.1.0";

    /** modId del mod. Duplicado aquÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â­ porque tracker-server es standalone (no depende del mod). */
    public static final String MOD_ID = "azorea";

    /** TTL por defecto para anuncios (segundos). Refrescos deben llegar antes de expirar. */
    public static final int DEFAULT_TTL_SECONDS = 300;

    /** TTL mÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â­nimo aceptado por trackers (rechazan valores menores). */
    public static final int MIN_TTL_SECONDS = 60;

    /** TTL mÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¡ximo aceptado por trackers. */
    public static final int MAX_TTL_SECONDS = 3600;

    /** MÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¡ximo de resultados en GET /list. */
    public static final int MAX_LIST_RESULTS = 50;

    /** TTL por defecto para invites (segundos). Sweeper los purga tras esto. */
    public static final int INVITE_TTL_SECONDS = 3600;

    private TrackerProtocol() {
    }

    // ===== Identidad (F5.1) =====

    public record Identity(
            String azoreaId,
            String displayName,
            String publicKeyBase64
    ) {
        public Identity {
            Objects.requireNonNull(azoreaId, "azoreaId");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(publicKeyBase64, "publicKeyBase64");
            if (!isValidAzoreaId(azoreaId)) {
                throw new IllegalArgumentException("azoreaId invÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¡lido: " + azoreaId);
            }
            if (displayName.isBlank()) {
                throw new IllegalArgumentException("displayName vacÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â­o");
            }
        }
    }

    // ===== Anuncios y listings (v2) =====

    public record Announcement(
            int azoreaProtocol,
            String azoreaVersion,
            String gameId,
            String hostToken,
            Identity hostIdentity,
            String mcVersion,
            String neoForgeVersion,
            List<ModEntry> mods,
            int maxPlayers,
            int currentPlayers,
            String worldName,
            String inviteCode,
            long timestamp,
            int ttlSeconds
    ) {
    }

    public record ModEntry(String modid, String version, boolean required) {
    }

    public record GameListing(
            String gameId,
            Identity hostIdentity,
            String mcVersion,
            String neoForgeVersion,
            List<ModEntry> mods,
            int maxPlayers,
            int currentPlayers,
            List<Identity> currentPlayersIdentity,
            String worldName,
            String inviteCode,
            long firstSeen,
            long lastSeen,
            long expiresAt
    ) {
    }

    public record Filters(String mcVersion, String modId, int maxResults) {
        public Filters {
            if (maxResults <= 0) {
                maxResults = 20;
            }
            if (maxResults > MAX_LIST_RESULTS) {
                maxResults = MAX_LIST_RESULTS;
            }
        }
    }

    public record AnnounceResponse(long expiresAt) {
    }

    public record ListResponse(List<GameListing> games) {
    }

    public record ErrorResponse(String error, String message) {
    }

    // ===== Presence (F5.2) =====

    public record PresenceUpdate(
            String gameId,
            Identity identity,
            String op,
            long timestamp
    ) {
        public PresenceUpdate {
            Objects.requireNonNull(gameId, "gameId");
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(op, "op");
            if (!op.equals("join") && !op.equals("leave")) {
                throw new IllegalArgumentException("op debe ser 'join' o 'leave': " + op);
            }
        }
    }

    public record PresenceResponse(int currentPlayers) {
    }

    // ===== Invites (F5.2 + F7.3) =====

    /**
     * ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â§ F7.3: type field. "game" (default, invitacion a partida) o "friend" (solicitud de amistad).
     * Reconocido en el recipient vÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â­a {@link #typeOrDefault()}.
     */
    public record InviteRequest(
            Identity fromIdentity,
            String toAzoreaId,
            String gameId,
            String encryptedBlobBase64,
            long sentAt,
            String type
    ) {
        public InviteRequest {
            Objects.requireNonNull(fromIdentity, "fromIdentity");
            Objects.requireNonNull(toAzoreaId, "toAzoreaId");
            Objects.requireNonNull(gameId, "gameId");
            Objects.requireNonNull(encryptedBlobBase64, "encryptedBlobBase64");
            if (!isValidAzoreaId(toAzoreaId)) {
                throw new IllegalArgumentException("toAzoreaId invÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¡lido: " + toAzoreaId);
            }
        }

        /** Devuelve el type o "game" por defecto. */
        public String typeOrDefault() {
            return type == null ? "game" : type;
        }
    }

    public record Invite(
            Identity fromIdentity,
            String gameId,
            String encryptedBlobBase64,
            long sentAt
    ) {
    }

    public record InviteList(List<Invite> invites) {
    }

    public record InviteAck(boolean queued, long sentAt) {
    }

    /** Respuesta de POST /relay/session (F6.2). */
    public record RelaySessionResponse(String sessionId, int relayPort) {
    }

    // ===== Friend lookup (F5.2) =====

    public record FriendStatus(
            String azoreaId,
            String displayName,
            String publicKeyBase64,
            boolean online,
            String currentGameId,
            long lastSeen
    ) {
    }

    // ===== JSON =====

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .create();

    public static Gson gson() {
        return GSON;
    }

    // ===== GeneraciÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â³n de identificadores =====

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public static String newGameId() {
        return UUID.randomUUID().toString();
    }

    public static String newHostToken() {
        final byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return toHex(bytes);
    }

    public static String newInviteCode() {
        final byte[] bytes = new byte[4];
        SECURE_RANDOM.nextBytes(bytes);
        int n = ((bytes[0] & 0xFF) << 24) | ((bytes[1] & 0xFF) << 16)
                | ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
        return String.format("AZ-%s-%s-%s-%s",
                encodeBase32(n >>> 20 & 0x3FF),
                encodeBase32(n >>> 10 & 0x3FF),
                encodeBase32(n & 0x3FF),
                encodeBase32(SECURE_RANDOM.nextInt() & 0x3FF));
    }

    private static String encodeBase32(final int n) {
        final char[] alphabet = "0123456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();
        final StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(alphabet[(n >> (i * 5)) & 0x1F]);
        }
        return sb.toString();
    }

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    private static String toHex(final byte[] bytes) {
        final char[] hex = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            final int v = bytes[i] & 0xFF;
            hex[i * 2] = HEX_CHARS[v >>> 4];
            hex[i * 2 + 1] = HEX_CHARS[v & 0x0F];
        }
        return new String(hex);
    }

    // ===== ValidaciÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â³n =====

    public static void validate(final Announcement a) {
        Objects.requireNonNull(a, "announcement");
        if (a.azoreaProtocol() != AZOREA_PROTOCOL_VERSION) {
            throw new IllegalArgumentException("azoreaProtocol esperado " + AZOREA_PROTOCOL_VERSION
                    + ", recibido " + a.azoreaProtocol());
        }
        if (!isValidGameId(a.gameId())) {
            throw new IllegalArgumentException("gameId invÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¡lido (UUID esperado): " + a.gameId());
        }
        if (!isValidHostToken(a.hostToken())) {
            throw new IllegalArgumentException("hostToken invÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¡lido (64 hex chars esperados)");
        }
        Objects.requireNonNull(a.hostIdentity(), "hostIdentity");
        if (!isValidAzoreaId(a.hostIdentity().azoreaId())) {
            throw new IllegalArgumentException("hostIdentity.azoraId invÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¡lido");
        }
        if (!isValidMcVersion(a.mcVersion())) {
            throw new IllegalArgumentException("mcVersion invÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¡lida: " + a.mcVersion());
        }
        if (a.mods() == null || a.mods().isEmpty()) {
            throw new IllegalArgumentException("mods no puede estar vacÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â­o");
        }
        if (a.maxPlayers() < 1 || a.maxPlayers() > 1000) {
            throw new IllegalArgumentException("maxPlayers fuera de rango [1, 1000]: " + a.maxPlayers());
        }
        if (a.currentPlayers() < 0 || a.currentPlayers() > a.maxPlayers()) {
            throw new IllegalArgumentException("currentPlayers fuera de rango [0, maxPlayers]: "
                    + a.currentPlayers());
        }
        if (a.ttlSeconds() < MIN_TTL_SECONDS || a.ttlSeconds() > MAX_TTL_SECONDS) {
            throw new IllegalArgumentException("ttlSeconds fuera de rango ["
                    + MIN_TTL_SECONDS + ", " + MAX_TTL_SECONDS + "]: " + a.ttlSeconds());
        }
        if (!isValidInviteCode(a.inviteCode())) {
            throw new IllegalArgumentException("inviteCode invÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¡lido: " + a.inviteCode());
        }
        final boolean hasAzorea = a.mods().stream()
                .anyMatch(m -> MOD_ID.equals(m.modid()) && m.required());
        if (!hasAzorea) {
            throw new IllegalArgumentException("mods debe incluir " + MOD_ID
                    + " como required");
        }
    }

    public static boolean isValidGameId(final String s) {
        if (s == null) return false;
        try {
            final UUID parsed = UUID.fromString(s);
            return parsed.version() == 4;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static boolean isValidHostToken(final String s) {
        return s != null && s.length() == 64 && s.matches("[0-9a-f]+");
    }

    public static boolean isValidMcVersion(final String s) {
        return s != null && s.matches("\\d+\\.\\d+(\\.\\d+)?");
    }

    public static boolean isValidInviteCode(final String s) {
        if (s == null) return false;
        return s.matches("AZ-[0-9A-HJ-NP-Z]{6}-[0-9A-HJ-NP-Z]{6}-[0-9A-HJ-NP-Z]{6}-[0-9A-HJ-NP-Z]{6}")
                || s.matches("AZ-[0-9A-HJ-NP-Z]{24}");
    }

    public static boolean isValidAzoreaId(final String s) {
        return isValidInviteCode(s);
    }
}