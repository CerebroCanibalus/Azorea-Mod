// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client.friends;

import com.azorea.mod.tracker.AzoreaInviteService;
import com.azorea.mod.tracker.TrackerProtocol;
import com.azorea.mod.v1211.AzoreaMod;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Servicio de friend list v2 (ver AGENTS.md § F4.5 + F5.2).
 *
 * <p>v2: añade soporte para azorea_id + lookup online via tracker + sendInvite.
 *
 * <p>§ Storage: JSON en {@code <gameDir>/azorea/friends.json}.
 * Formato v2:
 * <pre>{@code
 * { "friends": [
 *     {"azorea_id": "AZ-...", "display_name": "...", "public_key_base64": "...",
 *      "added_at": 12345, "last_seen": 0, "online": false},
 *     ...
 * ]}
 * }</pre>
 *
 * <p>§ Lookup online: usa {@code AzoreaTrackerClient.lookupFriend(azoreaId)}.
 * § Send invite: usa {@code AzoreaInviteService.sendInvite(...)}, requiere
 * que el friend tenga publicKey (lookup hace fetch).
 */
public final class AzoreaFriendsService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzoreaFriendsService.class);
    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<FriendDto>>() {}.getType();

    private final Path storageFile;
    private final List<AzoreaFriend> friends;

    public AzoreaFriendsService(final java.io.File gameDir) {
        this.storageFile = gameDir.toPath().resolve("azorea/friends.json");
        this.friends = load();
    }

    public AzoreaFriendsService(final Path storageFile) {
        this.storageFile = storageFile;
        this.friends = load();
    }

    public List<AzoreaFriend> getAll() {
        return new ArrayList<>(friends);
    }

    /**
     * Añade friend por azorea_id + displayName + publicKey (F5.2 v2).
     *
     * <p>§ FIX sync 2026-10-01: si el friend YA existe devolvíamos {@code false} sin
     * tocar nada, y eso dejaba los campos sin rellenar <b>para siempre</b>:
     * <ul>
     *   <li>El flujo <b>offline</b> añade con {@code publicKey = null} (placeholder
     *       "AZ-1234…") — ver {@code AzoreaFriendListScreen.onAddClicked} cuando
     *       {@code lookupFriend} falla. Después, cuando llega la clave real vía
     *       lookup o vía <i>invite bundle</i>, {@code addByAzoreaId} veía que ya
     *       existía y la descartaba ⇒ {@link #sendInvite} fallaba <b>para siempre</b>
     *       con "sin publicKey".</li>
     *   <li>El nombre placeholder nunca se sustituía por el real.</li>
     * </ul>
     * Ahora se completa lo que falte (clave, id, nombre placeholder) manteniendo
     * la firma y el contrato de retorno intactos.
     *
     * @return {@code true} si se creó un friend <b>nuevo</b>; {@code false} si ya
     *         existía (aunque se haya completado) — la UI muestra "Already in list.",
     *         que sigue siendo verdad.
     */
    public boolean addByAzoreaId(final String azoreaId, final String displayName, final String publicKeyBase64) {
        if (azoreaId == null || azoreaId.isBlank()) return false;
        if (displayName == null || displayName.isBlank()) return false;

        // § Buscar primero por id; si no, por nombre (mismo criterio que exists()).
        final Optional<AzoreaFriend> byId = findByAzoreaId(azoreaId);
        final Optional<AzoreaFriend> match = byId.isPresent() ? byId : findByDisplayName(displayName);
        if (match.isPresent()) {
            backfill(match.get(), azoreaId, displayName, publicKeyBase64);
            return false;
        }
        friends.add(AzoreaFriend.now(azoreaId, displayName, publicKeyBase64));
        save();
        LOGGER.info("Friend añadido por azorea_id: {} ({})", displayName, azoreaId);
        return true;
    }

    /**
     * Completa campos vacíos o placeholder de un friend ya existente.
     * No toca nada que ya tenga valor real — nunca sobreescribe información buena.
     */
    private void backfill(final AzoreaFriend old, final String azoreaId,
                          final String displayName, final String publicKeyBase64) {
        AzoreaFriend upd = old;
        boolean changed = false;

        // 1) Clave pública: solo si la actual está vacía.
        //    § CRÍTICO: es la X25519 (X.509) — AzoreaInviteService hace
        //    encryptForRecipient() con ECDH. Meter aquí la Ed25519 rompería sendInvite.
        if (isBlank(old.publicKeyBase64()) && !isBlank(publicKeyBase64)) {
            upd = upd.withPublicKey(publicKeyBase64);
            changed = true;
        }

        // 2) azorea_id: friend legacy (añadido solo por MC username) que ahora sabemos.
        if (isBlank(old.azoreaId())) {
            upd = new AzoreaFriend(azoreaId, upd.displayName(), upd.publicKeyBase64(),
                    upd.addedAt(), upd.lastSeen(), upd.online());
            changed = true;
        } else if (azoreaId.equals(old.azoreaId())
                && isPlaceholder(old.azoreaId(), old.displayName())
                && !isPlaceholder(azoreaId, displayName)
                && !displayName.equals(old.displayName())) {
            // 3) Nombre placeholder → nombre real (solo si coincide el id: el
            //    placeholder se generó a partir del MISMO id, ver FriendListScreen).
            upd = new AzoreaFriend(upd.azoreaId(), displayName, upd.publicKeyBase64(),
                    upd.addedAt(), upd.lastSeen(), upd.online());
            changed = true;
        }

        if (!changed) return;
        final int idx = friends.indexOf(old);   // record ⇒ equals por componentes
        if (idx >= 0) friends.set(idx, upd);
        save();
        LOGGER.info("Friend sincronizado: {} ({}) clave={} nombre placeholder={}",
                upd.displayName(), upd.azoreaId(),
                upd.publicKeyBase64() != null,
                isPlaceholder(upd.azoreaId(), upd.displayName()));
    }

    /**
     * ¿El nombre es el placeholder que genera la UI al no poder hacer lookup?
     * {@code AzoreaFriendListScreen} construye {@code input.substring(0,8) + "..."}
     * con {@code input} = el azorea_id, así que la comprobación es determinista.
     */
    private static boolean isPlaceholder(final String azoreaId, final String displayName) {
        if (azoreaId == null || displayName == null) return false;
        final int n = Math.min(8, azoreaId.length());
        return displayName.equals(azoreaId.substring(0, n) + "...");
    }

    private static boolean isBlank(final String s) {
        return s == null || s.isBlank();
    }

    /**
     * Añade friend solo por displayName (legacy v1 — sin azorea_id).
     * Útil cuando el user solo conoce el MC username.
     */
    public boolean addByDisplayName(final String displayName) {
        if (displayName == null || displayName.isBlank()) return false;
        if (exists(null, displayName)) return false;
        friends.add(AzoreaFriend.legacy(displayName));
        save();
        LOGGER.info("Friend añadido por display_name (legacy): {}", displayName);
        return true;
    }

    public boolean remove(final String azoreaIdOrDisplayName) {
        final Optional<AzoreaFriend> existing = findByAzoreaIdOrDisplayName(azoreaIdOrDisplayName);
        if (existing.isEmpty()) return false;
        friends.remove(existing.get());
        save();
        LOGGER.info("Friend eliminado: {}", azoreaIdOrDisplayName);
        return true;
    }

    public Optional<AzoreaFriend> findByAzoreaId(final String azoreaId) {
        if (azoreaId == null) return Optional.empty();
        return friends.stream()
                .filter(f -> azoreaId.equals(f.azoreaId()))
                .findFirst();
    }

    public Optional<AzoreaFriend> findByDisplayName(final String displayName) {
        if (displayName == null) return Optional.empty();
        final String lower = displayName.toLowerCase().trim();
        return friends.stream()
                .filter(f -> f.displayName().toLowerCase().equals(lower))
                .findFirst();
    }

    public Optional<AzoreaFriend> findByAzoreaIdOrDisplayName(final String s) {
        if (s == null) return Optional.empty();
        Optional<AzoreaFriend> f = findByAzoreaId(s);
        if (f.isPresent()) return f;
        return findByDisplayName(s);
    }

    public boolean exists(final String azoreaId, final String displayName) {
        return findByAzoreaId(azoreaId).isPresent() || findByDisplayName(displayName).isPresent();
    }

    public int size() {
        return friends.size();
    }

    /**
     * § F5.2: actualiza online status de un friend via tracker lookup.
     * Si el friend tiene azoreaId, hace lookup; si no, no se puede.
     *
     * <p>§ F8.x: fallback a LAN discovery si el tracker falla (embedded trackers separados).
     */
    public void refreshOnlineStatus(final AzoreaFriend friend) {
        if (friend.azoreaId() == null) {
            return;  // legacy friend, no lookup possible
        }
        final var services = AzoreaMod.get().services();
        if (services == null || services.trackerClient() == null) return;
        // Intentar tracker primero.
        final var trackerResult = services.trackerClient().lookupFriend(friend.azoreaId());
        if (trackerResult.isPresent()) {
            final var status = trackerResult.get();
            final AzoreaFriend updated = friend.withOnline(status.online(), status.lastSeen());
            final AzoreaFriend finalUpdated = friend.publicKeyBase64() != null
                    ? updated : updated.withPublicKey(status.publicKeyBase64());
            updateFriend(finalUpdated);
            return;
        }
        // § F8.x: fallback a LAN discovery. Si el peer fue visto en LAN <15s, online.
        final var lanPeer = com.azorea.mod.v1211.tracker.lan.AzoreaLanDiscovery.get();
        if (lanPeer != null) {
            final var peer = lanPeer.getPeer(friend.azoreaId());
            if (peer != null && peer.isOnline()) {
                final AzoreaFriend lanUpdated = friend.withOnline(true, peer.lastSeenMs() / 1000L);
                updateFriend(lanUpdated);
                LOGGER.debug("Friend {} online via LAN discovery (bind={})",
                        friend.azoreaId(), peer.bindAddress());
            } else {
                updateFriend(friend.withOnline(false, 0L));
            }
        }
    }

    private void updateFriend(final AzoreaFriend updated) {
        final int idx = friends.indexOf(updated);
        if (idx >= 0) {
            friends.set(idx, updated);
            save();
        }
    }

    /**
     * § F5.2b: envía invite al friend. Requiere que el friend tenga publicKey
     * (lookup primero si no lo tiene).
     */
    public Optional<TrackerProtocol.InviteAck> sendInvite(
            final AzoreaFriend friend,
            final TrackerProtocol.Identity fromIdentity,
            final String gameId,
            final AzoreaInviteService.ConnectionInfo connectionInfo) {
        if (friend.publicKeyBase64() == null) {
            // Lookup primero.
            refreshOnlineStatus(friend);
            if (friend.publicKeyBase64() == null) {
                LOGGER.warn("Friend {} sin publicKey (no se puede invitar)", friend.displayName());
                return Optional.empty();
            }
        }
        final var services = AzoreaMod.get().services();
        if (services == null || services.invite() == null) return Optional.empty();
        final byte[] publicKey;
        try {
            publicKey = Base64.getDecoder().decode(friend.publicKeyBase64());
        } catch (IllegalArgumentException e) {
            LOGGER.warn("publicKey inválida para friend {}: {}", friend.displayName(), e.getMessage());
            return Optional.empty();
        }
        return services.invite().sendInvite(
                fromIdentity, friend.azoreaId(), gameId, publicKey, connectionInfo);
    }

    // ===== Persistencia =====

    private List<AzoreaFriend> load() {
        if (!Files.isRegularFile(storageFile)) {
            LOGGER.info("No hay friends.json previo; empezando con lista vacía.");
            return new ArrayList<>();
        }
        try (Reader r = Files.newBufferedReader(storageFile, StandardCharsets.UTF_8)) {
            final StorageDto dto = GSON.fromJson(r, StorageDto.class);
            if (dto == null || dto.friends == null) return new ArrayList<>();
            // Migrar DTOs a records v2.
            final List<AzoreaFriend> result = new ArrayList<>();
            for (final FriendDto f : dto.friends) {
                result.add(new AzoreaFriend(
                        f.azoreaId, f.displayName, f.publicKeyBase64,
                        f.addedAt, f.lastSeen, f.online));
            }
            LOGGER.info("Cargados {} friends desde {}", result.size(), storageFile);
            return result;
        } catch (Exception e) {
            LOGGER.error("Error leyendo {}: {}", storageFile, e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    private void save() {
        try {
            final Path parent = storageFile.getParent();
            if (parent != null && !Files.isDirectory(parent)) {
                Files.createDirectories(parent);
            }
            final StorageDto dto = new StorageDto();
            dto.friends = new ArrayList<>();
            for (final AzoreaFriend f : friends) {
                final FriendDto d = new FriendDto();
                d.azoreaId = f.azoreaId();
                d.displayName = f.displayName();
                d.publicKeyBase64 = f.publicKeyBase64();
                d.addedAt = f.addedAt();
                d.lastSeen = f.lastSeen();
                d.online = f.online();
                dto.friends.add(d);
            }
            try (Writer w = Files.newBufferedWriter(storageFile, StandardCharsets.UTF_8)) {
                GSON.toJson(dto, w);
            }
        } catch (IOException e) {
            LOGGER.error("Error guardando {}: {}", storageFile, e.getMessage(), e);
        }
    }

    /** DTO raíz del JSON. */
    private static final class StorageDto {
        @SerializedName("friends")
        public List<FriendDto> friends;
    }

    /** DTO de cada friend en JSON. */
    private static final class FriendDto {
        @SerializedName("azorea_id")
        public String azoreaId;
        /** v2: display_name. F4.5 usaba "name" como campo legacy — aceptamos ambos. */
        @SerializedName(value = "display_name", alternate = {"name"})
        public String displayName;
        @SerializedName("public_key_base64")
        public String publicKeyBase64;
        @SerializedName("added_at")
        public long addedAt;
        @SerializedName("last_seen")
        public long lastSeen;
        @SerializedName("online")
        public boolean online;
    }

    public Path getStorageFile() {
        return storageFile;
    }
}