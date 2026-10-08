// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.tracker;

import com.azorea.mod.AzoreaConstants;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Protocolo del tracker Azorea — copia del lado mod (ver AGENTS.md § DA-5 + F5.2).
 *
 * <p>Mirror de {@code tracker-server/src/.../TrackerProtocol.java}. Mantener ambos sincronizados.
 * Diferencias con el tracker-server: usa {@link AzoreaConstants#MOD_ID} (módulo shared) en lugar
 * de una constante local.
 *
 * <p><b>v2 (F5.2):</b> se elimina {@code Connection} del Announcement y del GameListing público.
 * La connection info del host se entrega SOLO en encrypted blobs vía POST /invite. El tracker
 * nunca almacena ni transmite la IP en claro.
 *
 * <p>Ver docs de cada record para detalles.
 */
public final class TrackerProtocol {

    /** Versión del protocolo. Sincronizada con tracker-server. Incrementar si cambia el schema. */
    public static final int AZOREA_PROTOCOL_VERSION = 2;

    /** Versión actual del mod Azorea. Sincronizada con gradle.properties. */
    public static final String AZOREA_VERSION = "0.1.0";

    /** TTL por defecto para anuncios (segundos). */
    public static final int DEFAULT_TTL_SECONDS = 300;

    /** TTL mínimo aceptado por trackers. */
    public static final int MIN_TTL_SECONDS = 60;

    /** TTL máximo aceptado por trackers. */
    public static final int MAX_TTL_SECONDS = 3600;

    /** Máximo de resultados en GET /list. */
    public static final int MAX_LIST_RESULTS = 50;

    /** TTL por defecto para invites (segundos). */
    public static final int INVITE_TTL_SECONDS = 3600;

    private TrackerProtocol() {
    }

    // ===== Identidad (F5.1) =====

    /**
     * Identidad Azorea de un peer (host o jugador).
     *
     * <p>El tracker usa {@code azoreaId} como identificador estable; {@code publicKeyBase64}
     * permite a los recipients verificar firmas Ed25519 y derivar claves X25519 para
     * cifrar blobs en POST /invite.
     */
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
                throw new IllegalArgumentException("azoreaId inválido: " + azoreaId);
            }
            if (displayName.isBlank()) {
                throw new IllegalArgumentException("displayName vacío");
            }
        }
    }

    // ===== Anuncios y listings (v2) =====

    /**
     * Anuncio enviado por el host al tracker (v2). NO incluye IP/port.
     *
     * <p>§ F9 (DA-8): los 3 últimos campos prueban IDENTIDAD:
     * <ul>
     *   <li>{@code signingKey} — Ed25519 pública (X.509, base64). Es la clave
     *       <b>atada al azorea_id</b> vía {@link com.azorea.mod.v1211.identity.AzoreaId}.</li>
     *   <li>{@code hwCommit} — SHA-256(canonical(hw)) en base64; componente del ID.</li>
     *   <li>{@code signature} — Ed25519 sobre {@link #canonicalForSigning}.</li>
     * </ul>
     * El tracker verifica: (1) que el ID reclamado se re-deriva de las claves + hwCommit,
     * y (2) que la firma es válida sobre el contenido. Sin ambos → 403.
     *
     * <p>Backward-compat: el constructor de 14 params (sin firma) sigue existiendo
     * y deja los campos de firma a null — útil para tests y para trackers que aún
     * no verifican. Un anuncio con firma null es rechazado por
     * {@link #verifyAnnounce}.
     */
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
            int ttlSeconds,
            String signingKey,
            String hwCommit,
            String signature
    ) {
        /** Backward-compat: anuncio sin campos de firma (firma = null). */
        public Announcement(final int azoreaProtocol, final String azoreaVersion,
                            final String gameId, final String hostToken,
                            final Identity hostIdentity, final String mcVersion,
                            final String neoForgeVersion, final List<ModEntry> mods,
                            final int maxPlayers, final int currentPlayers,
                            final String worldName, final String inviteCode,
                            final long timestamp, final int ttlSeconds) {
            this(azoreaProtocol, azoreaVersion, gameId, hostToken, hostIdentity,
                    mcVersion, neoForgeVersion, mods, maxPlayers, currentPlayers,
                    worldName, inviteCode, timestamp, ttlSeconds, null, null, null);
        }

        /** Anuncio con firma: devuelve una copia con los 3 campos de identidad. */
        public Announcement withSignature(final String signingKey, final String hwCommit,
                                          final String signature) {
            return new Announcement(azoreaProtocol, azoreaVersion, gameId, hostToken,
                    hostIdentity, mcVersion, neoForgeVersion, mods, maxPlayers,
                    currentPlayers, worldName, inviteCode, timestamp, ttlSeconds,
                    signingKey, hwCommit, signature);
        }
    }

    /** Entrada de mod en el anuncio. */
    public record ModEntry(String modid, String version, boolean required) {
    }

    /**
     * Listing devuelto por GET /list (v2). NO incluye IP/port.
     */
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

    /** Filtros para GET /list. */
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

    /** Respuesta de POST /announce. */
    public record AnnounceResponse(long expiresAt) {
    }

    /** Respuesta de GET /list. */
    public record ListResponse(List<GameListing> games) {
    }

    /** Respuesta de error estándar. */
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

    // ===== Invites (F5.2) =====

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
                throw new IllegalArgumentException("toAzoreaId inválido: " + toAzoreaId);
            }
        }

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

    // ===== Generación de identificadores =====

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** Genera un game_id (UUID v4). */
    public static String newGameId() {
        return UUID.randomUUID().toString();
    }

    /** Genera un host_token (256 bits, hex-encoded = 64 chars). */
    public static String newHostToken() {
        final byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return toHex(bytes);
    }

    /** Genera un invite_code (formato AZ-XXXXXX-XXXXXX-XXXXXX-XXXXXX). */
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

    // ===== Validación =====

    public static void validate(final Announcement a) {
        Objects.requireNonNull(a, "announcement");
        if (a.azoreaProtocol() != AZOREA_PROTOCOL_VERSION) {
            throw new IllegalArgumentException("azoreaProtocol esperado " + AZOREA_PROTOCOL_VERSION
                    + ", recibido " + a.azoreaProtocol());
        }
        if (!isValidGameId(a.gameId())) {
            throw new IllegalArgumentException("gameId inválido (UUID esperado): " + a.gameId());
        }
        if (!isValidHostToken(a.hostToken())) {
            throw new IllegalArgumentException("hostToken inválido (64 hex chars esperados)");
        }
        Objects.requireNonNull(a.hostIdentity(), "hostIdentity");
        if (!isValidAzoreaId(a.hostIdentity().azoreaId())) {
            throw new IllegalArgumentException("hostIdentity.azoreaId inválido");
        }
        if (!isValidMcVersion(a.mcVersion())) {
            throw new IllegalArgumentException("mcVersion inválida: " + a.mcVersion());
        }
        if (a.mods() == null || a.mods().isEmpty()) {
            throw new IllegalArgumentException("mods no puede estar vacío");
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
            throw new IllegalArgumentException("inviteCode inválido: " + a.inviteCode());
        }
        // El mod Azorea debe estar presente y ser required.
        final boolean hasAzorea = a.mods().stream()
                .anyMatch(m -> AzoreaConstants.MOD_ID.equals(m.modid()) && m.required());
        if (!hasAzorea) {
            throw new IllegalArgumentException("mods debe incluir " + AzoreaConstants.MOD_ID
                    + " como required");
        }
    }

    // ===== § F9: Firma de anuncios (DA-8) =====

    /**
     * Serialización canónica del anuncio para FIRMAR/VERIFICAR.
     *
     * <p>NO incluye {@code signature} (evita circularidad) — todos los demás campos
     * sí, de modo que un atacante no puede alterar world/players/inviteCode/etc.
     * sin romper la firma.
     *
     * <p>Determinista: orden de campos fijo y {@code mods} ordenados por
     * {@code modid@version@required}, así emisor y receptor calculan bytes idénticos
     * sin depender del orden del JSON ni del serializador.
     *
     * <p>Versionado con prefijo {@code azorea-announce/v1}: si cambia el conjunto de
     * campos firmados, se bump a v2 para que los anuncios viejos fallen en vez de
     * verificar mal.
     */
    public static String canonicalForSigning(final Announcement a) {
        Objects.requireNonNull(a, "announcement");
        Objects.requireNonNull(a.hostIdentity(), "hostIdentity");
        final StringBuilder sb = new StringBuilder(512);
        sb.append("azorea-announce/v1\n");
        sb.append("protocol=").append(a.azoreaProtocol()).append('\n');
        sb.append("version=").append(a.azoreaVersion()).append('\n');
        sb.append("game_id=").append(a.gameId()).append('\n');
        sb.append("host_token=").append(a.hostToken()).append('\n');
        sb.append("azorea_id=").append(a.hostIdentity().azoreaId()).append('\n');
        sb.append("display_name=").append(a.hostIdentity().displayName()).append('\n');
        sb.append("x25519_key=").append(a.hostIdentity().publicKeyBase64()).append('\n');
        sb.append("signing_key=").append(a.signingKey()).append('\n');
        sb.append("hw_commit=").append(a.hwCommit()).append('\n');
        sb.append("mc=").append(a.mcVersion()).append('\n');
        sb.append("neoforge=").append(a.neoForgeVersion()).append('\n');
        sb.append("max_players=").append(a.maxPlayers()).append('\n');
        sb.append("players=").append(a.currentPlayers()).append('\n');
        sb.append("world=").append(a.worldName()).append('\n');
        sb.append("invite=").append(a.inviteCode()).append('\n');
        sb.append("timestamp=").append(a.timestamp()).append('\n');
        sb.append("ttl=").append(a.ttlSeconds()).append('\n');
        if (a.mods() != null) {
            a.mods().stream()
                    .map(m -> m.modid() + "@" + m.version() + "@" + m.required())
                    .sorted()
                    .forEach(m -> sb.append("mod=").append(m).append('\n'));
        }
        return sb.toString();
    }

    /**
     * Verifica la IDENTIDAD de un anuncio (§ F9 / DA-8).
     *
     * <p>Dos comprobaciones, ambas necesarias:
     * <ol>
     *   <li><b>Auto-certificación</b>: el {@code azorea_id} reclamado se re-deriva a
     *       partir de la Ed25519 publicada + la X25519 publicada + el hwCommit.
     *       Un atacante no puede reclamar tu ID sin tus claves.</li>
     *   <li><b>Firma</b>: la Ed25519 firma el contenido canónico del anuncio.
     *       Cubre todos los campos → no se puede alterar nada sin romperla.</li>
     * </ol>
     *
     * @throws IllegalArgumentException con la razón concreta si la verificación falla
     */
    public static void verifyAnnounce(final Announcement a) {
        Objects.requireNonNull(a, "announcement");
        final java.util.List<String> missing = new java.util.ArrayList<>(3);
        if (a.signingKey() == null || a.signingKey().isBlank()) missing.add("signingKey");
        if (a.hwCommit() == null || a.hwCommit().isBlank()) missing.add("hwCommit");
        if (a.signature() == null || a.signature().isBlank()) missing.add("signature");
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("anuncio sin campos F9 requeridos: "
                    + String.join(", ", missing));
        }

        final byte[] edPub;
        final byte[] xPub;
        final byte[] hwCommit;
        final byte[] signature;
        try {
            edPub = Base64.getDecoder().decode(a.signingKey());
            xPub = Base64.getDecoder().decode(a.hostIdentity().publicKeyBase64());
            hwCommit = Base64.getDecoder().decode(a.hwCommit());
            signature = Base64.getDecoder().decode(a.signature());
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException("campos de firma no son base64 válido: "
                    + e.getMessage());
        }

        // (1) El ID reclamado debe derivarse de estas claves + hwCommit.
        if (!com.azorea.mod.v1211.identity.AzoreaId.verify(
                a.hostIdentity().azoreaId(), edPub, xPub, hwCommit)) {
            throw new IllegalArgumentException("azorea_id NO corresponde a las claves publicadas"
                    + " (suplantación de identidad)");
        }

        // (2) La firma Ed25519 debe ser válida sobre el contenido canónico.
        final byte[] message = canonicalForSigning(a).getBytes(StandardCharsets.UTF_8);
        if (!com.azorea.mod.v1211.identity.AzoreaIdentity.verifySignature(edPub, message, signature)) {
            throw new IllegalArgumentException("firma Ed25519 inválida sobre el anuncio");
        }
    }

    /**
     * § F9: firma un anuncio con la Ed25519 del host y devuelve la copia sellada.
     *
     * <p>Lado emisor. El receptor (tracker) llama a {@link #verifyAnnounce}.
     * La clave pública se pasa explícita porque Java no la deriva desde la privada
     * de forma portátil.
     *
     * @param a           anuncio sin firmar
     * @param ed25519Pub  clave pública Ed25519 (X.509) del host — se publica
     * @param ed25519Priv clave privada Ed25519 (PKCS8) del host — firma
     * @param hwCommit    SHA-256(canonical(hw)) del host — se publica
     * @return anuncio con {@code signingKey}, {@code hwCommit} y {@code signature} rellenos
     * @throws IllegalArgumentException si la firma falla
     */
    public static Announcement signAnnounce(final Announcement a,
                                            final byte[] ed25519Pub,
                                            final byte[] ed25519Priv,
                                            final byte[] hwCommit) {
        Objects.requireNonNull(a, "announcement");
        Objects.requireNonNull(ed25519Pub, "ed25519Pub");
        Objects.requireNonNull(ed25519Priv, "ed25519Priv");
        Objects.requireNonNull(hwCommit, "hwCommit");
        try {
            final String signingKeyB64 = Base64.getEncoder().encodeToString(ed25519Pub);
            final String hwCommitB64 = Base64.getEncoder().encodeToString(hwCommit);

            // § F9: DOS pasadas — primero se rellenan signingKey + hwCommit y DESPUÉS
            // se firma. Si se firmara antes, el canónico tendría esos campos a null y
            // la verificación (que los lee rellenos) no cuadraría jamás.
            // La firma (campo 3) queda excluida del canónico, así que no hay ciclicidad.
            final Announcement withKeys = a.withSignature(signingKeyB64, hwCommitB64, null);

            final byte[] message = canonicalForSigning(withKeys).getBytes(StandardCharsets.UTF_8);
            final byte[] signature = com.azorea.mod.v1211.identity.AzoreaIdentity
                    .sign(ed25519Priv, message);

            return withKeys.withSignature(signingKeyB64, hwCommitB64,
                    Base64.getEncoder().encodeToString(signature));
        } catch (final Exception e) {
            throw new IllegalArgumentException("firma del anuncio falló: " + e.getMessage(), e);
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
        // § F5.2 fix: aceptar ambos formatos para backward compat con identity.json
        // generados antes del fix (sin dashes entre grupos). Formato canónico:
        //   AZ-XXXXXX-XXXXXX-XXXXXX-XXXXXX (4 grupos de 6 chars con dashes)
        // Formato legacy (F5.1 bug — ya no se genera, pero identity.json existente):
        //   AZ-XXXXXXXXXXXXXXXXXXXXXXXX (24 chars continuos)
        return s.matches("AZ-[0-9A-HJ-NP-Z]{6}-[0-9A-HJ-NP-Z]{6}-[0-9A-HJ-NP-Z]{6}-[0-9A-HJ-NP-Z]{6}")
                || s.matches("AZ-[0-9A-HJ-NP-Z]{24}");
    }

    public static boolean isValidAzoreaId(final String s) {
        return isValidInviteCode(s); // mismo formato
    }
}