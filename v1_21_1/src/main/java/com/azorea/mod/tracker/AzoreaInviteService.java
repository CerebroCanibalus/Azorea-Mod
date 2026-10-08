// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.tracker;

import com.azorea.mod.v1211.identity.AzoreaCrypto;
import com.azorea.mod.v1211.identity.AzoreaIdentity;
import com.azorea.mod.v1211.identity.AzoreaIdentityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/**
 * Servicio de invites (F5.2b + F7.3).
 *
 * <p><b>§ Modelo criptográfico:</b>
 * <pre>{@code
 * HOST (envía invite):
 *   1. plaintext = JSON {host, port, host_token, timestamp} (connection info)
 *   2. encryptedBlob = AzoreaCrypto.encryptForRecipient(friend.publicKey, plaintext)
 *      (X25519 ephemeral ECDH + ChaCha20-Poly1305 AEAD)
 *   3. inviteRequest = InviteRequest{fromIdentity, toAzoreaId, gameId, encryptedBlobBase64}
 *   4. POST /invite → tracker queues for recipient
 *
 * RECIPIENT (recibe invite):
 *   1. GET /invites/<myAzoreaId> → poll pending invites (drain)
 *   2. plaintext = AzoreaCrypto.decryptFromHost(myPrivateKey, encryptedBlob)
 *   3. extract host, port, host_token from plaintext
 *   4. Connect via vanilla Minecraft protocol (F3.5 join logic)
 * }</pre>
 *
 * <p>§ F7.3 friend requests: usa type="friend" + gameId="friend_request" como sentinel.
 */
public final class AzoreaInviteService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzoreaInviteService.class);

    private final AzoreaTrackerClient client;
    private final AzoreaIdentityService identityService;

    public AzoreaInviteService(final AzoreaTrackerClient client,
                               final AzoreaIdentityService identityService) {
        this.client = Objects.requireNonNull(client, "client");
        this.identityService = Objects.requireNonNull(identityService, "identityService");
    }

    // ===== ConnectionInfo (lo que viaja cifrado) =====

    /**
     * Estructura de datos que viaja cifrada dentro del encrypted_blob.
     */
    public record ConnectionInfo(String host, int port, String hostToken, long timestamp,
                                 String relaySessionId, int relayPort) {
        /** Backward-compat constructor (sin relay info). */
        public ConnectionInfo(final String host, final int port, final String hostToken,
                              final long timestamp) {
            this(host, port, hostToken, timestamp, null, 0);
        }
    }

    // ===== Sender (host) =====

    public Optional<TrackerProtocol.InviteAck> sendInvite(
            final TrackerProtocol.Identity fromIdentity,
            final String toAzoreaId,
            final String gameId,
            final byte[] friendPublicKeyEncoded,
            final ConnectionInfo connectionInfo) {
        Objects.requireNonNull(fromIdentity, "fromIdentity");
        Objects.requireNonNull(toAzoreaId, "toAzoreaId");
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(friendPublicKeyEncoded, "friendPublicKeyEncoded");
        Objects.requireNonNull(connectionInfo, "connectionInfo");

        if (!TrackerProtocol.isValidAzoreaId(toAzoreaId)) {
            throw new IllegalArgumentException("toAzoreaId inválido");
        }
        if (!TrackerProtocol.isValidGameId(gameId)) {
            throw new IllegalArgumentException("gameId inválido");
        }

        final String plaintextJson = connectionInfoToJson(connectionInfo);
        final byte[] plaintext = plaintextJson.getBytes(StandardCharsets.UTF_8);
        final byte[] encryptedBlob;
        try {
            encryptedBlob = AzoreaCrypto.encryptForRecipient(friendPublicKeyEncoded, plaintext);
        } catch (GeneralSecurityException e) {
            LOGGER.error("Error cifrando invite para {}: {}", toAzoreaId, e.getMessage(), e);
            return Optional.empty();
        }
        final String encryptedBlobBase64 = Base64.getEncoder().encodeToString(encryptedBlob);

        final TrackerProtocol.InviteRequest req = new TrackerProtocol.InviteRequest(
                fromIdentity, toAzoreaId, gameId, encryptedBlobBase64,
                System.currentTimeMillis(), "game");

        LOGGER.info("Enviando invite cifrado a {} (game={}, bytes={})",
                toAzoreaId, gameId, encryptedBlob.length);
        return client.sendInvite(req);
    }

    /**
     * § F7.3: envía una solicitud de amistad al recipient. Reutiliza el invite flow
     * con {@code type="friend"} + gameId="friend_request" como sentinel.
     */
    public Optional<TrackerProtocol.InviteAck> sendFriendRequest(
            final TrackerProtocol.Identity fromIdentity,
            final String toAzoreaId,
            final byte[] recipientPublicKeyEncoded) {
        Objects.requireNonNull(fromIdentity, "fromIdentity");
        Objects.requireNonNull(toAzoreaId, "toAzoreaId");
        Objects.requireNonNull(recipientPublicKeyEncoded, "recipientPublicKeyEncoded");
        if (!TrackerProtocol.isValidAzoreaId(toAzoreaId)) {
            throw new IllegalArgumentException("toAzoreaId inválido");
        }
        // Cifra blob trivial (el friend request es metadata-only).
        final byte[] plaintext = "{\"friend_request\":true}".getBytes(StandardCharsets.UTF_8);
        final byte[] encryptedBlob;
        try {
            encryptedBlob = AzoreaCrypto.encryptForRecipient(recipientPublicKeyEncoded, plaintext);
        } catch (GeneralSecurityException e) {
            LOGGER.error("Error cifrando friend request para {}: {}", toAzoreaId, e.getMessage());
            return Optional.empty();
        }
        final String encryptedBlobBase64 = Base64.getEncoder().encodeToString(encryptedBlob);
        final TrackerProtocol.InviteRequest req = new TrackerProtocol.InviteRequest(
                fromIdentity, toAzoreaId, "friend_request", encryptedBlobBase64,
                System.currentTimeMillis(), "friend");

        LOGGER.info("Enviando friend request a {} (encrypted_blob={}B)",
                toAzoreaId, encryptedBlob.length);
        return client.sendInvite(req);
    }

    // ===== Receiver (friend) =====

    public java.util.List<DecryptedInvite> receiveInvites() {
        final AzoreaIdentity identity = identityService.getIdentity();
        if (identity == null) {
            LOGGER.warn("No se pueden recibir invites: identity no inicializada");
            return java.util.List.of();
        }
        final byte[] myPrivateKey = identityService.x25519PrivateKey();
        final Optional<TrackerProtocol.InviteList> response = client.pollInvites(identity.azoreaId());
        if (response.isEmpty()) {
            return java.util.List.of();
        }
        final java.util.List<DecryptedInvite> result = new java.util.ArrayList<>();
        for (final TrackerProtocol.Invite invite : response.get().invites()) {
            try {
                final byte[] encrypted = Base64.getDecoder().decode(invite.encryptedBlobBase64());
                final byte[] plaintext = AzoreaCrypto.decryptFromHost(myPrivateKey, encrypted);
                // Friend requests: blob es `{"friend_request":true}` — no ConnectionInfo.
                if (invite.gameId().equals("friend_request")) {
                    result.add(new DecryptedInvite(invite, null));
                    continue;
                }
                final String json = new String(plaintext, StandardCharsets.UTF_8);
                final ConnectionInfo info = connectionInfoFromJson(json);
                result.add(new DecryptedInvite(invite, info));
                LOGGER.info("Invite descifrado de {} → game={} host={}:{}",
                        invite.fromIdentity().azoreaId(), invite.gameId(),
                        info.host(), info.port());
            } catch (GeneralSecurityException e) {
                LOGGER.warn("No se pudo descifrar invite de {}: {}",
                        invite.fromIdentity().azoreaId(), e.getMessage());
            }
        }
        return result;
    }

    /** Invite descifrado listo para usar. */
    public record DecryptedInvite(TrackerProtocol.Invite raw, ConnectionInfo info) {

        /** § F7.3: detecta friend requests (gameId = "friend_request"). */
        public boolean isFriendRequest() {
            return "friend_request".equals(raw.gameId());
        }
    }

    // ===== Serialización simple (manual, sin Gson) =====

    private static String connectionInfoToJson(final ConnectionInfo info) {
        String json = "{\"host\":\"" + info.host() + "\","
                + "\"port\":" + info.port() + ","
                + "\"host_token\":\"" + info.hostToken() + "\","
                + "\"timestamp\":" + info.timestamp();
        // § F8.x: relay info opcional (para relay fallback).
        if (info.relaySessionId() != null && !info.relaySessionId().isBlank()) {
            json += ",\"relay_session\":\"" + info.relaySessionId() + "\""
                    + ",\"relay_port\":" + info.relayPort();
        }
        return json + "}";
    }

    private static ConnectionInfo connectionInfoFromJson(final String json) {
        String host = null;
        int port = 0;
        String hostToken = null;
        long timestamp = 0L;
        String relaySessionId = null;
        int relayPort = 0;
        for (final String part : json.substring(1, json.length() - 1).split(",")) {
            final int colon = part.indexOf(':');
            if (colon < 0) continue;
            final String key = part.substring(0, colon).replace("\"", "").trim();
            final String value = part.substring(colon + 1).trim();
            switch (key) {
                case "host" -> host = value.replace("\"", "");
                case "port" -> port = Integer.parseInt(value);
                case "host_token" -> hostToken = value.replace("\"", "");
                case "timestamp" -> timestamp = Long.parseLong(value);
                case "relay_session" -> relaySessionId = value.replace("\"", "");
                case "relay_port" -> relayPort = Integer.parseInt(value);
                default -> {}
            }
        }
        return new ConnectionInfo(host, port, hostToken, timestamp, relaySessionId, relayPort);
    }
}